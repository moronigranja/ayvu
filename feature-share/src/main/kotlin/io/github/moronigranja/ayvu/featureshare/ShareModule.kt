package io.github.moronigranja.ayvu.featureshare

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.github.moronigranja.ayvu.locate.IndexRebuilder
import io.github.moronigranja.ayvu.locate.TextIndex
import io.github.moronigranja.ayvu.ocr.OcrEngine
import io.github.moronigranja.ayvu.persistence.AppSettings
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
