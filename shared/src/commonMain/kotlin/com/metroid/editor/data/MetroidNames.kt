package com.metroid.editor.data

/**
 * Known enemy type IDs and structure descriptions from the Metroid NES disassembly.
 */
object MetroidNames {
    val ENEMY_TYPES = mapOf(
        0x00 to "Skree",
        0x01 to "Rio",
        0x02 to "Squeept",
        0x03 to "Ripper",
        0x04 to "Ripper II",
        0x05 to "Waver",
        0x06 to "Geemer",
        0x07 to "Zeela",
        0x08 to "Zoomer",
        0x09 to "Mellow",
        0x0A to "Memu",
        0x0B to "Dessgeega",
        0x0C to "Side Hopper",
        0x0D to "Dragon",
        0x0E to "Geruta",
        0x0F to "Multiviola",
        0x10 to "Nova",
        0x11 to "Polyp",
        0x12 to "Gamet",
        0x13 to "Zebbo",
        0x14 to "Zeb",
        0x15 to "Metroid",
        0x16 to "Rinkas",
        0x17 to "Mini-Kraid",
        0x18 to "Kraid (boss)",
        0x19 to "Ridley (boss)",
        0x1A to "Mother Brain",
        0x1B to "Fake Block",
        0x1C to "Bomb Block",
        0x1D to "Missile Block",
        0x1E to "Energy Tank",
        0x1F to "Missile Pickup",
        0x20 to "Long Beam",
        0x21 to "Ice Beam",
        0x22 to "Wave Beam",
        0x23 to "Bomb",
        0x24 to "Varia Suit",
        0x25 to "High Jump",
        0x26 to "Screw Attack",
        0x27 to "Morph Ball"
    )

    fun enemyName(typeId: Int): String =
        ENEMY_TYPES[typeId] ?: "Unknown (%02X)".format(typeId)

    val ENEMY_COLORS = mapOf(
        0x15 to 0xFFFF4444.toInt(), // Metroid
        0x18 to 0xFFFF0000.toInt(), // Kraid
        0x19 to 0xFFFF0000.toInt(), // Ridley
        0x1A to 0xFFFF0000.toInt(), // Mother Brain
        0x1E to 0xFF00FF00.toInt(), // Energy Tank
        0x1F to 0xFFFFCC00.toInt(), // Missile Pickup
        0x20 to 0xFF00CCFF.toInt(), // Long Beam
        0x21 to 0xFF66CCFF.toInt(), // Ice Beam
        0x22 to 0xFF9966FF.toInt(), // Wave Beam
        0x23 to 0xFFFFAA00.toInt(), // Bomb
        0x24 to 0xFFFF66FF.toInt(), // Varia
        0x25 to 0xFF66FF66.toInt(), // HiJump
        0x26 to 0xFFFFFF00.toInt(), // Screw Attack
        0x27 to 0xFFFF9900.toInt()  // Morph Ball
    )

    fun enemyColor(typeId: Int): Int =
        ENEMY_COLORS[typeId] ?: 0xFFFF6666.toInt()

    fun isItem(typeId: Int): Boolean = typeId in 0x1B..0x27
}
