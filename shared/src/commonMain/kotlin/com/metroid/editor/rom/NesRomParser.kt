package com.metroid.editor.rom

/**
 * Parser for NES ROM files in iNES format (.nes).
 *
 * iNES header (16 bytes):
 *   0-3:  "NES\x1A" magic
 *   4:    PRG ROM size in 16KB units
 *   5:    CHR ROM size in 8KB units (0 = CHR RAM)
 *   6:    Flags 6 (mirroring, battery, trainer, mapper low nibble)
 *   7:    Flags 7 (VS/Playchoice, NES 2.0, mapper high nibble)
 *   8:    PRG RAM size in 8KB units
 *   9-15: Padding
 *
 * Metroid (USA) specifics:
 *   Mapper 1 (MMC1), 8×16KB PRG banks = 128KB, 0 CHR ROM (uses CHR RAM),
 *   battery-backed SRAM at $6000-$7FFF.
 *
 *   Bank mapping:
 *     $8000-$BFFF: switchable (banks 0-6)
 *     $C000-$FFFF: fixed bank 7
 */
class NesRomParser(val romData: ByteArray) {
    val header = InesHeader.parse(romData)
    val prgSize: Int get() = header.prgRomSize
    val chrSize: Int get() = header.chrRomSize
    val mapper: Int get() = header.mapper
    val hasTrainer: Boolean get() = header.hasTrainer

    private val prgOffset: Int = INES_HEADER_SIZE + if (hasTrainer) TRAINER_SIZE else 0
    private val chrOffset: Int = prgOffset + prgSize

    fun getPrgBank(bankNumber: Int): ByteArray {
        val offset = prgOffset + bankNumber * PRG_BANK_SIZE
        require(offset + PRG_BANK_SIZE <= romData.size) {
            "Bank $bankNumber out of range (ROM has ${header.prgBanks} banks)"
        }
        return romData.copyOfRange(offset, offset + PRG_BANK_SIZE)
    }

    /**
     * Read a byte at an absolute ROM file offset.
     */
    fun readByte(offset: Int): Int = romData[offset].toInt() and 0xFF

    /**
     * Read a little-endian 16-bit word at an absolute ROM file offset.
     */
    fun readWord(offset: Int): Int =
        (romData[offset].toInt() and 0xFF) or
        ((romData[offset + 1].toInt() and 0xFF) shl 8)

    /**
     * Convert a CPU address ($8000-$FFFF) within a given bank to a ROM file offset.
     * Bank 7 is fixed at $C000-$FFFF; banks 0-6 are at $8000-$BFFF.
     */
    fun bankAddressToRomOffset(bankNumber: Int, cpuAddress: Int): Int {
        val bankOffset = prgOffset + bankNumber * PRG_BANK_SIZE
        return if (cpuAddress >= 0xC000) {
            // Fixed bank 7
            prgOffset + 7 * PRG_BANK_SIZE + (cpuAddress - 0xC000)
        } else {
            bankOffset + (cpuAddress - 0x8000)
        }
    }

    /**
     * Read a byte at a CPU address within a specific bank.
     */
    fun readBankByte(bankNumber: Int, cpuAddress: Int): Int =
        readByte(bankAddressToRomOffset(bankNumber, cpuAddress))

    /**
     * Read a 16-bit word at a CPU address within a specific bank.
     */
    fun readBankWord(bankNumber: Int, cpuAddress: Int): Int =
        readWord(bankAddressToRomOffset(bankNumber, cpuAddress))

    /**
     * Read a range of bytes at a CPU address within a specific bank.
     */
    fun readBankBytes(bankNumber: Int, cpuAddress: Int, length: Int): ByteArray {
        val offset = bankAddressToRomOffset(bankNumber, cpuAddress)
        return romData.copyOfRange(offset, offset + length)
    }

    /**
     * Validate that this looks like a Metroid NES ROM.
     */
    /**
     * Write a byte at an absolute ROM file offset.
     */
    fun writeByte(offset: Int, value: Int) {
        romData[offset] = (value and 0xFF).toByte()
    }

    /**
     * Write a byte at a CPU address within a specific bank.
     */
    fun writeBankByte(bankNumber: Int, cpuAddress: Int, value: Int) {
        writeByte(bankAddressToRomOffset(bankNumber, cpuAddress), value)
    }

    /**
     * Create a mutable copy of the ROM data for export.
     */
    fun copyRomData(): ByteArray = romData.copyOf()

    fun isMetroidRom(): Boolean {
        if (!header.isValid) return false
        if (header.prgBanks != 8 && header.prgBanks != 16) return false
        if (header.chrBanks != 0) return false
        // Mapper 1 (MMC1) for original, mapper 4 (MMC3) for TxROM hack
        if (header.mapper != 1 && header.mapper != 4) return false
        return true
    }

    companion object {
        const val INES_HEADER_SIZE = 16
        const val TRAINER_SIZE = 512
        const val PRG_BANK_SIZE = 0x4000  // 16KB
        private const val METROID_RAW_SIZE = 8 * PRG_BANK_SIZE  // 131072 bytes (128KB)

        fun fromFile(data: ByteArray): NesRomParser = NesRomParser(ensureHeader(data))

        /**
         * If the data is a raw (headerless) Metroid ROM, prepend an iNES header.
         * Detects headerless ROMs by checking size (exactly 128KB) and absence of "NES\x1A" magic.
         */
        fun ensureHeader(data: ByteArray): ByteArray {
            if (data.size >= INES_HEADER_SIZE) {
                val magic = String(data, 0, 4, Charsets.ISO_8859_1)
                if (magic == "NES\u001A") return data
            }
            if (data.size == METROID_RAW_SIZE) {
                val header = byteArrayOf(
                    0x4E, 0x45, 0x53, 0x1A, // "NES\x1A"
                    0x08,                     // 8 × 16KB PRG ROM
                    0x00,                     // 0 CHR ROM (CHR RAM)
                    0x12,                     // flags6: mapper 1 low nibble (0001), battery bit
                    0x00,                     // flags7
                    0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00
                )
                return header + data
            }
            return data
        }
    }
}

data class InesHeader(
    val magic: String,
    val prgBanks: Int,
    val chrBanks: Int,
    val flags6: Int,
    val flags7: Int,
    val prgRamSize: Int,
    val flags9: Int,
    val flags10: Int
) {
    val isValid: Boolean get() = magic == "NES\u001A"
    val mapper: Int get() = ((flags6 shr 4) and 0x0F) or (flags7 and 0xF0)
    val mirroring: Int get() = flags6 and 0x01
    val hasBattery: Boolean get() = (flags6 and 0x02) != 0
    val hasTrainer: Boolean get() = (flags6 and 0x04) != 0
    val fourScreenVRAM: Boolean get() = (flags6 and 0x08) != 0
    val prgRomSize: Int get() = prgBanks * 0x4000
    val chrRomSize: Int get() = chrBanks * 0x2000

    companion object {
        fun parse(data: ByteArray): InesHeader {
            require(data.size >= 16) { "ROM too small for iNES header" }
            val magic = String(data, 0, 4, Charsets.ISO_8859_1)
            return InesHeader(
                magic = magic,
                prgBanks = data[4].toInt() and 0xFF,
                chrBanks = data[5].toInt() and 0xFF,
                flags6 = data[6].toInt() and 0xFF,
                flags7 = data[7].toInt() and 0xFF,
                prgRamSize = data[8].toInt() and 0xFF,
                flags9 = data[9].toInt() and 0xFF,
                flags10 = data[10].toInt() and 0xFF
            )
        }
    }
}
