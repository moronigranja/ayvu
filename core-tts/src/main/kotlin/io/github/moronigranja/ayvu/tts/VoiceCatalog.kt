package io.github.moronigranja.ayvu.tts

import io.github.moronigranja.ayvu.tts.kokoro.KokoroPacks
import io.github.moronigranja.ayvu.tts.kokoro.KokoroVoiceBank
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The voice names for the picker (V1, moved to core-tts in C1.3 — pack/voice
 * enumeration is core-tts domain): `KokoroVoiceBank` parses the whole 28 MiB
 * voices pack to enumerate names, so that work is done once, lazily, off the
 * UI thread, and cached for the process. An absent/unverified voices pack
 * yields an empty catalog (the UI shows the download action instead).
 *
 * Provided by the app's pack wiring (`PackModule`, C1.4) as a singleton now,
 * replacing the feature-settings provider that this class's old Hilt
 * annotations backed.
 */
class VoiceCatalog(
    private val cache: PackCache,
) {
    @Volatile private var names: Set<String>? = null

    suspend fun names(): Set<String> {
        names?.let { return it }
        val loaded =
            withContext(Dispatchers.IO) {
                runCatching {
                    val file: File = cache.targetFile(KokoroPacks.voices)
                    if (!cache.isVerified(KokoroPacks.voices)) {
                        emptySet()
                    } else {
                        KokoroVoiceBank.load(file).voiceNames
                    }
                }.getOrDefault(emptySet())
            }
        names = loaded
        return loaded
    }

    fun invalidate() {
        names = null
    }
}
