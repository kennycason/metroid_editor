package com.metroid.editor.rom

import com.metroid.editor.data.*

/**
 * Handles exporting edited room data back into ROM.
 *
 * Metroid NES rooms are built from objects → structures → macros → tiles.
 * Positions covered by objects can be patched by modifying the macro index byte
 * in the structure data. Positions that are originally empty (background) have no
 * structure byte to modify — they would require adding new objects + structures,
 * which needs free ROM space that may not exist.
 *
 * This exporter:
 * - Patches covered positions in-place (fast, reliable)
 * - Reports uncovered positions that couldn't be exported
 */
object RomExporter {

    data class ExportResult(
        val patchedCount: Int,
        val skippedCount: Int,
        val skippedPositions: List<Pair<Int, Int>>
    )

    fun exportRoom(
        exportParser: NesRomParser,
        md: MetroidRomData,
        room: Room,
        origGrid: MapRenderer.MacroGrid,
        editedGrid: MapRenderer.MacroGrid
    ): ExportResult {
        val bank = MetroidRomData.AREA_BANKS[room.area] ?: return ExportResult(0, 0, emptyList())

        val changes = mutableListOf<GridChange>()
        for (my in 0 until MapRenderer.ROOM_HEIGHT_MACROS) {
            for (mx in 0 until MapRenderer.ROOM_WIDTH_MACROS) {
                val oldMacro = origGrid.get(mx, my)
                val newMacro = editedGrid.get(mx, my)
                if (oldMacro != newMacro && newMacro >= 0) {
                    changes.add(GridChange(mx, my, oldMacro, newMacro))
                }
            }
        }
        if (changes.isEmpty()) return ExportResult(0, 0, emptyList())

        val ptrs = md.readAreaPointers(room.area)
        val provMap = buildProvenanceMap(exportParser, md, room, bank, ptrs)

        var patched = 0
        val skipped = mutableListOf<Pair<Int, Int>>()

        for (c in changes) {
            val romOffset = provMap[packKey(c.x, c.y)]
            if (romOffset != null) {
                exportParser.writeByte(romOffset, c.newMacro and 0xFF)
                patched++
            } else {
                skipped.add(c.x to c.y)
            }
        }

        return ExportResult(patched, skipped.size, skipped)
    }

    /**
     * Build a coverage map for a room: true = position has structure coverage (editable for export).
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

    private data class GridChange(val x: Int, val y: Int, val oldMacro: Int, val newMacro: Int)

    private fun packKey(x: Int, y: Int): Long = x.toLong() or (y.toLong() shl 32)

    private fun buildProvenanceMap(
        parser: NesRomParser,
        md: MetroidRomData,
        room: Room,
        bank: Int,
        ptrs: MetroidRomData.AreaPointers
    ): Map<Long, Int> {
        val provMap = mutableMapOf<Long, Int>()
        for (obj in room.objects) {
            val structure = md.readStructure(room.area, obj.structIndex) ?: continue
            val structAddr = parser.readBankWord(bank, ptrs.structPtrTable + obj.structIndex * 2)
            if (structAddr < 0x8000 || structAddr >= 0xC000) continue

            var structPos = structAddr
            var macroRow = obj.posY
            for (row in structure.rows) {
                var macroCol = obj.posX + row.xOffset
                val rowDataStart = structPos + 1
                for (i in row.macroIndices.indices) {
                    if (macroCol in 0 until MapRenderer.ROOM_WIDTH_MACROS &&
                        macroRow in 0 until MapRenderer.ROOM_HEIGHT_MACROS
                    ) {
                        provMap[packKey(macroCol, macroRow)] =
                            parser.bankAddressToRomOffset(bank, rowDataStart + i)
                    }
                    macroCol++
                }
                structPos += 1 + row.macroIndices.size
                macroRow++
            }
        }
        return provMap
    }
}
