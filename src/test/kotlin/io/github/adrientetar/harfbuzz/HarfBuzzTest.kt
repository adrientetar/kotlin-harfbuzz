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

    @Test
    fun `virtual shaper with codepoints`() {
        // Test VirtualHarfBuzzShaper with hb_buffer_add (which requires content type fix)
        val upem = 1000
        val glyphOrder = listOf(".notdef", "A", "B", "C")
        val unicodeToGid = mapOf(
            'A'.code to 1,
            'B'.code to 2,
            'C'.code to 3,
        )
        val hAdvances = intArrayOf(0, 500, 600, 550) // widths for .notdef, A, B, C

        val shaper = VirtualHarfBuzzShaper(
            upem = upem,
            glyphOrder = glyphOrder,
            unicodeToGid = unicodeToGid,
            hAdvances = hAdvances,
            tableOverrides = null,
        )

        shaper.use {
            // Shape "ABC" using codepoints
            val codepoints = intArrayOf('A'.code, 'B'.code, 'C'.code)
            val clusters = intArrayOf(0, 1, 2)

            val result = it.shapeCodepoints(codepoints, clusters)

            assertEquals(3, result.size, "Should have 3 shaped glyphs")

            // Check glyph IDs match the unicodeToGid mapping
            assertEquals(1, result[0].codepoint, "First glyph should be GID 1 (A)")
            assertEquals(2, result[1].codepoint, "Second glyph should be GID 2 (B)")
            assertEquals(3, result[2].codepoint, "Third glyph should be GID 3 (C)")

            // Check clusters are preserved
            assertEquals(0, result[0].cluster)
            assertEquals(1, result[1].cluster)
            assertEquals(2, result[2].cluster)

            // Check advances match hAdvances
            assertEquals(500, result[0].xAdvance, "A should have advance 500")
            assertEquals(600, result[1].xAdvance, "B should have advance 600")
            assertEquals(550, result[2].xAdvance, "C should have advance 550")
        }
    }

    @Test
    fun `virtual shaper with unencoded glyphs`() {
        // Test that unencoded glyphs (via CH_GID_PREFIX) work correctly
        val upem = 1000
        val glyphOrder = listOf(".notdef", "A", "B", "ligature_AB")
        val unicodeToGid = mapOf('A'.code to 1, 'B'.code to 2)
        val hAdvances = intArrayOf(0, 500, 600, 900)

        val shaper = VirtualHarfBuzzShaper(
            upem = upem,
            glyphOrder = glyphOrder,
            unicodeToGid = unicodeToGid,
            hAdvances = hAdvances,
            tableOverrides = null,
        )

        shaper.use {
            // Address the unencoded "ligature_AB" glyph (GID 3) via prefix
            val gid3Codepoint = (VirtualHarfBuzzShaper.CH_GID_PREFIX + 3u).toInt()
            val codepoints = intArrayOf(gid3Codepoint)
            val clusters = intArrayOf(0)

            val result = it.shapeCodepoints(codepoints, clusters)

            assertEquals(1, result.size)
            assertEquals(3, result[0].codepoint, "Should resolve to GID 3")
            assertEquals(900, result[0].xAdvance, "ligature_AB should have advance 900")
        }
    }
}
