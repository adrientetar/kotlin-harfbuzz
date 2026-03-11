package io.github.adrientetar.harfbuzz

import com.sun.jna.Memory
import com.sun.jna.Pointer

/**
 * Shared helpers for blob management and buffer result extraction.
 */
internal object ShaperHelpers {
    // hb_glyph_info_t and hb_glyph_position_t are both 5 × int32
    private const val FIELDS_PER_STRUCT = 5

    /**
     * Prepare a native blob for an OpenType table override.
     */
    fun prepareOverrideBlob(
        tag: Int,
        data: ByteArray,
        overrideMemory: MutableMap<Int, Memory>,
        overrideBlobs: MutableMap<Int, Pointer>,
    ) {
        val mem = Memory(data.size.toLong())
        mem.write(0, data, 0, data.size)
        overrideMemory[tag] = mem

        val blob = HarfBuzz.hb_blob_create(
            mem,
            data.size,
            HbMemoryMode.READONLY,
            null,
            null,
        ) ?: throw HarfBuzzNativeException("Failed to create blob for table ${tagToString(tag)}")
        overrideBlobs[tag] = blob
    }

    /**
     * Prepare all override blobs from a [TableOverrides] instance.
     */
    fun prepareOverrides(
        overrides: TableOverrides?,
        overrideMemory: MutableMap<Int, Memory>,
        overrideBlobs: MutableMap<Int, Pointer>,
    ) {
        if (overrides == null) return
        overrides.gsub?.let { prepareOverrideBlob(HbTag.GSUB, it, overrideMemory, overrideBlobs) }
        overrides.gpos?.let { prepareOverrideBlob(HbTag.GPOS, it, overrideMemory, overrideBlobs) }
        overrides.gdef?.let { prepareOverrideBlob(HbTag.GDEF, it, overrideMemory, overrideBlobs) }
    }

    /**
     * Set buffer segment properties (direction, script, language).
     * If all are null, uses HarfBuzz auto-detection.
     */
    fun setSegmentProperties(
        buffer: Pointer,
        direction: Direction?,
        script: String?,
        language: String?,
    ) {
        if (direction != null) {
            HarfBuzz.hb_buffer_set_direction(buffer, direction.hbValue)
        }
        if (script != null) {
            val bytes = script.toByteArray(Charsets.US_ASCII)
            HarfBuzz.hb_buffer_set_script(buffer, HarfBuzz.hb_script_from_string(bytes, bytes.size))
        }
        if (language != null) {
            val bytes = language.toByteArray(Charsets.US_ASCII)
            HarfBuzz.hb_buffer_set_language(buffer, HarfBuzz.hb_language_from_string(bytes, bytes.size))
        }
        if (direction == null && script == null && language == null) {
            HarfBuzz.hb_buffer_guess_segment_properties(buffer)
        }
    }

    /**
     * Extract shaped glyphs from a HarfBuzz buffer.
     *
     * Reads entire arrays in bulk to minimize JNA transitions.
     * hb_glyph_info_t layout:     [codepoint:i32, mask:i32, cluster:i32, var1:i32, var2:i32] = 5 ints
     * hb_glyph_position_t layout: [x_advance:i32, y_advance:i32, x_offset:i32, y_offset:i32, var:i32] = 5 ints
     */
    fun extractGlyphs(buffer: Pointer): List<GlyphInfo> {
        val count = HarfBuzz.hb_buffer_get_length(buffer)
        if (count == 0) return emptyList()

        val infosPtr = HarfBuzz.hb_buffer_get_glyph_infos(buffer, null)
            ?: return emptyList()
        val positionsPtr = HarfBuzz.hb_buffer_get_glyph_positions(buffer, null)
            ?: return emptyList()

        // Bulk read: 2 JNA calls instead of 6*count
        val infos = infosPtr.getIntArray(0, count * FIELDS_PER_STRUCT)
        val positions = positionsPtr.getIntArray(0, count * FIELDS_PER_STRUCT)

        return (0 until count).map { i ->
            val ii = i * FIELDS_PER_STRUCT
            GlyphInfo(
                codepoint = infos[ii],         // codepoint field
                cluster = infos[ii + 2],     // cluster field (skip mask)
                xAdvance = positions[ii],
                yAdvance = positions[ii + 1],
                xOffset = positions[ii + 2],
                yOffset = positions[ii + 3],
            )
        }
    }

    /**
     * Resolve features to a native pointer and count.
     * Fast-paths [FeatureSet] to reuse its pre-allocated native memory.
     */
    fun allocateFeatures(features: Any?): Pair<Pointer?, Int> {
        return when (features) {
            null -> Pair(null, 0)
            is FeatureSet -> Pair(features.nativePtr, features.count)
            is List<*> -> {
                @Suppress("UNCHECKED_CAST")
                val featureList = features as List<Feature>
                if (featureList.isEmpty()) Pair(null, 0)
                else allocateFeatureList(featureList)
            }
            else -> Pair(null, 0)
        }
    }

    /**
     * Allocate native features array from a list of [Feature].
     * Writes directly to native memory using known hb_feature_t layout (4 × int32 = 16 bytes).
     */
    internal fun allocateFeatureList(features: List<Feature>): Pair<Pointer, Int> {
        val mem = Memory((features.size * 16).toLong())
        for ((i, feat) in features.withIndex()) {
            val offset = (i * 16).toLong()
            mem.setInt(offset, hbTag(feat.tag[0], feat.tag[1], feat.tag[2], feat.tag[3]))
            mem.setInt(offset + 4, feat.value)
            mem.setInt(offset + 8, feat.start)
            mem.setInt(offset + 12, feat.end)
        }
        return Pair(mem, features.size)
    }

    /**
     * Destroy all override blobs.
     */
    fun destroyOverrideBlobs(overrideBlobs: Map<Int, Pointer>) {
        for (blob in overrideBlobs.values) {
            HarfBuzz.hb_blob_destroy(blob)
        }
    }
}
