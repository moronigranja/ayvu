package io.github.moronigranja.ayvu.featuresettings

import java.io.IOException
import java.io.InputStream

/**
 * One licence document bundled in the APK's assets by `:app`'s
 * `copyLicenceAssets` task — the repo-root `LICENSE` and `NOTICE.md`, copied
 * at build time so the app never carries a second, drifting copy of the text.
 * [assetName] is the asset name AND the repo-root file name.
 */
data class LicenceDocument(
    /** The heading the About → Licences viewer shows. */
    val title: String,
    val assetName: String,
)

/**
 * The documents the APK ships, in viewer order: the app's own GPL-3.0 licence
 * first, then the third-party attributions. GPL §4 requires a distributed
 * binary to carry the licence text, so this surface never depends on a browser
 * or a network.
 */
object LicenceDocuments {
    val license = LicenceDocument("GNU General Public License v3.0", "LICENSE")
    val notice = LicenceDocument("Third-party notices", "NOTICE.md")
    val all = listOf(license, notice)
}

/** The outcome of reading one bundled document — never a blank page. */
sealed interface LicenceText {
    val document: LicenceDocument

    data class Loaded(
        override val document: LicenceDocument,
        val text: String,
    ) : LicenceText

    /** The asset is missing, unreadable or empty: the viewer names the
     * document and the reason instead of rendering nothing. */
    data class Failed(
        override val document: LicenceDocument,
        val reason: String,
    ) : LicenceText
}

/**
 * Opens one APK asset by name — the reader's single platform edge, so the read
 * itself stays host-testable. `:app` binds it over `Context.assets`, whose
 * `open` throws [IOException] for an absent asset.
 */
fun interface LicenceAssetSource {
    @Throws(IOException::class)
    fun open(assetName: String): InputStream
}

/**
 * Reads the bundled licence documents ([LicenceDocuments.all]) as UTF-8, one
 * typed result per document. A read that fails — or that yields nothing but
 * whitespace — is a [LicenceText.Failed], never a silently empty page.
 * Failures are caught rather than propagated because this path renders inside
 * the settings screen: a bad build must show the missing licence, not take the
 * screen down.
 */
class LicenceReader(
    private val assets: LicenceAssetSource,
) {
    fun read(document: LicenceDocument): LicenceText =
        try {
            val text = assets.open(document.assetName).use { it.readBytes().toString(Charsets.UTF_8) }
            if (text.isBlank()) {
                LicenceText.Failed(document, "the bundled asset is empty")
            } else {
                LicenceText.Loaded(document, text)
            }
        } catch (e: Exception) {
            LicenceText.Failed(document, e.message ?: "the bundled asset could not be read")
        }

    fun readAll(): List<LicenceText> = LicenceDocuments.all.map(::read)
}
