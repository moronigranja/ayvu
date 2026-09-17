package io.github.moronigranja.ayvu.featuresettings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.github.moronigranja.ayvu.ui.AyvuSpacing
import io.github.moronigranja.ayvu.ui.SectionHeader

// The two About links: the address shown on the row and the target opened.
private const val SOURCE_ADDRESS = "github.com/moronigranja/ayvu"
private const val SOURCE_URL = "https://$SOURCE_ADDRESS"
private const val NOTICE_ADDRESS = "github.com/moronigranja/ayvu/blob/main/NOTICE.md"
private const val NOTICE_URL = "https://$NOTICE_ADDRESS"

// The in-app row's detail: the documents it opens ship inside this build.
private const val LICENCES_DETAIL = "Bundled in the app — readable offline"

// The setup card's own claim (SetupScreen.PrivacyCard), with the pack download
// named because it is the only network use this app makes.
private const val PRIVACY =
    "Everything runs on this device. No account, no telemetry, no cloud processing. " +
        "The only network use is downloading the free speech and OCR packs."

/**
 * Release 0.1.1: the Settings "About" group — build identity, licence +
 * source, third-party notices and the on-device claim. Sits last in the
 * settings list, after Backup & restore.
 *
 * [versionName] arrives through the `AppInfo` seam (this module has no
 * `BuildConfig` of its own) and the version line renders unconditionally: a
 * device without a browser still shows which build it is running, and the link
 * rows then do nothing through the [LinkOpener] seam instead of crashing.
 * Rows are 48 dp targets with an explicit click label for TalkBack (B4).
 *
 * Audit B: [onOpenLicences] opens the in-app Licences surface, which renders
 * the GPL-3.0 text and the notices this APK carries (`assets/LICENSE` +
 * `assets/NOTICE.md`). The two outbound link rows stay — the bundled copy is
 * the one a recipient always has, the links are the online convenience.
 */
@Composable
fun aboutSection(
    versionName: String,
    onOpenLink: (String) -> Unit,
    onOpenLicences: () -> Unit,
) {
    Column {
        SectionHeader("About", Modifier.padding(top = AyvuSpacing.LG, bottom = AyvuSpacing.XS))
        Text(
            "Ayvu $versionName",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(horizontal = AyvuSpacing.XS),
        )
        aboutRow(
            title = "Licences (GPL-3.0)",
            detail = LICENCES_DETAIL,
            clickLabel = "Open the bundled licences",
            onClick = onOpenLicences,
        )
        aboutRow(
            title = "Source code (GPL-3.0)",
            detail = SOURCE_ADDRESS,
            clickLabel = "Open in browser",
            onClick = { onOpenLink(SOURCE_URL) },
        )
        aboutRow(
            title = "Third-party notices",
            detail = NOTICE_ADDRESS,
            clickLabel = "Open in browser",
            onClick = { onOpenLink(NOTICE_URL) },
        )
        Text(
            PRIVACY,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = AyvuSpacing.XS, vertical = AyvuSpacing.XS),
        )
    }
}

/** One About row: [title] + the [detail] under it, as a 48 dp target. */
@Composable
private fun aboutRow(
    title: String,
    detail: String,
    clickLabel: String,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .clickable(
                    role = Role.Button,
                    onClickLabel = clickLabel,
                    onClick = onClick,
                ).padding(horizontal = AyvuSpacing.XS, vertical = AyvuSpacing.SM),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(
                detail,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
    }
}
