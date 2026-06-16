package com.metroid.editor.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.metroid.editor.data.WorldMapCell
import com.metroid.editor.rom.MetroidRomData
import org.jetbrains.skia.Font as SkiaFont
import org.jetbrains.skia.Paint as SkiaPaint
import org.jetbrains.skia.Typeface as SkiaTypeface

private data class RogueDawnWorldPreview(
    val cells: List<WorldMapCell>,
    val bounds: MetroidRomData.MapBounds,
    val nonEmptyCount: Int
)

@Composable
fun RogueDawnWorldMapViewer(
    editorState: EditorState,
    modifier: Modifier = Modifier
) {
    val T = EditorTheme
    val density = androidx.compose.ui.platform.LocalDensity.current.density
    val romVersion = editorState.romLoadVersion
    val preview = remember(romVersion, editorState.rogueDawnData) {
        editorState.rogueDawnData?.let { data ->
            val cells = data.readWorldMap()
            val nonEmpty = cells.filter { !it.isEmpty }
            if (nonEmpty.isEmpty()) {
                null
            } else {
                RogueDawnWorldPreview(
                    cells = cells,
                    bounds = MetroidRomData.MapBounds(
                        minX = nonEmpty.minOf { it.x },
                        maxX = nonEmpty.maxOf { it.x },
                        minY = nonEmpty.minOf { it.y },
                        maxY = nonEmpty.maxOf { it.y }
                    ),
                    nonEmptyCount = nonEmpty.size
                )
            }
        }
    }

    var scale by remember(romVersion) { mutableStateOf(-1f) }
    var offset by remember(romVersion) { mutableStateOf(Offset.Zero) }

    LaunchedEffect(romVersion, preview) {
        val map = preview
        if (map != null) {
            editorState.statusMessage =
                "Rogue Dawn world map: ${map.bounds.width}x${map.bounds.height} cells, ${map.nonEmptyCount} rooms"
        }
    }

    Box(modifier = modifier) {
        val map = preview
        if (map != null) {
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            val currentScale = if (scale < 0f) 1f else scale
                            scale = (currentScale * zoom).coerceIn(0.4f, 6f)
                            offset += pan
                        }
                    }
            ) {
                val baseCell = 24f
                if (scale < 0f && size.width > 0 && size.height > 0) {
                    val logicalW = size.width / density
                    val logicalH = size.height / density
                    val fitW = logicalW / (map.bounds.width * baseCell) * 0.94f
                    val fitH = logicalH / (map.bounds.height * baseCell) * 0.94f
                    scale = minOf(fitW, fitH).coerceIn(0.4f, 6f)
                    offset = Offset.Zero
                }

                val pxScale = scale * density
                val cellSize = baseCell * pxScale
                val mapWidth = map.bounds.width * cellSize
                val mapHeight = map.bounds.height * cellSize
                val left = size.width / 2 + offset.x - mapWidth / 2
                val top = size.height / 2 + offset.y - mapHeight / 2

                drawRect(T.surfaceDim, Offset(left, top), Size(mapWidth, mapHeight))

                val textFont = SkiaFont(SkiaTypeface.makeDefault(), (8.5f * pxScale).coerceIn(7f, 20f))
                val textPaint = SkiaPaint().apply {
                    color = T.textPrimary.toArgbCompat()
                    isAntiAlias = true
                }

                for (cell in map.cells) {
                    val x = cell.x
                    val y = cell.y
                    if (x !in map.bounds.minX..map.bounds.maxX || y !in map.bounds.minY..map.bounds.maxY) continue

                    val px = left + (x - map.bounds.minX) * cellSize
                    val py = top + (y - map.bounds.minY) * cellSize
                    val rectSize = Size(cellSize, cellSize)

                    if (cell.isEmpty) {
                        drawRect(T.background, Offset(px, py), rectSize)
                        drawRect(T.gridLine.copy(alpha = 0.25f), Offset(px, py), rectSize, style = Stroke(width = 0.6f))
                        continue
                    }

                    val fill = Color(roomIdColor(cell.roomNumber))
                    drawRect(fill.copy(alpha = 0.72f), Offset(px, py), rectSize)
                    drawRect(fill.copy(alpha = 0.95f), Offset(px, py), rectSize, style = Stroke(width = 1.1f))

                    if (cellSize >= 15f) {
                        val label = "%02X".format(cell.roomNumber)
                        val textWidth = textFont.measureTextWidth(label)
                        drawIntoCanvas { canvas ->
                            canvas.nativeCanvas.drawString(
                                label,
                                px + cellSize / 2 - textWidth / 2,
                                py + cellSize / 2 + textFont.size * 0.35f,
                                textFont,
                                textPaint
                            )
                        }
                    }
                }

                drawRect(T.border, Offset(left, top), Size(mapWidth, mapHeight), style = Stroke(width = 1.2f))
            }

            RogueDawnWorldOverlay(
                map = map,
                scale = scale,
                onZoomIn = { scale = (scale.coerceAtLeast(0.4f) * 1.25f).coerceIn(0.4f, 6f) },
                onZoomOut = { scale = (scale.coerceAtLeast(0.4f) / 1.25f).coerceIn(0.4f, 6f) },
                onFit = { scale = -1f; offset = Offset.Zero },
                modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp)
            )
        } else {
            Text(
                "Rogue Dawn world map unavailable",
                color = T.textMuted,
                modifier = Modifier.align(Alignment.Center).padding(16.dp)
            )
        }
    }
}

@Composable
private fun RogueDawnWorldOverlay(
    map: RogueDawnWorldPreview,
    scale: Float,
    onZoomIn: () -> Unit,
    onZoomOut: () -> Unit,
    onFit: () -> Unit,
    modifier: Modifier = Modifier
) {
    val T = EditorTheme
    Surface(
        color = T.surfaceVariant.copy(alpha = 0.86f),
        shape = MaterialTheme.shapes.small,
        modifier = modifier
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp)
        ) {
            Text(
                "Rogue Dawn map ${map.bounds.width}x${map.bounds.height} (${map.nonEmptyCount} cells)",
                color = T.textSecondary,
                fontSize = 11.sp
            )
            Text("-", color = T.textSecondary, fontSize = 14.sp, modifier = Modifier.clickable(onClick = onZoomOut))
            Text(
                "${if (scale > 0) (scale * 100).toInt() else 100}%",
                color = T.textSecondary,
                fontSize = 11.sp
            )
            Text("+", color = T.textSecondary, fontSize = 14.sp, modifier = Modifier.clickable(onClick = onZoomIn))
            Text("Fit", color = T.accent, fontSize = 10.sp, modifier = Modifier.clickable(onClick = onFit))
        }
    }
}

private fun roomIdColor(roomNumber: Int): Int {
    val palette = intArrayOf(
        0xFF6D5BD0.toInt(),
        0xFF2F9E8F.toInt(),
        0xFFD8912F.toInt(),
        0xFFC44858.toInt(),
        0xFF4F8BC9.toInt(),
        0xFF8E63B6.toInt(),
        0xFF6FA84F.toInt(),
        0xFFC76D35.toInt()
    )
    return palette[roomNumber and 0x07]
}

private fun Color.toArgbCompat(): Int {
    val a = (alpha * 255).toInt().coerceIn(0, 255)
    val r = (red * 255).toInt().coerceIn(0, 255)
    val g = (green * 255).toInt().coerceIn(0, 255)
    val b = (blue * 255).toInt().coerceIn(0, 255)
    return (a shl 24) or (r shl 16) or (g shl 8) or b
}
