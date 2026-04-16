package com.metroid.editor.rom

import com.metroid.editor.data.*
import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Re-encodes room and structure data from decoded macro grids.
 *
 * The encoding pipeline is the reverse of the decode path in [MapRenderer]:
 *   MacroGrid (16×15) → decompose into objects + structures
 *   → deduplicate structures across all rooms in the area
 *   → serialize to ROM-format bytes
 *
 * Structure format: rows of [lengthByte][macro bytes...], terminated by $FF.
 *   lengthByte = (xOffset << 4) | count  (count 0 = 16)
 *
 * Room format: [palette] [objects: posByte, structIdx, palByte]... [0xFD] [enemy/door bytes] [0xFF]
 */
object RoomEncoder {

    data class EncodedObject(
        val posY: Int,
        val posX: Int,
        val structKey: Int,
        val palette: Int
    )

    data class EncodedRoom(
        val roomNumber: Int,
        val palette: Int,
        val objects: List<EncodedObject>,
        val enemyDoorSuffix: ByteArray
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is EncodedRoom) return false
            return roomNumber == other.roomNumber
        }
        override fun hashCode(): Int = roomNumber
    }

    data class AreaEncoding(
        val rooms: List<EncodedRoom>,
        val structures: List<List<StructureRow>>,
        val roomDataBytes: Int,
        val structDataBytes: Int,
        val ptrTableBytes: Int
    ) {
        val totalBytes: Int get() = roomDataBytes + structDataBytes + ptrTableBytes
    }

    /**
     * Encode all rooms for an area from their macro grids.
     * Structures are deduplicated across all rooms.
     */
    fun encodeArea(
        rooms: List<Room>,
        grids: List<MapRenderer.MacroGrid>
    ): AreaEncoding {
        require(rooms.size == grids.size) { "rooms and grids must match" }

        val structCatalog = mutableMapOf<List<StructureRow>, Int>()
        val structList = mutableListOf<List<StructureRow>>()

        val encodedRooms = rooms.zip(grids).map { (room, grid) ->
            val objects = decomposeGrid(grid, structCatalog, structList)
            val suffix = extractEnemyDoorSuffix(room)
            EncodedRoom(room.roomNumber, grid.roomPalette, objects, suffix)
        }

        val roomBytes = encodedRooms.sumOf { serializeRoomData(it).size }
        val structBytes = structList.sumOf { serializeStructure(it).size }
        val ptrBytes = rooms.size * 2 + structList.size * 2

        return AreaEncoding(encodedRooms, structList, roomBytes, structBytes, ptrBytes)
    }

    /**
     * Decompose a 16×15 macro grid into room objects with deduplicated structures.
     *
     * Greedy algorithm: scan left-to-right, top-to-bottom. For each uncovered
     * non-empty cell, build a multi-row structure by extending right then down.
     */
    fun decomposeGrid(
        grid: MapRenderer.MacroGrid,
        structCatalog: MutableMap<List<StructureRow>, Int>,
        structList: MutableList<List<StructureRow>>
    ): List<EncodedObject> {
        val covered = BooleanArray(MapRenderer.ROOM_WIDTH_MACROS * MapRenderer.ROOM_HEIGHT_MACROS)
        val objects = mutableListOf<EncodedObject>()

        for (y in 0 until MapRenderer.ROOM_HEIGHT_MACROS) {
            for (x in 0 until MapRenderer.ROOM_WIDTH_MACROS) {
                val idx = y * MapRenderer.ROOM_WIDTH_MACROS + x
                if (covered[idx]) continue
                val macro = grid.get(x, y)
                if (macro < 0) continue
                val pal = grid.getAttr(x, y) and 0x03

                val rows = buildStructureRows(grid, covered, x, y, pal)
                if (rows.isEmpty()) continue

                val structIdx = structCatalog.getOrPut(rows) {
                    structList.add(rows)
                    structList.size - 1
                }

                objects.add(EncodedObject(y, x, structIdx, pal))

                for ((ri, row) in rows.withIndex()) {
                    for (ci in row.macroIndices.indices) {
                        val cx = x + row.xOffset + ci
                        val cy = y + ri
                        if (cx in 0 until MapRenderer.ROOM_WIDTH_MACROS &&
                            cy in 0 until MapRenderer.ROOM_HEIGHT_MACROS
                        ) {
                            covered[cy * MapRenderer.ROOM_WIDTH_MACROS + cx] = true
                        }
                    }
                }
            }
        }

        return objects
    }

    /**
     * Build structure rows by greedy extension: right on the current row,
     * then down for subsequent rows starting at the same column.
     */
    private fun buildStructureRows(
        grid: MapRenderer.MacroGrid,
        covered: BooleanArray,
        startX: Int,
        startY: Int,
        pal: Int
    ): List<StructureRow> {
        val rows = mutableListOf<StructureRow>()

        for (y in startY until MapRenderer.ROOM_HEIGHT_MACROS) {
            if (y > startY) {
                val idx = y * MapRenderer.ROOM_WIDTH_MACROS + startX
                if (covered[idx]) break
                val m = grid.get(startX, y)
                if (m < 0) break
                if ((grid.getAttr(startX, y) and 0x03) != pal) break
            }

            val macros = mutableListOf<Int>()
            var rx = startX
            while (rx < MapRenderer.ROOM_WIDTH_MACROS) {
                val idx = y * MapRenderer.ROOM_WIDTH_MACROS + rx
                if (covered[idx]) break
                val m = grid.get(rx, y)
                if (m < 0) break
                if ((grid.getAttr(rx, y) and 0x03) != pal) break
                macros.add(m)
                rx++
            }

            if (macros.isEmpty()) break
            rows.add(StructureRow(0, macros))
        }

        return rows
    }

    /**
     * Extract the enemy/door data suffix from a room's raw bytes.
     * Returns everything from the $FD marker (or $FF if no enemies) to end, inclusive.
     */
    fun extractEnemyDoorSuffix(room: Room): ByteArray {
        val raw = room.rawData
        var pos = 1 // skip palette byte
        while (pos < raw.size) {
            val b = raw[pos].toInt() and 0xFF
            if (b == MetroidRomData.ROOM_END_OBJECTS || b == MetroidRomData.ROOM_END_DATA) break
            if (b == MetroidRomData.ROOM_EMPTY_OBJECT) {
                pos++
                continue
            }
            pos += 3
        }
        return if (pos < raw.size) raw.copyOfRange(pos, raw.size) else byteArrayOf(0xFF.toByte())
    }

    /**
     * Serialize an encoded room to ROM-format bytes.
     */
    fun serializeRoomData(room: EncodedRoom): ByteArray {
        val bytes = mutableListOf<Byte>()
        bytes.add(room.palette.toByte())
        for (obj in room.objects) {
            val posByte = ((obj.posY and 0x0F) shl 4) or (obj.posX and 0x0F)
            bytes.add(posByte.toByte())
            bytes.add((obj.structKey and 0xFF).toByte())
            bytes.add((obj.palette and 0xFF).toByte())
        }
        bytes.addAll(room.enemyDoorSuffix.toList())
        return bytes.toByteArray()
    }

    /**
     * Serialize structure rows to ROM-format bytes (terminated by $FF).
     */
    fun serializeStructure(rows: List<StructureRow>): ByteArray {
        val bytes = mutableListOf<Byte>()
        for (row in rows) {
            val count = row.macroIndices.size
            val countNibble = if (count == 16) 0 else count
            val lengthByte = ((row.xOffset and 0x0F) shl 4) or (countNibble and 0x0F)
            bytes.add(lengthByte.toByte())
            for (m in row.macroIndices) {
                bytes.add((m and 0xFF).toByte())
            }
        }
        bytes.add(0xFF.toByte())
        return bytes.toByteArray()
    }

    /**
     * Decode an encoded room back to a MacroGrid (for round-trip verification).
     * Uses the structure list from [encodeArea] instead of reading from ROM.
     */
    fun decodeEncoded(
        encoded: EncodedRoom,
        structures: List<List<StructureRow>>
    ): MapRenderer.MacroGrid {
        val grid = MapRenderer.MacroGrid(roomPalette = encoded.palette)
        grid.attrs.fill(encoded.palette)

        for (obj in encoded.objects) {
            val rows = structures[obj.structKey]
            var macroRow = obj.posY
            for (row in rows) {
                var macroCol = obj.posX + row.xOffset
                for (macroIdx in row.macroIndices) {
                    if (macroCol in 0 until MapRenderer.ROOM_WIDTH_MACROS &&
                        macroRow in 0 until MapRenderer.ROOM_HEIGHT_MACROS
                    ) {
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

    // -- ROM writer --

    data class BankLayout(
        val roomPtrTableAddr: Int,
        val structPtrTableAddr: Int,
        val roomDataRegions: List<IntRange>,
        val structDataRegions: List<IntRange>,
        val reclaimableRanges: List<IntRange>,
        val totalReclaimable: Int
    )

    /**
     * Analyze the current bank layout to find reclaimable space for re-encoding.
     * Identifies all byte ranges used by room data, structure data, and pointer tables.
     *
     * IMPORTANT: The area pointer table entries are NOT in address order.
     * specItemsTable often sits between structPtrTable and macroDefs, so
     * we derive the struct count from the max struct index used in rooms.
     */
    fun analyzeBankLayout(
        parser: NesRomParser,
        md: MetroidRomData,
        area: Area
    ): BankLayout {
        val bank = MetroidRomData.AREA_BANKS[area] ?: error("No bank for $area")
        val ptrs = md.readAreaPointers(area)
        val roomCount = md.getRoomCount(area)
        val rooms = md.readAllRooms(area)

        val roomPtrs = (0 until roomCount).map {
            parser.readBankWord(bank, ptrs.roomPtrTable + it * 2)
        }

        val roomRegions = mutableListOf<IntRange>()
        for (roomAddr in roomPtrs) {
            val size = measureRoomData(parser, bank, roomAddr)
            val start = parser.bankAddressToRomOffset(bank, roomAddr)
            roomRegions.add(start until start + size)
        }

        // Derive struct count from the highest struct index actually used in room objects.
        // DO NOT use (macroDefs - structPtrTable) / 2 — other tables (specItemsTable)
        // can sit between structPtrTable and macroDefs.
        val maxStructIdx = rooms.flatMap { r -> r.objects.map { it.structIndex } }.maxOrNull() ?: 0
        val structCount = maxStructIdx + 1

        val structPtrs = (0 until structCount).map {
            parser.readBankWord(bank, ptrs.structPtrTable + it * 2)
        }

        val structRegions = mutableListOf<IntRange>()
        for (structAddr in structPtrs.distinct()) {
            if (structAddr < 0x8000 || structAddr >= 0xC000) continue
            val size = measureStructData(parser, bank, structAddr)
            val start = parser.bankAddressToRomOffset(bank, structAddr)
            structRegions.add(start until start + size)
        }

        val roomPtrTableStart = parser.bankAddressToRomOffset(bank, ptrs.roomPtrTable)
        val roomPtrTableEnd = roomPtrTableStart + roomCount * 2

        val structPtrTableStart = parser.bankAddressToRomOffset(bank, ptrs.structPtrTable)
        val structPtrTableEnd = structPtrTableStart + structCount * 2

        val allRanges = mutableListOf<IntRange>()
        allRanges.add(roomPtrTableStart until roomPtrTableEnd)
        allRanges.add(structPtrTableStart until structPtrTableEnd)
        allRanges.addAll(roomRegions)
        allRanges.addAll(structRegions)

        val merged = mergeRanges(allRanges)

        return BankLayout(
            roomPtrTableAddr = ptrs.roomPtrTable,
            structPtrTableAddr = ptrs.structPtrTable,
            roomDataRegions = roomRegions,
            structDataRegions = structRegions,
            reclaimableRanges = merged,
            totalReclaimable = merged.sumOf { it.last - it.first + 1 }
        )
    }

    /**
     * Write re-encoded area data into the ROM.
     * Each item (pointer table, room data, structure data) is placed in a
     * contiguous region — items never straddle non-contiguous free blocks.
     */
    fun writeToRom(
        parser: NesRomParser,
        area: Area,
        encoding: AreaEncoding,
        layout: BankLayout
    ): WriteResult {
        val bank = MetroidRomData.AREA_BANKS[area] ?: error("No bank for $area")
        val needed = encoding.totalBytes

        if (needed > layout.totalReclaimable) {
            return WriteResult(
                success = false,
                bytesUsed = needed,
                bytesAvailable = layout.totalReclaimable,
                message = "Not enough space: need $needed bytes, have ${layout.totalReclaimable}"
            )
        }

        // Wipe all reclaimable regions
        for (range in layout.reclaimableRanges) {
            for (offset in range) {
                parser.writeByte(offset, 0xFF)
            }
        }

        val alloc = ContiguousAllocator(layout.reclaimableRanges)

        // Room and struct pointer tables MUST be adjacent: getRoomCount = (structPtr - roomPtr) / 2
        val roomPtrSize = encoding.rooms.size * 2
        val structPtrSize = encoding.structures.size * 2
        val combinedPtrSize = roomPtrSize + structPtrSize
        val roomPtrTableRomOffset = alloc.allocate(combinedPtrSize)
            ?: return WriteResult(false, needed, layout.totalReclaimable,
                "No contiguous block for pointer tables ($combinedPtrSize bytes)")
        val structPtrTableRomOffset = roomPtrTableRomOffset + roomPtrSize
        val roomPtrTableCpuAddr = romOffsetToCpuAddr(roomPtrTableRomOffset, bank)
        val structPtrTableCpuAddr = romOffsetToCpuAddr(structPtrTableRomOffset, bank)

        // Write room data — each room in its own contiguous allocation
        val roomAddrs = mutableListOf<Int>()
        for (room in encoding.rooms) {
            val data = serializeRoomData(room)
            val romOff = alloc.allocate(data.size)
                ?: return WriteResult(false, needed, layout.totalReclaimable,
                    "No contiguous block for room ${room.roomNumber} (${data.size} bytes)")
            roomAddrs.add(romOffsetToCpuAddr(romOff, bank))
            for (i in data.indices) {
                parser.writeByte(romOff + i, data[i].toInt() and 0xFF)
            }
        }

        // Write structure data — each structure in its own contiguous allocation
        val structAddrs = mutableListOf<Int>()
        for (struct in encoding.structures) {
            val data = serializeStructure(struct)
            val romOff = alloc.allocate(data.size)
                ?: return WriteResult(false, needed, layout.totalReclaimable,
                    "No contiguous block for structure (${data.size} bytes)")
            structAddrs.add(romOffsetToCpuAddr(romOff, bank))
            for (i in data.indices) {
                parser.writeByte(romOff + i, data[i].toInt() and 0xFF)
            }
        }

        // Fill in room pointer table
        for ((i, addr) in roomAddrs.withIndex()) {
            val off = roomPtrTableRomOffset + i * 2
            parser.writeByte(off, addr and 0xFF)
            parser.writeByte(off + 1, (addr shr 8) and 0xFF)
        }

        // Fill in struct pointer table
        for ((i, addr) in structAddrs.withIndex()) {
            val off = structPtrTableRomOffset + i * 2
            parser.writeByte(off, addr and 0xFF)
            parser.writeByte(off + 1, (addr shr 8) and 0xFF)
        }

        // Update area pointer table entries
        val areaPtrBase = parser.bankAddressToRomOffset(bank, MetroidRomData.AREA_POINTERS_ADDR)
        parser.writeByte(areaPtrBase + 2, roomPtrTableCpuAddr and 0xFF)
        parser.writeByte(areaPtrBase + 3, (roomPtrTableCpuAddr shr 8) and 0xFF)
        parser.writeByte(areaPtrBase + 4, structPtrTableCpuAddr and 0xFF)
        parser.writeByte(areaPtrBase + 5, (structPtrTableCpuAddr shr 8) and 0xFF)

        val totalWritten = roomPtrSize + structPtrSize +
                encoding.rooms.sumOf { serializeRoomData(it).size } +
                encoding.structures.sumOf { serializeStructure(it).size }

        return WriteResult(
            success = true,
            bytesUsed = totalWritten,
            bytesAvailable = layout.totalReclaimable,
            message = "Wrote $totalWritten / ${layout.totalReclaimable} bytes " +
                    "(${encoding.rooms.size} rooms, ${encoding.structures.size} structs)"
        )
    }

    data class WriteResult(
        val success: Boolean,
        val bytesUsed: Int,
        val bytesAvailable: Int,
        val message: String
    ) {
        val bytesFree: Int get() = bytesAvailable - bytesUsed
    }

    // -- Space budget calculation --

    data class SpaceBudget(
        val tilesUsed: Int,
        val roomDataBytes: Int,
        val structDataBytes: Int,
        val ptrTableBytes: Int,
        val totalUsed: Int,
        val totalAvailable: Int,
        val freeSpaceInBank: Int
    ) {
        val bytesFree: Int get() = totalAvailable - totalUsed
        val percentUsed: Int get() = if (totalAvailable > 0) (totalUsed * 100) / totalAvailable else 100
    }

    /**
     * Calculate the actual space budget from current ROM data, not from re-encoding.
     * Shows real room/struct/ptr table usage vs the reclaimable space in the bank.
     */
    fun calculateBudget(
        parser: NesRomParser,
        md: MetroidRomData,
        area: Area,
        rooms: List<Room>,
        grids: List<MapRenderer.MacroGrid>
    ): SpaceBudget {
        val layout = analyzeBankLayout(parser, md, area)

        val tilesUsed = grids.sumOf { grid ->
            (0 until MapRenderer.ROOM_WIDTH_MACROS * MapRenderer.ROOM_HEIGHT_MACROS).count {
                grid.macros[it] >= 0
            }
        }

        val roomDataBytes = layout.roomDataRegions.sumOf { it.last - it.first + 1 }
        val structDataBytes = layout.structDataRegions.sumOf { it.last - it.first + 1 }
        val maxStructIdx = rooms.flatMap { r -> r.objects.map { it.structIndex } }.maxOrNull() ?: 0
        val ptrTableBytes = rooms.size * 2 + (maxStructIdx + 1) * 2

        // Scan for actual free $FF runs in the bank (potential growth room)
        val bank = MetroidRomData.AREA_BANKS[area] ?: 0
        val bankStart = NesRomParser.INES_HEADER_SIZE + bank * NesRomParser.PRG_BANK_SIZE
        val bankEnd = bankStart + NesRomParser.PRG_BANK_SIZE
        var freeInBank = 0
        var runLen = 0
        for (i in bankStart until bankEnd) {
            if ((parser.readByte(i) and 0xFF) == 0xFF) {
                runLen++
            } else {
                if (runLen >= 3) freeInBank += runLen
                runLen = 0
            }
        }
        if (runLen >= 3) freeInBank += runLen

        return SpaceBudget(
            tilesUsed = tilesUsed,
            roomDataBytes = roomDataBytes,
            structDataBytes = structDataBytes,
            ptrTableBytes = ptrTableBytes,
            totalUsed = layout.totalReclaimable,
            totalAvailable = layout.totalReclaimable + freeInBank,
            freeSpaceInBank = freeInBank
        )
    }

    // -- Helpers --

    private fun measureRoomData(parser: NesRomParser, bank: Int, cpuAddr: Int): Int {
        var pos = cpuAddr
        pos++ // palette byte
        while (true) {
            val b = parser.readBankByte(bank, pos)
            if (b == MetroidRomData.ROOM_END_OBJECTS || b == MetroidRomData.ROOM_END_DATA) {
                pos++
                break
            }
            if (b == MetroidRomData.ROOM_EMPTY_OBJECT) {
                pos++
                continue
            }
            pos += 3
        }
        if (parser.readBankByte(bank, pos - 1) == MetroidRomData.ROOM_END_OBJECTS) {
            // Enemy/door section until $FF
            while (true) {
                val b = parser.readBankByte(bank, pos)
                pos++
                if (b == MetroidRomData.ROOM_END_DATA) break
                val entryType = b and 0x0F
                when (entryType) {
                    1, 7 -> pos += 2
                    2, 4 -> pos += 1
                    else -> {}
                }
            }
        }
        return pos - cpuAddr
    }

    private fun measureStructData(parser: NesRomParser, bank: Int, cpuAddr: Int): Int {
        var pos = cpuAddr
        while (true) {
            val b = parser.readBankByte(bank, pos)
            if (b == MetroidRomData.STRUCT_END) {
                pos++
                break
            }
            val count = b and 0x0F
            pos += 1 + (if (count == 0) 16 else count)
        }
        return pos - cpuAddr
    }

    private fun mergeRanges(ranges: List<IntRange>): List<IntRange> {
        if (ranges.isEmpty()) return emptyList()
        val sorted = ranges.sortedBy { it.first }
        val merged = mutableListOf(sorted[0])
        for (i in 1 until sorted.size) {
            val last = merged.last()
            val cur = sorted[i]
            if (cur.first <= last.last + 1) {
                merged[merged.size - 1] = last.first..maxOf(last.last, cur.last)
            } else {
                merged.add(cur)
            }
        }
        return merged
    }

    private fun romOffsetToCpuAddr(romOffset: Int, bank: Int): Int {
        val bankStart = NesRomParser.INES_HEADER_SIZE + bank * NesRomParser.PRG_BANK_SIZE
        return 0x8000 + (romOffset - bankStart)
    }

    /**
     * Best-fit allocator for contiguous blocks within non-contiguous free regions.
     * Each allocation is guaranteed to be fully within a single region.
     */
    private class ContiguousAllocator(regions: List<IntRange>) {
        private val freeBlocks = regions.map { it.first to (it.last - it.first + 1) }.toMutableList()

        fun allocate(size: Int): Int? {
            var bestIdx = -1
            var bestSize = Int.MAX_VALUE
            for (i in freeBlocks.indices) {
                val (_, blockSize) = freeBlocks[i]
                if (blockSize >= size && blockSize < bestSize) {
                    bestIdx = i
                    bestSize = blockSize
                }
            }
            if (bestIdx < 0) return null
            val (start, blockSize) = freeBlocks[bestIdx]
            if (blockSize == size) {
                freeBlocks.removeAt(bestIdx)
            } else {
                freeBlocks[bestIdx] = (start + size) to (blockSize - size)
            }
            return start
        }
    }
}
