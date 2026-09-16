package com.moronigranja.localttsreader.featureplayer.playback

import com.moronigranja.localttsreader.model.Book
import com.moronigranja.localttsreader.model.Chapter
import com.moronigranja.localttsreader.model.TextPassage
import com.moronigranja.localttsreader.player.PlayerPosition
import com.moronigranja.localttsreader.player.pregen.PregenKey
import com.moronigranja.localttsreader.player.pregen.PregenPlanner
import com.moronigranja.localttsreader.player.pregen.TranslationTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The playback edge's cache-key identity (cleanup pass 4, 2026-09-15).
 *
 * The disk tier and the look-ahead queue address one passage's audio through
 * [PregenKey.toString]; the loop READS with a key it builds itself and the queue
 * WRITES with the key [PregenPlanner] builds. Those two used to be separate
 * constructions, and they drifted: the boundary pre-arm's key omitted the
 * read-in-language pair (`x<lang>` / `t<translator>`), so in a translated session
 * it peeked the ORIGINAL language's audio.
 *
 * The invariant pinned here is the one that keeps them honest: every key the edge
 * computes is exactly what the canonical builder produces for the same
 * book/voice/speed/language — the edge invents no key of its own.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlaybackServicePregenKeyTest {
    private val book =
        Book(
            id = "key-book",
            title = "Keys",
            chapters = listOf(Chapter(0, "One", listOf(TextPassage("p0"), TextPassage("p1")))),
        )

    /** The edge's key IS the canonical builder's key — for the plain session and
     * for the read-in-language one (the case the pre-arm got wrong). */
    @Test
    fun `the edge's live key matches the canonical pregen builder`() {
        val service = PlaybackService()
        val position = PlayerPosition(book.id, 0, 1)

        listOf(
            null to "af_bella",
            "pt" to "pf_dora",
        ).forEach { (translateLang, voice) ->
            val edge = service.livePregenKey(book, position, voice, 1.0, translateLang)
            val canonical =
                PregenPlanner(book, voice, 1.0, target = translateLang?.let { TranslationTarget(it) })
                    .key(chapterIndex = 0, passageIndex = 1)
            assertEquals(
                "edge key must be the builder's key (translateLang=$translateLang)",
                canonical.toString(),
                edge.toString(),
            )
        }
    }

    /** The identity itself: the read-in-language session's key carries the target
     * language AND the translator that rendered it, so a translated render can
     * never be read as the original's audio (decisions #114/#162).
     *
     * The `kokoro` segment is the key's DEFAULT engine: no product site supplies
     * an engine today — the dimension exists in the key and is documented as
     * preventing cross-engine collisions, but nothing populates it (queued
     * finding, 2026-09-15 audit). These strings pin today's on-disk identity, so
     * wiring a real engine later must update them deliberately. */
    @Test
    fun `the translated key carries the target language and the translator`() {
        val service = PlaybackService()
        val position = PlayerPosition(book.id, 0, 1)

        val original = service.livePregenKey(book, position, "af_bella", 1.0, null)
        val translated = service.livePregenKey(book, position, "pf_dora", 1.0, "pt")

        assertEquals("${book.id}/kokoro/af_bella/1/c0p1", original.toString())
        assertEquals("${book.id}/kokoro/pf_dora/1/xpt/tlfm12b/c0p1", translated.toString())
        assertNotEquals(original.toString(), translated.toString())
    }
}
