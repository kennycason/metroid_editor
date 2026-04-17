package com.metroid.editor.data

/**
 * Enemy type IDs from the Metroid NES disassembly (m1disasm).
 *
 * IMPORTANT: Enemy type IDs are PER-AREA. The same ID maps to different
 * enemies in different areas. The type byte in room data is an index into
 * the area's enemy data tables (EnemyInitDelayTbl, etc.)
 */
object MetroidNames {

    /** Brinstar enemy types (from prg1_brinstar.asm) */
    private val BRINSTAR_ENEMIES = mapOf(
        0x00 to "Sidehopper",
        0x01 to "Sidehopper", // ceiling
        0x02 to "Waver",
        0x03 to "Ripper",
        0x04 to "Skree",
        0x05 to "Zoomer",
        0x06 to "Rio",
        0x07 to "Zeb",
        0x08 to "Kraid",  // crashes in Brinstar
    )

    /** Norfair enemy types (from prg2_norfair.asm) */
    private val NORFAIR_ENEMIES = mapOf(
        0x00 to "Geruta",     // swooper idle
        0x01 to "Geruta",     // swooper attacking
        0x02 to "Ripper II",
        0x03 to "Mella",
        0x04 to "Mella",
        0x05 to "Mella",
        0x06 to "Zeela",      // crawler
        0x07 to "Gamet",
        0x08 to "Mella",
        0x09 to "Mella",
        0x0A to "Mella",
        0x0B to "Squeept",    // lava jumper
        0x0C to "Nova",       // bouncy orb
        0x0D to "Dragon",
        0x0E to "Polyp",      // rock launcher
    )

    /** Tourian enemy types (from prg3_tourian.asm) */
    private val TOURIAN_ENEMIES = mapOf(
        0x00 to "Metroid",
        0x01 to "Metroid",
        0x02 to "Zebetite",
        0x03 to "Cannon",
        0x04 to "Rinka",
    )

    /** Kraid's Hideout enemy types (from prg4_kraid.asm) */
    private val KRAID_ENEMIES = mapOf(
        0x00 to "Sidehopper",
        0x01 to "Sidehopper", // ceiling
        0x02 to "Memu",
        0x03 to "Ripper",
        0x04 to "Skree",
        0x05 to "Zeela",      // crawler
        0x06 to "Memu",
        0x07 to "Geega",
        0x08 to "Kraid",
        0x09 to "Kraid Lint",
        0x0A to "Kraid Nail",
    )

    /** Ridley's Hideout enemy types (from prg5_ridley.asm) */
    private val RIDLEY_ENEMIES = mapOf(
        0x00 to "Holtz",      // swooper idle
        0x01 to "Holtz",      // swooper attacking
        0x02 to "Dessgeega",
        0x03 to "Dessgeega",  // ceiling
        0x04 to "Viola",
        0x05 to "Viola",
        0x06 to "Zeela",      // crawler
        0x07 to "Zebbo",
        0x08 to "Viola",
        0x09 to "Ridley",
        0x0A to "Ridley Fire",
        0x0B to "Viola",
        0x0C to "Multiviola",
    )

    private val AREA_ENEMIES = mapOf(
        Area.BRINSTAR to BRINSTAR_ENEMIES,
        Area.NORFAIR to NORFAIR_ENEMIES,
        Area.TOURIAN to TOURIAN_ENEMIES,
        Area.KRAID to KRAID_ENEMIES,
        Area.RIDLEY to RIDLEY_ENEMIES,
    )

    /** Old global table — fallback only */
    private val GLOBAL_ENEMIES = mapOf(
        0x00 to "Enemy 00",
        0x01 to "Enemy 01",
    )

    fun enemyName(typeId: Int, area: Area? = null): String {
        // The type byte has upper bits for "tough" flag and boss flag
        val index = typeId and 0x3F
        if (area != null) {
            return AREA_ENEMIES[area]?.get(index) ?: "Enemy %02X".format(index)
        }
        return GLOBAL_ENEMIES[index] ?: "Enemy %02X".format(index)
    }

    // Colors by enemy category
    fun enemyColor(typeId: Int, area: Area? = null): Int {
        val index = typeId and 0x3F
        val name = enemyName(typeId, area).lowercase()
        return when {
            "metroid" in name -> 0xFFFF4444.toInt()
            "kraid" in name -> 0xFFFF0000.toInt()
            "ridley" in name -> 0xFFFF0000.toInt()
            "zebetite" in name -> 0xFFCC4444.toInt()
            "rinka" in name -> 0xFFFF8800.toInt()
            "cannon" in name -> 0xFFFF8800.toInt()
            "sidehopper" in name -> 0xFFFF6666.toInt()
            "dessgeega" in name -> 0xFFFF6666.toInt()
            "ripper" in name -> 0xFF66AAFF.toInt()
            "zoomer" in name || "zeela" in name -> 0xFF66FF66.toInt()
            "dragon" in name -> 0xFFFF4400.toInt()
            else -> 0xFFFF8866.toInt()
        }
    }

    fun isItem(typeId: Int): Boolean = false // items come from specItemsTable, not enemy entries

    // Item types from the special items table (handler type 2 = power-up)
    val ITEM_NAMES = mapOf(
        0x00 to "Maru Mari",      // Morph Ball
        0x01 to "Bomb",
        0x02 to "Ice Beam",
        0x03 to "Long Beam",
        0x04 to "Wave Beam",
        0x05 to "Screw Attack",
        0x06 to "Varia Suit",
        0x07 to "High Jump",
        0x08 to "Energy Tank",
        0x09 to "Missile Pickup",
    )
}
