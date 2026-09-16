package io.github.moronigranja.ayvu.tts.kokoro

import java.io.File

/**
 * G0 narration-corpus generator: phonemizes `tools/g0-corpus.tsv`
 * (`lang<TAB>category<TAB>text`, headerless) through the production
 * phonemizer stack — `NormalizingPhonemizer` → [EspeakPhonemizer], the exact
 * default [KokoroEngine.open] wires — and writes the five-column device
 * corpus for the spike-tts G0CorpusRunner benchmark:
 *
 *     id<TAB>lang<TAB>category<TAB>text<TAB>phonemes
 *
 * `id` is a zero-padded 4-digit row index (0001, 0002, …). A blank phonemize
 * result fails the whole run (wrong espeak code or unsupported language must
 * never yield a partial corpus).
 *
 * This pass is phonemization only — the pack cache is not opened — but the
 * first launcher arg stays the pack-cache root for g0Corpus-task parity with
 * KokoroGrainSpike.
 *
 * Usage: ./gradlew :core-tts:g0Corpus [-PkokoroCache=<dir>]
 */
fun main(args: Array<String>) {
    args.getOrNull(0)
        ?: File(System.getProperty("user.home"), ".cache/local-tts-reader/packs").absolutePath

    val source =
        sequenceOf("tools/g0-corpus.tsv", "../tools/g0-corpus.tsv")
            .map(::File)
            .firstOrNull { it.isFile }
            ?: error("g0-corpus source not found (looked for tools/g0-corpus.tsv and ../tools/g0-corpus.tsv)")
    // NormalizingPhonemizer is not AutoCloseable; keep the espeak delegate
    // to release the native library in finally.
    val espeak = EspeakPhonemizer.load()
    val phonemizer = NormalizingPhonemizer(espeak)
    val rows = ArrayList<String>()
    try {
        for ((index, line) in source.readLines().withIndex()) {
            if (line.isBlank()) continue
            val parts = line.split('\t')
            require(parts.size == 3) {
                "g0-corpus line ${index + 1}: expected 3 tab-separated columns, got ${parts.size}"
            }
            val (lang, category, text) = parts
            // espeak emits a literal newline inside its phoneme string for
            // some symbol readings (e.g. "0.5%", "20¢", "$50"); collapse it —
            // a record must stay one TSV line.
            val phonemes = phonemizer.phonemize(text, lang).replace("\n", " ")
            if (phonemes.isBlank()) {
                error(
                    "g0-corpus line ${index + 1}: phonemize returned empty for lang=$lang " +
                        "— wrong espeak code or unsupported language",
                )
            }
            rows += "%04d\t%s\t%s\t%s\t%s".format(rows.size + 1, lang, category, text, phonemes)
        }
    } finally {
        espeak.close()
    }
    check(rows.isNotEmpty()) { "g0-corpus source ${source.absolutePath} produced no rows" }

    // Written beside the module's test data, derived from the root the source was
    // found under so the task works from either CWD (pass 5b: it used to land in
    // the module root). NOT src/main/resources — a main resource ships in the app.
    val outFile = File(source.parentFile.parentFile, "core-tts/src/test/resources/g0_corpus.tsv")
    outFile.parentFile.mkdirs()
    outFile.writeText(rows.joinToString("\n") + "\n")
    println("g0 corpus: ${rows.size} entries -> ${outFile.absolutePath}")
}
