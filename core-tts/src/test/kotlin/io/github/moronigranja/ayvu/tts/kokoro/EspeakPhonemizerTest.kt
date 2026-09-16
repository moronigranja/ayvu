package io.github.moronigranja.ayvu.tts.kokoro

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/**
 * Ground-truth tests against the real espeak-ng library: the expected strings
 * were generated with the reference phonemizer 3.4.0 pipeline
 * (preserve_punctuation=True, with_stress=True) driving the SAME system
 * library (/usr/lib/libespeak-ng.so, espeak-ng 1.52.0) and are frozen here.
 * Skipped when espeak-ng is not installed.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EspeakPhonemizerTest {
    private lateinit var phonemizer: EspeakPhonemizer

    @BeforeAll
    fun setUp() {
        phonemizer =
            try {
                EspeakPhonemizer.load()
            } catch (e: Throwable) {
                assumeTrue(false, "espeak-ng not available: ${e.message}")
                throw e
            }
        assumeTrue("en-us" in phonemizer.supportedLanguages(), "espeak-ng voices missing")
    }

    @Test
    fun `english sentences match the reference phoneme stream`() {
        assertEquals("həlˈoʊ, wˈɜːld! ", phonemizer.phonemize("Hello, world!", "en-us"))
        assertEquals(
            "ðə kwˈɪk bɹˈaʊn fˈɑːks dʒˈʌmps ˌoʊvɚ ðə lˈeɪzi dˈɑːɡ. ",
            phonemizer.phonemize("The quick brown fox jumps over the lazy dog.", "en-us"),
        )
        assertEquals(
            "ʃiː sˈɛd: \"aɪl biː ðˈɛɹ, woʊnt ˈaɪ?\" \nðˈɛn ʃiː lˈɛft. ",
            phonemizer.phonemize("She said: \"I'll be there, won't I?\"\n\nThen she left.", "en-us"),
        )
        // The T2 benchmark sample: three marks, in place (regression against
        // marks being exiled to the end of the string).
        assertEquals(
            "həlˈoʊ, wˈɜːld! ðɪs ɪz ɐ tˈɛst ʌvðə kəkˈɔːɹoʊ spˈiːtʃ pˈaɪplaɪn ɔnðə dʒˌeɪvˌiːˈɛm. ",
            phonemizer.phonemize("Hello, world! This is a test of the Kokoro speech pipeline on the JVM.", "en-us"),
        )
    }

    @Test
    fun `british english dialect is its own voice`() {
        assertEquals(
            "ðə bjˈuːtifəl flˈaʊəz blˈuːmd ɪnðə ɡˈɑːdən. ",
            phonemizer.phonemize("The beautiful flowers bloomed in the garden.", "en-gb"),
        )
    }

    @Test
    fun `french and portuguese match the reference`() {
        assertEquals(
            "bɔ̃ʒˈuʁ, kɔmˌɑ̃ alˈevuz oʒuʁdyˈi ? ",
            phonemizer.phonemize("Bonjour, comment allez-vous aujourd'hui ?", "fr-fr"),
        )
        assertEquals(
            "olˈa, tˈudʊ bˈeɪŋ koŋ vosˈe? sˈiŋ, ˌobriɡˈadʊ. ",
            phonemizer.phonemize("Olá, tudo bem com você? Sim, obrigado.", "pt-br"),
        )
    }

    @Test
    fun `more languages phonemize through their espeak voices`() {
        assertEquals("¡ˈola! ¿kˈomo estˈas? ", phonemizer.phonemize("¡Hola! ¿Cómo estás?", "es"))
        assertEquals("tʃˈao, kˌome stˈaj? bˈɛne, ɡrˈatsje mˈille! ", phonemizer.phonemize("Ciao, come stai? Bene, grazie mille!", "it"))
        assertEquals("ɡˈuːtən mˈɔɾɡən, viː ɡˈeːt ɛs dˈiːɾ? ", phonemizer.phonemize("Guten Morgen, wie geht es dir?", "de"))
        assertEquals(
            "ˈɐnnjʌŋhˌɐsejˌo, ˈonɯɫnˈɐɫs-iqˌɐ tɕˈot-nejˌo. ",
            phonemizer.phonemize("안녕하세요, 오늘 날씨가 좋네요.", "ko"),
        )
        assertEquals("nəmˈʌsteː, ˌaːp kˈɛːseː hɛ̃? ", phonemizer.phonemize("नमस्ते, आप कैसे हैं?", "hi"))
        assertEquals(
            "ˌo̞häjˌo̞ɯᵝɡˌo̞zäimˈäsɯᵝ ",
            phonemizer.phonemize("おはようございます。", "ja"),
        )
    }

    @Test
    fun `a decimal separator between digits is not split into marks`() {
        // "19,99" keeps its comma as one espeak call; the mark detectors only
        // see the clause comma and the question mark (reference behavior).
        assertEquals(
            "ðæt kˈɔsts nˈaɪntiːn nˈaɪnti nˈaɪn dˈɑːlɚz ɪn tˈoʊɾəl, ɹˈaɪt? ",
            phonemizer.phonemize("That costs 19,99 dollars in total, right?", "en-us"),
        )
    }

    @Test
    fun `blank and whitespace-only text phonemizes to empty`() {
        assertEquals("", phonemizer.phonemize("", "en-us"))
        assertEquals("", phonemizer.phonemize("   \n\n  ", "en-us"))
    }

    @Test
    fun `unsupported languages fail typed`() {
        assertThrows(PhonemizeException::class.java) {
            phonemizer.phonemize("hello", "xx-zz")
        }
        assertTrue(phonemizer.supportedLanguages().isNotEmpty())
    }

    @Test
    fun `concurrent phonemization is serialized and correct`() {
        val threads =
            List(8) { t ->
                Thread {
                    repeat(10) {
                        val got = phonemizer.phonemize("Hello, world!", "en-us")
                        assertEquals("həlˈoʊ, wˈɜːld! ", got)
                    }
                }.also { it.start() }
            }
        threads.forEach { it.join() }
    }
}
