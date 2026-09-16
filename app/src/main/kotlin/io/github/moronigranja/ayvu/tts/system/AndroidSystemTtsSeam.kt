package io.github.moronigranja.ayvu.tts.system

import android.content.Context
import android.media.AudioAttributes
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The real [SystemTtsSeam] over android.speech.tts (C1.5). Synthesis goes
 * through `synthesizeToFile` (WAV) then parses to mono 16-bit PCM — the
 * reliable route across engine codecs (raw `AudioFormat` PCM on the
 * utterance stream is device-lottery); the parse fails loudly
 * (`Unavailable`) on a non-PCM/non-16-bit file rather than guessing.
 *
 * Positions stay book-time because audio length maps 1:1 to the produced
 * samples (speed is ignored in v1 — the system engine's rate is a device
 * setting, decisions #102).
 */
@Singleton
class AndroidSystemTtsSeam
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) : SystemTtsSeam {
        private val lock = Any()
        private var tts: TextToSpeech? = null
        private var languages: Set<String>? = null
        private val utteranceCounter = AtomicInteger()

        override fun availableLanguages(): Set<String> {
            languages?.let { return it }
            synchronized(lock) {
                languages?.let { return it }
                val engine = engineLocked() ?: return emptySet()
                val found =
                    engine.availableLanguages
                        .mapNotNull { it.language.takeIf(String::isNotBlank) }
                        .toSet()
                languages = found
                return found
            }
        }

        override suspend fun synthesizeToPcm(
            text: String,
            language: String?,
        ): TtsSynthesis =
            withContext(Dispatchers.IO) {
                val engine = engine() ?: return@withContext TtsSynthesis.Unavailable

                if (language != null) {
                    val status = engine.setLanguage(Locale.forLanguageTag(language))
                    if (status == TextToSpeech.LANG_MISSING_DATA || status == TextToSpeech.LANG_NOT_SUPPORTED) {
                        return@withContext TtsSynthesis.Unavailable
                    }
                }
                engine.setAudioAttributes(
                    AudioAttributes
                        .Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )

                val dir = File(context.cacheDir, "system-tts").apply { mkdirs() }
                val target = File(dir, "utt-${utteranceCounter.incrementAndGet()}.wav")
                val done = CountDownLatch(1)
                var ok = false
                engine.setOnUtteranceProgressListener(
                    object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) {}

                        override fun onDone(utteranceId: String?) {
                            ok = true
                            done.countDown()
                        }

                        @Deprecated("Deprecated in Java")
                        override fun onError(utteranceId: String?) {
                            done.countDown()
                        }

                        override fun onError(
                            utteranceId: String?,
                            errorCode: Int,
                        ) {
                            done.countDown()
                        }
                    },
                )

                val code =
                    try {
                        engine.synthesizeToFile(text, null, target, "ayvu-${target.name}")
                    } catch (t: Throwable) {
                        TextToSpeech.ERROR
                    }
                if (code != TextToSpeech.SUCCESS) {
                    target.delete()
                    return@withContext TtsSynthesis.Unavailable
                }
                // Some engines report onDone before the file is fully flushed;
                // give the write a beat and verify by parse (which also fails on
                // a truncated file).
                if (!done.await(60, TimeUnit.SECONDS)) {
                    target.delete()
                    return@withContext TtsSynthesis.Unavailable
                }
                val parsed = parseWavToPcm(target)
                target.delete()
                parsed
            }

        private fun engine(): TextToSpeech? = synchronized(lock) { engineLocked() }

        private fun engineLocked(): TextToSpeech? {
            tts?.let { return it }
            val ready = CountDownLatch(1)
            var ok = false
            val candidate =
                try {
                    TextToSpeech(context) { status ->
                        ok = status == TextToSpeech.SUCCESS
                        ready.countDown()
                    }
                } catch (t: Throwable) {
                    return null
                }
            if (!ready.await(5, TimeUnit.SECONDS) || !ok) {
                runCatching { candidate.shutdown() }
                return null
            }
            tts = candidate
            return candidate
        }

        /** Parses a PCM16 little-endian WAV into mono samples; null on any
         * non-PCM/unsupported layout (never guesses). */
        private fun parseWavToPcm(file: File): TtsSynthesis {
            try {
                val bytes = file.readBytes()
                if (bytes.size < 44 || bytes[0] != 'R'.code.toByte() || bytes[1] != 'I'.code.toByte() ||
                    bytes[2] != 'F'.code.toByte() || bytes[3] != 'F'.code.toByte()
                ) {
                    return TtsSynthesis.Unavailable
                }
                var offset = 12
                var sampleRate = 0
                var channels = 1
                var bits = 16
                var dataStart = -1
                var dataLen = 0
                while (offset + 8 <= bytes.size) {
                    val id = String(bytes, offset, 4, Charsets.US_ASCII)
                    val size = leInt(bytes, offset + 4)
                    val body = offset + 8
                    if (body + size > bytes.size) return TtsSynthesis.Unavailable
                    when (id) {
                        "fmt " -> {
                            val format = leShort(bytes, body)
                            if (format != 1) return TtsSynthesis.Unavailable // PCM only
                            channels = leShort(bytes, body + 2)
                            sampleRate = leInt(bytes, body + 4)
                            bits = leShort(bytes, body + 14)
                        }
                        "data" -> {
                            dataStart = body
                            dataLen = size
                        }
                    }
                    offset = body + size
                }
                if (dataStart < 0 || dataLen <= 0 || sampleRate <= 0 || bits != 16 || channels !in 1..2) {
                    return TtsSynthesis.Unavailable
                }
                val sampleCount = dataLen / 2 / channels
                val mono = ByteArray(sampleCount * 2)
                val src = ByteBuffer.wrap(bytes, dataStart, dataLen).order(ByteOrder.LITTLE_ENDIAN)
                val dst = ByteBuffer.wrap(mono).order(ByteOrder.LITTLE_ENDIAN)
                for (i in 0 until sampleCount) {
                    if (channels == 1) {
                        dst.putShort(src.getShort())
                    } else {
                        val l = src.getShort().toInt()
                        val r = src.getShort().toInt()
                        dst.putShort(((l + r) / 2).toShort())
                    }
                }
                return if (sampleCount == 0) TtsSynthesis.Unavailable else TtsSynthesis.Audio(mono, sampleRate)
            } catch (t: Throwable) {
                return TtsSynthesis.Unavailable
            }
        }

        private fun leInt(
            b: ByteArray,
            o: Int,
        ): Int =
            (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8) or
                ((b[o + 2].toInt() and 0xFF) shl 16) or ((b[o + 3].toInt() and 0xFF) shl 24)

        private fun leShort(
            b: ByteArray,
            o: Int,
        ): Int = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)
    }
