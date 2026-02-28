package com.metroid.editor.data

/**
 * A single room in Metroid NES, parsed from ROM area bank data.
 *
 * Room data format (from MetroidMMC3 disassembly):
 *   Byte 0:        Palette index (initial attribute table fill, 0–3)
 *   Then repeating object entries (3 bytes each):
 *     Byte 0:      Position (%yyyyxxxx — upper nibble = Y, lower = X, in macro units)
 *     Byte 1:      Structure index (into StructPtrTable)
 *     Byte 2:      Attribute table info (palette bits for this structure)
 *   $FD:           End of objects marker
 *   Then enemy/door entries until $FF terminator
 *
 * Objects are placed into Room RAM ($6000 or $6400) which is a 1KB nametable-sized buffer.
 * Structures are built from 2×2 tile "macros" (each macro = 4 tile indices).
 */
data class Room(
    val area: Area,
    val roomNumber: Int,
    val palette: Int,
    val objects: List<RoomObject>,
    val enemies: List<RoomEnemy>,
    val doors: List<RoomDoor>,
    val rawData: ByteArray,
    val romOffset: Int
) {
    val displayName: String
        get() = "${area.displayName} Room ${"$%02X".format(roomNumber)}"

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Room) return false
        return area == other.area && roomNumber == other.roomNumber
    }

    override fun hashCode(): Int = 31 * area.hashCode() + roomNumber
}

data class RoomObject(
    val posY: Int,
    val posX: Int,
    val structIndex: Int,
    val palette: Int
)

data class RoomEnemy(
    val slot: Int,
    val type: Int,
    val posY: Int,
    val posX: Int
)

data class RoomDoor(
    val info: Int,
    val side: Int
)

/**
 * A structure definition, composed of rows of macro references.
 * Each row specifies an x-offset and a list of macro indices.
 */
data class Structure(
    val index: Int,
    val rows: List<StructureRow>
)

data class StructureRow(
    val xOffset: Int,
    val macroIndices: List<Int>
)

/**
 * A 2×2 tile macro. Four 8×8 tile indices arranged as:
 *   [topLeft]  [topRight]
 *   [botLeft]  [botRight]
 */
data class TileMacro(
    val topLeft: Int,
    val topRight: Int,
    val botLeft: Int,
    val botRight: Int
)

/**
 * A single map cell in the 32×32 world map grid.
 */
data class WorldMapCell(
    val x: Int,
    val y: Int,
    val roomNumber: Int
) {
    val isEmpty: Boolean get() = roomNumber == 0xFF
}
