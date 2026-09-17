package io.github.moronigranja.ayvu.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.github.moronigranja.ayvu.featuresettings.AndroidHttpTransport
import io.github.moronigranja.ayvu.ocr.TessDataStager
import io.github.moronigranja.ayvu.ocr.TrainedDataPacks
import io.github.moronigranja.ayvu.player.EspeakStager
import io.github.moronigranja.ayvu.player.IoDispatcher
import io.github.moronigranja.ayvu.tts.DefaultEngines
import io.github.moronigranja.ayvu.tts.EngineDescriptor
import io.github.moronigranja.ayvu.tts.PackCache
import io.github.moronigranja.ayvu.tts.PackDownloader
import io.github.moronigranja.ayvu.tts.PackInstaller
import io.github.moronigranja.ayvu.tts.PackRegistry
import io.github.moronigranja.ayvu.tts.PackStager
import io.github.moronigranja.ayvu.tts.TTSEngine
import io.github.moronigranja.ayvu.tts.VoiceCatalog
import io.github.moronigranja.ayvu.tts.kokoro.KokoroPacks
import io.github.moronigranja.ayvu.tts.setup.StatFsStorageProbe
import io.github.moronigranja.ayvu.tts.setup.StorageProbe
import io.github.moronigranja.ayvu.tts.system.AndroidSystemTtsSeam
import io.github.moronigranja.ayvu.tts.system.SystemTtsEngine
import io.github.moronigranja.ayvu.tts.system.SystemTtsSeam
import io.github.moronigranja.ayvu.tts.translate.TranslatePackStager
import io.github.moronigranja.ayvu.tts.translate.TranslatePacks
import io.github.moronigranja.ayvu.tts.translate.TranslateSpec
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Named
import javax.inject.Singleton

/**
 * A6/C1.4 composition root: the pack machinery over the Android transport,
 * moved out of feature-settings (the pack/voice domain is core-tts; only the
 * composition root owns the wiring). Setup, settings and the engine runtimes
 * share this singleton so "Ready" in the UI and "downloadable" at play time
 * are the same disk truth.
 */
@Module
@InstallIn(SingletonComponent::class)
object PackModule {
    /** The app's internal files dir, QUALIFIED — OcrModule's TessDataDir
     * provides a bare `File`, and an unqualified injection would resolve to
     * it (all staging roots would land under `files/tesseract`; #50). */
    @Provides
    @Singleton
    @Named("app_files_dir")
    fun provideAppFilesDir(
        @ApplicationContext context: Context,
    ): File = context.filesDir

    @Provides
    @Singleton
    fun providePackCache(
        @ApplicationContext context: Context,
    ): PackCache = PackCache(context.filesDir)

    @Provides
    @Singleton
    fun provideTransport(): io.github.moronigranja.ayvu.tts.DownloadTransport = AndroidHttpTransport()

    @Provides
    @Singleton
    fun providePackDownloader(
        cache: PackCache,
        transport: io.github.moronigranja.ayvu.tts.DownloadTransport,
    ): PackDownloader = PackDownloader(cache, transport)

    /** One registry over the known engines' packs (DefaultEngines: Kokoro-82M,
     * Piper `piper-v1`, gated CosyVoice3 metadata) plus the OCR language packs. */
    @Provides
    @Singleton
    fun providePackRegistry(
        cache: PackCache,
        downloader: PackDownloader,
        @IoDispatcher ioDispatcher: CoroutineDispatcher,
    ): PackRegistry {
        val descriptors =
            DefaultEngines.descriptors +
                listOf(
                    EngineDescriptor(TrainedDataPacks.spec, TrainedDataPacks.all),
                    // Read-in-language: a registry-only pseudo-engine (the
                    // selector's switch stays closed; TranslateRuntime owns
                    // staging + sessions for this pack, decisions #114).
                    EngineDescriptor(TranslateSpec, TranslatePacks.all),
                )
        return PackRegistry(cache, downloader, descriptors, ioDispatcher)
    }

    /** Lazy voice catalog: names arrive from the voices pack once it is verified. */
    @Provides
    @Singleton
    fun provideVoiceCatalog(cache: PackCache): VoiceCatalog = VoiceCatalog(cache)

    /**
     * B4: what a verified pack unlocks. Dispatched on the pack (not the engine —
     * the espeak bundle lives under `kokoro-82m` next to the model/voices, which
     * need no staging) and run on the IO dispatcher, so the operation's Stop can
     * cancel a long extract.
     */
    @Provides
    @Singleton
    fun providePackStager(
        @Named("app_files_dir") filesDir: File,
        cache: PackCache,
        registry: PackRegistry,
        voiceCatalog: VoiceCatalog,
        @IoDispatcher io: CoroutineDispatcher,
    ): PackStager =
        PackStager { packId ->
            val pack =
                registry.packs.value
                    .firstOrNull { it.pack.id == packId }
                    ?.pack
                    ?: return@PackStager
            val staged =
                withContext(io) {
                    when {
                        pack.id == KokoroPacks.espeak.id -> EspeakStager.stage(filesDir, cache, pack)
                        pack.id == TranslatePacks.pack.id -> TranslatePackStager.stage(filesDir, cache, pack)
                        pack.engineId == TrainedDataPacks.ENGINE_ID -> TessDataStager.stage(filesDir, cache, pack)
                        else -> true // model/voice artifacts are consumed in place
                    }
                }
            check(staged) { "staging $packId did not complete" }
            if (packId == KokoroPacks.voices.id) voiceCatalog.invalidate()
        }

    /** The one installer every download entry point (settings, setup, reader,
     *  library) runs through. */
    @Provides
    @Singleton
    fun providePackInstaller(
        registry: PackRegistry,
        stager: PackStager,
    ): PackInstaller = PackInstaller(registry, stager)

    /** C1.2: the setup storage check — free bytes under the app files dir. */
    @Provides
    @Singleton
    fun provideStorageProbe(
        @Named("app_files_dir") dir: File,
    ): StorageProbe = StatFsStorageProbe(dir)

    /** C1.5 (decisions #102): the degraded zero-download system voice. */
    @Provides
    @Singleton
    fun provideSystemTtsSeam(
        @ApplicationContext context: Context,
    ): SystemTtsSeam = AndroidSystemTtsSeam(context)

    /** Bound under a name so [EngineSelector] (feature-player) consumes it
     * lazily — a kokoro-only session never touches the device TTS. */
    @Provides
    @Singleton
    @Named("system_tts")
    fun provideSystemTtsEngine(seam: SystemTtsSeam): TTSEngine = SystemTtsEngine(seam)
}
