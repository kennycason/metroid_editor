package com.metroid.editor.rom

import com.metroid.editor.data.Area
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RoomEncoderTest {

    private var rom: NesRomParser? = null
    private var data: MetroidRomData? = null

    @BeforeAll
    fun setup() {
        val romFile = NesRomParserTest.findRomFile()
        if (romFile != null) {
            val rawData = romFile.readBytes()
            val romBytes = NesRomParser.ensureHeader(rawData)
            rom = NesRomParser(romBytes)
            data = MetroidRomData(rom!!)
        }
    }

    @Test
    fun `round trip all areas - encode then decode matches original grids`() {
        val r = rom ?: return
        val d = data ?: return
        val pd = NesPatternDecoder(r)
        val renderer = MapRenderer(d, pd)

        for (area in listOf(Area.BRINSTAR, Area.NORFAIR, Area.TOURIAN, Area.KRAID, Area.RIDLEY)) {
            val rooms = d.readAllRooms(area)
            val grids = rooms.map { renderer.buildMacroGrid(it) }

            val encoding = RoomEncoder.encodeArea(rooms, grids)

            println("\n=== ${area.displayName} ===")
            println("  Rooms: ${rooms.size}")
            println("  Unique structures: ${encoding.structures.size}")
            println("  Total encoded: ${encoding.totalBytes}")

            for (i in rooms.indices) {
                val original = grids[i]
                val decoded = RoomEncoder.decodeEncoded(encoding.rooms[i], encoding.structures)

                for (y in 0 until MapRenderer.ROOM_HEIGHT_MACROS) {
                    for (x in 0 until MapRenderer.ROOM_WIDTH_MACROS) {
                        val origMacro = original.get(x, y)
                        val decMacro = decoded.get(x, y)
                        assertEquals(origMacro, decMacro,
                            "${area.displayName} Room ${rooms[i].roomNumber}: " +
                                "macro mismatch at ($x,$y): orig=$origMacro, decoded=$decMacro")

                        if (origMacro >= 0) {
                            val origAttr = original.getAttr(x, y) and 0x03
                            val decAttr = decoded.getAttr(x, y) and 0x03
                            assertEquals(origAttr, decAttr,
                                "${area.displayName} Room ${rooms[i].roomNumber}: " +
                                    "attr mismatch at ($x,$y): orig=$origAttr, decoded=$decAttr")
                        }
                    }
                }
            }

            println("  All ${rooms.size} rooms round-trip correctly")
        }
    }

    @Test
    fun `bank layout analysis with correct struct count`() {
        val r = rom ?: return
        val d = data ?: return

        for (area in listOf(Area.BRINSTAR, Area.NORFAIR, Area.TOURIAN, Area.KRAID, Area.RIDLEY)) {
            val rooms = d.readAllRooms(area)
            val ptrs = d.readAreaPointers(area)
            val maxStructIdx = rooms.flatMap { rm -> rm.objects.map { it.structIndex } }.maxOrNull() ?: 0

            val layout = RoomEncoder.analyzeBankLayout(r, d, area)
            println("\n=== ${area.displayName} Bank Layout ===")
            println("  Struct count (from max used index): ${maxStructIdx + 1}")
            println("  Room data regions: ${layout.roomDataRegions.size}")
            println("  Struct data regions: ${layout.structDataRegions.size}")
            println("  Total reclaimable: ${layout.totalReclaimable} bytes")

            // Verify reclaimable ranges don't overlap with critical tables
            val bank = MetroidRomData.AREA_BANKS[area]!!
            val specItemsRom = r.bankAddressToRomOffset(bank, ptrs.specItemsTable)
            val macroDefsRom = r.bankAddressToRomOffset(bank, ptrs.macroDefs)
            val enemyFrame1Rom = r.bankAddressToRomOffset(bank, ptrs.enemyFramePtrTbl1)

            for (range in layout.reclaimableRanges) {
                assertFalse(specItemsRom in range,
                    "${area.displayName}: specItemsTable overlaps reclaimable range!")
                assertFalse(macroDefsRom in range,
                    "${area.displayName}: macroDefs overlaps reclaimable range!")
                assertFalse(enemyFrame1Rom in range,
                    "${area.displayName}: enemyFramePtrTbl1 overlaps reclaimable range!")
            }
            println("  No overlap with critical tables")
        }
    }

    @Test
    fun `greedy re-encoder space analysis for all areas`() {
        val r = rom ?: return
        val d = data ?: return
        val pd = NesPatternDecoder(r)
        val renderer = MapRenderer(d, pd)

        for (area in listOf(Area.BRINSTAR, Area.NORFAIR, Area.TOURIAN, Area.KRAID, Area.RIDLEY)) {
            val rooms = d.readAllRooms(area)
            val grids = rooms.map { renderer.buildMacroGrid(it) }

            val layout = RoomEncoder.analyzeBankLayout(r, d, area)
            val encoding = RoomEncoder.encodeArea(rooms, grids)

            println("\n=== ${area.displayName} ===")
            println("  Encoded: ${encoding.totalBytes} bytes")
            println("  Available: ${layout.totalReclaimable} bytes")
            println("  Fit: ${if (encoding.totalBytes <= layout.totalReclaimable) "YES" else "NO (+${encoding.totalBytes - layout.totalReclaimable})"}")
        }
    }

    @Test
    fun `enemy door suffix extraction preserves original data`() {
        val r = rom ?: return
        val d = data ?: return

        val room = d.readRoom(Area.BRINSTAR, 9) ?: return
        val suffix = RoomEncoder.extractEnemyDoorSuffix(room)

        println("Room 9 raw data size: ${room.rawData.size}")
        println("Enemy/door suffix size: ${suffix.size}")

        assertTrue(suffix.isNotEmpty(), "Suffix should not be empty")
        val firstByte = suffix[0].toInt() and 0xFF
        assertTrue(firstByte == 0xFD || firstByte == 0xFF,
            "Suffix should start with 0xFD or 0xFF, got ${"%02X".format(firstByte)}")
        assertEquals(0xFF, suffix.last().toInt() and 0xFF, "Suffix should end with 0xFF")
    }

    @Test
    fun `space budget calculation works`() {
        val r = rom ?: return
        val d = data ?: return
        val pd = NesPatternDecoder(r)
        val renderer = MapRenderer(d, pd)

        for (area in listOf(Area.BRINSTAR, Area.NORFAIR, Area.KRAID, Area.RIDLEY)) {
            val rooms = d.readAllRooms(area)
            val grids = rooms.map { renderer.buildMacroGrid(it) }

            val budget = RoomEncoder.calculateBudget(r, d, area, rooms, grids)
            println("\n=== ${area.displayName} Budget ===")
            println("  Tiles: ${budget.tilesUsed}")
            println("  Room data: ${budget.roomDataBytes} bytes")
            println("  Struct data: ${budget.structDataBytes} bytes")
            println("  Ptr tables: ${budget.ptrTableBytes} bytes")
            println("  Total: ${budget.totalUsed} / ${budget.totalAvailable} (${budget.percentUsed}%)")
            println("  Bank free: ${budget.freeSpaceInBank} bytes")

            assertTrue(budget.tilesUsed > 0)
            assertTrue(budget.totalAvailable > 0)
            assertTrue(budget.percentUsed <= 100, "${area.displayName}: usage should be <= 100%")
        }
    }
}
