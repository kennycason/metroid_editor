package com.metroid.editor.rom

/**
 * Decodes NES 2bpp planar tile patterns into pixel data and manages CHR RAM assembly.
 *
 * NES CHR format (2 bits per pixel, 16 bytes per 8x8 tile):
 *   Bytes 0-7:  Plane 0 (bit 0 of each pixel), one byte per row
 *   Bytes 8-15: Plane 1 (bit 1 of each pixel), one byte per row
 *
 * Metroid uses CHR RAM (no CHR ROM). Tile patterns are stored in PRG ROM across
 * multiple banks and copied to PPU pattern tables during area init. The GFXInfo
 * table at 0xC6E0 (fixed bank 7) defines the source/destination for each block.
 * Background tiles go to PPU 0x1000-0x1FFF (pattern table 1).
 */
class NesPatternDecoder(private val rom: NesRomParser) {

    companion object {
        const val TILE_SIZE = 8
        const val BYTES_PER_TILE = 16
        const val PIXELS_PER_TILE = TILE_SIZE * TILE_SIZE // 64
        const val PAL_PTR_TABLE_ADDR = 0x9560
        const val GFX_INFO_ADDR = 0xC6E0
        const val GFX_INFO_ENTRY_SIZE = 7
        const val PATTERN_TABLE_SIZE = 0x1000 // 4KB per pattern table (256 tiles)
        const val BG_PATTERN_TABLE_BASE = 0x1000 // PPU address of background pattern table

        /**
         * NES master palette — 64 colors in ARGB format.
         * Standard NTSC NES palette (Nestopia's default).
         */
        val NES_PALETTE = intArrayOf(
            0xFF666666.toInt(), 0xFF002A88.toInt(), 0xFF1412A7.toInt(), 0xFF3B00A4.toInt(),
            0xFF5C007E.toInt(), 0xFF6E0040.toInt(), 0xFF6C0600.toInt(), 0xFF561D00.toInt(),
            0xFF333500.toInt(), 0xFF0B4800.toInt(), 0xFF005200.toInt(), 0xFF004F08.toInt(),
            0xFF00404D.toInt(), 0xFF000000.toInt(), 0xFF000000.toInt(), 0xFF000000.toInt(),
            0xFFADADAD.toInt(), 0xFF155FD9.toInt(), 0xFF4240FF.toInt(), 0xFF7527FE.toInt(),
            0xFFA01ACC.toInt(), 0xFFB71E7B.toInt(), 0xFFB53120.toInt(), 0xFF994E00.toInt(),
            0xFF6B6D00.toInt(), 0xFF388700.toInt(), 0xFF0C9300.toInt(), 0xFF008F32.toInt(),
            0xFF007C8D.toInt(), 0xFF000000.toInt(), 0xFF000000.toInt(), 0xFF000000.toInt(),
            0xFFFFFEFF.toInt(), 0xFF64B0FF.toInt(), 0xFF9290FF.toInt(), 0xFFC676FF.toInt(),
            0xFFF36AFF.toInt(), 0xFFFE6ECC.toInt(), 0xFFFE8170.toInt(), 0xFFEA9E22.toInt(),
            0xFFBCBE00.toInt(), 0xFF88D800.toInt(), 0xFF5CE430.toInt(), 0xFF45E082.toInt(),
            0xFF48CDDE.toInt(), 0xFF4F4F4F.toInt(), 0xFF000000.toInt(), 0xFF000000.toInt(),
            0xFFFFFEFF.toInt(), 0xFFC0DFFF.toInt(), 0xFFD3D2FF.toInt(), 0xFFE8C8FF.toInt(),
            0xFFFBC2FF.toInt(), 0xFFFEC4EA.toInt(), 0xFFFECCC5.toInt(), 0xFFF7D8A5.toInt(),
            0xFFE4E594.toInt(), 0xFFCFEF96.toInt(), 0xFFBDF4AB.toInt(), 0xFFB3F3CC.toInt(),
            0xFFB5EBF2.toInt(), 0xFFB8B8B8.toInt(), 0xFF000000.toInt(), 0xFF000000.toInt()
        )
    }

    /**
     * Decode a single 8×8 tile pattern into palette indices (0–3).
     * Returns an array of 64 values.
     */
    fun decodeTile(tileData: ByteArray, offset: Int = 0): IntArray {
        val pixels = IntArray(PIXELS_PER_TILE)
        for (row in 0 until TILE_SIZE) {
            val plane0 = tileData[offset + row].toInt() and 0xFF
            val plane1 = tileData[offset + row + 8].toInt() and 0xFF
            for (col in 0 until TILE_SIZE) {
                val bit = 7 - col
                val p0 = (plane0 shr bit) and 1
                val p1 = (plane1 shr bit) and 1
                pixels[row * TILE_SIZE + col] = p0 or (p1 shl 1)
            }
        }
        return pixels
    }

    /**
     * Decode tile pattern data from a specific ROM offset.
     */
    fun decodeTileFromRom(romOffset: Int): IntArray {
        val data = rom.romData.copyOfRange(romOffset, romOffset + BYTES_PER_TILE)
        return decodeTile(data)
    }

    /**
     * Decode tile pattern data from a CPU address within a bank.
     */
    fun decodeTileFromBank(bankNumber: Int, cpuAddress: Int): IntArray {
        val offset = rom.bankAddressToRomOffset(bankNumber, cpuAddress)
        return decodeTileFromRom(offset)
    }

    /**
     * Read area-specific palette data from ROM.
     *
     * Each area bank has a palette pointer table (PalPntrTbl) at $9560.
     * Each entry points to a PPU write command string:
     *   [PPU addr high ($3F)] [PPU addr low] [length] [data bytes...] [0x00 terminator]
     * The data bytes are NES palette indices written to PPU palette RAM ($3F00–$3F1F).
     *
     * Palette 0 is the initial full palette loaded at area init (typically writes all 32 bytes).
     * Subsequent palettes only update partial ranges (e.g., changing sprite colors).
     */
    fun readAreaPalette(bankNumber: Int, paletteIndex: Int): IntArray {
        val palTableAddr = PAL_PTR_TABLE_ADDR
        val palAddr = rom.readBankWord(bankNumber, palTableAddr + paletteIndex * 2)
        return parsePpuPaletteString(bankNumber, palAddr)
    }

    /**
     * Parse a PPU write command string into a 32-entry ARGB palette array.
     * Each command: addr_hi, addr_lo, length, then data bytes. Terminated by 0x00.
     * PPU 0x3F00-0x3F0F = background palettes, 0x3F10-0x3F1F = sprite palettes.
     */
    private fun parsePpuPaletteString(bankNumber: Int, startAddr: Int): IntArray {
        val palette = IntArray(32) { NES_PALETTE[0x0F] } // default to black
        var pos = startAddr

        while (true) {
            val addrHi = rom.readBankByte(bankNumber, pos)
            if (addrHi == 0x00) break

            val addrLo = rom.readBankByte(bankNumber, pos + 1)
            val length = rom.readBankByte(bankNumber, pos + 2)
            pos += 3

            val ppuAddr = (addrHi shl 8) or addrLo
            for (i in 0 until length) {
                val paletteOffset = (ppuAddr + i) - 0x3F00
                if (paletteOffset in 0 until 32) {
                    val nesColor = rom.readBankByte(bankNumber, pos + i) and 0x3F
                    palette[paletteOffset] = NES_PALETTE[nesColor]
                }
            }
            pos += length
        }
        return palette
    }

    /**
     * Get a 4-color sub-palette (background palette 0–3 or sprite palette 4–7).
     */
    fun getSubPalette(fullPalette: IntArray, subPaletteIndex: Int): IntArray {
        val start = subPaletteIndex * 4
        return intArrayOf(
            fullPalette[0],            // Color 0 is always the universal background color
            fullPalette[start + 1],
            fullPalette[start + 2],
            fullPalette[start + 3]
        )
    }

    /**
     * Render a single 8×8 tile to ARGB pixels using a sub-palette.
     * Color index 0 is transparent (returns 0x00000000).
     */
    fun renderTile(tilePixels: IntArray, subPalette: IntArray, transparent: Boolean = false): IntArray {
        return IntArray(PIXELS_PER_TILE) { i ->
            val colorIdx = tilePixels[i]
            if (colorIdx == 0 && transparent) 0x00000000
            else subPalette[colorIdx.coerceIn(0, 3)]
        }
    }

    /**
     * LoadSamusGFX BG entries — loaded before area-specific GFX.
     * Entry 23: complete font → PPU $1000 (BG tiles 0x00-0x3F)
     * Entry 22: solid tiles → PPU $1FC0 (BG tiles 0xFC-0xFF)
     */
    private val samusGfxBgEntries = intArrayOf(23, 22)

    /**
     * GFX entry indices loaded by each area's init routine (from the disassembly).
     * These are indices into the GFXInfo table at 0xC6E0.
     * Order matters: later entries can overwrite earlier ones in PPU address space.
     */
    private val areaGfxEntries = mapOf(
        com.metroid.editor.data.Area.BRINSTAR to intArrayOf(3, 4, 5, 6, 25, 22),
        com.metroid.editor.data.Area.NORFAIR to intArrayOf(4, 5, 7, 8, 9, 25, 22),
        com.metroid.editor.data.Area.KRAID to intArrayOf(4, 5, 10, 15, 16, 17, 25, 22),
        com.metroid.editor.data.Area.TOURIAN to intArrayOf(5, 10, 11, 12, 13, 14, 26, 28, 25, 22),
        com.metroid.editor.data.Area.RIDLEY to intArrayOf(4, 5, 10, 18, 19, 25, 22)
    )

    private val chrRamCache = mutableMapOf<com.metroid.editor.data.Area, ByteArray>()

    data class GfxInfoEntry(
        val bankNumber: Int,
        val srcAddr: Int,
        val ppuDest: Int,
        val length: Int
    )

    private fun readGfxInfoEntry(index: Int): GfxInfoEntry {
        val addr = GFX_INFO_ADDR + index * GFX_INFO_ENTRY_SIZE
        return GfxInfoEntry(
            bankNumber = rom.readByte(rom.bankAddressToRomOffset(7, addr)),
            srcAddr = rom.readWord(rom.bankAddressToRomOffset(7, addr + 1)),
            ppuDest = rom.readWord(rom.bankAddressToRomOffset(7, addr + 3)),
            length = rom.readWord(rom.bankAddressToRomOffset(7, addr + 5))
        )
    }

    /**
     * Build the virtual 8KB CHR RAM (two 4KB pattern tables) for an area,
     * mimicking the game's area init GFX loading sequence.
     * Returns an 8KB ByteArray indexed by PPU address 0x0000-0x1FFF.
     */
    fun buildChrRam(area: com.metroid.editor.data.Area): ByteArray {
        chrRamCache[area]?.let { return it }

        val chrRam = ByteArray(0x2000)
        val areaEntries = areaGfxEntries[area] ?: return chrRam

        // LoadSamusGFX BG entries first (base layer), then area-specific entries overwrite
        val allEntries = samusGfxBgEntries + areaEntries

        for (entryIdx in allEntries) {
            val entry = readGfxInfoEntry(entryIdx)
            if (entry.ppuDest !in 0 until 0x2000) continue
            val srcRomOffset = rom.bankAddressToRomOffset(entry.bankNumber, entry.srcAddr)
            val length = minOf(entry.length, 0x2000 - entry.ppuDest, rom.romData.size - srcRomOffset)
            if (length > 0) {
                System.arraycopy(rom.romData, srcRomOffset, chrRam, entry.ppuDest, length)
            }
        }

        chrRamCache[area] = chrRam
        return chrRam
    }

    /**
     * Decode a background tile from the assembled CHR RAM.
     * Background pattern table is at PPU 0x1000-0x1FFF (tile indices 0x00-0xFF).
     */
    fun decodeBgTileFromChrRam(chrRam: ByteArray, tileIndex: Int): IntArray {
        val offset = BG_PATTERN_TABLE_BASE + tileIndex * BYTES_PER_TILE
        if (offset + BYTES_PER_TILE > chrRam.size) return IntArray(PIXELS_PER_TILE)
        return decodeTile(chrRam, offset)
    }
}
