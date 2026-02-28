package com.metroid.editor.rom

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.io.File

/**
 * Tests for NES tile pattern decoding and palette handling.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class NesPatternDecoderTest {

    private var rom: NesRomParser? = null
    private var decoder: NesPatternDecoder? = null

    @BeforeAll
    fun setup() {
        val romFile = NesRomParserTest.findRomFile()
        if (romFile != null) {
            rom = NesRomParser(romFile.readBytes())
            decoder = NesPatternDecoder(rom!!)
        }
    }

    @Test
    fun `NES master palette has 64 entries`() {
        assertEquals(64, NesPatternDecoder.NES_PALETTE.size)
        // Color 0x0D is always black
        assertEquals(0xFF000000.toInt(), NesPatternDecoder.NES_PALETTE[0x0D])
    }

    @Test
    fun `decode known 2bpp tile pattern`() {
        // Create a simple test tile: all pixels in column 0 are color 3, rest are 0
        val tileData = ByteArray(16)
        for (row in 0..7) {
            tileData[row] = 0x80.toByte()      // Plane 0: bit 7 set
            tileData[row + 8] = 0x80.toByte()  // Plane 1: bit 7 set
        }

        val dec = decoder ?: NesPatternDecoder(NesRomParser(ByteArray(0x20010)))
        val pixels = dec.decodeTile(tileData)

        assertEquals(64, pixels.size)
        // First pixel of each row should be color 3 (both planes set)
        for (row in 0..7) {
            assertEquals(3, pixels[row * 8], "Row $row, col 0 should be color 3")
            assertEquals(0, pixels[row * 8 + 1], "Row $row, col 1 should be color 0")
        }
    }

    @Test
    fun `decode tile with plane 0 only gives color 1`() {
        val tileData = ByteArray(16)
        tileData[0] = 0xFF.toByte() // Plane 0 row 0: all bits set
        // Plane 1 row 0 stays 0

        val dec = decoder ?: NesPatternDecoder(NesRomParser(ByteArray(0x20010)))
        val pixels = dec.decodeTile(tileData)

        for (col in 0..7) {
            assertEquals(1, pixels[col], "Row 0, col $col should be color 1 (plane 0 only)")
        }
    }

    @Test
    fun `decode tile with plane 1 only gives color 2`() {
        val tileData = ByteArray(16)
        tileData[8] = 0xFF.toByte() // Plane 1 row 0: all bits set
        // Plane 0 row 0 stays 0

        val dec = decoder ?: NesPatternDecoder(NesRomParser(ByteArray(0x20010)))
        val pixels = dec.decodeTile(tileData)

        for (col in 0..7) {
            assertEquals(2, pixels[col], "Row 0, col $col should be color 2 (plane 1 only)")
        }
    }

    @Test
    fun `can decode tiles from Brinstar bank`() {
        val dec = decoder ?: return
        val r = rom ?: return

        // Decode first few tiles from Brinstar bank (bank 1)
        val bankOffset = r.bankAddressToRomOffset(1, 0x8000)
        for (i in 0..3) {
            val tileOffset = bankOffset + i * 16
            if (tileOffset + 16 <= r.romData.size) {
                val pixels = dec.decodeTileFromRom(tileOffset)
                assertEquals(64, pixels.size)
                assertTrue(pixels.all { it in 0..3 }, "All pixel values should be 0-3")
            }
        }
    }

    @Test
    fun `area palette contains valid colors`() {
        val dec = decoder ?: return

        val palette = dec.readAreaPalette(1, 0) // Brinstar palette 0
        assertEquals(32, palette.size, "Full palette should have 32 entries (4 bg + 4 sprite sub-palettes)")

        for (i in palette.indices) {
            val color = palette[i]
            val alpha = (color ushr 24) and 0xFF
            assertEquals(0xFF, alpha, "Color $i should be fully opaque, got alpha=$alpha")
        }
    }

    @Test
    fun `sub-palette extraction`() {
        val dec = decoder ?: return

        val fullPalette = dec.readAreaPalette(1, 0)
        val subPal0 = dec.getSubPalette(fullPalette, 0)
        val subPal1 = dec.getSubPalette(fullPalette, 1)

        assertEquals(4, subPal0.size)
        assertEquals(4, subPal1.size)

        // Color 0 of all sub-palettes should be the universal background color
        assertEquals(subPal0[0], subPal1[0], "All sub-palettes share color 0 (background)")
    }

    @Test
    fun `Brinstar palette 0 has correct NES color indices`() {
        val dec = decoder ?: return

        val palette = dec.readAreaPalette(1, 0)
        // Brinstar Palette00 BG sub-palette 0 should be: $0F(black), $22(blue), $12(dark blue), $1C(teal)
        assertEquals(NesPatternDecoder.NES_PALETTE[0x0F], palette[0], "BG color 0 should be NES $0F (black)")
        assertEquals(NesPatternDecoder.NES_PALETTE[0x22], palette[1], "BG color 1 should be NES $22 (blue)")
        assertEquals(NesPatternDecoder.NES_PALETTE[0x12], palette[2], "BG color 2 should be NES $12 (dark blue)")

        // Background color should be black (NES $0F)
        val subPal = dec.getSubPalette(palette, 0)
        assertEquals(NesPatternDecoder.NES_PALETTE[0x0F], subPal[0], "Universal BG color should be black")
        println("Brinstar BG sub-palette 0: ${subPal.map { "0x%08X".format(it) }}")
    }

    @Test
    fun `can decode tiles from graphics bank 6`() {
        val dec = decoder ?: return
        val r = rom ?: return

        // Bank 6 contains sprite tile patterns (Samus, items, etc.)
        val bankOffset = r.bankAddressToRomOffset(6, 0x8000)
        val pixels = dec.decodeTileFromRom(bankOffset)
        assertEquals(64, pixels.size)
        assertTrue(pixels.all { it in 0..3 })
    }

    @Test
    fun `render tile produces ARGB pixels`() {
        val dec = decoder ?: return

        val tileData = ByteArray(16)
        tileData[0] = 0xAA.toByte() // alternating pattern
        tileData[8] = 0x55.toByte()
        val indices = dec.decodeTile(tileData)

        val subPalette = intArrayOf(
            0xFF000000.toInt(), // color 0: black
            0xFF0000FF.toInt(), // color 1: blue
            0xFF00FF00.toInt(), // color 2: green
            0xFFFF0000.toInt()  // color 3: red
        )

        val rendered = dec.renderTile(indices, subPalette)
        assertEquals(64, rendered.size)
        // All pixels should be one of the palette colors
        assertTrue(rendered.all { it in subPalette })
    }
}
