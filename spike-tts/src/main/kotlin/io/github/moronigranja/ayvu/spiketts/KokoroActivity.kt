package io.github.moronigranja.ayvu.spiketts

import android.app.Activity
import android.os.Bundle
import android.util.Log
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Interactive runner for the Kokoro on-device benchmark (decisions #30).
 * Requires an unlocked screen: a launched-but-keyguarded activity is frozen
 * by the process freezer and its threads stall in `__refrigerator`. For
 * locked/off screens use the instrumented `KokoroDeviceBenchmarkTest`.
 * The measurement body lives in [KokoroBenchmarkRunner].
 */
class KokoroActivity : Activity() {
    companion object {
        private const val TAG = "KokoroSpike"
    }

    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val scroll = ScrollView(this)
        // A multi-minute benchmark must not let the screen sleep: a demoted
        // foreground app is reclaimed by lmkd under memory pressure.
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        status = TextView(this)
        status.textSize = 13f
        status.text = "Kokoro spike starting…"
        scroll.addView(
            status,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT),
        )
        setContentView(scroll)
        Thread {
            val ok =
                KokoroBenchmarkRunner(applicationContext).run { line ->
                    Log.d(TAG, line)
                    runOnUiThread { status.text = status.text.toString() + "\n" + line }
                }
            status.post { if (!ok) status.text = status.text.toString() + "\nFAILED" }
        }.start()
    }
}
