package io.github.adrientetar.harfbuzz

/**
 * Base exception for all HarfBuzz library errors.
 *
 * Catch this type to handle any error originating from the kotlin-harfbuzz library.
 */
open class HarfBuzzException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/**
 * Thrown when a native HarfBuzz call fails (e.g., resource allocation returns null).
 */
class HarfBuzzNativeException(message: String, cause: Throwable? = null) : HarfBuzzException(message, cause)

/**
 * Thrown when an operation is attempted on a [Font] that has already been [closed][AutoCloseable.close].
 */
class HarfBuzzClosedException(message: String) : HarfBuzzException(message)

/**
 * Thrown when the native HarfBuzz library cannot be loaded
 * (e.g., unsupported OS/architecture, extraction failure).
 */
class HarfBuzzLoadException(message: String, cause: Throwable? = null) : HarfBuzzException(message, cause)
