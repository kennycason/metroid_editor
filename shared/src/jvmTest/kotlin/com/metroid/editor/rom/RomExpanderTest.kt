package com.metroid.editor.rom

import com.metroid.editor.data.Area
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.io.File

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RomExpanderTest {

    private var rom: ByteArray? = null

    @BeforeAll
    fun setup() {
        val romFile = NesRomParserTest.findRomFile()
        if (romFile != null) {
            val rawData = romFile.readBytes()
            rom = NesRomParser.ensureHeader(rawData)
        }
    }

    @Test
    fun `expand doubles ROM size and updates header`() {
        val origRom = rom ?: return
        assertTrue(RomExpander.isStandardRom(origRom), "ROM should be standard size")

        val expanded = RomExpander.expand(origRom)
        assertNotNull(expanded)
        expanded!!

        assertEquals(RomExpander.EXPANDED_ROM_SIZE, expanded.size, "Expanded ROM should be 256KB + header")
        assertEquals(16, expanded[RomExpander.BANK_COUNT_OFFSET].toInt() and 0xFF, "PRG bank count should be 16")
        assertTrue(RomExpander.isExpandedRom(expanded))
    }

    @Test
    fun `expanded ROM has valid iNES header`() {
        val origRom = rom ?: return
        val expanded = RomExpander.expand(origRom) ?: return

        // First 4 bytes: NES\x1A
        assertEquals('N'.code.toByte(), expanded[0])
        assertEquals('E'.code.toByte(), expanded[1])
        assertEquals('S'.code.toByte(), expanded[2])
        assertEquals(0x1A.toByte(), expanded[3])
        // Byte 4: PRG count = 16
        assertEquals(16, expanded[4].toInt() and 0xFF)
        // Bytes 5-15: same as original
        for (i in 5 until 16) {
            assertEquals(origRom[i], expanded[i], "Header byte $i should match original")
        }
    }

    @Test
    fun `fixed bank is preserved at end of expanded ROM`() {
        val origRom = rom ?: return
        val expanded = RomExpander.expand(origRom) ?: return

        val origFixedStart = RomExpander.STANDARD_ROM_SIZE - RomExpander.PRG_BANK_SIZE
        val expandedFixedStart = RomExpander.EXPANDED_ROM_SIZE - RomExpander.PRG_BANK_SIZE

        // Verify the original bank 7 code (excluding patched regions) is intact
        // The patched regions are small (11 + 226 bytes), everything else should match
        var matches = 0
        var diffs = 0
        for (i in 0 until RomExpander.PRG_BANK_SIZE) {
            if (origRom[origFixedStart + i] == expanded[expandedFixedStart + i]) matches++
            else diffs++
        }
        // Most of the 16KB bank should match (patches are < 300 bytes)
        assertTrue(matches > 16000, "Most of fixed bank should be preserved (matches=$matches, diffs=$diffs)")
        assertTrue(diffs < 400, "Only patched bytes should differ (diffs=$diffs)")
    }

    @Test
    fun `expanded ROM can be parsed and all areas readable`() {
        val origRom = rom ?: return
        val expanded = RomExpander.expand(origRom) ?: return
        val parser = NesRomParser(expanded)

        assertTrue(parser.isMetroidRom(), "Expanded ROM should still be detected as Metroid")

        val md = MetroidRomData(parser)
        for (area in Area.entries) {
            val ptrs = md.readAreaPointers(area)
            // Verify key pointers are still valid
            assertTrue(ptrs.roomPtrTable in 0x8000..0xBFFF, "${area}: roomPtrTable out of range")
            assertTrue(ptrs.structPtrTable in 0x8000..0xBFFF, "${area}: structPtrTable out of range")
            assertTrue(ptrs.macroDefs in 0x8000..0xBFFF, "${area}: macroDefs out of range")

            val rooms = md.readAllRooms(area)
            assertTrue(rooms.isNotEmpty(), "${area}: should have rooms")
            println("${area.displayName}: ${rooms.size} rooms, ptrs OK")
        }
    }

    @Test
    fun `expanded ROM has freed space in area banks`() {
        val origRom = rom ?: return
        val expanded = RomExpander.expand(origRom) ?: return

        // Check that pattern data was zeroed in area banks
        // Brinstar (bank 1) had patterns at $8D60 (0x400 bytes) and $9160 (0x400 bytes)
        val bank1Start = 0x10 + 1 * 0x4000
        val patternOffset1 = bank1Start + (0x8D60 - 0x8000)
        val patternOffset2 = bank1Start + (0x9160 - 0x8000)

        var zeroCount1 = 0
        for (i in 0 until 0x400) {
            if (expanded[patternOffset1 + i] == 0x00.toByte()) zeroCount1++
        }
        var zeroCount2 = 0
        for (i in 0 until 0x400) {
            if (expanded[patternOffset2 + i] == 0x00.toByte()) zeroCount2++
        }

        println("Brinstar bank pattern areas zeroed: $zeroCount1/1024 and $zeroCount2/1024 bytes")
        assertTrue(zeroCount1 > 900, "Brinstar pattern area 1 should be mostly zeroed ($zeroCount1/1024)")
        assertTrue(zeroCount2 > 900, "Brinstar pattern area 2 should be mostly zeroed ($zeroCount2/1024)")
    }

    @Test
    fun `expanded ROM has pattern data in new banks`() {
        val origRom = rom ?: return
        val expanded = RomExpander.expand(origRom) ?: return

        // Brinstar BG patterns should be at 0x1C010 in expanded ROM
        val bgDest = 0x1C010
        var nonZero = 0
        for (i in 0 until 0x1000) {
            if (expanded[bgDest + i] != 0x00.toByte()) nonZero++
        }
        assertTrue(nonZero > 100, "Brinstar BG patterns should exist at 0x1C010 ($nonZero non-zero bytes)")

        // Player sprites should be at 0x22410
        val sprDest = 0x22410
        var sprNonZero = 0
        for (i in 0 until 0x09A0) {
            if (expanded[sprDest + i] != 0x00.toByte()) sprNonZero++
        }
        assertTrue(sprNonZero > 100, "Player sprites should exist at 0x22410 ($sprNonZero non-zero bytes)")
    }

    @Test
    fun `expanded ROM passes bankswitch patch verification`() {
        val origRom = rom ?: return
        val expanded = RomExpander.expand(origRom) ?: return

        // Verify the banking hack at ROM offset 0x3C4FF
        val hackOffset = 0x3C4FF
        assertEquals(0x98.toByte(), expanded[hackOffset], "TYA")
        assertEquals(0x85.toByte(), expanded[hackOffset + 1], "STA")
        assertEquals(0x00.toByte(), expanded[hackOffset + 2], "$00")
        assertEquals(0xEA.toByte(), expanded[hackOffset + 9], "NOP")
        assertEquals(0xEA.toByte(), expanded[hackOffset + 10], "NOP")
    }

    @Test
    fun `write expanded ROM for emulator testing`() {
        val origRom = rom ?: return
        val expanded = RomExpander.expand(origRom) ?: return

        val outFile = File("/tmp/metroid_expanded.nes")
        outFile.writeBytes(expanded)
        println("Expanded ROM written to: ${outFile.absolutePath} (${expanded.size} bytes)")
    }
}
