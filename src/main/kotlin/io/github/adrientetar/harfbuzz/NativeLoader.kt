package io.github.adrientetar.harfbuzz

import com.sun.jna.NativeLibrary
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.security.MessageDigest

/**
 * Handles platform-specific loading of the HarfBuzz native library.
 * 
 * Extracts the appropriate binary from JAR resources to a temp directory
 * and loads it via JNA.
 */
internal object NativeLoader {
    private const val LIB_NAME = "harfbuzz"
    
    @Volatile
    private var loaded = false
    private lateinit var library: NativeLibrary

    fun loadLibrary(): NativeLibrary {
        if (!loaded) {
            synchronized(this) {
                if (!loaded) {
                    library = doLoad()
                    loaded = true
                }
            }
        }
        return library
    }

    private fun doLoad(): NativeLibrary {
        val platform = detectPlatform()
        val libFileName = libraryFileName()
        val resourcePath = "natives/$platform/$libFileName"
        
        // Try loading from resources (JAR)
        val cl = javaClass.classLoader ?: Thread.currentThread().contextClassLoader
        val resourceBytes = cl?.getResourceAsStream(resourcePath)?.use { it.readBytes() }
        if (resourceBytes != null) {
            // Use content-hash directory to reuse across restarts and avoid temp file accumulation
            val hash = MessageDigest.getInstance("SHA-256")
                .digest(resourceBytes)
                .take(8)
                .joinToString("") { "%02x".format(it) }
            val cacheDir = File(System.getProperty("java.io.tmpdir"), "kotlin-harfbuzz-$hash")
            val libFile = File(cacheDir, libFileName)

            if (!libFile.exists()) {
                try {
                    cacheDir.mkdirs()
                    // Write to a temp file first, then rename atomically to avoid partial files
                    val tmpFile = File.createTempFile("harfbuzz", null, cacheDir)
                    try {
                        tmpFile.writeBytes(resourceBytes)
                        if (!System.getProperty("os.name").lowercase().contains("windows")) {
                            tmpFile.setExecutable(true)
                        }
                        tmpFile.renameTo(libFile)
                    } finally {
                        tmpFile.delete() // clean up if rename succeeded or failed
                    }
                } catch (e: IOException) {
                    error("Failed to extract native library to $libFile: ${e.message}")
                }
            }
            
            return NativeLibrary.getInstance(libFile.absolutePath)
        }
        
        // Fallback: try system library path
        return NativeLibrary.getInstance(LIB_NAME)
    }

    private fun detectPlatform(): String {
        val os = System.getProperty("os.name").lowercase()
        val arch = System.getProperty("os.arch").lowercase()
        
        val osName = when {
            os.contains("mac") || os.contains("darwin") -> "macos"
            os.contains("linux") -> "linux"
            os.contains("windows") -> "windows"
            else -> error("Unsupported OS: $os")
        }
        
        val archName = when {
            arch == "aarch64" || arch == "arm64" -> "arm64"
            arch == "amd64" || arch == "x86_64" -> "x64"
            else -> error("Unsupported architecture: $arch")
        }
        
        return "$osName-$archName"
    }

    private fun libraryFileName(): String {
        val os = System.getProperty("os.name").lowercase()
        return when {
            os.contains("mac") || os.contains("darwin") -> "libharfbuzz.dylib"
            os.contains("linux") -> "libharfbuzz.so"
            os.contains("windows") -> "harfbuzz.dll"
            else -> error("Unsupported OS: $os")
        }
    }
}
