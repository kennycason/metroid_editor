package com.metroid.editor.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.metroid.editor.data.MetroidNames
import com.metroid.editor.data.Room
import com.metroid.editor.rom.MapRenderer
import java.awt.image.BufferedImage
import org.jetbrains.skia.Font as SkiaFont
import org.jetbrains.skia.Paint as SkiaPaint
import org.jetbrains.skia.Typeface as SkiaTypeface

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun MapViewer(
    room: Room,
    renderer: MapRenderer,
    editorState: EditorState,
    modifier: Modifier = Modifier
) {
    val T = EditorTheme
    var scale by remember { mutableStateOf(-1f) }  // -1 = auto-fit on first layout
    var offset by remember { mutableStateOf(Offset.Zero) }
    var renderedImage by remember { mutableStateOf<ImageBitmap?>(null) }
    var renderError by remember { mutableStateOf<String?>(null) }
    var hoverMacro by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    val density = androidx.compose.ui.platform.LocalDensity.current.density

    val editVer = editorState.editVersion
    val grid = editorState.workingGrid

    // Reset to auto-fit when room changes
    LaunchedEffect(room) {
        scale = -1f
        offset = Offset.Zero
    }

    LaunchedEffect(renderer, room, editVer) {
        try {
            val result = if (grid != null) {
                renderer.renderFromGrid(room, grid)
            } else {
                renderer.renderRoom(room)
            }
            renderedImage = pixelsToImageBitmap(result.pixels, result.width, result.height)
            renderError = null
        } catch (e: Exception) {
            renderError = "Render error: ${e.message}"
            renderedImage = null
        }
    }

    // All coordinate math uses physical pixels (canvas space).
    // `scale` is logical (density-independent). `pxScale` = physical scale.
    fun screenToMacro(screenPos: Offset, canvasSize: androidx.compose.ui.geometry.Size): Pair<Int, Int>? {
        val pxScale = scale * density
        val imgW = MapRenderer.ROOM_WIDTH_PX * pxScale
        val imgH = MapRenderer.ROOM_HEIGHT_PX * pxScale
        val imgX = canvasSize.width / 2 + offset.x - imgW / 2
        val imgY = canvasSize.height / 2 + offset.y - imgH / 2

        val px = ((screenPos.x - imgX) / pxScale).toInt()
        val py = ((screenPos.y - imgY) / pxScale).toInt()

        val macroX = px / MapRenderer.MACRO_SIZE
        val macroY = py / MapRenderer.MACRO_SIZE

        return if (macroX in 0 until MapRenderer.ROOM_WIDTH_MACROS &&
            macroY in 0 until MapRenderer.ROOM_HEIGHT_MACROS
        ) macroX to macroY else null
    }

    Box(modifier = modifier) {
        val image = renderedImage
        if (image != null) {
            var canvasSize by remember { mutableStateOf(androidx.compose.ui.geometry.Size.Zero) }

            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            if (!editorState.isPainting) {
                                scale = (scale * zoom).coerceIn(0.5f, 8f)
                                offset += pan
                            }
                        }
                    }
                    .pointerInput(editorState.selectedMacroIndex, editorState.activeTool, scale, offset) {
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent()

                                when (event.type) {
                                    PointerEventType.Press -> {
                                        val pos = event.changes.firstOrNull()?.position ?: continue
                                        val macro = screenToMacro(pos, canvasSize) ?: continue

                                        val isRightClick = event.buttons.isSecondaryPressed
                                        if (isRightClick || editorState.activeTool == EditorTool.SAMPLE) {
                                            editorState.sampleAt(macro.first, macro.second)
                                        } else {
                                            editorState.beginStroke()
                                            editorState.paintAt(macro.first, macro.second)
                                        }
                                        event.changes.forEach { it.consume() }
                                    }
                                    PointerEventType.Move -> {
                                        val pos = event.changes.firstOrNull()?.position ?: continue
                                        hoverMacro = screenToMacro(pos, canvasSize)

                                        if (editorState.isPainting) {
                                            val macro = hoverMacro
                                            if (macro != null) {
                                                editorState.paintAt(macro.first, macro.second)
                                            }
                                            event.changes.forEach { it.consume() }
                                        }
                                    }
                                    PointerEventType.Release -> {
                                        if (editorState.isPainting) {
                                            editorState.endStroke()
                                            event.changes.forEach { it.consume() }
                                        }
                                    }
                                    PointerEventType.Exit -> {
                                        hoverMacro = null
                                    }
                                }
                            }
                        }
                    }
            ) {
                canvasSize = size

                // Auto-fit: compute LOGICAL scale (density-independent)
                if (scale < 0f && size.width > 0 && size.height > 0) {
                    val logicalW = size.width / density
                    val logicalH = size.height / density
                    val fitW = logicalW / MapRenderer.ROOM_WIDTH_PX * 0.9f
                    val fitH = logicalH / MapRenderer.ROOM_HEIGHT_PX * 0.9f
                    scale = minOf(fitW, fitH).coerceIn(0.5f, 8f)
                    offset = Offset.Zero
                }

                // pxScale = physical pixel scale for all drawing
                val pxScale = scale * density
                val centerX = size.width / 2 + offset.x
                val centerY = size.height / 2 + offset.y
                val scaledWidth = image.width * pxScale
                val scaledHeight = image.height * pxScale
                val imgLeft = centerX - scaledWidth / 2
                val imgTop = centerY - scaledHeight / 2

                drawImage(
                    image = image,
                    dstOffset = androidx.compose.ui.unit.IntOffset(imgLeft.toInt(), imgTop.toInt()),
                    dstSize = IntSize(scaledWidth.toInt(), scaledHeight.toInt()),
                    filterQuality = FilterQuality.None
                )

                val macroScaled = MapRenderer.MACRO_SIZE * pxScale

                // Coverage overlay
                if (editorState.showCoverage) {
                    val coverage = editorState.coverageMap
                    if (coverage != null) {
                        for (my in 0 until MapRenderer.ROOM_HEIGHT_MACROS) {
                            for (mx in 0 until MapRenderer.ROOM_WIDTH_MACROS) {
                                if (!coverage[my * MapRenderer.ROOM_WIDTH_MACROS + mx]) {
                                    drawRect(
                                        color = T.coverageOverlay,
                                        topLeft = Offset(imgLeft + mx * macroScaled, imgTop + my * macroScaled),
                                        size = Size(macroScaled, macroScaled)
                                    )
                                }
                            }
                        }
                    }
                }

                // Grid overlay
                if (editorState.showGrid) {
                    for (mx in 0..MapRenderer.ROOM_WIDTH_MACROS) {
                        val x = imgLeft + mx * macroScaled
                        drawLine(T.gridLine, Offset(x, imgTop), Offset(x, imgTop + scaledHeight))
                    }
                    for (my in 0..MapRenderer.ROOM_HEIGHT_MACROS) {
                        val y = imgTop + my * macroScaled
                        drawLine(T.gridLine, Offset(imgLeft, y), Offset(imgLeft + scaledWidth, y))
                    }
                }

                // Enemy overlays
                if (editorState.showEnemies && room.enemies.isNotEmpty()) {
                    val labelFont = SkiaFont(SkiaTypeface.makeDefault(), (9f * pxScale).coerceIn(8f, 24f))

                    for (enemy in room.enemies) {
                        val ex = imgLeft + enemy.posX * macroScaled
                        val ey = imgTop + enemy.posY * macroScaled
                        val color = Color(MetroidNames.enemyColor(enemy.type))
                        val isItem = MetroidNames.isItem(enemy.type)
                        val markerSize = macroScaled * 0.7f
                        val markerOff = (macroScaled - markerSize) / 2

                        if (isItem) {
                            val cx = ex + macroScaled / 2
                            val cy = ey + macroScaled / 2
                            val r = markerSize / 2
                            val path = Path().apply {
                                moveTo(cx, cy - r); lineTo(cx + r, cy)
                                lineTo(cx, cy + r); lineTo(cx - r, cy); close()
                            }
                            drawPath(path, color.copy(alpha = 0.35f))
                            drawPath(path, color.copy(alpha = 0.9f), style = Stroke(width = 1.5f))
                        } else {
                            drawRect(color.copy(alpha = 0.25f),
                                Offset(ex + markerOff, ey + markerOff), Size(markerSize, markerSize))
                            drawRect(color.copy(alpha = 0.8f),
                                Offset(ex + markerOff, ey + markerOff), Size(markerSize, markerSize),
                                style = Stroke(width = 1.5f))
                        }

                        // Name label
                        val name = MetroidNames.enemyName(enemy.type)
                        drawIntoCanvas { canvas ->
                            val argb = color.toArgb()
                            val bgPaint = SkiaPaint().apply {
                                this.color = org.jetbrains.skia.Color.makeARGB(180, 0, 0, 0)
                            }
                            val textPaint = SkiaPaint().apply {
                                this.color = argb
                                isAntiAlias = true
                            }
                            val textWidth = labelFont.measureTextWidth(name)
                            val textX = ex + macroScaled / 2 - textWidth / 2
                            val textY = ey - 3f * pxScale.coerceAtLeast(1f)
                            val pad = 2f
                            canvas.nativeCanvas.drawRect(
                                org.jetbrains.skia.Rect.makeXYWH(textX - pad, textY - labelFont.size + 1, textWidth + pad * 2, labelFont.size + 2),
                                bgPaint
                            )
                            canvas.nativeCanvas.drawString(name, textX, textY, labelFont, textPaint)
                        }
                    }
                }

                // Door overlays — NES Metroid doors span rows 6-8 (3 macros tall)
                if (editorState.showDoors && room.doors.isNotEmpty()) {
                    for (door in room.doors) {
                        val doorW = macroScaled * 0.4f
                        val doorH = macroScaled * 3
                        val doorY = 6  // doors are always at macro row 6
                        val dx = if (door.side == 0) imgLeft + scaledWidth - doorW else imgLeft
                        val dy = imgTop + doorY * macroScaled
                        drawRect(T.doorColor.copy(alpha = 0.25f), Offset(dx, dy), Size(doorW, doorH))
                        drawRect(T.doorColor.copy(alpha = 0.8f), Offset(dx, dy), Size(doorW, doorH), style = Stroke(1.5f))

                        // Door label
                        val sideLabel = if (door.side == 0) "R" else "L"
                        drawIntoCanvas { canvas ->
                            val paint = SkiaPaint().apply {
                                color = T.doorColor.toArgb()
                                isAntiAlias = true
                            }
                            val font = SkiaFont(SkiaTypeface.makeDefault(), (8f * pxScale).coerceIn(7f, 20f))
                            val labelX = dx + doorW / 2 - font.measureTextWidth(sideLabel) / 2
                            val labelY = dy + doorH + font.size + 2
                            canvas.nativeCanvas.drawString(sideLabel, labelX, labelY, font, paint)
                        }
                    }
                }

                // Hover highlight
                val hover = hoverMacro
                if (hover != null && (editorState.selectedMacroIndex >= 0 || editorState.activeTool == EditorTool.ERASE)) {
                    val hx = imgLeft + hover.first * macroScaled
                    val hy = imgTop + hover.second * macroScaled
                    val toolColor = when (editorState.activeTool) {
                        EditorTool.PAINT -> Color(0xAAFFFFFF)
                        EditorTool.ERASE -> T.errorRed
                        EditorTool.SAMPLE -> T.sampleGreen
                    }
                    drawRect(toolColor.copy(alpha = 0.2f), Offset(hx, hy), Size(macroScaled, macroScaled))
                    drawRect(toolColor, Offset(hx, hy), Size(macroScaled, macroScaled), style = Stroke(width = 1.5f))
                }
            }
        }

        if (renderError != null) {
            Text(renderError!!, color = T.errorRed,
                modifier = Modifier.align(Alignment.Center).padding(16.dp))
        }

        // Bottom overlay: hover info + zoom controls
        Row(
            modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val hv = hoverMacro
            if (hv != null) {
                val grid2 = editorState.workingGrid
                val macroAtHover = grid2?.get(hv.first, hv.second) ?: -1
                Text(
                    "(${hv.first}, ${hv.second})" + if (macroAtHover >= 0) " M#${"%02X".format(macroAtHover)}" else "",
                    color = T.textMuted, style = MaterialTheme.typography.bodySmall
                )
            }
            if (editorState.canUndo) {
                Text("${editorState.undoStack.size} edits",
                    color = T.accent.copy(alpha = 0.6f), style = MaterialTheme.typography.bodySmall)
            }

            Spacer(Modifier.width(4.dp))

            // Zoom controls
            Surface(
                color = T.surfaceVariant.copy(alpha = 0.8f),
                shape = MaterialTheme.shapes.small
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                ) {
                    Text(
                        "\u2212",
                        color = T.textSecondary,
                        fontSize = 14.sp,
                        modifier = Modifier
                            .clickable { scale = (scale / 1.25f).coerceIn(0.5f, 8f) }
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                    Text(
                        "${if (scale > 0) (scale * 100).toInt() else 100}%",
                        color = T.textSecondary, fontSize = 11.sp,
                        modifier = Modifier.padding(horizontal = 4.dp)
                    )
                    Text(
                        "+",
                        color = T.textSecondary,
                        fontSize = 14.sp,
                        modifier = Modifier
                            .clickable { scale = (scale * 1.25f).coerceIn(0.5f, 8f) }
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                    Text(
                        "Fit",
                        color = T.accent,
                        fontSize = 10.sp,
                        modifier = Modifier
                            .clickable { scale = -1f; offset = Offset.Zero }
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }
        }

        // Tool indicator
        Text(
            editorState.activeTool.label.uppercase(),
            modifier = Modifier.align(Alignment.TopStart).padding(8.dp),
            color = when (editorState.activeTool) {
                EditorTool.PAINT -> T.accent
                EditorTool.ERASE -> T.errorRed
                EditorTool.SAMPLE -> T.sampleGreen
            }.copy(alpha = 0.7f),
            style = MaterialTheme.typography.labelSmall
        )
    }
}

fun pixelsToImageBitmap(pixels: IntArray, width: Int, height: Int): ImageBitmap {
    val bufferedImage = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
    bufferedImage.setRGB(0, 0, width, height, pixels, 0, width)
    return bufferedImage.toComposeImageBitmap()
}
