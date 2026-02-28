package com.metroid.editor.rom

import com.metroid.editor.data.Area
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.io.File

/**
 * Tests for Metroid-specific data extraction.
 * Validates area pointers, room parsing, structure/macro reading, and world map.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MetroidRomDataTest {

    private var rom: NesRomParser? = null
    private var data: MetroidRomData? = null

    @BeforeAll
    fun setup() {
        val romFile = NesRomParserTest.findRomFile()
        if (romFile != null) {
            rom = NesRomParser(romFile.readBytes())
            data = MetroidRomData(rom!!)
        }
    }

    @Test
    fun `area pointers are valid for Brinstar`() {
        val d = data ?: return
        val ptrs = d.readAreaPointers(Area.BRINSTAR)

        // All pointers should be in the switchable bank range ($8000-$BFFF)
        assertTrue(ptrs.roomPtrTable in 0x8000..0xBFFF,
            "RmPtrTbl should be in bank range, got 0x${ptrs.roomPtrTable.toString(16)}")
        assertTrue(ptrs.structPtrTable in 0x8000..0xBFFF,
            "StrctPtrTbl should be in bank range, got 0x${ptrs.structPtrTable.toString(16)}")
        assertTrue(ptrs.macroDefs in 0x8000..0xBFFF,
            "MacroDefs should be in bank range, got 0x${ptrs.macroDefs.toString(16)}")

        // Room pointer table should come before structure table
        assertTrue(ptrs.roomPtrTable < ptrs.structPtrTable,
            "Room pointers should precede structure pointers")
    }

    @Test
    fun `area pointers valid for all areas`() {
        val d = data ?: return
        for (area in Area.entries) {
            val ptrs = d.readAreaPointers(area)
            assertTrue(ptrs.roomPtrTable in 0x8000..0xBFFF,
                "${area.displayName}: RmPtrTbl 0x${ptrs.roomPtrTable.toString(16)} out of range")
            assertTrue(ptrs.structPtrTable in 0x8000..0xBFFF,
                "${area.displayName}: StrctPtrTbl 0x${ptrs.structPtrTable.toString(16)} out of range")
            assertTrue(ptrs.macroDefs in 0x8000..0xBFFF,
                "${area.displayName}: MacroDefs 0x${ptrs.macroDefs.toString(16)} out of range")
        }
    }

    @Test
    fun `Brinstar has reasonable room count`() {
        val d = data ?: return
        val count = d.getRoomCount(Area.BRINSTAR)
        // Brinstar should have roughly 30-50 rooms
        assertTrue(count in 10..80,
            "Brinstar room count $count seems wrong (expected 10-80)")
        println("Brinstar rooms: $count")
    }

    @Test
    fun `all areas have rooms`() {
        val d = data ?: return
        for (area in Area.entries) {
            val count = d.getRoomCount(area)
            assertTrue(count > 0, "${area.displayName} should have at least 1 room, got $count")
            println("${area.displayName}: $count rooms")
        }
    }

    @Test
    fun `can parse Brinstar room 0`() {
        val d = data ?: return
        val room = d.readRoom(Area.BRINSTAR, 0)
        assertNotNull(room, "Brinstar room 0 should be parseable")

        room!!
        assertEquals(Area.BRINSTAR, room.area)
        assertEquals(0, room.roomNumber)
        assertTrue(room.palette in 0..3, "Palette should be 0-3, got ${room.palette}")
        assertTrue(room.objects.isNotEmpty(), "Room should have at least one object")
        println("Brinstar room 0: palette=${room.palette}, ${room.objects.size} objects, " +
                "${room.enemies.size} enemies, ${room.doors.size} doors")
    }

    @Test
    fun `room objects have valid positions`() {
        val d = data ?: return
        val room = d.readRoom(Area.BRINSTAR, 0) ?: return

        for (obj in room.objects) {
            assertTrue(obj.posX in 0..15, "Object X=${obj.posX} out of macro range")
            assertTrue(obj.posY in 0..15, "Object Y=${obj.posY} out of macro range")
            assertTrue(obj.palette in 0..3, "Object palette=${obj.palette} out of range")
        }
    }

    @Test
    fun `can read structures`() {
        val d = data ?: return
        val room = d.readRoom(Area.BRINSTAR, 0) ?: return

        for (obj in room.objects.take(3)) {
            val struct = d.readStructure(Area.BRINSTAR, obj.structIndex)
            assertNotNull(struct, "Structure ${obj.structIndex} should be readable")
            struct!!
            assertTrue(struct.rows.isNotEmpty(), "Structure should have at least one row")
            println("Structure ${obj.structIndex}: ${struct.rows.size} rows, " +
                    "first row has ${struct.rows.first().macroIndices.size} macros")
        }
    }

    @Test
    fun `can read macros`() {
        val d = data ?: return
        val macro0 = d.readMacro(Area.BRINSTAR, 0)
        assertNotNull(macro0, "Macro 0 should be readable")
        macro0!!
        // Tile indices should be in valid range (0-255)
        assertTrue(macro0.topLeft in 0..255)
        assertTrue(macro0.topRight in 0..255)
        assertTrue(macro0.botLeft in 0..255)
        assertTrue(macro0.botRight in 0..255)
        println("Macro 0: TL=${"$%02X".format(macro0.topLeft)} TR=${"$%02X".format(macro0.topRight)} " +
                "BL=${"$%02X".format(macro0.botLeft)} BR=${"$%02X".format(macro0.botRight)}")
    }

    @Test
    fun `can read all macros for each area`() {
        val d = data ?: return
        for (area in Area.entries) {
            val macros = d.readAllMacros(area)
            assertTrue(macros.isNotEmpty(), "${area.displayName} should have macros")
            println("${area.displayName}: ${macros.size} macros")
        }
    }

    @Test
    fun `shared world map is readable from bank 0`() {
        val d = data ?: return
        val cells = d.readWorldMap()

        assertEquals(1024, cells.size, "World map should be 32×32 = 1024 cells")
        val nonEmpty = cells.filter { !it.isEmpty }
        assertTrue(nonEmpty.size > 100, "World map should have many non-empty cells, got ${nonEmpty.size}")
        println("World map: ${nonEmpty.size} non-empty cells out of 1024")

        val bounds = d.getWorldMapBounds()
        assertNotNull(bounds)
        println("World map bounds: x=${bounds!!.minX}-${bounds.maxX}, y=${bounds.minY}-${bounds.maxY}")
    }

    @Test
    fun `can parse all rooms in all areas`() {
        val d = data ?: return
        var totalRooms = 0
        for (area in Area.entries) {
            val rooms = d.readAllRooms(area)
            assertTrue(rooms.isNotEmpty(), "${area.displayName} should have parseable rooms")
            totalRooms += rooms.size
            println("${area.displayName}: ${rooms.size} rooms parsed")
        }
        println("Total rooms across all areas: $totalRooms")
        assertTrue(totalRooms > 50, "Total room count should be > 50")
    }

    @Test
    fun `special room numbers for Brinstar`() {
        val d = data ?: return
        // From disassembly: special rooms are $2B, $2C, $28, $0B, $1C, $0A, $1A
        val specialRooms = listOf(0x2B, 0x2C, 0x28, 0x0B, 0x1C, 0x0A, 0x1A)
        val roomCount = d.getRoomCount(Area.BRINSTAR)
        for (roomNum in specialRooms) {
            if (roomNum < roomCount) {
                val room = d.readRoom(Area.BRINSTAR, roomNum)
                assertNotNull(room, "Special room 0x${roomNum.toString(16)} should be parseable")
            }
        }
    }

    @Test
    fun `Samus start position is in Brinstar`() {
        val d = data ?: return
        // From disassembly: Samus starts at map pos (3, 14) in Brinstar
        val cells = d.readWorldMap()

        val startCell = cells.find { it.x == 3 && it.y == 14 }
        assertNotNull(startCell, "Cell (3,14) should exist in world map")
        assertFalse(startCell!!.isEmpty, "Samus start position should have a room")
        println("Samus start room: 0x%02X".format(startCell.roomNumber))
    }
}
