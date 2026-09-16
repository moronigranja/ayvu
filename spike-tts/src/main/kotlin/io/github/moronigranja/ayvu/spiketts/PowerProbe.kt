package io.github.moronigranja.ayvu.spiketts

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Process

/**
 * Battery + core-placement sampler for the thread-sweep leg (decisions #147).
 *
 * Every 500 ms it records the instantaneous battery current
 * (`BATTERY_PROPERTY_CURRENT_NOW`, µA) against the sticky battery intent's
 * voltage and plug state, keeping per-tick accumulators so a leg can report
 * mean power and energy per hour of synthesized audio.
 *
 * **Validity rule:** while the device is on USB/AC the battery current is a
 * charge current, not a load signal — a strong charger supplies the SoC
 * without the battery current moving. Samples taken while plugged are
 * therefore counted separately, and every reported power number is derived
 * ONLY from on-battery samples ([unpluggedSamples]); [unpluggedFraction]
 * says how much of the leg that was. A leg with 0 unplugged samples reports
 * power 0 and must be read as "energy not measured".
 *
 * It also samples which CPU the CALLING thread occupies (`/proc/self/task/<tid>/stat`
 * field 39). With T=1 that thread IS the inference thread, and big/little
 * placement is the first thing that explains a single-thread number that
 * falls off the scaling curve. Placement is a property of the caller, so
 * start this probe from the thread that runs the inference loop.
 */
internal class PowerProbe(
    private val context: Context,
    private val tag: String = "KokoroSpike",
) {
    /** Mean power over on-battery samples, mW. 0 when there were none. */
    var meanPowerMw = 0.0
        private set

    /** Signed mean current over all samples, mA (device sign convention kept). */
    var meanCurrentMa = 0.0
        private set

    var peakAbsCurrentMa = 0.0
        private set

    var unpluggedSamples = 0
        private set

    var totalSamples = 0
        private set

    /**
     * Peak battery temperature, °C. Android 14+ hides `ThermalManager` from
     * apps (SDK 37: the class does not resolve at all — [ThermalProbe] reports
     * status -1 there), so battery temperature is the in-app thermal signal
     * that survives: it is what a reader feels through the back cover.
     */
    var maxBatteryTempC = 0.0
        private set

    val cpusObserved = linkedSetOf<Int>()

    val unpluggedFraction: Double
        get() = if (totalSamples == 0) 0.0 else unpluggedSamples.toDouble() / totalSamples

    private val tid = Process.myTid()

    companion object {
        /** True while USB/AC/wireless power is attached (sticky battery intent). */
        fun isPlugged(context: Context): Boolean {
            val sticky = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            return (sticky?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
        }
    }

    @Volatile private var running = false
    private var thread: Thread? = null

    fun start() {
        if (thread != null) return
        running = true
        thread =
            Thread {
                val battery = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
                var unpluggedPowerMw = 0.0
                var currentSumUa = 0L
                var currentSamples = 0
                while (running) {
                    try {
                        val sticky = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                        val plugged = (sticky?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
                        val voltageV = (sticky?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1) ?: -1) / 1000.0
                        val currentUa = battery?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW) ?: 0
                        val tempC = (sticky?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) ?: -1) / 10.0
                        if (tempC > maxBatteryTempC) maxBatteryTempC = tempC
                        totalSamples++
                        if (currentUa != Int.MIN_VALUE) {
                            currentSamples++
                            currentSumUa += currentUa
                            meanCurrentMa = currentSumUa / 1000.0 / currentSamples
                            val absMa = kotlin.math.abs(currentUa) / 1000.0
                            if (absMa > peakAbsCurrentMa) peakAbsCurrentMa = absMa
                            if (!plugged && voltageV > 0.0) {
                                unpluggedSamples++
                                unpluggedPowerMw += absMa * voltageV
                                meanPowerMw = unpluggedPowerMw / unpluggedSamples
                            }
                        }
                        cpuOfThread()?.let { cpusObserved += it }
                    } catch (t: Throwable) {
                        android.util.Log.d(tag, "power sample skipped: $t")
                    }
                    Thread.sleep(500)
                }
            }.also {
                it.isDaemon = true
                it.start()
            }
    }

    fun stop() {
        running = false
        thread?.join(2000)
        thread = null
    }

    /** `processor` (field 39) of this thread from /proc/self/task/<tid>/stat. */
    private fun cpuOfThread(): Int? {
        val line =
            runCatching { java.io.File("/proc/self/task/$tid/stat").readText() }.getOrNull() ?: return null
        val after = line.substringAfter(")", missingDelimiterValue = "").trim()
        if (after.isEmpty()) return null
        // After the comm field the columns start at `state` (field 3), so the
        // processor column (39) is index 36.
        val fields = after.split(' ')
        return fields.getOrNull(36)?.toIntOrNull()
    }
}
