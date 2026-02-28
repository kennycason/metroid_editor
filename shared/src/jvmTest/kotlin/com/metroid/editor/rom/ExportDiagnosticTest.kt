package com.metroid.editor.rom

import com.metroid.editor.data.Area
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

        val exportData = r.copyRomData()
        val exportParser = NesRomParser(exportData)

        val room = d.readRoom(Area.BRINSTAR, 9) ?: return
        val origGrid = renderer.buildMacroGrid(room)
        val editedGrid = origGrid.copy()

        // Find covered positions with non-zero macros and change them
        val coverage = RomExporter.buildCoverageMap(d, room)
        var editsApplied = 0
        for (my in 0 until MapRenderer.ROOM_HEIGHT_MACROS) {
            for (mx in 0 until MapRenderer.ROOM_WIDTH_MACROS) {
                if (coverage[my * MapRenderer.ROOM_WIDTH_MACROS + mx]) {
                    val orig = origGrid.get(mx, my)
                    if (orig >= 0 && orig != 32) {
                        editedGrid.set(mx, my, 32)
                        editsApplied++
                        if (editsApplied >= 5) break
                    }
                }
            }
            if (editsApplied >= 5) break
        }

        println("Applied $editsApplied edits on covered positions")
        assertTrue(editsApplied > 0, "Should find covered positions to edit")

        val result = RomExporter.exportRoom(exportParser, d, room, origGrid, editedGrid)
        println("Patched: ${result.patchedCount}, Skipped: ${result.skippedCount}")

        assertEquals(editsApplied, result.patchedCount, "All covered edits should be patched")
        assertEquals(0, result.skippedCount, "No skipped edits for covered positions")

        val changedBytes = r.romData.zip(exportData.toList()).count { (a, b) -> a != b }
        println("ROM bytes changed: $changedBytes")
        assertTrue(changedBytes > 0, "Export ROM should differ from original")
        assertTrue(changedBytes >= editsApplied, "Should have at least $editsApplied bytes changed")
    }

    @Test
    fun `export uncovered positions reports skipped`() {
        val r = rom ?: return
        val d = data ?: return
        val pd = NesPatternDecoder(r)
        val renderer = MapRenderer(d, pd)

        val exportData = r.copyRomData()
        val exportParser = NesRomParser(exportData)

        val room = d.readRoom(Area.BRINSTAR, 9) ?: return
        val origGrid = renderer.buildMacroGrid(room)
        val editedGrid = origGrid.copy()

        // Edit uncovered (background) positions
        val coverage = RomExporter.buildCoverageMap(d, room)
        var uncoveredEdits = 0
        for (my in 0 until MapRenderer.ROOM_HEIGHT_MACROS) {
            for (mx in 0 until MapRenderer.ROOM_WIDTH_MACROS) {
                if (!coverage[my * MapRenderer.ROOM_WIDTH_MACROS + mx]) {
                    editedGrid.set(mx, my, 32)
                    uncoveredEdits++
                    if (uncoveredEdits >= 3) break
                }
            }
            if (uncoveredEdits >= 3) break
        }

        println("Applied $uncoveredEdits edits on uncovered positions")
        assertTrue(uncoveredEdits > 0)

        val result = RomExporter.exportRoom(exportParser, d, room, origGrid, editedGrid)
        println("Patched: ${result.patchedCount}, Skipped: ${result.skippedCount}")

        assertEquals(0, result.patchedCount)
        assertEquals(uncoveredEdits, result.skippedCount)
        assertTrue(result.skippedPositions.isNotEmpty())
    }

    @Test
    fun `coverage map marks covered vs uncovered correctly`() {
        val r = rom ?: return
        val d = data ?: return
        val pd = NesPatternDecoder(r)
        val renderer = MapRenderer(d, pd)

        val room = d.readRoom(Area.BRINSTAR, 9) ?: return
        val grid = renderer.buildMacroGrid(room)
        val coverage = RomExporter.buildCoverageMap(d, room)

        var coveredCount = 0
        var uncoveredCount = 0
        for (my in 0 until MapRenderer.ROOM_HEIGHT_MACROS) {
            for (mx in 0 until MapRenderer.ROOM_WIDTH_MACROS) {
                val idx = my * MapRenderer.ROOM_WIDTH_MACROS + mx
                if (coverage[idx]) {
                    coveredCount++
                    assertTrue(grid.get(mx, my) >= 0,
                        "Covered position ($mx,$my) should have a macro")
                } else {
                    uncoveredCount++
                }
            }
        }
        println("Room 9: $coveredCount covered, $uncoveredCount uncovered (background)")
        assertTrue(coveredCount > 0, "Should have some covered positions")
        assertTrue(uncoveredCount > 0, "Should have some uncovered positions")
    }
}
