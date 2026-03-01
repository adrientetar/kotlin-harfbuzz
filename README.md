<div align="center">

kotlin-harfbuzz
===============

**Kotlin bindings for [HarfBuzz] text shaping using JNA Direct Mapping**

[![Kotlin](https://img.shields.io/badge/Language-Kotlin-7f52ff.svg)](https://kotlinlang.org/)
[![Apache 2.0](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](LICENSE.txt)
[![Maven central](https://img.shields.io/maven-central/v/io.github.adrientetar/kotlin-harfbuzz?color=brightgreen)](https://central.sonatype.com/artifact/io.github.adrientetar/kotlin-harfbuzz)

</div>

This library provides Kotlin bindings to the [HarfBuzz] C library for OpenType text shaping. It uses JNA Direct Mapping for good performance, with a minimal API surface focused on text shaping and support for injecting pre-compiled GSUB/GPOS/GDEF tables.

HarfBuzz is built from source via submodule (pinned to 12.3.2).

Maven library
-------------

```kotlin
repositories {
    mavenCentral()
}

dependencies {
    implementation("io.github.adrientetar:kotlin-harfbuzz:1.0.0")
}
```

Usage
-----

### Basic Text Shaping

```kotlin
val fontBytes = File("myfont.ttf").readBytes()
HarfBuzzFont(fontBytes).use { font ->
    val glyphs = font.shape("Hello")
    glyphs.forEach { glyph ->
        println("Glyph ID: ${glyph.glyphId}, Advance: ${glyph.xAdvance}")
    }
}
```

### With Table Overrides (for live preview)

```kotlin
val overrides = TableOverrides(
    gsub = gsubBytes,
    gpos = gposBytes,
    gdef = gdefBytes
)

HarfBuzzFont(fontBytes, overrides).use { font ->
    val glyphs = font.shape("Hello")
}
```

### Explicit Text Direction

```kotlin
HarfBuzzFont(fontBytes).use { font ->
    val rtlGlyphs = font.shape("مرحبا", TextDirection.RTL)
}
```

See [the tests](/src/test/kotlin/io/github/adrientetar/harfbuzz) for more sample code.

API reference
-------------

| Class | Description |
|-------|-------------|
| `Font` | Common interface for HarfBuzz fonts. Defines `upem`, `shape()`, `close()`, and `version()`. |
| `HarfBuzzFont` | Font backed by a real font file (binary). Implements `Font`. |
| `VirtualHarfBuzzFont` | Font backed by in-memory tables and custom font callbacks, for editor use without a full binary font. Implements `Font`. Also offers `shapeCodepoints()` for explicit codepoint+cluster input. |
| `Buffer` | Reusable native buffer for repeated shaping. Pass to `shape()` to avoid per-call allocation. |
| `Feature` | OpenType feature to enable/disable (e.g., `"kern"`, `"liga"`). Supports `fromString()` for HarfBuzz feature syntax. |
| `FeatureSet` | Pre-allocated set of features for zero-allocation shaping. |
| `ShapedGlyph` | Result of shaping: `glyphId`, `cluster`, `xAdvance`, `yAdvance`, `xOffset`, `yOffset` (in font units). |
| `TableOverrides` | Optional GSUB/GPOS/GDEF table data to override during shaping. |
| `TextDirection` | `LTR` or `RTL` text direction for shaping. |

Build
-----

You need the following installed:

- JDK 11 or later
- [meson] and [ninja] (for building HarfBuzz from source)

```bash
git clone --recurse-submodules https://github.com/nicebyte/kotlin-harfbuzz.git
cd kotlin-harfbuzz
./gradlew build
```

For CI/CD, GitHub Actions builds native libraries for macOS (ARM64, x64), Linux (x64, ARM64), and Windows (x64, ARM64).

[HarfBuzz]: https://harfbuzz.github.io/
[meson]: https://mesonbuild.com/
[ninja]: https://ninja-build.org/
