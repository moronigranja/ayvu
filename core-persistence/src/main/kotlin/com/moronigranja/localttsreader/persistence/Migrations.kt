package com.moronigranja.localttsreader.persistence

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Forward-only schema evolution (decisions #22: no destructive fallback — a
 * schema bump without a migration fails loudly rather than wiping the
 * library). One object per step; builders must call [addMigrations].
 */
val MIGRATION_1_2: Migration =
    object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // Player slice (T4-1, decisions #33): progress gains the in-passage
            // book-time offset + per-book speed; bookmarks and the position ring
            // arrive as new tables.
            db.execSQL("ALTER TABLE progress ADD COLUMN offsetSeconds REAL NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE progress ADD COLUMN speed REAL NOT NULL DEFAULT 1.0")
            db.execSQL(
                """
            CREATE TABLE IF NOT EXISTS `bookmarks` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `bookId` TEXT NOT NULL,
                `chapterIndex` INTEGER NOT NULL,
                `passageIndex` INTEGER NOT NULL,
                `offsetSeconds` REAL NOT NULL,
                `label` TEXT,
                `createdAtEpochMillis` INTEGER NOT NULL
            )
            """,
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_bookmarks_bookId` ON `bookmarks` (`bookId`)")
            db.execSQL(
                """
            CREATE TABLE IF NOT EXISTS `position_history` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `bookId` TEXT NOT NULL,
                `chapterIndex` INTEGER NOT NULL,
                `passageIndex` INTEGER NOT NULL,
                `offsetSeconds` REAL NOT NULL,
                `createdAtEpochMillis` INTEGER NOT NULL
            )
            """,
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_position_history_bookId` ON `position_history` (`bookId`)")
        }
    }

/** Phase H (decisions #109, post-v1-plan Slice A): the per-day consumption
 * table arrives as a pure add — v2 tables are untouched. */
val MIGRATION_2_3: Migration =
    object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
            CREATE TABLE IF NOT EXISTS `activity_seconds` (
                `dayKey` TEXT NOT NULL,
                `bookId` TEXT NOT NULL,
                `kind` TEXT NOT NULL,
                `seconds` INTEGER NOT NULL,
                PRIMARY KEY(`dayKey`, `bookId`, `kind`)
            )
            """,
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_activity_seconds_bookId` ON `activity_seconds` (`bookId`)")
        }
    }
