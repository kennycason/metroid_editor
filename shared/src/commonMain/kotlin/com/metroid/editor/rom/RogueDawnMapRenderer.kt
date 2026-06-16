package com.metroid.editor.rom

import com.metroid.editor.data.Area
import com.metroid.editor.data.Room
import com.metroid.editor.data.RoomObject
import com.metroid.editor.data.Structure
import com.metroid.editor.data.StructureRow
import com.metroid.editor.data.TileMacro
import com.metroid.editor.data.WorldMapCell
import com.metroid.editor.data.roomKey

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
    private data class ResolvedWorldCell(
        val cell: WorldMapCell,
        val area: Area
    )

    fun renderRoom(room: Room): MapRenderer.RoomRenderResult {
        val pixels = IntArray(MapRenderer.ROOM_WIDTH_PX * MapRenderer.ROOM_HEIGHT_PX)
        val fullPalette = patternDecoder.readAreaPalette(room.area.bankNumber, 0)
        pixels.fill(fullPalette[0])

        val tileBuffer = IntArray(MapRenderer.NAMETABLE_WIDTH_TILES * MapRenderer.NAMETABLE_HEIGHT_TILES) { -1 }
        val attrBuffer = IntArray(MapRenderer.ROOM_WIDTH_MACROS * MapRenderer.ROOM_HEIGHT_MACROS) { room.palette }

        for (obj in room.objects) {
            placeObject(room.area, obj, tileBuffer, attrBuffer)
        }

        val bgChrBanks = backgroundChr1kBanks(room) ?: IntArray(0)
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

                val tilePixels = decodeBgTile(bgChrBanks, tileIndex)
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

    fun backgroundChrTableIndex(room: Room): Int? {
        return readRoomChrTableIndex(room)
            ?: ROOM_BG_CHR_TABLE_INDEX_OVERRIDES[room.area]?.get(room.roomNumber)
            ?: AREA_BG_CHR_TABLE_INDEX[room.area]
    }

    fun backgroundChr1kBanks(room: Room): IntArray? {
        val tableIndex = backgroundChrTableIndex(room) ?: return null
        return backgroundChr1kBanksForTableIndex(tableIndex)
    }

    fun backgroundChr1kBanks(area: Area): IntArray? {
        val tableIndex = AREA_BG_CHR_TABLE_INDEX[area] ?: return null
        return backgroundChr1kBanksForTableIndex(tableIndex)
    }

    fun renderFullWorldMap(backgroundColor: Int = 0xFF050505.toInt()): MapRenderer.FullWorldMapRenderResult? {
        val renderableCells = resolveWorldMapCells(data.readWorldMap())
        if (renderableCells.isEmpty()) return null

        val bounds = MetroidRomData.MapBounds(
            minX = renderableCells.minOf { it.cell.x },
            maxX = renderableCells.maxOf { it.cell.x },
            minY = renderableCells.minOf { it.cell.y },
            maxY = renderableCells.maxOf { it.cell.y }
        )

        val width = bounds.width * MapRenderer.ROOM_WIDTH_PX
        val height = bounds.height * MapRenderer.ROOM_HEIGHT_PX
        val pixels = IntArray(width * height) { backgroundColor }

        val roomCache = mutableMapOf<String, Room?>()
        var placedRooms = 0
        var skippedRooms = 0

        for ((cell, area) in renderableCells) {
            val key = roomKey(area, cell.roomNumber)
            val room = roomCache.getOrPut(key) {
                data.readRoom(area, cell.roomNumber)
            }

            if (room == null) {
                skippedRooms++
                continue
            }

            val rendered = renderRoom(room)
            val destX = (cell.x - bounds.minX) * MapRenderer.ROOM_WIDTH_PX
            val destY = (cell.y - bounds.minY) * MapRenderer.ROOM_HEIGHT_PX
            blitPixels(rendered.pixels, rendered.width, rendered.height, pixels, width, height, destX, destY)
            placedRooms++
        }

        return MapRenderer.FullWorldMapRenderResult(
            pixels = pixels,
            width = width,
            height = height,
            bounds = bounds,
            placedRooms = placedRooms,
            skippedRooms = skippedRooms
        )
    }

    private fun resolveWorldMapCells(cells: List<WorldMapCell>): List<ResolvedWorldCell> {
        val roomCounts = Area.entries.associateWith { data.getRoomCount(it) }

        return cells.mapNotNull { cell ->
            if (cell.isEmpty) return@mapNotNull null
            val area = rogueDawnAreaAt(cell.x, cell.y) ?: return@mapNotNull null
            if (cell.roomNumber !in 0 until roomCounts.getValue(area)) return@mapNotNull null
            ResolvedWorldCell(cell, area)
        }
    }

    internal fun rogueDawnAreaAt(x: Int, y: Int): Area? {
        if (x !in 0 until MetroidRomData.WORLD_MAP_WIDTH ||
            y !in 0 until MetroidRomData.WORLD_MAP_HEIGHT
        ) {
            return null
        }

        return when (ROGUE_DAWN_MAP_AREA[y][x]) {
            'B' -> Area.BRINSTAR
            'N' -> Area.NORFAIR
            'T' -> Area.TOURIAN
            'K' -> Area.KRAID
            'R' -> Area.RIDLEY
            else -> null
        }
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

    private fun decodeBgTile(bgChrBanks: IntArray, tileIndex: Int): IntArray {
        val chr1kBank = bgChrBanks
            .getOrNull(tileIndex / TILES_PER_1K_BANK)
            ?: return IntArray(NesPatternDecoder.PIXELS_PER_TILE)
        val tileInBank = tileIndex and 0x3F
        val chrOffset = data.chrRomOffset() + chr1kBank * CHR_1K_BANK_SIZE + tileInBank * NesPatternDecoder.BYTES_PER_TILE
        if (chrOffset < data.chrRomOffset() || chrOffset + NesPatternDecoder.BYTES_PER_TILE > data.rom.romData.size) {
            return IntArray(NesPatternDecoder.PIXELS_PER_TILE)
        }
        return patternDecoder.decodeTile(data.rom.romData, chrOffset)
    }

    private fun backgroundChr1kBanksForTableIndex(tableIndex: Int): IntArray {
        return CHR_BANK_TABLE_ADDRS.map { tableAddr ->
            data.readPrg8BankByte(CHR_BANK_TABLE_PRG8_BANK, tableAddr + tableIndex)
        }.toIntArray()
    }

    private fun readRoomChrTableIndex(room: Room): Int? {
        val trailer = data.readRoomTrailer(room)
        var found: Int? = null
        var index = 0
        while (index <= trailer.size - ROOM_CHR_TABLE_SET_PATTERN.size - 2) {
            if (trailer[index] == LDA_IMMEDIATE_OPCODE &&
                trailer.copyOfRange(index + 2, index + 2 + ROOM_CHR_TABLE_SET_PATTERN.size)
                    .contentEquals(ROOM_CHR_TABLE_SET_PATTERN)
            ) {
                found = trailer[index + 1].toInt() and 0xFF
                index += ROOM_CHR_TABLE_SET_PATTERN.size + 2
            } else {
                index++
            }
        }
        return found
    }

    private fun blitPixels(
        src: IntArray,
        srcWidth: Int,
        srcHeight: Int,
        dest: IntArray,
        destWidth: Int,
        destHeight: Int,
        destX: Int,
        destY: Int
    ) {
        for (row in 0 until srcHeight) {
            val y = destY + row
            if (y !in 0 until destHeight) continue
            val srcOffset = row * srcWidth
            val destOffset = y * destWidth + destX
            for (col in 0 until srcWidth) {
                val x = destX + col
                if (x in 0 until destWidth) {
                    dest[destOffset + col] = src[srcOffset + col]
                }
            }
        }
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
        private const val CHR_1K_BANK_SIZE = 0x400
        private const val TILES_PER_1K_BANK = 64
        private const val CHR_BANK_TABLE_PRG8_BANK = 0x3D
        private const val MAX_STRUCTURE_ROWS = 128
        private const val LDA_IMMEDIATE_OPCODE: Byte = 0xA9.toByte()
        private val CHR_BANK_TABLE_ADDRS = intArrayOf(0xB600, 0xB700, 0xB800, 0xB900)
        private val ROOM_CHR_TABLE_SET_PATTERN = byteArrayOf(
            0x8D.toByte(), 0x01, 0x78,
            0x8D.toByte(), 0x03, 0x78
        )

        val AREA_DATA_BANKS = mapOf(
            Area.BRINSTAR to 0x10,
            Area.NORFAIR to 0x12,
            Area.TOURIAN to 0x14,
            Area.KRAID to 0x16,
            Area.RIDLEY to 0x18
        )

        /**
         * Index into Rogue Dawn's background CHR bank tables. The game writes these
         * through MMC3 registers 2-5, which map four 1KB banks at PPU $1000-$1FFF.
         */
        val AREA_BG_CHR_TABLE_INDEX = mapOf(
            Area.BRINSTAR to 0x09,
            Area.NORFAIR to 0x19,
            Area.TOURIAN to 0x2B,
            Area.KRAID to 0x38,
            Area.RIDLEY to 0x5D
        )

        val ROOM_BG_CHR_TABLE_INDEX_OVERRIDES = mapOf(
            Area.BRINSTAR to mapOf(
                0x12 to 0x0D,
                0x16 to 0x0D
            )
        )

        /**
         * Rogue Dawn keeps Metroid's single 32x32 world-map byte table: each cell
         * stores a room number only, not an area id. The vanilla METEdit ownership
         * mask does not apply because Rogue Dawn rearranges the regions. This mask
         * follows Rogue Dawn's shipped printable map and is validated against the
         * hack's per-area room tables.
         */
        private val ROGUE_DAWN_MAP_AREA = arrayOf(
            ".KKKKKKKKKKKKKKKK..TTTTTTTTTTTTT",
            "KKKKKKKKKKKKKKKK...TTTTTTTTTTT.T",
            "KKKKKKK........K...TTTTTTTTTTT.T",
            "KKKKKKK.TTTTTTTTTTTTTTT...TTT..T",
            "KKKKKKKTT..T...T...TTT....TTTT..",
            ".KKK..TTT..T..TTT..TTTTTTTTTT..T",
            ".KKKK.KTTTTT....B....TTTTTTTT..T",
            "....KKKTTT...BBBBBBB.TTTTTTTTT..",
            ".KKKKKK...B.BBBBBBBBB..TTTTT....",
            ".KK.KKBBBBBBBBBBBBBBBBBBBBBTTTT.",
            ".KKKKK.B.BBBBBBBBBBBBBBBB.B...T.",
            "KKKKKKKKKBBBBBBBB..BBBBBB.TTTTT.",
            "KKKKKKKKK..BBBBBBBBBBBBBBBT.....",
            "KKKKKKKKKKKKBBBBBBBBBBBBBB......",
            "KKKKKKKKK...B...B.........NNN...",
            "KKKKKKKK.NNNNNNNNNNNNNNNNNN.....",
            "KKKKKK.K.NNNNNNNNNNNNNNN..NNNN..",
            "KK.KKKKK.NNNNNNNNN.....N...NRRR.",
            "KK.K.KKKNNNNNNNNNNNNNNNNRRRRRR..",
            "KKKKKK.KK.N...NNNNNNNNNNNRRRRRRR",
            "K......KKNNN..NNNNN.NNNNNRRRRRRR",
            "KKKKKKKKKNNNNNNNNNNNNN.N.RRRRRRR",
            "K....K.KKNNNNNNNNNNNNNNNRRRRRRRR",
            "KKKKKKKKKNNN....NN......RRRRRRRR",
            "K.KKKKKKKNNNNNNNNNRRRRRRRRRRRRRR",
            "KKKKKKKKKNNNNNNNNNRRRR.R..R..RRR",
            ".KKKKK.KKNNNNNNNNN...RRRRRR..R.R",
            ".K.KKKKKK...NNNNNN...R.RRRRRRR.R",
            ".K......KKK.NNNNNN..RRRRRRRRRR.R",
            ".KKKKKKKKK..NNNNNNN.......RRRR.R",
            ".........KK.......N.RK.......R.R",
            "..K.......K.......N..........R.."
        )
    }
}
