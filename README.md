# kotlin-harfbuzz

Kotlin bindings for [HarfBuzz](https://harfbuzz.github.io/) text shaping library using JNA Direct Mapping.

## Overview

`kotlin-harfbuzz` provides Kotlin bindings to the HarfBuzz C library for OpenType text shaping. It uses JNA Direct Mapping for good performance.

Key features:
- Minimal API surface (only what's needed for text shaping)
- Support for `hb_face_create_for_tables` to inject fea-rs compiled GSUB/GPOS/GDEF tables
- Builds HarfBuzz from source via submodule (pinned to 12.3.0)
- High-level Kotlin wrapper for ergonomic usage

## Building

### Prerequisites

- JDK 21+
- [meson](https://mesonbuild.com/) and [ninja](https://ninja-build.org/) for building HarfBuzz

On macOS:
```bash
brew install meson ninja
```

On Linux:
```bash
sudo apt-get install meson ninja-build
```

### Clone and Build

```bash
git clone --recurse-submodules https://github.com/nicebyte/kotlin-harfbuzz.git
cd kotlin-harfbuzz
./gradlew build
```

The build will automatically compile HarfBuzz 12.3.0 from the included submodule.

## Usage

### Basic Text Shaping

```kotlin
val fontBytes = File("myfont.ttf").readBytes()
HarfBuzzShaper(fontBytes).use { shaper ->
    val glyphs = shaper.shape("Hello")
    glyphs.forEach { glyph ->
        println("Glyph ID: ${glyph.glyphId}, Advance: ${glyph.xAdvance}")
    }
}
```

### With Table Overrides (for live preview)

```kotlin
// Use fea-rs compiled tables for live preview
val overrides = TableOverrides(
    gsub = gsubBytes,
    gpos = gposBytes,
    gdef = gdefBytes
)

HarfBuzzShaper(fontBytes, overrides).use { shaper ->
    val glyphs = shaper.shape("Hello")
}
```

### Explicit Text Direction

```kotlin
HarfBuzzShaper(fontBytes).use { shaper ->
    val rtlGlyphs = shaper.shape("مرحبا", TextDirection.RTL)
}
```

## Building Native Libraries

The native library is built automatically during `./gradlew build` using the HarfBuzz submodule (pinned to version 12.3.0).

For CI/CD, GitHub Actions builds native libraries for all platforms:
- macOS ARM64 (Apple Silicon)
- macOS x64 (Intel)
- Linux x64
- Linux ARM64
- Windows x64

## API Reference

### HarfBuzzShaper

The main high-level class for text shaping.

- `HarfBuzzShaper(fontData: ByteArray, tableOverrides: TableOverrides? = null)` - Create a shaper from font bytes
- `shape(text: String, direction: TextDirection? = null): List<ShapedGlyph>` - Shape text into positioned glyphs
- `upem: Int` - Units per em of the font
- `close()` - Release native resources (implements `AutoCloseable`)

### ShapedGlyph

Result of shaping a single glyph:

- `glyphId: Int` - Glyph ID in the font
- `cluster: Int` - Original text position
- `xAdvance: Int` - Horizontal advance in font units
- `yAdvance: Int` - Vertical advance in font units
- `xOffset: Int` - Horizontal offset in font units
- `yOffset: Int` - Vertical offset in font units

### TableOverrides

Optional OpenType tables to override during shaping:

- `gsub: ByteArray?` - GSUB table data
- `gpos: ByteArray?` - GPOS table data
- `gdef: ByteArray?` - GDEF table data

### TextDirection

Text direction for shaping:

- `LTR` - Left to right
- `RTL` - Right to left

## License

MIT License
