package io.github.moronigranja.ayvu

import android.content.Context
import android.content.Intent
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.github.moronigranja.ayvu.featureshare.OpenTarget
import io.github.moronigranja.ayvu.featureshare.ShareOpenHandler
import javax.inject.Singleton

/**
 * S3 navigation seam: the share gate stays feature-side; the app owns how a
 * "Listen here" opens (MainActivity, cleared-to-top, with the passage extras).
 */
@Module
@InstallIn(SingletonComponent::class)
object AppShareModule {
    @Provides
    @Singleton
    fun provideShareOpenHandler(
        @ApplicationContext context: Context,
    ): ShareOpenHandler =
        ShareOpenHandler { target ->
            context.startActivity(
                Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
                    putExtra(OpenTarget.EXTRA_BOOK_ID, target.bookId)
                    putExtra(OpenTarget.EXTRA_CHAPTER, target.chapterIndex)
                    putExtra(OpenTarget.EXTRA_PASSAGE, target.passageIndex)
                },
            )
        }
}
