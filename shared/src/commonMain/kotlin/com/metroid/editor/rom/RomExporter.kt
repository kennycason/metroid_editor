package com.metroid.editor.rom

import com.metroid.editor.data.*
import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

object RomExporter {

    data class ExportResult(
        val totalEdits: Int,
        val coveredPatched: Int,
        val skippedUncovered: Int,
        val areasProcessed: Int,
        val warnings: List<String>
    ) {
        val success: Boolean get() = skippedUncovered == 0
    }

    /**
     * Export all edits to the ROM.
     *
     * Covered edits (on existing struct tiles) are always applied via in-place byte patching.
     * Uncovered edits (on empty cells) cannot be applied without ROM expansion — these are
     * reported as skipped and cause success=false.
     */
    fun exportAll(
        exportParser: NesRomParser,
        origParser: NesRomParser,
        md: MetroidRomData,
        renderer: MapRenderer,
        editedRooms: Map<String, RoomEdits>
    ): ExportResult {
        val editsByArea = groupEditsByArea(editedRooms)
        var totalEdits = 0
        var totalSkipped = 0
        val warnings = mutableListOf<String>()

        for ((area, areaEdits) in editsByArea) {
            val result = AreaRewriter.rewriteArea(
                exportParser, origParser, md, renderer, area, areaEdits
            )
            totalEdits += result.editCount
            totalSkipped += result.skippedUncovered
            warnings.addAll(result.errors)
            logger.info { "${area.displayName}: ${result.editCount} applied, " +
                    "${result.skippedUncovered} skipped" }
        }

        return ExportResult(totalEdits, totalEdits, totalSkipped, editsByArea.size, warnings)
    }

    /**
     * Build a coverage map showing which grid cells are covered by existing room objects.
     * Used by the editor UI and tests.
     */
    fun buildCoverageMap(md: MetroidRomData, room: Room): BooleanArray {
        val coverage = BooleanArray(MapRenderer.ROOM_WIDTH_MACROS * MapRenderer.ROOM_HEIGHT_MACROS)
        for (obj in room.objects) {
            val structure = md.readStructure(room.area, obj.structIndex) ?: continue
            var macroRow = obj.posY
            for (row in structure.rows) {
                var macroCol = obj.posX + row.xOffset
                for (i in row.macroIndices.indices) {
                    if (macroCol in 0 until MapRenderer.ROOM_WIDTH_MACROS &&
                        macroRow in 0 until MapRenderer.ROOM_HEIGHT_MACROS
                    ) {
                        coverage[macroRow * MapRenderer.ROOM_WIDTH_MACROS + macroCol] = true
                    }
                    macroCol++
                }
                macroRow++
            }
        }
        return coverage
    }

    /** Exposed for testing — computes the lowest CPU address that is data (not code). */
    internal fun dataRegionFloorForTest(ptrs: MetroidRomData.AreaPointers): Int {
        return minOf(
            ptrs.specItemsTable, ptrs.roomPtrTable, ptrs.structPtrTable,
            ptrs.macroDefs, ptrs.enemyFramePtrTbl1, ptrs.enemyFramePtrTbl2,
            ptrs.enemyPlacePtrTbl, ptrs.enemyAnimIndexTbl
        )
    }

    /** Exposed for testing — measures struct data size including FF terminator. */
    internal fun measureStructDataSizeForTest(parser: NesRomParser, bank: Int, cpuAddr: Int): Int {
        var pos = cpuAddr
        while (true) {
            val b = parser.readBankByte(bank, pos)
            if (b == 0xFF) { pos++; break }
            val count = b and 0x0F
            pos += 1 + (if (count == 0) 16 else count)
        }
        return pos - cpuAddr
    }

    private fun groupEditsByArea(
        editedRooms: Map<String, RoomEdits>
    ): Map<Area, Map<String, RoomEdits>> {
        val result = mutableMapOf<Area, MutableMap<String, RoomEdits>>()
        for ((key, edits) in editedRooms) {
            if (edits.macroEdits.isEmpty()) continue
            val parts = key.split(":")
            val areaId = parts[0].toIntOrNull() ?: continue
            val area = Area.entries.getOrNull(areaId) ?: continue
            result.getOrPut(area) { mutableMapOf() }[key] = edits
        }
        return result
    }
}
