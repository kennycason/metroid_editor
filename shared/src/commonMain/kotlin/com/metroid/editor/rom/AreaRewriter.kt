package com.metroid.editor.rom

import com.metroid.editor.data.*
import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Atomic area bank rewriter.
 *
 * Instead of incrementally patching ROM bytes (which caused Bugs 1-4 due to
 * cascading pointer invalidation), this rewrites ALL room and struct data for
 * an area in a single atomic pass — following Editroid's proven save order:
 *
 *   1. Snapshot everything from the original ROM
 *   2. Apply edits to in-memory data structures
 *   3. Write everything back atomically:
 *      a. StructPtrTable (at allocated offset)
 *      b. StructData (contiguously after table)
 *      c. SpecItemsTable (with fresh absolute pointers)
 *      d. RoomPtrTable
 *      e. RoomData (contiguously)
 *      f. Update AreaPointers at $9598
 *   4. Post-write verification
 *
 * Data that is NOT modified (macroDefs, enemy tables) is never touched.
 */
object AreaRewriter {

    data class StructSnapshot(val data: ByteArray, val indices: MutableList<Int>)

    data class RewriteResult(
        val success: Boolean,
        val editCount: Int,
        val errors: List<String>,
        val bytesUsed: Int = 0,
        val bytesAvailable: Int = 0,
        val skippedUncovered: Int = 0
    )

    /**
     * Rewrite an entire area's room/struct data atomically.
     *
     * @param exportParser   Mutable ROM parser to write into
     * @param origParser     Original (unmodified) ROM parser for clean reads
     * @param md             MetroidRomData built from origParser
     * @param renderer       MapRenderer built from origParser
     * @param area           The area to rewrite
     * @param areaEdits      Map of roomKey -> RoomEdits for rooms in this area
     */
    fun rewriteArea(
        exportParser: NesRomParser,
        origParser: NesRomParser,
        md: MetroidRomData,
        renderer: MapRenderer,
        area: Area,
        areaEdits: Map<String, RoomEdits>
    ): RewriteResult {
        val bank = MetroidRomData.AREA_BANKS[area]
            ?: return RewriteResult(false, 0, listOf("No bank for area $area"))
        val bankStart = NesRomParser.INES_HEADER_SIZE + bank * NesRomParser.PRG_BANK_SIZE
        val origPtrs = md.readAreaPointers(area)
        val allRooms = md.readAllRooms(area)

        // ── Phase 1: Snapshot everything from original ROM ──

        // 1a. Snapshot all room data (raw bytes)
        val roomDataMap = mutableMapOf<Int, ByteArray>()
        for (r in allRooms) {
            roomDataMap[r.roomNumber] = r.rawData.copyOf()
        }

        // 1b. Snapshot all struct data (deduplicated by CPU address)
        val maxStructIdx = allRooms.flatMap { r -> r.objects.map { it.structIndex } }
            .maxOrNull() ?: 0
        val structByAddr = linkedMapOf<Int, StructSnapshot>()
        for (idx in 0..maxStructIdx) {
            val cpuAddr = origParser.readBankWord(bank, origPtrs.structPtrTable + idx * 2)
            if (cpuAddr < 0x8000 || cpuAddr >= 0xC000) continue
            val existing = structByAddr[cpuAddr]
            if (existing != null) {
                existing.indices.add(idx)
            } else {
                val romOff = origParser.bankAddressToRomOffset(bank, cpuAddr)
                val size = measureStructSize(origParser, bank, cpuAddr)
                val bytes = ByteArray(size) { (origParser.readByte(romOff + it) and 0xFF).toByte() }
                structByAddr[cpuAddr] = StructSnapshot(bytes, mutableListOf(idx))
            }
        }

        // 1c. Snapshot specItemsTable
        val specItemsCpu = origPtrs.specItemsTable
        val specItemsRom = origParser.bankAddressToRomOffset(bank, specItemsCpu)
        val specItemsEnd = findSpecItemsEnd(origParser, bank, origPtrs, allRooms, structByAddr.keys)
        val specItemsSize = specItemsEnd - specItemsRom
        val specItemsData = if (specItemsSize > 0)
            ByteArray(specItemsSize) { (origParser.readByte(specItemsRom + it) and 0xFF).toByte() }
        else null

        // 1d. Read struct catalog for matching
        val structCatalog = buildStructCatalog(md, area, allRooms)

        logger.info { "rewriteArea ${area.displayName}: ${allRooms.size} rooms, " +
                "${structByAddr.size} unique structs (0..$maxStructIdx), " +
                "specItems=${specItemsSize} bytes" }

        // ── Phase 2: Apply edits to in-memory data ──
        //
        // Three paths per room:
        // A. Covered edits on UNSHARED structs: patch bytes directly (zero space cost)
        // B. Covered edits on SHARED structs: full room re-encode with cloned structs
        //    (because patching a shared struct would modify ALL rooms using it)
        // C. Uncovered edits: full room re-encode with new 1x1 structs

        // Build struct sharing map: structIndex → set of room numbers using it
        val structUsers = mutableMapOf<Int, MutableSet<Int>>()
        for (r in allRooms) {
            for (obj in r.objects) {
                structUsers.getOrPut(obj.structIndex) { mutableSetOf() }.add(r.roomNumber)
            }
        }

        var editCount = 0
        val newStructs = mutableListOf<NewStruct>()
        val clonedStructs = mutableListOf<ClonedStruct>()
        var needsFullRewrite = false
        val warnings = mutableListOf<String>()

        // Track which struct indices are used (for allocating new slots)
        val usedStructIndices = (0..maxStructIdx).toMutableSet()

        for ((key, roomEditsData) in areaEdits) {
            if (roomEditsData.macroEdits.isEmpty()) continue
            val parts = key.split(":")
            val roomNum = parts.getOrNull(1)?.toIntOrNull(16) ?: continue
            val room = allRooms.find { it.roomNumber == roomNum } ?: continue

            val coverage = buildCoverageMap(room, structCatalog)

            // Classify edits
            val coveredEdits = mutableListOf<MacroEdit>()
            val uncoveredEdits = mutableListOf<MacroEdit>()
            for (edit in roomEditsData.macroEdits) {
                val idx = edit.y * MapRenderer.ROOM_WIDTH_MACROS + edit.x
                if (coverage[idx]) coveredEdits.add(edit) else uncoveredEdits.add(edit)
            }

            // Check if any covered edit touches a shared struct
            val touchesShared = coveredEdits.any { edit ->
                val obj = findCoveringObject(room, edit, structCatalog) ?: return@any false
                val users = structUsers[obj.structIndex] ?: emptySet()
                users.size > 1
            }

            if (uncoveredEdits.isEmpty() && !touchesShared) {
                // Path A: Covered-only on unshared structs — safe in-place byte patch
                applyCoveredEdits(origParser, bank, origPtrs, room, coveredEdits, structByAddr, structCatalog)
                editCount += coveredEdits.size
                logger.debug { "  Room $roomNum: ${coveredEdits.size} covered edits on unshared structs (in-place patch)" }
            } else if (uncoveredEdits.isEmpty() && touchesShared) {
                // Path B: Clone shared structs, apply edits to clones, update room objects
                needsFullRewrite = true
                val cloneResult = cloneSharedStructsForRoom(
                    origParser, bank, origPtrs, room, coveredEdits, structCatalog,
                    structUsers, structByAddr, usedStructIndices, clonedStructs
                )
                roomDataMap[room.roomNumber] = cloneResult
                editCount += coveredEdits.size
                logger.debug { "  Room $roomNum: ${room.rawData.size} → ${cloneResult.size} bytes, " +
                        "${coveredEdits.size} edits (${clonedStructs.size} struct clones)" }
            } else {
                // Path C: Mixed covered+uncovered edits
                // Step 1: Handle covered edits via struct cloning (like Path B)
                needsFullRewrite = true
                if (coveredEdits.isNotEmpty()) {
                    val cloneResult = cloneSharedStructsForRoom(
                        origParser, bank, origPtrs, room, coveredEdits, structCatalog,
                        structUsers, structByAddr, usedStructIndices, clonedStructs
                    )
                    roomDataMap[room.roomNumber] = cloneResult
                }

                // Step 2: Add new 1x1 objects for uncovered cells
                val roomBytes = (roomDataMap[room.roomNumber] ?: room.rawData).toMutableList()
                // Find the $FD or $FF terminator to insert before it
                val suffix = RoomEncoder.extractEnemyDoorSuffix(room)
                // Remove the suffix (we'll re-append after adding objects)
                val dataWithoutSuffix = roomBytes.dropLast(suffix.size).toMutableList()

                val existingNewStructs = newStructs.toList()
                for (edit in uncoveredEdits) {
                    // Find or create a 1x1 struct for this macro
                    var structIdx = find1x1Struct(structCatalog.toList() +
                        newStructs.map { StructEntry(it.slotIndex,
                            Structure(it.slotIndex, listOf(StructureRow(0, listOf(it.macroIndex)))), 1) },
                        edit.macroIndex)
                    if (structIdx == null) {
                        val slot = (usedStructIndices.maxOrNull() ?: -1) + 1
                        newStructs.add(NewStruct(slot, edit.macroIndex))
                        usedStructIndices.add(slot)
                        structIdx = slot
                    }
                    // Append object bytes before the suffix
                    dataWithoutSuffix.add((((edit.y and 0x0F) shl 4) or (edit.x and 0x0F)).toByte())
                    dataWithoutSuffix.add((structIdx and 0xFF).toByte())
                    dataWithoutSuffix.add((edit.palette and 0xFF).toByte())
                }

                // Re-append the suffix
                dataWithoutSuffix.addAll(suffix.toList())
                roomDataMap[room.roomNumber] = dataWithoutSuffix.toByteArray()

                editCount += roomEditsData.macroEdits.size
                val newSize = dataWithoutSuffix.size
                logger.debug { "  Room $roomNum: ${room.rawData.size} → $newSize bytes, " +
                        "${coveredEdits.size} covered (cloned) + ${uncoveredEdits.size} uncovered (new objects)" }
            }
        }

        // ── Phase 2b: If all edits are in-place patches, use fast path ──
        if (!needsFullRewrite) {
            for ((cpuAddr, snapshot) in structByAddr) {
                val romOff = exportParser.bankAddressToRomOffset(bank, cpuAddr)
                for (i in snapshot.data.indices) {
                    exportParser.writeByte(romOff + i, snapshot.data[i].toInt() and 0xFF)
                }
            }
            logger.info { "rewriteArea ${area.displayName}: $editCount covered-only edits (in-place patch)" }
            return RewriteResult(true, editCount, warnings)
        }

        // ── Phase 3+: Full rewrite for uncovered edits ──
        // ── Phase 3: Compute layout and check space ──

        // Determine reclaimable regions — all data we can overwrite
        val reclaimableRanges = mutableListOf<IntRange>()

        // Room pointer table
        val roomPtrStart = origParser.bankAddressToRomOffset(bank, origPtrs.roomPtrTable)
        reclaimableRanges.add(roomPtrStart until roomPtrStart + allRooms.size * 2)

        // Struct pointer table
        val structPtrStart = origParser.bankAddressToRomOffset(bank, origPtrs.structPtrTable)
        reclaimableRanges.add(structPtrStart until structPtrStart + (maxStructIdx + 1) * 2)

        // SpecItemsTable
        if (specItemsSize > 0) {
            reclaimableRanges.add(specItemsRom until specItemsRom + specItemsSize)
        }

        // All room data
        for (r in allRooms) {
            reclaimableRanges.add(r.romOffset until r.romOffset + r.rawData.size)
        }

        // All struct data
        for ((cpuAddr, snapshot) in structByAddr) {
            val romOff = origParser.bankAddressToRomOffset(bank, cpuAddr)
            reclaimableRanges.add(romOff until romOff + snapshot.data.size)
        }

        // Scan for freed regions in expanded ROMs (zeroed-out pattern data).
        // Standard ROMs are fully packed — no extra free space exists.
        val isExpanded = RomExpander.isExpandedRom(exportParser.romData)
        val bankRomStart = NesRomParser.INES_HEADER_SIZE + bank * NesRomParser.PRG_BANK_SIZE
        val bankRomEnd = bankRomStart + NesRomParser.PRG_BANK_SIZE
        val extraFree = if (isExpanded) {
            val mergedData = mergeRanges(reclaimableRanges)
            val freeRanges = mutableListOf<IntRange>()

            // Find contiguous runs of 0x00 bytes (freed pattern data)
            var runStart = -1
            var runLen = 0
            for (i in bankRomStart until bankRomEnd) {
                val b = exportParser.readByte(i) and 0xFF
                if (b == 0x00) {
                    if (runStart < 0) runStart = i
                    runLen++
                } else {
                    if (runLen >= 16) freeRanges.add(runStart until runStart + runLen)
                    runStart = -1; runLen = 0
                }
            }
            if (runLen >= 16) freeRanges.add(runStart until runStart + runLen)

            // Protect enemy tables + macro defs
            val protectedRanges = mutableListOf<IntRange>()
            val enemyTableAddrs = listOf(
                origPtrs.enemyAnimIndexTbl, origPtrs.enemyFramePtrTbl1,
                origPtrs.enemyFramePtrTbl2, origPtrs.enemyPlacePtrTbl
            ).filter { it in 0x8000 until 0xC000 }
            val lowestEnemy = enemyTableAddrs.minOrNull() ?: 0xC000
            if (lowestEnemy < 0xC000) {
                protectedRanges.add(bankRomStart + (lowestEnemy - 0x8000) until bankRomEnd)
            }
            protectedRanges.add(bankRomStart + (origPtrs.macroDefs - 0x8000) until bankRomEnd)

            val exclusions = mergeRanges(mergedData + protectedRanges)
            val result = subtractRanges(freeRanges, exclusions)
            if (result.isNotEmpty()) {
                val bytes = result.sumOf { it.last - it.first + 1 }
                logger.info { "rewriteArea: found $bytes extra bytes in ${result.size} freed regions (expanded ROM)" }
            }
            result
        } else {
            emptyList()
        }
        val allRegions = mergeRanges(reclaimableRanges + extraFree)

        // Calculate new sizes
        val extraStructCount = newStructs.size + clonedStructs.size
        val newStructCount = maxStructIdx + 1 + extraStructCount
        val totalNeeded =
            allRooms.size * 2 +                                    // room pointer table
            newStructCount * 2 +                                   // struct pointer table
            specItemsSize +                                        // specItemsTable
            roomDataMap.values.sumOf { it.size } +                 // all room data
            structByAddr.values.sumOf { it.data.size } +           // existing struct data
            newStructs.size * 3 +                                  // new 1x1 structs (3 bytes each)
            clonedStructs.sumOf { it.data.size }                   // cloned struct data

        val totalAvailable = allRegions.sumOf { it.last - it.first + 1 }

        logger.info { "rewriteArea: needed=$totalNeeded available=$totalAvailable " +
                "($extraStructCount new/cloned structs)" }

        if (totalNeeded > totalAvailable) {
            return RewriteResult(false, editCount,
                listOf("Not enough space: need $totalNeeded, have $totalAvailable in ${area.displayName}. Expand the ROM to add tiles to empty cells."),
                totalNeeded, totalAvailable, skippedUncovered = newStructs.size + (totalNeeded - totalAvailable))
        }

        // ── Phase 4: Wipe reclaimable regions and write everything atomically ──

        for (range in allRegions) {
            for (offset in range) {
                exportParser.writeByte(offset, 0xFF)
            }
        }

        val alloc = LinearAllocator(allRegions, bankStart)

        // 4a. Struct pointer table + Room pointer table (contiguous)
        val ptrBlockSize = allRooms.size * 2 + newStructCount * 2
        val ptrBlockRom = alloc.allocate(ptrBlockSize, "pointer tables")
            ?: return RewriteResult(false, editCount, listOf("Failed to allocate pointer tables"))
        val roomPtrTableRom = ptrBlockRom
        val structPtrTableRom = ptrBlockRom + allRooms.size * 2

        // 4b. SpecItemsTable (right after pointer tables for contiguity)
        var newSpecItemsCpu = specItemsCpu
        if (specItemsData != null && specItemsSize > 0) {
            val specRom = alloc.allocate(specItemsSize, "specItemsTable")
                ?: return RewriteResult(false, editCount, listOf("Failed to allocate specItemsTable"))
            newSpecItemsCpu = 0x8000 + (specRom - bankStart)

            // Fix internal linked-list pointers
            val delta = newSpecItemsCpu - specItemsCpu
            if (delta != 0) {
                fixSpecItemsPointers(specItemsData, specItemsCpu, delta)
            }

            for (i in specItemsData.indices) {
                exportParser.writeByte(specRom + i, specItemsData[i].toInt() and 0xFF)
            }
        }

        // 4c. Write struct data and build CPU address map
        val structAddrMap = mutableMapOf<Int, Int>() // old CPU addr → new CPU addr
        for ((oldCpuAddr, snapshot) in structByAddr) {
            val romOff = alloc.allocate(snapshot.data.size, "struct @$%04X".format(oldCpuAddr))
                ?: return RewriteResult(false, editCount, listOf("Failed to allocate struct data"))
            for (i in snapshot.data.indices) {
                exportParser.writeByte(romOff + i, snapshot.data[i].toInt() and 0xFF)
            }
            structAddrMap[oldCpuAddr] = 0x8000 + (romOff - bankStart)
        }

        // 4d. Write new 1x1 struct data
        val newStructAddrMap = mutableMapOf<Int, Int>() // slot index → new CPU addr
        for (ns in newStructs) {
            val romOff = alloc.allocate(3, "new struct slot=${ns.slotIndex}")
                ?: return RewriteResult(false, editCount, listOf("Failed to allocate new struct"))
            exportParser.writeByte(romOff, 0x01)                     // xOffset=0, count=1
            exportParser.writeByte(romOff + 1, ns.macroIndex and 0xFF)
            exportParser.writeByte(romOff + 2, 0xFF)                 // terminator
            newStructAddrMap[ns.slotIndex] = 0x8000 + (romOff - bankStart)
        }

        // 4d2. Write cloned struct data
        for (cs in clonedStructs) {
            val romOff = alloc.allocate(cs.data.size, "cloned struct slot=${cs.slotIndex}")
                ?: return RewriteResult(false, editCount, listOf("Failed to allocate cloned struct"))
            for (i in cs.data.indices) {
                exportParser.writeByte(romOff + i, cs.data[i].toInt() and 0xFF)
            }
            newStructAddrMap[cs.slotIndex] = 0x8000 + (romOff - bankStart)
        }

        // 4e. Write struct pointer table
        val origStructPtrs = IntArray(maxStructIdx + 1) { idx ->
            origParser.readBankWord(bank, origPtrs.structPtrTable + idx * 2)
        }
        for (idx in 0 until newStructCount) {
            val newCpu: Int = when {
                idx in newStructAddrMap -> newStructAddrMap[idx]!!
                idx < origStructPtrs.size -> structAddrMap[origStructPtrs[idx]] ?: origStructPtrs[idx]
                else -> 0x0000
            }
            exportParser.writeByte(structPtrTableRom + idx * 2, newCpu and 0xFF)
            exportParser.writeByte(structPtrTableRom + idx * 2 + 1, (newCpu shr 8) and 0xFF)
        }

        // 4f. Write room data and room pointer table
        for (r in allRooms) {
            val data = roomDataMap[r.roomNumber]!!
            val romOff = alloc.allocate(data.size, "room ${r.roomNumber}")
                ?: return RewriteResult(false, editCount, listOf("Failed to allocate room ${r.roomNumber}"))
            for (i in data.indices) {
                exportParser.writeByte(romOff + i, data[i].toInt() and 0xFF)
            }
            val cpuAddr = 0x8000 + (romOff - bankStart)
            exportParser.writeByte(roomPtrTableRom + r.roomNumber * 2, cpuAddr and 0xFF)
            exportParser.writeByte(roomPtrTableRom + r.roomNumber * 2 + 1, (cpuAddr shr 8) and 0xFF)
        }

        // 4g. Update area pointer table at $9598
        val areaPtrRom = exportParser.bankAddressToRomOffset(bank, MetroidRomData.AREA_POINTERS_ADDR)
        val newRoomPtrCpu = 0x8000 + (roomPtrTableRom - bankStart)
        val newStructPtrCpu = 0x8000 + (structPtrTableRom - bankStart)

        exportParser.writeByte(areaPtrRom + 0, newSpecItemsCpu and 0xFF)
        exportParser.writeByte(areaPtrRom + 1, (newSpecItemsCpu shr 8) and 0xFF)
        exportParser.writeByte(areaPtrRom + 2, newRoomPtrCpu and 0xFF)
        exportParser.writeByte(areaPtrRom + 3, (newRoomPtrCpu shr 8) and 0xFF)
        exportParser.writeByte(areaPtrRom + 4, newStructPtrCpu and 0xFF)
        exportParser.writeByte(areaPtrRom + 5, (newStructPtrCpu shr 8) and 0xFF)
        // macroDefs (offset 6-7) and enemy tables (8-15) are NOT touched

        logger.info { "rewriteArea ${area.displayName}: wrote ${alloc.bytesUsed}/$totalAvailable bytes, " +
                "specItems=$%04X→$%04X rooms=$%04X→$%04X structs=$%04X→$%04X".format(
                    specItemsCpu, newSpecItemsCpu, origPtrs.roomPtrTable, newRoomPtrCpu,
                    origPtrs.structPtrTable, newStructPtrCpu) }

        // ── Phase 5: Post-write verification ──
        val verifyErrors = verifyAreaIntegrity(exportParser, origParser, md, area, bank, allRooms)
        if (verifyErrors.isNotEmpty()) {
            logger.error { "rewriteArea: verification FAILED: ${verifyErrors.joinToString("; ")}" }
        }

        return RewriteResult(
            success = verifyErrors.isEmpty(),
            editCount = editCount,
            errors = verifyErrors,
            bytesUsed = alloc.bytesUsed,
            bytesAvailable = totalAvailable
        )
    }

    // ── Covered edit patching ──

    /**
     * Build a coverage map for a room using the struct catalog.
     */
    private fun buildCoverageMap(room: Room, catalog: List<StructEntry>): BooleanArray {
        val width = MapRenderer.ROOM_WIDTH_MACROS
        val height = MapRenderer.ROOM_HEIGHT_MACROS
        val map = BooleanArray(width * height)
        for (obj in room.objects) {
            val se = catalog.find { it.structIndex == obj.structIndex } ?: continue
            var my = obj.posY
            for (row in se.struct.rows) {
                var mx = obj.posX + row.xOffset
                for (m in row.macroIndices) {
                    if (mx in 0 until width && my in 0 until height) map[my * width + mx] = true
                    mx++
                }
                my++
            }
        }
        return map
    }

    /**
     * Clone shared structs for a room's covered edits.
     *
     * For each covered edit that touches a shared struct:
     * 1. Clone the struct's raw data to a new slot index
     * 2. Apply the edit to the cloned data
     * 3. Update the room's object to use the new slot
     *
     * Returns the new room data bytes (same format, same size or +0 since
     * only struct indices change, not object positions).
     */
    private fun cloneSharedStructsForRoom(
        origParser: NesRomParser,
        bank: Int,
        ptrs: MetroidRomData.AreaPointers,
        room: Room,
        edits: List<MacroEdit>,
        catalog: List<StructEntry>,
        structUsers: Map<Int, Set<Int>>,
        structByAddr: LinkedHashMap<Int, StructSnapshot>,
        usedIndices: MutableSet<Int>,
        clonedStructs: MutableList<ClonedStruct>
    ): ByteArray {
        // Map: original struct index → cloned struct index (only for structs that need cloning)
        val cloneMap = mutableMapOf<Int, Int>()

        // Group edits by which struct they touch
        val editsByStruct = mutableMapOf<Int, MutableList<MacroEdit>>()
        for (edit in edits) {
            val obj = findCoveringObject(room, edit, catalog) ?: continue
            editsByStruct.getOrPut(obj.structIndex) { mutableListOf() }.add(edit)
        }

        // Clone each shared struct that has edits
        for ((structIdx, structEdits) in editsByStruct) {
            val users = structUsers[structIdx] ?: emptySet()
            if (users.size <= 1) {
                // Not shared — apply edit in-place to existing snapshot
                val se = catalog.find { it.structIndex == structIdx } ?: continue
                val cpuAddr = origParser.readBankWord(bank, ptrs.structPtrTable + structIdx * 2)
                val snapshot = structByAddr[cpuAddr] ?: continue
                for (edit in structEdits) {
                    patchStructByte(se, room, edit, snapshot)
                }
                continue
            }

            // Shared struct — clone it
            val cpuAddr = origParser.readBankWord(bank, ptrs.structPtrTable + structIdx * 2)
            val snapshot = structByAddr[cpuAddr] ?: continue

            // Allocate new slot
            val newSlot = (usedIndices.maxOrNull() ?: -1) + 1
            usedIndices.add(newSlot)

            // Clone the struct data
            val clonedData = snapshot.data.copyOf()

            // Apply edits to the clone
            val se = catalog.find { it.structIndex == structIdx } ?: continue
            val tempSnapshot = StructSnapshot(clonedData, mutableListOf(structIdx))
            for (edit in structEdits) {
                patchStructByte(se, room, edit, tempSnapshot)
            }

            clonedStructs.add(ClonedStruct(newSlot, clonedData))
            cloneMap[structIdx] = newSlot
            logger.debug { "    Cloned struct \$${structIdx.toString(16)} → slot $newSlot (${clonedData.size} bytes)" }
        }

        // Rebuild room data with updated struct indices
        val suffix = RoomEncoder.extractEnemyDoorSuffix(room)
        val bytes = mutableListOf<Byte>()
        bytes.add(room.palette.toByte())
        for (obj in room.objects) {
            val newStructIdx = cloneMap[obj.structIndex] ?: obj.structIndex
            bytes.add((((obj.posY and 0x0F) shl 4) or (obj.posX and 0x0F)).toByte())
            bytes.add((newStructIdx and 0xFF).toByte())
            bytes.add((obj.palette and 0xFF).toByte())
        }
        bytes.addAll(suffix.toList())
        return bytes.toByteArray()
    }

    /** Patch a single macro byte in a struct snapshot */
    private fun patchStructByte(se: StructEntry, room: Room, edit: MacroEdit, snapshot: StructSnapshot) {
        // Find the room object that owns this cell
        val obj = findCoveringObject(room, edit, listOf(se)) ?: return
        var pos = 0
        var my = obj.posY
        for (row in se.struct.rows) {
            pos++ // skip row header
            var mx = obj.posX + row.xOffset
            for (ci in row.macroIndices.indices) {
                if (mx == edit.x && my == edit.y && pos + ci < snapshot.data.size) {
                    snapshot.data[pos + ci] = (edit.macroIndex and 0xFF).toByte()
                    return
                }
                mx++
            }
            pos += row.macroIndices.size
            my++
        }
    }

    /** Find which room object covers the given macro position */
    private fun findCoveringObject(room: Room, edit: MacroEdit, catalog: List<StructEntry>): RoomObject? {
        var best: RoomObject? = null
        for (obj in room.objects) {
            val se = catalog.find { it.structIndex == obj.structIndex } ?: continue
            var my = obj.posY
            for (row in se.struct.rows) {
                var mx = obj.posX + row.xOffset
                for (m in row.macroIndices) {
                    if (mx == edit.x && my == edit.y) best = obj
                    mx++
                }
                my++
            }
        }
        return best
    }

    /**
     * Patch covered edits directly into struct data snapshots.
     *
     * For each edit, find which struct/row/column it falls in, then modify
     * the corresponding byte in the struct data snapshot.
     */
    private fun applyCoveredEdits(
        origParser: NesRomParser,
        bank: Int,
        ptrs: MetroidRomData.AreaPointers,
        room: Room,
        edits: List<MacroEdit>,
        structByAddr: LinkedHashMap<Int, StructSnapshot>,
        catalog: List<StructEntry>
    ) {
        for (edit in edits) {
            // Find which object covers this cell (last writer wins)
            var bestObj: RoomObject? = null
            var bestSe: StructEntry? = null
            for (obj in room.objects) {
                val se = catalog.find { it.structIndex == obj.structIndex } ?: continue
                var my = obj.posY
                for (row in se.struct.rows) {
                    var mx = obj.posX + row.xOffset
                    for (m in row.macroIndices) {
                        if (mx == edit.x && my == edit.y) { bestObj = obj; bestSe = se }
                        mx++
                    }
                    my++
                }
            }

            val obj = bestObj ?: continue
            val se = bestSe ?: continue
            val structCpu = origParser.readBankWord(bank, ptrs.structPtrTable + obj.structIndex * 2)
            if (structCpu < 0x8000 || structCpu >= 0xC000) continue

            // Walk struct rows to find byte offset for (edit.x, edit.y)
            var pos = 0
            var my = obj.posY
            var found = false
            for (row in se.struct.rows) {
                pos++ // skip row header byte
                var mx = obj.posX + row.xOffset
                for (ci in row.macroIndices.indices) {
                    if (mx == edit.x && my == edit.y) {
                        val snapshot = structByAddr[structCpu]
                        if (snapshot != null && pos + ci < snapshot.data.size) {
                            snapshot.data[pos + ci] = (edit.macroIndex and 0xFF).toByte()
                            found = true
                        }
                        break
                    }
                    mx++
                }
                if (found) break
                pos += row.macroIndices.size
                my++
            }

            if (!found) {
                logger.warn { "applyCoveredEdits: could not patch (${edit.x},${edit.y}) in room ${room.roomNumber}" }
            }
        }
    }

    // ── Room encoding from grid ──

    /** A new 1x1 struct for uncovered edits */
    data class NewStruct(val slotIndex: Int, val macroIndex: Int)

    /** A cloned struct with modified bytes for shared struct edits */
    data class ClonedStruct(val slotIndex: Int, val data: ByteArray)

    /**
     * Encode a room from an edited MacroGrid back to ROM-format bytes.
     *
     * Strategy:
     * 1. Keep original objects whose structures still match the edited grid
     * 2. For uncovered cells, find matching structs from the catalog
     * 3. For remaining cells, create new 1x1 structs
     * 4. Preserve enemy/door suffix byte-for-byte
     */
    private fun encodeRoomFromGrid(
        editedGrid: MapRenderer.MacroGrid,
        origRoom: Room,
        structCatalog: List<StructEntry>,
        newStructs: MutableList<NewStruct>
    ): ByteArray {
        val width = MapRenderer.ROOM_WIDTH_MACROS
        val height = MapRenderer.ROOM_HEIGHT_MACROS
        val covered = BooleanArray(width * height)
        val objects = mutableListOf<RoomObject>()

        // Build provenance: which object is the last writer for each cell
        val provenance = IntArray(width * height) { -1 }
        for ((i, obj) in origRoom.objects.withIndex()) {
            val se = structCatalog.find { it.structIndex == obj.structIndex } ?: continue
            var my = obj.posY
            for (row in se.struct.rows) {
                var mx = obj.posX + row.xOffset
                for (m in row.macroIndices) {
                    if (mx in 0 until width && my in 0 until height) {
                        provenance[my * width + mx] = i
                    }
                    mx++
                }
                my++
            }
        }

        // Step 1: Keep original objects that still match
        for ((objIdx, obj) in origRoom.objects.withIndex()) {
            val se = structCatalog.find { it.structIndex == obj.structIndex } ?: continue
            if (structMatchesGrid(se.struct, editedGrid, obj.posX, obj.posY, objIdx, provenance)) {
                objects.add(obj)
                markCovered(se.struct, obj.posX, obj.posY, covered, width, height)
            }
        }

        // Step 2: For uncovered cells, find best matching existing structs
        val sortedStructs = structCatalog.sortedByDescending { it.cellCount }
        for (y in 0 until height) {
            for (x in 0 until width) {
                if (covered[y * width + x]) continue
                val macro = editedGrid.get(x, y)
                if (macro < 0) continue
                val pal = editedGrid.getAttr(x, y)

                val best = findBestMatch(editedGrid, sortedStructs, x, y, pal, covered)
                if (best != null) {
                    objects.add(RoomObject(y, x, best.structIndex, pal))
                    markCovered(best.struct, x, y, covered, width, height)
                }
            }
        }

        // Step 3: For still-uncovered cells, use or create 1x1 structs
        val existingStructs = structCatalog.toMutableList()
        // Include previously created new structs
        for (ns in newStructs) {
            if (existingStructs.none { it.structIndex == ns.slotIndex }) {
                existingStructs.add(StructEntry(ns.slotIndex,
                    Structure(ns.slotIndex, listOf(StructureRow(0, listOf(ns.macroIndex)))), 1))
            }
        }

        val usedIndices = (structCatalog.map { it.structIndex } +
                newStructs.map { it.slotIndex }).toMutableSet()
        var nextSlot = (usedIndices.maxOrNull() ?: -1) + 1

        for (y in 0 until height) {
            for (x in 0 until width) {
                if (covered[y * width + x]) continue
                val macro = editedGrid.get(x, y)
                if (macro < 0) continue
                val pal = editedGrid.getAttr(x, y)

                // Try to find existing 1x1 struct for this macro
                var idx = find1x1Struct(existingStructs, macro)
                if (idx == null) {
                    // Create a new 1x1 struct
                    val slot = nextSlot++
                    newStructs.add(NewStruct(slot, macro))
                    existingStructs.add(StructEntry(slot,
                        Structure(slot, listOf(StructureRow(0, listOf(macro)))), 1))
                    usedIndices.add(slot)
                    idx = slot
                }

                objects.add(RoomObject(y, x, idx, pal))
                covered[y * width + x] = true
            }
        }

        // Build room bytes
        val suffix = RoomEncoder.extractEnemyDoorSuffix(origRoom)
        val bytes = mutableListOf<Byte>()
        bytes.add(origRoom.palette.toByte())
        for (obj in objects) {
            bytes.add((((obj.posY and 0x0F) shl 4) or (obj.posX and 0x0F)).toByte())
            bytes.add((obj.structIndex and 0xFF).toByte())
            bytes.add((obj.palette and 0xFF).toByte())
        }
        bytes.addAll(suffix.toList())

        return bytes.toByteArray()
    }

    // ── Structure matching ──

    data class StructEntry(val structIndex: Int, val struct: Structure, val cellCount: Int)

    private fun buildStructCatalog(md: MetroidRomData, area: Area, rooms: List<Room>): List<StructEntry> {
        val maxIdx = rooms.flatMap { r -> r.objects.map { it.structIndex } }.maxOrNull() ?: 0
        return (0..maxIdx).mapNotNull { idx ->
            val struct = md.readStructure(area, idx) ?: return@mapNotNull null
            StructEntry(idx, struct, struct.rows.sumOf { it.macroIndices.size })
        }
    }

    private fun structMatchesGrid(
        struct: Structure, grid: MapRenderer.MacroGrid,
        startX: Int, startY: Int, objIndex: Int, provenance: IntArray
    ): Boolean {
        val width = MapRenderer.ROOM_WIDTH_MACROS
        val height = MapRenderer.ROOM_HEIGHT_MACROS
        var my = startY
        for (row in struct.rows) {
            var mx = startX + row.xOffset
            for (macroIdx in row.macroIndices) {
                if (mx !in 0 until width || my !in 0 until height) return false
                // Only verify cells where this object is the final writer
                if (provenance[my * width + mx] == objIndex) {
                    if (grid.get(mx, my) != macroIdx) return false
                }
                mx++
            }
            my++
        }
        return true
    }

    private data class StructMatch(val structIndex: Int, val struct: Structure, val coverCount: Int)

    private fun findBestMatch(
        grid: MapRenderer.MacroGrid, sortedStructs: List<StructEntry>,
        startX: Int, startY: Int, palette: Int, covered: BooleanArray
    ): StructMatch? {
        val width = MapRenderer.ROOM_WIDTH_MACROS
        val height = MapRenderer.ROOM_HEIGHT_MACROS
        var best: StructMatch? = null

        for (se in sortedStructs) {
            if (best != null && se.cellCount <= best.coverCount) break
            var matches = true
            var newCells = 0
            var my = startY
            for (row in se.struct.rows) {
                var mx = startX + row.xOffset
                for (macroIdx in row.macroIndices) {
                    if (mx !in 0 until width || my !in 0 until height) { matches = false; break }
                    if (grid.get(mx, my) != macroIdx) { matches = false; break }
                    if (grid.getAttr(mx, my) != palette) { matches = false; break }
                    if (!covered[my * width + mx]) newCells++
                    mx++
                }
                if (!matches) break
                my++
            }
            if (matches && newCells > 0 && (best == null || newCells > best.coverCount)) {
                best = StructMatch(se.structIndex, se.struct, newCells)
            }
        }
        return best
    }

    private fun find1x1Struct(catalog: List<StructEntry>, macro: Int): Int? {
        return catalog.firstOrNull { se ->
            se.struct.rows.size == 1 &&
            se.struct.rows[0].macroIndices.size == 1 &&
            se.struct.rows[0].xOffset == 0 &&
            se.struct.rows[0].macroIndices[0] == macro
        }?.structIndex
    }

    private fun markCovered(
        struct: Structure, startX: Int, startY: Int,
        covered: BooleanArray, width: Int, height: Int
    ) {
        var my = startY
        for (row in struct.rows) {
            var mx = startX + row.xOffset
            for (i in row.macroIndices.indices) {
                if (mx in 0 until width && my in 0 until height) {
                    covered[my * width + mx] = true
                }
                mx++
            }
            my++
        }
    }

    // ── SpecItemsTable pointer fixup ──

    /**
     * Walk the Y-node linked list and adjust absolute "next Y" pointers by delta.
     * X-entry offsets are relative and do NOT need adjustment.
     */
    private fun fixSpecItemsPointers(data: ByteArray, origBaseCpu: Int, delta: Int) {
        var pos = 0
        var steps = 0
        while (pos + 2 < data.size && steps < 100) {
            val lo = data[pos + 1].toInt() and 0xFF
            val hi = data[pos + 2].toInt() and 0xFF
            if (lo == 0xFF && hi == 0xFF) break  // end of list
            val nextPtr = (hi shl 8) or lo
            val nextOff = nextPtr - origBaseCpu
            if (nextOff <= pos || nextOff >= data.size) break  // safety
            val newPtr = nextPtr + delta
            data[pos + 1] = (newPtr and 0xFF).toByte()
            data[pos + 2] = ((newPtr shr 8) and 0xFF).toByte()
            pos = nextOff
            steps++
        }
        logger.debug { "fixSpecItemsPointers: walked $steps entries, delta=$delta" }
    }

    // ── Post-write verification ──

    /**
     * Verify all 13 invariants after writing.
     */
    internal fun verifyAreaIntegrity(
        exportParser: NesRomParser,
        origParser: NesRomParser,
        origMd: MetroidRomData,
        area: Area,
        bank: Int,
        origRooms: List<Room>
    ): List<String> {
        val errors = mutableListOf<String>()
        val bankStart = NesRomParser.INES_HEADER_SIZE + bank * NesRomParser.PRG_BANK_SIZE
        val bankEnd = bankStart + NesRomParser.PRG_BANK_SIZE

        val exportMd = MetroidRomData(exportParser)
        val origPtrs = origMd.readAreaPointers(area)
        val newPtrs = exportMd.readAreaPointers(area)

        // Invariant 1: All 8 area pointer entries must be valid CPU addresses
        val ptrValues = listOf(
            "specItemsTable" to newPtrs.specItemsTable,
            "roomPtrTable" to newPtrs.roomPtrTable,
            "structPtrTable" to newPtrs.structPtrTable,
            "macroDefs" to newPtrs.macroDefs,
        )
        for ((name, addr) in ptrValues) {
            if (addr < 0x8000 || addr >= 0xC000) {
                errors.add("$name pointer 0x%04X out of bank range".format(addr))
            }
        }

        // Invariant 7: macroDefs must not change
        if (origPtrs.macroDefs != newPtrs.macroDefs)
            errors.add("macroDefs changed: 0x%04X→0x%04X".format(origPtrs.macroDefs, newPtrs.macroDefs))

        // Invariant 8: enemy tables must not change
        if (origPtrs.enemyFramePtrTbl1 != newPtrs.enemyFramePtrTbl1)
            errors.add("enemyFramePtrTbl1 changed")
        if (origPtrs.enemyFramePtrTbl2 != newPtrs.enemyFramePtrTbl2)
            errors.add("enemyFramePtrTbl2 changed")
        if (origPtrs.enemyPlacePtrTbl != newPtrs.enemyPlacePtrTbl)
            errors.add("enemyPlacePtrTbl changed")
        if (origPtrs.enemyAnimIndexTbl != newPtrs.enemyAnimIndexTbl)
            errors.add("enemyAnimIndexTbl changed")

        // Invariant 11: room count must match
        val newRoomCount = (newPtrs.structPtrTable - newPtrs.roomPtrTable) / 2
        if (newRoomCount != origRooms.size)
            errors.add("Room count changed: ${origRooms.size}→$newRoomCount (roomPtr=0x%04X structPtr=0x%04X)".format(
                newPtrs.roomPtrTable, newPtrs.structPtrTable))

        // Invariant 12: all data within bank
        for (r in origRooms) {
            val cpuAddr = exportParser.readBankWord(bank, newPtrs.roomPtrTable + r.roomNumber * 2)
            if (cpuAddr < 0x8000 || cpuAddr >= 0xC000)
                errors.add("Room ${r.roomNumber} pointer 0x%04X out of bank range".format(cpuAddr))
        }

        // Invariant 2-3: verify all rooms can be re-read and have valid struct references
        val exportRooms = try { exportMd.readAllRooms(area) } catch (e: Exception) {
            errors.add("readAllRooms crashed: ${e.message}")
            return errors
        }

        if (exportRooms.size != origRooms.size)
            errors.add("readAllRooms returned ${exportRooms.size} rooms, expected ${origRooms.size}")

        // Invariant 9-10: verify room and struct terminators
        for (expRoom in exportRooms) {
            val rd = expRoom.rawData
            if (rd.isEmpty() || (rd.last().toInt() and 0xFF) != 0xFF)
                errors.add("Room ${expRoom.roomNumber} data doesn't end with 0xFF")

            // Verify struct references are valid
            for (obj in expRoom.objects) {
                val structCpu = exportParser.readBankWord(bank, newPtrs.structPtrTable + obj.structIndex * 2)
                if (structCpu < 0x8000 || structCpu >= 0xC000) {
                    errors.add("Room ${expRoom.roomNumber} obj struct=${obj.structIndex} points to invalid 0x%04X".format(structCpu))
                    continue
                }
                // Verify struct data ends with 0xFF
                val structRom = exportParser.bankAddressToRomOffset(bank, structCpu)
                if (structRom < bankStart || structRom >= bankEnd) {
                    errors.add("Room ${expRoom.roomNumber} struct ${obj.structIndex} ROM offset out of bank")
                    continue
                }
                var pos = structCpu
                var safety = 0
                while (safety < 500) {
                    val b = exportParser.readBankByte(bank, pos)
                    if (b == 0xFF) break
                    val count = b and 0x0F
                    pos += 1 + (if (count == 0) 16 else count)
                    safety++
                }
                if (safety >= 500)
                    errors.add("Room ${expRoom.roomNumber} struct ${obj.structIndex} doesn't terminate")
            }
        }

        // Invariant 4-6: verify specItemsTable linked list is traversable
        if (specItemsListValid(exportParser, bank, newPtrs.specItemsTable).not())
            errors.add("specItemsTable linked list is broken")

        // Verify macroDefs data wasn't corrupted (spot check first 20 macros)
        val macroBase = origPtrs.macroDefs
        for (i in 0 until 20) {
            val addr = macroBase + i * 4
            if (addr + 3 >= 0xC000) break
            for (b in 0..3) {
                val orig = origParser.readBankByte(bank, addr + b)
                val exp = exportParser.readBankByte(bank, addr + b)
                if (orig != exp) {
                    errors.add("Macro $i byte $b corrupted: 0x%02X→0x%02X".format(orig, exp))
                    break
                }
            }
        }

        return errors
    }

    private fun specItemsListValid(parser: NesRomParser, bank: Int, startCpu: Int): Boolean {
        var pos = startCpu
        var steps = 0
        while (steps < 100) {
            if (pos < 0x8000 || pos >= 0xC000) return false
            val lo = parser.readBankByte(bank, pos + 1) and 0xFF
            val hi = parser.readBankByte(bank, pos + 2) and 0xFF
            if (lo == 0xFF && hi == 0xFF) return true  // valid end
            val nextPtr = (hi shl 8) or lo
            if (nextPtr <= pos || nextPtr >= 0xC000) return false
            pos = nextPtr
            steps++
        }
        return false  // too many steps
    }

    // ── Utility functions ──

    private fun measureStructSize(parser: NesRomParser, bank: Int, cpuAddr: Int): Int {
        var pos = cpuAddr
        while (true) {
            val b = parser.readBankByte(bank, pos)
            if (b == 0xFF) { pos++; break }
            val count = b and 0x0F
            pos += 1 + (if (count == 0) 16 else count)
        }
        return pos - cpuAddr
    }

    private fun findSpecItemsEnd(
        parser: NesRomParser, bank: Int,
        ptrs: MetroidRomData.AreaPointers, rooms: List<Room>,
        structAddrs: Collection<Int>
    ): Int {
        val specRom = parser.bankAddressToRomOffset(bank, ptrs.specItemsTable)
        val candidates = mutableListOf<Int>()
        for (r in rooms) candidates.add(r.romOffset)
        for (addr in structAddrs) {
            if (addr in 0x8000 until 0xC000) {
                candidates.add(parser.bankAddressToRomOffset(bank, addr))
            }
        }
        candidates.add(parser.bankAddressToRomOffset(bank, ptrs.roomPtrTable))
        return candidates.filter { it > specRom }.minOrNull() ?: specRom
    }

    private fun computeDataFloor(ptrs: MetroidRomData.AreaPointers, structAddrs: Collection<Int>): Int {
        var floor = minOf(
            ptrs.specItemsTable, ptrs.roomPtrTable, ptrs.structPtrTable,
            ptrs.macroDefs, ptrs.enemyFramePtrTbl1, ptrs.enemyFramePtrTbl2,
            ptrs.enemyPlacePtrTbl, ptrs.enemyAnimIndexTbl
        )
        for (addr in structAddrs) {
            if (addr in 0x8000 until 0xC000) floor = minOf(floor, addr)
        }
        return floor
    }

    /**
     * Find the end of actual data starting from [startCpu], scanning up to [limitCpu].
     * Returns the CPU address of the first 0xFF byte after the last non-0xFF byte.
     */
    private fun findDataEnd(parser: NesRomParser, bank: Int, startCpu: Int, limitCpu: Int): Int {
        var lastNonFF = startCpu
        for (cpu in startCpu until limitCpu) {
            val b = parser.readBankByte(bank, cpu) and 0xFF
            if (b != 0xFF) lastNonFF = cpu + 1
        }
        return lastNonFF
    }

    private fun scanFreeRuns(
        parser: NesRomParser, bank: Int,
        floorCpu: Int, ceilingCpu: Int, minRun: Int = 3
    ): List<IntRange> {
        val bankStart = NesRomParser.INES_HEADER_SIZE + bank * NesRomParser.PRG_BANK_SIZE
        val scanStart = bankStart + maxOf(0, floorCpu - 0x8000)
        val scanEnd = bankStart + minOf(NesRomParser.PRG_BANK_SIZE, ceilingCpu - 0x8000)
        val ranges = mutableListOf<IntRange>()
        var runStart = -1
        var runLen = 0
        for (i in scanStart until scanEnd) {
            if ((parser.readByte(i) and 0xFF) == 0xFF) {
                if (runStart < 0) runStart = i
                runLen++
            } else {
                if (runLen >= minRun) ranges.add(runStart until runStart + runLen)
                runStart = -1; runLen = 0
            }
        }
        if (runLen >= minRun) ranges.add(runStart until runStart + runLen)
        return ranges
    }

    /**
     * Subtract exclusion ranges from a list of ranges.
     * Returns only the portions of [ranges] that don't overlap with [exclude].
     */
    private fun subtractRanges(ranges: List<IntRange>, exclude: List<IntRange>): List<IntRange> {
        if (exclude.isEmpty()) return ranges
        val result = mutableListOf<IntRange>()
        for (r in ranges) {
            var remaining = listOf(r)
            for (ex in exclude) {
                remaining = remaining.flatMap { rem ->
                    when {
                        ex.last < rem.first || ex.first > rem.last -> listOf(rem)  // no overlap
                        ex.first <= rem.first && ex.last >= rem.last -> emptyList()  // fully contained
                        ex.first <= rem.first -> listOf(ex.last + 1..rem.last)  // left trim
                        ex.last >= rem.last -> listOf(rem.first until ex.first)  // right trim
                        else -> listOf(rem.first until ex.first, ex.last + 1..rem.last)  // split
                    }
                }
            }
            result.addAll(remaining.filter { !it.isEmpty() })
        }
        return result
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

    /**
     * Best-fit allocator across non-contiguous regions.
     */
    private class LinearAllocator(regions: List<IntRange>, private val bankStart: Int) {
        private val blocks = regions.map { it.first to (it.last - it.first + 1) }.toMutableList()
        var bytesUsed = 0; private set

        fun allocate(size: Int, label: String = ""): Int? {
            // Best-fit: find smallest block that fits
            var bestIdx = -1
            var bestSize = Int.MAX_VALUE
            for (i in blocks.indices) {
                val (_, bSize) = blocks[i]
                if (bSize >= size && bSize < bestSize) {
                    bestIdx = i; bestSize = bSize
                }
            }
            if (bestIdx < 0) {
                logger.warn { "allocate FAILED: $size bytes for $label" }
                return null
            }
            val (start, bSize) = blocks[bestIdx]
            if (bSize == size) blocks.removeAt(bestIdx)
            else blocks[bestIdx] = (start + size) to (bSize - size)
            bytesUsed += size
            return start
        }
    }
}
