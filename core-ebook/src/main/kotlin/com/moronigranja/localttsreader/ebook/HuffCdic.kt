package com.moronigranja.localttsreader.ebook

import java.io.ByteArrayOutputStream

/**
 * MOBI decompression, ported from KindleUnpack's `mobi_uncompress.py` (which is
 * itself the reference for the mobileRead spec):
 *
 * - [palmdocUnpack]: PalmDOC LZ77 — literal runs (len 1..8), space+byte (0xC0..0xFF),
 *   and 2-byte back-references (distance ≤ 2047, length 3..10).
 * - [HuffCdicDecoder]: HUFF/CDIC — canonical Huffman stream from the HUFF record
 *   tables indexing a CDIC phrase dictionary; compressed phrases are expanded
 *   recursively and memoized.
 */
internal object Palmdoc {
    fun unpack(input: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(input.size * 2)
        var p = 0
        while (p < input.size) {
            val c = input[p].toInt() and 0xFF
            p++
            when {
                c in 1..8 -> {
                    if (p + c <= input.size) {
                        out.write(input, p, c)
                        p += c
                    }
                }
                c < 128 -> out.write(c)
                c >= 192 -> {
                    out.write(' '.code)
                    out.write(c xor 128)
                }
                else -> {
                    if (p >= input.size) break
                    val word = (c shl 8) or (input[p].toInt() and 0xFF)
                    p++
                    val m = (word ushr 3) and 0x7FF
                    val n = (word and 7) + 3
                    val size = out.size()
                    if (m == 0 || m > size) break // corrupt input: stop cleanly
                    if (m > n) {
                        val buf = out.toByteArray()
                        out.write(buf, size - m, n)
                    } else {
                        val b = out.toByteArray()[size - m].toInt()
                        repeat(n) { out.write(b) }
                    }
                }
            }
        }
        return out.toByteArray()
    }
}

internal class HuffCdicDecoder {
    private val dict1Codelen = IntArray(256)
    private val dict1Term = BooleanArray(256)
    private val dict1Max = LongArray(256)
    private val mincode = LongArray(33)
    private val maxcode = LongArray(33)
    private val dictionary = mutableListOf<Pair<ByteArray, Boolean>>() // slice, isPlain

    fun loadHuff(huff: ByteArray) {
        if (!Bytes.hasText(huff, 0, "HUFF") || Bytes.u32(huff, 4) != 0x18L) {
            throw EBookParseException("invalid HUFF record")
        }
        val off1 = Bytes.u32(huff, 8).toInt()
        val off2 = Bytes.u32(huff, 12).toInt()
        for (i in 0 until 256) {
            val v = Bytes.u32(huff, off1 + 4 * i)
            val codelen = (v and 0x1F).toInt()
            if (codelen == 0) throw EBookParseException("invalid HUFF table (zero code length)")
            dict1Codelen[i] = codelen
            dict1Term[i] = (v and 0x80L) != 0L
            dict1Max[i] = ((((v ushr 8) + 1) shl (32 - codelen)) - 1) and 0xFFFFFFFFL
        }
        for (codelen in 1..32) {
            val min = Bytes.u32(huff, off2 + 8 * (codelen - 1))
            val max = Bytes.u32(huff, off2 + 8 * (codelen - 1) + 4)
            mincode[codelen] = (min shl (32 - codelen)) and 0xFFFFFFFFL
            maxcode[codelen] = (((max + 1) shl (32 - codelen)) - 1) and 0xFFFFFFFFL
        }
    }

    fun loadCdic(cdic: ByteArray) {
        if (!Bytes.hasText(cdic, 0, "CDIC") || Bytes.u32(cdic, 4) != 0x10L) {
            throw EBookParseException("invalid CDIC record")
        }
        val phrases = Bytes.u32(cdic, 8)
        val bits = Bytes.u32(cdic, 12).toInt()
        val n = minOf(1L shl bits, phrases - dictionary.size).toInt()
        if (n <= 0) return
        val startIndex = dictionary.size
        repeat(n) { dictionary.add(ByteArray(0) to true) } // placeholder, overwritten below
        for (k in 0 until n) {
            val off = Bytes.u16(cdic, 16 + 2 * k)
            val lenAndFlag = Bytes.u16(cdic, 16 + off)
            val length = lenAndFlag and 0x7FFF
            val from = 18 + off
            val to = from + length
            if (to > cdic.size) throw EBookParseException("corrupt CDIC record (slice out of range)")
            dictionary[startIndex + k] = cdic.copyOfRange(from, to) to ((lenAndFlag and 0x8000) != 0)
        }
    }

    /** Decompress one text section's byte stream. */
    fun unpack(data: ByteArray): ByteArray = unpack(data, depth = 0)

    private fun unpack(
        data: ByteArray,
        depth: Int,
    ): ByteArray {
        if (depth > 64) throw EBookParseException("corrupt HUFF stream (dictionary recursion too deep)")
        val padded = data + ByteArray(8)
        val out = ByteArrayOutputStream(data.size)
        var pos = 0
        var n = 32
        var x = Bytes.be64(padded, 0)
        var bitsLeft = data.size * 8L
        while (true) {
            if (n <= 0) {
                pos += 4
                x = Bytes.be64(padded, pos)
                n += 32
            }
            val code = (x ushr n) and 0xFFFFFFFFL
            val idx = (code ushr 24).toInt()
            var codelen = dict1Codelen[idx]
            var max = dict1Max[idx]
            if (!dict1Term[idx]) {
                while (code < mincode[codelen]) {
                    codelen++
                    if (codelen > 32) throw EBookParseException("corrupt HUFF stream (code length > 32)")
                }
                max = maxcode[codelen]
            }
            n -= codelen
            bitsLeft -= codelen
            if (bitsLeft < 0) break
            val r = ((max - code) ushr (32 - codelen)).toInt()
            if (r !in dictionary.indices) throw EBookParseException("corrupt HUFF stream (index out of range)")
            val (slice, plain) = dictionary[r]
            val decoded =
                if (plain) {
                    slice
                } else {
                    val expanded = unpack(slice, depth + 1)
                    dictionary[r] = expanded to true // memoize
                    expanded
                }
            out.write(decoded)
        }
        return out.toByteArray()
    }
}
