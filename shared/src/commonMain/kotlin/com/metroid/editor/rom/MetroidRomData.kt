package com.metroid.editor.rom

import com.metroid.editor.data.*
import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Extracts Metroid-specific game data from the ROM.
 *
 * Each area bank (1–5) contains at offset 0x9598 (CPU addr) an AreaPointers table:
 *   Word 0 (0x9598): SpecItmsTbl — special items table
 *   Word 1 (0x959A): RmPtrTbl   — room pointer table
 *   Word 2 (0x959C): StrctPtrTbl — structure pointer table
 *   Word 3 (0x959E): MacroDefs  — macro (2×2 tile) definitions
 *   Word 4 (0x95A0): EnemyFramePtrTbl1
 *   Word 5 (0x95A2): EnemyFramePtrTbl2
 *   Word 6 (0x95A4): EnemyPlacePtrTbl
 *   Word 7 (0x95A6): EnemyAnimIndexTbl
 *
 * World map: A single shared 32×32 grid (1024 bytes) stored in bank 0 (Title bank)
 * at CPU address 0xA53E. The CopyMap routine at 0xA93E copies this to RAM 0x7000–0x73FF.
 * All areas share the same map — different regions correspond to different areas.
 *
 * From the disassembly, GetRoomNum calculates: addr = (MapY * 32) + MapX + 0x7000,
 * then reads the room number byte. 0xFF = empty/unused cell.
 */
class MetroidRomData(val rom: NesRomParser) {

    companion object {
        const val AREA_POINTERS_ADDR = 0x9598
        const val WORLD_MAP_SIZE = 1024
        const val WORLD_MAP_WIDTH = 32
        const val WORLD_MAP_HEIGHT = 32
        const val ROOM_END_OBJECTS = 0xFD
        const val ROOM_END_DATA = 0xFF
        const val ROOM_EMPTY_OBJECT = 0xFE
        const val STRUCT_END = 0xFF

        /** Bank 0 (Title bank) contains the shared world map. */
        const val WORLD_MAP_BANK = 0
        /** CPU address of the WorldMap label in bank 0. */
        const val WORLD_MAP_CPU_ADDR = 0xA53E

        val AREA_BANKS = mapOf(
            Area.BRINSTAR to 1,
            Area.NORFAIR to 2,
            Area.TOURIAN to 3,
            Area.KRAID to 4,
            Area.RIDLEY to 5
        )
    }

    data class AreaPointers(
        val specItemsTable: Int,
        val roomPtrTable: Int,
        val structPtrTable: Int,
        val macroDefs: Int,
        val enemyFramePtrTbl1: Int,
        val enemyFramePtrTbl2: Int,
        val enemyPlacePtrTbl: Int,
        val enemyAnimIndexTbl: Int
    )

    fun readAreaPointers(area: Area): AreaPointers {
        val bank = AREA_BANKS[area] ?: error("No bank for area $area")
        return AreaPointers(
            specItemsTable = rom.readBankWord(bank, AREA_POINTERS_ADDR),
            roomPtrTable = rom.readBankWord(bank, AREA_POINTERS_ADDR + 2),
            structPtrTable = rom.readBankWord(bank, AREA_POINTERS_ADDR + 4),
            macroDefs = rom.readBankWord(bank, AREA_POINTERS_ADDR + 6),
            enemyFramePtrTbl1 = rom.readBankWord(bank, AREA_POINTERS_ADDR + 8),
            enemyFramePtrTbl2 = rom.readBankWord(bank, AREA_POINTERS_ADDR + 10),
            enemyPlacePtrTbl = rom.readBankWord(bank, AREA_POINTERS_ADDR + 12),
            enemyAnimIndexTbl = rom.readBankWord(bank, AREA_POINTERS_ADDR + 14)
        )
    }

    /**
     * Read the shared world map. Returns a 32×32 grid of room numbers.
     * The map is stored once in bank 0 (Title bank) at CPU address 0xA53E.
     * All areas share the same map; different regions correspond to different areas.
     * The [area] parameter is accepted for API compatibility but is unused.
     */
    fun readWorldMap(@Suppress("UNUSED_PARAMETER") area: Area? = null): List<WorldMapCell> {
        val cells = mutableListOf<WorldMapCell>()
        for (y in 0 until WORLD_MAP_HEIGHT) {
            for (x in 0 until WORLD_MAP_WIDTH) {
                val offset = rom.bankAddressToRomOffset(WORLD_MAP_BANK, WORLD_MAP_CPU_ADDR + y * WORLD_MAP_WIDTH + x)
                val roomNum = rom.readByte(offset)
                cells.add(WorldMapCell(x, y, roomNum))
            }
        }
        return cells
    }

    /**
     * Read the room pointer table for an area and determine how many rooms there are.
     * The room pointer table (RmPtrTbl) is immediately followed by the structure pointer
     * table (StrctPtrTbl), so: room count = (StrctPtrTbl - RmPtrTbl) / 2.
     * Each entry is a 16-bit pointer to room data elsewhere in the bank.
     */
    fun getRoomCount(area: Area): Int {
        val ptrs = readAreaPointers(area)
        return (ptrs.structPtrTable - ptrs.roomPtrTable) / 2
    }

    /**
     * Read all room pointers for an area.
     */
    fun readRoomPointers(area: Area): List<Int> {
        val bank = AREA_BANKS[area] ?: error("No bank for area $area")
        val ptrs = readAreaPointers(area)
        val count = getRoomCount(area)

        return (0 until count).map { i ->
            rom.readBankWord(bank, ptrs.roomPtrTable + i * 2)
        }
    }

    /**
     * Parse a single room's data from ROM.
     */
    fun readRoom(area: Area, roomNumber: Int): Room? {
        val bank = AREA_BANKS[area] ?: return null
        val roomPointers = readRoomPointers(area)
        if (roomNumber >= roomPointers.size) return null

        val roomAddr = roomPointers[roomNumber]
        val romOffset = rom.bankAddressToRomOffset(bank, roomAddr)

        return try {
            parseRoomData(area, roomNumber, bank, roomAddr, romOffset)
        } catch (e: Exception) {
            logger.error(e) { "Failed to parse ${area.displayName} room $roomNumber" }
            null
        }
    }

    private fun parseRoomData(area: Area, roomNumber: Int, bank: Int, cpuAddr: Int, romOffset: Int): Room {
        val rawData = mutableListOf<Byte>()
        var pos = cpuAddr

        // Byte 0: palette
        val palette = rom.readBankByte(bank, pos)
        rawData.add(palette.toByte())
        pos++

        // Parse room objects until $FD or $FF
        val objects = mutableListOf<RoomObject>()
        var hasEnemyDoorSection = false
        while (true) {
            val b = rom.readBankByte(bank, pos)
            rawData.add(b.toByte())

            if (b == ROOM_END_OBJECTS) {
                hasEnemyDoorSection = true
                pos++
                break
            }
            if (b == ROOM_END_DATA) {
                pos++
                break
            }
            if (b == ROOM_EMPTY_OBJECT) {
                pos++
                continue
            }

            val objPosY = (b shr 4) and 0x0F
            val objPosX = b and 0x0F
            val structIdx = rom.readBankByte(bank, pos + 1)
            val objPal = rom.readBankByte(bank, pos + 2)
            rawData.add(structIdx.toByte())
            rawData.add(objPal.toByte())

            objects.add(RoomObject(objPosY, objPosX, structIdx, objPal))
            pos += 3
        }

        // Parse enemy/door data until $FF (only if objects ended with $FD)
        val enemies = mutableListOf<RoomEnemy>()
        val doors = mutableListOf<RoomDoor>()

        if (hasEnemyDoorSection) {
            while (true) {
                if (pos >= cpuAddr + 0x1000) break // safety limit
                val b = rom.readBankByte(bank, pos)
                rawData.add(b.toByte())

                if (b == ROOM_END_DATA) break

                val entryType = b and 0x0F
                when (entryType) {
                    1 -> {
                        // Enemy
                        val slot = b and 0xF0
                        val type = rom.readBankByte(bank, pos + 1)
                        val enemyPos = rom.readBankByte(bank, pos + 2)
                        rawData.add(type.toByte())
                        rawData.add(enemyPos.toByte())
                        enemies.add(RoomEnemy(slot, type, (enemyPos shr 4) and 0x0F, enemyPos and 0x0F))
                        pos += 3
                    }
                    2 -> {
                        // Door
                        val doorInfo = rom.readBankByte(bank, pos + 1)
                        rawData.add(doorInfo.toByte())
                        val doorSide = if ((doorInfo and 0x80) != 0) 1 else 0
                        doors.add(RoomDoor(doorInfo, doorSide))
                        pos += 2
                    }
                    4 -> {
                        // Elevator
                        val elevData = rom.readBankByte(bank, pos + 1)
                        rawData.add(elevData.toByte())
                        pos += 2
                    }
                    6 -> {
                        // Statues (Kraid/Ridley)
                        pos += 1
                    }
                    7 -> {
                        // Zeb hole (regenerating enemies)
                        val zebType = rom.readBankByte(bank, pos + 1)
                        val zebPos = rom.readBankByte(bank, pos + 2)
                        rawData.add(zebType.toByte())
                        rawData.add(zebPos.toByte())
                        pos += 3
                    }
                    else -> {
                        pos += 1
                    }
                }
            }
        }

        return Room(
            area = area,
            roomNumber = roomNumber,
            palette = palette,
            objects = objects,
            enemies = enemies,
            doors = doors,
            rawData = rawData.toByteArray(),
            romOffset = romOffset
        )
    }

    /**
     * Parse a structure definition from ROM.
     */
    fun readStructure(area: Area, structIndex: Int): Structure? {
        val bank = AREA_BANKS[area] ?: return null
        val ptrs = readAreaPointers(area)

        val structAddr = rom.readBankWord(bank, ptrs.structPtrTable + structIndex * 2)
        if (structAddr < 0x8000 || structAddr >= 0xC000) return null

        val rows = mutableListOf<StructureRow>()
        var pos = structAddr

        while (true) {
            val lengthByte = rom.readBankByte(bank, pos)
            if (lengthByte == STRUCT_END) break

            val xOffset = (lengthByte shr 4) and 0x0F
            var count = lengthByte and 0x0F
            if (count == 0) count = 16

            val macros = mutableListOf<Int>()
            for (i in 0 until count) {
                macros.add(rom.readBankByte(bank, pos + 1 + i))
            }

            rows.add(StructureRow(xOffset, macros))
            pos += 1 + count
        }

        return Structure(structIndex, rows)
    }

    /**
     * Read macro definitions for an area.
     * Each macro is 4 bytes: TL, TR, BL, BR tile indices.
     *
     * From the disassembly, macros are accessed as: MacroPtr + (macroNum * 4).
     * TilePosTable at $EF9A is [0x21, 0x20, 0x01, 0x00].
     * DrawMacro reads bytes sequentially (inc $11) while X decrements 3→0,
     * so byte 0 maps to TilePosTable[3]=0x00 (TL), byte 1→TilePosTable[2]=0x01 (TR),
     * byte 2→TilePosTable[1]=0x20 (BL), byte 3→TilePosTable[0]=0x21 (BR).
     */
    fun readMacro(area: Area, macroIndex: Int): TileMacro? {
        val bank = AREA_BANKS[area] ?: return null
        val ptrs = readAreaPointers(area)
        val macroAddr = ptrs.macroDefs + macroIndex * 4

        if (macroAddr < 0x8000 || macroAddr + 3 >= 0xC000) return null

        val b0 = rom.readBankByte(bank, macroAddr)
        val b1 = rom.readBankByte(bank, macroAddr + 1)
        val b2 = rom.readBankByte(bank, macroAddr + 2)
        val b3 = rom.readBankByte(bank, macroAddr + 3)

        return TileMacro(
            topLeft = b0,
            topRight = b1,
            botLeft = b2,
            botRight = b3
        )
    }

    /**
     * Read all macros for an area.
     */
    fun readAllMacros(area: Area): List<TileMacro> {
        val macros = mutableListOf<TileMacro>()
        var i = 0
        while (true) {
            val macro = readMacro(area, i) ?: break
            macros.add(macro)
            i++
            if (i > 512) break // safety limit
        }
        return macros
    }

    /**
     * Read all rooms for an area.
     */
    fun readAllRooms(area: Area): List<Room> {
        val count = getRoomCount(area)
        return (0 until count).mapNotNull { readRoom(area, it) }
    }

    /**
     * Read all areas' data.
     */
    fun readAllAreas(): Map<Area, List<Room>> {
        return Area.entries.associateWith { readAllRooms(it) }
    }

    /**
     * Get the map bounds (non-empty region) for the world map.
     */
    fun getWorldMapBounds(area: Area? = null): MapBounds? {
        val cells = readWorldMap(area)
        val nonEmpty = cells.filter { !it.isEmpty }
        if (nonEmpty.isEmpty()) return null
        return MapBounds(
            minX = nonEmpty.minOf { it.x },
            maxX = nonEmpty.maxOf { it.x },
            minY = nonEmpty.minOf { it.y },
            maxY = nonEmpty.maxOf { it.y }
        )
    }

    data class MapBounds(val minX: Int, val maxX: Int, val minY: Int, val maxY: Int) {
        val width: Int get() = maxX - minX + 1
        val height: Int get() = maxY - minY + 1
    }
}
