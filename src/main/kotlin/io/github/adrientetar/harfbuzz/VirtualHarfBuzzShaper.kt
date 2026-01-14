package io.github.adrientetar.harfbuzz

import com.sun.jna.Memory
import com.sun.jna.Pointer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * HarfBuzz shaper backed only by OpenType layout tables (GSUB/GPOS/GDEF)
 * and custom font callbacks for cmap/metrics.
 *
 * We provide HarfBuzz with:
 * - A face created from in-memory tables (via hb_face_create_for_tables)
 * - A font funcs vtable that implements nominal glyph mapping and advances
 *
 * This enables shaping in an editor without building a full binary font.
 */
class VirtualHarfBuzzShaper(
    upem: Int,
    glyphOrder: List<String>,
    /** Map from Unicode scalar value to glyph ID (GID) */
    private val unicodeToGid: Map<Int, Int>,
    /** Horizontal advances in font units, indexed by GID */
    private val hAdvances: IntArray,
    tableOverrides: TableOverrides? = null,
) : AutoCloseable {

    companion object {
        /**
         * Base prefix for unencoded glyphs. We add it to the glyph GID so we can
         * address unencoded glyphs via hb_codepoint_t values.
         */
        val CH_GID_PREFIX: UInt = 0x8000_0000u
    }

    private val face: Pointer
    private val font: Pointer

    // Must keep references to prevent GC during callback lifetime
    private val overrideBlobs = mutableMapOf<Int, Pointer>()
    private val overrideMemory = mutableMapOf<Int, Memory>()
    private var tableCallback: HbReferenceTableFunc? = null

    private var fontFuncs: Pointer? = null
    private var nominalGlyphFunc: HbNominalGlyphFunc? = null
    private var hAdvanceFunc: HbGlyphHAdvanceFunc? = null
    private var glyphNameFunc: HbGlyphNameFunc? = null

    private val upemValue: Int = upem
    private val glyphOrderValue: List<String> = glyphOrder

    // Thread-safety: prevent use-after-free when close() is called during shaping
    private val closed = AtomicBoolean(false)
    private val shapingLock = ReentrantLock()

    init {
        // Prepare override blobs
        if (tableOverrides != null) {
            tableOverrides.gsub?.let { prepareOverrideBlob(HbTag.GSUB, it) }
            tableOverrides.gpos?.let { prepareOverrideBlob(HbTag.GPOS, it) }
            tableOverrides.gdef?.let { prepareOverrideBlob(HbTag.GDEF, it) }
        }

        // Table callback for hb_face_create_for_tables
        tableCallback = object : HbReferenceTableFunc {
            override fun invoke(face: Pointer?, tag: Int, userData: Pointer?): Pointer? {
                if (closed.get()) return HarfBuzz.hb_blob_get_empty()

                val blob = overrideBlobs[tag]
                // hb_face_create_for_tables expects the callback to return a hb_blob_t* that the
                // caller owns (and will hb_blob_destroy when done). If we return the same blob
                // pointer without incrementing its refcount, HarfBuzz can destroy it multiple
                // times across table lookups, leading to heap corruption / SIGSEGV.
                return if (blob != null) {
                    HarfBuzz.hb_blob_reference(blob)
                } else {
                    HarfBuzz.hb_blob_reference(HarfBuzz.hb_blob_get_empty())
                }
            }
        }

        face = HarfBuzz.hb_face_create_for_tables(tableCallback, null, null)
            ?: error("Failed to create HarfBuzz face for tables")

        // Configure face metrics / glyph count (required since we don't have a real font blob)
        HarfBuzz.hb_face_set_upem(face, upemValue)
        HarfBuzz.hb_face_set_glyph_count(face, glyphOrderValue.size)

        font = HarfBuzz.hb_font_create(face)
            ?: error("Failed to create HarfBuzz font")

        HarfBuzz.hb_font_set_scale(font, upemValue, upemValue)

        installFontFuncs()
    }

    private fun installFontFuncs() {
        val funcs = HarfBuzz.hb_font_funcs_create()
            ?: error("Failed to create hb_font_funcs")

        nominalGlyphFunc = object : HbNominalGlyphFunc {
            override fun invoke(
                font: Pointer?,
                fontData: Pointer?,
                unicode: Int,
                glyph: Pointer?,
                userData: Pointer?,
            ): Int {
                // Guard against use-after-free: return 0 if shaper is closed
                if (closed.get()) return 0

                val u = unicode.toUInt()

                // Explicit glyph-id encoding (unencoded glyphs)
                if (u >= CH_GID_PREFIX) {
                    val gid = (u - CH_GID_PREFIX).toInt()
                    if (gid in glyphOrderValue.indices && glyph != null) {
                        glyph.setInt(0, gid)
                        return 1
                    }
                    return 0
                }

                val gid = unicodeToGid[unicode] ?: return 0
                if (glyph != null) {
                    glyph.setInt(0, gid)
                    return 1
                }
                return 0
            }
        }

        hAdvanceFunc = object : HbGlyphHAdvanceFunc {
            override fun invoke(font: Pointer?, fontData: Pointer?, glyph: Int, userData: Pointer?): Int {
                // Guard against use-after-free: return 0 if shaper is closed
                if (closed.get()) return 0
                return hAdvances.getOrElse(glyph) { 0 }
            }
        }

        glyphNameFunc = object : HbGlyphNameFunc {
            override fun invoke(
                font: Pointer?,
                fontData: Pointer?,
                glyph: Int,
                name: Pointer?,
                size: Int,
                userData: Pointer?,
            ): Int {
                // Guard against use-after-free: return 0 if shaper is closed
                if (closed.get()) return 0

                val glyphName = glyphOrderValue.getOrNull(glyph) ?: return 0
                if (name == null || size <= 0) return 0

                val bytes = glyphName.toByteArray(Charsets.UTF_8)
                val toCopy = minOf(bytes.size, size - 1)
                name.write(0, bytes, 0, toCopy)
                name.setByte(toCopy.toLong(), 0)
                return 1
            }
        }

        HarfBuzz.hb_font_funcs_set_nominal_glyph_func(funcs, nominalGlyphFunc, null, null)
        HarfBuzz.hb_font_funcs_set_glyph_h_advance_func(funcs, hAdvanceFunc, null, null)
        HarfBuzz.hb_font_funcs_set_glyph_name_func(funcs, glyphNameFunc, null, null)
        HarfBuzz.hb_font_funcs_make_immutable(funcs)

        HarfBuzz.hb_font_set_funcs(font, funcs, null, null)
        fontFuncs = funcs
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
     * Shape an explicit sequence of hb_codepoint_t + cluster values.
     *
     * This is the mode needed for editor shaping where we want:
     * - deterministic clusters from our parsed text model
     * - ability to address unencoded glyphs via CH_GID_PREFIX+gid
     *
     * @param codepoints Array of codepoints to shape
     * @param clusters Array of cluster values (must be same length as codepoints)
     * @param direction Optional text direction (null = auto-detect)
     * @param features Optional list of features to enable/disable during shaping
     * @throws IllegalStateException if the shaper has been closed
     */
    fun shapeCodepoints(
        codepoints: IntArray,
        clusters: IntArray,
        direction: TextDirection? = null,
        features: List<HbFeature>? = null,
    ): List<ShapedGlyph> {
        if (codepoints.isEmpty()) return emptyList()
        require(codepoints.size == clusters.size) { "codepoints and clusters must be same length" }

        // Use lock to prevent close() from destroying resources while shaping is in progress
        return shapingLock.withLock {
            if (closed.get()) {
                throw IllegalStateException("VirtualHarfBuzzShaper has been closed")
            }

            val buffer = HarfBuzz.hb_buffer_create()
                ?: error("Failed to create HarfBuzz buffer")

            try {
                for (i in codepoints.indices) {
                    HarfBuzz.hb_buffer_add(buffer, codepoints[i], clusters[i])
                }

                // hb_buffer_add does NOT set content type (unlike hb_buffer_add_utf*),
                // so we must set it manually before calling hb_buffer_guess_segment_properties
                // or hb_shape, both of which assert HB_BUFFER_CONTENT_TYPE_UNICODE.
                HarfBuzz.hb_buffer_set_content_type(buffer, HbBufferContentType.UNICODE)

                if (direction != null) {
                    HarfBuzz.hb_buffer_set_direction(buffer, direction.hbValue)
                } else {
                    HarfBuzz.hb_buffer_guess_segment_properties(buffer)
                }

                // Allocate features array if provided
                val (featuresPtr, numFeatures) = if (features != null && features.isNotEmpty()) {
                    val (mem, _) = HbFeature.allocateArray(features)
                    Pair(mem, features.size)
                } else {
                    Pair(null, 0)
                }

                HarfBuzz.hb_shape(font, buffer, featuresPtr, numFeatures)

                val count = HarfBuzz.hb_buffer_get_length(buffer)
                if (count == 0) return@withLock emptyList()

                val infosPtr = HarfBuzz.hb_buffer_get_glyph_infos(buffer, null)
                    ?: return@withLock emptyList()
                val positionsPtr = HarfBuzz.hb_buffer_get_glyph_positions(buffer, null)
                    ?: return@withLock emptyList()

                val infoSize = HbGlyphInfo().size()
                val posSize = HbGlyphPosition().size()

                (0 until count).map { i ->
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
    }

    override fun close() {
        // Acquire lock to ensure no shaping operation is in progress
        shapingLock.withLock {
            if (closed.getAndSet(true)) {
                return // Already closed
            }

            HarfBuzz.hb_font_destroy(font)
            HarfBuzz.hb_face_destroy(face)
            for (blob in overrideBlobs.values) {
                HarfBuzz.hb_blob_destroy(blob)
            }
            fontFuncs?.let { HarfBuzz.hb_font_funcs_destroy(it) }
            // Memory instances are freed when GC'd
        }
    }
}
