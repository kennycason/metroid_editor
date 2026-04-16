package com.metroid.editor.rom

import com.metroid.editor.data.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.io.File

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class Room0Test {
    @Test
    fun `diagnose room 0 export with single tile`() {
        val romFile = NesRomParserTest.findRomFile() ?: run { println("ROM not found"); return }
        val rawData = romFile.readBytes()
        val romBytes = NesRomParser.ensureHeader(rawData)
        val origParser = NesRomParser(romBytes)
        val md = MetroidRomData(origParser)
        val pd = NesPatternDecoder(origParser)
        val renderer = MapRenderer(md, pd)

        val room = md.readRoom(Area.BRINSTAR, 0) ?: run { println("Room 0 not found"); return }
        val grid = renderer.buildMacroGrid(room)
        val coverage = RomExporter.buildCoverageMap(md, room)
        val allRooms = md.readAllRooms(Area.BRINSTAR)
        
        println("=== Room 0 info ===")
        println("  Objects: ${room.objects.size}, rawData: ${room.rawData.size} bytes")
        
        // Find struct sharing
        val structUsage = mutableMapOf<Int, MutableSet<Int>>()
        for (r in allRooms) {
            for (obj in r.objects) {
                structUsage.getOrPut(obj.structIndex) { mutableSetOf() }.add(r.roomNumber)
            }
        }
        
        val room0ExclusiveStructs = room.objects.map { it.structIndex }
            .filter { (structUsage[it]?.size ?: 0) == 1 }
        println("  Room-exclusive structs: $room0ExclusiveStructs")
        
        // Find an uncovered position
        var editX = -1; var editY = -1
        for (my in 0 until MapRenderer.ROOM_HEIGHT_MACROS) {
            for (mx in 0 until MapRenderer.ROOM_WIDTH_MACROS) {
                val macro = grid.get(mx, my)
                if (macro < 0 && !coverage[my * MapRenderer.ROOM_WIDTH_MACROS + mx]) {
                    editX = mx; editY = my; break
                }
            }
            if (editX >= 0) break
        }
        
        println("  First uncovered empty cell: ($editX, $editY)")
        
        if (editX < 0) { println("No uncovered cells found"); return }
        
        // Try single tile edit
        val edits = mutableMapOf<String, RoomEdits>()
        edits[roomKey(Area.BRINSTAR, 0)] = RoomEdits(
            macroEdits = mutableListOf(MacroEdit(editX, editY, 2, 0))
        )
        
        val exportData = origParser.copyRomData()
        val exportParser = NesRomParser(exportData)
        val result = RomExporter.exportAll(exportParser, origParser, md, renderer, edits)
        
        println("  Export result: success=${result.success} covered=${result.coveredPatched} uncovered=${result.skippedUncovered}")
        println("  Errors: ${result.warnings}")
        
        val expMd = MetroidRomData(exportParser)
        val origPtrs = md.readAreaPointers(Area.BRINSTAR)
        val newPtrs = expMd.readAreaPointers(Area.BRINSTAR)
        println("  specItemsTable: $%04X → $%04X".format(origPtrs.specItemsTable, newPtrs.specItemsTable))
        println("  roomPtrTable:   $%04X → $%04X".format(origPtrs.roomPtrTable, newPtrs.roomPtrTable))
        println("  structPtrTable: $%04X → $%04X".format(origPtrs.structPtrTable, newPtrs.structPtrTable))
        println("  macroDefs:      $%04X → $%04X ${if(origPtrs.macroDefs != newPtrs.macroDefs) "CHANGED!" else "OK"}".format(origPtrs.macroDefs, newPtrs.macroDefs))
        
        // Check ALL rooms' raw data
        val origRooms = md.readAllRooms(Area.BRINSTAR)
        val expRooms = expMd.readAllRooms(Area.BRINSTAR)
        var badRooms = 0
        for (origR in origRooms) {
            if (origR.roomNumber == 0) continue
            val expR = expRooms.find { it.roomNumber == origR.roomNumber }
            if (expR == null) { println("  Room ${origR.roomNumber}: MISSING!"); badRooms++; continue }
            if (!origR.rawData.contentEquals(expR.rawData)) {
                println("  Room ${origR.roomNumber}: RAW DATA CHANGED (${origR.rawData.size} → ${expR.rawData.size} bytes)")
                badRooms++
            }
        }
        if (badRooms == 0) println("  All rooms OK")
        
        // Check struct data for room 0's structs
        println("  Struct data changes:")
        for (obj in room.objects) {
            val origS = md.readStructure(Area.BRINSTAR, obj.structIndex)
            val expS = expMd.readStructure(Area.BRINSTAR, obj.structIndex)
            if (origS == null || expS == null) continue
            if (origS.rows.size != expS.rows.size || origS.rows.zip(expS.rows).any { (a,b) -> a.macroIndices != b.macroIndices || a.xOffset != b.xOffset }) {
                println("    Struct ${obj.structIndex}: CHANGED (${origS.rows.size} rows → ${expS.rows.size} rows)")
            }
        }
        
        val tmpFile = File("/tmp/metroid_test_room0.nes")
        tmpFile.writeBytes(exportData)
        println("  Written to: ${tmpFile.absolutePath}")
    }
}
