package com.metroid.editor.rom

import com.metroid.editor.data.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ExportDiagnosticTest {

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
    fun `export covered position edits works`() {
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

        println("Applied ${edits.size} edits on covered positions")
        assertTrue(edits.isNotEmpty())

        val editedRooms = mutableMapOf<String, RoomEdits>()
        editedRooms[roomKey(Area.BRINSTAR, 9)] = RoomEdits(macroEdits = edits.toMutableList())

        val exportData = r.copyRomData()
        val exportParser = NesRomParser(exportData)
        val result = RomExporter.exportAll(exportParser, r, d, renderer, editedRooms)

        println("Covered: ${result.coveredPatched}, Uncovered: ${result.uncoveredPatched}")
        assertEquals(edits.size, result.coveredPatched, "All covered edits should be patched")
        assertTrue(result.success, "No errors expected")

        val exportMd = MetroidRomData(exportParser)
        val exportRenderer = MapRenderer(exportMd, NesPatternDecoder(exportParser))
        val reread = exportMd.readRoom(Area.BRINSTAR, 9) ?: fail("Room should exist")
        val rereadGrid = exportRenderer.buildMacroGrid(reread)

        for (edit in edits) {
            assertEquals(edit.macroIndex, rereadGrid.get(edit.x, edit.y),
                "Edit at (${edit.x},${edit.y}) should be applied")
        }
    }

    @Test
    fun `export user actual edits from medit file`() {
        val r = rom ?: return
        val d = data ?: return
        val pd = NesPatternDecoder(r)
        val renderer = MapRenderer(d, pd)

        // Exact edits from the user's .medit file
        val edits = mutableListOf(
            MacroEdit(2, 11, 32, 0), MacroEdit(3, 11, 32, 0),
            MacroEdit(13, 11, 32, 0), MacroEdit(14, 11, 32, 0),
            MacroEdit(2, 12, 32, 0), MacroEdit(3, 12, 32, 0),
            MacroEdit(4, 12, 32, 0), MacroEdit(12, 12, 32, 0),
            MacroEdit(13, 12, 32, 0), MacroEdit(14, 12, 32, 0),
        )

        val room = d.readRoom(Area.BRINSTAR, 9) ?: return
        val coverage = RomExporter.buildCoverageMap(d, room)

        var covCount = 0
        var uncCount = 0
        for (e in edits) {
            val idx = e.y * MapRenderer.ROOM_WIDTH_MACROS + e.x
            if (coverage[idx]) covCount++ else uncCount++
        }
        println("User edits: $covCount covered, $uncCount uncovered out of ${edits.size} total")

        val editedRooms = mutableMapOf<String, RoomEdits>()
        editedRooms[roomKey(Area.BRINSTAR, 9)] = RoomEdits(macroEdits = edits)

        val exportData = r.copyRomData()
        val exportParser = NesRomParser(exportData)
        val result = RomExporter.exportAll(exportParser, r, d, renderer, editedRooms)

        println("Result: covered=${result.coveredPatched}, uncovered=${result.uncoveredPatched}")
        println("Errors: ${result.errors}")

        if (result.success) {
            val exportMd = MetroidRomData(exportParser)
            val exportRenderer = MapRenderer(exportMd, NesPatternDecoder(exportParser))
            val reread = exportMd.readRoom(Area.BRINSTAR, 9) ?: fail("Room should exist")
            val rereadGrid = exportRenderer.buildMacroGrid(reread)

            for (edit in edits) {
                val actual = rereadGrid.get(edit.x, edit.y)
                val idx = edit.y * MapRenderer.ROOM_WIDTH_MACROS + edit.x
                val type = if (coverage[idx]) "covered" else "uncovered"
                assertEquals(edit.macroIndex, actual,
                    "$type edit at (${edit.x},${edit.y}): expected macro ${edit.macroIndex} got $actual")
            }
            println("All ${edits.size} user edits verified!")
        }

        // Verify other rooms/areas not corrupted
        val exportMd = MetroidRomData(exportParser)
        val origPtrs = d.readAreaPointers(Area.BRINSTAR)
        val newPtrs = exportMd.readAreaPointers(Area.BRINSTAR)
        assertEquals(origPtrs.specItemsTable, newPtrs.specItemsTable, "specItemsTable corrupted!")
        assertEquals(origPtrs.macroDefs, newPtrs.macroDefs, "macroDefs corrupted!")
        assertEquals(origPtrs.enemyFramePtrTbl1, newPtrs.enemyFramePtrTbl1, "enemyFramePtrTbl1 corrupted!")
        println("Critical area pointers verified intact")
    }

    @Test
    fun `room 9 structure layout and extension potential`() {
        val r = rom ?: return
        val d = data ?: return
        val pd = NesPatternDecoder(r)
        val renderer = MapRenderer(d, pd)

        val room = d.readRoom(Area.BRINSTAR, 9) ?: return
        val grid = renderer.buildMacroGrid(room)
        val coverage = RomExporter.buildCoverageMap(d, room)

        println("=== Room 9 Objects ===")
        for (obj in room.objects) {
            val struct = d.readStructure(Area.BRINSTAR, obj.structIndex)
            if (struct != null) {
                println("  Obj: pos=(${obj.posX},${obj.posY}) struct=${obj.structIndex} pal=${obj.palette}")
                for (row in struct.rows) {
                    val macros = row.macroIndices.joinToString(",")
                    println("    Row: xOff=${row.xOffset} macros=[$macros]")
                }
                val cells = struct.rows.sumOf { it.macroIndices.size }
                println("    (${cells} cells total)")
            }
        }

        println("\n=== Row 12 coverage ===")
        for (x in 0 until MapRenderer.ROOM_WIDTH_MACROS) {
            val idx = 12 * MapRenderer.ROOM_WIDTH_MACROS + x
            val macro = grid.get(x, 12)
            val cov = coverage[idx]
            println("  ($x,12): macro=$macro covered=$cov")
        }

        // Check which structs are used in OTHER rooms too
        val allRooms = d.readAllRooms(Area.BRINSTAR)
        val structUsage = mutableMapOf<Int, MutableSet<Int>>()
        for (r2 in allRooms) {
            for (obj in r2.objects) {
                structUsage.getOrPut(obj.structIndex) { mutableSetOf() }.add(r2.roomNumber)
            }
        }
        println("\n=== Struct sharing for room 9 objects ===")
        for (obj in room.objects) {
            val rooms = structUsage[obj.structIndex] ?: emptySet()
            println("  Struct ${obj.structIndex}: used in rooms ${rooms.sorted()}")
        }

        // Show free space around room 9
        val bank = MetroidRomData.AREA_BANKS[Area.BRINSTAR] ?: 0
        val bankStart = NesRomParser.INES_HEADER_SIZE + bank * NesRomParser.PRG_BANK_SIZE
        val sortedRooms = allRooms.sortedBy { it.romOffset }
        val r9idx = sortedRooms.indexOfFirst { it.roomNumber == 9 }
        if (r9idx >= 0) {
            val r9 = sortedRooms[r9idx]
            val before = if (r9idx > 0) sortedRooms[r9idx - 1] else null
            val after = if (r9idx < sortedRooms.size - 1) sortedRooms[r9idx + 1] else null
            val gapBefore = if (before != null) r9.romOffset - (before.romOffset + before.rawData.size) else -1
            val gapAfter = if (after != null) after.romOffset - (r9.romOffset + r9.rawData.size) else -1
            println("\n=== Room 9 surroundings ===")
            if (before != null) println("  Before: Room ${before.roomNumber} ends at ${before.romOffset + before.rawData.size}, gap=$gapBefore")
            println("  Room 9: offset=${r9.romOffset} size=${r9.rawData.size}")
            if (after != null) println("  After: Room ${after.roomNumber} starts at ${after.romOffset}, gap=$gapAfter")
        }
    }

    @Test
    fun `struct pointer table capacity for expansion`() {
        val r = rom ?: return
        val d = data ?: return

        for (area in Area.entries) {
            val ptrs = d.readAreaPointers(area)
            val bank = MetroidRomData.AREA_BANKS[area] ?: continue
            val rooms = d.readAllRooms(area)
            val maxStructIdx = rooms.flatMap { rm -> rm.objects.map { it.structIndex } }.maxOrNull() ?: 0
            val currentEntries = maxStructIdx + 1
            val tableEndAddr = ptrs.structPtrTable + currentEntries * 2

            // What's at the end of the struct pointer table?
            val sortedTables = listOf(
                "specItems" to ptrs.specItemsTable,
                "roomPtr" to ptrs.roomPtrTable,
                "structPtr" to ptrs.structPtrTable,
                "macroDefs" to ptrs.macroDefs,
                "enemyFrame1" to ptrs.enemyFramePtrTbl1,
                "enemyFrame2" to ptrs.enemyFramePtrTbl2,
                "enemyPlace" to ptrs.enemyPlacePtrTbl,
                "enemyAnim" to ptrs.enemyAnimIndexTbl
            ).sortedBy { it.second }

            val nextTable = sortedTables.firstOrNull { it.second > ptrs.structPtrTable }
            val gapAfterStructTbl = if (nextTable != null) nextTable.second - tableEndAddr else -1
            val firstStructDataAddr = (0..maxStructIdx).minOfOrNull {
                r.readBankWord(bank, ptrs.structPtrTable + it * 2)
            } ?: 0
            val gapToFirstStructData = firstStructDataAddr - tableEndAddr

            println("=== ${area.displayName} ===")
            println("  structPtrTable: $%04X, entries=$currentEntries (maxIdx=$maxStructIdx)".format(ptrs.structPtrTable))
            println("  table end: $%04X".format(tableEndAddr))
            println("  next table: ${nextTable?.first} at $%04X (gap=$gapAfterStructTbl bytes)".format(nextTable?.second ?: 0))
            println("  first struct data: $%04X (gap from table end=$gapToFirstStructData)".format(firstStructDataAddr))

            // Check bytes right after the table
            val bytesAfter = (0 until minOf(10, gapToFirstStructData.coerceAtLeast(0))).map {
                r.readBankByte(bank, tableEndAddr + it)
            }
            println("  bytes after table: ${bytesAfter.joinToString(" ") { "$%02X".format(it) }}")

            // The theoretical max struct pointer table size
            val oldStyleCount = if (nextTable != null) (nextTable.second - ptrs.structPtrTable) / 2 else -1
            println("  old-style table size (to next table): $oldStyleCount entries")
            println("  expansion room: ${gapAfterStructTbl / 2} new slots available")
        }
    }

    @Test
    fun `find duplicate or empty structs in brinstar`() {
        val r = rom ?: return
        val d = data ?: return

        val area = Area.BRINSTAR
        val bank = MetroidRomData.AREA_BANKS[area] ?: return
        val rooms = d.readAllRooms(area)
        val ptrs = d.readAreaPointers(area)
        val maxStructIdx = rooms.flatMap { rm -> rm.objects.map { it.structIndex } }.maxOrNull() ?: 0

        // Check for duplicate pointers
        val ptrToIndices = mutableMapOf<Int, MutableList<Int>>()
        for (i in 0..maxStructIdx) {
            val addr = r.readBankWord(bank, ptrs.structPtrTable + i * 2)
            ptrToIndices.getOrPut(addr) { mutableListOf() }.add(i)
        }
        val duplicates = ptrToIndices.filter { it.value.size > 1 }
        println("=== Duplicate struct pointers ===")
        if (duplicates.isEmpty()) println("  None found")
        for ((addr, indices) in duplicates) {
            println("  Addr $%04X: indices $indices".format(addr))
        }

        // Check for empty/tiny structs
        println("\n=== Small structs (0-1 cells) ===")
        val usedBy = mutableMapOf<Int, MutableList<Int>>()
        for (rm in rooms) {
            for (obj in rm.objects) {
                usedBy.getOrPut(obj.structIndex) { mutableListOf() }.add(rm.roomNumber)
            }
        }
        for (i in 0..maxStructIdx) {
            val struct = d.readStructure(area, i) ?: continue
            val cells = struct.rows.sumOf { it.macroIndices.size }
            if (cells <= 1) {
                val roomsUsing = usedBy[i]?.distinct() ?: emptyList()
                println("  Struct $i: $cells cells, used by rooms $roomsUsing")
                if (cells == 1) {
                    val macro = struct.rows[0].macroIndices[0]
                    println("    → 1x1 macro=$macro")
                }
            }
        }

        // Check for structs used only by room 9
        println("\n=== Structs exclusive to room 9 ===")
        for (obj in rooms.find { it.roomNumber == 9 }?.objects ?: emptyList()) {
            val roomsUsing = usedBy[obj.structIndex]?.distinct() ?: emptyList()
            if (roomsUsing == listOf(9)) {
                println("  Struct ${obj.structIndex}: exclusive to room 9")
            }
        }
    }

    @Test
    fun `struct 22 data and free space context`() {
        val r = rom ?: return
        val d = data ?: return

        val area = Area.BRINSTAR
        val bank = MetroidRomData.AREA_BANKS[area] ?: return
        val ptrs = d.readAreaPointers(area)
        val rooms = d.readAllRooms(area)
        val maxStructIdx = rooms.flatMap { rm -> rm.objects.map { it.structIndex } }.maxOrNull() ?: 0

        // Find struct 22's data address and size
        val struct22Addr = r.readBankWord(bank, ptrs.structPtrTable + 22 * 2)
        val struct22RomOff = r.bankAddressToRomOffset(bank, struct22Addr)

        // Measure struct sizes for all structs sorted by address
        data class StructInfo(val idx: Int, val cpuAddr: Int, val romOff: Int, val size: Int)
        val structInfos = (0..maxStructIdx).map { i ->
            val addr = r.readBankWord(bank, ptrs.structPtrTable + i * 2)
            val romOff = r.bankAddressToRomOffset(bank, addr)
            var pos = addr
            while (true) {
                val b = r.readBankByte(bank, pos)
                if (b == 0xFF) { pos++; break }
                val c = b and 0x0F
                pos += 1 + (if (c == 0) 16 else c)
            }
            StructInfo(i, addr, romOff, pos - addr)
        }.sortedBy { it.cpuAddr }

        val s22idx = structInfos.indexOfFirst { it.idx == 22 }
        println("=== Struct data layout near struct 22 ===")
        for (i in maxOf(0, s22idx - 3)..minOf(structInfos.size - 1, s22idx + 3)) {
            val si = structInfos[i]
            val nextOff = if (i + 1 < structInfos.size) structInfos[i + 1].romOff else -1
            val gap = if (nextOff >= 0) nextOff - (si.romOff + si.size) else -1
            val marker = if (si.idx == 22) " <<<" else ""
            println("  Struct %2d: CPU $%04X, ROM $%05X, %d bytes, gap=%d$marker".format(
                si.idx, si.cpuAddr, si.romOff, si.size, gap))
        }

        // Also show raw bytes around struct 22
        println("\nBytes around struct 22 (${struct22RomOff - 5} to ${struct22RomOff + 22}):")
        val start = struct22RomOff - 5
        val bytes = (start until start + 28).map { "$%02X".format(r.readByte(it)) }
        println("  ${bytes.joinToString(" ")}")
    }

    @Test
    fun `export 2 tile extension edits`() {
        val r = rom ?: return
        val d = data ?: return
        val pd = NesPatternDecoder(r)
        val renderer = MapRenderer(d, pd)

        val edits = mutableListOf(
            MacroEdit(4, 12, 2, 0),
            MacroEdit(12, 12, 2, 0),
        )

        val room = d.readRoom(Area.BRINSTAR, 9) ?: return
        val coverage = RomExporter.buildCoverageMap(d, room)

        var covCount = 0
        var uncCount = 0
        for (e in edits) {
            val idx = e.y * MapRenderer.ROOM_WIDTH_MACROS + e.x
            if (coverage[idx]) covCount++ else uncCount++
        }
        println("2-tile edits: $covCount covered, $uncCount uncovered")

        val editedRooms = mutableMapOf<String, RoomEdits>()
        editedRooms[roomKey(Area.BRINSTAR, 9)] = RoomEdits(macroEdits = edits)

        val exportData = r.copyRomData()
        val exportParser = NesRomParser(exportData)
        val result = RomExporter.exportAll(exportParser, r, d, renderer, editedRooms)

        println("Result: covered=${result.coveredPatched}, uncovered=${result.uncoveredPatched}")
        println("Errors: ${result.errors}")
        println("Success: ${result.success}")

        assertTrue(result.success, "Export should succeed: ${result.errors}")
        assertEquals(2, result.uncoveredPatched, "Both uncovered edits should be patched")

        // Verify the exported ROM has the edits
        val exportMd = MetroidRomData(exportParser)
        val exportRenderer = MapRenderer(exportMd, NesPatternDecoder(exportParser))
        val reread = exportMd.readRoom(Area.BRINSTAR, 9) ?: fail("Room should still exist")
        val rereadGrid = exportRenderer.buildMacroGrid(reread)

        for (edit in edits) {
            val actual = rereadGrid.get(edit.x, edit.y)
            assertEquals(edit.macroIndex, actual,
                "Edit at (${edit.x},${edit.y}): expected macro ${edit.macroIndex} got $actual")
        }
        println("All edits verified in exported ROM!")

        // Verify critical tables not corrupted
        val origPtrs = d.readAreaPointers(Area.BRINSTAR)
        val newPtrs = exportMd.readAreaPointers(Area.BRINSTAR)
        assertEquals(origPtrs.specItemsTable, newPtrs.specItemsTable, "specItemsTable corrupted!")
        assertEquals(origPtrs.macroDefs, newPtrs.macroDefs, "macroDefs corrupted!")
        println("Critical area pointers intact")
    }

    @Test
    fun `coverage map marks covered vs uncovered correctly`() {
        val d = data ?: return

        val room = d.readRoom(Area.BRINSTAR, 9) ?: return
        val coverage = RomExporter.buildCoverageMap(d, room)

        var coveredCount = 0
        var uncoveredCount = 0
        for (i in coverage.indices) {
            if (coverage[i]) coveredCount++ else uncoveredCount++
        }
        println("Room 9: $coveredCount covered, $uncoveredCount uncovered")
        assertTrue(coveredCount > 0)
        assertTrue(uncoveredCount > 0)
    }

    @Test
    fun `exported ROM preserves non-room data`() {
        val r = rom ?: return
        val d = data ?: return
        val pd = NesPatternDecoder(r)
        val renderer = MapRenderer(d, pd)

        val edits = mutableMapOf<String, RoomEdits>()
        edits[roomKey(Area.BRINSTAR, 9)] = RoomEdits(
            macroEdits = mutableListOf(MacroEdit(5, 5, 32, 0))
        )

        val exportData = r.copyRomData()
        val exportParser = NesRomParser(exportData)
        RomExporter.exportAll(exportParser, r, d, renderer, edits)

        // Verify critical tables weren't corrupted
        val exportMd = MetroidRomData(exportParser)
        val origPtrs = d.readAreaPointers(Area.BRINSTAR)
        val newPtrs = exportMd.readAreaPointers(Area.BRINSTAR)

        assertEquals(origPtrs.specItemsTable, newPtrs.specItemsTable, "specItemsTable corrupted")
        assertEquals(origPtrs.macroDefs, newPtrs.macroDefs, "macroDefs corrupted")
        assertEquals(origPtrs.enemyFramePtrTbl1, newPtrs.enemyFramePtrTbl1, "enemyFramePtrTbl1 corrupted")
        assertEquals(origPtrs.enemyFramePtrTbl2, newPtrs.enemyFramePtrTbl2, "enemyFramePtrTbl2 corrupted")
        assertEquals(origPtrs.enemyPlacePtrTbl, newPtrs.enemyPlacePtrTbl, "enemyPlacePtrTbl corrupted")
        assertEquals(origPtrs.enemyAnimIndexTbl, newPtrs.enemyAnimIndexTbl, "enemyAnimIndexTbl corrupted")

        // Verify all rooms in all OTHER areas are untouched
        for (area in listOf(Area.NORFAIR, Area.TOURIAN, Area.KRAID, Area.RIDLEY)) {
            val origAreaPtrs = d.readAreaPointers(area)
            val newAreaPtrs = exportMd.readAreaPointers(area)
            assertEquals(origAreaPtrs.roomPtrTable, newAreaPtrs.roomPtrTable,
                "${area.displayName} roomPtrTable changed!")
        }
    }
}
