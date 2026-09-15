package com.moronigranja.localttsreader.persistence

import androidx.room.Database
import androidx.room.RoomDatabase

/**
 * The app's single database (P1). Version 1 = books + cached passages +
 * progress + settings; version 2 (T4-1) extends progress with the in-passage
 * offset and per-book speed and adds `bookmarks` + `position_history`;
 * version 3 (Phase H, decisions #109) adds the per-day `activity_seconds`
 * stats table. Schema evolution is forward-only migrations
 * (`exportSchema = false` until the CI slice (V2) adds a schema-drift check;
 * no destructive fallback — a schema bump without a migration fails loudly
 * rather than wiping the library). Builders MUST add [MIGRATION_1_2] and
 * [MIGRATION_2_3].
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
    ],
    version = 3,
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
}
