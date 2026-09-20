package io.github.moronigranja.ayvu.llm

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.Closeable
import java.io.File

/**
 * The LFM2.5-1.2B-Instruct translator (decisions #161/#162): llama.cpp over the
 * downloaded GGUF — greedy, one rendered chat message per passage.
 *
 * Mirrors the retired `Sm100Translator` shape — a private constructor over a
 * native handle, a single-flight [translate], an explicit [close] for the
 * owning runtime's idle policy — with one addition, [userMessage], which builds
 * the prompt text the measured gate sent (`docs/prints/beam-spike/lfm12_gate.py`
 * lines 8-11): the wrapper never composes prompts at the call site.
 *
 * The native session holds the GGUF (~730 MB mapped) plus its KV cache, so it
 * is opened lazily by [io.github.moronigranja.ayvu.featureplayer.playback
 * .TranslateRuntime] and closed on idle — never resident during
 * original-language listening.
 */
class LlamaTranslator private constructor(
    private val handle: Long,
) : Closeable {
    private val mutex = Mutex()

    @Volatile private var closed = false

    /**
     * Translates [text] into [promptLanguage] (the language NAME the model
     * understands, e.g. "Brazilian Portuguese"), single-flight. Returns the
     * model's text with the gate's post-processing applied: surrounding
     * whitespace and quotes stripped. Blank on an empty generation — the
     * [io.github.moronigranja.ayvu.tts.translate.TranslatingEngine] degrade
     * contract handles that.
     */
    suspend fun translate(
        text: String,
        promptLanguage: String,
    ): String =
        mutex.withLock {
            check(!closed) { "translator is closed" }
            val bytes = nativeCompleteBytes(handle, userMessage(text, promptLanguage), MAX_TOKENS)
            String(bytes, Charsets.UTF_8).trim().removeSurrounding("\"")
        }

    /**
     * Frees the model, context and sampler. Idempotent — the idle-close timer
     * and process teardown may both reach it.
     */
    override fun close() {
        synchronized(this) {
            if (closed) return
            closed = true
        }
        nativeClose(handle)
    }

    companion object {
        /**
         * Greedy decode cap — the gate's `max_tokens` (lfm12_gate.py:14). The
         * measured passages generate ~44 tokens; 400 keeps headroom without
         * letting a misbehaving decode run to the context limit.
         */
        const val MAX_TOKENS = 400

        /**
         * The user message the measured gate sent, with the hardcoded target
         * replaced by [promptLanguage] (lfm12_gate.py:8-11). Pure, so the
         * prompt shape is unit-tested on the JVM without the native library.
         *
         * [text] is normalized first: a passage is one block of prose, so any
         * whitespace run inside it is source formatting (a hard-wrapped .txt or
         * Markdown import keeps its line breaks — `TextParser` joins wrapped
         * lines with `\n`), and the model ENDS ITS TURN at the first newline,
         * returning a translation of only the text before it with
         * `finish_reason: stop` — a silently truncated passage (`open-bugs.md`,
         * decisions #185). Collapsing to single spaces keeps the text the
         * translator sees equivalent for every legitimate input; the reader's
         * displayed text is untouched.
         */
        fun userMessage(
            text: String,
            promptLanguage: String,
        ): String = "Translate to $promptLanguage, reply only with the translation:\n\n${passage(text)}"

        /** One line of prose: every whitespace run (newlines included) becomes
         * a single space; surrounding blanks are dropped. */
        private fun passage(text: String): String = WHITESPACE.replace(text, " ").trim()

        private val WHITESPACE = Regex("\\s+")

        /**
         * Loads [modelFile] into a new native session pinned to [nThreads]
         * (the app's `ttsThreads` setting). [backendDir] is the directory the
         * packaged ggml CPU-backend modules live in — the app's
         * `applicationInfo.nativeLibraryDir`; the runtime picks the best arm64
         * kernel variant for the device from there.
         *
         * Throws [IllegalStateException] when the model cannot be loaded — the
         * caller treats that as the typed "translator unavailable" failure.
         */
        fun open(
            modelFile: File,
            nThreads: Int,
            backendDir: String,
        ): LlamaTranslator {
            // Deliberately here and not in a companion `init`: the prompt shape
            // above stays unit-testable on the JVM (no native library).
            System.loadLibrary("ayvu_llm")
            return LlamaTranslator(nativeLoadModel(modelFile.absolutePath, nThreads, backendDir))
        }

        @JvmStatic
        private external fun nativeLoadModel(
            path: String,
            nThreads: Int,
            backendDir: String,
        ): Long

        @JvmStatic
        private external fun nativeCompleteBytes(
            handle: Long,
            userMessage: String,
            maxTokens: Int,
        ): ByteArray

        @JvmStatic
        private external fun nativeClose(handle: Long)
    }
}
