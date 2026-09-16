package com.moronigranja.localttsreader

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.moronigranja.localttsreader.featureplayer.playback.PlaybackService
import com.moronigranja.localttsreader.featuresettings.AndroidHttpTransport
import com.moronigranja.localttsreader.model.Book
import com.moronigranja.localttsreader.model.Chapter
import com.moronigranja.localttsreader.model.LibraryEntry
import com.moronigranja.localttsreader.model.TextPassage
import com.moronigranja.localttsreader.player.EspeakStager
import com.moronigranja.localttsreader.player.PlaybackStateHolder
import com.moronigranja.localttsreader.player.PlayerPhase
import com.moronigranja.localttsreader.player.pregen.PcmPassageCache
import com.moronigranja.localttsreader.player.pregen.PregenKey
import com.moronigranja.localttsreader.player.pregen.TranslationTarget
import com.moronigranja.localttsreader.tts.PackCache
import com.moronigranja.localttsreader.tts.PackDownloader
import com.moronigranja.localttsreader.tts.kokoro.KokoroPacks
import com.moronigranja.localttsreader.tts.translate.TranslatePackStager
import com.moronigranja.localttsreader.tts.translate.TranslatePacks
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The translated playback loop on the real device (decisions #160/#162): a book
 * whose "Read in" target is pt-BR plays through the PRODUCTION path — the
 * [PlaybackService] resolves the engine, wraps it in the TranslatingEngine over
 * the real [com.moronigranja.localttsreader.llm.LlamaTranslator], the Kokoro
 * target voice renders the Portuguese, and the look-ahead queue persists every
 * passage under its `x<lang>/t<translator>` cache key.
 *
 * The assertion that the translated render really happened is the disk tier: the
 * queue writes each synthesized passage with the key it was synthesized under,
 * so the presence of `xpt-BR/tlfm12b` entries after playback is direct proof
 * that the LFM translator fed the target voice (the per-passage `Translate`
 * logcat lines carry the milliseconds).
 *
 * Requires network on the first run (Kokoro model 325 MB + voices + espeak
 * bundle + the LFM pack 730 MB) — later runs reuse the staged artifacts.
 */
@RunWith(AndroidJUnit4::class)
class LfmTranslatedPlaybackE2eTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private val files: File get() = context.filesDir

    /** The gate's own corpus (m/flores eng.devtest), as one passage. */
    private val book =
        Book(
            id = "lfm-playback-e2e-book",
            title = "LFM Read-in-language E2E",
            chapters =
                listOf(
                    Chapter(
                        0,
                        "One",
                        listOf(
                            TextPassage(
                                "We now have 4-month-old mice that are non-diabetic that used to be diabetic, he added. " +
                                    "Dr. Ehud Ur, professor of medicine at Dalhousie University in Halifax, Nova Scotia " +
                                    "cautioned that the research is still in its early days. " +
                                    "Like some other experts, he is skeptical about whether diabetes can be cured.",
                            ),
                        ),
                    ),
                ),
        )

    @Before
    fun setUp() {
        val cache = PackCache(files)
        val downloader = PackDownloader(cache, AndroidHttpTransport())
        runBlocking {
            // TTS legs: the same packs + staging the Speech screen performs.
            for (pack in listOf(KokoroPacks.model, KokoroPacks.voices)) {
                downloader.download(pack)
            }
            downloader.download(KokoroPacks.espeak)
            EspeakStager.stage(files, cache, KokoroPacks.espeak)
            assertTrue("espeak bundle must be staged", EspeakStager.isStaged(files))

            // Translate leg.
            downloader.download(TranslatePacks.pack)
            TranslatePackStager.stage(files, cache, TranslatePacks.pack)
            assertTrue("translate bundle must be staged", TranslatePackStager.isStaged(files))

            val app = context.applicationContext as LocalTtsReaderApp
            app.appSettings.setVoice("pf_dora")
            app.appSettings.setTtsEngine("kokoro-82m")
            app.appSettings.setBookTranslate(book.id, "pt-BR")
            app.libraryStore.add(LibraryEntry(book, importedAtEpochMillis = 1L))
        }
        // A clean slate for this book so the assertions read THIS run's writes.
        File(files, "pregen/${book.id}").deleteRecursively()
    }

    @After
    fun tearDown() {
        context.stopService(Intent(context, PlaybackService::class.java))
        runBlocking {
            val app = context.applicationContext as LocalTtsReaderApp
            app.appSettings.setBookTranslate(book.id, null)
            app.libraryStore.delete(book.id)
        }
    }

    @Test
    fun playbackRendersPortugueseAndCachesUnderTheLfmTranslatorKey() {
        PlaybackStateHolder.reset()
        context.startForegroundService(
            Intent(context, PlaybackService::class.java)
                .setAction(PlaybackService.ACTION_PLAY)
                .putExtra(PlaybackService.EXTRA_BOOK_ID, book.id),
        )

        var sawProgress = false
        val deadline = System.currentTimeMillis() + 240_000
        while (System.currentTimeMillis() < deadline) {
            val state = PlaybackStateHolder.state.value
            Thread.sleep(250)
            if (state.phase == PlayerPhase.PLAYING || state.phase == PlayerPhase.LOADING) sawProgress = true
            if (state.phase == PlayerPhase.COMPLETED) break
        }
        val finalState = PlaybackStateHolder.state.value
        assertTrue("playback never progressed (state=$finalState)", sawProgress)
        assertTrue("playback did not complete (state=$finalState)", finalState.phase == PlayerPhase.COMPLETED)

        val cache = PcmPassageCache(File(files, "pregen"))
        val translated =
            PregenKey(
                book.id,
                0,
                0,
                "pf_dora",
                1.0,
                engine = PregenKey.DEFAULT_ENGINE,
                target = TranslationTarget("pt-BR"),
            )
        val audio = cache.get(translated)
        assertTrue("no translated audio cached under $translated", audio != null && audio.pcm.isNotEmpty())
        assertTrue(
            "the translator segment must be on disk",
            File(File(files, "pregen"), "$translated.pcm").isFile,
        )
        // Same passage, untranslated key: the two must not alias (the render and
        // the cache key name what was actually synthesized, decisions #114).
        assertTrue(
            "the original-language key must be a different entry",
            cache.get(translated.copy(target = null)) == null,
        )
    }
}
