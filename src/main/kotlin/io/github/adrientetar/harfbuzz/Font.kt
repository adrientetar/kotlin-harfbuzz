package io.github.adrientetar.harfbuzz

/**
 * Common interface for HarfBuzz font objects that can shape text.
 */
interface Font : AutoCloseable {
    companion object {
        /** Returns the version string of the linked HarfBuzz library. */
        fun version(): String = HarfBuzz.hb_version_string()
    }

    /** Units per em of the font. */
    val upem: Int

    /**
     * Shape a text string into positioned glyphs.
     *
     * @param text The text to shape
     * @param direction Optional text direction (null = auto-detect)
     * @param script Optional ISO 15924 script tag, e.g. "Latn", "Arab" (null = auto-detect)
     * @param language Optional BCP 47 language tag, e.g. "en", "ar" (null = auto-detect)
     * @param features Optional features — a [List]<[Feature]> or a pre-allocated [FeatureSet]
     * @param buffer Optional reusable [Buffer] for repeated shaping (null = allocate internally)
     */
    fun shape(
        text: String,
        direction: Direction? = null,
        script: String? = null,
        language: String? = null,
        features: List<Feature>? = null,
        buffer: Buffer? = null,
    ): List<GlyphInfo>

    /**
     * Shape a text string with a pre-allocated [FeatureSet] for zero-allocation feature passing.
     */
    fun shape(
        text: String,
        direction: Direction? = null,
        script: String? = null,
        language: String? = null,
        features: FeatureSet,
        buffer: Buffer? = null,
    ): List<GlyphInfo>
}
