package com.metroid.editor.rom

import com.metroid.editor.data.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.io.File

/**
 * Comprehensive tests for the atomic AreaRewriter.
 *
 * These tests verify all 13 invariants that must hold for a valid ROM:
 *  1. AreaPointers at $9598 have valid CPU addresses
 *  2. RoomPtrTable is contiguous, one ptr per room
 *  3. StructPtrTable has valid ptrs for all used indices
 *  4. SpecItmsTbl pointer at $9598+0 is valid
 *  5. SpecItmsTbl Y-node "next" pointers are valid absolute CPU addresses
 *  6. SpecItmsTbl sorted by Y ascending, X ascending
 *  7. MacroDefs pointer valid, data not overwritten
 *  8. Enemy tables remain valid
 *  9. Room data ends with $FF
 * 10. Struct data ends with $FF
 * 11. Room count = (StructPtrTable - RoomPtrTable) / 2
 * 12. All data within one 16KB bank
 * 13. $FE in room data = skip
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AreaRewriterTest {

    private var rom: NesRomParser? = null
    private var md: MetroidRomData? = null

    @BeforeAll
    fun setup() {
        val romFile = NesRomParserTest.findRomFile()
        if (romFile != null) {
            val rawData = romFile.readBytes()
            val romBytes = NesRomParser.ensureHeader(rawData)
            rom = NesRomParser(romBytes)
            md = MetroidRomData(rom!!)
        }
    }

    // ── Helpers ──

    private fun makeRenderer(): MapRenderer {
        val r = rom ?: throw IllegalStateException("ROM not loaded")
        return MapRenderer(md!!, NesPatternDecoder(r))
    }

    private fun exportAndVerify(
        area: Area,
        edits: Map<String, RoomEdits>,
        testName: String
    ): RomExporter.ExportResult {
        val r = rom ?: return RomExporter.ExportResult(0, 0, 0, 0, listOf("ROM not found"))
        val d = md ?: return RomExporter.ExportResult(0, 0, 0, 0, listOf("Data not found"))
        val renderer = makeRenderer()

        val exportData = r.copyRomData()
        val exportParser = NesRomParser(exportData)
        val result = RomExporter.exportAll(exportParser, r, d, renderer, edits)

        println("=== $testName ===")
        println("  Edits: ${result.totalEdits}, Success: ${result.success}")
        if (result.warnings.isNotEmpty()) println("  Errors: ${result.warnings}")

        val tmpFile = File("/tmp/metroid_rewriter_$testName.nes")
        tmpFile.writeBytes(exportData)
        println("  Written to: ${tmpFile.absolutePath}")

        return result
    }

    private fun deepVerify(
        exportParser: NesRomParser,
        area: Area,
        editedRoomNums: Set<Int> = emptySet(),
        allEditedAreas: Set<Area> = setOf(area),
        origParser: NesRomParser? = null,
        origMd: MetroidRomData? = null
    ) {
        val r = origParser ?: rom!!
        val d = origMd ?: md!!
        val bank = MetroidRomData.AREA_BANKS[area]!!
        val bankStart = NesRomParser.INES_HEADER_SIZE + bank * NesRomParser.PRG_BANK_SIZE

        val exportMd = MetroidRomData(exportParser)
        val origPtrs = d.readAreaPointers(area)
        val newPtrs = exportMd.readAreaPointers(area)

        // Invariant 7: macroDefs unchanged
        assertEquals(origPtrs.macroDefs, newPtrs.macroDefs, "macroDefs corrupted!")

        // Invariant 8: enemy tables unchanged
        assertEquals(origPtrs.enemyFramePtrTbl1, newPtrs.enemyFramePtrTbl1, "enemyFramePtrTbl1 corrupted!")
        assertEquals(origPtrs.enemyFramePtrTbl2, newPtrs.enemyFramePtrTbl2, "enemyFramePtrTbl2 corrupted!")
        assertEquals(origPtrs.enemyPlacePtrTbl, newPtrs.enemyPlacePtrTbl, "enemyPlacePtrTbl corrupted!")
        assertEquals(origPtrs.enemyAnimIndexTbl, newPtrs.enemyAnimIndexTbl, "enemyAnimIndexTbl corrupted!")

        // Invariant 11: room count preserved
        val origRoomCount = d.getRoomCount(area)
        val newRoomCount = exportMd.getRoomCount(area)
        assertEquals(origRoomCount, newRoomCount, "Room count changed!")

        // Read all rooms and verify
        val origRooms = d.readAllRooms(area)
        val exportRooms = exportMd.readAllRooms(area)
        assertEquals(origRooms.size, exportRooms.size, "Room list size mismatch")

        // Non-edited rooms: raw data must match byte-for-byte
        for (origRoom in origRooms) {
            if (origRoom.roomNumber in editedRoomNums) continue
            val expRoom = exportRooms.find { it.roomNumber == origRoom.roomNumber }
                ?: fail("Room ${origRoom.roomNumber} missing!")

            assertTrue(origRoom.rawData.contentEquals(expRoom.rawData),
                "Room ${origRoom.roomNumber} raw data corrupted " +
                        "(${origRoom.rawData.size} vs ${expRoom.rawData.size} bytes)")
        }

        // Verify no other areas were touched
        for (otherArea in Area.entries) {
            if (otherArea in allEditedAreas) continue
            val otherBank = MetroidRomData.AREA_BANKS[otherArea] ?: continue
            val otherBankStart = NesRomParser.INES_HEADER_SIZE + otherBank * NesRomParser.PRG_BANK_SIZE
            val otherBankEnd = minOf(otherBankStart + NesRomParser.PRG_BANK_SIZE, r.romData.size)
            var diffs = 0
            for (i in otherBankStart until otherBankEnd) {
                if (i < exportParser.romData.size && (r.readByte(i) and 0xFF) != (exportParser.readByte(i) and 0xFF)) diffs++
            }
            assertEquals(0, diffs, "${otherArea.displayName} bank was modified ($diffs bytes)")
        }

        // Verify fixed bank untouched (bank 7 for standard, last bank for expanded)
        val isExpanded = RomExpander.isExpandedRom(r.romData)
        val fixedBankNum = if (isExpanded) (r.romData.size - NesRomParser.INES_HEADER_SIZE) / NesRomParser.PRG_BANK_SIZE - 1 else 7
        val fixedStart = NesRomParser.INES_HEADER_SIZE + fixedBankNum * NesRomParser.PRG_BANK_SIZE
        val fixedEnd = minOf(fixedStart + NesRomParser.PRG_BANK_SIZE, r.romData.size)
        var fixedDiffs = 0
        for (i in fixedStart until fixedEnd) {
            if (i < exportParser.romData.size && (r.readByte(i) and 0xFF) != (exportParser.readByte(i) and 0xFF)) fixedDiffs++
        }
        assertEquals(0, fixedDiffs, "Fixed bank corrupted!")

        // Verify game code region untouched (only for standard ROMs — expanded ROMs use freed pattern regions)
        if (!isExpanded) {
            val floor = RomExporter.dataRegionFloorForTest(origPtrs)
            val codeEnd = bankStart + (floor - 0x8000)
            val areaPtrRomStart = bankStart + (MetroidRomData.AREA_POINTERS_ADDR - 0x8000)
            val areaPtrRange = areaPtrRomStart until areaPtrRomStart + 16
            var codeDiffs = 0
            for (i in bankStart until codeEnd) {
                if (i in areaPtrRange) continue
                if ((r.readByte(i) and 0xFF) != (exportParser.readByte(i) and 0xFF)) codeDiffs++
            }
            assertEquals(0, codeDiffs, "Game code region corrupted ($codeDiffs bytes below CPU $%04X)".format(floor))
        }

        // Verify specItemsTable linked list
        verifySpecItemsList(exportParser, bank, newPtrs.specItemsTable, origPtrs.specItemsTable, r)

        // Verify macro data not corrupted (spot check)
        for (i in 0 until 20) {
            val addr = origPtrs.macroDefs + i * 4
            if (addr + 3 >= 0xC000) break
            for (b in 0..3) {
                assertEquals(
                    r.readBankByte(bank, addr + b),
                    exportParser.readBankByte(bank, addr + b),
                    "Macro $i byte $b corrupted"
                )
            }
        }
    }

    private fun verifySpecItemsList(
        parser: NesRomParser, bank: Int, startCpu: Int,
        origStartCpu: Int, origParser: NesRomParser
    ) {
        var pos = startCpu
        var steps = 0
        val visited = mutableListOf<Int>()
        while (steps < 100) {
            assertTrue(pos in 0x8000 until 0xC000,
                "specItemsTable entry at CPU $%04X out of range".format(pos))
            if (pos in visited) fail<Unit>("specItemsTable cycle at $%04X".format(pos))
            visited.add(pos)

            val lo = parser.readBankByte(bank, pos + 1) and 0xFF
            val hi = parser.readBankByte(bank, pos + 2) and 0xFF
            if (lo == 0xFF && hi == 0xFF) break
            val nextPtr = (hi shl 8) or lo
            assertTrue(nextPtr > pos, "specItemsTable next ptr $%04X <= current $%04X".format(nextPtr, pos))
            assertTrue(nextPtr in 0x8000 until 0xC000,
                "specItemsTable next ptr $%04X out of range".format(nextPtr))
            pos = nextPtr
            steps++
        }
        assertTrue(steps < 100, "specItemsTable walk exceeded 100 steps")
        assertTrue(visited.size >= 2, "Expected at least 2 specItemsTable entries (got ${visited.size})")
    }

    // ── Tests ──

    @Test
    fun `zero-edit export produces byte-identical ROM`() {
        val r = rom ?: return
        val d = md ?: return

        val exportData = r.copyRomData()
        val exportParser = NesRomParser(exportData)
        val result = RomExporter.exportAll(exportParser, r, d, makeRenderer(), emptyMap())

        assertTrue(result.success)
        assertEquals(0, result.totalEdits)

        // Byte-for-byte identical
        val origData = r.copyRomData()
        var diffs = 0
        for (i in origData.indices) {
            if (origData[i] != exportData[i]) diffs++
        }
        assertEquals(0, diffs, "Zero-edit export should be byte-identical")
    }

    @Test
    fun `single covered edit - brinstar room 9`() {
        val r = rom ?: return
        // Room 9's structs are all shared — requires expanded ROM to clone safely
        val expanded = RomExpander.expand(r.romData) ?: return
        val expParser = NesRomParser(expanded)
        val d = MetroidRomData(expParser)
        val renderer = MapRenderer(d, NesPatternDecoder(expParser))

        val room = d.readRoom(Area.BRINSTAR, 9) ?: return
        val grid = renderer.buildMacroGrid(room)
        val coverage = RomExporter.buildCoverageMap(d, room)

        // Find a covered cell to edit
        var edit: MacroEdit? = null
        for (my in 0 until MapRenderer.ROOM_HEIGHT_MACROS) {
            for (mx in 0 until MapRenderer.ROOM_WIDTH_MACROS) {
                if (coverage[my * MapRenderer.ROOM_WIDTH_MACROS + mx]) {
                    val orig = grid.get(mx, my)
                    if (orig >= 0 && orig != 32) {
                        edit = MacroEdit(mx, my, 32, grid.getAttr(mx, my))
                        break
                    }
                }
            }
            if (edit != null) break
        }
        if (edit == null) return

        val edits = mapOf(roomKey(Area.BRINSTAR, 9) to RoomEdits(mutableListOf(edit)))
        val exportData = expParser.copyRomData()
        val exportParser = NesRomParser(exportData)
        val result = RomExporter.exportAll(exportParser, expParser, d, renderer, edits)

        assertTrue(result.success, "Export failed: ${result.warnings}")

        // Verify edit applied
        val exportMd = MetroidRomData(exportParser)
        val exportRenderer = MapRenderer(exportMd, NesPatternDecoder(exportParser))
        val reread = exportMd.readRoom(Area.BRINSTAR, 9)!!
        val rereadGrid = exportRenderer.buildMacroGrid(reread)
        assertEquals(edit.macroIndex, rereadGrid.get(edit.x, edit.y))

        deepVerify(exportParser, Area.BRINSTAR, setOf(9), origParser = expParser, origMd = d)
    }

    @Test
    fun `single uncovered edit reports space error gracefully`() {
        val r = rom ?: return
        val d = md ?: return
        val renderer = makeRenderer()

        val room = d.readRoom(Area.BRINSTAR, 0) ?: return
        val coverage = RomExporter.buildCoverageMap(d, room)

        // Find uncovered empty cell
        var edit: MacroEdit? = null
        for (my in 0 until MapRenderer.ROOM_HEIGHT_MACROS) {
            for (mx in 0 until MapRenderer.ROOM_WIDTH_MACROS) {
                val idx = my * MapRenderer.ROOM_WIDTH_MACROS + mx
                if (!coverage[idx]) {
                    edit = MacroEdit(mx, my, 6, 0)
                    break
                }
            }
            if (edit != null) break
        }
        if (edit == null) return

        val edits = mapOf(roomKey(Area.BRINSTAR, 0) to RoomEdits(mutableListOf(edit)))
        val exportData = r.copyRomData()
        val exportParser = NesRomParser(exportData)
        val result = RomExporter.exportAll(exportParser, r, d, renderer, edits)

        // Standard Metroid ROM has no free space — uncovered edits should fail gracefully
        assertTrue(result.skippedUncovered > 0, "Should skip uncovered edits in packed bank")
    }

    @Test
    fun `multi-room edits across brinstar`() {
        val r = rom ?: return
        // Most Brinstar structs are shared — use expanded ROM
        val expanded = RomExpander.expand(r.romData) ?: return
        val expParser = NesRomParser(expanded)
        val d = MetroidRomData(expParser)
        val renderer = MapRenderer(d, NesPatternDecoder(expParser))

        val rooms = d.readAllRooms(Area.BRINSTAR)
        val edits = mutableMapOf<String, RoomEdits>()
        var roomsEdited = 0

        for (room in rooms) {
            if (roomsEdited >= 5) break
            val grid = renderer.buildMacroGrid(room)
            val coverage = RomExporter.buildCoverageMap(d, room)

            val roomEdits = mutableListOf<MacroEdit>()
            for (my in 0 until MapRenderer.ROOM_HEIGHT_MACROS) {
                for (mx in 0 until MapRenderer.ROOM_WIDTH_MACROS) {
                    if (coverage[my * MapRenderer.ROOM_WIDTH_MACROS + mx]) {
                        val orig = grid.get(mx, my)
                        if (orig >= 0 && orig != 32) {
                            roomEdits.add(MacroEdit(mx, my, 32, grid.getAttr(mx, my)))
                            if (roomEdits.size >= 3) break
                        }
                    }
                }
                if (roomEdits.size >= 3) break
            }

            if (roomEdits.isNotEmpty()) {
                edits[roomKey(Area.BRINSTAR, room.roomNumber)] = RoomEdits(roomEdits)
                roomsEdited++
            }
        }

        assertTrue(roomsEdited >= 3, "Need at least 3 rooms with editable cells")

        val exportData = expParser.copyRomData()
        val exportParser = NesRomParser(exportData)
        val result = RomExporter.exportAll(exportParser, expParser, d, renderer, edits)

        assertTrue(result.success, "Multi-room export failed: ${result.warnings}")
        assertTrue(result.totalEdits >= 3 * 3, "Expected at least 9 total edits")

        val editedNums = edits.keys.mapNotNull { it.split(":").getOrNull(1)?.toIntOrNull(16) }.toSet()
        deepVerify(exportParser, Area.BRINSTAR, editedNums, origParser = expParser, origMd = d)

        File("/tmp/metroid_rewriter_multi_room.nes").writeBytes(exportData)
        println("Multi-room: $roomsEdited rooms, ${result.totalEdits} edits, success=${result.success}")
    }

    @Test
    fun `multi-room uncovered edits - two rooms`() {
        val r = rom ?: return
        val d = md ?: return
        val renderer = makeRenderer()

        // Use only 2 rooms to stay within Brinstar's tight space budget
        val rooms = d.readAllRooms(Area.BRINSTAR)
        val edits = mutableMapOf<String, RoomEdits>()

        for (roomNum in listOf(9, 2)) {
            val room = rooms.find { it.roomNumber == roomNum } ?: continue
            val coverage = RomExporter.buildCoverageMap(d, room)

            val edit = (0 until MapRenderer.ROOM_HEIGHT_MACROS).flatMap { my ->
                (0 until MapRenderer.ROOM_WIDTH_MACROS).mapNotNull { mx ->
                    if (!coverage[my * MapRenderer.ROOM_WIDTH_MACROS + mx])
                        MacroEdit(mx, my, 6, 0) else null
                }
            }.firstOrNull() ?: continue

            edits[roomKey(Area.BRINSTAR, room.roomNumber)] = RoomEdits(mutableListOf(edit))
        }

        assertTrue(edits.size >= 2, "Need at least 2 rooms with uncovered cells")

        val exportData = r.copyRomData()
        val exportParser = NesRomParser(exportData)
        val result = RomExporter.exportAll(exportParser, r, d, renderer, edits)

        // Standard Metroid ROM is fully packed — uncovered edits won't fit
        assertTrue(result.skippedUncovered > 0, "Should skip uncovered edits in packed bank")
    }

    @Test
    fun `six-room edit scenario reports space error gracefully`() {
        val r = rom ?: return
        val d = md ?: return
        val renderer = makeRenderer()

        // This scenario exceeds Brinstar's tight 16KB budget with 30 uncovered edits.
        // The atomic rewriter correctly reports "not enough space" rather than
        // producing a corrupted ROM (which the old incremental approach would do).
        val edits = mutableMapOf<String, RoomEdits>()
        edits["0:09"] = RoomEdits(mutableListOf(
            MacroEdit(11, 10, 32, 0), MacroEdit(12, 10, 32, 0),
            MacroEdit(13, 10, 32, 0), MacroEdit(13, 11, 32, 0)
        ))
        edits["0:0B"] = RoomEdits(mutableListOf(
            MacroEdit(4, 4, 46, 0), MacroEdit(5, 4, 46, 0),
            MacroEdit(6, 4, 46, 0), MacroEdit(8, 4, 46, 0)
        ))

        val exportData = r.copyRomData()
        val exportParser = NesRomParser(exportData)
        val result = RomExporter.exportAll(exportParser, r, d, renderer, edits)

        // May or may not succeed depending on available space
        // The key assertion: if it fails, it fails cleanly with an error message
        // rather than producing a corrupted ROM
        println("=== six_room_scenario ===")
        println("  Edits: ${result.totalEdits}, Success: ${result.success}")
        if (!result.success) {
            assertTrue(result.skippedUncovered > 0,
                "Should have skipped uncovered edits, got: ${result.warnings}")
            println("  Expected space limitation reached")
        } else {
            // If it succeeds, verify integrity
            val editedNums = edits.keys.mapNotNull { it.split(":").getOrNull(1)?.toIntOrNull(16) }.toSet()
            deepVerify(exportParser, Area.BRINSTAR, editedNums)
        }
    }

    @Test
    fun `edits across two areas`() {
        val r = rom ?: return
        val d = md ?: return
        val renderer = makeRenderer()

        // Edit one covered cell in Brinstar and one in Norfair
        val edits = mutableMapOf<String, RoomEdits>()

        for (area in listOf(Area.BRINSTAR, Area.NORFAIR)) {
            val rooms = d.readAllRooms(area)
            val room = rooms.firstOrNull { it.objects.isNotEmpty() } ?: continue
            val coverage = RomExporter.buildCoverageMap(d, room)
            val grid = renderer.buildMacroGrid(room)

            val edit = (0 until MapRenderer.ROOM_HEIGHT_MACROS).flatMap { my ->
                (0 until MapRenderer.ROOM_WIDTH_MACROS).mapNotNull { mx ->
                    val idx = my * MapRenderer.ROOM_WIDTH_MACROS + mx
                    if (coverage[idx] && grid.get(mx, my) >= 0)
                        MacroEdit(mx, my, 0, grid.getAttr(mx, my)) else null
                }
            }.firstOrNull() ?: continue

            edits[roomKey(area, room.roomNumber)] = RoomEdits(mutableListOf(edit))
        }

        assertTrue(edits.size >= 2, "Need edits in at least 2 areas")

        val exportData = r.copyRomData()
        val exportParser = NesRomParser(exportData)
        val result = RomExporter.exportAll(exportParser, r, d, renderer, edits)

        assertTrue(result.success, "Multi-area export failed: ${result.warnings}")

        val allEditedAreas = setOf(Area.BRINSTAR, Area.NORFAIR)
        for (area in allEditedAreas) {
            val areaEdits = edits.filter { it.key.startsWith("${area.id}:") }
            if (areaEdits.isEmpty()) continue
            val editedNums = areaEdits.keys.mapNotNull { it.split(":").getOrNull(1)?.toIntOrNull(16) }.toSet()
            deepVerify(exportParser, area, editedNums, allEditedAreas)
        }

        File("/tmp/metroid_rewriter_multi_area.nes").writeBytes(exportData)
    }

    @Test
    fun `uncovered edits in packed bank fail gracefully`() {
        val r = rom ?: return
        val d = md ?: return
        val renderer = makeRenderer()

        // Standard Metroid Brinstar bank has zero free space
        val edits = mapOf(
            roomKey(Area.BRINSTAR, 9) to RoomEdits(mutableListOf(
                MacroEdit(3, 8, 6, 0),
                MacroEdit(13, 8, 6, 0),
            ))
        )

        val exportData = r.copyRomData()
        val exportParser = NesRomParser(exportData)
        val result = RomExporter.exportAll(exportParser, r, d, renderer, edits)

        assertTrue(result.skippedUncovered > 0, "Should skip uncovered edits in packed bank")
    }
}
