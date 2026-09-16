package io.github.moronigranja.ayvu.tts.setup

import android.os.StatFs
import java.io.File
import javax.inject.Inject

/**
 * C1.2: the Android [StorageProbe] — free bytes on the filesystem hosting
 * the app's internal files dir (where packs + espeak land). A stale/cached
 * value would under- or over-name a shortfall, so every read probes StatFs
 * live (cheap, called on flow emissions).
 */
class StatFsStorageProbe
    @Inject
    constructor(
        private val dir: File,
    ) : StorageProbe {
        override fun availableBytes(): Long =
            try {
                StatFs(dir.path).availableBytes
            } catch (t: Throwable) {
                // A probe failure must never crash setup — report 0 so the shortfall
                // names the gap conservatively (a wrong "plenty of space" is worse).
                0L
            }
    }
