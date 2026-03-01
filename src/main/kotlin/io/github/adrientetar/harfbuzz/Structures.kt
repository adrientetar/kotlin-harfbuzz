package io.github.adrientetar.harfbuzz

import com.sun.jna.Memory
import com.sun.jna.Pointer
import com.sun.jna.Structure

/**
 * hb_feature_t - A feature to enable or disable during shaping.
 *
 * The `tag` is a 4-byte OpenType feature tag (e.g., 'kern', 'liga').
 * Set `value` to 0 to disable the feature, 1 to enable it.
 * `start` and `end` define the range of glyphs to apply to (use 0 and -1 for all).
 */
@Structure.FieldOrder("tag", "value", "start", "end")
internal open class HbFeature : Structure {
    @JvmField var tag: Int = 0       // hb_tag_t (4-byte feature tag)
    @JvmField var value: Int = 0     // 0 = disable, 1 = enable
    @JvmField var start: Int = 0     // start index (0 = from beginning)
    @JvmField var end: Int = -1      // end index (-1 = to end)

    constructor() : super()
    constructor(p: Pointer) : super(p) { read() }

    class ByReference : HbFeature(), Structure.ByReference
    class ByValue : HbFeature(), Structure.ByValue

    companion object {
        /** HB_FEATURE_GLOBAL_START */
        const val GLOBAL_START = 0

        /** HB_FEATURE_GLOBAL_END (unsigned -1) */
        const val GLOBAL_END = -1

        /**
         * Create a tag from a 4-character string.
         */
        fun makeTag(s: String): Int {
            require(s.length == 4) { "Tag must be exactly 4 characters" }
            return (s[0].code shl 24) or
                (s[1].code shl 16) or
                (s[2].code shl 8) or
                s[3].code
        }

        /**
         * Allocate a contiguous array of HbFeature structures in native memory.
         * Returns the Memory pointer and keeps structures alive.
         */
        fun allocateArray(features: List<HbFeature>): Pair<Memory, List<HbFeature>> {
            if (features.isEmpty()) return Pair(Memory(1), emptyList())
            val structSize = HbFeature().size()
            val mem = Memory((features.size * structSize).toLong())
            val allocated = features.mapIndexed { i, feat ->
                val ptr = mem.share((i * structSize).toLong())
                HbFeature(ptr).apply {
                    tag = feat.tag
                    value = feat.value
                    start = feat.start
                    end = feat.end
                    write()
                }
            }
            return Pair(mem, allocated)
        }
    }
}

/**
 * hb_glyph_info_t - Information about a shaped glyph.
 * 
 * After shaping, `codepoint` contains the glyph ID (not the original codepoint).
 * `cluster` maps back to the original text position.
 */
@Structure.FieldOrder("codepoint", "mask", "cluster", "var1", "var2")
internal open class HbGlyphInfo : Structure {
    @JvmField var codepoint: Int = 0  // glyph ID after shaping
    @JvmField var mask: Int = 0
    @JvmField var cluster: Int = 0
    @JvmField var var1: Int = 0       // internal
    @JvmField var var2: Int = 0       // internal

    constructor() : super()
    constructor(p: Pointer) : super(p) { read() }

    class ByReference : HbGlyphInfo(), Structure.ByReference
    class ByValue : HbGlyphInfo(), Structure.ByValue
}

/**
 * hb_glyph_position_t - Position information for a shaped glyph.
 * 
 * All values are in font units.
 */
@Structure.FieldOrder("x_advance", "y_advance", "x_offset", "y_offset", "var")
internal open class HbGlyphPosition : Structure {
    @JvmField var x_advance: Int = 0
    @JvmField var y_advance: Int = 0
    @JvmField var x_offset: Int = 0
    @JvmField var y_offset: Int = 0
    @JvmField var `var`: Int = 0      // internal

    constructor() : super()
    constructor(p: Pointer) : super(p) { read() }

    class ByReference : HbGlyphPosition(), Structure.ByReference
    class ByValue : HbGlyphPosition(), Structure.ByValue
}
