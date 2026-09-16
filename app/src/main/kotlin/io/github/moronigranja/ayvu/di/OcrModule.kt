package io.github.moronigranja.ayvu.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.github.moronigranja.ayvu.featureocr.TessTwoOcrEngine
import io.github.moronigranja.ayvu.ocr.OcrEngine
import io.github.moronigranja.ayvu.ocr.TessDataStager
import java.io.File
import javax.inject.Singleton

/**
 * A6 composition root: the OCR implementation binding (formerly feature-ocr's
 * OcrModule). Consumers (feature-share, the settings OCR section) see only
 * the core [OcrEngine] seam; only this module knows tess-two.
 */
@Module
@InstallIn(SingletonComponent::class)
object OcrModule {
    @Provides
    @Singleton
    fun provideTessDataDir(
        @ApplicationContext context: Context,
    ): File = TessDataStager.tesseractDataPath(context.filesDir)

    @Provides
    @Singleton
    fun provideOcrEngine(dataDir: File): OcrEngine = TessTwoOcrEngine(dataDir)
}
