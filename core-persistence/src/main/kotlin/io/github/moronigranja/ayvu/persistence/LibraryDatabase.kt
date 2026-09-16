package io.github.moronigranja.ayvu.persistence

import androidx.room.Database
import androidx.room.RoomDatabase

/**
 * The app's single database (P1). Version 1 = books + cached passages +
 * progress + settings; version 2 (T4-1) extends progress with the in-passage
 * offset and per-book speed and adds `bookmarks` + `position_history`;
 * version 3 (Phase H, decisions #109) adds the per-day `activity_seconds`
 * stats table; version 4 adds the per-passage `translations` table (the
 * read-in-language display). Schema evolution is forward-only migrations
 * (`exportSchema = false` until the CI slice (V2) adds a schema-drift check;
 * no destructive fallback — a schema bump without a migration fails loudly
 * rather than wiping the library). Builders MUST add [MIGRATION_1_2],
 * [MIGRATION_2_3] and [MIGRATION_3_4].
 */
@Database(
    entities = [
        BookEntity::class,
        PassageEntity::class,
        ProgressEntity::class,
        SettingEntity::class,
        BookmarkEntity::class,
        PositionHistoryEntity::class,
        ActivitySecondsEntity::class,
        TranslationEntity::class,
    ],
    version = 4,
    exportSchema = false,
)
abstract class LibraryDatabase : RoomDatabase() {
    abstract fun bookDao(): BookDao

    abstract fun passageDao(): PassageDao

    abstract fun progressDao(): ProgressDao

    abstract fun settingsDao(): SettingsDao

    abstract fun bookmarkDao(): BookmarkDao

    abstract fun historyDao(): PositionHistoryDao

    abstract fun activityDao(): ActivitySecondsDao

    abstract fun translationDao(): TranslationDao
}
