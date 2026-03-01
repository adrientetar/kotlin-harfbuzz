package io.github.adrientetar.harfbuzz

import com.sun.jna.Memory
import com.sun.jna.Pointer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Result of shaping a single glyph.
 */
data class ShapedGlyph(
    val glyphId: Int,
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
class TableOverrides(
    val gsub: ByteArray? = null,
    val gpos: ByteArray? = null,
    val gdef: ByteArray? = null,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is TableOverrides) return false
        return gsub.contentEquals(other.gsub)
            && gpos.contentEquals(other.gpos)
            && gdef.contentEquals(other.gdef)
    }

    override fun hashCode(): Int {
        var result = gsub?.contentHashCode() ?: 0
        result = 31 * result + (gpos?.contentHashCode() ?: 0)
        result = 31 * result + (gdef?.contentHashCode() ?: 0)
        return result
    }

    private fun ByteArray?.contentEquals(other: ByteArray?): Boolean =
        if (this == null) other == null else other != null && this.contentEquals(other)
}

/**
 * High-level HarfBuzz shaper with proper resource management.
 * 
 * Usage:
 * ```
 * val fontBytes = File("myfont.ttf").readBytes()
 * HarfBuzzFont(fontBytes).use { font ->
 *     val glyphs = font.shape("Hello")
 * }
 * ```
 * 
 * For live preview with fea-rs tables:
 * ```
 * val overrides = TableOverrides(gsub = gsubBytes, gpos = gposBytes)
 * HarfBuzzFont(fontBytes, overrides).use { font ->
 *     val glyphs = font.shape("Hello")
 * }
 * ```
 */
class HarfBuzzFont(
    fontData: ByteArray,
    tableOverrides: TableOverrides? = null,
) : Font {
    private val fontMemory: Memory
    private val blob: Pointer
    private val baseFace: Pointer
    private val face: Pointer
    private val font: Pointer
    
    // Must keep references to prevent GC during callback lifetime
    private val overrideBlobs = mutableMapOf<Int, Pointer>()
    private val overrideMemory = mutableMapOf<Int, Memory>()
    private var tableCallback: HbReferenceTableFunc? = null

    // Thread-safety: prevent use-after-free when close() is called during shaping
    private val closed = AtomicBoolean(false)
    private val shapingLock = ReentrantLock()

    override val upem: Int

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

        // Prepare override blobs
        ShaperHelpers.prepareOverrides(tableOverrides, overrideMemory, overrideBlobs)

        // Create face with optional table overrides
        face = if (overrideBlobs.isNotEmpty()) {
            createFaceWithOverrides()
        } else {
            baseFace
        }

        font = HarfBuzz.hb_font_create(face)
            ?: error("Failed to create HarfBuzz font")

        upem = HarfBuzz.hb_face_get_upem(face)
        HarfBuzz.hb_font_set_scale(font, upem, upem)
    }

    private fun createFaceWithOverrides(): Pointer {
        // Create callback that returns overrides or delegates to base face.
        // hb_face_create_for_tables expects the callback to return a hb_blob_t*
        // that the caller owns. We must increment the refcount before returning,
        // otherwise HarfBuzz can destroy our blob leading to a double-free.
        tableCallback = object : HbReferenceTableFunc {
            override fun invoke(face: Pointer?, tag: Int, userData: Pointer?): Pointer? {
                if (closed.get()) return HarfBuzz.hb_blob_get_empty()
                val blob = overrideBlobs[tag]
                return if (blob != null) {
                    HarfBuzz.hb_blob_reference(blob)
                } else {
                    HarfBuzz.hb_face_reference_table(baseFace, tag)
                }
            }
        }

        return HarfBuzz.hb_face_create_for_tables(tableCallback, null, null)
            ?: error("Failed to create HarfBuzz face with table overrides")
    }

    /**
     * Shape a text string into positioned glyphs.
     */
    override fun shape(
        text: String,
        direction: TextDirection?,
        script: String?,
        language: String?,
        features: List<Feature>?,
        buffer: Buffer?,
    ): List<ShapedGlyph> = doShape(text, direction, script, language, features, buffer)

    override fun shape(
        text: String,
        direction: TextDirection?,
        script: String?,
        language: String?,
        features: FeatureSet,
        buffer: Buffer?,
    ): List<ShapedGlyph> = doShape(text, direction, script, language, features, buffer)

    private fun doShape(
        text: String,
        direction: TextDirection?,
        script: String?,
        language: String?,
        features: Any?,
        buffer: Buffer?,
    ): List<ShapedGlyph> {
        if (text.isEmpty()) return emptyList()

        return shapingLock.withLock {
            if (closed.get()) {
                throw IllegalStateException("HarfBuzzFont has been closed")
            }

            val buf: Pointer
            val ownsBuffer: Boolean
            if (buffer != null) {
                buf = buffer.ptr
                HarfBuzz.hb_buffer_clear_contents(buf)
                ownsBuffer = false
            } else {
                buf = HarfBuzz.hb_buffer_create()
                    ?: error("Failed to create HarfBuzz buffer")
                ownsBuffer = true
            }

            try {
                // Add text as UTF-16
                val chars = text.toCharArray()
                HarfBuzz.hb_buffer_pre_allocate(buf, chars.size)
                HarfBuzz.hb_buffer_add_utf16(buf, chars, chars.size, 0, chars.size)

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
        shapingLock.withLock {
            if (closed.getAndSet(true)) {
                return // Already closed
            }

            HarfBuzz.hb_font_destroy(font)
            if (face != baseFace) {
                HarfBuzz.hb_face_destroy(face)
            }
            HarfBuzz.hb_face_destroy(baseFace)
            ShaperHelpers.destroyOverrideBlobs(overrideBlobs)
            HarfBuzz.hb_blob_destroy(blob)
            // Memory instances are freed when GC'd
        }
    }
}
