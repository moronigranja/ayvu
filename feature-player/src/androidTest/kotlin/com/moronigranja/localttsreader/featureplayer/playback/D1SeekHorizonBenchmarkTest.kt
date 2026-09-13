package com.moronigranja.localttsreader.featureplayer.playback

import android.app.ActivityManager
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import android.util.Log
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.moronigranja.localttsreader.model.Book
import com.moronigranja.localttsreader.model.Chapter
import com.moronigranja.localttsreader.model.LibraryEntry
import com.moronigranja.localttsreader.model.TextPassage
import com.moronigranja.localttsreader.persistence.AppSettings
import com.moronigranja.localttsreader.persistence.LibraryDatabase
import com.moronigranja.localttsreader.persistence.RoomLibraryStore
import com.moronigranja.localttsreader.persistence.SettingsStore
import com.moronigranja.localttsreader.player.BookProgress
import com.moronigranja.localttsreader.player.InMemoryPlayerStore
import com.moronigranja.localttsreader.player.PlaybackStateHolder
import com.moronigranja.localttsreader.player.PlayerPosition
import com.moronigranja.localttsreader.player.passageText
import com.moronigranja.localttsreader.player.pregen.PregenKey
import com.moronigranja.localttsreader.player.pregen.PregenQueue
import com.moronigranja.localttsreader.tts.EngineSpec
import com.moronigranja.localttsreader.tts.EngineTier
import com.moronigranja.localttsreader.tts.PackCache
import com.moronigranja.localttsreader.tts.SegmentAnchor
import com.moronigranja.localttsreader.tts.SynthesisOutcome
import com.moronigranja.localttsreader.tts.SynthesisRequest
import com.moronigranja.localttsreader.tts.TTSEngine
import com.moronigranja.localttsreader.tts.TtsPack
import com.moronigranja.localttsreader.tts.kokoro.KokoroPacks
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * D1 acceptance scaffold (roadmap "Instant ±30-second seek horizon", decisions
 * #155) — the device leg the roadmap requires on the S22 and the Bigme HiBreak
 * (B6CLR0B2FHFA006000712). Same shape as the spike-tts benchmark tests: one
 * instrumented entry point, args via `am instrument -e`, per-row structured
 * output to logcat (tag `AyvuD1`) plus a JSON file on the external files dir.
 *
 * What it measures, per the acceptance:
 *  - ten ±30 s seeks after normal listening (the cushion is awaited to the 30 s
 *    horizon before each seek), each classified into `buffer|pregen|disk|cold`
 *    with its seek→audio latency;
 *  - zero synchronous synthesis at seek time (the counting engine wrapper sees
 *    every `synthesize` call; a target-text call inside the seek window is a
 *    synchronous synthesis — the 79.6 s S22 / 107.0 s HiBreak pre-D1 cost);
 *  - cold first play (open → first audio, including the engine open and the
 *    horizon build), with a best-effort Choreographer-skip count from logcat
 *    (needs `adb shell pm grant <pkg> android.permission.READ_LOGS`; -1 when
 *    unreadable — count it host-side with `adb logcat -d | grep -c Skipped`);
 *  - bounded queue memory (passage count + queued seconds + process PSS per row).
 *
 * The service runs the PRODUCTION path — real KokoroRuntime (packs + espeak
 * staged per docs/build.md), real PregenCache write-through, real fill/loop —
 * driven through the same command seam the host tests use (Hilt's generated
 * onCreate cannot run here). The engine is opened ONCE per process (two
 * espeak_Initialize calls crash the device — PregenE2eTest's rule).
 *
 * Usage (staging recipe in docs/build.md §"D1 seek-horizon staging"):
 *   adb shell svc power stayon true
 *   adb logcat -c
 *   adb shell am instrument -w \
 *     -e class com.moronigranja.localttsreader.featureplayer.playback.D1SeekHorizonBenchmarkTest \
 *     -e seeks 10 -e delta 30.0 -e strict 1 \
 *     com.moronigranja.localttsreader.featureplayer.test/androidx.test.runner.AndroidJUnitRunner
 *   adb logcat -d -s AyvuD1
 *   adb exec-out run-as com.moronigranja.localttsreader.featureplayer cat \
 *     /sdcard/Android/data/com.moronigranja.localttsreader.featureplayer/files/d1_seek_results.json
 *
 * Args: `seeks` 1-20 (default 10), `delta` seconds (default 30.0, alternating
 * ±), `strict` 1 (default) fails the run on any synchronous-synthesis seek /
 * unbounded queue, 0 records only (slow devices where the fill cannot rebuild
 * the horizon inside the pre-wait budget), `stage` absolute dir holding
 * kokoro-model, kokoro-voices, libespeak-ng.so, espeak-ng-data/ (copied into
 * the test app's filesDir before the run; omit when already staged).
 */
@RunWith(AndroidJUnit4::class)
class D1SeekHorizonBenchmarkTest {

    /** The D1 horizon this scaffold pre-waits for (decisions #155). */
    private val horizonSeconds = 30.0

    /** The production passage bulwark the queue must never exceed. */
    private val passageCeiling = 60

    private class CountingEngine(private val rt: CountingRuntime) : TTSEngine {
        @Volatile private var inner: TTSEngine? = null

        fun attach(engine: TTSEngine): TTSEngine {
            inner = engine
            return this
        }

        override val spec: EngineSpec get() = inner!!.spec
        override val packs: List<TtsPack> get() = inner!!.packs

        override suspend fun synthesize(request: SynthesisRequest): SynthesisOutcome {
            rt.calls += request.text to System.currentTimeMillis()
            return inner!!.synthesize(request)
        }
    }

    /** The real runtime; [engine] wraps whatever it opens so every synthesis
     * call is counted (the zero-synchronous-synthesis proof). */
    private class CountingRuntime(
        context: Context,
        settings: AppSettings,
    ) : KokoroRuntime(context, settings) {
        val calls = CopyOnWriteArrayList<Pair<String, Long>>()
        private val counting = CountingEngine(this)

        override fun engine(): TTSEngine? = super.engine()?.let { counting.attach(it) }
    }

    /** Instant dispatch, timestamps per play: the loop parks at the head wait
     * and every seek is externally driven — the scaffold's seek cadence. */
    private class TimingOutput : PassageOutput {
        val playAt = CopyOnWriteArrayList<Long>()

        override fun play(pcm: ByteArray, sampleRate: Int, speed: Double) {
            playAt += System.currentTimeMillis()
        }

        override fun stop() = Unit
        override val positionSamples: Int get() = 0
        override fun setVolume(multiplier: Float) = Unit
    }

    private fun stage(from: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val src = File(from)
        check(src.isDirectory) { "stage dir missing: $from (push it per build.md)" }
        val cache = PackCache(context.filesDir)
        val model = cache.targetFile(KokoroPacks.model)
        val voices = cache.targetFile(KokoroPacks.voices)
        model.parentFile?.mkdirs()
        File(src, "kokoro-model").copyTo(model, overwrite = true)
        File(src, "kokoro-voices").copyTo(voices, overwrite = true)
        val espeak = File(context.filesDir, "espeak").apply { mkdirs() }
        File(src, "libespeak-ng.so").copyTo(File(espeak, "libespeak-ng.so"), overwrite = true)
        val data = File(src, "espeak-ng-data")
        if (data.isDirectory) data.copyRecursively(File(espeak, "espeak-ng-data"), overwrite = true)
        Log.d(TAG, "staged kokoro model+voices and espeak from $from")
    }

    @Test
    fun measureD1SeekHorizonOnDevice() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val args = InstrumentationRegistry.getArguments()
        val seeks = args.getString("seeks")?.toIntOrNull()?.coerceIn(1, 20) ?: 10
        val delta = args.getString("delta")?.toDoubleOrNull() ?: 30.0
        val strict = args.getString("strict") != "0"
        args.getString("stage")?.let { stage(it) }

        val database = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val scope = CoroutineScope(Dispatchers.IO)
        val book = Book(
            id = "d1-seek-horizon-book",
            title = "D1 Seek Horizon",
            chapters = listOf(
                Chapter(
                    0,
                    "One",
                    (1..60).map { i ->
                        TextPassage("Passage number $i with enough words to span almost sixty characters of speech text.")
                    },
                ),
            ),
        )
        runBlocking { RoomLibraryStore(database, scope).add(LibraryEntry(book, importedAtEpochMillis = 1L)) }

        val settings = AppSettings(SettingsStore(database.settingsDao()))
        val runtime = CountingRuntime(context, settings)
        val output = TimingOutput()
        val service = PlaybackService().apply {
            val attach = ContextWrapper::class.java.getDeclaredMethod("attachBaseContext", Context::class.java)
            attach.isAccessible = true
            attach.invoke(this, context)
            val audioManager = PlaybackService::class.java.getDeclaredField("audioManager")
            audioManager.isAccessible = true
            audioManager.set(this, context.getSystemService(Context.AUDIO_SERVICE))
            val session = PlaybackService::class.java.getDeclaredField("session")
            session.isAccessible = true
            session.set(this, android.support.v4.media.session.MediaSessionCompat(this, "local-tts-reader"))
            this.store = InMemoryPlayerStore()
            this.output = output
            this.libraryStore = RoomLibraryStore(database, scope)
            this.settings = settings
            this.runtime = runtime
            this.pregenCache = PregenCache(context)
            this.selector = EngineSelector(
                runtime,
                PiperRuntime(context, settings),
                object : dagger.Lazy<TTSEngine> {
                    override fun get(): TTSEngine = error("system tts unused in the D1 scaffold")
                },
                settings,
            )
        }
        PlaybackStateHolder.reset()
        try {
            val openedAt = System.currentTimeMillis()
            service.openBook(book.id)
            await("openBook builds the queue", 60_000) { PlaybackStateHolder.state.value.bookId == book.id }
            val queueField = PlaybackService::class.java.getDeclaredField("queue").apply { isAccessible = true }
            val queue = queueField.get(service) as PregenQueue
            val machine = service.machine!!
            val voice = settings.state.value.voice

            fun aheadSeconds(): Double =
                queue.aheadSeconds(machine.state.value.position ?: PlayerPosition(book.id, 0, 0))

            // Cold first play: let openBook's front-load fill build the horizon
            // (the engine opens during it), then start the loop at the opening.
            val cushionDeadline = System.currentTimeMillis() + 240_000
            while (aheadSeconds() < horizonSeconds && System.currentTimeMillis() < cushionDeadline) Thread.sleep(200)
            service.seekBy(0.0) // starts the loop at the opening (cold first play)
            await("cold first audio", 180_000) { output.playAt.isNotEmpty() }
            val coldFirstPlayMs = output.playAt.first() - openedAt
            val coldSynthCalls = runtime.calls.size
            val choreographerSkips = choreographerSkips()
            Log.d(TAG, "cold: first-audio ms=$coldFirstPlayMs synthCalls=$coldSynthCalls choreographerSkips=$choreographerSkips")

            val rows = JSONArray()
            var syncSynthSeeks = 0
            for (i in 1..seeks) {
                val action = if (i % 2 == 1) "seek_forward" else "seek_backward"
                val d = if (i % 2 == 1) delta else -delta
                // "After normal listening": let the fill rebuild the horizon at
                // the current playhead before the seek (no-op when already full).
                val preWaitStart = System.currentTimeMillis()
                val rebuildDeadline = preWaitStart + 180_000
                while (aheadSeconds() < horizonSeconds && System.currentTimeMillis() < rebuildDeadline) Thread.sleep(200)
                val preWaitMs = System.currentTimeMillis() - preWaitStart

                val position = machine.state.value.position!!
                val elapsed = BookProgress.elapsedSeconds(book, position)
                val target = BookProgress.positionAt(book, elapsed + d)
                val targetText = book.passageText(target.chapterIndex, target.passageIndex) ?: ""
                val key = PregenKey(book.id, target.chapterIndex, target.passageIndex, voice, 1.0)
                val lastAudio =
                    PlaybackService::class.java.getDeclaredField("lastAudio").apply { isAccessible = true }
                        .get(service) as Pair<PregenKey, *>?
                val predicted =
                    when {
                        queue.peek(target.chapterIndex, target.passageIndex) != null -> "pregen"
                        service.pregenCache.cache.contains(key) -> "disk"
                        lastAudio?.first == key -> "buffer"
                        else -> "cold"
                    }

                val playsBefore = output.playAt.size
                val seekAt = System.currentTimeMillis()
                service.seekBy(d)
                await("seek $i resolves to audio", 180_000) { output.playAt.size > playsBefore }
                val latencyMs = output.playAt.last() - seekAt
                val synced = runtime.calls.any { it.first == targetText && it.second >= seekAt }
                if (synced) syncSynthSeeks++
                val pssKb =
                    (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager)
                        .getProcessMemoryInfo(intArrayOf(android.os.Process.myPid()))
                        .firstOrNull()?.totalPss ?: -1

                val row = JSONObject()
                    .put("seek", i)
                    .put("action", action)
                    .put("delta", d)
                    .put("target", "${target.chapterIndex}/${target.passageIndex}")
                    .put("predicted", predicted)
                    .put("syncSynth", synced)
                    .put("latencyMs", latencyMs)
                    .put("preWaitMs", preWaitMs)
                    .put("queueSize", queue.size)
                    .put("aheadSeconds", aheadSeconds())
                    .put("pssKb", pssKb)
                rows.put(row)
                Log.d(
                    TAG,
                    "row: seek=$i $action target=${row.get("target")} predicted=$predicted " +
                        "syncSynth=$synced latencyMs=$latencyMs preWaitMs=$preWaitMs " +
                        "queueSize=${queue.size} ahead=${aheadSeconds()} pssKb=$pssKb",
                )
            }

            val summary = JSONObject()
                .put("device", Build.MODEL)
                .put("fingerprint", Build.FINGERPRINT)
                .put("horizonSeconds", horizonSeconds)
                .put("seeks", seeks)
                .put("delta", delta)
                .put("coldFirstPlayMs", coldFirstPlayMs)
                .put("coldSynthCalls", coldSynthCalls)
                .put("choreographerSkips", choreographerSkips)
                .put("syncSynthSeeks", syncSynthSeeks)
                .put("rows", rows)
            val out = File(context.getExternalFilesDir(null), "d1_seek_results.json")
            out.writeText(summary.toString(2))
            Log.d(
                TAG,
                "DONE rows=$seeks syncSynthSeeks=$syncSynthSeeks coldFirstPlayMs=$coldFirstPlayMs " +
                    "choreographerSkips=$choreographerSkips out=${out.absolutePath}",
            )

            if (strict) {
                assertTrue(
                    "every seek must resolve without synchronous synthesis " +
                        "(found $syncSynthSeeks cold; see d1_seek_results.json)",
                    syncSynthSeeks == 0,
                )
                assertTrue(
                    "queue memory must stay bounded (the passage bulwark)",
                    (0 until rows.length()).all { rows.getJSONObject(it).getInt("queueSize") <= passageCeiling },
                )
            }
        } finally {
            service.stopEverything()
            PlaybackActive.markStopped()
            database.close()
        }
    }

    /** Best-effort Choreographer skip count over the whole logcat buffer —
     * needs READ_LOGS (grant via adb); -1 when unreadable. */
    private fun choreographerSkips(): Int =
        runCatching {
            val process = Runtime.getRuntime().exec(arrayOf("logcat", "-d"))
            val text = process.inputStream.bufferedReader().readText()
            process.destroy()
            text.lineSequence().count { it.contains("Choreographer") && it.contains("Skipped") }
        }.getOrDefault(-1)

    private fun await(
        label: String,
        timeoutMs: Long,
        condition: () -> Boolean,
    ) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(50)
        }
        throw AssertionError("timed out waiting for: $label")
    }

    private companion object {
        const val TAG = "AyvuD1"
    }
}
