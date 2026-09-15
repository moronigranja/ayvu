package com.moronigranja.localttsreader.tts.kokoro

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipInputStream

/**
 * The voices pack (`voices-v1.0.bin`): a numpy `.npz` archive whose members
 * are `.npy` float32 style tensors, one (510, 1, 256) tensor per voice. Row
 * `i` of a voice is the style vector for an `i+1`-phoneme window — the graph's
 * `style` input (reference `get_voice_style` / `_style_for`).
 *
 * The parse is intentionally numpy-format aware (magic header, `<f4`,
 * little-endian, no fortran order) rather than zip-key based, so the same
 * loader serves any compatible voices pack and fails loudly on a wrong one.
 */
class KokoroVoiceBank private constructor(
    private val styles: Map<String, FloatArray>,
) {
    /** Voice names in pack order. */
    val voiceNames: Set<String> get() = styles.keys

    /** The full style tensor of [name] (510 × 256 floats), null when unknown. */
    fun style(name: String): FloatArray? = styles[name]

    /** The 256-float style row for a window of [length] phonemes (reference `_style_for`). */
    fun styleFor(
        name: String,
        length: Int,
    ): FloatArray? {
        val tensor = styles[name] ?: return null
        val row = minOf(length, ROWS).coerceAtLeast(1) - 1
        return tensor.copyOfRange(row * STYLE_DIM, (row + 1) * STYLE_DIM)
    }

    companion object {
        const val ROWS = 510
        const val STYLE_DIM = 256

        fun load(file: File): KokoroVoiceBank {
            require(file.isFile) { "voices file not found: $file" }
            val styles = linkedMapOf<String, FloatArray>()
            ZipInputStream(file.inputStream().buffered()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (entry.isDirectory || !entry.name.endsWith(".npy")) continue
                    val name = entry.name.removeSuffix(".npy")
                    require(name.isNotBlank()) { "empty voice name in ${file.name}" }
                    val data = zip.readBytes()
                    val header = parseNpyHeader(data)
                    require(header.descr == "<f4" && !header.fortranOrder) {
                        "unsupported voice tensor in $file: ${header.descr} ${if (header.fortranOrder) "fortran" else "c"} order"
                    }
                    require(header.elementCount == ROWS.toLong() * STYLE_DIM) {
                        "voice tensor size ${header.elementCount} != $ROWS*$STYLE_DIM in $file"
                    }
                    val floats = FloatArray((data.size - header.offset) / Float.SIZE_BYTES)
                    ByteBuffer
                        .wrap(data, header.offset, floats.size * Float.SIZE_BYTES)
                        .order(ByteOrder.LITTLE_ENDIAN)
                        .asFloatBuffer()
                        .get(floats)
                    styles[name] = floats
                }
            }
            require(styles.isNotEmpty()) { "no voice tensors found in $file" }
            return KokoroVoiceBank(styles)
        }

        private data class NpyHeader(
            val offset: Int,
            val descr: String,
            val fortranOrder: Boolean,
            val elementCount: Long,
        )

        private fun parseNpyHeader(data: ByteArray): NpyHeader {
            require(
                data.size >= 10 && data[0] == 0x93.toByte() && data[1] == 'N'.code.toByte() &&
                    data[2] == 'U'.code.toByte() && data[3] == 'M'.code.toByte() && data[4] == 'P'.code.toByte() &&
                    data[5] == 'Y'.code.toByte(),
            ) { "not a numpy .npy member" }
            val version = data[6].toInt()
            val headerLength =
                when (version) {
                    1 -> (data[8].toInt() and 0xFF) or ((data[9].toInt() and 0xFF) shl 8)
                    2, 3 ->
                        (data[8].toInt() and 0xFF) or ((data[9].toInt() and 0xFF) shl 8) or
                            ((data[10].toInt() and 0xFF) shl 16) or ((data[11].toInt() and 0xFF) shl 24)
                    else -> error("unsupported numpy format version $version")
                }
            val offset =
                when (version) {
                    1 -> 10
                    else -> 12
                }
            val header = String(data, offset, headerLength, Charsets.US_ASCII)
            val descr =
                Regex("'descr':\\s*'([^']*)'").find(header)?.groupValues?.get(1)
                    ?: error("descr missing from npy header")
            val fortran = Regex("'fortran_order':\\s*(True|False)").find(header)?.groupValues?.get(1) == "True"
            val shape =
                Regex("'shape':\\s*\\(([^)]*)\\)").find(header)?.groupValues?.get(1)
                    ?: error("shape missing from npy header")
            val dimensions =
                shape
                    .split(',')
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .map { it.toLong() }
            var count = 1L
            for (dimension in dimensions) count *= dimension
            return NpyHeader(offset + headerLength, descr, fortran, count)
        }
    }
}
