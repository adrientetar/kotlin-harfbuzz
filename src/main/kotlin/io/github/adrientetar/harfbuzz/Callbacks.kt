package io.github.adrientetar.harfbuzz

import com.sun.jna.Callback
import com.sun.jna.Pointer

/**
 * Callback for hb_face_create_for_tables.
 * 
 * HarfBuzz calls this to retrieve table data by tag.
 * Return an hb_blob_t* for the requested table, or null if not available.
 */
internal interface HbReferenceTableFunc : Callback {
    /**
     * @param face The face requesting the table (can be used for delegation)
     * @param tag OpenType table tag (e.g., HB_TAG_GSUB)
     * @param userData User data passed to hb_face_create_for_tables
     * @return Pointer to hb_blob_t containing table data, or null
     */
    fun invoke(face: Pointer?, tag: Int, userData: Pointer?): Pointer?
}

/**
 * Destroy callback (typically null for our use case since we manage memory in Kotlin).
 */
internal interface HbDestroyFunc : Callback {
    fun invoke(userData: Pointer?)
}

/**
 * Callback for hb_font_funcs_set_nominal_glyph_func.
 *
 * Return non-zero and set `glyph` when a mapping exists.
 */
internal interface HbNominalGlyphFunc : Callback {
    fun invoke(
        font: Pointer?,
        fontData: Pointer?,
        unicode: Int,
        glyph: Pointer?,
        userData: Pointer?,
    ): Int
}

/**
 * Callback for hb_font_funcs_set_glyph_h_advance_func.
 */
internal interface HbGlyphHAdvanceFunc : Callback {
    fun invoke(
        font: Pointer?,
        fontData: Pointer?,
        glyph: Int,
        userData: Pointer?,
    ): Int
}

/**
 * Callback for hb_font_funcs_set_glyph_name_func.
 *
 * Should write a NUL-terminated UTF-8 string into `name` up to `size` bytes.
 * Return non-zero on success.
 */
internal interface HbGlyphNameFunc : Callback {
    fun invoke(
        font: Pointer?,
        fontData: Pointer?,
        glyph: Int,
        name: Pointer?,
        size: Int,
        userData: Pointer?,
    ): Int
}
