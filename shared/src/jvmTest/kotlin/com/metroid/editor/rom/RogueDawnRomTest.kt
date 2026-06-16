package com.metroid.editor.rom

import com.metroid.editor.data.Area
import org.junit.jupiter.api.Assertions.assertArrayEquals
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

    @Test
    fun `Rogue Dawn structures and macros expand room objects`() {
        val parser = loadRogueDawn()
        val data = RogueDawnRomData(parser)
        val renderer = RogueDawnMapRenderer(data, NesPatternDecoder(parser))
        val room = data.readRoom(Area.BRINSTAR, 0) ?: error("Missing Brinstar room 0")

        val firstStructure = renderer.readStructure(Area.BRINSTAR, room.objects.first().structIndex)
            ?: error("Missing first room structure")
        val grid = renderer.buildMacroGrid(room)

        assertTrue(firstStructure.rows.isNotEmpty())
        assertTrue(grid.macros.any { it >= 0 }, "Room should expand into placed macros")
        assertEquals(0x10, RogueDawnMapRenderer.AREA_DATA_BANKS.getValue(Area.BRINSTAR))
        assertEquals(0x09, RogueDawnMapRenderer.AREA_BG_CHR_TABLE_INDEX.getValue(Area.BRINSTAR))
        assertArrayEquals(intArrayOf(0x1C, 0x1D, 0x12, 0x13), renderer.backgroundChr1kBanks(Area.BRINSTAR))
        assertEquals(0x38, RogueDawnMapRenderer.AREA_BG_CHR_TABLE_INDEX.getValue(Area.KRAID))
        assertArrayEquals(intArrayOf(0x58, 0x59, 0x5A, 0x5B), renderer.backgroundChr1kBanks(Area.KRAID))
        assertEquals(0x2B, RogueDawnMapRenderer.AREA_BG_CHR_TABLE_INDEX.getValue(Area.TOURIAN))
        assertArrayEquals(intArrayOf(0x40, 0x41, 0x42, 0x43), renderer.backgroundChr1kBanks(Area.TOURIAN))
    }

    @Test
    fun `Rogue Dawn room scripts select alternate background CHR banks`() {
        val parser = loadRogueDawn()
        val data = RogueDawnRomData(parser)
        val renderer = RogueDawnMapRenderer(data, NesPatternDecoder(parser))

        val brinstarRoom10 = data.readRoom(Area.BRINSTAR, 0x10) ?: error("Missing Brinstar room 10")
        val brinstarRoom12 = data.readRoom(Area.BRINSTAR, 0x12) ?: error("Missing Brinstar room 12")
        val brinstarRoom13 = data.readRoom(Area.BRINSTAR, 0x13) ?: error("Missing Brinstar room 13")
        val tourianRoom49 = data.readRoom(Area.TOURIAN, 0x49) ?: error("Missing Tourian room 49")
        val ridleyRoom55 = data.readRoom(Area.RIDLEY, 0x55) ?: error("Missing Ridley room 55")

        assertEquals(0x0D, renderer.backgroundChrTableIndex(brinstarRoom10))
        assertArrayEquals(intArrayOf(0x80, 0x81, 0x82, 0x83), renderer.backgroundChr1kBanks(brinstarRoom10))
        assertEquals(0x0D, renderer.backgroundChrTableIndex(brinstarRoom12))
        assertEquals(0x09, renderer.backgroundChrTableIndex(brinstarRoom13))
        assertEquals(0x2F, renderer.backgroundChrTableIndex(tourianRoom49))
        assertArrayEquals(intArrayOf(0x40, 0xD7, 0xD8, 0x84), renderer.backgroundChr1kBanks(tourianRoom49))
        assertEquals(0x66, renderer.backgroundChrTableIndex(ridleyRoom55))
        assertArrayEquals(intArrayOf(0xEC, 0xED, 0xEE, 0xEF), renderer.backgroundChr1kBanks(ridleyRoom55))
    }

    @Test
    fun `Rogue Dawn tile renderer produces nonblank room pixels`() {
        val parser = loadRogueDawn()
        val data = RogueDawnRomData(parser)
        val renderer = RogueDawnMapRenderer(data, NesPatternDecoder(parser))
        val room = data.readRoom(Area.BRINSTAR, 0) ?: error("Missing Brinstar room 0")

        val rendered = renderer.renderRoom(room)
        val uniqueColors = rendered.pixels.toSet()

        assertEquals(MapRenderer.ROOM_WIDTH_PX, rendered.width)
        assertEquals(MapRenderer.ROOM_HEIGHT_PX, rendered.height)
        assertTrue(uniqueColors.size > 4, "Rendered room should contain decoded CHR tile colors")
    }
}
