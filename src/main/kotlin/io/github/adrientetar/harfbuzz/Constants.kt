package io.github.adrientetar.harfbuzz

/** Memory modes for hb_blob_create */
object HbMemoryMode {
    const val DUPLICATE = 0
    const val READONLY = 1
    const val WRITABLE = 2
    const val READONLY_MAY_MAKE_WRITABLE = 3
}

/** Text directions */
object HbDirection {
    const val INVALID = 0
    const val LTR = 4
    const val RTL = 5
    const val TTB = 6
    const val BTT = 7
}

/** Common OpenType table tags */
object HbTag {
    val GSUB = hbTag('G', 'S', 'U', 'B')
    val GPOS = hbTag('G', 'P', 'O', 'S')
    val GDEF = hbTag('G', 'D', 'E', 'F')
    val cmap = hbTag('c', 'm', 'a', 'p')
    val head = hbTag('h', 'e', 'a', 'd')
    val hhea = hbTag('h', 'h', 'e', 'a')
    val hmtx = hbTag('h', 'm', 't', 'x')
    val maxp = hbTag('m', 'a', 'x', 'p')
    val name = hbTag('n', 'a', 'm', 'e')
    val OS2  = hbTag('O', 'S', '/', '2')
    val post = hbTag('p', 'o', 's', 't')
    val loca = hbTag('l', 'o', 'c', 'a')
    val glyf = hbTag('g', 'l', 'y', 'f')
    val CFF  = hbTag('C', 'F', 'F', ' ')
    val CFF2 = hbTag('C', 'F', 'F', '2')
}

/**
 * Create an OpenType tag from 4 characters.
 * Tags are big-endian 4-byte integers.
 */
fun hbTag(c1: Char, c2: Char, c3: Char, c4: Char): Int =
    (c1.code shl 24) or (c2.code shl 16) or (c3.code shl 8) or c4.code

/** Convert tag int back to string for debugging */
fun tagToString(tag: Int): String = buildString {
    append((tag shr 24 and 0xFF).toChar())
    append((tag shr 16 and 0xFF).toChar())
    append((tag shr 8 and 0xFF).toChar())
    append((tag and 0xFF).toChar())
}
