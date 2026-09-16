package io.github.moronigranja.ayvu

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import dagger.hilt.android.AndroidEntryPoint
import io.github.moronigranja.ayvu.ebook.IntakeRouting
import io.github.moronigranja.ayvu.featurelibrary.LibraryScreen
import io.github.moronigranja.ayvu.featurelibrary.LibraryViewModel
import io.github.moronigranja.ayvu.featureplayer.ui.ReaderScreen
import io.github.moronigranja.ayvu.featuresettings.SettingsScreen
import io.github.moronigranja.ayvu.featureshare.OpenTarget
import io.github.moronigranja.ayvu.persistence.AppSettings
import io.github.moronigranja.ayvu.persistence.ThemeMode
import io.github.moronigranja.ayvu.player.PlayerPosition
import io.github.moronigranja.ayvu.setup.SetupGate
import io.github.moronigranja.ayvu.setup.SetupScreen
import io.github.moronigranja.ayvu.ui.AyvuTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var appSettings: AppSettings

    @Inject lateinit var setupGate: SetupGate

    /** Process-lifetime scope (app.di) for the gate re-derivation. */
    @Inject lateinit var appScope: CoroutineScope

    /** The library's one import surface (activity-scoped: LibraryScreen gets
     * the same instance via its own viewModels()). External VIEW / forwarded
     * book-file intents funnel in here so the overlay shows on the library. */
    private val libraryViewModel: LibraryViewModel by viewModels()

    /** S3 "Listen here" → { book, passage } consumed once by composition. */
    private var pendingTarget by mutableStateOf<OpenTarget?>(null)

    /**
     * POST_NOTIFICATIONS (API 33+): requested once per process at cold start —
     * the single gate every notification consumer shares (media 42, pregen 43,
     * generation 44). Denied → "don't ask again" governs; never nag.
     */
    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* denied: no nag */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // All notification surfaces hang off this one permission; asking at
        // app entry beats a setup-flow coupling or a first-notification race.
        if (
            Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        pendingTarget = consumeTarget(intent)
        dispatchExternalIntake(intent)
        // C1.4: the gate derives from durable facts (packs/books/engine) on
        // every cold start — never an onboarding flag (C3). Non-blocking:
        // composition starts on the library, then flips once `active` lands.
        appScope.launch { setupGate.evaluate() }
        setContent {
            val settingsState by appSettings.state.collectAsState()
            val dark =
                when (settingsState.theme) {
                    ThemeMode.SYSTEM -> isSystemInDarkTheme()
                    ThemeMode.LIGHT -> false
                    ThemeMode.DARK -> true
                }
            AyvuTheme(darkTheme = dark) {
                var openBookId by rememberSaveable { mutableStateOf<String?>(null) }
                var targetChapter by rememberSaveable { mutableStateOf(-1) }
                var targetPassage by rememberSaveable { mutableStateOf(-1) }
                var openSettings by rememberSaveable { mutableStateOf(false) }

                val target = pendingTarget
                if (target != null) {
                    openBookId = target.bookId
                    targetChapter = target.chapterIndex
                    targetPassage = target.passageIndex
                    pendingTarget = null
                }

                val bookId = openBookId
                when {
                    // C1.4: FIRST branch — the guided setup owns the empty tree.
                    setupGate.active ->
                        SetupScreen(
                            onFinished = {
                                setupGate.dismiss()
                                appScope.launch { setupGate.evaluate() }
                            },
                        )
                    openSettings -> SettingsScreen(onBack = { openSettings = false })
                    bookId != null ->
                        ReaderScreen(
                            bookId = bookId,
                            startAt =
                                if (targetChapter >= 0) {
                                    PlayerPosition(bookId, targetChapter, targetPassage)
                                } else {
                                    null
                                },
                            onClose = {
                                openBookId = null
                                targetChapter = -1
                                targetPassage = -1
                            },
                            onOpenSettings = {
                                openBookId = null
                                targetChapter = -1
                                targetPassage = -1
                                openSettings = true
                            },
                        )
                    else ->
                        LibraryScreen(
                            onOpenBook = { openBookId = it },
                            onOpenSettings = { openSettings = true },
                        )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingTarget = consumeTarget(intent)
        dispatchExternalIntake(intent)
    }

    /** F4: file-manager ACTION_VIEW or forwarded book-file shares land in the
     * shared library importer — the overlay shows progress/stage/result on the
     * (already visible) library. Unsupported formats surface typed guidance
     * there, never a silent no-op. */
    private fun dispatchExternalIntake(intent: Intent?) {
        if (intent == null) return
        val uri =
            when (intent.action) {
                Intent.ACTION_VIEW -> intent.data
                IntakeRouting.ACTION_IMPORT_BOOK -> intent.parcelableExtraUri()
                else -> null
            } ?: return
        libraryViewModel.intakeUri(uri)
    }

    private fun Intent.parcelableExtraUri(): Uri? =
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
        }

    private fun consumeTarget(intent: Intent?): OpenTarget? =
        intent?.let {
            OpenTarget.fromExtras(
                bookId = it.getStringExtra(OpenTarget.EXTRA_BOOK_ID),
                chapterIndex = it.getIntExtra(OpenTarget.EXTRA_CHAPTER, -1),
                passageIndex = it.getIntExtra(OpenTarget.EXTRA_PASSAGE, -1),
            )
        }
}
