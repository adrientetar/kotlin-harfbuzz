package io.github.adrientetar.harfbuzz

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HarfBuzzTest {
    @Test
    fun `shape simple text`() {
        val fontPath = System.getenv("TEST_FONT_PATH") 
            ?: "/System/Library/Fonts/Helvetica.ttc"
        val fontBytes = File(fontPath).readBytes()

        HarfBuzzShaper(fontBytes).use { shaper ->
            val result = shaper.shape("Hello")

            assertEquals(5, result.size, "Should have 5 glyphs for 'Hello'")
            
            // Verify clusters map back to text positions
            assertEquals(0, result[0].cluster) // H
            assertEquals(1, result[1].cluster) // e
            assertEquals(2, result[2].cluster) // l
            assertEquals(3, result[3].cluster) // l
            assertEquals(4, result[4].cluster) // o

            // All glyphs should have positive advance
            assertTrue(result.all { it.xAdvance > 0 })
        }
    }

    @Test
    fun `shape with table overrides`() {
        val fontPath = System.getenv("TEST_FONT_PATH")
            ?: "/System/Library/Fonts/Helvetica.ttc"
        val fontBytes = File(fontPath).readBytes()

        // Empty overrides (no GSUB/GPOS/GDEF) should still work
        val overrides = TableOverrides()

        assertDoesNotThrow {
            HarfBuzzShaper(fontBytes, overrides).use { shaper ->
                val result = shaper.shape("Test")
                assertEquals(4, result.size)
            }
        }
    }

    @Test
    fun `shape RTL text`() {
        val fontPath = System.getenv("TEST_FONT_PATH")
            ?: "/System/Library/Fonts/Helvetica.ttc"
        val fontBytes = File(fontPath).readBytes()

        HarfBuzzShaper(fontBytes).use { shaper ->
            // Even without Arabic glyphs, direction should work
            val result = shaper.shape("abc", TextDirection.RTL)
            assertEquals(3, result.size)
            
            // RTL text should have reversed cluster order
            // (actual behavior depends on font and text)
        }
    }

    @Test
    fun `upem is read correctly`() {
        val fontPath = System.getenv("TEST_FONT_PATH")
            ?: "/System/Library/Fonts/Helvetica.ttc"
        val fontBytes = File(fontPath).readBytes()

        HarfBuzzShaper(fontBytes).use { shaper ->
            assertTrue(shaper.upem > 0, "UPEM should be positive")
            assertTrue(shaper.upem in 16..65535, "UPEM should be in valid range")
        }
    }
}
