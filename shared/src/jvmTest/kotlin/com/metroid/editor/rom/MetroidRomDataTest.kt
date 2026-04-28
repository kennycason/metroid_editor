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

    // --- Room neighbor tests verified against m1map_room_ids.png ---

    @Test
    fun `MAP_AREA_INDEX covers all non-FF cells`() {
        val d = data ?: return
        val cells = d.readWorldMap()
        var missingArea = 0
        for (cell in cells) {
            if (!cell.isEmpty) {
                val area = MetroidRomData.areaAt(cell.x, cell.y)
                if (area == null) {
                    missingArea++
                    println("  Cell (${cell.x},${cell.y}) room=$%02X has no area".format(cell.roomNumber))
                }
            }
        }
        assertEquals(0, missingArea, "All non-FF cells must have an area assignment")
    }

    @Test
    fun `Brinstar room 09 at start position has correct neighbors`() {
        val d = data ?: return
        // Brinstar starts at (3,14), room $09
        val n = d.findRoomNeighbors(Area.BRINSTAR, 0x09)
        assertNotNull(n)
        assertEquals(3, n!!.mapX)
        assertEquals(14, n.mapY)
        // Left: (2,14) = $17, Right: (4,14) = $14
        assertEquals(0x17, n.left?.roomNumber, "Left of start should be room \$17")
        assertEquals(0x14, n.right?.roomNumber, "Right of start should be room \$14")
    }

    @Test
    fun `Brinstar horizontal shaft room 14 right of start`() {
        val d = data ?: return
        // (4,14) = room $14 in Brinstar
        val n = d.findRoomNeighbors(Area.BRINSTAR, 0x14, hintMapX = 4, hintMapY = 14)
        assertNotNull(n)
        assertEquals(0x09, n!!.left?.roomNumber, "Left should be room \$09")
        assertEquals(0x13, n.right?.roomNumber, "Right should be room \$13")
    }

    @Test
    fun `Brinstar room 18 has left right and down neighbors`() {
        val d = data ?: return
        // From the map: (6,14) = room $18, left=(5,14)=$13, right=(7,14)=$12
        val n = d.findRoomNeighbors(Area.BRINSTAR, 0x18, hintMapX = 6, hintMapY = 14)
        assertNotNull(n)
        assertEquals(0x13, n!!.left?.roomNumber, "Left of \$18 should be \$13")
        assertEquals(0x12, n.right?.roomNumber, "Right of \$18 should be \$12")
    }

    @Test
    fun `Norfair room 08 at top-left shaft entrance`() {
        val d = data ?: return
        // Norfair: (13,14) = room $08 (from map)
        val n = d.findRoomNeighbors(Area.NORFAIR, 0x08, hintMapX = 13, hintMapY = 14)
        assertNotNull(n)
        assertEquals(13, n!!.mapX)
        assertEquals(14, n.mapY)
        assertEquals(0x1D, n.right?.roomNumber, "Right of Norfair \$08 should be \$1D")
    }

    @Test
    fun `Brinstar room 08 at map position 1_14 is Brinstar not Norfair`() {
        val d = data ?: return
        // (1,14) = room $08 in Brinstar (from MapIndex)
        assertEquals(Area.BRINSTAR, MetroidRomData.areaAt(1, 14))
        // (13,14) = room $08 in Norfair
        assertEquals(Area.NORFAIR, MetroidRomData.areaAt(13, 14))
    }

    @Test
    fun `Brinstar start shaft rooms 08-17-09-14-13 connect horizontally`() {
        val d = data ?: return
        // Known Brinstar horizontal shaft from m1map: (1,14)=$08, (2,14)=$17, (3,14)=$09, (4,14)=$14, (5,14)=$13
        val cells = d.readWorldMap()
        fun roomAt(x: Int, y: Int) = cells[y * 32 + x].roomNumber

        assertEquals(0x08, roomAt(1, 14), "Map (1,14)")
        assertEquals(0x17, roomAt(2, 14), "Map (2,14)")
        assertEquals(0x09, roomAt(3, 14), "Map (3,14)")
        assertEquals(0x14, roomAt(4, 14), "Map (4,14)")
        assertEquals(0x13, roomAt(5, 14), "Map (5,14)")
        assertEquals(0x18, roomAt(6, 14), "Map (6,14)")
        assertEquals(0x12, roomAt(7, 14), "Map (7,14)")

        // Verify navigation chain works
        val n1 = d.findRoomNeighbors(Area.BRINSTAR, 0x08, 1, 14)!!
        assertEquals(0x17, n1.right?.roomNumber)

        val n2 = d.findRoomNeighbors(Area.BRINSTAR, 0x17, 2, 14)!!
        assertEquals(0x08, n2.left?.roomNumber)
        assertEquals(0x09, n2.right?.roomNumber)

        val n3 = d.findRoomNeighbors(Area.BRINSTAR, 0x09, 3, 14)!!
        assertEquals(0x17, n3.left?.roomNumber)
        assertEquals(0x14, n3.right?.roomNumber)
    }

    @Test
    fun `Kraid area cells are correctly attributed`() {
        val d = data ?: return
        // Kraid occupies bottom-left region
        assertEquals(Area.KRAID, MetroidRomData.areaAt(1, 21))
        assertEquals(Area.KRAID, MetroidRomData.areaAt(8, 24))
        assertEquals(Area.KRAID, MetroidRomData.areaAt(12, 30))
    }

    @Test
    fun `Ridley area cells are correctly attributed`() {
        val d = data ?: return
        assertEquals(Area.RIDLEY, MetroidRomData.areaAt(14, 25))
        assertEquals(Area.RIDLEY, MetroidRomData.areaAt(25, 25))
        assertEquals(Area.RIDLEY, MetroidRomData.areaAt(30, 29))
    }

    @Test
    fun `Tourian area cells are correctly attributed`() {
        val d = data ?: return
        assertEquals(Area.TOURIAN, MetroidRomData.areaAt(1, 3))
        assertEquals(Area.TOURIAN, MetroidRomData.areaAt(3, 4))
        assertEquals(Area.TOURIAN, MetroidRomData.areaAt(10, 7))
    }

    @Test
    fun `room 21 is found in correct area region`() {
        val d = data ?: return
        // Room $21 exists in Brinstar at (22,3), Norfair at (14,17), Kraid at (10,27)
        val brinN = d.findRoomNeighbors(Area.BRINSTAR, 0x21)
        assertNotNull(brinN)
        assertEquals(Area.BRINSTAR, MetroidRomData.areaAt(brinN!!.mapX, brinN.mapY),
            "Brinstar room \$21 should be in Brinstar region")

        val norN = d.findRoomNeighbors(Area.NORFAIR, 0x21)
        assertNotNull(norN)
        assertEquals(Area.NORFAIR, MetroidRomData.areaAt(norN!!.mapX, norN.mapY),
            "Norfair room \$21 should be in Norfair region")
    }
}
