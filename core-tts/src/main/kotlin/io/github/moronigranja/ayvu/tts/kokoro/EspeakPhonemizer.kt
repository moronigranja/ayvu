package io.github.moronigranja.ayvu.tts.kokoro

import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.ptr.PointerByReference
import java.io.File
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * [Phonemizer] driving the system espeak-ng shared library through JNA —
 * a faithful port of the pipeline kokoro-onnx gets from phonemizer's espeak
 * backend (phonemize(text, lang, preserve_punctuation=True, with_stress=True)
 * with the default separator and strip=False):
 *
 * 1. the text is split into non-blank lines; punctuation is peeled off each
 *    line into marks (default mark set ";:,.!?¡¿—…\"«»“”(){}[]", a comma or
 *    dot between two digits stays with the text — decimals),
 * 2. every chunk goes through espeak_TextToPhonemes (UTF-8 mode, IPA +
 *    stress, '_' phoneme separator) until the pointer is consumed,
 * 3. espeak's line noise is post-processed exactly like phonemizer
 *    (_+ collapse, "_ " fix, per-word trailing '_' removal, stress kept),
 * 4. the marks are spliced back between the chunks,
 * 5. lines are joined with '\n'.
 *
 * espeak-ng keeps process-global state, so every native call is serialized on
 * an internal lock (inference runs outside this class and stays concurrent).
 *
 * Backend resolution matches phonemizer: the default library is located
 * through the system loader; an explicit [libraryPath] file wins. The espeak
 * data directory is discovered next to the library and under the standard
 * prefixes, and falls back to letting espeak find its own data.
 */
class EspeakPhonemizer(
    libraryPath: String = DEFAULT_LIBRARY,
    dataPath: String? = null,
) : Phonemizer,
    AutoCloseable {
    private val lock = ReentrantLock()
    private val lib: EspeakLibrary
    private val languageToIdentifier: Map<String, String>
    private var currentVoice: String? = null
    private var closed = false

    // espeak_Initialize may keep the data path pointer; hold the buffer for the object's life.
    @Suppress("unused")
    private val dataMemory: Memory?

    init {
        lock.withLock {
            lib = Native.load(libraryPath, EspeakLibrary::class.java)
            dataMemory =
                dataPath?.let { path ->
                    val bytes = path.toByteArray(Charsets.UTF_8)
                    Memory((bytes.size + 1).toLong()).also { memory ->
                        memory.write(0, bytes, 0, bytes.size)
                        memory.setByte(bytes.size.toLong(), 0)
                    }
                }
            val sampleRate = lib.espeak_Initialize(AUDIO_OUTPUT_SYNCHRONOUS, 0L, dataMemory, 0)
            if (sampleRate <= 0) {
                lib.espeak_Terminate()
                throw PhonemizeException("espeak_Initialize failed (sampleRate=$sampleRate)")
            }
            languageToIdentifier = scanVoices()
        }
    }

    override fun supportedLanguages(): Set<String> = languageToIdentifier.keys

    override fun phonemize(
        text: String,
        language: String,
    ): String {
        lock.withLock {
            check(!closed) { "phonemizer is closed" }
            val identifier =
                languageToIdentifier[language]
                    ?: throw PhonemizeException(
                        "language '$language' is not supported by this espeak-ng installation " +
                            "(available: ${languageToIdentifier.keys.sorted().joinToString(", ")})",
                    )
            if (currentVoice != identifier) {
                if (lib.espeak_SetVoiceByName(identifier) != 0) {
                    throw PhonemizeException("failed to load espeak-ng voice '$identifier'")
                }
                currentVoice = identifier
            }

            // phonemizer: str2list -> line.strip('\n') -> drop blank lines
            val lines =
                text
                    .trim { it == '\n' }
                    .split('\n')
                    .filter { it.isNotBlank() }

            val punctuated = mutableListOf<String>()
            for (line in lines) {
                val (chunks, marks) = PunctuationPreserve.preserve(line)
                val phonemized = chunks.map { postprocessLine(espeakPhonemes(it)) }
                punctuated += PunctuationPreserve.restore(phonemized, marks)
            }
            return punctuated.joinToString("\n")
        }
    }

    private fun espeakPhonemes(chunk: String): String {
        val utf8 = chunk.toByteArray(Charsets.UTF_8)
        val memory = Memory((utf8.size + 1).toLong())
        memory.write(0, utf8, 0, utf8.size)
        memory.setByte(utf8.size.toLong(), 0)
        val textIn = PointerByReference(memory)

        val results = mutableListOf<String>()
        while (true) {
            val phonemes = lib.espeak_TextToPhonemes(textIn, TEXT_MODE_UTF8, PHONEMES_MODE)
            if (phonemes != null && phonemes.getByte(0) != 0.toByte()) {
                // The returned buffer is static and reused: copy before the next call.
                results += phonemes.getString(0, Charsets.UTF_8.name())
            }
            val remaining = textIn.value
            if (remaining == null || remaining == Pointer.NULL || remaining.getByte(0) == 0.toByte()) break
        }
        return results.joinToString(" ")
    }

    private fun postprocessLine(line: String): String {
        // phonemizer _postprocess_line: espeak can split one call over lines
        var out = line.trim().replace('\n', ' ').replace("  ", " ")
        // espeak-ng bug workaround: stray phoneme separators after a word
        // (https://github.com/espeak-ng/espeak-ng/issues/694)
        out = out.replace(Regex("_+"), "_").replace(Regex("_ "), " ")
        // language_switch with 'keep-flags' is a pass-through (no-op here)
        return out
            .split(' ')
            .joinToString(" ") { word ->
                // strip=False, tie=None: espeak's '_' phoneme separator is added
                // back per word and then replaced by the empty phone separator.
                word.trim().replace("_", "")
            }
    }

    /** phonemizer `set_voice`: the first (non-mbrola) voice per language. */
    private fun scanVoices(): Map<String, String> {
        val result = linkedMapOf<String, String>()
        val list = lib.espeak_ListVoices(null) ?: return result
        var index = 0L
        while (true) {
            val voicePtr = list.getPointer(index * Native.POINTER_SIZE)
            index += 1
            if (voicePtr == null || voicePtr == Pointer.NULL) break
            val voice = Structure.newInstance(EspeakVoiceStruct::class.java, voicePtr)
            voice.read()
            val identifier = voice.identifier?.getString(0, Charsets.UTF_8.name()) ?: continue
            if (identifier.startsWith("mb/")) continue
            val language = decodeFirstLanguage(voice.languages) ?: continue
            if (language.isNotEmpty() && language !in result) {
                result[language] = identifier
            }
        }
        return result
    }

    private fun decodeFirstLanguage(languages: Pointer?): String? {
        if (languages == null || languages == Pointer.NULL) return null
        // (priority byte, UTF-8 string, NUL)*, NUL; phonemizer takes the first string.
        return try {
            val bytes = mutableListOf<Byte>()
            while (true) {
                val b = languages.getByte(bytes.size.toLong())
                if (b == 0.toByte()) break
                bytes += b
            }
            // Skip the leading priority byte.
            if (bytes.size <= 1) null else String(bytes.subList(1, bytes.size).toByteArray(), Charsets.UTF_8)
        } catch (e: IndexOutOfBoundsException) {
            null
        }
    }

    override fun close() {
        lock.withLock {
            if (!closed) {
                closed = true
                lib.espeak_Terminate()
            }
        }
    }

    private interface EspeakLibrary : Library {
        fun espeak_Initialize(
            output: Int,
            buflength: Long,
            path: Pointer?,
            options: Int,
        ): Int

        fun espeak_Terminate()

        fun espeak_SetVoiceByName(name: String?): Int

        fun espeak_ListVoices(voiceSpec: Pointer?): Pointer?

        fun espeak_TextToPhonemes(
            textIn: PointerByReference?,
            textmode: Int,
            phonememode: Int,
        ): Pointer?
    }

    companion object {
        // espeak-ng speak_lib.h: AUDIO_OUTPUT_SYNCHRONOUS = 0x02, espeakCHARS_UTF8 = 1,
        // phoneme mode bit 1 = IPA, bits 8-23 = separator char ('_').
        private const val AUDIO_OUTPUT_SYNCHRONOUS = 0x02
        private const val TEXT_MODE_UTF8 = 1
        private const val PHONEMES_MODE = ('_'.code shl 8) or 0x02

        /** Let JNA/Native.findLibrary resolve "espeak-ng"/"espeak" through the system loader. */
        const val DEFAULT_LIBRARY: String = "espeak-ng"

        fun load(
            libraryPath: String = DEFAULT_LIBRARY,
            dataPath: String? = defaultDataPath(),
        ): EspeakPhonemizer = EspeakPhonemizer(libraryPath, dataPath)

        private fun defaultDataPath(): String? {
            val candidates =
                listOf(
                    "/usr/share/espeak-ng-data",
                    "/usr/local/share/espeak-ng-data",
                    "/usr/lib/espeak-ng-data",
                )
            return candidates.firstOrNull { File(it).isDirectory }
        }
    }
}

/** espeak-ng `espeak_VOICE` (speak_lib.h): 3 pointers, 4 bytes, int score, spare. */
@Structure.FieldOrder("name", "languages", "identifier", "gender", "age", "variant", "xx1", "score", "spare")
internal class EspeakVoiceStruct : Structure() {
    @JvmField var name: Pointer? = null

    @JvmField var languages: Pointer? = null

    @JvmField var identifier: Pointer? = null

    @JvmField var gender: Byte = 0

    @JvmField var age: Byte = 0

    @JvmField var variant: Byte = 0

    @JvmField var xx1: Byte = 0

    @JvmField var score: Int = 0

    @JvmField var spare: Pointer? = null
}

/**
 * phonemizer's Punctuation class: strip marks into a side list (positions
 * B/E/I/A), phonemize the chunks, splice the marks back.
 */
private object PunctuationPreserve {
    // phonemizer Punctuation.default_marks()
    private const val MARKS = ";:,.!?¡¿—…\"«»“”(){}[]"
    private const val DECIMALS = ",."

    private val marksRegex: Regex =
        run {
            val others = MARKS.filter { it !in DECIMALS }
            val escapedClass = buildString { for (c in others) append(escapeClassChar(c)) }
            val alternatives =
                buildList {
                    if (escapedClass.isNotEmpty()) add("[$escapedClass]")
                    for (decimal in DECIMALS) {
                        // A decimal separator between two digits is not punctuation (phonemizer).
                        add("(?<![0-9])[$decimal]")
                        add("[$decimal](?![0-9])")
                    }
                }
            Regex("(\\s*(?:${alternatives.joinToString("|")})+\\s*)+")
        }

    private fun escapeClassChar(c: Char): String =
        when (c) {
            '\\', '[', ']', '^', '-', '&' -> "\\$c"
            else -> c.toString()
        }

    data class Mark(
        val index: Int,
        val text: String,
        val position: Char,
    )

    /** Splits one line into chunks and marks; empties are dropped by the caller. */
    fun preserve(line: String): Pair<List<String>, List<Mark>> {
        val matches = marksRegex.findAll(line).toList()
        if (matches.isEmpty()) return listOf(line) to emptyList()
        if (matches.size == 1 && matches[0].value == line) {
            return emptyList<String>() to listOf(Mark(0, line, 'A'))
        }
        val marks =
            matches.mapIndexed { i, match ->
                val position =
                    when {
                        i == 0 && line.startsWith(match.value) -> 'B'
                        i == matches.lastIndex && line.endsWith(match.value) -> 'E'
                        else -> 'I'
                    }
                Mark(0, match.value, position)
            }
        val chunks = mutableListOf<String>()
        var remaining = line
        for (mark in marks) {
            // Literal split (python line.split(mark.mark)): Regex.escape would
            // produce \Q..\E quoting, which the String overload treats literally.
            val parts = remaining.split(mark.text, limit = 2)
            chunks += parts[0]
            remaining = parts.getOrElse(1) { "" }
        }
        chunks += remaining
        // python: [line for line in preserved_text if line] — empty chunks
        // would phonemize to "" and leak stray spaces into the restored lines.
        return chunks.filter { it.isNotEmpty() } to marks
    }

    /**
     * phonemizer Punctuation.restore with Separator('', '', ' ') and strip=False:
     * re-inserts the marks between the phonemized chunks, each line joined with ' '.
     */
    fun restore(
        phonemized: List<String>,
        marks: List<Mark>,
    ): List<String> {
        val text = phonemized.toMutableList()
        val queue = marks.toMutableList()
        val out = mutableListOf<String>()
        var pos = 0
        while (text.isNotEmpty() || queue.isNotEmpty()) {
            when {
                queue.isEmpty() -> {
                    for (line in text) out += if (line.endsWith(" ")) line else "$line "
                    text.clear()
                }
                text.isEmpty() -> {
                    out += queue.joinToString("") { it.text }
                    queue.clear()
                }
                else -> {
                    val mark = queue.first()
                    if (mark.index == pos) {
                        queue.removeAt(0)
                        val markText = mark.text
                        if (text[0].endsWith(" ")) text[0] = text[0].dropLast(1)
                        when (mark.position) {
                            'B' -> text[0] = markText + text[0]
                            'E' -> {
                                out += text.removeAt(0) + markText + " "
                                pos += 1
                            }
                            'A' -> {
                                out += markText + " "
                                pos += 1
                            }
                            else -> { // 'I' — the mark hangs between two chunks
                                if (text.size == 1) {
                                    text[0] = text[0] + markText
                                } else {
                                    val first = text.removeAt(0)
                                    text[0] = first + markText + text[0]
                                }
                            }
                        }
                    } else {
                        out += text.removeAt(0)
                        pos += 1
                    }
                }
            }
        }
        return out
    }
}
