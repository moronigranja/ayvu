package com.moronigranja.localttsreader.di

import android.content.Context
import com.moronigranja.localttsreader.featuresettings.AndroidHttpTransport
import com.moronigranja.localttsreader.ocr.TrainedDataPacks
import com.moronigranja.localttsreader.tts.DefaultEngines
import com.moronigranja.localttsreader.tts.EngineDescriptor
import com.moronigranja.localttsreader.tts.PackCache
import com.moronigranja.localttsreader.tts.PackDownloader
import com.moronigranja.localttsreader.tts.PackRegistry
import com.moronigranja.localttsreader.tts.TTSEngine
import com.moronigranja.localttsreader.tts.VoiceCatalog
import com.moronigranja.localttsreader.tts.setup.StatFsStorageProbe
import com.moronigranja.localttsreader.tts.setup.StorageProbe
import com.moronigranja.localttsreader.tts.system.AndroidSystemTtsSeam
import com.moronigranja.localttsreader.tts.system.SystemTtsEngine
import com.moronigranja.localttsreader.tts.system.SystemTtsSeam
import com.moronigranja.localttsreader.tts.translate.TranslatePacks
import com.moronigranja.localttsreader.tts.translate.TranslateSpec
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
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
    fun provideTransport(): com.moronigranja.localttsreader.tts.DownloadTransport = AndroidHttpTransport()

    @Provides
    @Singleton
    fun providePackDownloader(
        cache: PackCache,
        transport: com.moronigranja.localttsreader.tts.DownloadTransport,
    ): PackDownloader = PackDownloader(cache, transport)

    /** One registry over the known engines' packs (DefaultEngines: Kokoro-82M,
     * Piper `piper-v1`, gated CosyVoice3 metadata) plus the OCR language packs. */
    @Provides
    @Singleton
    fun providePackRegistry(
        cache: PackCache,
        downloader: PackDownloader,
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
        return PackRegistry(cache, downloader, descriptors)
    }

    /** Lazy voice catalog: names arrive from the voices pack once it is verified. */
    @Provides
    @Singleton
    fun provideVoiceCatalog(cache: PackCache): VoiceCatalog = VoiceCatalog(cache)

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
