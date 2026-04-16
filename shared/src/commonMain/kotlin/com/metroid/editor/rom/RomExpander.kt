package com.metroid.editor.rom

import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Expands a standard 128KB Metroid NES ROM to 256KB (Expando format).
 *
 * This follows Editroid 3.0's proven expansion approach:
 * 1. Double ROM size from 8 to 16 PRG banks
 * 2. Copy fixed bank (bank 7) to end of expanded ROM
 * 3. Apply bankswitch hack (11 bytes) — allows accessing banks 8-15
 * 4. Apply PPU loader patch (226 bytes) — redirects pattern loading to new banks
 * 5. Relocate pattern data from area banks to new banks 7-8
 * 6. Zero out old pattern locations, freeing ~2KB per area bank
 *
 * After expansion, each area's bank has ~2KB more space for room/struct data,
 * enabling uncovered edits that would fail on standard ROMs.
 */
object RomExpander {

    const val STANDARD_ROM_SIZE = 0x20010  // 128KB + 16-byte header
    const val EXPANDED_ROM_SIZE = 0x40010  // 256KB + 16-byte header
    const val PRG_BANK_SIZE = 0x4000       // 16KB per PRG bank
    const val HEADER_SIZE = 0x10
    const val BANK_COUNT_OFFSET = 0x04     // iNES header byte for PRG bank count

    /** The 11-byte bankswitch hack from Editroid — NOPs out STA SwitchUprBits */
    private val BANKING_HACK = byteArrayOf(
        0x98.toByte(), 0x85.toByte(), 0x00.toByte(),  // TYA; STA $00
        0xA5.toByte(), 0x28.toByte(),                  // LDA $28 (SwitchUprBits)
        0x29.toByte(), 0x18.toByte(),                  // AND #$18
        0x05.toByte(), 0x00.toByte(),                  // ORA $00
        0xEA.toByte(), 0xEA.toByte()                   // NOP; NOP (replaces STA SwitchUprBits)
    )
    private const val BANKING_HACK_ROM_OFFSET = 0x3C4FF  // CPU $C4EF in expanded fixed bank

    /** ROM offset of PPU loader patch in expanded ROM */
    private const val PPU_LOADER_ROM_OFFSET = 0x3C5E7    // CPU $C5D7 in expanded fixed bank

    /**
     * The 226-byte PPU loader patch from Editroid.
     * Contains new InitXxxGFX routines + GFXInfo table pointing to relocated patterns.
     */
    private val PPU_LOADER = byteArrayOf(
        // InitTitleGFX ($C5D7)
        0xA0.toByte(), 0x0E.toByte(), 0x20.toByte(), 0xAB.toByte(), 0xC7.toByte(),  // LDY #$0E; JSR LoadGFX
        0xA0.toByte(), 0x00.toByte(), 0x20.toByte(), 0xAB.toByte(), 0xC7.toByte(),  // LDY #$00; JSR LoadGFX
        0xAD.toByte(), 0xB3.toByte(), 0x69.toByte(),                                // LDA JustInBailey ($69B3)
        0xF0.toByte(), 0x05.toByte(),                                                // BEQ +5
        0xA0.toByte(), 0x11.toByte(), 0x20.toByte(), 0xAB.toByte(), 0xC7.toByte(),  // LDY #$11; JSR LoadGFX
        0xA0.toByte(), 0x0D.toByte(), 0x20.toByte(), 0xAB.toByte(), 0xC7.toByte(),  // LDY #$0D; JSR LoadGFX
        0xA0.toByte(), 0x0F.toByte(), 0x20.toByte(), 0xAB.toByte(), 0xC7.toByte(),  // LDY #$0F; JSR LoadGFX
        0xA0.toByte(), 0x10.toByte(), 0x4C.toByte(), 0xAB.toByte(), 0xC7.toByte(),  // LDY #$10; JMP LoadGFX
        // InitBrinstarGFX ($C5FA)
        0xA0.toByte(), 0x03.toByte(), 0x20.toByte(), 0xAB.toByte(), 0xC7.toByte(),  // LDY #$03; JSR LoadGFX
        0xA0.toByte(), 0x08.toByte(), 0x4C.toByte(), 0xAB.toByte(), 0xC7.toByte(),  // LDY #$08; JMP LoadGFX
        // InitNorfairGFX ($C604)
        0xA0.toByte(), 0x04.toByte(), 0x20.toByte(), 0xAB.toByte(), 0xC7.toByte(),
        0xA0.toByte(), 0x09.toByte(), 0x4C.toByte(), 0xAB.toByte(), 0xC7.toByte(),
        // InitTourianGFX ($C60E)
        0xA0.toByte(), 0x07.toByte(), 0x20.toByte(), 0xAB.toByte(), 0xC7.toByte(),
        0xA0.toByte(), 0x0C.toByte(), 0x4C.toByte(), 0xAB.toByte(), 0xC7.toByte(),
        // InitKraidGFX ($C618)
        0xA0.toByte(), 0x06.toByte(), 0x20.toByte(), 0xAB.toByte(), 0xC7.toByte(),
        0xA0.toByte(), 0x0B.toByte(), 0x4C.toByte(), 0xAB.toByte(), 0xC7.toByte(),
        // InitRidleyGFX ($C622)
        0xA0.toByte(), 0x05.toByte(), 0x20.toByte(), 0xAB.toByte(), 0xC7.toByte(),
        0xA0.toByte(), 0x0A.toByte(), 0x4C.toByte(), 0xAB.toByte(), 0xC7.toByte(),
        // InitGFX6 ($C62C)
        0xA0.toByte(), 0x01.toByte(), 0x20.toByte(), 0xAB.toByte(), 0xC7.toByte(),
        0xA0.toByte(), 0x02.toByte(), 0x4C.toByte(), 0xAB.toByte(), 0xC7.toByte(),
        // InitGFX7 ($C636)
        0xA0.toByte(), 0x0F.toByte(), 0x4C.toByte(), 0xAB.toByte(), 0xC7.toByte(),

        // GFXInfo table ($C63B) — 18 entries × 7 bytes
        // Format: [bank] [cpuAddr_lo] [cpuAddr_hi] [ppuDest_lo] [ppuDest_hi] [byteCount_lo] [byteCount_hi]
        // (0) Player sprites: Bank 8, $A400→PPU $0000, $09A0 bytes
        0x08.toByte(), 0x00.toByte(), 0xA4.toByte(), 0x00.toByte(), 0x00.toByte(), 0xA0.toByte(), 0x09.toByte(),
        // (1) Ending sprites: Bank 9, $8400→PPU $0000, $0520 bytes
        0x09.toByte(), 0x00.toByte(), 0x84.toByte(), 0x00.toByte(), 0x00.toByte(), 0x20.toByte(), 0x05.toByte(),
        // (2) "The End" graphics: Bank 9, $8000→PPU $1000, $0400 bytes
        0x09.toByte(), 0x00.toByte(), 0x80.toByte(), 0x00.toByte(), 0x10.toByte(), 0x00.toByte(), 0x04.toByte(),
        // (3) Brinstar BG: Bank 7, $8000→PPU $1000, $1000 bytes
        0x07.toByte(), 0x00.toByte(), 0x80.toByte(), 0x00.toByte(), 0x10.toByte(), 0x00.toByte(), 0x10.toByte(),
        // (4) Norfair BG: Bank 7, $9000→PPU $1000, $1000 bytes
        0x07.toByte(), 0x00.toByte(), 0x90.toByte(), 0x00.toByte(), 0x10.toByte(), 0x00.toByte(), 0x10.toByte(),
        // (5) Ridley BG: Bank 7, $A000→PPU $1000, $1000 bytes
        0x07.toByte(), 0x00.toByte(), 0xA0.toByte(), 0x00.toByte(), 0x10.toByte(), 0x00.toByte(), 0x10.toByte(),
        // (6) Kraid BG: Bank 7, $B000→PPU $1000, $1000 bytes
        0x07.toByte(), 0x00.toByte(), 0xB0.toByte(), 0x00.toByte(), 0x10.toByte(), 0x00.toByte(), 0x10.toByte(),
        // (7) Tourian BG: Bank 8, $8000→PPU $1000, $1000 bytes
        0x08.toByte(), 0x00.toByte(), 0x80.toByte(), 0x00.toByte(), 0x10.toByte(), 0x00.toByte(), 0x10.toByte(),
        // (8) Brinstar sprites: Bank 8, $9000→PPU $0C00, $0400 bytes
        0x08.toByte(), 0x00.toByte(), 0x90.toByte(), 0x00.toByte(), 0x0C.toByte(), 0x00.toByte(), 0x04.toByte(),
        // (9) Norfair sprites: Bank 8, $9400→PPU $0C00, $0400 bytes
        0x08.toByte(), 0x00.toByte(), 0x94.toByte(), 0x00.toByte(), 0x0C.toByte(), 0x00.toByte(), 0x04.toByte(),
        // (A) Ridley sprites: Bank 8, $9C00→PPU $0C00, $0400 bytes
        0x08.toByte(), 0x00.toByte(), 0x9C.toByte(), 0x00.toByte(), 0x0C.toByte(), 0x00.toByte(), 0x04.toByte(),
        // (B) Kraid sprites: Bank 8, $A000→PPU $0C00, $0400 bytes
        0x08.toByte(), 0x00.toByte(), 0xA0.toByte(), 0x00.toByte(), 0x0C.toByte(), 0x00.toByte(), 0x04.toByte(),
        // (C) Tourian sprites: Bank 8, $9800→PPU $0C00, $0400 bytes
        0x08.toByte(), 0x00.toByte(), 0x98.toByte(), 0x00.toByte(), 0x0C.toByte(), 0x00.toByte(), 0x04.toByte(),
        // (D) Title sprites: Bank 8, $ADA0→PPU $0C00, $0100 bytes
        0x08.toByte(), 0xA0.toByte(), 0xAD.toByte(), 0x00.toByte(), 0x0C.toByte(), 0x00.toByte(), 0x01.toByte(),
        // (E) "Metroid" logo: Bank 8, $AF00→PPU $1400, $0500 bytes
        0x08.toByte(), 0x00.toByte(), 0xAF.toByte(), 0x00.toByte(), 0x14.toByte(), 0x00.toByte(), 0x05.toByte(),
        // (F) Font: Bank 8, $B400→PPU $1000, $0400 bytes
        0x08.toByte(), 0x00.toByte(), 0xB4.toByte(), 0x00.toByte(), 0x10.toByte(), 0x00.toByte(), 0x04.toByte(),
        // (10) Digit sprites: Bank 8, $B400→PPU $0A00, $00A0 bytes
        0x08.toByte(), 0x00.toByte(), 0xB4.toByte(), 0x00.toByte(), 0x0A.toByte(), 0xA0.toByte(), 0x00.toByte(),
        // (11) Suitless Samus: Bank 8, $B800→PPU $0000, $07B0 bytes
        0x08.toByte(), 0x00.toByte(), 0xB8.toByte(), 0x00.toByte(), 0x00.toByte(), 0xB0.toByte(), 0x07.toByte(),
    )

    /**
     * Original pattern group table: 29 entries at ROM offset 0x1C6F0 in the fixed bank.
     * Each entry is 7 bytes: [bank] [cpuLo] [cpuHi] [ppuLo] [ppuHi] [lenLo] [lenHi]
     */
    private const val ORIG_GFX_TABLE_OFFSET = 0x1C6F0
    private const val ORIG_GFX_ENTRY_COUNT = 29
    private const val GFX_ENTRY_SIZE = 7

    /**
     * Pattern relocation destinations in expanded ROM (from Editroid Expander.cs).
     * Index is area ordinal (Brinstar=0, Norfair=1, Tourian=2, Ridley=3, Kraid=4).
     */
    private val BG_DUMP_OFFSETS = intArrayOf(0x1C010, 0x1D010, 0x20010, 0x1F010, 0x1E010)
    private val SPR_OFFSETS = intArrayOf(0x21010, 0x21410, 0x21810, 0x22010, 0x21C10)

    /** Which GFXInfo group index holds sprites for each area */
    private val AREA_SPR_GROUPS = intArrayOf(6, 9, 0x0E, 0x11, 0x13)

    /** Misc pattern copy destinations (from Editroid CopyMiscPatterns) */
    private data class MiscPatternCopy(val groupIndex: Int, val destRomOffset: Int)
    private val MISC_PATTERNS = listOf(
        MiscPatternCopy(0x00, 0x22410),  // Player sprites
        MiscPatternCopy(0x14, 0x22DB0),  // Title screen sprites
        MiscPatternCopy(0x15, 0x22F10),  // Title screen BG
        MiscPatternCopy(0x17, 0x23410),  // Letters
        MiscPatternCopy(0x1B, 0x23810),  // Suitless sprites
        MiscPatternCopy(0x02, 0x24010),  // "The End" graphics
        MiscPatternCopy(0x01, 0x24410),  // Ending sprites
    )

    /** Area bank assignments matching MetroidRomData.AREA_BANKS */
    private val AREA_BANKS = intArrayOf(1, 2, 5, 3, 4) // Brinstar, Norfair, Tourian, Ridley, Kraid

    /**
     * BG pattern group indices for each area — from PatternGroupIndex table in ROM.
     * Each area's compiled PPU dump must include:
     * - Group 2 (base BG patterns, originally loaded by InitGFX6 but no longer in expanded ROM)
     * - All area-specific BG groups from the PatternGroupIndex table
     * Groups are layered in order — later groups overwrite earlier ones at overlapping PPU offsets.
     */
    private val AREA_BG_GROUPS = arrayOf(
        intArrayOf(2, 3, 4, 5, 22),             // Brinstar: base + area-specific
        intArrayOf(2, 4, 5, 7, 8, 22),           // Norfair
        intArrayOf(2, 5, 10, 11, 12, 13, 26, 28, 22),  // Tourian
        intArrayOf(2, 4, 5, 10, 18, 22),         // Ridley
        intArrayOf(2, 4, 5, 10, 15, 16, 22),     // Kraid
    )

    fun isStandardRom(romData: ByteArray): Boolean =
        romData.size in STANDARD_ROM_SIZE..STANDARD_ROM_SIZE + 1024

    fun isExpandedRom(romData: ByteArray): Boolean =
        romData.size >= EXPANDED_ROM_SIZE

    /**
     * Expand a standard 128KB ROM to 256KB Expando format.
     * Returns the expanded ROM data, or null on failure.
     */
    fun expand(romData: ByteArray): ByteArray? {
        if (romData.size < STANDARD_ROM_SIZE) {
            logger.error { "ROM too small: ${romData.size} bytes" }
            return null
        }
        if (romData.size >= EXPANDED_ROM_SIZE) {
            logger.warn { "ROM already expanded: ${romData.size} bytes" }
            return romData.copyOf()
        }

        logger.info { "Expanding ROM from ${romData.size} to $EXPANDED_ROM_SIZE bytes" }
        val newRom = ByteArray(EXPANDED_ROM_SIZE)

        // Step 1: Copy first 7 banks + header (everything except fixed bank)
        val unmovedSize = STANDARD_ROM_SIZE - PRG_BANK_SIZE // 0x1C010
        romData.copyInto(newRom, 0, 0, unmovedSize)

        // Step 2: Copy fixed bank (bank 7) to end of expanded ROM
        val fixedBankSrc = STANDARD_ROM_SIZE - PRG_BANK_SIZE  // 0x1C010
        val fixedBankDst = EXPANDED_ROM_SIZE - PRG_BANK_SIZE  // 0x3C010
        romData.copyInto(newRom, fixedBankDst, fixedBankSrc, fixedBankSrc + PRG_BANK_SIZE)

        // Step 3: Update iNES header — PRG bank count
        val bankCount = (EXPANDED_ROM_SIZE - HEADER_SIZE) / PRG_BANK_SIZE
        newRom[BANK_COUNT_OFFSET] = bankCount.toByte()

        // Step 4: Read original GFXInfo table (before patching)
        val origGfxEntries = readGfxTable(romData)

        // Step 5: Relocate BG patterns for each area
        for (areaIdx in 0 until 5) {
            val bgGroups = AREA_BG_GROUPS[areaIdx]
            val destBase = BG_DUMP_OFFSETS[areaIdx]
            for (groupIdx in bgGroups) {
                val entry = origGfxEntries[groupIdx]
                if (entry.ppuDest >= 0x1000) { // BG pattern
                    val relOffset = entry.ppuDest - 0x1000
                    val dest = destBase + relOffset
                    romData.copyInto(newRom, dest, entry.romOffset, entry.romOffset + entry.byteCount)
                    logger.debug { "  BG group $groupIdx: ROM 0x${entry.romOffset.toString(16)} → 0x${dest.toString(16)} (${entry.byteCount} bytes)" }
                }
            }
        }

        // Step 6: Relocate sprite patterns for each area
        for (areaIdx in 0 until 5) {
            val groupIdx = AREA_SPR_GROUPS[areaIdx]
            val entry = origGfxEntries[groupIdx]
            val dest = SPR_OFFSETS[areaIdx]
            romData.copyInto(newRom, dest, entry.romOffset, entry.romOffset + entry.byteCount)
            logger.debug { "  SPR group $groupIdx: ROM 0x${entry.romOffset.toString(16)} → 0x${dest.toString(16)} (${entry.byteCount} bytes)" }
        }

        // Step 7: Copy misc patterns (player sprites, title screen, etc.)
        for (misc in MISC_PATTERNS) {
            val entry = origGfxEntries[misc.groupIndex]
            romData.copyInto(newRom, misc.destRomOffset, entry.romOffset, entry.romOffset + entry.byteCount)
            logger.debug { "  MISC group ${misc.groupIndex}: ROM 0x${entry.romOffset.toString(16)} → 0x${misc.destRomOffset.toString(16)} (${entry.byteCount} bytes)" }
        }

        // Step 8: Zero out old pattern locations to free space in area banks
        for (entry in origGfxEntries) {
            // Only zero patterns in area banks (1-5), not bank 6 or fixed bank
            if (entry.bank in 1..5) {
                for (i in 0 until entry.byteCount) {
                    newRom[entry.romOffset + i] = 0x00
                }
                logger.debug { "  Zeroed ${entry.byteCount} bytes at ROM 0x${entry.romOffset.toString(16)} (bank ${entry.bank})" }
            }
        }

        // Step 9: Apply ASM patches
        BANKING_HACK.copyInto(newRom, BANKING_HACK_ROM_OFFSET)
        PPU_LOADER.copyInto(newRom, PPU_LOADER_ROM_OFFSET)

        // Step 10: Update JSR/JMP pointers in fixed bank to new Init routines
        prgWriteWord(newRom, 0xC54D, 0xC5D7)  // JSR InitTitleGFX
        prgWriteWord(newRom, 0xC573, 0xC5FA)  // JSR InitBrinstarGFX
        prgWriteWord(newRom, 0xC58B, 0xC604)  // JSR InitNorfairGFX
        prgWriteWord(newRom, 0xC5A3, 0xC60E)  // JSR InitTourianGFX
        prgWriteWord(newRom, 0xC5BE, 0xC618)  // JSR InitKraidGFX
        prgWriteWord(newRom, 0xC5CB, 0xC622)  // JSR InitRidleyGFX
        prgWriteWord(newRom, 0xC5D5, 0xC62C)  // JSR InitGFX6
        prgWriteWord(newRom, 0xC7B7, 0xC63B)  // LDA GFXInfo table pointer

        // Update JSR InitGFX7 in title bank (3 call sites)
        newRom[0x1135] = 0x36.toByte(); newRom[0x1136] = 0xC6.toByte()
        newRom[0x1378] = 0x36.toByte(); newRom[0x1379] = 0xC6.toByte()
        newRom[0x13B9] = 0x36.toByte(); newRom[0x13BA] = 0xC6.toByte()

        logger.info { "ROM expansion complete: ${newRom.size} bytes, ${bankCount} PRG banks" }
        return newRom
    }

    /** Write a 16-bit word to the expanded fixed bank at the given CPU address */
    private fun prgWriteWord(rom: ByteArray, cpuAddr: Int, value: Int) {
        val romOffset = 0x3C010 + (cpuAddr - 0xC000)
        rom[romOffset] = (value and 0xFF).toByte()
        rom[romOffset + 1] = ((value shr 8) and 0xFF).toByte()
    }

    private data class GfxEntry(
        val bank: Int, val cpuAddr: Int, val ppuDest: Int, val byteCount: Int
    ) {
        val romOffset: Int get() = HEADER_SIZE + bank * PRG_BANK_SIZE + (cpuAddr - 0x8000)
    }

    private fun readGfxTable(romData: ByteArray): List<GfxEntry> {
        return (0 until ORIG_GFX_ENTRY_COUNT).map { i ->
            val off = ORIG_GFX_TABLE_OFFSET + i * GFX_ENTRY_SIZE
            GfxEntry(
                bank = romData[off].toInt() and 0xFF,
                cpuAddr = (romData[off + 1].toInt() and 0xFF) or ((romData[off + 2].toInt() and 0xFF) shl 8),
                ppuDest = (romData[off + 3].toInt() and 0xFF) or ((romData[off + 4].toInt() and 0xFF) shl 8),
                byteCount = (romData[off + 5].toInt() and 0xFF) or ((romData[off + 6].toInt() and 0xFF) shl 8)
            )
        }
    }
}
