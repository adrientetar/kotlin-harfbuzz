package io.github.adrientetar.harfbuzz

import com.sun.jna.Memory
import com.sun.jna.Pointer

/**
 * Result of shaping a single glyph.
 */
data class ShapedGlyph(
    val codepoint: Int,
    val cluster: Int,
    val xAdvance: Int,
    val yAdvance: Int,
    val xOffset: Int,
    val yOffset: Int,
)

/**
 * Text direction for shaping.
 */
enum class TextDirection(val hbValue: Int) {
    LTR(HbDirection.LTR),
    RTL(HbDirection.RTL),
}

/**
 * Optional OpenType tables to override during shaping.
 * Used for live preview with fea-rs compiled tables.
 */
data class TableOverrides(
    val gsub: ByteArray? = null,
    val gpos: ByteArray? = null,
    val gdef: ByteArray? = null,
)

/**
 * High-level HarfBuzz shaper with proper resource management.
 * 
 * Usage:
 * ```
 * val fontBytes = File("myfont.ttf").readBytes()
 * HarfBuzzShaper(fontBytes).use { shaper ->
 *     val glyphs = shaper.shape("Hello")
 * }
 * ```
 * 
 * For live preview with fea-rs tables:
 * ```
 * val overrides = TableOverrides(gsub = gsubBytes, gpos = gposBytes)
 * HarfBuzzShaper(fontBytes, overrides).use { shaper ->
 *     val glyphs = shaper.shape("Hello")
 * }
 * ```
 */
class HarfBuzzShaper(
    fontData: ByteArray,
    tableOverrides: TableOverrides? = null,
) : AutoCloseable {
    private val fontMemory: Memory
    private val blob: Pointer
    private val baseFace: Pointer
    private val face: Pointer
    private val font: Pointer
    
    // Must keep references to prevent GC during callback lifetime
    private val overrideBlobs = mutableMapOf<Int, Pointer>()
    private val overrideMemory = mutableMapOf<Int, Memory>()
    private var tableCallback: HbReferenceTableFunc? = null

    val upem: Int

    init {
        // Copy font data to native memory
        fontMemory = Memory(fontData.size.toLong())
        fontMemory.write(0, fontData, 0, fontData.size)

        // Create base blob and face
        blob = HarfBuzz.hb_blob_create(
            fontMemory,
            fontData.size,
            HbMemoryMode.READONLY,
            null,
            null,
        ) ?: error("Failed to create HarfBuzz blob")

        baseFace = HarfBuzz.hb_face_create(blob, 0)
            ?: error("Failed to create HarfBuzz face")

        // Create face with optional table overrides
        face = if (tableOverrides != null && hasOverrides(tableOverrides)) {
            createFaceWithOverrides(tableOverrides)
        } else {
            baseFace
        }

        font = HarfBuzz.hb_font_create(face)
            ?: error("Failed to create HarfBuzz font")

        upem = HarfBuzz.hb_face_get_upem(face)
        HarfBuzz.hb_font_set_scale(font, upem, upem)
    }

    private fun hasOverrides(overrides: TableOverrides): Boolean =
        overrides.gsub != null || overrides.gpos != null || overrides.gdef != null

    private fun createFaceWithOverrides(overrides: TableOverrides): Pointer {
        // Prepare override blobs
        overrides.gsub?.let { prepareOverrideBlob(HbTag.GSUB, it) }
        overrides.gpos?.let { prepareOverrideBlob(HbTag.GPOS, it) }
        overrides.gdef?.let { prepareOverrideBlob(HbTag.GDEF, it) }

        // Create callback that returns overrides or delegates to base face
        tableCallback = object : HbReferenceTableFunc {
            override fun invoke(face: Pointer?, tag: Int, userData: Pointer?): Pointer? {
                // Check if we have an override for this tag
                overrideBlobs[tag]?.let { return it }
                // Otherwise delegate to base face
                return HarfBuzz.hb_face_reference_table(baseFace, tag)
            }
        }

        return HarfBuzz.hb_face_create_for_tables(tableCallback, null, null)
            ?: error("Failed to create HarfBuzz face with table overrides")
    }

    private fun prepareOverrideBlob(tag: Int, data: ByteArray) {
        val mem = Memory(data.size.toLong())
        mem.write(0, data, 0, data.size)
        overrideMemory[tag] = mem

        val blob = HarfBuzz.hb_blob_create(
            mem,
            data.size,
            HbMemoryMode.READONLY,
            null,
            null,
        ) ?: error("Failed to create blob for table ${tagToString(tag)}")
        overrideBlobs[tag] = blob
    }

    /**
     * Shape a text string into positioned glyphs.
     */
    fun shape(
        text: String,
        direction: TextDirection? = null,
    ): List<ShapedGlyph> {
        if (text.isEmpty()) return emptyList()

        val buffer = HarfBuzz.hb_buffer_create()
            ?: error("Failed to create HarfBuzz buffer")

        try {
            // Add text as UTF-16
            val chars = text.toCharArray()
            HarfBuzz.hb_buffer_add_utf16(buffer, chars, chars.size, 0, chars.size)

            // Set direction or auto-detect
            if (direction != null) {
                HarfBuzz.hb_buffer_set_direction(buffer, direction.hbValue)
            } else {
                HarfBuzz.hb_buffer_guess_segment_properties(buffer)
            }

            // Shape
            HarfBuzz.hb_shape(font, buffer, null, 0)

            // Extract results
            val count = HarfBuzz.hb_buffer_get_length(buffer)
            if (count == 0) return emptyList()

            val infosPtr = HarfBuzz.hb_buffer_get_glyph_infos(buffer, null)
                ?: return emptyList()
            val positionsPtr = HarfBuzz.hb_buffer_get_glyph_positions(buffer, null)
                ?: return emptyList()

            val infoSize = HbGlyphInfo().size()
            val posSize = HbGlyphPosition().size()

            return (0 until count).map { i ->
                val info = HbGlyphInfo(infosPtr.share((i * infoSize).toLong()))
                val pos = HbGlyphPosition(positionsPtr.share((i * posSize).toLong()))

                ShapedGlyph(
                    codepoint = info.codepoint,
                    cluster = info.cluster,
                    xAdvance = pos.x_advance,
                    yAdvance = pos.y_advance,
                    xOffset = pos.x_offset,
                    yOffset = pos.y_offset,
                )
            }
        } finally {
            HarfBuzz.hb_buffer_destroy(buffer)
        }
    }

    override fun close() {
        HarfBuzz.hb_font_destroy(font)
        if (face != baseFace) {
            HarfBuzz.hb_face_destroy(face)
        }
        HarfBuzz.hb_face_destroy(baseFace)
        for (blob in overrideBlobs.values) {
            HarfBuzz.hb_blob_destroy(blob)
        }
        HarfBuzz.hb_blob_destroy(blob)
        // Memory instances are freed when GC'd
    }
}
