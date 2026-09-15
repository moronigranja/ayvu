package com.moronigranja.localttsreader.ebook

/**
 * Palm Database container shared by MOBI7 and KF8 formats, with the PalmDOC header
 * semantics from the mobileRead MOBI spec.
 *
 * Layout (record 0, first 16 bytes):
 *   0x00 u16 compression   1 = none, 2 = PalmDOC LZ77, 0x4448 = HUFF/CDIC
 *   0x04 u32 uncompressed text length
 *   0x08 u16 text record count
 *   0x0C u16 encryption    0 = none, 1 = old, 2 = current
 * The MOBI header proper starts at 0x10 with the identifier 'MOBI'.
 *
 * DRM-encrypted files are rejected up front (deDRM is out-of-app; see docs).
 */
internal class MobiContainer(
    bytes: ByteArray,
) {
    val records: List<ByteArray>
    val compression: Int
    val textRecords: Int
    val encryption: Int

    /** Sequential name from the 78-byte PDB header (book title for plain PalmDOC). */
    val palmName: String

    init {
        if (bytes.size < 78) throw EBookParseException("not a Palm database (too small)")
        val numRecords = Bytes.u16(bytes, 76)
        if (numRecords == 0) throw EBookParseException("Palm database has no records")
        val offsets = IntArray(numRecords) { Bytes.u32(bytes, 78 + 8 * it).toInt() }
        records =
            List(numRecords) { i ->
                val start = offsets[i]
                val end = if (i + 1 < numRecords) offsets[i + 1] else bytes.size
                if (start < 0 || end < start || end > bytes.size) {
                    throw EBookParseException("corrupt Palm database record table")
                }
                bytes.copyOfRange(start, end)
            }
        val palm = records.first()
        if (palm.size < 16) throw EBookParseException("PalmDOC header record too short")
        compression = Bytes.u16(palm, 0)
        textRecords = Bytes.u16(palm, 8)
        encryption = Bytes.u16(palm, 12)
        palmName = String(bytes, 0, 32, Charsets.US_ASCII).trimEnd('\u0000', ' ')
        if (encryption != 0) {
            throw EBookParseException(
                "DRM-encrypted ebook is not supported — the app only consumes DRM-free files (see docs)",
            )
        }
        if (compression !in setOf(1, 2) && compression != HUFF_CDIC) {
            throw EBookParseException("unsupported compression 0x%04x".format(compression))
        }
    }

    /** Record 0's MOBI header (starting at 0x10), or null when absent (plain PalmDOC). */
    fun mobiHeader(): ByteArray? {
        val rec0 = records.first()
        if (rec0.size < 0x14 || !Bytes.hasText(rec0, 0x10, "MOBI")) return null
        return rec0
    }

    companion object {
        const val HUFF_CDIC = 0x4448
    }
}
