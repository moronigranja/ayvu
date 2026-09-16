package io.github.moronigranja.ayvu.di

import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.github.moronigranja.ayvu.model.LibraryStore
import io.github.moronigranja.ayvu.persistence.AppSettings
import io.github.moronigranja.ayvu.persistence.BookDao
import io.github.moronigranja.ayvu.persistence.CorruptDatabaseGuard
import io.github.moronigranja.ayvu.persistence.LibraryDatabase
import io.github.moronigranja.ayvu.persistence.MIGRATION_1_2
import io.github.moronigranja.ayvu.persistence.MIGRATION_2_3
import io.github.moronigranja.ayvu.persistence.MIGRATION_3_4
import io.github.moronigranja.ayvu.persistence.PassageDao
import io.github.moronigranja.ayvu.persistence.ProgressDao
import io.github.moronigranja.ayvu.persistence.RoomActivityStore
import io.github.moronigranja.ayvu.persistence.RoomLibraryStore
import io.github.moronigranja.ayvu.persistence.RoomPlayerStore
import io.github.moronigranja.ayvu.persistence.RoomTranslationStore
import io.github.moronigranja.ayvu.persistence.SettingsDao
import io.github.moronigranja.ayvu.persistence.SettingsStore
import io.github.moronigranja.ayvu.persistence.TranslationDao
import io.github.moronigranja.ayvu.player.ActivityStore
import io.github.moronigranja.ayvu.player.PlayerStore
import io.github.moronigranja.ayvu.player.TranslationStore
import kotlinx.coroutines.CoroutineScope
import javax.inject.Singleton

/**
 * A6 composition root: the Room persistence layer bindings (formerly
 * feature-library's PersistenceModule). The store contract [LibraryStore] is
 * bound to the single [RoomLibraryStore] instance, so the list UI, the
 * import coordinator, and the launch-time index rebuild all share one
 * database-backed surface.
 */
@Module
@InstallIn(SingletonComponent::class)
object PersistenceModule {
    @Provides
    @Singleton
    fun provideDatabase(
        @ApplicationContext context: Context,
    ): LibraryDatabase {
        // S22 2026-08-29: an `install -r` left a 68 B fragment at the db path;
        // Room would crash the launch-time rebuild on it forever. Quarantine
        // corrupt files first so the app opens fresh instead of crash-looping.
        CorruptDatabaseGuard.quarantineIfCorrupt(context, DATABASE_NAME)
        return Room
            .databaseBuilder(context, LibraryDatabase::class.java, DATABASE_NAME)
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
            .build()
    }

    @Provides
    fun provideBookDao(database: LibraryDatabase): BookDao = database.bookDao()

    @Provides
    fun providePassageDao(database: LibraryDatabase): PassageDao = database.passageDao()

    @Provides
    fun provideProgressDao(database: LibraryDatabase): ProgressDao = database.progressDao()

    @Provides
    fun provideSettingsDao(database: LibraryDatabase): SettingsDao = database.settingsDao()

    @Provides
    @Singleton
    fun provideLibraryStore(
        database: LibraryDatabase,
        scope: CoroutineScope,
    ): LibraryStore = RoomLibraryStore(database, scope)

    @Provides
    @Singleton
    fun provideSettingsStore(dao: SettingsDao): SettingsStore = SettingsStore(dao)

    /** The hot-path mirror the player and the activity theme read (V1). */
    @Provides
    @Singleton
    fun provideAppSettings(store: SettingsStore): AppSettings = AppSettings(store)

    @Provides
    fun provideBookmarkDao(database: LibraryDatabase) = database.bookmarkDao()

    @Provides
    fun provideHistoryDao(database: LibraryDatabase) = database.historyDao()

    @Provides
    @Singleton
    fun provideActivityStore(database: LibraryDatabase): ActivityStore = RoomActivityStore(database.activityDao())

    @Provides
    @Singleton
    fun providePlayerStore(database: LibraryDatabase): PlayerStore = RoomPlayerStore(database)

    @Provides
    fun provideTranslationDao(database: LibraryDatabase): TranslationDao = database.translationDao()

    /** The translated-text store (v4, read-in-language display) — consumed by
     * the feature-player translation service and the backup archive. */
    @Provides
    @Singleton
    fun provideTranslationStore(database: LibraryDatabase): TranslationStore = RoomTranslationStore(database)

    private const val DATABASE_NAME = "local-tts-reader.db"
}
