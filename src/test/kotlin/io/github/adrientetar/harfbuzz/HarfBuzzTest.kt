package io.github.adrientetar.harfbuzz

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class HarfBuzzTest {
    private fun getTestFontBytes(): ByteArray {
        val fontPath = System.getenv("TEST_FONT_PATH")
            ?: "harfbuzz/perf/fonts/Roboto-Regular.ttf"
        return File(fontPath).readBytes()
    }

    // ========== Font.version() ==========

    @Test
    fun `version returns non-empty string`() {
        val version = Font.version()
        assertTrue(version.isNotEmpty(), "Version string should not be empty")
        assertTrue(version.contains("."), "Version string should contain a dot separator")
    }

    // ========== HarfBuzzFont ==========

    @Test
    fun `shape simple text`() {
        val fontBytes = getTestFontBytes()

        HarfBuzzFont(fontBytes).use { font ->
            val result = font.shape("Hello")

            assertEquals(5, result.size, "Should have 5 glyphs for 'Hello'")

            // Verify clusters map back to text positions
            assertEquals(0, result[0].cluster)
            assertEquals(1, result[1].cluster)
            assertEquals(2, result[2].cluster)
            assertEquals(3, result[3].cluster)
            assertEquals(4, result[4].cluster)

            // All glyphs should have positive advance
            assertTrue(result.all { it.xAdvance > 0 })
        }
    }

    @Test
    fun `shape empty text returns empty list`() {
        HarfBuzzFont(getTestFontBytes()).use { font ->
            assertEquals(emptyList(), font.shape(""))
        }
    }

    @Test
    fun `shape with table overrides`() {
        val fontBytes = getTestFontBytes()
        val overrides = TableOverrides()

        assertDoesNotThrow {
            HarfBuzzFont(fontBytes, overrides).use { font ->
                val result = font.shape("Test")
                assertEquals(4, result.size)
            }
        }
    }

    @Test
    fun `shape RTL text`() {
        val fontBytes = getTestFontBytes()

        HarfBuzzFont(fontBytes).use { font ->
            val result = font.shape("abc", Direction.RTL)
            assertEquals(3, result.size)
        }
    }

    @Test
    fun `shape with script and language`() {
        val fontBytes = getTestFontBytes()

        HarfBuzzFont(fontBytes).use { font ->
            val result = font.shape("Hello", script = "Latn", language = "en")
            assertEquals(5, result.size)
        }
    }

    @Test
    fun `upem is read correctly`() {
        val fontBytes = getTestFontBytes()

        HarfBuzzFont(fontBytes).use { font ->
            assertTrue(font.upem > 0, "UPEM should be positive")
            assertTrue(font.upem in 16..65535, "UPEM should be in valid range")
        }
    }

    @Test
    fun `double close is safe`() {
        val font = HarfBuzzFont(getTestFontBytes())
        font.close()
        assertDoesNotThrow { font.close() }
    }

    @Test
    fun `shape after close throws`() {
        val font = HarfBuzzFont(getTestFontBytes())
        font.close()
        assertThrows<HarfBuzzClosedException> { font.shape("Hello") }
    }

    @Test
    fun `shape produces consistent results`() {
        HarfBuzzFont(getTestFontBytes()).use { font ->
            val r1 = font.shape("Hello")
            val r2 = font.shape("Hello")
            assertEquals(r1, r2, "Same input should produce identical output")
        }
    }

    // ========== Feature ==========

    @Test
    fun `feature data class basics`() {
        val f = Feature("kern", 1)
        assertEquals("kern", f.tag)
        assertEquals(1, f.value)
        assertEquals(0, f.start)
        assertEquals(-1, f.end)
    }

    @Test
    fun `feature boolean constructor`() {
        val enabled = Feature("kern", true)
        assertEquals(1, enabled.value)

        val disabled = Feature("liga", false)
        assertEquals(0, disabled.value)
    }

    @Test
    fun `feature tag must be 4 chars`() {
        assertThrows<IllegalArgumentException> { Feature("ke", 1) }
        assertThrows<IllegalArgumentException> { Feature("kerns", 1) }
    }

    @Test
    fun `feature fromMap`() {
        val features = Feature.fromMap("kern" to true, "liga" to false)
        assertEquals(2, features.size)
        assertEquals("kern", features[0].tag)
        assertEquals(1, features[0].value)
        assertEquals("liga", features[1].tag)
        assertEquals(0, features[1].value)
    }

    @Test
    fun `feature fromString parses simple tag`() {
        val f = Feature.fromString("kern")
        assertEquals("kern", f.tag)
        assertEquals(1, f.value)
    }

    @Test
    fun `feature fromString parses disable syntax`() {
        val f = Feature.fromString("-liga")
        assertEquals("liga", f.tag)
        assertEquals(0, f.value)
    }

    @Test
    fun `feature fromString parses enable syntax`() {
        val f = Feature.fromString("+kern")
        assertEquals("kern", f.tag)
        assertEquals(1, f.value)
    }

    @Test
    fun `feature fromString rejects invalid`() {
        assertThrows<IllegalArgumentException> { Feature.fromString("") }
    }

    // ========== FeatureSet ==========

    @Test
    fun `featureSet creation and usage`() {
        val fs = FeatureSet("kern" to true, "liga" to false)
        assertEquals(2, fs.features.size)

        HarfBuzzFont(getTestFontBytes()).use { font ->
            val result = font.shape("Hello", features = fs)
            assertEquals(5, result.size)
        }
    }

    @Test
    fun `featureSet reuse across calls`() {
        val fs = FeatureSet("kern" to true)

        HarfBuzzFont(getTestFontBytes()).use { font ->
            val r1 = font.shape("Hello", features = fs)
            val r2 = font.shape("World", features = fs)
            assertEquals(5, r1.size)
            assertEquals(5, r2.size)
        }
    }

    @Test
    fun `featureSet requires non-empty`() {
        assertThrows<IllegalArgumentException> { FeatureSet.from(emptyList()) }
    }

    // ========== Feature list shaping ==========

    @Test
    fun `shape with feature list`() {
        HarfBuzzFont(getTestFontBytes()).use { font ->
            val features = listOf(Feature("kern", true))
            val result = font.shape("Hello", features = features)
            assertEquals(5, result.size)
        }
    }

    // ========== Buffer ==========

    @Test
    fun `buffer reuse with HarfBuzzFont`() {
        HarfBuzzFont(getTestFontBytes()).use { font ->
            Buffer.create().use { buffer ->
                val r1 = font.shape("Hello", buffer = buffer)
                val r2 = font.shape("World", buffer = buffer)
                assertEquals(5, r1.size)
                assertEquals(5, r2.size)
                // Results should match non-buffer shaping
                val r3 = font.shape("Hello")
                assertEquals(r1, r3)
            }
        }
    }

    @Test
    fun `buffer reuse with VirtualHarfBuzzFont`() {
        val font = makeVirtualFont()
        font.use {
            Buffer.create().use { buffer ->
                val r1 = it.shape("ABC", buffer = buffer)
                val r2 = it.shape("AB", buffer = buffer)
                assertEquals(3, r1.size)
                assertEquals(2, r2.size)
            }
        }
    }

    @Test
    fun `buffer reuse with shapeCodepoints`() {
        val font = makeVirtualFont()
        font.use {
            Buffer.create().use { buffer ->
                val cp = intArrayOf('A'.code, 'B'.code)
                val cl = intArrayOf(0, 1)
                val r1 = it.shapeCodepoints(cp, cl, buffer = buffer)
                val r2 = it.shapeCodepoints(cp, cl, buffer = buffer)
                assertEquals(r1, r2)
            }
        }
    }

    // ========== VirtualHarfBuzzFont ==========

    @Test
    fun `virtual font shape string`() {
        val font = makeVirtualFont()
        font.use {
            val result = it.shape("ABC")
            assertEquals(3, result.size)
            assertEquals(1, result[0].codepoint)
            assertEquals(2, result[1].codepoint)
            assertEquals(3, result[2].codepoint)
        }
    }

    @Test
    fun `virtual font with codepoints`() {
        val font = makeVirtualFont()
        font.use {
            val codepoints = intArrayOf('A'.code, 'B'.code, 'C'.code)
            val clusters = intArrayOf(0, 1, 2)
            val result = it.shapeCodepoints(codepoints, clusters)

            assertEquals(3, result.size)
            assertEquals(1, result[0].codepoint)
            assertEquals(2, result[1].codepoint)
            assertEquals(3, result[2].codepoint)

            assertEquals(0, result[0].cluster)
            assertEquals(1, result[1].cluster)
            assertEquals(2, result[2].cluster)

            assertEquals(500, result[0].xAdvance)
            assertEquals(600, result[1].xAdvance)
            assertEquals(550, result[2].xAdvance)
        }
    }

    @Test
    fun `virtual font unencoded glyphs`() {
        val font = VirtualHarfBuzzFont(
            upem = 1000,
            glyphOrder = listOf(".notdef", "A", "B", "ligature_AB"),
            unicodeToGid = mapOf('A'.code to 1, 'B'.code to 2),
            hAdvances = intArrayOf(0, 500, 600, 900),
        )
        font.use {
            val gid3Codepoint = (VirtualHarfBuzzFont.CH_GID_PREFIX + 3u).toInt()
            val codepoints = intArrayOf(gid3Codepoint)
            val clusters = intArrayOf(0)

            val result = it.shapeCodepoints(codepoints, clusters)

            assertEquals(1, result.size)
            assertEquals(3, result[0].codepoint)
            assertEquals(900, result[0].xAdvance)
        }
    }

    @Test
    fun `virtual font upem`() {
        val font = makeVirtualFont()
        assertEquals(1000, font.upem)
        font.close()
    }

    @Test
    fun `virtual font empty text`() {
        val font = makeVirtualFont()
        font.use {
            assertEquals(emptyList(), it.shape(""))
        }
    }

    @Test
    fun `virtual font empty codepoints`() {
        val font = makeVirtualFont()
        font.use {
            assertEquals(emptyList(), it.shapeCodepoints(intArrayOf(), intArrayOf()))
        }
    }

    @Test
    fun `virtual font mismatched arrays throws`() {
        val font = makeVirtualFont()
        font.use {
            assertThrows<IllegalArgumentException> {
                it.shapeCodepoints(intArrayOf(65), intArrayOf(0, 1))
            }
        }
    }

    @Test
    fun `virtual font double close is safe`() {
        val font = makeVirtualFont()
        font.close()
        assertDoesNotThrow { font.close() }
    }

    @Test
    fun `virtual font shape after close throws`() {
        val font = makeVirtualFont()
        font.close()
        assertThrows<HarfBuzzClosedException> { font.shape("A") }
    }

    @Test
    fun `virtual font with script and language`() {
        val font = makeVirtualFont()
        font.use {
            val result = it.shape("ABC", script = "Latn", language = "en")
            assertEquals(3, result.size)
        }
    }

    @Test
    fun `virtual font with features`() {
        val font = makeVirtualFont()
        font.use {
            val features = listOf(Feature("kern", true))
            val result = it.shape("ABC", features = features)
            assertEquals(3, result.size)
        }
    }

    @Test
    fun `virtual font with feature set`() {
        val font = makeVirtualFont()
        val fs = FeatureSet("kern" to true)
        font.use {
            val result = it.shape("ABC", features = fs)
            assertEquals(3, result.size)
        }
    }

    // ========== TableOverrides ==========

    @Test
    fun `tableOverrides equals and hashCode`() {
        val a = TableOverrides(gsub = byteArrayOf(1, 2, 3))
        val b = TableOverrides(gsub = byteArrayOf(1, 2, 3))
        val c = TableOverrides(gsub = byteArrayOf(4, 5, 6))

        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertTrue(a != c)
    }

    @Test
    fun `tableOverrides null fields equal`() {
        val a = TableOverrides()
        val b = TableOverrides()
        assertEquals(a, b)
    }

    // ========== Font interface polymorphism ==========

    @Test
    fun `font interface works with both implementations`() {
        val fonts = listOf<Font>(
            HarfBuzzFont(getTestFontBytes()),
            makeVirtualFont(),
        )

        for (font in fonts) {
            font.use {
                assertTrue(it.upem > 0)
                val result = it.shape("AB")
                assertEquals(2, result.size)
            }
        }
    }

    // ========== Helpers ==========

    private fun makeVirtualFont(): VirtualHarfBuzzFont {
        return VirtualHarfBuzzFont(
            upem = 1000,
            glyphOrder = listOf(".notdef", "A", "B", "C", "ligature_AB"),
            unicodeToGid = mapOf('A'.code to 1, 'B'.code to 2, 'C'.code to 3),
            hAdvances = intArrayOf(0, 500, 600, 550, 900),
        )
    }
}
