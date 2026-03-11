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
class VirtualHarfBuzzFont(
    override val upem: Int,
    glyphOrder: List<String>,
    /** Map from Unicode scalar value to glyph ID (GID) */
    private val unicodeToGid: Map<Int, Int>,
    /** Horizontal advances in font units, indexed by GID */
    private val hAdvances: IntArray,
    tableOverrides: TableOverrides? = null,
) : Font {

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

    private val glyphOrderValue: List<String> = glyphOrder
    // Pre-encode glyph names to avoid repeated UTF-8 encoding in callbacks
    private val glyphNameBytes: Array<ByteArray> = Array(glyphOrder.size) {
        glyphOrder[it].toByteArray(Charsets.UTF_8)
    }

    // Thread-safety: prevent use-after-free when close() is called during shaping
    private val closed = AtomicBoolean(false)
    private val shapingLock = ReentrantLock()

    init {
        // Prepare override blobs
        ShaperHelpers.prepareOverrides(tableOverrides, overrideMemory, overrideBlobs)

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
            ?: throw HarfBuzzNativeException("Failed to create HarfBuzz face for tables")

        // Configure face metrics / glyph count (required since we don't have a real font blob)
        HarfBuzz.hb_face_set_upem(face, upem)
        HarfBuzz.hb_face_set_glyph_count(face, glyphOrderValue.size)

        font = HarfBuzz.hb_font_create(face)
            ?: throw HarfBuzzNativeException("Failed to create HarfBuzz font")

        HarfBuzz.hb_font_set_scale(font, upem, upem)

        installFontFuncs()
    }

    private fun installFontFuncs() {
        val funcs = HarfBuzz.hb_font_funcs_create()
            ?: throw HarfBuzzNativeException("Failed to create hb_font_funcs")

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

                val bytes = glyphNameBytes.getOrNull(glyph) ?: return 0
                if (name == null || size <= 0) return 0

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

    /**
     * Shape a text string into positioned glyphs.
     *
     * Converts the string to Unicode codepoints and shapes them with
     * sequential cluster values.
     */
    override fun shape(
        text: String,
        direction: Direction?,
        script: String?,
        language: String?,
        features: List<Feature>?,
        buffer: Buffer?,
    ): List<GlyphInfo> {
        if (text.isEmpty()) return emptyList()
        val codepoints = text.codePoints().toArray()
        val clusters = IntArray(codepoints.size) { it }
        return doShapeCodepoints(codepoints, clusters, direction, script, language, features, buffer)
    }

    override fun shape(
        text: String,
        direction: Direction?,
        script: String?,
        language: String?,
        features: FeatureSet,
        buffer: Buffer?,
    ): List<GlyphInfo> {
        if (text.isEmpty()) return emptyList()
        val codepoints = text.codePoints().toArray()
        val clusters = IntArray(codepoints.size) { it }
        return doShapeCodepoints(codepoints, clusters, direction, script, language, features, buffer)
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
     * @param script Optional ISO 15924 script tag, e.g. "Latn", "Arab" (null = auto-detect)
     * @param language Optional BCP 47 language tag, e.g. "en", "ar" (null = auto-detect)
     * @param features Optional list of features to enable/disable during shaping
     * @param buffer Optional reusable [Buffer] for repeated shaping (null = allocate internally)
     * @throws IllegalStateException if the shaper has been closed
     */
    fun shapeCodepoints(
        codepoints: IntArray,
        clusters: IntArray,
        direction: Direction? = null,
        script: String? = null,
        language: String? = null,
        features: List<Feature>? = null,
        buffer: Buffer? = null,
    ): List<GlyphInfo> = doShapeCodepoints(codepoints, clusters, direction, script, language, features, buffer)

    /**
     * Shape codepoints with a pre-allocated [FeatureSet].
     */
    fun shapeCodepoints(
        codepoints: IntArray,
        clusters: IntArray,
        direction: Direction? = null,
        script: String? = null,
        language: String? = null,
        features: FeatureSet,
        buffer: Buffer? = null,
    ): List<GlyphInfo> = doShapeCodepoints(codepoints, clusters, direction, script, language, features, buffer)

    private fun doShapeCodepoints(
        codepoints: IntArray,
        clusters: IntArray,
        direction: Direction?,
        script: String?,
        language: String?,
        features: Any?,
        buffer: Buffer?,
    ): List<GlyphInfo> {
        if (codepoints.isEmpty()) return emptyList()
        require(codepoints.size == clusters.size) { "codepoints and clusters must be same length" }

        // Use lock to prevent close() from destroying resources while shaping is in progress
        return shapingLock.withLock {
            if (closed.get()) {
                throw HarfBuzzClosedException("VirtualHarfBuzzFont has been closed")
            }

            val buf: Pointer
            val ownsBuffer: Boolean
            if (buffer != null) {
                buf = buffer.ptr
                HarfBuzz.hb_buffer_clear_contents(buf)
                ownsBuffer = false
            } else {
                buf = HarfBuzz.hb_buffer_create()
                    ?: throw HarfBuzzNativeException("Failed to create HarfBuzz buffer")
                ownsBuffer = true
            }

            try {
                HarfBuzz.hb_buffer_pre_allocate(buf, codepoints.size)
                for (i in codepoints.indices) {
                    HarfBuzz.hb_buffer_add(buf, codepoints[i], clusters[i])
                }

                // hb_buffer_add does NOT set content type (unlike hb_buffer_add_utf*),
                // so we must set it manually before calling hb_buffer_guess_segment_properties
                // or hb_shape, both of which assert HB_BUFFER_CONTENT_TYPE_UNICODE.
                HarfBuzz.hb_buffer_set_content_type(buf, HbBufferContentType.UNICODE)

                // Set segment properties
                ShaperHelpers.setSegmentProperties(buf, direction, script, language)

                // Shape with optional features
                val (featuresPtr, numFeatures) = ShaperHelpers.allocateFeatures(features)
                HarfBuzz.hb_shape(font, buf, featuresPtr, numFeatures)

                // Extract results
                ShaperHelpers.extractGlyphs(buf)
            } finally {
                if (ownsBuffer) {
                    HarfBuzz.hb_buffer_destroy(buf)
                }
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
            ShaperHelpers.destroyOverrideBlobs(overrideBlobs)
            fontFuncs?.let { HarfBuzz.hb_font_funcs_destroy(it) }
            // Memory instances are freed when GC'd
        }
    }
}
