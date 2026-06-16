package com.metroid.editor.rom

import com.metroid.editor.data.Area
import com.metroid.editor.data.Room
import com.metroid.editor.data.RoomDoor
import com.metroid.editor.data.RoomEnemy
import com.metroid.editor.data.RoomObject
import com.metroid.editor.data.WorldMapCell

/**
 * Read-only Rogue Dawn V121 data profile.
 *
 * Rogue Dawn keeps several vanilla Metroid tables, including the shared world map and
 * per-area pointer block at $9598, but room pointers are 3-byte MMC3-style far
 * pointers: [8KB PRG bank, little-endian CPU address].
 */
class RogueDawnRomData(val rom: NesRomParser) {
    private val vanillaData = MetroidRomData(rom)

    fun isSupported(): Boolean = isSupported(rom)

    fun readWorldMap(): List<WorldMapCell> = vanillaData.readWorldMap()

    fun readAreaPointers(area: Area): MetroidRomData.AreaPointers = vanillaData.readAreaPointers(area)

    fun getRoomCount(area: Area): Int = readRoomFarPointers(area).size

    fun readRoomRefs(area: Area): List<RogueDawnRoomRef> {
        return readRoomFarPointers(area).mapIndexed { roomNumber, pointer ->
            RogueDawnRoomRef(
                area = area,
                roomNumber = roomNumber,
                pointer = pointer,
                romOffset = prg8BankAddressToRomOffset(pointer)
            )
        }
    }

    fun readRoomFarPointers(area: Area, maxEntries: Int = MAX_ROOM_POINTERS): List<RogueDawnFarPointer> {
        requireSupported()

        val areaBank = MetroidRomData.AREA_BANKS[area] ?: error("No bank for area $area")
        val roomTableAddress = readAreaPointers(area).roomPtrTable
        val roomTableOffset = rom.bankAddressToRomOffset(areaBank, roomTableAddress)
        val pointers = mutableListOf<RogueDawnFarPointer>()

        for (index in 0 until maxEntries) {
            val entryOffset = roomTableOffset + index * ROOM_POINTER_SIZE
            if (entryOffset + 2 >= prgRomEndOffset()) break

            val pointer = RogueDawnFarPointer(
                bank = rom.readByte(entryOffset),
                cpuAddress = rom.readWord(entryOffset + 1)
            )
            if (!pointer.isValid(rom.header.prgBanks * 2)) break

            val roomOffset = prg8BankAddressToRomOffset(pointer)
            if (roomOffset !in prgRomOffset() until prgRomEndOffset()) break

            pointers.add(pointer)
        }

        return pointers
    }

    fun readRoom(area: Area, roomNumber: Int): Room? {
        val pointer = readRoomFarPointers(area).getOrNull(roomNumber) ?: return null
        return parseRoomData(area, roomNumber, pointer)
    }

    fun readAllRooms(area: Area): List<Room> {
        return readRoomFarPointers(area).mapIndexedNotNull { roomNumber, pointer ->
            parseRoomData(area, roomNumber, pointer)
        }
    }

    fun prg8BankAddressToRomOffset(pointer: RogueDawnFarPointer): Int {
        require(pointer.isValid(rom.header.prgBanks * 2)) {
            "Invalid Rogue Dawn far pointer ${pointer.displayName}"
        }
        return prg8BankAddressToRomOffset(pointer.bank, pointer.cpuAddress)
    }

    fun prg8BankAddressToRomOffset(bank: Int, cpuAddress: Int): Int {
        require(bank in 0 until rom.header.prgBanks * 2 && cpuAddress in 0x8000 until 0xC000) {
            "Invalid Rogue Dawn PRG8 address ${"%02X:%04X".format(bank, cpuAddress)}"
        }
        return prgRomOffset() + bank * PRG_8K_BANK_SIZE + (cpuAddress and 0x1FFF)
    }

    fun readPrg8BankByte(bank: Int, cpuAddress: Int): Int {
        return rom.readByte(prg8BankAddressToRomOffset(bank, cpuAddress))
    }

    fun chrRomOffset(): Int {
        return prgRomEndOffset()
    }

    private fun parseRoomData(area: Area, roomNumber: Int, pointer: RogueDawnFarPointer): Room {
        val roomOffset = prg8BankAddressToRomOffset(pointer)
        val maxOffset = minOf(prgRomEndOffset(), roomOffset + ROOM_SCAN_LIMIT)
        val rawData = mutableListOf<Byte>()
        var pos = roomOffset

        val palette = readRoomByte(pos, maxOffset, pointer)
        rawData.add(palette.toByte())
        pos++

        val objects = mutableListOf<RoomObject>()

        while (true) {
            val b = readRoomByte(pos, maxOffset, pointer)
            rawData.add(b.toByte())
            pos++

            when (b) {
                MetroidRomData.ROOM_END_OBJECTS -> {
                    break
                }
                MetroidRomData.ROOM_END_DATA -> {
                    return Room(area, roomNumber, palette, objects, emptyList(), emptyList(), rawData.toByteArray(), roomOffset)
                }
                MetroidRomData.ROOM_EMPTY_OBJECT -> continue
            }

            val structIndex = readRoomByte(pos, maxOffset, pointer)
            val objectPalette = readRoomByte(pos + 1, maxOffset, pointer)
            rawData.add(structIndex.toByte())
            rawData.add(objectPalette.toByte())
            pos += 2

            objects.add(
                RoomObject(
                    posY = (b shr 4) and 0x0F,
                    posX = b and 0x0F,
                    structIndex = structIndex,
                    palette = objectPalette
                )
            )
        }

        val enemies = mutableListOf<RoomEnemy>()
        val doors = mutableListOf<RoomDoor>()

        while (true) {
            val b = readRoomByte(pos, maxOffset, pointer)
            rawData.add(b.toByte())
            pos++

            if (b == MetroidRomData.ROOM_END_DATA) break

            when (b and 0x0F) {
                1, 7 -> {
                    val type = readRoomByte(pos, maxOffset, pointer)
                    val enemyPos = readRoomByte(pos + 1, maxOffset, pointer)
                    rawData.add(type.toByte())
                    rawData.add(enemyPos.toByte())
                    pos += 2
                    enemies.add(
                        RoomEnemy(
                            slot = b and 0xF0,
                            type = type,
                            posY = (enemyPos shr 4) and 0x0F,
                            posX = enemyPos and 0x0F
                        )
                    )
                }
                2 -> {
                    val doorInfo = readRoomByte(pos, maxOffset, pointer)
                    rawData.add(doorInfo.toByte())
                    pos++
                    doors.add(RoomDoor(doorInfo, if ((doorInfo and 0x10) != 0) 1 else 0))
                }
                4 -> {
                    val elevatorData = readRoomByte(pos, maxOffset, pointer)
                    rawData.add(elevatorData.toByte())
                    pos++
                }
                6 -> Unit
                else -> Unit
            }
        }

        return Room(area, roomNumber, palette, objects, enemies, doors, rawData.toByteArray(), roomOffset)
    }

    private fun readRoomByte(offset: Int, maxOffset: Int, pointer: RogueDawnFarPointer): Int {
        require(offset < maxOffset) {
            "Unterminated Rogue Dawn room at ${pointer.displayName} within $ROOM_SCAN_LIMIT bytes"
        }
        return rom.readByte(offset)
    }

    private fun requireSupported() {
        require(isSupported()) {
            "Unsupported Rogue Dawn profile: ${rom.header.prgBanks}x16KB PRG, " +
                "${rom.header.chrBanks}x8KB CHR, mapper ${rom.mapper}"
        }
    }

    private fun prgRomOffset(): Int {
        return NesRomParser.INES_HEADER_SIZE + if (rom.hasTrainer) NesRomParser.TRAINER_SIZE else 0
    }

    private fun prgRomEndOffset(): Int = prgRomOffset() + rom.prgSize

    companion object {
        const val PRG_8K_BANK_SIZE = 0x2000
        const val ROOM_POINTER_SIZE = 3
        const val MAX_ROOM_POINTERS = 256
        private const val ROOM_SCAN_LIMIT = 0x400

        fun isSupported(rom: NesRomParser): Boolean {
            return rom.header.isValid &&
                rom.mapper == 4 &&
                rom.header.prgBanks == 32 &&
                rom.header.chrBanks == 32
        }
    }
}

data class RogueDawnFarPointer(
    val bank: Int,
    val cpuAddress: Int
) {
    val displayName: String get() = "%02X:%04X".format(bank, cpuAddress)

    fun isValid(prg8BankCount: Int): Boolean {
        return bank in 0 until prg8BankCount && cpuAddress in 0x8000 until 0xC000
    }
}

data class RogueDawnRoomRef(
    val area: Area,
    val roomNumber: Int,
    val pointer: RogueDawnFarPointer,
    val romOffset: Int
)
