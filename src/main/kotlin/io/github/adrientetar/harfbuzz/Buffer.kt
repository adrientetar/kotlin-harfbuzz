package io.github.adrientetar.harfbuzz

import com.sun.jna.Pointer

/**
 * Reusable HarfBuzz buffer for text shaping.
 *
 * Pre-allocates native memory for buffer operations, avoiding per-call
 * allocation overhead when shaping multiple texts.
 *
 * ```
 * Buffer.create().use { buffer ->
 *     font.shape("Hello", buffer = buffer)
 *     font.shape("World", buffer = buffer)  // reuses native buffer
 * }
 * ```
 *
 * A Buffer is not thread-safe. Do not share across threads without
 * external synchronization.
 */
class Buffer private constructor(
    internal val ptr: Pointer,
) : AutoCloseable {
    companion object {
        /** Create a new reusable buffer. */
        fun create(): Buffer {
            val ptr = HarfBuzz.hb_buffer_create()
                ?: throw HarfBuzzNativeException("Failed to create HarfBuzz buffer")
            return Buffer(ptr)
        }
    }

    override fun close() {
        HarfBuzz.hb_buffer_destroy(ptr)
    }
}
