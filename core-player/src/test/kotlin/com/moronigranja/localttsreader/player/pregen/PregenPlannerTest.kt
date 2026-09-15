package com.moronigranja.localttsreader.player.pregen

import com.moronigranja.localttsreader.model.Book
import com.moronigranja.localttsreader.model.Chapter
import com.moronigranja.localttsreader.model.TextPassage
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * S1/O3 shared walk (decisions #75): [PregenPlanner] is the single
 * spine-order passage walk consumed by [OfflinePregen] (whole-book runs) and
 * [PregenQueue] (playhead look-ahead). These tests cover the walk once — the
 * two executors' suites then verify their own loop decisions on top of it.
 */
class PregenPlannerTest {
    private val book =
        Book(
            id = "b1",
            title = "Two chapters",
            chapters =
                listOf(
                    Chapter(0, "One", listOf(TextPassage("a"), TextPassage("b"), TextPassage("c"))),
                    Chapter(1, "Two", listOf(TextPassage("d"), TextPassage("e"))),
                ),
        )
    private val planner = PregenPlanner(book, "af_heart", 1.0)

    private fun key(
        c: Int,
        p: Int,
    ) = PregenKey(book.id, c, p, "af_heart", 1.0, engine = PregenKey.DEFAULT_ENGINE)

    @Test
    fun `nextAfter walks spine order across chapter boundaries`() =
        runTest {
            assertEquals(0 to 1, planner.nextAfter(0 to 0))
            assertEquals(0 to 2, planner.nextAfter(0 to 1))
            assertEquals(1 to 0, planner.nextAfter(0 to 2))
            assertEquals(1 to 1, planner.nextAfter(1 to 0))
            assertNull(planner.nextAfter(1 to 1))
        }

    @Test
    fun `walk from null visits every passage in spine order`() =
        runTest {
            val visited = mutableListOf<Pair<Int, Int>>()
            planner.walk(onCandidate = { c, p, _ ->
                visited += c to p
                true
            })
            assertEquals(listOf(0 to 0, 0 to 1, 0 to 2, 1 to 0, 1 to 1), visited)
        }

    @Test
    fun `walk from a position starts strictly after it`() =
        runTest {
            val visited = mutableListOf<Pair<Int, Int>>()
            planner.walk(from = 0 to 1, onCandidate = { c, p, _ ->
                visited += c to p
                true
            })
            assertEquals(listOf(0 to 2, 1 to 0, 1 to 1), visited)
        }

    @Test
    fun `shouldVisit false skips without stopping the walk`() =
        runTest {
            // Skip 0/1 and 1/0; the rest still visit.
            val skip = setOf(0 to 1, 1 to 0)
            val visited = mutableListOf<Pair<Int, Int>>()
            planner.walk(
                shouldVisit = { c, p, _ -> (c to p) !in skip },
                onCandidate = { c, p, _ ->
                    visited += c to p
                    true
                },
            )
            assertEquals(listOf(0 to 0, 0 to 2, 1 to 1), visited)
        }

    @Test
    fun `stop halts the walk at the first offending passage`() =
        runTest {
            // Contiguity-style stop: halt at 0/2 (in-flight), never reaching 1/x.
            val visited = mutableListOf<Pair<Int, Int>>()
            planner.walk(
                stop = { _, p, _ -> p == 2 },
                onCandidate = { c, p, _ ->
                    visited += c to p
                    true
                },
            )
            assertEquals(listOf(0 to 0, 0 to 1), visited)
        }

    @Test
    fun `onCandidate false stops the walk`() =
        runTest {
            val visited = mutableListOf<Pair<Int, Int>>()
            planner.walk(onCandidate = { c, p, _ ->
                visited += c to p
                (c to p) != (0 to 1)
            })
            assertEquals(listOf(0 to 0, 0 to 1), visited)
        }

    @Test
    fun `onChapter fires once per chapter and can halt before it`() =
        runTest {
            val chapterStarts = mutableListOf<Int>()
            val visited = mutableListOf<Pair<Int, Int>>()
            planner.walk(
                onChapter = { c ->
                    chapterStarts += c
                    c == 0
                },
                onCandidate = { c, p, _ ->
                    visited += c to p
                    true
                },
            )
            // Chapter 1's hook fires (that is the halt decision point), then the
            // walk stops before its passages.
            assertEquals(listOf(0, 1), chapterStarts)
            assertEquals(listOf(0 to 0, 0 to 1, 0 to 2), visited)
        }

    @Test
    fun `onChapterDone fires after each fully walked chapter`() =
        runTest {
            val done = mutableListOf<Int>()
            planner.walk(onChapterDone = { c -> done += c })
            assertEquals(listOf(0, 1), done)
            // A walk stopped mid-chapter does not fire that chapter's done hook
            // (the offline executor counts only fully walked chapters).
            val doneAfterStop = mutableListOf<Int>()
            planner.walk(stop = { c, p, _ -> c == 0 && p == 1 }, onChapterDone = { c -> doneAfterStop += c })
            assertEquals(emptyList<Int>(), doneAfterStop)
        }

    @Test
    fun `key maps to the executor's PregenKey`() =
        runTest {
            assertEquals(key(0, 2), planner.key(0, 2))
            assertEquals("b1/kokoro/af_heart/1/c0p2", planner.key(0, 2).toString())
        }

    @Test
    fun `key carries the planner's engine into the path`() =
        runTest {
            val cosy = PregenPlanner(book, "af_heart", 1.0, engine = "cosyvoice3")
            assertEquals(
                PregenKey(book.id, 0, 1, "af_heart", 1.0, engine = "cosyvoice3"),
                cosy.key(0, 1),
            )
            assertEquals("b1/cosyvoice3/af_heart/1/c0p1", cosy.key(0, 1).toString())
        }
}
