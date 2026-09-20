package io.github.moronigranja.ayvu.setup

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.moronigranja.ayvu.model.LibraryStore
import io.github.moronigranja.ayvu.persistence.AppSettings
import io.github.moronigranja.ayvu.persistence.SettingsStore
import io.github.moronigranja.ayvu.player.EspeakStager
import io.github.moronigranja.ayvu.tts.PackRegistry
import io.github.moronigranja.ayvu.tts.setup.SetupEnginePacks
import io.github.moronigranja.ayvu.tts.setup.SetupFacts
import io.github.moronigranja.ayvu.tts.setup.SetupState
import java.io.File
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

/**
 * C1.4: the first-run gate — "should the setup flow show on this cold
 * start?". Re-derived from durable facts (required packs verified, espeak
 * staged, book count, persisted engine choice) on every process start and
 * after a dismissal; deliberately NOT an onboarding flag (C3 contract): a
 * zero-book user who killed the app sees setup again.
 *
 * Composition reads [active] as snapshot state, so the gate flips the screen
 * the moment [evaluate] lands (default false → LibraryScreen, then
 * re-composed when true — the plan's accepted non-blocking start).
 */
@Singleton
class SetupGate
    @Inject
    constructor(
        private val registry: PackRegistry,
        private val settings: AppSettings,
        private val libraryStore: LibraryStore,
        @Named("app_files_dir") private val filesDir: File,
    ) {
        var active by mutableStateOf(false)
            private set

        /** Recomputed on process start and after dismiss — never persisted. */
        suspend fun evaluate() {
            // C1.1's startup reload is async; a start-time evaluate must not
            // depend on its ordering — read the store directly here.
            settings.reload()
            // Disk truth: markers written by a finished download (or a prior
            // process) must be visible to the gate even if the registry's cached
            // statuses predate them.
            registry.refresh()
            val prefs = settings.state.value
            val requiredIds = SetupEnginePacks.requiredIds(prefs.ttsEngine, prefs.voice)
            val facts =
                SetupFacts(
                    requiredPacksReady = requiredIds.all(registry::isReady),
                    espeakStaged = EspeakStager.isStaged(filesDir),
                    voiceSelected = prefs.voice != SettingsStore.DEFAULT_VOICE,
                    bookCount = libraryStore.books.value.size,
                    systemTtsOptedIn = prefs.ttsEngine == SettingsStore.SYSTEM_TTS_ENGINE,
                    importDeferred = prefs.setupImportDeferred,
                )
            active = !SetupState.isTerminal(SetupState.derive(facts))
        }

        fun dismiss() {
            active = false
        }
    }
