package com.moronigranja.localttsreader.setup

import com.moronigranja.localttsreader.model.Book
import com.moronigranja.localttsreader.model.InMemoryLibraryStore
import com.moronigranja.localttsreader.model.LibraryEntry
import com.moronigranja.localttsreader.persistence.AppSettings
import com.moronigranja.localttsreader.persistence.SettingEntity
import com.moronigranja.localttsreader.persistence.SettingsDao
import com.moronigranja.localttsreader.persistence.SettingsStore
import com.moronigranja.localttsreader.tts.DownloadTransport
import com.moronigranja.localttsreader.tts.EngineDescriptor
import com.moronigranja.localttsreader.tts.EngineSpec
import com.moronigranja.localttsreader.tts.EngineTier
import com.moronigranja.localttsreader.tts.OpenResult
import com.moronigranja.localttsreader.tts.PackCache
import com.moronigranja.localttsreader.tts.PackDownloader
import com.moronigranja.localttsreader.tts.PackKind
import com.moronigranja.localttsreader.tts.PackRegistry
import com.moronigranja.localttsreader.tts.TtsPack
import com.moronigranja.localttsreader.tts.sha256Hex
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * C1.4 (JVM, fakes — no Robolectric): the gate re-derives from durable facts
 * every cold start. Registry markers are real files under the temp filesDir
 * (the same disk truth production uses); packs use tiny fixture descriptors
 * with the real kokoro ids.
 */
class SetupGateTest {
    @TempDir
    lateinit var root: File

    private lateinit var filesDir: File
    private lateinit var cache: PackCache
    private lateinit var registry: PackRegistry
    private lateinit var library: InMemoryLibraryStore
    private lateinit var settings: AppSettings
    private val dao = FakeSettingsDao()

    private val model = fixturePack("kokoro-model", 64)
    private val voices = fixturePack("kokoro-voices", 32)
    private val espeak = fixturePack("espeak-ng", 16)

    @BeforeEach
    fun setUp() {
        filesDir = File(root, "files").apply { mkdirs() }
        cache = PackCache(filesDir)
        val descriptors =
            listOf(
                EngineDescriptor(
                    spec = EngineSpec("kokoro-82m", "Kokoro", EngineTier.PRIMARY, setOf("en")),
                    packs = listOf(model, voices, espeak),
                ),
            )
        registry = PackRegistry(cache, PackDownloader(cache, FailTransport()), descriptors)
        library = InMemoryLibraryStore()
        settings = AppSettings(SettingsStore(dao))
    }

    @Test
    fun `zero books and missing packs keeps the gate active`() =
        runTest {
            val gate = gate()
            gate.evaluate()
            assertTrue(gate.active, "a clean install must show setup")
        }

    @Test
    fun `packs ready with no books still shows the import step`() =
        runTest {
            markReady(model)
            markReady(voices)
            markReady(espeak)
            markStagedEspeak()

            val gate = gate()
            gate.evaluate()
            assertTrue(gate.active, "packs done but no book → setup offers the import step")
        }

    @Test
    fun `everything ready with a book deactivates the gate`() =
        runTest {
            markReady(model)
            markReady(voices)
            markReady(espeak)
            markStagedEspeak()
            library.add(entry("book-1"))

            val gate = gate()
            gate.evaluate()
            assertFalse(gate.active, "books + ready packs → library, no setup")
        }

    @Test
    fun `system tts opted in with missing packs and a book is inactive`() =
        runTest {
            // decisions #102 leg 6: user took the degraded path, imported a book —
            // the flow is over even though no Kokoro pack exists.
            settings.setTtsEngine(SettingsStore.SYSTEM_TTS_ENGINE)
            library.add(entry("book-1"))

            val gate = gate()
            gate.evaluate()
            assertFalse(gate.active, "degraded-ready is terminal")
        }

    @Test
    fun `system tts opted in with missing packs and no book stays active`() =
        runTest {
            settings.setTtsEngine(SettingsStore.SYSTEM_TTS_ENGINE)

            val gate = gate()
            gate.evaluate()
            assertTrue(gate.active, "opted-in user still must import a book")
        }

    // ------------------------------------------------------------------
    // C3 — recovery and re-entry (roadmap): the gate re-derives from
    // durable facts, so losing what made setup complete re-activates it.
    // ------------------------------------------------------------------

    @Test
    fun `losing a pack reactivates a completed setup`() =
        runTest {
            markReady(model)
            markReady(voices)
            markReady(espeak)
            markStagedEspeak()
            library.add(entry("book-1"))
            val gate = gate()
            gate.evaluate()
            assertFalse(gate.active, "precondition: setup is complete")

            // The user deleted the pack files on their storage — the marker
            // goes with them (PackCache: the cache IS the pack state).
            cache.deleteArtifacts(voices)
            gate.evaluate()
            assertTrue(gate.active, "a lost required pack re-derives setup as active")
        }

    @Test
    fun `wiped espeak staging reactivates a completed setup`() =
        runTest {
            markReady(model)
            markReady(voices)
            markReady(espeak)
            markStagedEspeak()
            library.add(entry("book-1"))
            val gate = gate()
            gate.evaluate()
            assertFalse(gate.active, "precondition: setup is complete")

            File(filesDir, "espeak").deleteRecursively()
            gate.evaluate()
            assertTrue(gate.active, "a wiped engine-gate bundle re-derives setup as active")
        }

    @Test
    fun `dismissal is not sticky and re-derivation resurrects an incomplete setup`() =
        runTest {
            val gate = gate()
            gate.evaluate()
            assertTrue(gate.active)
            gate.dismiss()
            assertFalse(gate.active, "dismissal hides the flow")

            // Nothing changed on disk: the next evaluate (cold start in
            // production) re-derives the same durable facts — dismissal is
            // NOT an onboarding flag (C1/C3 contract).
            gate.evaluate()
            assertTrue(gate.active, "an incomplete setup resurfaces on re-derivation")
        }

    private fun gate(): SetupGate = SetupGate(registry, settings, library, filesDir)

    private fun markReady(pack: TtsPack) {
        val target = cache.targetFile(pack)
        target.parentFile?.mkdirs()
        target.writeBytes(ByteArray(pack.sizeBytes.toInt()))
        assertTrue(cache.verifyAndMark(pack), "fixture pack must verify")
    }

    /** EspeakStager.isStaged: lib + non-empty data dir under files/espeak/. */
    private fun markStagedEspeak() {
        val bundle = File(filesDir, "espeak")
        File(bundle, "espeak-ng-data").mkdirs()
        File(bundle, "libespeak-ng.so").writeBytes(ByteArray(4))
        File(bundle, "espeak-ng-data/voices").writeText("x")
    }

    private fun fixturePack(
        id: String,
        size: Long,
    ): TtsPack =
        TtsPack(
            id = id,
            engineId = "kokoro-82m",
            kind = PackKind.MODEL,
            displayName = id,
            url = "https://example.test/$id",
            sha256Hex = sha256Hex(ByteArray(size.toInt())),
            sizeBytes = size,
        )

    private fun entry(id: String) = LibraryEntry(Book(id = id, title = id), importedAtEpochMillis = 1)

    /** Downloads never run in gate tests; statuses stay disk-derived. */
    private class FailTransport : DownloadTransport {
        override suspend fun open(
            url: String,
            rangeFrom: Long?,
        ): OpenResult = OpenResult.HttpError(404)
    }
}

class FakeSettingsDao : SettingsDao {
    val rows = mutableMapOf<String, String>()

    override suspend fun get(key: String): String? = rows[key]

    override suspend fun put(setting: SettingEntity) {
        rows[setting.key] = setting.value
    }

    override suspend fun all(): List<SettingEntity> = rows.map { (key, value) -> SettingEntity(key, value) }

    override suspend fun putAll(settings: List<SettingEntity>) {
        settings.forEach { rows[it.key] = it.value }
    }

    override suspend fun delete(key: String) {
        rows.remove(key)
    }
}
