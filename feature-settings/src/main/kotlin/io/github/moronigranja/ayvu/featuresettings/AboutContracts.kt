package io.github.moronigranja.ayvu.featuresettings

/**
 * The app's own build identity, injected from `BuildConfig` by `:app` — one of
 * the two About-group seams (release 0.1.1). `feature-settings` has no
 * `BuildConfig` of its own (the versioned build facts live in the app module)
 * and never starts an Activity, so both ports are declared here and
 * implemented in `:app`: the same shape as
 * [io.github.moronigranja.ayvu.featureshare.ShareOpenHandler] and the
 * pack/offline-storage contracts bound by the composition root.
 */
data class AppInfo(
    /** `versionName` as built, e.g. "0.1.1". */
    val versionName: String,
)

/**
 * Opens an external link. A device with no browser handler (or one refusing
 * the intent) MUST be a silent no-op: the About rows stay rendered and the
 * version footer always shows — a tap never crashes the settings screen.
 */
fun interface LinkOpener {
    /** Opens [url]; no-op when the platform cannot handle it. */
    fun open(url: String)
}
