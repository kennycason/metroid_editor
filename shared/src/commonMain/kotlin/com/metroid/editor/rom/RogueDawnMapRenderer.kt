package com.metroid.editor.rom

import com.metroid.editor.data.Area
import com.metroid.editor.data.Room
import com.metroid.editor.data.RoomObject
import com.metroid.editor.data.Structure
import com.metroid.editor.data.StructureRow
import com.metroid.editor.data.TileMacro

/**
 * Tile renderer for Rogue Dawn's MMC3 data layout.
 *
 * Rogue Dawn keeps the vanilla room object and structure formats, but puts each
 * area's structures/macros in PRG 8KB banks and uses CHR ROM for tile patterns.
 */
class RogueDawnMapRenderer(
    private val data: RogueDawnRomData,
    private val patternDecoder: NesPatternDecoder
) {
    fun renderRoom(room: Room): MapRenderer.RoomRenderResult {
        val pixels = IntArray(MapRenderer.ROOM_WIDTH_PX * MapRenderer.ROOM_HEIGHT_PX)
        val fullPalette = patternDecoder.readAreaPalette(room.area.bankNumber, 0)
        pixels.fill(fullPalette[0])

        val tileBuffer = IntArray(MapRenderer.NAMETABLE_WIDTH_TILES * MapRenderer.NAMETABLE_HEIGHT_TILES) { -1 }
        val attrBuffer = IntArray(MapRenderer.ROOM_WIDTH_MACROS * MapRenderer.ROOM_HEIGHT_MACROS) { room.palette }

        for (obj in room.objects) {
            placeObject(room.area, obj, tileBuffer, attrBuffer)
        }

        for (ty in 0 until MapRenderer.NAMETABLE_HEIGHT_TILES) {
            for (tx in 0 until MapRenderer.NAMETABLE_WIDTH_TILES) {
                val tileIndex = tileBuffer[ty * MapRenderer.NAMETABLE_WIDTH_TILES + tx]
                if (tileIndex < 0) continue

                val macroX = tx / 2
                val macroY = ty / 2
                val attr = if (macroX in 0 until MapRenderer.ROOM_WIDTH_MACROS &&
                    macroY in 0 until MapRenderer.ROOM_HEIGHT_MACROS
                ) {
                    attrBuffer[macroY * MapRenderer.ROOM_WIDTH_MACROS + macroX]
                } else {
                    room.palette
                }

                val tilePixels = decodeBgTile(room.area, tileIndex)
                val subPalette = patternDecoder.getSubPalette(fullPalette, attr and 0x03)
                val rendered = patternDecoder.renderTile(tilePixels, subPalette)
                blitTile(pixels, tx * MapRenderer.TILE_SIZE, ty * MapRenderer.TILE_SIZE, rendered)
            }
        }

        return MapRenderer.RoomRenderResult(
            pixels = pixels,
            width = MapRenderer.ROOM_WIDTH_PX,
            height = MapRenderer.ROOM_HEIGHT_PX,
            room = room
        )
    }

    fun buildMacroGrid(room: Room): MapRenderer.MacroGrid {
        val grid = MapRenderer.MacroGrid(roomPalette = room.palette)
        grid.attrs.fill(room.palette)

        for (obj in room.objects) {
            val structure = readStructure(room.area, obj.structIndex) ?: continue
            var macroRow = obj.posY
            for (row in structure.rows) {
                var macroCol = obj.posX + row.xOffset
                for (macroIndex in row.macroIndices) {
                    if (macroCol in 0 until MapRenderer.ROOM_WIDTH_MACROS &&
                        macroRow in 0 until MapRenderer.ROOM_HEIGHT_MACROS
                    ) {
                        grid.set(macroCol, macroRow, macroIndex)
                        grid.setAttr(macroCol, macroRow, obj.palette)
                    }
                    macroCol++
                }
                macroRow++
            }
        }

        return grid
    }

    fun readStructure(area: Area, structIndex: Int): Structure? {
        val dataBank = AREA_DATA_BANKS[area] ?: return null
        val structPointer = data.rom.readWord(data.prg8BankAddressToRomOffset(dataBank, STRUCT_POINTER_TABLE_ADDR + structIndex * 2))
        if (structPointer !in 0x8000 until 0xC000) return null

        val rows = mutableListOf<StructureRow>()
        var pos = structPointer
        repeat(MAX_STRUCTURE_ROWS) {
            val lengthByte = data.readPrg8BankByte(dataBank, pos)
            if (lengthByte == MetroidRomData.STRUCT_END) return Structure(structIndex, rows)

            val xOffset = (lengthByte shr 4) and 0x0F
            var count = lengthByte and 0x0F
            if (count == 0) count = 16

            val macros = (0 until count).map { data.readPrg8BankByte(dataBank, pos + 1 + it) }
            rows.add(StructureRow(xOffset, macros))
            pos += 1 + count
        }

        return null
    }

    fun readMacro(area: Area, macroIndex: Int): TileMacro? {
        val dataBank = AREA_DATA_BANKS[area] ?: return null
        val macroAddress = MACRO_DEFS_ADDR + macroIndex * 4
        if (macroAddress + 3 >= STRUCT_POINTER_TABLE_ADDR) return null
        return TileMacro(
            topLeft = data.readPrg8BankByte(dataBank, macroAddress),
            topRight = data.readPrg8BankByte(dataBank, macroAddress + 1),
            botLeft = data.readPrg8BankByte(dataBank, macroAddress + 2),
            botRight = data.readPrg8BankByte(dataBank, macroAddress + 3)
        )
    }

    private fun placeObject(
        area: Area,
        obj: RoomObject,
        tileBuffer: IntArray,
        attrBuffer: IntArray
    ) {
        val structure = readStructure(area, obj.structIndex) ?: return

        var macroRow = obj.posY
        for (row in structure.rows) {
            var macroCol = obj.posX + row.xOffset
            for (macroIndex in row.macroIndices) {
                val macro = readMacro(area, macroIndex) ?: continue
                val tileX = macroCol * 2
                val tileY = macroRow * 2

                setTile(tileBuffer, tileX, tileY, macro.topLeft)
                setTile(tileBuffer, tileX + 1, tileY, macro.topRight)
                setTile(tileBuffer, tileX, tileY + 1, macro.botLeft)
                setTile(tileBuffer, tileX + 1, tileY + 1, macro.botRight)

                if (macroCol in 0 until MapRenderer.ROOM_WIDTH_MACROS &&
                    macroRow in 0 until MapRenderer.ROOM_HEIGHT_MACROS
                ) {
                    attrBuffer[macroRow * MapRenderer.ROOM_WIDTH_MACROS + macroCol] = obj.palette
                }

                macroCol++
            }
            macroRow++
        }
    }

    private fun setTile(buffer: IntArray, x: Int, y: Int, tileIndex: Int) {
        if (x in 0 until MapRenderer.NAMETABLE_WIDTH_TILES &&
            y in 0 until MapRenderer.NAMETABLE_HEIGHT_TILES
        ) {
            buffer[y * MapRenderer.NAMETABLE_WIDTH_TILES + x] = tileIndex
        }
    }

    private fun decodeBgTile(area: Area, tileIndex: Int): IntArray {
        val chrPage = AREA_BG_CHR_4K_PAGE[area] ?: return IntArray(NesPatternDecoder.PIXELS_PER_TILE)
        val chrOffset = data.chrRomOffset() + chrPage * CHR_4K_BANK_SIZE + tileIndex * NesPatternDecoder.BYTES_PER_TILE
        if (chrOffset < data.chrRomOffset() || chrOffset + NesPatternDecoder.BYTES_PER_TILE > data.rom.romData.size) {
            return IntArray(NesPatternDecoder.PIXELS_PER_TILE)
        }
        return patternDecoder.decodeTile(data.rom.romData, chrOffset)
    }

    private fun blitTile(dest: IntArray, x: Int, y: Int, tilePixels: IntArray) {
        for (row in 0 until MapRenderer.TILE_SIZE) {
            val destY = y + row
            if (destY !in 0 until MapRenderer.ROOM_HEIGHT_PX) continue
            for (col in 0 until MapRenderer.TILE_SIZE) {
                val destX = x + col
                if (destX in 0 until MapRenderer.ROOM_WIDTH_PX) {
                    dest[destY * MapRenderer.ROOM_WIDTH_PX + destX] = tilePixels[row * MapRenderer.TILE_SIZE + col]
                }
            }
        }
    }

    companion object {
        private const val MACRO_DEFS_ADDR = 0x8000
        private const val STRUCT_POINTER_TABLE_ADDR = 0x8400
        private const val CHR_4K_BANK_SIZE = 0x1000
        private const val MAX_STRUCTURE_ROWS = 128

        val AREA_DATA_BANKS = mapOf(
            Area.BRINSTAR to 0x10,
            Area.NORFAIR to 0x12,
            Area.TOURIAN to 0x14,
            Area.KRAID to 0x16,
            Area.RIDLEY to 0x18
        )

        /**
         * First-pass background CHR pages from Rogue Dawn's fixed-bank CHR group table.
         * Each value is a 4KB page in CHR ROM.
         */
        val AREA_BG_CHR_4K_PAGE = mapOf(
            Area.BRINSTAR to 1,
            Area.NORFAIR to 2,
            Area.TOURIAN to 8,
            Area.KRAID to 14,
            Area.RIDLEY to 20
        )
    }
}
