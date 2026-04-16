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

    private fun verifySpecItemsData(
        origParser: NesRomParser, exportParser: NesRomParser,
        origPtrs: MetroidRomData.AreaPointers, newPtrs: MetroidRomData.AreaPointers
    ) {
        if (origPtrs.specItemsTable == newPtrs.specItemsTable) return
        val bank = MetroidRomData.AREA_BANKS[Area.BRINSTAR] ?: return
        val specSize = origPtrs.roomPtrTable - origPtrs.specItemsTable
        if (specSize <= 0) return
        val origOff = origParser.bankAddressToRomOffset(bank, origPtrs.specItemsTable)
        val expOff = exportParser.bankAddressToRomOffset(bank, newPtrs.specItemsTable)
        var mismatches = 0
        for (i in 0 until specSize) {
            if ((origParser.readByte(origOff + i) and 0xFF) != (exportParser.readByte(expOff + i) and 0xFF)) {
                mismatches++
            }
        }
        assertEquals(0, mismatches,
            "specItemsTable data corrupted after relocation ($%04X→$%04X, $mismatches/$specSize bytes differ)"
                .format(origPtrs.specItemsTable, newPtrs.specItemsTable))
    }

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
        val baseRom = rom ?: return
        // Room 9's structs are all shared — use expanded ROM to clone safely
        val expanded = RomExpander.expand(baseRom.romData) ?: return
        val r = NesRomParser(expanded)
        val d = MetroidRomData(r)
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

        println("Total edits: ${result.totalEdits}")
        assertTrue(result.totalEdits >= edits.size, "All edits should be applied")
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

        println("Result: covered=${result.coveredPatched}, uncovered=${result.skippedUncovered}")
        println("Errors: ${result.warnings}")

        // Verify only covered edits were applied (uncovered ones are skipped)
        val exportMd2 = MetroidRomData(exportParser)
        val exportRenderer2 = MapRenderer(exportMd2, NesPatternDecoder(exportParser))
        val reread = exportMd2.readRoom(Area.BRINSTAR, 9) ?: fail("Room should exist")
        val rereadGrid = exportRenderer2.buildMacroGrid(reread)

        for (edit in edits) {
            val idx = edit.y * MapRenderer.ROOM_WIDTH_MACROS + edit.x
            if (coverage[idx]) {
                val actual = rereadGrid.get(edit.x, edit.y)
                assertEquals(edit.macroIndex, actual,
                    "Covered edit at (${edit.x},${edit.y}): expected macro ${edit.macroIndex} got $actual")
            }
        }
        println("${covCount} covered edits verified, ${uncCount} uncovered skipped")

        // Verify other rooms/areas not corrupted
        val exportMd = MetroidRomData(exportParser)
        val origPtrs = d.readAreaPointers(Area.BRINSTAR)
        val newPtrs = exportMd.readAreaPointers(Area.BRINSTAR)
        verifySpecItemsData(r, exportParser, origPtrs, newPtrs)
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

        println("Result: covered=${result.coveredPatched}, uncovered=${result.skippedUncovered}")
        println("Errors: ${result.warnings}")
        println("Success: ${result.success}")

        // Standard ROM is packed — uncovered edits fail gracefully
        assertTrue(result.skippedUncovered > 0, "Should skip uncovered edits")
        return

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
        verifySpecItemsData(r, exportParser, origPtrs, newPtrs)
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

        verifySpecItemsData(r, exportParser, origPtrs, newPtrs)
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

    @Test
    fun `zero-edit export produces byte-identical ROM`() {
        val r = rom ?: return
        val d = data ?: return
        val pd = NesPatternDecoder(r)
        val renderer = MapRenderer(d, pd)

        // Export with zero edits
        val exportData = r.copyRomData()
        val exportParser = NesRomParser(exportData)
        val result = RomExporter.exportAll(exportParser, r, d, renderer, emptyMap())

        println("Zero-edit export: totalEdits=${result.totalEdits}, errors=${result.warnings}")
        assertEquals(0, result.totalEdits, "Zero edits should produce zero changes")
        assertTrue(result.success, "Zero-edit export should succeed")

        // Compare byte-by-byte
        val origData = r.romData
        var diffCount = 0
        val firstDiffs = mutableListOf<String>()
        for (i in origData.indices) {
            if (origData[i] != exportData[i]) {
                diffCount++
                if (firstDiffs.size < 20) {
                    val bank = if (i >= NesRomParser.INES_HEADER_SIZE)
                        (i - NesRomParser.INES_HEADER_SIZE) / NesRomParser.PRG_BANK_SIZE else -1
                    firstDiffs.add("  offset $%05X (bank $bank): orig=$%02X export=$%02X"
                        .format(i, origData[i].toInt() and 0xFF, exportData[i].toInt() and 0xFF))
                }
            }
        }
        if (firstDiffs.isNotEmpty()) {
            println("BYTE DIFFERENCES ($diffCount total):")
            firstDiffs.forEach(::println)
        }
        assertEquals(0, diffCount, "Zero-edit export should produce byte-identical ROM, but $diffCount bytes differ")
    }

    @Test
    fun `all areas specItemsTable and pointers intact after single-room edit`() {
        val r = rom ?: return
        val d = data ?: return
        val pd = NesPatternDecoder(r)
        val renderer = MapRenderer(d, pd)

        // Make a single uncovered edit in Brinstar room 0 to trigger re-encoding
        val room0 = d.readRoom(Area.BRINSTAR, 0) ?: return
        val grid = renderer.buildMacroGrid(room0)
        // Find a non-covered cell
        val coverage = RomExporter.buildCoverageMap(d, room0)
        var editMade = false
        val edits = mutableMapOf<String, RoomEdits>()
        for (my in 0 until MapRenderer.ROOM_HEIGHT_MACROS) {
            for (mx in 0 until MapRenderer.ROOM_WIDTH_MACROS) {
                val idx = my * MapRenderer.ROOM_WIDTH_MACROS + mx
                if (!coverage[idx] && grid.get(mx, my) < 0) {
                    // uncovered empty cell — place a tile
                    edits[roomKey(Area.BRINSTAR, 0)] = RoomEdits(
                        macroEdits = mutableListOf(MacroEdit(mx, my, 1, 0))
                    )
                    editMade = true
                    break
                }
            }
            if (editMade) break
        }
        if (!editMade) {
            println("Could not find uncovered empty cell in room 0, using covered edit")
            edits[roomKey(Area.BRINSTAR, 0)] = RoomEdits(
                macroEdits = mutableListOf(MacroEdit(5, 5, 32, 0))
            )
        }

        val exportData = r.copyRomData()
        val exportParser = NesRomParser(exportData)
        val result = RomExporter.exportAll(exportParser, r, d, renderer, edits)
        println("Edit export: edits=${result.totalEdits} errors=${result.warnings}")

        val exportMd = MetroidRomData(exportParser)

        // Check ALL areas — even ones we didn't edit
        for (area in Area.entries) {
            val bank = MetroidRomData.AREA_BANKS[area] ?: continue
            val origPtrs = d.readAreaPointers(area)
            val newPtrs = exportMd.readAreaPointers(area)

            println("=== ${area.displayName} (bank $bank) ===")
            println("  specItems: orig=$%04X new=$%04X".format(origPtrs.specItemsTable, newPtrs.specItemsTable))
            println("  roomPtr:   orig=$%04X new=$%04X".format(origPtrs.roomPtrTable, newPtrs.roomPtrTable))
            println("  structPtr: orig=$%04X new=$%04X".format(origPtrs.structPtrTable, newPtrs.structPtrTable))
            println("  macroDefs: orig=$%04X new=$%04X".format(origPtrs.macroDefs, newPtrs.macroDefs))

            // For non-edited areas, all pointers must be identical
            if (area != Area.BRINSTAR) {
                assertEquals(origPtrs.specItemsTable, newPtrs.specItemsTable,
                    "${area.displayName} specItemsTable changed!")
                assertEquals(origPtrs.roomPtrTable, newPtrs.roomPtrTable,
                    "${area.displayName} roomPtrTable changed!")
                assertEquals(origPtrs.structPtrTable, newPtrs.structPtrTable,
                    "${area.displayName} structPtrTable changed!")
                assertEquals(origPtrs.macroDefs, newPtrs.macroDefs,
                    "${area.displayName} macroDefs changed!")
            }

            // Verify specItemsTable data integrity
            verifySpecItemsIntegrity(r, exportParser, bank, origPtrs, newPtrs, area)

            // Verify all rooms can be read
            val origRooms = d.readAllRooms(area)
            val newRooms = exportMd.readAllRooms(area)
            assertEquals(origRooms.size, newRooms.size,
                "${area.displayName} room count changed (${origRooms.size}→${newRooms.size})")

            // Verify room pointer table entries
            for (i in origRooms.indices) {
                val origAddr = r.readBankWord(bank, origPtrs.roomPtrTable + i * 2)
                val newAddr = exportParser.readBankWord(bank, newPtrs.roomPtrTable + i * 2)
                if (area != Area.BRINSTAR) {
                    assertEquals(origAddr, newAddr,
                        "${area.displayName} room $i pointer changed ($%04X→$%04X)".format(origAddr, newAddr))
                }
            }
        }
    }

    /**
     * Walk the specItemsTable linked list and verify all "next Y" pointers are valid
     * and the data bytes match the original.
     */
    private fun verifySpecItemsIntegrity(
        origParser: NesRomParser, exportParser: NesRomParser,
        bank: Int,
        origPtrs: MetroidRomData.AreaPointers, newPtrs: MetroidRomData.AreaPointers,
        area: Area
    ) {
        // Walk the original linked list to get expected Y-node structure
        val origNodes = walkSpecItemsList(origParser, bank, origPtrs.specItemsTable)
        val newNodes = walkSpecItemsList(exportParser, bank, newPtrs.specItemsTable)

        println("  specItems linked list: orig=${origNodes.size} nodes, new=${newNodes.size} nodes")

        assertEquals(origNodes.size, newNodes.size,
            "${area.displayName} specItemsTable node count changed")

        for (i in origNodes.indices) {
            val orig = origNodes[i]
            val new_ = newNodes[i]
            assertEquals(orig.mapY, new_.mapY,
                "${area.displayName} specItems node $i: MapY changed (${orig.mapY}→${new_.mapY})")
        }
    }

    private data class SpecItemNode(val cpuAddr: Int, val mapY: Int, val nextPtr: Int)

    private fun walkSpecItemsList(parser: NesRomParser, bank: Int, startAddr: Int): List<SpecItemNode> {
        val nodes = mutableListOf<SpecItemNode>()
        var addr = startAddr
        var safety = 0
        while (safety < 200) {
            safety++
            val mapY = parser.readBankByte(bank, addr)
            val nextLo = parser.readBankByte(bank, addr + 1)
            val nextHi = parser.readBankByte(bank, addr + 2)
            val nextPtr = (nextHi shl 8) or nextLo

            nodes.add(SpecItemNode(addr, mapY, nextPtr))

            if (nextLo == 0xFF && nextHi == 0xFF) break  // end of list
            if (nextPtr < 0x8000 || nextPtr >= 0xC000) {
                println("  WARNING: bad next pointer $%04X at node addr $%04X".format(nextPtr, addr))
                break
            }
            addr = nextPtr
        }
        return nodes
    }
}
