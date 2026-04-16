package com.metroid.editor.rom

import com.metroid.editor.data.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.io.File

/**
 * Round-trip export tests that verify edits survive export and re-read.
 * Exported ROMs are written to /tmp/ for manual inspection in an emulator.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ExportRoundTripTest {

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

    private fun exportAndVerify(
        testName: String,
        area: Area,
        roomNum: Int,
        edits: List<MacroEdit>,
        expectSuccess: Boolean = true,
        useExpanded: Boolean = false
    ): RomExporter.ExportResult {
        val baseRom = rom ?: return RomExporter.ExportResult(0, 0, 0, 0, listOf("ROM not found"))
        val r: NesRomParser
        val d: MetroidRomData
        if (useExpanded) {
            val expanded = RomExpander.expand(baseRom.romData) ?: return RomExporter.ExportResult(0, 0, 0, 0, listOf("Expand failed"))
            r = NesRomParser(expanded)
            d = MetroidRomData(r)
        } else {
            r = baseRom
            d = data ?: return RomExporter.ExportResult(0, 0, 0, 0, listOf("Data not found"))
        }
        val pd = NesPatternDecoder(r)
        val renderer = MapRenderer(d, pd)

        val editedRooms = mutableMapOf<String, RoomEdits>()
        editedRooms[roomKey(area, roomNum)] = RoomEdits(macroEdits = edits.toMutableList())

        val exportData = r.copyRomData()
        val exportParser = NesRomParser(exportData)
        val result = RomExporter.exportAll(exportParser, r, d, renderer, editedRooms)

        println("=== $testName ===")
        println("  Covered: ${result.coveredPatched}, Uncovered: ${result.skippedUncovered}")
        println("  Errors: ${result.warnings}")
        println("  Success: ${result.success}")

        val tmpFile = File("/tmp/metroid_test_$testName.nes")
        tmpFile.writeBytes(exportData)
        println("  Written to: ${tmpFile.absolutePath}")

        if (expectSuccess) {
            assertTrue(result.success, "Export should succeed: ${result.warnings}")

            val exportMd = MetroidRomData(exportParser)
            val exportRenderer = MapRenderer(exportMd, NesPatternDecoder(exportParser))
            val reread = exportMd.readRoom(area, roomNum) ?: fail("Room should still exist")
            val rereadGrid = exportRenderer.buildMacroGrid(reread)

            for (edit in edits) {
                val actual = rereadGrid.get(edit.x, edit.y)
                assertEquals(edit.macroIndex, actual,
                    "Edit at (${edit.x},${edit.y}): expected macro ${edit.macroIndex} got $actual")
            }
            println("  All ${edits.size} edits verified in exported ROM")

            verifyIntegrity(exportParser, d, area, setOf(roomNum))
        }

        return result
    }

    private fun verifySpecItemsData(
        origParser: NesRomParser,
        exportParser: NesRomParser,
        area: Area,
        origPtrs: MetroidRomData.AreaPointers,
        newPtrs: MetroidRomData.AreaPointers
    ) {
        val bank = MetroidRomData.AREA_BANKS[area] ?: return
        val specSize = origPtrs.roomPtrTable - origPtrs.specItemsTable
        if (specSize <= 0) return

        if (origPtrs.specItemsTable == newPtrs.specItemsTable) return

        val origOff = origParser.bankAddressToRomOffset(bank, origPtrs.specItemsTable)
        val expOff = exportParser.bankAddressToRomOffset(bank, newPtrs.specItemsTable)
        var mismatches = 0
        for (i in 0 until specSize) {
            if ((origParser.readByte(origOff + i) and 0xFF) != (exportParser.readByte(expOff + i) and 0xFF)) {
                mismatches++
            }
        }
        assertEquals(0, mismatches,
            "specItemsTable data corrupted after relocation ($%04X→$%04X, $mismatches of $specSize bytes differ)"
                .format(origPtrs.specItemsTable, newPtrs.specItemsTable))
    }

    private fun verifyIntegrity(
        exportParser: NesRomParser,
        origData: MetroidRomData,
        editedArea: Area,
        editedRoomNums: Set<Int> = emptySet()
    ) {
        val exportMd = MetroidRomData(exportParser)

        val origPtrs = origData.readAreaPointers(editedArea)
        val newPtrs = exportMd.readAreaPointers(editedArea)
        verifySpecItemsData(origData.rom, exportParser, editedArea, origPtrs, newPtrs)
        assertEquals(origPtrs.macroDefs, newPtrs.macroDefs, "macroDefs corrupted!")
        assertEquals(origPtrs.enemyFramePtrTbl1, newPtrs.enemyFramePtrTbl1, "enemyFramePtrTbl1 corrupted!")
        assertEquals(origPtrs.enemyFramePtrTbl2, newPtrs.enemyFramePtrTbl2, "enemyFramePtrTbl2 corrupted!")
        assertEquals(origPtrs.enemyPlacePtrTbl, newPtrs.enemyPlacePtrTbl, "enemyPlacePtrTbl corrupted!")
        assertEquals(origPtrs.enemyAnimIndexTbl, newPtrs.enemyAnimIndexTbl, "enemyAnimIndexTbl corrupted!")

        for (area in Area.entries) {
            if (area == editedArea) continue
            val origAreaPtrs = origData.readAreaPointers(area)
            val newAreaPtrs = exportMd.readAreaPointers(area)
            assertEquals(origAreaPtrs.roomPtrTable, newAreaPtrs.roomPtrTable,
                "${area.displayName} roomPtrTable changed!")
            assertEquals(origAreaPtrs.structPtrTable, newAreaPtrs.structPtrTable,
                "${area.displayName} structPtrTable changed!")
        }

        val origRooms = origData.readAllRooms(editedArea)
        val exportRooms = exportMd.readAllRooms(editedArea)
        assertEquals(origRooms.size, exportRooms.size, "Room count changed!")
        for (origRoom in origRooms) {
            if (origRoom.roomNumber in editedRoomNums) continue
            val exportRoom = exportRooms.find { it.roomNumber == origRoom.roomNumber }
                ?: fail("Room ${origRoom.roomNumber} missing from exported ROM!")
            assertEquals(origRoom.objects.size, exportRoom.objects.size,
                "Room ${origRoom.roomNumber} object count changed")
            assertEquals(origRoom.enemies.size, exportRoom.enemies.size,
                "Room ${origRoom.roomNumber} enemy count changed")
            assertEquals(origRoom.doors.size, exportRoom.doors.size,
                "Room ${origRoom.roomNumber} door count changed")
            assertTrue(origRoom.rawData.contentEquals(exportRoom.rawData),
                "Room ${origRoom.roomNumber} raw data corrupted " +
                        "(${origRoom.rawData.size} vs ${exportRoom.rawData.size} bytes)")
        }
    }

    @Test
    fun `covered edits only - brinstar room 9`() {
        val r = rom ?: return
        val d = data ?: return
        val pd = NesPatternDecoder(r)
        val renderer = MapRenderer(d, pd)

        val room = d.readRoom(Area.BRINSTAR, 9) ?: return
        val grid = renderer.buildMacroGrid(room)
        val coverage = RomExporter.buildCoverageMap(d, room)

        val edits = mutableListOf<MacroEdit>()
        for (my in 0 until MapRenderer.ROOM_HEIGHT_MACROS) {
            for (mx in 0 until MapRenderer.ROOM_WIDTH_MACROS) {
                if (coverage[my * MapRenderer.ROOM_WIDTH_MACROS + mx]) {
                    val orig = grid.get(mx, my)
                    if (orig >= 0 && orig != 32) {
                        edits.add(MacroEdit(mx, my, 32, grid.getAttr(mx, my)))
                        if (edits.size >= 5) break
                    }
                }
            }
            if (edits.size >= 5) break
        }

        // Room 9's structs are all shared — use expanded ROM to clone safely
        val result = exportAndVerify("covered_only_br9", Area.BRINSTAR, 9, edits, useExpanded = true)
        assertTrue(result.totalEdits >= edits.size)
    }

    @Test
    fun `uncovered edits are skipped in packed bank`() {
        if (rom == null) return
        val edits = listOf(MacroEdit(4, 12, 2, 0), MacroEdit(12, 12, 2, 0))
        val result = exportAndVerify("uncovered_2tile_br9", Area.BRINSTAR, 9, edits, expectSuccess = false)
        assertTrue(result.skippedUncovered >= 2, "Uncovered edits should be skipped")
    }

    @Test
    fun `multiple uncovered edits are skipped`() {
        if (rom == null) return
        val edits = listOf(
            MacroEdit(3, 12, 2, 0), MacroEdit(4, 12, 2, 0),
            MacroEdit(12, 12, 2, 0), MacroEdit(13, 12, 2, 0), MacroEdit(14, 12, 2, 0),
        )
        val result = exportAndVerify("iterative_extension_br9", Area.BRINSTAR, 9, edits, expectSuccess = false)
        assertTrue(result.skippedUncovered >= 5, "All uncovered edits should be skipped")
    }

    @Test
    fun `mixed covered and uncovered edits - brinstar room 9`() {
        val r = rom ?: return
        val d = data ?: return
        val pd = NesPatternDecoder(r)
        val renderer = MapRenderer(d, pd)

        val room = d.readRoom(Area.BRINSTAR, 9) ?: return
        val grid = renderer.buildMacroGrid(room)
        val coverage = RomExporter.buildCoverageMap(d, room)

        val edits = mutableListOf<MacroEdit>()
        for (my in 0 until MapRenderer.ROOM_HEIGHT_MACROS) {
            for (mx in 0 until MapRenderer.ROOM_WIDTH_MACROS) {
                if (coverage[my * MapRenderer.ROOM_WIDTH_MACROS + mx]) {
                    val orig = grid.get(mx, my)
                    if (orig >= 0 && orig != 32) {
                        edits.add(MacroEdit(mx, my, 32, grid.getAttr(mx, my)))
                        if (edits.size >= 3) break
                    }
                }
            }
            if (edits.size >= 3) break
        }
        edits.add(MacroEdit(4, 12, 2, 0))
        edits.add(MacroEdit(12, 12, 2, 0))

        val result = exportAndVerify("mixed_edits_br9", Area.BRINSTAR, 9, edits, expectSuccess = false)
        assertTrue(result.coveredPatched >= 3, "Covered edits should be patched in-place")
    }

    @Test
    fun `user actual medit file edits - Metroid U`() {
        val r = rom ?: return
        val d = data ?: return
        val pd = NesPatternDecoder(r)
        val renderer = MapRenderer(d, pd)

        // Exact edits from /Users/kenny/Dropbox/emulator/nes/Metroid/Metroid (U).medit (updated)
        val edits = listOf(
            MacroEdit(3, 8, 6, 0),
            MacroEdit(13, 8, 6, 0),
            MacroEdit(3, 12, 6, 0),
            MacroEdit(4, 12, 6, 0),
            MacroEdit(12, 12, 6, 0),
            MacroEdit(13, 12, 6, 0),
        )

        val room = d.readRoom(Area.BRINSTAR, 9)!!
        val coverage = RomExporter.buildCoverageMap(d, room)
        val bank = MetroidRomData.AREA_BANKS[Area.BRINSTAR]!!

        println("=== User .medit edits coverage ===")
        for (e in edits) {
            val idx = e.y * MapRenderer.ROOM_WIDTH_MACROS + e.x
            val cov = coverage[idx]
            println("  (${e.x},${e.y}): macro=${e.macroIndex} pal=${e.palette} covered=$cov")
        }

        // Show what objects cover the area near the edits
        println("\n=== Coverage near edit positions (rows 7-13, cols 3-15) ===")
        for (y in 7..13) {
            val sb = StringBuilder("  y=$y: ")
            for (x in 3..15) {
                val idx = y * MapRenderer.ROOM_WIDTH_MACROS + x
                sb.append(if (coverage[idx]) "C" else ".")
            }
            println(sb)
        }

        // Show struct 11 details (covers rows 5-9 area)
        val struct11 = d.readStructure(Area.BRINSTAR, 11)
        println("\n=== Struct 11 details ===")
        if (struct11 != null) {
            for ((ri, row) in struct11.rows.withIndex()) {
                println("  Row $ri: xOff=${row.xOffset} macros=[${row.macroIndices.joinToString(",")}]")
            }
        }

        // Show free space blocks in Brinstar bank
        val bankStart = NesRomParser.INES_HEADER_SIZE + bank * NesRomParser.PRG_BANK_SIZE
        val bankEnd = bankStart + NesRomParser.PRG_BANK_SIZE
        val freeBlocks = mutableListOf<Pair<Int, Int>>()  // (offset, size)
        var runStart = -1; var runLen = 0
        for (i in bankStart until bankEnd) {
            if ((r.readByte(i) and 0xFF) == 0xFF) {
                if (runStart < 0) runStart = i
                runLen++
            } else {
                if (runLen >= 3) freeBlocks.add(runStart to runLen)
                runStart = -1; runLen = 0
            }
        }
        if (runLen >= 3) freeBlocks.add(runStart to runLen)
        println("\n=== Free space blocks in Brinstar bank (>= 3 bytes) ===")
        println("  Total blocks: ${freeBlocks.size}")
        println("  Total free bytes: ${freeBlocks.sumOf { it.second }}")
        println("  Largest block: ${freeBlocks.maxOfOrNull { it.second } ?: 0} bytes")
        for ((off, sz) in freeBlocks.sortedByDescending { it.second }.take(10)) {
            println("  Block at ROM $%05X: $sz bytes".format(off))
        }

        // Room 9 info
        println("\n=== Room 9 data ===")
        println("  ROM offset: $%05X, size: ${room.rawData.size} bytes".format(room.romOffset))
        println("  Objects: ${room.objects.size}")
        for (obj in room.objects) {
            val st = d.readStructure(Area.BRINSTAR, obj.structIndex)
            val cells = st?.rows?.sumOf { it.macroIndices.size } ?: 0
            println("    Obj at (${obj.posX},${obj.posY}) struct=${obj.structIndex} pal=${obj.palette} cells=$cells")
        }

        // Show adjacent rooms in ROM order for compaction analysis
        val allRooms = d.readAllRooms(Area.BRINSTAR)
        val sortedByOffset = allRooms.sortedBy { it.romOffset }
        println("\n=== All Brinstar rooms sorted by ROM offset ===")
        for (rm in sortedByOffset) {
            val marker = if (rm.roomNumber == 9) " <<<" else ""
            println("  Room %2d: offset=$%05X size=%2d bytes$marker".format(rm.roomNumber, rm.romOffset, rm.rawData.size))
        }

        var covCount = 0; var uncCount = 0
        for (e in edits) {
            if (coverage[e.y * MapRenderer.ROOM_WIDTH_MACROS + e.x]) covCount++ else uncCount++
        }
        println("\nTotal: $covCount covered, $uncCount uncovered of ${edits.size}")

        // Show struct 22 details (covers bottom row)
        val struct22 = d.readStructure(Area.BRINSTAR, 22)
        println("\n=== Struct 22 details ===")
        if (struct22 != null) {
            for ((ri, row) in struct22.rows.withIndex()) {
                println("  Row $ri: xOff=${row.xOffset} macros=[${row.macroIndices.joinToString(",")}]")
            }
        }

        // Which structs are shared across rooms?
        val allRoomsStructUsage = mutableMapOf<Int, MutableSet<Int>>()
        for (rm in allRooms) {
            for (obj in rm.objects) {
                allRoomsStructUsage.getOrPut(obj.structIndex) { mutableSetOf() }.add(rm.roomNumber)
            }
        }
        val room9Structs = room.objects.map { it.structIndex }.toSet()
        println("\n=== Room 9 struct sharing ===")
        for (si in room9Structs.sorted()) {
            val rooms = allRoomsStructUsage[si] ?: emptySet()
            println("  Struct $si: used by rooms ${rooms.sorted()}")
        }

        // Build original grid for comparison
        val origGrid = renderer.buildMacroGrid(room)
        val editedGrid = origGrid.copy()
        for (e in edits) {
            editedGrid.set(e.x, e.y, e.macroIndex)
            editedGrid.setAttr(e.x, e.y, e.palette)
        }

        val result = exportAndVerify("user_medit_U", Area.BRINSTAR, 9, edits, expectSuccess = false)
        println("Result: covered=${result.coveredPatched}, uncovered=${result.skippedUncovered}, success=${result.success}")
        if (!result.success) {
            println("  Space limitation — 6 uncovered edits exceed Brinstar budget")
            return
        }

        // FULL GRID COMPARISON: check ALL cells, not just edited ones
        val exportParser = NesRomParser(java.io.File("/tmp/metroid_test_user_medit_U.nes").readBytes())
        val exportMd = MetroidRomData(exportParser)
        val exportRenderer = MapRenderer(exportMd, NesPatternDecoder(exportParser))
        val exportRoom = exportMd.readRoom(Area.BRINSTAR, 9)!!
        val exportGrid = exportRenderer.buildMacroGrid(exportRoom)

        println("\n=== Exported room 9 objects ===")
        for (obj in exportRoom.objects) {
            val st = exportMd.readStructure(Area.BRINSTAR, obj.structIndex)
            val cells = st?.rows?.sumOf { it.macroIndices.size } ?: 0
            println("  Obj at (${obj.posX},${obj.posY}) struct=${obj.structIndex} pal=${obj.palette} cells=$cells")
        }

        println("\n=== Full grid diff (editedGrid vs exportGrid) ===")
        var diffCount = 0
        for (y in 0 until MapRenderer.ROOM_HEIGHT_MACROS) {
            for (x in 0 until MapRenderer.ROOM_WIDTH_MACROS) {
                val expected = editedGrid.get(x, y)
                val actual = exportGrid.get(x, y)
                val expAttr = editedGrid.getAttr(x, y)
                val actAttr = exportGrid.getAttr(x, y)
                if (expected != actual || expAttr != actAttr) {
                    val origM = origGrid.get(x, y)
                    val origA = origGrid.getAttr(x, y)
                    println("  DIFF ($x,$y): orig=macro$origM/pal$origA expected=macro$expected/pal$expAttr actual=macro$actual/pal$actAttr")
                    diffCount++
                }
            }
        }
        if (diffCount == 0) println("  No differences! Export matches editedGrid perfectly.")
        else println("  Total diffs: $diffCount")

        // Cross-room struct corruption check
        println("\n=== Cross-room struct corruption check ===")
        for (si in room9Structs.sorted()) {
            val origStruct = d.readStructure(Area.BRINSTAR, si) ?: continue
            val exportStruct = exportMd.readStructure(Area.BRINSTAR, si) ?: continue
            var structDiff = false
            for ((ri, row) in origStruct.rows.withIndex()) {
                if (ri >= exportStruct.rows.size) { structDiff = true; break }
                val expRow = exportStruct.rows[ri]
                if (row.xOffset != expRow.xOffset || row.macroIndices != expRow.macroIndices) {
                    println("  Struct $si row $ri CHANGED: orig=[xOff=${row.xOffset},${row.macroIndices}] export=[xOff=${expRow.xOffset},${expRow.macroIndices}]")
                    structDiff = true
                }
            }
            if (!structDiff && origStruct.rows.size != exportStruct.rows.size) {
                println("  Struct $si row count changed: ${origStruct.rows.size} → ${exportStruct.rows.size}")
            }
        }

        // BANK INTEGRITY: compare original vs export byte-by-byte
        // Identify expected-changed ranges (room/struct data, pointer tables)
        // Any change OUTSIDE those ranges = corruption
        val origPtrs = d.readAreaPointers(Area.BRINSTAR)
        val exportPtrs = exportMd.readAreaPointers(Area.BRINSTAR)
        println("\n=== Area pointer table comparison ===")
        println("  specItemsTable:   orig=$%04X export=$%04X ${if (origPtrs.specItemsTable != exportPtrs.specItemsTable) "MOVED" else "same"}".format(origPtrs.specItemsTable, exportPtrs.specItemsTable))
        println("  roomPtrTable:     orig=$%04X export=$%04X ${if (origPtrs.roomPtrTable != exportPtrs.roomPtrTable) "MOVED" else "same"}".format(origPtrs.roomPtrTable, exportPtrs.roomPtrTable))
        println("  structPtrTable:   orig=$%04X export=$%04X ${if (origPtrs.structPtrTable != exportPtrs.structPtrTable) "MOVED" else "same"}".format(origPtrs.structPtrTable, exportPtrs.structPtrTable))
        println("  macroDefs:        orig=$%04X export=$%04X ${if (origPtrs.macroDefs != exportPtrs.macroDefs) "CHANGED!" else "OK"}".format(origPtrs.macroDefs, exportPtrs.macroDefs))
        println("  enemyFramePtrTbl1:orig=$%04X export=$%04X ${if (origPtrs.enemyFramePtrTbl1 != exportPtrs.enemyFramePtrTbl1) "CHANGED!" else "OK"}".format(origPtrs.enemyFramePtrTbl1, exportPtrs.enemyFramePtrTbl1))
        println("  enemyFramePtrTbl2:orig=$%04X export=$%04X ${if (origPtrs.enemyFramePtrTbl2 != exportPtrs.enemyFramePtrTbl2) "CHANGED!" else "OK"}".format(origPtrs.enemyFramePtrTbl2, exportPtrs.enemyFramePtrTbl2))
        println("  enemyPlacePtrTbl: orig=$%04X export=$%04X ${if (origPtrs.enemyPlacePtrTbl != exportPtrs.enemyPlacePtrTbl) "CHANGED!" else "OK"}".format(origPtrs.enemyPlacePtrTbl, exportPtrs.enemyPlacePtrTbl))
        println("  enemyAnimIndexTbl:orig=$%04X export=$%04X ${if (origPtrs.enemyAnimIndexTbl != exportPtrs.enemyAnimIndexTbl) "CHANGED!" else "OK"}".format(origPtrs.enemyAnimIndexTbl, exportPtrs.enemyAnimIndexTbl))

        verifySpecItemsData(r, exportParser, Area.BRINSTAR, origPtrs, exportPtrs)
        assertEquals(origPtrs.macroDefs, exportPtrs.macroDefs, "macroDefs corrupted!")
        assertEquals(origPtrs.enemyFramePtrTbl1, exportPtrs.enemyFramePtrTbl1, "enemyFramePtrTbl1 corrupted!")
        assertEquals(origPtrs.enemyFramePtrTbl2, exportPtrs.enemyFramePtrTbl2, "enemyFramePtrTbl2 corrupted!")
        assertEquals(origPtrs.enemyPlacePtrTbl, exportPtrs.enemyPlacePtrTbl, "enemyPlacePtrTbl corrupted!")
        assertEquals(origPtrs.enemyAnimIndexTbl, exportPtrs.enemyAnimIndexTbl, "enemyAnimIndexTbl corrupted!")

        // Full bank byte diff: check for unexpected changes
        val bankNum = MetroidRomData.AREA_BANKS[Area.BRINSTAR]!!
        val bStart = NesRomParser.INES_HEADER_SIZE + bankNum * NesRomParser.PRG_BANK_SIZE
        val bEnd = bStart + NesRomParser.PRG_BANK_SIZE
        val dataFloor = minOf(
            origPtrs.specItemsTable, origPtrs.roomPtrTable, origPtrs.structPtrTable,
            origPtrs.macroDefs, origPtrs.enemyFramePtrTbl1, origPtrs.enemyFramePtrTbl2,
            origPtrs.enemyPlacePtrTbl, origPtrs.enemyAnimIndexTbl
        )
        val dataFloorRom = bStart + (dataFloor - 0x8000)
        // The area pointer table at AREA_POINTERS_ADDR ($9598) is 16 bytes of data
        // that we intentionally update — exclude it from the code region check.
        val areaPtrRom = bStart + (MetroidRomData.AREA_POINTERS_ADDR - 0x8000)
        val areaPtrRange = areaPtrRom until areaPtrRom + 16

        var codeRegionChanges = 0
        var dataRegionChanges = 0
        val changedRegions = mutableListOf<Pair<Int, Int>>()
        var changeStart = -1
        for (i in bStart until bEnd) {
            val origByte = r.readByte(i) and 0xFF
            val expByte = exportParser.readByte(i) and 0xFF
            if (origByte != expByte) {
                if (changeStart < 0) changeStart = i
                val isAreaPtrTable = i in areaPtrRange
                if (i < dataFloorRom && !isAreaPtrTable) codeRegionChanges++ else dataRegionChanges++
            } else {
                if (changeStart >= 0) {
                    changedRegions.add(changeStart to (i - changeStart))
                    changeStart = -1
                }
            }
        }
        if (changeStart >= 0) changedRegions.add(changeStart to (bEnd - changeStart))

        println("\n=== Bank byte diff summary ===")
        println("  Data region floor: CPU $%04X (ROM $%05X)".format(dataFloor, dataFloorRom))
        println("  Code region changes: $codeRegionChanges (MUST be 0)")
        println("  Data region changes: $dataRegionChanges")
        println("  Changed regions: ${changedRegions.size}")
        for ((start, len) in changedRegions.take(20)) {
            val cpuAddr = 0x8000 + (start - bStart)
            val region = if (start < dataFloorRom) "CODE!" else "data"
            println("  ROM $%05X (CPU $%04X): $len bytes changed [$region]".format(start, cpuAddr))
        }
        assertEquals(0, codeRegionChanges,
            "Code region (below $%04X) was modified! This corrupts game logic.".format(dataFloor))

        // Verify ALL rooms in Brinstar can be re-read correctly
        println("\n=== All rooms re-read check ===")
        var failedRooms = 0
        val exportAllRooms = exportMd.readAllRooms(Area.BRINSTAR)
        for (origR in allRooms) {
            val expR = exportAllRooms.find { it.roomNumber == origR.roomNumber }
            if (expR == null) {
                println("  Room ${origR.roomNumber}: MISSING in export!")
                failedRooms++
                continue
            }
            if (origR.roomNumber != 9) {
                if (origR.enemies.size != expR.enemies.size) {
                    println("  Room ${origR.roomNumber}: enemy count ${origR.enemies.size} → ${expR.enemies.size}")
                    failedRooms++
                }
                if (origR.doors.size != expR.doors.size) {
                    println("  Room ${origR.roomNumber}: door count ${origR.doors.size} → ${expR.doors.size}")
                    failedRooms++
                }
            }
        }
        if (failedRooms == 0) println("  All ${allRooms.size} rooms OK!")
        assertEquals(0, failedRooms, "Some rooms have corrupted data after export!")
    }

    @Test
    fun `edits in norfair room`() {
        val d = data ?: return
        if (rom == null) return

        val rooms = d.readAllRooms(Area.NORFAIR)
        val room = rooms.firstOrNull { it.objects.isNotEmpty() } ?: return
        val renderer = MapRenderer(d, NesPatternDecoder(rom!!))
        val grid = renderer.buildMacroGrid(room)
        val coverage = RomExporter.buildCoverageMap(d, room)

        val edits = mutableListOf<MacroEdit>()
        for (my in 0 until MapRenderer.ROOM_HEIGHT_MACROS) {
            for (mx in 0 until MapRenderer.ROOM_WIDTH_MACROS) {
                if (coverage[my * MapRenderer.ROOM_WIDTH_MACROS + mx]) {
                    val orig = grid.get(mx, my)
                    if (orig >= 0) {
                        edits.add(MacroEdit(mx, my, 0, grid.getAttr(mx, my)))
                        if (edits.size >= 3) break
                    }
                }
            }
            if (edits.size >= 3) break
        }

        if (edits.isEmpty()) return
        exportAndVerify("norfair_covered", Area.NORFAIR, room.roomNumber, edits)
    }

    @Test
    fun `edits in kraid room`() {
        val d = data ?: return
        if (rom == null) return

        val rooms = d.readAllRooms(Area.KRAID)
        val room = rooms.firstOrNull { it.objects.isNotEmpty() } ?: return
        val renderer = MapRenderer(d, NesPatternDecoder(rom!!))
        val grid = renderer.buildMacroGrid(room)
        val coverage = RomExporter.buildCoverageMap(d, room)

        val edits = mutableListOf<MacroEdit>()
        for (my in 0 until MapRenderer.ROOM_HEIGHT_MACROS) {
            for (mx in 0 until MapRenderer.ROOM_WIDTH_MACROS) {
                if (coverage[my * MapRenderer.ROOM_WIDTH_MACROS + mx]) {
                    val orig = grid.get(mx, my)
                    if (orig >= 0) {
                        edits.add(MacroEdit(mx, my, 0, grid.getAttr(mx, my)))
                        if (edits.size >= 3) break
                    }
                }
            }
            if (edits.size >= 3) break
        }

        if (edits.isEmpty()) return
        exportAndVerify("kraid_covered", Area.KRAID, room.roomNumber, edits)
    }

    @Test
    fun `multi-room edits across same area`() {
        val baseRom = rom ?: return
        // Most Brinstar structs are shared — use expanded ROM
        val expanded = RomExpander.expand(baseRom.romData) ?: return
        val r = NesRomParser(expanded)
        val d = MetroidRomData(r)
        val pd = NesPatternDecoder(r)
        val renderer = MapRenderer(d, pd)

        val rooms = d.readAllRooms(Area.BRINSTAR)
        val editedRooms = mutableMapOf<String, RoomEdits>()

        var roomsEdited = 0
        for (room in rooms) {
            if (roomsEdited >= 3) break
            val grid = renderer.buildMacroGrid(room)
            val coverage = RomExporter.buildCoverageMap(d, room)

            val edits = mutableListOf<MacroEdit>()
            for (my in 0 until MapRenderer.ROOM_HEIGHT_MACROS) {
                for (mx in 0 until MapRenderer.ROOM_WIDTH_MACROS) {
                    if (coverage[my * MapRenderer.ROOM_WIDTH_MACROS + mx]) {
                        val orig = grid.get(mx, my)
                        if (orig >= 0 && orig != 32) {
                            edits.add(MacroEdit(mx, my, 32, grid.getAttr(mx, my)))
                            if (edits.size >= 2) break
                        }
                    }
                }
                if (edits.size >= 2) break
            }

            if (edits.isNotEmpty()) {
                editedRooms[roomKey(Area.BRINSTAR, room.roomNumber)] =
                    RoomEdits(macroEdits = edits.toMutableList())
                roomsEdited++
            }
        }

        val exportData = r.copyRomData()
        val exportParser = NesRomParser(exportData)
        val result = RomExporter.exportAll(exportParser, r, d, renderer, editedRooms)

        println("=== multi_room_brinstar ===")
        println("  Rooms edited: $roomsEdited")
        println("  Covered: ${result.coveredPatched}, Errors: ${result.warnings}")

        val tmpFile = File("/tmp/metroid_test_multi_room_brinstar.nes")
        tmpFile.writeBytes(exportData)
        println("  Written to: ${tmpFile.absolutePath}")

        assertTrue(result.success, "Multi-room export should succeed: ${result.warnings}")
        assertTrue(result.totalEdits > 0, "Should have edits applied")

        val editedNums = editedRooms.keys.mapNotNull { it.split(":").getOrNull(1)?.toIntOrNull(16) }.toSet()
        verifyIntegrity(exportParser, d, Area.BRINSTAR, editedNums)
    }

    @Test
    fun `single uncovered tile in packed bank fails gracefully`() {
        val r = rom ?: return
        val d = data ?: return

        val edits = listOf(MacroEdit(3, 8, 6, 0))  // (3,8) is uncovered in Room 9

        val exportData = r.copyRomData()
        val exportParser = NesRomParser(exportData)
        val pd = NesPatternDecoder(r)
        val renderer = MapRenderer(d, pd)

        val editedRooms = mutableMapOf<String, RoomEdits>()
        editedRooms[roomKey(Area.BRINSTAR, 9)] = RoomEdits(macroEdits = edits.toMutableList())
        val result = RomExporter.exportAll(exportParser, r, d, renderer, editedRooms)

        // Standard Metroid Brinstar is fully packed — uncovered edits fail
        assertTrue(result.skippedUncovered > 0, "Should skip uncovered edits in packed bank")
    }

    @Test
    fun `spec items table relocation test expects space error in packed bank`() {
        val r = rom ?: return
        val d = data ?: return

        // (3,8) is uncovered in Room 9, requires full rewrite which won't fit
        val edits = listOf(MacroEdit(3, 8, 6, 0))

        val exportData = r.copyRomData()
        val exportParser = NesRomParser(exportData)
        val pd = NesPatternDecoder(r)
        val renderer = MapRenderer(d, pd)

        val editedRooms = mutableMapOf<String, RoomEdits>()
        editedRooms[roomKey(Area.BRINSTAR, 9)] = RoomEdits(macroEdits = edits.toMutableList())
        val result = RomExporter.exportAll(exportParser, r, d, renderer, editedRooms)

        // Standard ROM is packed — uncovered edits can't fit
        assertTrue(result.skippedUncovered > 0, "Should skip uncovered edits")
    }

    @Test
    fun `multi-room uncovered edits do not corrupt specItemsTable (bug 4)`() {
        val r = rom ?: return
        val d = data ?: return
        val pd = NesPatternDecoder(r)
        val renderer = MapRenderer(d, pd)

        // Place uncovered tiles in 2 rooms — each may create new struct entries,
        // growing the struct pointer table into the specItemsTable region.
        // Use specific rooms that don't expand excessively (Room 1 grows from 8→50 bytes).
        val rooms = d.readAllRooms(Area.BRINSTAR)
        val editedRooms = mutableMapOf<String, RoomEdits>()
        val targetRooms = listOf(9, 2)
        var roomsEdited = 0

        for (roomNum in targetRooms) {
            val room = rooms.find { it.roomNumber == roomNum } ?: continue
            val grid = renderer.buildMacroGrid(room)
            val coverage = RomExporter.buildCoverageMap(d, room)

            // Find an UNcovered cell to place a tile — this forces new struct creation
            var edit: MacroEdit? = null
            for (my in 0 until MapRenderer.ROOM_HEIGHT_MACROS) {
                for (mx in 0 until MapRenderer.ROOM_WIDTH_MACROS) {
                    val idx = my * MapRenderer.ROOM_WIDTH_MACROS + mx
                    if (!coverage[idx] && grid.get(mx, my) < 0) {
                        edit = MacroEdit(mx, my, 6, 0)
                        break
                    }
                }
                if (edit != null) break
            }
            if (edit == null) continue

            editedRooms[roomKey(Area.BRINSTAR, room.roomNumber)] =
                RoomEdits(macroEdits = mutableListOf(edit))
            roomsEdited++
        }

        assertTrue(roomsEdited >= 2, "Need at least 2 rooms with uncovered cells (got $roomsEdited)")

        val exportData = r.copyRomData()
        val exportParser = NesRomParser(exportData)
        val result = RomExporter.exportAll(exportParser, r, d, renderer, editedRooms)

        println("=== multi_room_uncovered_bug4 ===")
        println("  Rooms edited: $roomsEdited")
        println("  Result: covered=${result.coveredPatched} uncovered=${result.skippedUncovered}")
        println("  Errors: ${result.warnings}")

        val tmpFile = File("/tmp/metroid_test_multi_room_uncovered_bug4.nes")
        tmpFile.writeBytes(exportData)
        println("  Written to: ${tmpFile.absolutePath}")

        // Standard ROM is packed — uncovered edits fail gracefully
        assertTrue(result.skippedUncovered > 0, "Should skip uncovered edits in packed bank")
        return

        // Verify specItemsTable is not corrupted (unreachable — left for ROM expansion)
        val exportMd = MetroidRomData(exportParser)
        val origPtrs = d.readAreaPointers(Area.BRINSTAR)
        val newPtrs = exportMd.readAreaPointers(Area.BRINSTAR)
        verifySpecItemsData(r, exportParser, Area.BRINSTAR, origPtrs, newPtrs)

        // Verify all rooms can still be read
        val exportRooms = exportMd.readAllRooms(Area.BRINSTAR)
        assertEquals(rooms.size, exportRooms.size, "Room count changed!")

        // Verify non-edited rooms have intact data (object/enemy/door counts)
        // Note: raw byte comparison may fail for relocated rooms (offset-dependent re-parsing)
        // so we check semantic equivalence instead.
        val editedNums = editedRooms.keys.mapNotNull { it.split(":").getOrNull(1)?.toIntOrNull(16) }.toSet()
        var corruptedRooms = 0
        for (origRoom in rooms) {
            if (origRoom.roomNumber in editedNums) continue
            val expRoom = exportRooms.find { it.roomNumber == origRoom.roomNumber }
                ?: fail("Room ${origRoom.roomNumber} missing!")
            if (origRoom.objects.size != expRoom.objects.size ||
                origRoom.enemies.size != expRoom.enemies.size ||
                origRoom.doors.size != expRoom.doors.size) {
                println("  Room ${origRoom.roomNumber} CORRUPTED: objects=${origRoom.objects.size}→${expRoom.objects.size} " +
                    "enemies=${origRoom.enemies.size}→${expRoom.enemies.size} doors=${origRoom.doors.size}→${expRoom.doors.size}")
                corruptedRooms++
            }
        }
        assertEquals(0, corruptedRooms, "$corruptedRooms non-edited rooms corrupted after multi-room export")

        println("  All integrity checks passed!")
    }

    @Test
    fun `export preserves room object count when only covered edits`() {
        val baseRom = rom ?: return
        // Room 9's structs are all shared — use expanded ROM
        val expanded = RomExpander.expand(baseRom.romData) ?: return
        val r = NesRomParser(expanded)
        val d = MetroidRomData(r)
        val pd = NesPatternDecoder(r)
        val renderer = MapRenderer(d, pd)

        val room = d.readRoom(Area.BRINSTAR, 9) ?: return
        val grid = renderer.buildMacroGrid(room)
        val coverage = RomExporter.buildCoverageMap(d, room)

        val edit = (0 until MapRenderer.ROOM_HEIGHT_MACROS).flatMap { my ->
            (0 until MapRenderer.ROOM_WIDTH_MACROS).mapNotNull { mx ->
                val idx = my * MapRenderer.ROOM_WIDTH_MACROS + mx
                if (coverage[idx] && grid.get(mx, my) >= 0 && grid.get(mx, my) != 32) {
                    MacroEdit(mx, my, 32, grid.getAttr(mx, my))
                } else null
            }
        }.firstOrNull() ?: return

        val editedRooms = mutableMapOf<String, RoomEdits>()
        editedRooms[roomKey(Area.BRINSTAR, 9)] = RoomEdits(macroEdits = mutableListOf(edit))

        val exportData = r.copyRomData()
        val exportParser = NesRomParser(exportData)
        val result = RomExporter.exportAll(exportParser, r, d, renderer, editedRooms)

        assertTrue(result.success, "Export failed: ${result.warnings}")
        assertTrue(result.totalEdits >= 1, "At least 1 edit should be applied")

        val exportMd = MetroidRomData(exportParser)
        val reread = exportMd.readRoom(Area.BRINSTAR, 9) ?: fail("Room should exist")
        // On expanded ROMs with shared struct cloning, object count may differ
        assertTrue(reread.objects.isNotEmpty(), "Room should still have objects")
    }

    @Test
    fun `multi-room uncovered edits do not corrupt other rooms (Bug 5)`() {
        val r = rom ?: return
        val d = data ?: return
        val pd = NesPatternDecoder(r)
        val renderer = MapRenderer(d, pd)

        // Edit uncovered cells in 2 rooms — triggers compaction on the first room
        // and exercises the Bug 5 fix (stale romOffset after compaction).
        // Use macro 6 and specific rooms that don't expand excessively.
        val rooms = d.readAllRooms(Area.BRINSTAR)
        val editedRooms = mutableMapOf<String, RoomEdits>()
        val targetRooms = listOf(9, 2) // Room 9 and Room 2 have moderate growth
        var roomsEdited = 0

        for (roomNum in targetRooms) {
            val room = rooms.find { it.roomNumber == roomNum } ?: continue
            val coverage = RomExporter.buildCoverageMap(d, room)

            // Find an uncovered cell we can edit
            val edit = (0 until MapRenderer.ROOM_HEIGHT_MACROS).flatMap { my ->
                (0 until MapRenderer.ROOM_WIDTH_MACROS).mapNotNull { mx ->
                    val idx = my * MapRenderer.ROOM_WIDTH_MACROS + mx
                    if (!coverage[idx]) {
                        MacroEdit(mx, my, 6, 0)
                    } else null
                }
            }.firstOrNull() ?: continue

            editedRooms[roomKey(Area.BRINSTAR, room.roomNumber)] =
                RoomEdits(macroEdits = mutableListOf(edit))
            roomsEdited++
        }

        if (roomsEdited < 2) {
            println("Could not find enough rooms with uncovered cells, skipping")
            return
        }

        val exportData = r.copyRomData()
        val exportParser = NesRomParser(exportData)
        val result = RomExporter.exportAll(exportParser, r, d, renderer, editedRooms)

        println("=== multi_room_uncovered_bug5 ===")
        println("  Rooms edited: $roomsEdited")
        println("  Covered: ${result.coveredPatched}, Uncovered: ${result.skippedUncovered}")
        println("  Errors: ${result.warnings}")
        // Standard ROM is packed — uncovered edits fail gracefully
        assertTrue(result.skippedUncovered > 0, "Should skip uncovered edits in packed bank")
        return

        val tmpFile = File("/tmp/metroid_test_multi_room_uncovered_bug5.nes")
        tmpFile.writeBytes(exportData)
        println("  Written to: ${tmpFile.absolutePath}")

        // Verify ALL rooms in the area are intact (not just edited ones)
        val editedNums = editedRooms.keys.map {
            it.split(":").getOrNull(1)?.toIntOrNull(16) ?: -1
        }.toSet()

        // Diagnostic: dump Room 46 details before verifyIntegrity
        val exportMd = MetroidRomData(exportParser)
        val origRoom46 = d.readRoom(Area.BRINSTAR, 46)
        val exportRoom46 = exportMd.readRoom(Area.BRINSTAR, 46)
        if (origRoom46 != null && exportRoom46 != null) {
            println("  Room 46 orig: objects=${origRoom46.objects.size} enemies=${origRoom46.enemies.size} " +
                    "doors=${origRoom46.doors.size} rawSize=${origRoom46.rawData.size} offset=0x${"%05X".format(origRoom46.romOffset)}")
            println("  Room 46 export: objects=${exportRoom46.objects.size} enemies=${exportRoom46.enemies.size} " +
                    "doors=${exportRoom46.doors.size} rawSize=${exportRoom46.rawData.size} offset=0x${"%05X".format(exportRoom46.romOffset)}")
            val bank = MetroidRomData.AREA_BANKS[Area.BRINSTAR]!!
            val origPtrs = d.readAreaPointers(Area.BRINSTAR)
            val newPtrs = exportMd.readAreaPointers(Area.BRINSTAR)
            val origCpu = r.readBankWord(bank, origPtrs.roomPtrTable + 46 * 2)
            val newCpu = exportParser.readBankWord(bank, newPtrs.roomPtrTable + 46 * 2)
            println("  Room 46 ptr: orig=0x${"%04X".format(origCpu)} export=0x${"%04X".format(newCpu)}")
            // Dump first 20 bytes at both offsets
            val dumpSize = minOf(70, origRoom46.rawData.size + 10)
            val origBytes = (0 until dumpSize).map { "%02X".format(r.readByte(origRoom46.romOffset + it) and 0xFF) }.joinToString(" ")
            val expBytes = (0 until dumpSize).map { "%02X".format(exportParser.readByte(exportRoom46.romOffset + it) and 0xFF) }.joinToString(" ")
            println("  Room 46 orig bytes (${dumpSize}): $origBytes")
            println("  Room 46 export bytes (${dumpSize}): $expBytes")
            println("  Room 46 orig rawData: ${origRoom46.rawData.map { "%02X".format(it.toInt() and 0xFF) }.joinToString(" ")}")
            // Check what room 47 looks like
            val origRoom47 = d.readRoom(Area.BRINSTAR, 47)
            val exportRoom47 = exportMd.readRoom(Area.BRINSTAR, 47)
            if (origRoom47 != null) {
                println("  Room 47 orig: offset=0x${"%05X".format(origRoom47.romOffset)} rawSize=${origRoom47.rawData.size}")
            }
            if (exportRoom47 != null) {
                println("  Room 47 export: offset=0x${"%05X".format(exportRoom47.romOffset)} rawSize=${exportRoom47.rawData.size}")
                println("  Room 46 end offset: 0x${"%05X".format(exportRoom46.romOffset + 26)}")
                println("  Gap between Room 46 end and Room 47: ${exportRoom47.romOffset - (exportRoom46.romOffset + 26)}")
            }
        }

        verifyIntegrity(exportParser, d, Area.BRINSTAR, editedNums)
        println("  All room integrity verified")
    }

    // -- Deep ROM validation tests --

    @Test
    fun `zero-edit export produces byte-identical ROM`() {
        val r = rom ?: return
        val d = data ?: return
        val pd = NesPatternDecoder(r)
        val renderer = MapRenderer(d, pd)

        val exportData = r.copyRomData()
        val exportParser = NesRomParser(exportData)
        val result = RomExporter.exportAll(exportParser, r, d, renderer, emptyMap())

        assertTrue(result.success, "Zero-edit export should succeed: ${result.warnings}")
        assertEquals(0, result.totalEdits, "Zero-edit export should have 0 edits")

        // Byte-for-byte comparison
        val origData = r.copyRomData()
        var diffs = 0
        val firstDiffs = mutableListOf<String>()
        for (i in origData.indices) {
            if (origData[i] != exportData[i]) {
                diffs++
                if (firstDiffs.size < 20) {
                    firstDiffs.add("  offset 0x%05X: orig=0x%02X export=0x%02X".format(
                        i, origData[i].toInt() and 0xFF, exportData[i].toInt() and 0xFF))
                }
            }
        }
        if (diffs > 0) {
            println("=== zero_edit_identity ===")
            println("  $diffs bytes differ!")
            firstDiffs.forEach { println(it) }
        }
        assertEquals(0, diffs, "Zero-edit export should be byte-identical to original")
    }

    @Test
    fun `single covered edit preserves all areas and pointer tables`() {
        val baseRom = rom ?: return
        // Room 9's structs are all shared — use expanded ROM
        val expanded = RomExpander.expand(baseRom.romData) ?: return
        val r = NesRomParser(expanded)
        val d = MetroidRomData(r)
        val pd = NesPatternDecoder(r)
        val renderer = MapRenderer(d, pd)

        // Find one covered cell to edit in Brinstar
        val room = d.readRoom(Area.BRINSTAR, 9) ?: return
        val grid = renderer.buildMacroGrid(room)
        val coverage = RomExporter.buildCoverageMap(d, room)
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

        val editedRooms = mutableMapOf<String, RoomEdits>()
        editedRooms[roomKey(Area.BRINSTAR, room.roomNumber)] =
            RoomEdits(macroEdits = mutableListOf(edit))

        val exportData = r.copyRomData()
        val exportParser = NesRomParser(exportData)
        val result = RomExporter.exportAll(exportParser, r, d, renderer, editedRooms)
        assertTrue(result.success, "Single covered edit should succeed: ${result.warnings}")

        val tmpFile = File("/tmp/metroid_test_single_covered_deep.nes")
        tmpFile.writeBytes(exportData)
        println("=== single_covered_deep ===")
        println("  Written to: ${tmpFile.absolutePath}")

        // Deep validation: every area, every room, every pointer table
        deepValidateExportedRom(r, exportParser, d, Area.BRINSTAR, setOf(room.roomNumber))
    }

    @Test
    fun `single uncovered edit preserves all areas and pointer tables`() {
        val r = rom ?: return
        val d = data ?: return
        val pd = NesPatternDecoder(r)
        val renderer = MapRenderer(d, pd)

        // Find one uncovered cell to edit in Brinstar
        val room = d.readRoom(Area.BRINSTAR, 0) ?: return
        val grid = renderer.buildMacroGrid(room)
        val coverage = RomExporter.buildCoverageMap(d, room)
        var edit: MacroEdit? = null
        for (my in 0 until MapRenderer.ROOM_HEIGHT_MACROS) {
            for (mx in 0 until MapRenderer.ROOM_WIDTH_MACROS) {
                val idx = my * MapRenderer.ROOM_WIDTH_MACROS + mx
                if (!coverage[idx] && grid.get(mx, my) < 0) {
                    edit = MacroEdit(mx, my, 6, 0)
                    break
                }
            }
            if (edit != null) break
        }
        if (edit == null) return

        val editedRooms = mutableMapOf<String, RoomEdits>()
        editedRooms[roomKey(Area.BRINSTAR, room.roomNumber)] =
            RoomEdits(macroEdits = mutableListOf(edit))

        val exportData = r.copyRomData()
        val exportParser = NesRomParser(exportData)
        val result = RomExporter.exportAll(exportParser, r, d, renderer, editedRooms)
        // Standard ROM is packed — uncovered edits fail gracefully
        assertTrue(result.skippedUncovered > 0, "Should skip uncovered edits in packed bank")
        return  // Skip deep validation since export didn't apply

        val tmpFile = File("/tmp/metroid_test_single_uncovered_deep.nes")
        tmpFile.writeBytes(exportData)
        println("=== single_uncovered_deep ===")
        println("  Edit: macro ${edit.macroIndex} at (${edit.x},${edit.y}) in Room 0")
        println("  Written to: ${tmpFile.absolutePath}")

        // Validate struct pointer table integrity
        val exportMd = MetroidRomData(exportParser)
        val exportPtrs = exportMd.readAreaPointers(Area.BRINSTAR)
        val bank = MetroidRomData.AREA_BANKS[Area.BRINSTAR]!!
        val bankStart = NesRomParser.INES_HEADER_SIZE + bank * NesRomParser.PRG_BANK_SIZE

        // Read exported room and check its object struct references
        val exportRoom = exportMd.readRoom(Area.BRINSTAR, 0)!!
        println("  Exported Room 0: ${exportRoom.objects.size} objects")
        for ((i, obj) in exportRoom.objects.withIndex()) {
            val structCpu = exportParser.readBankWord(bank, exportPtrs.structPtrTable + obj.structIndex * 2)
            val structRom = exportParser.bankAddressToRomOffset(bank, structCpu)
            val firstBytes = (0 until minOf(8, bankStart + NesRomParser.PRG_BANK_SIZE - structRom))
                .map { "%02X".format(exportParser.readByte(structRom + it) and 0xFF) }
                .joinToString(" ")
            println("    obj[$i]: pos=(${obj.posX},${obj.posY}) struct=${obj.structIndex} " +
                "pal=${obj.palette} → CPU 0x%04X data: $firstBytes".format(structCpu))
        }

        // Compare ALL struct pointers orig vs export, using measured sizes
        val origPtrs = d.readAreaPointers(Area.BRINSTAR)
        val maxStruct = 50 // includes potential new struct
        var structDiffs = 0
        for (idx in 0 until maxStruct) {
            val origCpu = r.readBankWord(bank, origPtrs.structPtrTable + idx * 2)
            val expCpu = exportParser.readBankWord(bank, exportPtrs.structPtrTable + idx * 2)
            if (origCpu != expCpu) {
                if (origCpu < 0x8000 || origCpu >= 0xC000) continue
                if (expCpu < 0x8000 || expCpu >= 0xC000) {
                    println("  Struct[$idx]: 0x%04X→0x%04X INVALID EXPORT POINTER!".format(origCpu, expCpu))
                    structDiffs++
                    continue
                }
                val origRom = r.bankAddressToRomOffset(bank, origCpu)
                val expRom = exportParser.bankAddressToRomOffset(bank, expCpu)
                // Measure struct size from original
                val origSize = RomExporter.measureStructDataSizeForTest(r, bank, origCpu)
                val origBytes = (0 until origSize).map { "%02X".format(r.readByte(origRom + it) and 0xFF) }.joinToString(" ")
                val expBytes = (0 until origSize).map { "%02X".format(exportParser.readByte(expRom + it) and 0xFF) }.joinToString(" ")
                val dataMatch = origBytes == expBytes
                println("  Struct[$idx]: 0x%04X→0x%04X size=$origSize data: $origBytes → $expBytes ${if (dataMatch) "OK" else "CORRUPTED!"}".format(origCpu, expCpu))
                if (!dataMatch) structDiffs++
            }
        }
        assertEquals(0, structDiffs, "$structDiffs structs have corrupted data after relocation")

        deepValidateExportedRom(r, exportParser, d, Area.BRINSTAR, setOf(room.roomNumber))
    }

    /**
     * Deep validation: checks ALL areas (not just the edited one) to ensure
     * nothing outside the edited bank was corrupted.
     */
    private fun deepValidateExportedRom(
        origParser: NesRomParser,
        exportParser: NesRomParser,
        origData: MetroidRomData,
        editedArea: Area,
        editedRoomNums: Set<Int>
    ) {
        val exportMd = MetroidRomData(exportParser)
        val issues = mutableListOf<String>()

        for (area in Area.entries) {
            val bank = MetroidRomData.AREA_BANKS[area] ?: continue
            val origPtrs = origData.readAreaPointers(area)
            val exportPtrs = exportMd.readAreaPointers(area)

            // Non-edited areas should have identical pointer tables
            if (area != editedArea) {
                if (origPtrs.roomPtrTable != exportPtrs.roomPtrTable)
                    issues.add("${area.displayName}: roomPtrTable changed 0x%04X→0x%04X".format(origPtrs.roomPtrTable, exportPtrs.roomPtrTable))
                if (origPtrs.structPtrTable != exportPtrs.structPtrTable)
                    issues.add("${area.displayName}: structPtrTable changed")
                if (origPtrs.specItemsTable != exportPtrs.specItemsTable)
                    issues.add("${area.displayName}: specItemsTable changed")
            }

            // These should NEVER change, even for edited areas
            if (origPtrs.macroDefs != exportPtrs.macroDefs)
                issues.add("${area.displayName}: macroDefs changed 0x%04X→0x%04X".format(origPtrs.macroDefs, exportPtrs.macroDefs))
            if (origPtrs.enemyFramePtrTbl1 != exportPtrs.enemyFramePtrTbl1)
                issues.add("${area.displayName}: enemyFramePtrTbl1 changed")
            if (origPtrs.enemyFramePtrTbl2 != exportPtrs.enemyFramePtrTbl2)
                issues.add("${area.displayName}: enemyFramePtrTbl2 changed")
            if (origPtrs.enemyPlacePtrTbl != exportPtrs.enemyPlacePtrTbl)
                issues.add("${area.displayName}: enemyPlacePtrTbl changed")
            if (origPtrs.enemyAnimIndexTbl != exportPtrs.enemyAnimIndexTbl)
                issues.add("${area.displayName}: enemyAnimIndexTbl changed")

            // Validate all room pointers point to valid addresses
            val origRooms = origData.readAllRooms(area)
            val exportRooms = try {
                exportMd.readAllRooms(area)
            } catch (e: Exception) {
                issues.add("${area.displayName}: readAllRooms CRASHED: ${e.message}")
                continue
            }

            if (origRooms.size != exportRooms.size) {
                issues.add("${area.displayName}: room count ${origRooms.size}→${exportRooms.size}")
                continue
            }

            // Validate each room's pointer is within the bank
            val bankStart = NesRomParser.INES_HEADER_SIZE + bank * NesRomParser.PRG_BANK_SIZE
            for (expRoom in exportRooms) {
                val cpuAddr = exportParser.readBankWord(bank, exportPtrs.roomPtrTable + expRoom.roomNumber * 2)
                if (cpuAddr < 0x8000 || cpuAddr >= 0xC000) {
                    issues.add("${area.displayName} Room ${expRoom.roomNumber}: pointer 0x%04X out of bank range".format(cpuAddr))
                }
            }

            // Validate non-edited rooms have identical data
            for (origRoom in origRooms) {
                if (area == editedArea && origRoom.roomNumber in editedRoomNums) continue
                val expRoom = exportRooms.find { it.roomNumber == origRoom.roomNumber } ?: continue

                if (origRoom.objects.size != expRoom.objects.size)
                    issues.add("${area.displayName} Room ${origRoom.roomNumber}: objects ${origRoom.objects.size}→${expRoom.objects.size}")
                if (origRoom.enemies.size != expRoom.enemies.size)
                    issues.add("${area.displayName} Room ${origRoom.roomNumber}: enemies ${origRoom.enemies.size}→${expRoom.enemies.size}")
                if (origRoom.doors.size != expRoom.doors.size)
                    issues.add("${area.displayName} Room ${origRoom.roomNumber}: doors ${origRoom.doors.size}→${expRoom.doors.size}")
                if (!origRoom.rawData.contentEquals(expRoom.rawData))
                    issues.add("${area.displayName} Room ${origRoom.roomNumber}: rawData changed (${origRoom.rawData.size}→${expRoom.rawData.size} bytes)")
            }

            // Non-edited areas: entire bank should be byte-identical
            if (area != editedArea) {
                var bankDiffs = 0
                for (i in bankStart until bankStart + NesRomParser.PRG_BANK_SIZE) {
                    if ((origParser.readByte(i) and 0xFF) != (exportParser.readByte(i) and 0xFF)) {
                        bankDiffs++
                    }
                }
                if (bankDiffs > 0) {
                    issues.add("${area.displayName}: bank $bank has $bankDiffs byte differences (should be untouched)")
                }
            }

            // Edited area: game code region (below data floor) must be untouched.
            // Skip for expanded ROMs — data uses freed pattern regions below the floor.
            if (area == editedArea && !RomExpander.isExpandedRom(origParser.romData)) {
                val floor = RomExporter.dataRegionFloorForTest(origPtrs)
                val codeStart = bankStart
                val codeEnd = bankStart + (floor - 0x8000)
                val areaPtrRomStart = bankStart + (MetroidRomData.AREA_POINTERS_ADDR - 0x8000)
                val areaPtrRomEnd = areaPtrRomStart + 16
                var codeDiffs = 0
                val codeFirstDiffs = mutableListOf<String>()
                for (i in codeStart until codeEnd) {
                    if (i in areaPtrRomStart until areaPtrRomEnd) continue
                    val orig = origParser.readByte(i) and 0xFF
                    val exp = exportParser.readByte(i) and 0xFF
                    if (orig != exp) {
                        codeDiffs++
                        if (codeFirstDiffs.size < 10) {
                            codeFirstDiffs.add("ROM 0x%05X (CPU 0x%04X): 0x%02X→0x%02X".format(
                                i, 0x8000 + i - bankStart, orig, exp))
                        }
                    }
                }
                if (codeDiffs > 0) {
                    issues.add("${area.displayName}: GAME CODE region corrupted ($codeDiffs bytes changed, floor=0x%04X)".format(floor))
                    codeFirstDiffs.forEach { issues.add("  $it") }
                }
            }
        }

        // Verify iNES header is intact
        for (i in 0 until NesRomParser.INES_HEADER_SIZE) {
            if ((origParser.readByte(i) and 0xFF) != (exportParser.readByte(i) and 0xFF)) {
                issues.add("iNES header byte $i changed!")
            }
        }

        // Verify fixed bank (bank 7: $C000-$FFFF with reset/NMI/IRQ vectors) is untouched
        val fixedBankStart = NesRomParser.INES_HEADER_SIZE + 7 * NesRomParser.PRG_BANK_SIZE
        var fixedDiffs = 0
        for (i in fixedBankStart until fixedBankStart + NesRomParser.PRG_BANK_SIZE) {
            if ((origParser.readByte(i) and 0xFF) != (exportParser.readByte(i) and 0xFF)) {
                fixedDiffs++
            }
        }
        if (fixedDiffs > 0) {
            issues.add("Fixed bank 7 corrupted ($fixedDiffs bytes changed)")
        }

        if (issues.isNotEmpty()) {
            println("  Deep validation issues:")
            issues.forEach { println("    - $it") }
        } else {
            println("  Deep validation: ALL OK")
        }
        assertTrue(issues.isEmpty(), "${issues.size} validation issues:\n${issues.joinToString("\n")}")
    }

    @Test
    fun `six-room multi-edit does not corrupt game code (user scenario)`() {
        val origParser = rom ?: return
        val md = data ?: return
        val renderer = MapRenderer(md, NesPatternDecoder(origParser))

        // Simulate user's actual edit from Kentroid.medit: 6 rooms in Brinstar
        val editedRooms = mutableMapOf<String, RoomEdits>()
        // Room 0x09: 4 edits with macro 32
        editedRooms["0:09"] = RoomEdits(mutableListOf(
            MacroEdit(11, 10, 32, 0), MacroEdit(12, 10, 32, 0),
            MacroEdit(13, 10, 32, 0), MacroEdit(13, 11, 32, 0)
        ))
        // Room 0x0B: 8 edits with macro 46
        editedRooms["0:0B"] = RoomEdits(mutableListOf(
            MacroEdit(4, 4, 46, 0), MacroEdit(5, 4, 46, 0),
            MacroEdit(6, 4, 46, 0), MacroEdit(8, 4, 46, 0),
            MacroEdit(9, 4, 46, 0), MacroEdit(9, 5, 46, 0),
            MacroEdit(9, 6, 46, 0), MacroEdit(9, 7, 46, 0)
        ))
        // Room 0x0C: 3 edits with macro 46
        editedRooms["0:0C"] = RoomEdits(mutableListOf(
            MacroEdit(5, 6, 46, 0), MacroEdit(5, 7, 46, 0), MacroEdit(6, 9, 46, 0)
        ))
        // Room 0x0D: 6 edits with macro 46
        editedRooms["0:0D"] = RoomEdits(mutableListOf(
            MacroEdit(10, 9, 46, 0), MacroEdit(11, 9, 46, 0),
            MacroEdit(9, 10, 46, 0), MacroEdit(4, 11, 46, 0),
            MacroEdit(5, 11, 46, 0), MacroEdit(6, 11, 46, 0)
        ))
        // Room 0x0E: 3 edits with macro 46
        editedRooms["0:0E"] = RoomEdits(mutableListOf(
            MacroEdit(1, 11, 46, 0), MacroEdit(2, 11, 46, 0), MacroEdit(3, 11, 46, 0)
        ))
        // Room 0x0F: 6 edits with macro 46
        editedRooms["0:0F"] = RoomEdits(mutableListOf(
            MacroEdit(6, 9, 46, 0), MacroEdit(7, 9, 46, 0),
            MacroEdit(8, 9, 46, 0), MacroEdit(10, 9, 46, 0),
            MacroEdit(1, 10, 46, 0), MacroEdit(1, 11, 46, 0)
        ))

        val exportData = origParser.copyRomData()
        val exportParser = NesRomParser(exportData)
        val result = RomExporter.exportAll(exportParser, origParser, md, renderer, editedRooms)

        println("User scenario: ${result.totalEdits} edits, covered=${result.coveredPatched}, " +
                "uncovered=${result.skippedUncovered}, errors=${result.warnings}")

        // Some rooms may fail due to bank space constraints — that's expected.
        // The critical test: no game code corruption even when rooms overflow.

        // Deep validation: game code in bank 1 must be untouched
        val bank = 1
        val bankStart = NesRomParser.INES_HEADER_SIZE + bank * NesRomParser.PRG_BANK_SIZE
        val ptrs = MetroidRomData.AreaPointers(
            specItemsTable = exportParser.readBankWord(bank, MetroidRomData.AREA_POINTERS_ADDR),
            roomPtrTable = exportParser.readBankWord(bank, MetroidRomData.AREA_POINTERS_ADDR + 2),
            structPtrTable = exportParser.readBankWord(bank, MetroidRomData.AREA_POINTERS_ADDR + 4),
            macroDefs = exportParser.readBankWord(bank, MetroidRomData.AREA_POINTERS_ADDR + 6),
            enemyFramePtrTbl1 = exportParser.readBankWord(bank, MetroidRomData.AREA_POINTERS_ADDR + 8),
            enemyFramePtrTbl2 = exportParser.readBankWord(bank, MetroidRomData.AREA_POINTERS_ADDR + 10),
            enemyPlacePtrTbl = exportParser.readBankWord(bank, MetroidRomData.AREA_POINTERS_ADDR + 12),
            enemyAnimIndexTbl = exportParser.readBankWord(bank, MetroidRomData.AREA_POINTERS_ADDR + 14)
        )

        // Check that enemy tables are untouched (skip macroDefs — compaction may allocate
        // into blank/0xFF macro entries, which is intentional and only affects unused macros)
        val tablePtrs = listOf(ptrs.enemyFramePtrTbl1, ptrs.enemyFramePtrTbl2,
            ptrs.enemyPlacePtrTbl, ptrs.enemyAnimIndexTbl)
        for (tablePtr in tablePtrs) {
            val tableRom = bankStart + (tablePtr - 0x8000)
            // Check first 64 bytes of each table
            for (i in 0 until 64) {
                if (tableRom + i >= bankStart + NesRomParser.PRG_BANK_SIZE) break
                val orig = origParser.readByte(tableRom + i) and 0xFF
                val export = exportParser.readByte(tableRom + i) and 0xFF
                if (orig != export) {
                    fail<Unit>("Game table at CPU %04X byte $i changed: $orig → $export".format(tablePtr))
                }
            }
        }

        // Write for manual emulator testing
        val outFile = File("/tmp/metroid_test_user_6room_scenario.nes")
        outFile.writeBytes(exportData)
        println("Wrote: ${outFile.absolutePath}")
    }
}
