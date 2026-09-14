package com.moronigranja.localttsreader.tts.translate

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import java.io.File

/**
 * The parity gate for the whole SPM port (decisions #114 conditioning):
 * `Small100Tokenizer.encode(text, tgtLang)` must equal the pinned HF
 * `SMALL100Tokenizer` ids head-for-head. The golden fixture was produced
 * host-side by `tools/gen_nmt_inputs.py`-style tokenization — the pinned
 * tokenizer.py at `alirezamsh/small100` @ `8ab680e…` — over FLORES-101
 * sentences spanning en/es/it/pt/de/fr + zh (CJK) + hi (Devanagari).
 */
class Small100TokenizerTest {
    @Test
    fun goldenParityWithPinnedHfTokenizer() {
        for (sample in golden.samples) {
            val ids = tokenizer.encode(sample.src, sample.tgt)
            assertArrayEquals(
                sample.ids.toIntArray(),
                ids,
                "id mismatch for ${sample.label} (${sample.tgt})",
            )
        }
    }

    @Test
    fun specialIdsMatchPinnedVocab() {
        assertEquals(2, tokenizer.eosId) // `</s>` — decoder start + EOS
        assertEquals(3, tokenizer.unkId)
        // added_tokens.json pins __af__=128004 … __pt__=128075 … __zu__=128103
        // directly after the 128004-entry text vocab.
        assertEquals(128004, tokenizer.langIdOffset)
        assertEquals(128004, tokenizer.langId("af"))
        assertEquals(128075, tokenizer.langId("pt"))
        assertEquals(128102, tokenizer.langId("zh"))
        assertEquals(128103, tokenizer.langId("zu"))
    }

    @Test
    fun unknownAppLanguageNeverGuessed() {
        assertEquals(null, Small100Lang.toSmall100("xx"))
        assertEquals(null, Small100Lang.toSmall100("pt-PT")) // pt-PT != pt-BR: no guess
    }

    @Test
    fun encodeTruncatesTo512() {
        val longText = "The quick brown fox jumps over the lazy dog. ".repeat(80)
        val ids = tokenizer.encode(longText, "es")
        assertEquals(512, ids.size)
        assertEquals(tokenizer.langId("es"), ids[0])
        assertEquals(tokenizer.eosId, ids[511])
    }

    @Test
    fun roundTripRestoresNormalizedText() {
        for (text in listOf("Olá, tudo bem? Está chovendo agora.", "こんにちは世界。", "नमस्ते दुनिया")) {
            // The round trip restores the normalized form exactly: the
            // encode-side dummy prefix ▁ is consumed on decode (sentencepiece
            // processor semantics), single spaces survive, CJK untouched.
            assertEquals(text, tokenizer.decode(tokenizer.encode(text, "en")))
        }
    }

    @Test
    fun decodeStripsConditioningAndSpecials() {
        val ids = tokenizer.encode("Olá, mundo!", "pt")
        // [lang, pieces…, eos] — decode must drop both ends and the unk slot.
        val decoded = tokenizer.decode(ids)
        assertEquals("Olá, mundo!", decoded)
    }

    companion object {
        private lateinit var tokenizer: Small100Tokenizer
        private lateinit var golden: GoldenFixture

        private class GoldenFixture(
            val samples: List<GoldenSample>,
        )

        private class GoldenSample(
            val label: String,
            val src: String,
            val tgt: String,
            val ids: List<Int>,
        )

        @JvmStatic
        @BeforeAll
        fun load() {
            tokenizer =
                Small100Tokenizer.load(
                    resourceFile("/sentencepiece.bpe.model"),
                    resourceFile("/vocab.json"),
                )
            golden = parseGolden(resourceFile("/small100-golden-ids.json").readText())
        }

        private fun resourceFile(name: String): File {
            val url =
                Small100TokenizerTest::class.java.getResource(name)
                    ?: error("missing test fixture $name")
            return File(url.toURI())
        }

        private fun parseGolden(json: String): GoldenFixture {
            val root = Json.parseToJsonElement(json).jsonObject
            val samples =
                root.getValue("samples").jsonArray.map { sample ->
                    val o = sample.jsonObject
                    GoldenSample(
                        o.getValue("label").jsonPrimitive.content,
                        o.getValue("src").jsonPrimitive.content,
                        o.getValue("tgt").jsonPrimitive.content,
                        o.getValue("ids").jsonArray.map { it.jsonPrimitive.content.toInt() },
                    )
                }
            return GoldenFixture(samples)
        }
    }
}
