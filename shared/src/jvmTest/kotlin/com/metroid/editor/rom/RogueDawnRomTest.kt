package com.metroid.editor.rom

import com.metroid.editor.data.Area
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Characterization tests for Rogue Dawn V121.
 *
 * The ROM is not stored in this repository. Set ROGUE_DAWN_ROM to a local copy, or keep the
 * default local path used during development, to enable these tests.
 */
class RogueDawnRomTest {
    private fun findRogueDawnRom(): File? {
        val envPath = System.getenv("ROGUE_DAWN_ROM")
        if (!envPath.isNullOrBlank()) {
            val envFile = File(envPath)
            if (envFile.exists()) return envFile
        }

        return listOf(
            "/Users/kenny/Dropbox/emulator/nes/Metroid/Rogue Dawn V121/Rogue Dawn V121.nes",
            "roms/Rogue Dawn V121.nes",
            "../roms/Rogue Dawn V121.nes"
        )
            .map(::File)
            .firstOrNull { it.exists() }
    }

    private fun loadRogueDawn(): NesRomParser {
        val romFile = findRogueDawnRom()
        assumeTrue(romFile != null, "Rogue Dawn ROM not found; set ROGUE_DAWN_ROM")
        return NesRomParser(romFile!!.readBytes())
    }

    @Test
    fun `Rogue Dawn header identifies MMC3 CHR ROM layout`() {
        val parser = loadRogueDawn()

        assertEquals("NES\u001A", parser.header.magic)
        assertEquals(32, parser.header.prgBanks)
        assertEquals(32, parser.header.chrBanks)
        assertEquals(4, parser.mapper)
        assertEquals(16 + 32 * 0x4000 + 32 * 0x2000, parser.romData.size)
        assertTrue(RogueDawnRomData.isSupported(parser))
    }

    @Test
    fun `current vanilla Metroid gate rejects Rogue Dawn`() {
        val parser = loadRogueDawn()

        assertFalse(
            parser.isMetroidRom(),
            "Rogue Dawn needs an MMC3 plus CHR-ROM profile, not just relaxed validation"
        )
    }

    @Test
    fun `Rogue Dawn world map is still readable at vanilla world map location`() {
        val parser = loadRogueDawn()
        val data = MetroidRomData(parser)

        val cells = data.readWorldMap()
        val nonEmpty = cells.filter { !it.isEmpty }

        assertEquals(1024, cells.size)
        assertEquals(738, nonEmpty.size)
        assertTrue(nonEmpty.any { it.roomNumber >= 0x80 }, "Rogue Dawn uses expanded room IDs in the world map")
    }

    @Test
    fun `vanilla area room count calculation is invalid for Rogue Dawn`() {
        val parser = loadRogueDawn()
        val data = MetroidRomData(parser)

        for (area in Area.entries) {
            val count = data.getRoomCount(area)
            assertTrue(
                count < 0,
                "${area.displayName}: vanilla 2-byte in-bank room table assumption unexpectedly produced $count"
            )
        }
    }

    @Test
    fun `Rogue Dawn Brinstar room table looks like far pointers`() {
        val parser = loadRogueDawn()
        val data = MetroidRomData(parser)
        val ptrs = data.readAreaPointers(Area.BRINSTAR)
        val bank = MetroidRomData.AREA_BANKS[Area.BRINSTAR] ?: error("No Brinstar bank")
        val roomTableOffset = parser.bankAddressToRomOffset(bank, ptrs.roomPtrTable)

        val firstEntryBank = parser.readByte(roomTableOffset)
        val firstEntryAddr = parser.readWord(roomTableOffset + 1)
        val secondEntryBank = parser.readByte(roomTableOffset + 3)
        val secondEntryAddr = parser.readWord(roomTableOffset + 4)

        assertEquals(0x0C, firstEntryBank)
        assertEquals(0xA000, firstEntryAddr)
        assertEquals(0x0C, secondEntryBank)
        assertEquals(0xA042, secondEntryAddr)
    }

    @Test
    fun `Rogue Dawn far pointer room tables are readable for every area`() {
        val parser = loadRogueDawn()
        val data = RogueDawnRomData(parser)
        val expectedCounts = mapOf(
            Area.BRINSTAR to 115,
            Area.NORFAIR to 178,
            Area.TOURIAN to 118,
            Area.KRAID to 192,
            Area.RIDLEY to 180
        )
        val expectedFirstPointers = mapOf(
            Area.BRINSTAR to RogueDawnFarPointer(0x0C, 0xA000),
            Area.NORFAIR to RogueDawnFarPointer(0x0E, 0xB140),
            Area.TOURIAN to RogueDawnFarPointer(0x0E, 0xBFD0),
            Area.KRAID to RogueDawnFarPointer(0x1A, 0xBA1B),
            Area.RIDLEY to RogueDawnFarPointer(0x11, 0xBFFC)
        )

        for (area in Area.entries) {
            val refs = data.readRoomRefs(area)

            assertEquals(expectedCounts.getValue(area), refs.size, area.displayName)
            assertEquals(expectedFirstPointers.getValue(area), refs.first().pointer, area.displayName)
            assertTrue(refs.all { it.romOffset in 16 until 16 + parser.prgSize }, area.displayName)
        }
    }

    @Test
    fun `Rogue Dawn room data parses into vanilla room payload model`() {
        val parser = loadRogueDawn()
        val data = RogueDawnRomData(parser)

        val room = data.readRoom(Area.BRINSTAR, 0) ?: error("Missing Brinstar room 0")
        val firstObject = room.objects.first()

        assertEquals(2, room.palette)
        assertEquals(20, room.objects.size)
        assertEquals(0, room.enemies.size)
        assertEquals(1, room.doors.size)
        assertEquals(65, room.rawData.size)
        assertEquals(0, firstObject.posY)
        assertEquals(15, firstObject.posX)
        assertEquals(0x90, firstObject.structIndex)
        assertEquals(0, firstObject.palette)
        assertEquals(0xA1, room.doors.single().info)
    }

    @Test
    fun `Rogue Dawn room parser handles all discovered rooms`() {
        val parser = loadRogueDawn()
        val data = RogueDawnRomData(parser)

        for (area in Area.entries) {
            val rooms = data.readAllRooms(area)

            assertEquals(data.getRoomCount(area), rooms.size, area.displayName)
            assertTrue(rooms.all { it.rawData.isNotEmpty() }, area.displayName)
            assertTrue(rooms.any { it.objects.isNotEmpty() }, area.displayName)
        }
    }
}
