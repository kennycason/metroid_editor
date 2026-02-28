package com.metroid.editor.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TilePalettePanel(
    editorState: EditorState,
    modifier: Modifier = Modifier
) {
    val pixels = editorState.tilePaletteImage
    val width = editorState.tilePaletteWidth
    val height = editorState.tilePaletteHeight
    val selectedMacro = editorState.selectedMacroIndex

    var scale by remember { mutableStateOf(2f) }
    val scrollState = rememberScrollState()
    var imageBitmap by remember { mutableStateOf<ImageBitmap?>(null) }

    LaunchedEffect(pixels, width, height) {
        if (pixels != null && width > 0 && height > 0) {
            imageBitmap = pixelsToImageBitmap(pixels, width, height)
        } else {
            imageBitmap = null
        }
    }

    Surface(
        color = Color(0xFF14142A),
        modifier = modifier
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Header row: palette selector + zoom
            Surface(
                color = Color(0xFF1E1E3A),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    // Sub-palette selector
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Pal", fontSize = 10.sp, color = Color(0xFF8080A0))
                        Spacer(Modifier.width(4.dp))
                        for (palIdx in 0..3) {
                            val isSelected = editorState.selectedSubPalette == palIdx
                            Surface(
                                color = if (isSelected) Color(0xFF8E6FFF) else Color(0xFF2A2A50),
                                shape = MaterialTheme.shapes.extraSmall,
                                modifier = Modifier
                                    .size(22.dp)
                                    .padding(1.dp)
                                    .clickable {
                                        editorState.selectedSubPalette = palIdx
                                        editorState.rebuildTilePalette()
                                    }
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text(
                                        "$palIdx",
                                        fontSize = 10.sp,
                                        color = if (isSelected) Color.White else Color(0xFF8080A0)
                                    )
                                }
                            }
                        }
                    }

                    // Zoom controls
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(
                            onClick = { scale = (scale - 0.5f).coerceAtLeast(1f) },
                            contentPadding = PaddingValues(0.dp),
                            modifier = Modifier.size(20.dp)
                        ) {
                            Text("-", fontSize = 12.sp, color = Color(0xFF8080A0))
                        }
                        Text(
                            "${scale.toInt()}x",
                            fontSize = 9.sp,
                            color = Color(0xFF606080)
                        )
                        TextButton(
                            onClick = { scale = (scale + 0.5f).coerceAtMost(6f) },
                            contentPadding = PaddingValues(0.dp),
                            modifier = Modifier.size(20.dp)
                        ) {
                            Text("+", fontSize = 12.sp, color = Color(0xFF8080A0))
                        }
                    }
                }
            }

            // Tile grid
            val image = imageBitmap
            if (image != null) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(scrollState)
                ) {
                    val scaledW = (width * scale).toInt()
                    val scaledH = (height * scale).toInt()

                    Canvas(
                        modifier = Modifier
                            .size(scaledW.dp, scaledH.dp)
                            .pointerInput(scale, width, height) {
                                detectTapGestures { tapOffset ->
                                    val tileSize = 8
                                    val macroSize = 16
                                    val tileGridH = 16 * tileSize
                                    val separatorH = 2
                                    val macroStartY = tileGridH + separatorH
                                    val macrosPerRow = 8

                                    val px = (tapOffset.x / (scale * density)).toInt()
                                    val py = (tapOffset.y / (scale * density)).toInt()

                                    if (py >= macroStartY) {
                                        val macroCol = px / macroSize
                                        val macroRow = (py - macroStartY) / macroSize
                                        val macroIdx = macroRow * macrosPerRow + macroCol
                                        editorState.selectedMacroIndex = macroIdx
                                    } else {
                                        editorState.selectedMacroIndex = -1
                                    }
                                }
                            }
                    ) {
                        drawImage(
                            image = image,
                            dstOffset = androidx.compose.ui.unit.IntOffset.Zero,
                            dstSize = IntSize(
                                (width * scale * density).toInt(),
                                (height * scale * density).toInt()
                            ),
                            filterQuality = FilterQuality.None
                        )

                        if (selectedMacro >= 0) {
                            val macroSize = 16
                            val macrosPerRow = 8
                            val tileGridH = 16 * 8
                            val separatorH = 2
                            val macroStartY = tileGridH + separatorH

                            val col = selectedMacro % macrosPerRow
                            val row = selectedMacro / macrosPerRow
                            val sx = col * macroSize * scale * density
                            val sy = (macroStartY + row * macroSize) * scale * density
                            val sw = macroSize * scale * density
                            val sh = macroSize * scale * density

                            drawRect(
                                color = Color(0x44FFFFFF),
                                topLeft = Offset(sx, sy),
                                size = Size(sw, sh)
                            )
                            drawRect(
                                color = Color(0xFFFFFFFF),
                                topLeft = Offset(sx, sy),
                                size = Size(sw, sh),
                                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2f)
                            )
                        }
                    }
                }
            } else {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "Load a ROM to see tiles",
                        fontSize = 11.sp,
                        color = Color(0xFF404060)
                    )
                }
            }
        }
    }
}
