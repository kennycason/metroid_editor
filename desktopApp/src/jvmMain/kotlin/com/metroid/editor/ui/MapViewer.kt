package com.metroid.editor.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.metroid.editor.data.MetroidNames
import com.metroid.editor.data.Room
import com.metroid.editor.rom.MapRenderer
import java.awt.image.BufferedImage

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun MapViewer(
    room: Room,
    renderer: MapRenderer,
    editorState: EditorState,
    modifier: Modifier = Modifier
) {
    var scale by remember { mutableStateOf(2f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var renderedImage by remember { mutableStateOf<ImageBitmap?>(null) }
    var renderError by remember { mutableStateOf<String?>(null) }
    var hoverMacro by remember { mutableStateOf<Pair<Int, Int>?>(null) }

    val editVer = editorState.editVersion
    val grid = editorState.workingGrid

    LaunchedEffect(room, editVer) {
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

    fun screenToMacro(screenPos: Offset, canvasSize: androidx.compose.ui.geometry.Size): Pair<Int, Int>? {
        val imgW = MapRenderer.ROOM_WIDTH_PX * scale
        val imgH = MapRenderer.ROOM_HEIGHT_PX * scale
        val imgX = canvasSize.width / 2 + offset.x - imgW / 2
        val imgY = canvasSize.height / 2 + offset.y - imgH / 2

        val px = ((screenPos.x - imgX) / scale).toInt()
        val py = ((screenPos.y - imgY) / scale).toInt()

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
                val centerX = size.width / 2 + offset.x
                val centerY = size.height / 2 + offset.y
                val scaledWidth = image.width * scale
                val scaledHeight = image.height * scale
                val imgLeft = centerX - scaledWidth / 2
                val imgTop = centerY - scaledHeight / 2

                drawImage(
                    image = image,
                    dstOffset = androidx.compose.ui.unit.IntOffset(imgLeft.toInt(), imgTop.toInt()),
                    dstSize = IntSize(scaledWidth.toInt(), scaledHeight.toInt()),
                    filterQuality = FilterQuality.None
                )

                val macroScaled = MapRenderer.MACRO_SIZE * scale

                // Coverage overlay — dim background (non-exportable) positions
                if (editorState.showCoverage) {
                    val coverage = editorState.coverageMap
                    if (coverage != null) {
                        for (my in 0 until MapRenderer.ROOM_HEIGHT_MACROS) {
                            for (mx in 0 until MapRenderer.ROOM_WIDTH_MACROS) {
                                if (!coverage[my * MapRenderer.ROOM_WIDTH_MACROS + mx]) {
                                    val cx = imgLeft + mx * macroScaled
                                    val cy = imgTop + my * macroScaled
                                    drawRect(
                                        color = Color(0x20FF0000),
                                        topLeft = Offset(cx, cy),
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
                        drawLine(Color(0x18FFFFFF), Offset(x, imgTop), Offset(x, imgTop + scaledHeight))
                    }
                    for (my in 0..MapRenderer.ROOM_HEIGHT_MACROS) {
                        val y = imgTop + my * macroScaled
                        drawLine(Color(0x18FFFFFF), Offset(imgLeft, y), Offset(imgLeft + scaledWidth, y))
                    }
                }

                // Enemy overlays
                if (editorState.showEnemies && room.enemies.isNotEmpty()) {
                    for (enemy in room.enemies) {
                        val ex = imgLeft + enemy.posX * macroScaled
                        val ey = imgTop + enemy.posY * macroScaled
                        val color = Color(MetroidNames.enemyColor(enemy.type))
                        val isItem = MetroidNames.isItem(enemy.type)
                        val markerSize = macroScaled * 0.7f
                        val markerOff = (macroScaled - markerSize) / 2

                        if (isItem) {
                            // Items: filled diamond shape
                            val cx = ex + macroScaled / 2
                            val cy = ey + macroScaled / 2
                            val r = markerSize / 2
                            val path = androidx.compose.ui.graphics.Path().apply {
                                moveTo(cx, cy - r)
                                lineTo(cx + r, cy)
                                lineTo(cx, cy + r)
                                lineTo(cx - r, cy)
                                close()
                            }
                            drawPath(path, color.copy(alpha = 0.35f))
                            drawPath(path, color.copy(alpha = 0.9f), style = Stroke(width = 1.5f))
                        } else {
                            // Enemies: filled rectangle with border
                            drawRect(
                                color = color.copy(alpha = 0.25f),
                                topLeft = Offset(ex + markerOff, ey + markerOff),
                                size = Size(markerSize, markerSize)
                            )
                            drawRect(
                                color = color.copy(alpha = 0.8f),
                                topLeft = Offset(ex + markerOff, ey + markerOff),
                                size = Size(markerSize, markerSize),
                                style = Stroke(width = 1.5f)
                            )
                        }
                    }
                }

                // Door overlays
                if (editorState.showDoors && room.doors.isNotEmpty()) {
                    for (door in room.doors) {
                        val doorColor = Color(0xFF00AAFF)
                        val doorW = macroScaled * 0.3f
                        val doorH = macroScaled * 3

                        if (door.side == 0) {
                            val dx = imgLeft + scaledWidth - doorW
                            val dy = imgTop + scaledHeight / 2 - doorH / 2
                            drawRect(doorColor.copy(alpha = 0.25f), Offset(dx, dy), Size(doorW, doorH))
                            drawRect(doorColor.copy(alpha = 0.8f), Offset(dx, dy), Size(doorW, doorH), style = Stroke(1.5f))
                        } else {
                            val dx = imgLeft
                            val dy = imgTop + scaledHeight / 2 - doorH / 2
                            drawRect(doorColor.copy(alpha = 0.25f), Offset(dx, dy), Size(doorW, doorH))
                            drawRect(doorColor.copy(alpha = 0.8f), Offset(dx, dy), Size(doorW, doorH), style = Stroke(1.5f))
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
                        EditorTool.ERASE -> Color(0xAAFF6666)
                        EditorTool.SAMPLE -> Color(0xAA66FF66)
                    }

                    drawRect(
                        color = toolColor.copy(alpha = 0.2f),
                        topLeft = Offset(hx, hy),
                        size = Size(macroScaled, macroScaled)
                    )
                    drawRect(
                        color = toolColor,
                        topLeft = Offset(hx, hy),
                        size = Size(macroScaled, macroScaled),
                        style = Stroke(width = 1.5f)
                    )
                }
            }
        }

        if (renderError != null) {
            Text(
                renderError!!,
                color = Color(0xFFFF6666),
                modifier = Modifier.align(Alignment.Center).padding(16.dp)
            )
        }

        // Info overlay
        Row(
            modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            val hv = hoverMacro
            if (hv != null) {
                val grid2 = editorState.workingGrid
                val macroAtHover = grid2?.get(hv.first, hv.second) ?: -1
                Text(
                    "(${hv.first}, ${hv.second})" + if (macroAtHover >= 0) " M#${"%02X".format(macroAtHover)}" else "",
                    color = Color(0xFF808090),
                    style = MaterialTheme.typography.bodySmall
                )
            }
            if (editorState.canUndo) {
                Text(
                    "${editorState.undoStack.size} edits",
                    color = Color(0xFF8E6FFF).copy(alpha = 0.6f),
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Text(
                "${(scale * 100).toInt()}%",
                color = Color(0xFF606080),
                style = MaterialTheme.typography.bodySmall
            )
        }

        // Tool indicator top-left
        Text(
            editorState.activeTool.label.uppercase(),
            modifier = Modifier.align(Alignment.TopStart).padding(8.dp),
            color = when (editorState.activeTool) {
                EditorTool.PAINT -> Color(0xFF8E6FFF)
                EditorTool.ERASE -> Color(0xFFFF6666)
                EditorTool.SAMPLE -> Color(0xFF66FF66)
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
