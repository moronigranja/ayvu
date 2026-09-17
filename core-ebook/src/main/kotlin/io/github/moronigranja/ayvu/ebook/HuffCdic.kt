/*
 * Ported from KindleUnpack — https://github.com/kevinhendricks/KindleUnpack
 * (GPL-3.0). This is a Kotlin reimplementation of its MOBI decompression
 * semantics, adapted to Ayvu's parser contracts; see NOTICE.md.
 */

package io.github.moronigranja.ayvu.ebook

/**
 * MOBI decompression, ported from KindleUnpack's `mobi_uncompress.py` (which is
 * itself the reference for the mobileRead spec):
 *
 * - [Palmdoc]: PalmDOC LZ77 — literal runs (len 1..8), space+byte (0xC0..0xFF),
 *   and 2-byte back-references (distance ≤ 2047, length 3..10).
 * - [HuffCdicDecoder]: HUFF/CDIC — canonical Huffman stream from the HUFF record
 *   tables indexing a CDIC phrase dictionary; compressed phrases are expanded
 *   recursively and memoized.
 *
 * Both write through [CappedTextOutput], so a record's expansion — nested phrase
 * expansions included — is bounded by [ImportLimits] as it is produced.
 */
internal object Palmdoc {
    /**
     * PalmDOC LZ77 decode of one text record. [expandedSoFar] is what the earlier records of the
     * same book already expanded to, so the record's ceiling and the book-wide one both apply.
     */
    fun unpack(
        input: ByteArray,
        expandedSoFar: Long = 0L,
        limits: ImportLimits = ImportLimits.DEFAULT,
    ): ByteArray {
        val out =
            CappedTextOutput(
                TextExpansionBudget(limits, expandedSoFar),
                initialBytes = minOf(input.size * 2, IO_BUFFER_BYTES),
            )
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

    /**
     * One HUFF record's code tables. The table offsets come from the record's own header, so each
     * one is range-checked: a crafted offset raises [EBookParseException], never an index crash.
     */
    fun loadHuff(huff: ByteArray) {
        if (huff.size < HUFF_HEADER_BYTES || !Bytes.hasText(huff, 0, "HUFF") || Bytes.u32(huff, 4) != 0x18L) {
            throw EBookParseException("invalid HUFF record")
        }
        val off1 = Bytes.u32(huff, 8).toInt()
        val off2 = Bytes.u32(huff, 12).toInt()
        if (off1 < 0 || off1.toLong() + CODE_LENGTH_TABLE_BYTES > huff.size) {
            throw EBookParseException("corrupt HUFF record (code-length table out of range)")
        }
        if (off2 < 0 || off2.toLong() + MIN_MAX_TABLE_BYTES > huff.size) {
            throw EBookParseException("corrupt HUFF record (min/max code table out of range)")
        }
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

    /**
     * One CDIC record's phrase table. Its header words are untrusted input: `1 shl bits` alone
     * reaches 2^31, so the table is bounded by what the record actually carries — one 2-byte phrase
     * offset each — and by the format's per-record phrase ceiling; an 18-byte record can therefore
     * never drive a 2^31-entry allocation. Every offset and length read is range-checked into a
     * typed [EBookParseException].
     */
    fun loadCdic(cdic: ByteArray) {
        if (cdic.size < CDIC_HEADER_BYTES || !Bytes.hasText(cdic, 0, "CDIC") || Bytes.u32(cdic, 4) != 0x10L) {
            throw EBookParseException("invalid CDIC record")
        }
        val phrases = Bytes.u32(cdic, 8)
        val bits = Bytes.u32(cdic, 12)
        val declared = if (bits in 0..31) 1L shl bits.toInt() else Long.MAX_VALUE
        val inRecord = ((cdic.size - CDIC_HEADER_BYTES) / 2).toLong()
        val n = minOf(declared, phrases - dictionary.size, inRecord, MAX_PHRASES_PER_RECORD)
        if (n <= 0) return
        val count = n.toInt()
        val startIndex = dictionary.size
        repeat(count) { dictionary.add(ByteArray(0) to true) } // placeholder, overwritten below
        for (k in 0 until count) {
            val off = readU16(cdic, CDIC_HEADER_BYTES + 2 * k, "phrase offset")
            val lenAndFlag = readU16(cdic, CDIC_HEADER_BYTES + off, "phrase length")
            val length = lenAndFlag and 0x7FFF
            val from = CDIC_HEADER_BYTES + 2 + off
            val to = from + length
            if (to > cdic.size) throw EBookParseException("corrupt CDIC record (slice out of range)")
            dictionary[startIndex + k] = cdic.copyOfRange(from, to) to ((lenAndFlag and 0x8000) != 0)
        }
    }

    /**
     * Decompress one text section's byte stream. [expandedSoFar] is what the earlier text records
     * of the same book already expanded to; [limits] bounds this record AND every nested phrase
     * expansion it triggers, because all of them draw on the one [CappedTextOutput] budget.
     */
    fun unpack(
        data: ByteArray,
        expandedSoFar: Long = 0L,
        limits: ImportLimits = ImportLimits.DEFAULT,
    ): ByteArray = decode(data, TextExpansionBudget(limits, expandedSoFar), depth = 0)

    private fun decode(
        data: ByteArray,
        budget: TextExpansionBudget,
        depth: Int,
    ): ByteArray {
        if (depth > 64) throw EBookParseException("corrupt HUFF stream (dictionary recursion too deep)")
        val padded = data + ByteArray(8)
        val out = CappedTextOutput(budget, initialBytes = minOf(data.size, IO_BUFFER_BYTES))
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
                    val expanded = decode(slice, budget, depth + 1)
                    dictionary[r] = expanded to true // memoize
                    expanded
                }
            out.write(decoded)
        }
        return out.toByteArray()
    }

    /** Range-checked [Bytes.u16]: a crafted CDIC offset must fail typed, never as an index crash. */
    private fun readU16(
        bytes: ByteArray,
        off: Int,
        what: String,
    ): Int {
        if (off < 0 || off + 2 > bytes.size) throw EBookParseException("corrupt CDIC record ($what out of range)")
        return Bytes.u16(bytes, off)
    }

    private companion object {
        /** "HUFF" + version + the two table offsets. */
        const val HUFF_HEADER_BYTES = 16

        /** "CDIC" + version + phrase count + bit width. */
        const val CDIC_HEADER_BYTES = 16

        /** One code-length word per top byte of the code. */
        const val CODE_LENGTH_TABLE_BYTES = 4 * 256

        /** A min/max pair for each of the 32 code lengths. */
        const val MIN_MAX_TABLE_BYTES = 8 * 32

        /** The phrase ceiling of one CDIC record: a real header's bit width is ≤ 16 (`1 shl bits` is
         *  the record's phrase chunk size), so nothing legitimate declares more. */
        const val MAX_PHRASES_PER_RECORD = 1L shl 16
    }
}
