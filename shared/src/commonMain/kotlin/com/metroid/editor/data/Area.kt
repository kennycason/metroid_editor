package com.metroid.editor.data

/**
 * Metroid NES areas.
 *
 * Each area corresponds to a PRG bank (1–5) that is swapped into $8000–$BFFF.
 * The area code stored in RAM at $74 encodes the area ID in its lower nibble.
 *
 * Bank layout in the ROM file (after the 16-byte iNES header):
 *   Bank 0: $0010 — Title / Ending
 *   Bank 1: $4010 — Brinstar
 *   Bank 2: $8010 — Norfair
 *   Bank 3: $C010 — Tourian
 *   Bank 4: $10010 — Kraid's Hideout
 *   Bank 5: $14010 — Ridley's Hideout
 *   Bank 6: $18010 — Graphics (sprites, CHR patterns)
 *   Bank 7: $1C010 — Game Engine (fixed at $C000–$FFFF)
 */
enum class Area(val id: Int, val bankNumber: Int, val areaCode: Int, val displayName: String) {
    BRINSTAR(0, 1, 0x00, "Brinstar"),
    NORFAIR(1, 2, 0x01, "Norfair"),
    KRAID(2, 4, 0x02, "Kraid's Hideout"),
    TOURIAN(3, 3, 0x03, "Tourian"),
    RIDLEY(4, 5, 0x04, "Ridley's Hideout");

    companion object {
        fun fromAreaCode(code: Int): Area? = entries.find { it.areaCode == (code and 0x0F) }
        fun fromBankNumber(bank: Int): Area? = entries.find { it.bankNumber == bank }
    }
}
