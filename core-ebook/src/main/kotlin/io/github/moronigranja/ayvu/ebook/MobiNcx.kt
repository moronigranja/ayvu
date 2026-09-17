/*
 * Ported from KindleUnpack — https://github.com/kevinhendricks/KindleUnpack
 * (GPL-3.0). This is a Kotlin reimplementation of its NCX indexing semantics
 * (MobiIndex/mobi_ncx), adapted to Ayvu's parser contracts; see NOTICE.md.
 */

package io.github.moronigranja.ayvu.ebook

/**
 * MOBI7 NCX ("filepos" navigation index) reader, mirroring KindleUnpack's
 * `MobiIndex`/`mobi_ncx` semantics for the PalmDOC layouts:
 *
 * - The MOBI header's NCX field (record-0 offset 0xF4) points at the first INDX
 *   record; `0xFFFFFFFF` means the book has no NCX at all.
 * - The main INDX record holds the header plus the TAGX tag table (at the offset
 *   named by its first header word). Entry records follow it immediately — as many
 *   as the main header's `count` word — each with its own INDX-style header
 *   (`start` = offset of the IDXT area inside the record, `count` = entries in it).
 * - Each IDXT entry is `[1-byte label length][label bytes][control bytes][values]`.
 *   The tag table says which tags live in the control byte(s); tag 1's first value
 *   is the nav point's **filepos** — a byte offset into the decompressed text
 *   records (the same byte space `MobiParser.readTextRecords` produces).
 * - Labels are UTF-8 (kindlegen convention); the rare codepage-65002 books translate
 *   labels through an ORDT2 table, read here exactly like KindleUnpack.
 *
 * Entries are returned in the order they are stored (document order for kindlegen
 * NCX indices), including every entry that resolves a tag-1 value: range/duplicate
 * filtering happens in the caller where the decompressed text length is known.
 *
 * Everything is advisory: malformed or inconsistent structures yield fewer (or zero)
 * nav points, never an exception, so callers can fall back to whole-book parsing.
 */
internal object MobiNcx {
    /** One nav point: [label] (null when the entry carries no usable label) and its
     *  byte offset (filepos) into the decompressed text records. */
    data class NavPoint(
        val label: String?,
        val posByte: Long,
    )

    private const val NO_INDEX = 0xFFFFFFFFL
    private const val ORDT_CODE = 0xFDEAL
    private const val INDX_HEADER_SIZE = 0x38

    /** One TAGX entry: tag id, values-per-entry, control-byte mask, end-flag. */
    private data class Tag(
        val id: Int,
        val valuesPerEntry: Int,
        val mask: Int,
        val endFlag: Int,
    )

    /** TAGX tag table plus the control-byte count shared by every IDXT entry. */
    private class TagTable(
        val tags: List<Tag>,
        val controlByteCount: Int,
    )

    private class PendingTag(
        val id: Int,
        val valueCount: Int?,
        val valueBytes: Int,
        val valuesPerEntry: Int,
    )

    fun navPoints(
        container: MobiContainer,
        mobiHeader: ByteArray?,
    ): List<NavPoint> {
        if (mobiHeader == null || mobiHeader.size < 0xF8) return emptyList()
        val idx = Bytes.u32(mobiHeader, 0xF4)
        if (idx == NO_INDEX || idx > Int.MAX_VALUE) return emptyList()
        val main = container.records.getOrNull(idx.toInt()) ?: return emptyList()
        if (main.size < 0xB4 || !Bytes.hasText(main, 0, "INDX")) return emptyList()

        val table = readTagTable(main)
        val ordt2 = readOrdt2(main)
        if (table == null) return emptyList()

        val points = mutableListOf<NavPoint>()
        val idxtCount = Bytes.u32(main, 0x18)
        for (i in 1L..idxtCount) {
            val section = idx + i
            if (section > Int.MAX_VALUE) break
            val record = container.records.getOrNull(section.toInt()) ?: break
            readRecord(record, table, ordt2, points)
        }
        return points
    }

    /** TAGX table at the main record's first header word; null when absent/broken. */
    private fun readTagTable(main: ByteArray): TagTable? {
        val tagxOffset = Bytes.u32(main, 0x04).toInt()
        if (tagxOffset < 0 || tagxOffset + 16 > main.size || !Bytes.hasText(main, tagxOffset, "TAGX")) return null
        val firstEntryOffset = Bytes.u32(main, tagxOffset + 4).toInt()
        val controlByteCount = Bytes.u32(main, tagxOffset + 8)
        if (firstEntryOffset < 12 || tagxOffset + firstEntryOffset > main.size || controlByteCount > 0xFFFF) return null
        val tags = mutableListOf<Tag>()
        var pos = tagxOffset + 12
        val end = tagxOffset + firstEntryOffset
        while (pos + 4 <= end) {
            tags += Tag(Bytes.u8(main, pos), Bytes.u8(main, pos + 1), Bytes.u8(main, pos + 2), Bytes.u8(main, pos + 3))
            pos += 4
        }
        if (tags.isEmpty()) return null
        return TagTable(tags, controlByteCount.toInt())
    }

    /**
     * ORDT2 label-translation table, present only for the hacked codepage-65002 books
     * KindleUnpack special-cases. Every other book (and every malformed variant) has
     * no table and decodes labels as plain UTF-8.
     */
    private fun readOrdt2(main: ByteArray): ShortArray? {
        val ocnt = Bytes.u32(main, 0xA4)
        val oentries = Bytes.u32(main, 0xA8)
        val hasOrdt = ocnt != 0L || oentries > 0L || Bytes.u32(main, 0x1C) == ORDT_CODE
        if (!hasOrdt || ocnt != 1L || oentries !in 1..0xFFFF) return null
        val op2 = Bytes.u32(main, 0xB0)
        if (op2 > main.size - 8L - 2L * oentries) return null
        if (!Bytes.hasText(main, op2.toInt(), "ORDT")) return null
        return ShortArray(oentries.toInt()) { Bytes.u16(main, op2.toInt() + 4 + 2 * it).toShort() }
    }

    private fun readRecord(
        record: ByteArray,
        table: TagTable,
        ordt2: ShortArray?,
        out: MutableList<NavPoint>,
    ) {
        if (record.size < INDX_HEADER_SIZE || !Bytes.hasText(record, 0, "INDX")) return
        val idxtPos = Bytes.u32(record, 0x14)
        val entryCount = Bytes.u32(record, 0x18)
        if (idxtPos < 0 || idxtPos + 4L + 2L * entryCount > record.size) return
        // bounds check above caps entryCount well below Int.MAX_VALUE
        for (j in 0 until entryCount.toInt()) {
            val start = Bytes.u16(record, idxtPos.toInt() + 4 + 2 * j)
            readEntry(record, start, table, ordt2)?.let(out::add)
        }
    }

    private fun readEntry(
        record: ByteArray,
        start: Int,
        table: TagTable,
        ordt2: ShortArray?,
    ): NavPoint? {
        if (start < 0 || start >= record.size) return null
        val labelLen = Bytes.u8(record, start)
        if (start + 1 + labelLen > record.size) return null
        val labelBytes = record.copyOfRange(start + 1, start + 1 + labelLen)
        val tagMap = readTagValues(record, start + 1 + labelLen, table) ?: return null
        val pos = tagMap[1]?.firstOrNull() ?: return null
        val label = decodeLabel(labelBytes, ordt2)
        return NavPoint(label, pos)
    }

    /**
     * Builds the per-entry tag → values map. Direct port of KindleUnpack's
     * `getTagMap` (control bytes, multi-bit full-mask → vwi byte length, shifted
     * partial values), with bounds checks instead of index errors: any overrun
     * aborts the entry (null), which the caller skips.
     */
    private fun readTagValues(
        record: ByteArray,
        controlStart: Int,
        table: TagTable,
    ): Map<Int, List<Long>>? {
        val pending = mutableListOf<PendingTag>()
        var controlIndex = 0
        var dataStart = controlStart + table.controlByteCount
        for (tag in table.tags) {
            if (tag.endFlag == 1) {
                controlIndex += 1
                continue
            }
            val controlOffset = controlStart + controlIndex
            if (controlOffset >= record.size) return null
            var mask = tag.mask
            var value = Bytes.u8(record, controlOffset) and mask
            if (value != 0) {
                if (value == mask && Integer.bitCount(mask) > 1) {
                    val (consumed, byteLength) = readVwi(record, dataStart) ?: return null
                    dataStart += consumed
                    pending += PendingTag(tag.id, null, byteLength.toInt(), tag.valuesPerEntry)
                } else if (value == mask) {
                    pending += PendingTag(tag.id, 1, 0, tag.valuesPerEntry)
                } else {
                    while (mask and 1 == 0) {
                        mask = mask ushr 1
                        value = value ushr 1
                    }
                    pending += PendingTag(tag.id, value, 0, tag.valuesPerEntry)
                }
            }
        }
        val out = mutableMapOf<Int, MutableList<Long>>()
        for (p in pending) {
            val values = out.getOrPut(p.id) { mutableListOf() }
            if (p.valueCount != null) {
                repeat(p.valueCount * p.valuesPerEntry) {
                    val (consumed, value) = readVwi(record, dataStart) ?: return null
                    dataStart += consumed
                    values += value
                }
            } else {
                val start = dataStart
                while (dataStart - start < p.valueBytes) {
                    val (consumed, value) = readVwi(record, dataStart) ?: return null
                    dataStart += consumed
                    values += value
                }
            }
        }
        return out
    }

    /** Amazon variable-width integer (7-bit groups, high bit ends), null past the end. */
    private fun readVwi(
        record: ByteArray,
        pos: Int,
    ): Pair<Int, Long>? {
        var value = 0L
        var p = pos
        while (p < record.size) {
            val b = Bytes.u8(record, p)
            p += 1
            value = (value shl 7) or (b and 0x7F).toLong()
            if ((b and 0x80) != 0) return p - pos to value
        }
        return null
    }

    /** UTF-8 label, ORDT2-translated for the rare 65002 books (KindleUnpack parity). */
    private fun decodeLabel(
        labelBytes: ByteArray,
        ordt2: ShortArray?,
    ): String? {
        val decoded =
            if (ordt2 != null) {
                val mapped =
                    ByteArray(labelBytes.size) { i ->
                        (ordt2[labelBytes[i].toInt() and 0xFF].toInt() and 0xFF).toByte()
                    }
                String(mapped, Charsets.UTF_8)
            } else {
                String(labelBytes, Charsets.UTF_8)
            }
        return OpfBookReader.decodeEntities(decoded).trim().ifEmpty { null }
    }
}
