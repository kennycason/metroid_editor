package com.metroid.editor.rom

import com.metroid.editor.data.*

/**
 * Renders Metroid NES rooms from ROM data into ARGB pixel arrays.
 *
 * Rendering pipeline:
 *   1. Room data → list of (position, structIndex, palette) objects
 *   2. Structure → rows of macro indices
 *   3. Macro → 4 tile indices (2×2 arrangement of 8×8 tiles)
 *   4. Tile patterns → 2bpp pixel data → ARGB via NES palette
 *
 * Room layout:
 *   NES nametable = 32×30 tiles (256×240 pixels)
 *   Room RAM is a full nametable: 32 columns × 30 rows of 8×8 tiles
 *   Positions in room data are in "macro units" (16×16 pixel blocks)
 *   So the room grid is 16×15 macros
 */
@Suppress("UNUSED_PARAMETER")
class MapRenderer(
    private val romData: MetroidRomData,
    private val patternDecoder: NesPatternDecoder,
    rom: NesRomParser? = null
) {
    companion object {
        const val NAMETABLE_WIDTH_TILES = 32
        const val NAMETABLE_HEIGHT_TILES = 30
        const val TILE_SIZE = 8
        const val MACRO_SIZE = 16  // 2×2 tiles = 16×16 pixels
        const val ROOM_WIDTH_MACROS = 16
        const val ROOM_HEIGHT_MACROS = 15
        const val ROOM_WIDTH_PX = NAMETABLE_WIDTH_TILES * TILE_SIZE   // 256
        const val ROOM_HEIGHT_PX = NAMETABLE_HEIGHT_TILES * TILE_SIZE // 240
    }

    /**
     * Editable room state: a flat 16×15 macro grid + attribute (palette) grid.
     * Built by decomposing room objects→structures→macros.
     */
    data class MacroGrid(
        val macros: IntArray = IntArray(ROOM_WIDTH_MACROS * ROOM_HEIGHT_MACROS) { -1 },
        val attrs: IntArray = IntArray(ROOM_WIDTH_MACROS * ROOM_HEIGHT_MACROS) { 0 },
        val roomPalette: Int = 0
    ) {
        fun get(x: Int, y: Int): Int =
            if (x in 0 until ROOM_WIDTH_MACROS && y in 0 until ROOM_HEIGHT_MACROS)
                macros[y * ROOM_WIDTH_MACROS + x] else -1

        fun set(x: Int, y: Int, macroIdx: Int) {
            if (x in 0 until ROOM_WIDTH_MACROS && y in 0 until ROOM_HEIGHT_MACROS)
                macros[y * ROOM_WIDTH_MACROS + x] = macroIdx
        }

        fun getAttr(x: Int, y: Int): Int =
            if (x in 0 until ROOM_WIDTH_MACROS && y in 0 until ROOM_HEIGHT_MACROS)
                attrs[y * ROOM_WIDTH_MACROS + x] else 0

        fun setAttr(x: Int, y: Int, pal: Int) {
            if (x in 0 until ROOM_WIDTH_MACROS && y in 0 until ROOM_HEIGHT_MACROS)
                attrs[y * ROOM_WIDTH_MACROS + x] = pal
        }

        fun copy(): MacroGrid = MacroGrid(macros.copyOf(), attrs.copyOf(), roomPalette)

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is MacroGrid) return false
            return macros.contentEquals(other.macros) && attrs.contentEquals(other.attrs)
        }
        override fun hashCode(): Int = macros.contentHashCode()
    }

    /**
     * Rendered room data — pixel array plus metadata.
     */
    data class RoomRenderResult(
        val pixels: IntArray,
        val width: Int,
        val height: Int,
        val room: Room
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is RoomRenderResult) return false
            return room == other.room
        }
        override fun hashCode(): Int = room.hashCode()
    }

    private val fallbackColors = mapOf(
        Area.BRINSTAR to intArrayOf(0xFF002A88.toInt(), 0xFF001844.toInt()),
        Area.NORFAIR to intArrayOf(0xFF8E3E1E.toInt(), 0xFF6E2E0E.toInt()),
        Area.KRAID to intArrayOf(0xFF4E4E8E.toInt(), 0xFF3E3E6E.toInt()),
        Area.TOURIAN to intArrayOf(0xFF6E5E3E.toInt(), 0xFF4E3E2E.toInt()),
        Area.RIDLEY to intArrayOf(0xFF8E4E4E.toInt(), 0xFF6E3E3E.toInt())
    )

    /**
     * Render a room to pixels using tile-accurate rendering.
     */
    fun renderRoom(room: Room): RoomRenderResult {
        val pixels = IntArray(ROOM_WIDTH_PX * ROOM_HEIGHT_PX)
        val bankNumber = MetroidRomData.AREA_BANKS[room.area] ?: return gridFallback(room)

        // Read the area's initial palette (Palette00 from PalPntrTbl)
        val fullPalette = patternDecoder.readAreaPalette(bankNumber, 0)

        // Build assembled CHR RAM for this area (tiles from multiple banks)
        val chrRam = patternDecoder.buildChrRam(room.area)

        // Fill background with palette color 0 (universal BG color at PPU 0x3F00)
        pixels.fill(fullPalette[0])

        // Build a nametable-like buffer of tile indices (32x30)
        val tileBuffer = IntArray(NAMETABLE_WIDTH_TILES * NAMETABLE_HEIGHT_TILES) { -1 }
        val attrBuffer = IntArray(ROOM_WIDTH_MACROS * ROOM_HEIGHT_MACROS) { room.palette }

        // Place objects into the tile buffer
        for (obj in room.objects) {
            placeObject(room.area, obj, tileBuffer, attrBuffer)
        }

        // Render tiles to pixels using assembled CHR RAM
        for (ty in 0 until NAMETABLE_HEIGHT_TILES) {
            for (tx in 0 until NAMETABLE_WIDTH_TILES) {
                val tileIdx = tileBuffer[ty * NAMETABLE_WIDTH_TILES + tx]
                if (tileIdx < 0) continue

                val macroX = tx / 2
                val macroY = ty / 2
                val attrIdx = if (macroY < ROOM_HEIGHT_MACROS && macroX < ROOM_WIDTH_MACROS)
                    attrBuffer[macroY * ROOM_WIDTH_MACROS + macroX] else room.palette

                val subPalette = patternDecoder.getSubPalette(fullPalette, attrIdx and 0x03)

                val tilePixels = patternDecoder.decodeBgTileFromChrRam(chrRam, tileIdx)
                val renderedTile = patternDecoder.renderTile(tilePixels, subPalette)
                blitTile(pixels, ROOM_WIDTH_PX, tx * TILE_SIZE, ty * TILE_SIZE, renderedTile)
            }
        }

        return RoomRenderResult(pixels, ROOM_WIDTH_PX, ROOM_HEIGHT_PX, room)
    }

    /**
     * Place a room object into the tile buffer by expanding its structure into macros into tiles.
     */
    private fun placeObject(area: Area, obj: RoomObject, tileBuffer: IntArray, attrBuffer: IntArray) {
        val structure = romData.readStructure(area, obj.structIndex) ?: return

        var macroRow = obj.posY
        for (row in structure.rows) {
            var macroCol = obj.posX + row.xOffset
            for (macroIdx in row.macroIndices) {
                val macro = romData.readMacro(area, macroIdx) ?: continue

                // Place 4 tiles (2×2) into the tile buffer
                val tileX = macroCol * 2
                val tileY = macroRow * 2

                setTile(tileBuffer, tileX, tileY, macro.topLeft)
                setTile(tileBuffer, tileX + 1, tileY, macro.topRight)
                setTile(tileBuffer, tileX, tileY + 1, macro.botLeft)
                setTile(tileBuffer, tileX + 1, tileY + 1, macro.botRight)

                // Set attribute table entry
                if (macroCol in 0 until ROOM_WIDTH_MACROS && macroRow in 0 until ROOM_HEIGHT_MACROS) {
                    attrBuffer[macroRow * ROOM_WIDTH_MACROS + macroCol] = obj.palette
                }

                macroCol++
            }
            macroRow++
        }
    }

    private fun setTile(buffer: IntArray, x: Int, y: Int, tileIdx: Int) {
        if (x in 0 until NAMETABLE_WIDTH_TILES && y in 0 until NAMETABLE_HEIGHT_TILES) {
            buffer[y * NAMETABLE_WIDTH_TILES + x] = tileIdx
        }
    }

    private fun blitTile(dest: IntArray, destWidth: Int, x: Int, y: Int, tilePixels: IntArray) {
        for (row in 0 until TILE_SIZE) {
            val destY = y + row
            if (destY < 0 || destY >= ROOM_HEIGHT_PX) continue
            for (col in 0 until TILE_SIZE) {
                val destX = x + col
                if (destX < 0 || destX >= destWidth) continue
                val pixel = tilePixels[row * TILE_SIZE + col]
                if (pixel != 0x00000000) {
                    dest[destY * destWidth + destX] = pixel
                }
            }
        }
    }

    /**
     * Decompose a room into an editable macro grid by expanding all objects.
     */
    fun buildMacroGrid(room: Room): MacroGrid {
        val grid = MacroGrid(roomPalette = room.palette)
        grid.attrs.fill(room.palette)

        for (obj in room.objects) {
            val structure = romData.readStructure(room.area, obj.structIndex) ?: continue
            var macroRow = obj.posY
            for (row in structure.rows) {
                var macroCol = obj.posX + row.xOffset
                for (macroIdx in row.macroIndices) {
                    if (macroCol in 0 until ROOM_WIDTH_MACROS && macroRow in 0 until ROOM_HEIGHT_MACROS) {
                        grid.set(macroCol, macroRow, macroIdx)
                        grid.setAttr(macroCol, macroRow, obj.palette)
                    }
                    macroCol++
                }
                macroRow++
            }
        }
        return grid
    }

    /**
     * Render a room from a MacroGrid (used for editing — re-renders after painting).
     */
    fun renderFromGrid(room: Room, grid: MacroGrid): RoomRenderResult {
        val pixels = IntArray(ROOM_WIDTH_PX * ROOM_HEIGHT_PX)
        val bankNumber = MetroidRomData.AREA_BANKS[room.area] ?: return gridFallback(room)

        val fullPalette = patternDecoder.readAreaPalette(bankNumber, 0)
        val chrRam = patternDecoder.buildChrRam(room.area)
        pixels.fill(fullPalette[0])

        for (my in 0 until ROOM_HEIGHT_MACROS) {
            for (mx in 0 until ROOM_WIDTH_MACROS) {
                val macroIdx = grid.get(mx, my)
                if (macroIdx < 0) continue

                val macro = romData.readMacro(room.area, macroIdx) ?: continue
                val attrIdx = grid.getAttr(mx, my) and 0x03
                val subPalette = patternDecoder.getSubPalette(fullPalette, attrIdx)

                val tiles = intArrayOf(macro.topLeft, macro.topRight, macro.botLeft, macro.botRight)
                for (ti in 0 until 4) {
                    val tx = mx * 2 + (ti % 2)
                    val ty = my * 2 + (ti / 2)
                    val tilePixels = patternDecoder.decodeBgTileFromChrRam(chrRam, tiles[ti])
                    val rendered = patternDecoder.renderTile(tilePixels, subPalette)
                    blitTile(pixels, ROOM_WIDTH_PX, tx * TILE_SIZE, ty * TILE_SIZE, rendered)
                }
            }
        }

        return RoomRenderResult(pixels, ROOM_WIDTH_PX, ROOM_HEIGHT_PX, room)
    }

    private fun gridFallback(room: Room): RoomRenderResult {
        val pixels = IntArray(ROOM_WIDTH_PX * ROOM_HEIGHT_PX)
        val colors = fallbackColors[room.area] ?: intArrayOf(0xFF333333.toInt(), 0xFF222222.toInt())
        pixels.fill(colors[1])

        // Draw objects as colored blocks
        for (obj in room.objects) {
            val px = obj.posX * MACRO_SIZE
            val py = obj.posY * MACRO_SIZE
            for (dy in 0 until MACRO_SIZE) {
                for (dx in 0 until MACRO_SIZE) {
                    val x = px + dx
                    val y = py + dy
                    if (x in 0 until ROOM_WIDTH_PX && y in 0 until ROOM_HEIGHT_PX) {
                        pixels[y * ROOM_WIDTH_PX + x] = colors[0]
                    }
                }
            }
        }

        return RoomRenderResult(pixels, ROOM_WIDTH_PX, ROOM_HEIGHT_PX, room)
    }

    /**
     * Render the world map overview for an area.
     * Each cell is ROOM_WIDTH_PX × ROOM_HEIGHT_PX, but we render a scaled-down version.
     */
    fun renderWorldMapOverview(area: Area, cellSize: Int = 8): WorldMapRenderResult? {
        val cells = romData.readWorldMap(area)
        if (cells.isEmpty()) return null

        val bounds = romData.getWorldMapBounds(area) ?: return null
        val width = bounds.width * cellSize
        val height = bounds.height * cellSize
        val pixels = IntArray(width * height)

        val colors = fallbackColors[area] ?: intArrayOf(0xFF444444.toInt(), 0xFF222222.toInt())
        pixels.fill(0xFF111111.toInt())

        for (cell in cells) {
            if (cell.isEmpty) continue
            val cx = (cell.x - bounds.minX) * cellSize
            val cy = (cell.y - bounds.minY) * cellSize
            for (dy in 0 until cellSize) {
                for (dx in 0 until cellSize) {
                    val px = cx + dx
                    val py = cy + dy
                    if (px in 0 until width && py in 0 until height) {
                        val isBorder = dx == 0 || dy == 0 || dx == cellSize - 1 || dy == cellSize - 1
                        pixels[py * width + px] = if (isBorder) colors[1] else colors[0]
                    }
                }
            }
        }

        return WorldMapRenderResult(pixels, width, height, bounds)
    }

    data class WorldMapRenderResult(
        val pixels: IntArray,
        val width: Int,
        val height: Int,
        val bounds: MetroidRomData.MapBounds
    )
}
