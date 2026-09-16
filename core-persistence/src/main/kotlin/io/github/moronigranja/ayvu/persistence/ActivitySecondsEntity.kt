package io.github.moronigranja.ayvu.persistence

import androidx.room.Entity
import androidx.room.Index

/**
 * Phase H per-day consumption seconds (decisions #109, post-v1-plan Slice
 * A): whole seconds per local day, book and kind — the dashboard's only
 * store. There is deliberately NO event/session timeline (roadmap): the
 * rows are merged by [ActivitySecondsDao.accumulate], so a session leaves
 * no trace beyond its seconds. `dayKey` is the device-zone local date
 * ("yyyy-MM-dd") — "today" is local; `kind` is "READ" | "LISTEN".
 */
@Entity(
    tableName = "activity_seconds",
    primaryKeys = ["dayKey", "bookId", "kind"],
    indices = [Index("bookId")],
)
data class ActivitySecondsEntity(
    val dayKey: String,
    val bookId: String,
    val kind: String,
    val seconds: Long,
)
