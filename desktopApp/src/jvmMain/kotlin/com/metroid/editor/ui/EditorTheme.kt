package com.metroid.editor.ui

import androidx.compose.ui.graphics.Color

/**
 * Centralized theme colors for the Metroid NES Editor.
 * All UI components should reference these instead of hardcoded Color() values.
 */
object EditorTheme {
    // -- Base surfaces --
    val background = Color(0xFF121212)
    val surface = Color(0xFF1E1E1E)
    val surfaceVariant = Color(0xFF2A2A2A)
    val surfaceElevated = Color(0xFF252525)
    val surfaceDim = Color(0xFF0E0E0E)

    // -- Panel backgrounds --
    val panelBg = Color(0xFF1A1A1A)
    val panelHeader = Color(0xFF222222)
    val panelSection = Color(0xFF1E1E1E)

    // -- Text --
    val textPrimary = Color(0xFFE0E0E0)
    val textSecondary = Color(0xFFA0A0A0)
    val textMuted = Color(0xFF666666)
    val textDim = Color(0xFF4A4A4A)

    // -- Accent --
    val accent = Color(0xFF8E6FFF)
    val accentDim = Color(0xFF6B50CC)
    val accentMuted = Color(0xFF3A2A66)

    // -- Action colors --
    val actionPrimary = Color(0xFFA0A0A0)       // menu text
    val actionDisabled = Color(0xFF505050)
    val exportGreen = Color(0xFF80CC80)
    val errorRed = Color(0xFFFF6666)
    val warningOrange = Color(0xFFFFAA44)
    val successGreen = Color(0xFF44CC44)
    val sampleGreen = Color(0xFF66FF66)

    // -- Selection --
    val selected = Color(0xFF333333)
    val selectedAccent = accent
    val hover = Color(0xFF2A2A2A)

    // -- Dividers & borders --
    val divider = Color(0xFF333333)
    val border = Color(0xFF3A3A3A)

    // -- Map viewer --
    val mapBackground = Color(0xFF0A0A0A)
    val gridLine = Color(0x18FFFFFF)
    val coverageOverlay = Color(0x20FF0000)
    val doorColor = Color(0xFF00AAFF)

    // -- Status bar --
    val statusBar = Color(0xFF161616)
    val toggleActive = Color(0xFF2A2A2A)

    // -- Tool palette --
    val paletteBg = Color(0xFF181818)
    val paletteHeader = Color(0xFF222222)
    val paletteButtonActive = accent
    val paletteButtonInactive = Color(0xFF2A2A2A)

    // -- Tab bar --
    val tabBar = Color(0xFF1A1A1A)
    val tabSelected = accent
    val tabUnselected = Color(0xFFA0A0A0)
}
