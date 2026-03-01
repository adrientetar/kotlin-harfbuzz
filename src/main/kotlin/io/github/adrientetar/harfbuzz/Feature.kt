package io.github.adrientetar.harfbuzz

import com.sun.jna.Memory
import com.sun.jna.Pointer

/**
 * An OpenType feature to enable or disable during shaping.
 *
 * @param tag 4-character OpenType feature tag (e.g., "kern", "liga")
 * @param value feature value: `true` to enable, `false` to disable, or an integer for multi-valued features
 * @param start start index in the buffer (0 = from beginning)
 * @param end end index in the buffer (-1 = to end)
 */
data class Feature(
    val tag: String,
    val value: Int = 1,
    val start: Int = 0,
    val end: Int = -1,
) {
    init {
        require(tag.length == 4) { "Feature tag must be exactly 4 characters, got: \"$tag\"" }
    }

    constructor(tag: String, enabled: Boolean, start: Int = 0, end: Int = -1)
        : this(tag, if (enabled) 1 else 0, start, end)

    companion object {
        /**
         * Create features from a map of tag to enabled/disabled.
         *
         * ```
         * Feature.fromMap("kern" to true, "liga" to false)
         * ```
         */
        fun fromMap(vararg features: Pair<String, Boolean>): List<Feature> =
            features.map { (tag, enabled) -> Feature(tag, enabled) }

        /**
         * Parse a feature from HarfBuzz's canonical string syntax.
         *
         * Supports formats like `"kern"`, `"-liga"`, `"+kern"`, `"kern[3:5]=0"`.
         * Uses HarfBuzz's own `hb_feature_from_string` parser for correctness.
         *
         * @throws IllegalArgumentException if the string is not a valid feature
         */
        fun fromString(str: String): Feature {
            val bytes = str.toByteArray(Charsets.US_ASCII)
            val mem = Memory(16)
            val result = HarfBuzz.hb_feature_from_string(bytes, bytes.size, mem)
            require(result != 0) { "Invalid feature string: \"$str\"" }
            return Feature(
                tag = tagToString(mem.getInt(0)),
                value = mem.getInt(4),
                start = mem.getInt(8),
                end = mem.getInt(12),
            )
        }
    }
}

/**
 * Pre-allocated set of OpenType features for repeated use in shaping.
 *
 * Native memory is allocated once at construction and reused across
 * all `shape()` calls, avoiding per-call allocation overhead.
 *
 * ```
 * val features = FeatureSet("kern" to true, "liga" to true)
 * shaper.shape("Hello", features = features)
 * shaper.shape("World", features = features)  // no reallocation
 * ```
 */
class FeatureSet private constructor(
    val features: List<Feature>,
    internal val nativePtr: Pointer,
    internal val count: Int,
) {
    companion object {
        /**
         * Create a feature set from feature pairs.
         */
        operator fun invoke(vararg features: Pair<String, Boolean>): FeatureSet =
            from(Feature.fromMap(*features))

        /**
         * Create a feature set from a list of features.
         */
        fun from(features: List<Feature>): FeatureSet {
            require(features.isNotEmpty()) { "FeatureSet must contain at least one feature" }
            val (ptr, _) = ShaperHelpers.allocateFeatureList(features)
            return FeatureSet(features, ptr, features.size)
        }
    }
}
