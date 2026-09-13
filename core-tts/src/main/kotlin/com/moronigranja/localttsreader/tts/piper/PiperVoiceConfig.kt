package com.moronigranja.localttsreader.tts.piper

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * One Piper voice's runtime config, parsed from the pinned `*.onnx.json`
 * (rhasspy/piper-voices ships it next to the weights): sample rate, the three
 * VITS inference scales, the espeak-ng phonemization voice, and the
 * phoneme_id_map that maps IPA codepoints to model ids.
 *
 * The id rule ([phonemeIds]) is the official piper one (piper-tts
 * `phoneme_ids.py`), verified head-for-head against `PiperVoice` on the
 * en_US-lessac-medium artifact (decisions #154): the ids open with
 * BOS=1 + PAD=0, every phoneme codepoint contributes (id, 0), and EOS=2
 * closes. Phonemes are NFD-decomposed before mapping (the id map keys carry
 * the combining marks separately, e.g. U+0303); codepoints missing from the
 * map are skipped exactly as official piper does — the pinned voices' maps
 * cover our phonemizer output with 0 unmapped (decisions #99).
 */
class PiperVoiceConfig(
    val sampleRateHz: Int,
    val noiseScale: Float,
    val lengthScale: Float,
    val noiseW: Float,
    /** The espeak-ng voice language the phonemizer runs for this voice. */
    val espeakVoice: String,
    /** The voice's phoneme_id_map — per-codepoint id lists (both pinned
     * voices carry single-id entries; the format allows lists). */
    val phonemeIdMap: Map<Char, List<Int>>,
) {

    /**
     * Maps a phoneme string to the model's phoneme ids — the verified rule
     * above. Whitespace collapses to the single gap the caller already
     * normalized away; [phonemes] is the phonemizer's IPA output.
     */
    fun phonemeIds(phonemes: String): IntArray {
        val ids = ArrayList<Int>(phonemes.length * 2 + 4)
        ids.add(BOS)
        ids.add(PAD)
        // Official piper decomposes phonemes into UTF-8 codepoints (NFD) before
        // mapping — combining marks are separate map keys.
        val decomposed = java.text.Normalizer.normalize(phonemes, java.text.Normalizer.Form.NFD)
        var i = 0
        while (i < decomposed.length) {
            val cp = decomposed.codePointAt(i)
            i += Character.charCount(cp)
            val mapped = if (cp < Character.MIN_SUPPLEMENTARY_CODE_POINT) phonemeIdMap[cp.toChar()] else null
            if (mapped != null) {
                ids.addAll(mapped)
                ids.add(PAD)
            }
        }
        ids.add(EOS)
        return ids.toIntArray()
    }

    /**
     * The `scales` tensor: (noise_scale, length_scale, noise_w) from the voice
     * config, with [SynthesisRequest.speed] expressed as the VITS length scale
     * (durations divide by speed, so speed 2.0 halves the length scale — the
     * speed 1.0 default stays the measured json values).
     */
    fun scales(speed: Double): FloatArray =
        floatArrayOf(noiseScale, (lengthScale / speed.coerceIn(SPEED_RANGE)).toFloat(), noiseW)

    companion object {
        private const val BOS = 1
        private const val PAD = 0
        private const val EOS = 2

        /** SynthesisRequest speed bounds (contract doc: Kokoro exports 0.5–2.0). */
        private val SPEED_RANGE = 0.5..2.0

        fun parse(json: String): PiperVoiceConfig {
            val root = Json.parseToJsonElement(json).jsonObject
            val audio = root["audio"]?.jsonObject ?: error("audio missing from Piper voice config")
            val inference = root["inference"]?.jsonObject ?: error("inference missing from Piper voice config")
            val espeak = root["espeak"]?.jsonObject ?: error("espeak missing from Piper voice config")
            val mapElement = root["phoneme_id_map"]?.jsonObject ?: error("phoneme_id_map missing from Piper voice config")
            val map = HashMap<Char, List<Int>>(mapElement.size)
            for ((key, value) in mapElement) {
                require(key.length == 1) {
                    "unsupported Piper voice config: multi-codepoint id-map key '$key'"
                }
                map[key[0]] = value.jsonArray.map { it.jsonPrimitive.int }
            }
            return PiperVoiceConfig(
                sampleRateHz = audio["sample_rate"]?.jsonPrimitive?.int
                    ?: error("sample_rate missing from Piper voice config"),
                noiseScale = (inference["noise_scale"]?.jsonPrimitive?.double
                    ?: error("noise_scale missing from Piper voice config")).toFloat(),
                lengthScale = (inference["length_scale"]?.jsonPrimitive?.double
                    ?: error("length_scale missing from Piper voice config")).toFloat(),
                noiseW = (inference["noise_w"]?.jsonPrimitive?.double
                    ?: error("noise_w missing from Piper voice config")).toFloat(),
                espeakVoice = espeak["voice"]?.jsonPrimitive?.content
                    ?: error("espeak voice missing from Piper voice config"),
                phonemeIdMap = map,
            )
        }
    }
}