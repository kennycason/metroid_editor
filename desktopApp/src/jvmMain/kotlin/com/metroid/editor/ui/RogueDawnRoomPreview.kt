package com.metroid.editor.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.metroid.editor.data.Room
import com.metroid.editor.rom.MapRenderer

@Composable
fun RogueDawnRoomPreview(
    room: Room,
    editorState: EditorState,
    modifier: Modifier = Modifier
) {
    val T = EditorTheme
    val density = androidx.compose.ui.platform.LocalDensity.current.density
    val romVersion = editorState.romLoadVersion
    val renderer = editorState.rogueDawnRenderer

    var scale by remember(romVersion, room) { mutableStateOf(-1f) }
    var offset by remember(romVersion, room) { mutableStateOf(Offset.Zero) }
    var renderedImage by remember(romVersion, room) { mutableStateOf<ImageBitmap?>(null) }
    var renderError by remember(romVersion, room) { mutableStateOf<String?>(null) }

    LaunchedEffect(romVersion, renderer, room) {
        try {
            if (renderer != null) {
                val result = renderer.renderRoom(room)
                renderedImage = pixelsToImageBitmap(result.pixels, result.width, result.height)
                renderError = null
            } else {
                renderedImage = null
                renderError = "Rogue Dawn renderer unavailable"
            }
        } catch (e: Exception) {
            renderedImage = null
            renderError = "Render error: ${e.message}"
        }
    }

    Box(modifier = modifier) {
        val image = renderedImage
        if (image != null) {
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            val currentScale = if (scale < 0f) 2f else scale
                            scale = (currentScale * zoom).coerceIn(0.5f, 8f)
                            offset += pan
                        }
                    }
            ) {
                if (scale < 0f && size.width > 0 && size.height > 0) {
                    val logicalW = size.width / density
                    val logicalH = size.height / density
                    val fitW = logicalW / MapRenderer.ROOM_WIDTH_PX * 0.9f
                    val fitH = logicalH / MapRenderer.ROOM_HEIGHT_PX * 0.9f
                    scale = minOf(fitW, fitH).coerceIn(0.5f, 8f)
                    offset = Offset.Zero
                }

                val pxScale = scale * density
                val scaledWidth = MapRenderer.ROOM_WIDTH_PX * pxScale
                val scaledHeight = MapRenderer.ROOM_HEIGHT_PX * pxScale
                val left = size.width / 2 + offset.x - scaledWidth / 2
                val top = size.height / 2 + offset.y - scaledHeight / 2
                val macro = MapRenderer.MACRO_SIZE * pxScale

                drawImage(
                    image = image,
                    dstOffset = IntOffset(left.toInt(), top.toInt()),
                    dstSize = IntSize(scaledWidth.toInt(), scaledHeight.toInt()),
                    filterQuality = FilterQuality.None
                )

                if (editorState.showGrid) {
                    for (mx in 0..MapRenderer.ROOM_WIDTH_MACROS) {
                        val x = left + mx * macro
                        drawLine(T.gridLine, Offset(x, top), Offset(x, top + scaledHeight))
                    }
                    for (my in 0..MapRenderer.ROOM_HEIGHT_MACROS) {
                        val y = top + my * macro
                        drawLine(T.gridLine, Offset(left, y), Offset(left + scaledWidth, y))
                    }
                }

                if (editorState.showEnemies) {
                    for (enemy in room.enemies) {
                        val x = left + enemy.posX * macro + macro * 0.18f
                        val y = top + enemy.posY * macro + macro * 0.18f
                        val sizePx = macro * 0.64f
                        drawRect(T.errorRed.copy(alpha = 0.30f), Offset(x, y), Size(sizePx, sizePx))
                        drawRect(T.errorRed, Offset(x, y), Size(sizePx, sizePx), style = Stroke(width = 1.5f))
                    }
                }

                if (editorState.showDoors) {
                    for (door in room.doors) {
                        val doorW = macro * 0.35f
                        val doorH = macro * 3f
                        val x = if (door.side == 0) left + scaledWidth - doorW else left
                        val y = top + 5 * macro
                        drawRect(T.doorColor.copy(alpha = 0.30f), Offset(x, y), Size(doorW, doorH))
                        drawRect(T.doorColor, Offset(x, y), Size(doorW, doorH), style = Stroke(width = 1.5f))
                    }
                }

                drawRect(T.border, Offset(left, top), Size(scaledWidth, scaledHeight), style = Stroke(width = 1f))
            }
        } else if (renderError != null) {
            Text(
                renderError!!,
                color = T.errorRed,
                modifier = Modifier.align(Alignment.Center).padding(16.dp)
            )
        }

        Surface(
            color = T.surfaceVariant.copy(alpha = 0.86f),
            shape = MaterialTheme.shapes.small,
            modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp)
        ) {
            Text(
                "Room ${'$'}${"%02X".format(room.roomNumber)}  tiles  ${room.enemies.size} enemies  ${room.doors.size} doors",
                color = T.textSecondary,
                fontSize = 11.sp,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp)
            )
        }
    }
}
