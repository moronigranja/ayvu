package io.github.moronigranja.ayvu.spiketts

import android.content.Context
import android.os.Build

/**
 * Background thermal sampler shared by every spike harness (Kokoro D1/D2/D3,
 * Kitten/MOSS D3 legs, CosyVoice3 T3): polls thermal status + headroom every
 * 500 ms while a measurement runs and keeps the observed maxima.
 */
internal class ThermalProbe(
    private val context: Context,
    private val tag: String = "KokoroSpike",
) {
    var maxStatus = -1
    var maxHeadroom = 0.0f

    @Volatile private var running = false
    private var thread: Thread? = null

    fun start() {
        if (Build.VERSION.SDK_INT < 29) return
        running = true
        thread =
            Thread {
                try {
                    // android.os.ThermalManager is missing from the compile jar;
                    // the class exists at runtime on API 29+.
                    val cls = Class.forName("android.os.ThermalManager")
                    val tm = context.getSystemService("thermalservice")!!
                    val statusM = cls.getMethod("getCurrentThermalStatus")
                    val headroomM = cls.getMethod("getThermalHeadroom", Int::class.javaPrimitiveType)
                    while (running) {
                        try {
                            val s = statusM.invoke(tm) as Int
                            if (s > maxStatus) maxStatus = s
                            val h = headroomM.invoke(tm, 0) as Float
                            if (h > maxHeadroom) maxHeadroom = h
                        } catch (e: Exception) {
                            android.util.Log.d(tag, "thermal sample skipped: $e")
                        }
                        Thread.sleep(500)
                    }
                } catch (e: ClassNotFoundException) {
                    android.util.Log.d(tag, "thermal probe unavailable (no ThermalManager class): $e")
                } catch (e: Throwable) {
                    android.util.Log.d(tag, "thermal probe failed: $e")
                }
            }.also {
                it.isDaemon = true
                it.start()
            }
    }

    fun stop() {
        running = false
        thread?.join(2000)
    }
}
