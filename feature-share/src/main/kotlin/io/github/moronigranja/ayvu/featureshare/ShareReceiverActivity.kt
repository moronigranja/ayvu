package io.github.moronigranja.ayvu.featureshare

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import dagger.hilt.android.AndroidEntryPoint
import io.github.moronigranja.ayvu.ebook.IntakeRouting
import io.github.moronigranja.ayvu.persistence.AppSettings
import io.github.moronigranja.ayvu.persistence.ThemeMode
import io.github.moronigranja.ayvu.ui.AyvuTheme
import javax.inject.Inject

/**
 * The ACTION_SEND gateway (S2): a launcher-less, exported activity that
 * receives shares from any app. Text and image shares resolve against the
 * library (S2/S3); book-file shares (F4) are triaged — a supported ebook
 * forwards to the feature-library import gateway without leaving the share
 * sheet, kfx/DRM/unsupported get typed guidance there, and text/image shares
 * keep the existing resolve path. Refresh cached settings on entry (a share
 * can boot the process; V1 settings must apply immediately).
 */
@AndroidEntryPoint
class ShareReceiverActivity : ComponentActivity() {
    @Inject lateinit var appSettings: AppSettings

    @Inject lateinit var openHandler: ShareOpenHandler

    private val viewModel: ShareViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val settingsState by appSettings.state.collectAsState()
            val dark =
                when (settingsState.theme) {
                    ThemeMode.SYSTEM -> isSystemInDarkTheme()
                    ThemeMode.LIGHT -> false
                    ThemeMode.DARK -> true
                }
            AyvuTheme(darkTheme = dark) {
                LaunchedEffect(Unit) { appSettings.reload() }
                ShareResultScreen(
                    onClose = { finish() },
                    onListen = { found ->
                        // S3: the app-side handler opens MainActivity at the passage.
                        openHandler.open(OpenTarget(found.bookId, found.chapterIndex, found.passageIndex))
                        finish()
                    },
                )
            }
        }
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        val stream = intent.parcelableStream()
        val displayName = stream?.let(::queryDisplayName)
        when (IntakeRouting.routeSend(hasStream = stream != null, mimeType = intent.type, displayName = displayName)) {
            // F4: a book file (or ebook-MIME stream) — forward to the import
            // gateway (feature-library) with the same URI + grant; the gateway
            // runs the one shared batch importer and shows guidance/summary.
            is IntakeRouting.SendRoute.Import -> {
                if (stream != null) {
                    val forward =
                        Intent(IntakeRouting.ACTION_IMPORT_BOOK).apply {
                            setPackage(packageName) // package-qualified: no share-sheet duplicate, no class ref (A6)
                            putExtra(Intent.EXTRA_STREAM, stream)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                    startActivity(forward)
                }
                finish()
            }
            is IntakeRouting.SendRoute.Guidance -> {
                // kfx/DRM guidance shown by the gateway (same pipeline as ACTION_VIEW):
                // forward the stream so the gateway's resolveFile can name + gate it.
                val forward =
                    Intent(IntakeRouting.ACTION_IMPORT_BOOK).apply {
                        setPackage(packageName)
                        if (stream != null) putExtra(Intent.EXTRA_STREAM, stream)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                startActivity(forward)
                finish()
            }
            // Text/image share — the existing resolve path.
            IntakeRouting.SendRoute.Resolve ->
                viewModel.resolve(
                    extraText = intent.getStringExtra(Intent.EXTRA_TEXT),
                    extraStream = stream,
                    mimeType = intent.type,
                )
        }
    }

    private fun Intent.parcelableStream(): Uri? =
        if (Build.VERSION.SDK_INT >= 33) {
            getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
        }

    private fun queryDisplayName(uri: Uri): String? =
        try {
            contentResolver
                .query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
        } catch (e: Exception) {
            null
        }
}
