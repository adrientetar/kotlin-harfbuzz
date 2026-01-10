package io.github.adrientetar.harfbuzz

import com.sun.jna.Native
import com.sun.jna.Pointer

/**
 * JNA Direct Mapping bindings to HarfBuzz C library.
 * 
 * Uses Direct Mapping for better performance than interface mapping.
 * Only exposes the minimal API surface needed for text shaping with
 * optional GSUB/GPOS/GDEF table injection.
 */
object HarfBuzz {
    init {
        Native.register(NativeLoader.loadLibrary())
    }

    // ========== Blob ==========
    
    @JvmStatic external fun hb_blob_create(
        data: Pointer?,
        length: Int,
        mode: Int,
        user_data: Pointer?,
        destroy: Pointer?,
    ): Pointer?

    @JvmStatic external fun hb_blob_destroy(blob: Pointer?)
    
    @JvmStatic external fun hb_blob_get_length(blob: Pointer?): Int

    // ========== Face ==========
    
    @JvmStatic external fun hb_face_create(blob: Pointer?, index: Int): Pointer?
    
    @JvmStatic external fun hb_face_create_for_tables(
        reference_table_func: HbReferenceTableFunc?,
        user_data: Pointer?,
        destroy: Pointer?,
    ): Pointer?

    @JvmStatic external fun hb_face_destroy(face: Pointer?)
    
    @JvmStatic external fun hb_face_reference_table(face: Pointer?, tag: Int): Pointer?
    
    @JvmStatic external fun hb_face_get_upem(face: Pointer?): Int

    @JvmStatic external fun hb_face_set_upem(face: Pointer?, upem: Int)

    @JvmStatic external fun hb_face_set_glyph_count(face: Pointer?, glyph_count: Int)

    // ========== Font ==========
    
    @JvmStatic external fun hb_font_create(face: Pointer?): Pointer?
    
    @JvmStatic external fun hb_font_destroy(font: Pointer?)
    
    @JvmStatic external fun hb_font_set_scale(font: Pointer?, x_scale: Int, y_scale: Int)

    @JvmStatic external fun hb_font_set_funcs(
        font: Pointer?,
        klass: Pointer?, // hb_font_funcs_t*
        font_data: Pointer?,
        destroy: HbDestroyFunc?,
    )

    // ========== Font Funcs ==========

    @JvmStatic external fun hb_font_funcs_create(): Pointer?

    @JvmStatic external fun hb_font_funcs_destroy(ffuncs: Pointer?)

    @JvmStatic external fun hb_font_funcs_make_immutable(ffuncs: Pointer?)

    @JvmStatic external fun hb_font_funcs_set_nominal_glyph_func(
        ffuncs: Pointer?,
        func: HbNominalGlyphFunc?,
        user_data: Pointer?,
        destroy: HbDestroyFunc?,
    )

    @JvmStatic external fun hb_font_funcs_set_glyph_h_advance_func(
        ffuncs: Pointer?,
        func: HbGlyphHAdvanceFunc?,
        user_data: Pointer?,
        destroy: HbDestroyFunc?,
    )

    @JvmStatic external fun hb_font_funcs_set_glyph_name_func(
        ffuncs: Pointer?,
        func: HbGlyphNameFunc?,
        user_data: Pointer?,
        destroy: HbDestroyFunc?,
    )

    // ========== Buffer ==========
    
    @JvmStatic external fun hb_buffer_create(): Pointer?
    
    @JvmStatic external fun hb_buffer_destroy(buffer: Pointer?)
    
    @JvmStatic external fun hb_buffer_add_utf16(
        buffer: Pointer?,
        text: CharArray?,
        text_length: Int,
        item_offset: Int,
        item_length: Int,
    )

    @JvmStatic external fun hb_buffer_add(
        buffer: Pointer?,
        codepoint: Int,
        cluster: Int,
    )

    @JvmStatic external fun hb_buffer_set_direction(buffer: Pointer?, direction: Int)
    
    @JvmStatic external fun hb_buffer_guess_segment_properties(buffer: Pointer?)
    
    @JvmStatic external fun hb_buffer_get_length(buffer: Pointer?): Int
    
    @JvmStatic external fun hb_buffer_get_glyph_infos(
        buffer: Pointer?,
        length: Pointer?, // uint* out param, can be null
    ): Pointer? // hb_glyph_info_t*

    @JvmStatic external fun hb_buffer_get_glyph_positions(
        buffer: Pointer?,
        length: Pointer?, // uint* out param, can be null
    ): Pointer? // hb_glyph_position_t*

    // ========== Shaping ==========
    
    @JvmStatic external fun hb_shape(
        font: Pointer?,
        buffer: Pointer?,
        features: Pointer?, // hb_feature_t*, can be null
        num_features: Int,
    )
}
