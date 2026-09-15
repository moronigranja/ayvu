package com.moronigranja.localttsreader.featureshare

import com.moronigranja.localttsreader.locate.IndexRebuilder
import com.moronigranja.localttsreader.locate.TextIndex
import com.moronigranja.localttsreader.ocr.OcrEngine
import com.moronigranja.localttsreader.persistence.AppSettings
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * S2 wiring: binds the resolver to THE app's [TextIndex] singleton (provided
 * by feature-library's ImportModule — the same index the launch-time rebuild
 * fills, so a share can never query a stale second copy).
 */
@Module
@InstallIn(SingletonComponent::class)
object ShareModule {
    @Provides
    @Singleton
    fun provideShareSnippetResolver(
        index: TextIndex,
        rebuildGate: IndexRebuilder,
        settings: AppSettings,
        ocr: OcrEngine?,
    ): ShareSnippetResolver =
        ShareSnippetResolver(
            index = index,
            rebuildGate = rebuildGate,
            threshold = { settings.state.value.threshold }, // cached mirror, no DB on the query path
            ocr = ocr,
            ocrLanguages = { settings.state.value.ocrLanguages },
        )
}
