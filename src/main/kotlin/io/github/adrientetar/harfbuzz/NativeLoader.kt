package io.github.adrientetar.harfbuzz

import com.sun.jna.NativeLibrary
import java.io.File
import java.nio.file.Files

/**
 * Handles platform-specific loading of the HarfBuzz native library.
 * 
 * Extracts the appropriate binary from JAR resources to a temp directory
 * and loads it via JNA.
 */
object NativeLoader {
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
        val resourcePath = "natives/$platform/${libraryFileName()}"
        
        // Try loading from resources (JAR)
        val resourceStream = javaClass.classLoader.getResourceAsStream(resourcePath)
        if (resourceStream != null) {
            val tempDir = Files.createTempDirectory("kotlin-harfbuzz").toFile()
            tempDir.deleteOnExit()
            
            val tempFile = File(tempDir, libraryFileName())
            tempFile.deleteOnExit()
            
            resourceStream.use { input ->
                tempFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            
            // Make executable on Unix
            if (!System.getProperty("os.name").lowercase().contains("windows")) {
                tempFile.setExecutable(true)
            }
            
            return NativeLibrary.getInstance(tempFile.absolutePath)
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
