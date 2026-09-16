package io.github.moronigranja.ayvu.persistence

import io.github.moronigranja.ayvu.player.ActivityChunk
import io.github.moronigranja.ayvu.player.ActivityKind
import io.github.moronigranja.ayvu.player.ActivityRow
import io.github.moronigranja.ayvu.player.ActivityStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Room-backed [ActivityStore] (Phase H, decisions #109): the
 * [ActivitySecondsDao] rows in the domain's vocabulary. [record] ADDS on
 * conflict (a session accumulates, never replaces — Room's `@Upsert` would
 * lose earlier sessions); the reads stream the day window the stats card
 * paints, already mapped out of the row shape.
 */
class RoomActivityStore(
    private val dao: ActivitySecondsDao,
) : ActivityStore {
    override suspend fun record(chunks: List<ActivityChunk>) {
        for (chunk in chunks) {
            dao.accumulate(chunk.dayKey, chunk.bookId, chunk.kind.name, chunk.seconds)
        }
    }

    override fun observeSince(sinceDayKey: String): Flow<List<ActivityRow>> =
        dao.observeSince(sinceDayKey).map { rows -> rows.map { it.toActivityRow() } }

    override fun observeActiveDays(): Flow<List<String>> = dao.observeActiveDays()
}

/** Row → domain. [ActivitySecondsEntity.kind] is the [ActivityKind] name. */
private fun ActivitySecondsEntity.toActivityRow(): ActivityRow =
    ActivityRow(
        dayKey = dayKey,
        kind = ActivityKind.valueOf(kind),
        seconds = seconds,
    )
