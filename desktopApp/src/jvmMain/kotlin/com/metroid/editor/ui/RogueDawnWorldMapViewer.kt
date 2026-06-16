package com.metroid.editor.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import com.metroid.editor.rom.MapRenderer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private data class RogueDawnWorldPreview(
    val image: ImageBitmap,
    val width: Int,
    val height: Int,
    val boundsWidth: Int,
    val boundsHeight: Int,
    val placedRooms: Int,
    val skippedRooms: Int
)

@Composable
fun RogueDawnWorldMapViewer(
    editorState: EditorState,
    modifier: Modifier = Modifier
) {
    val T = EditorTheme
    val density = androidx.compose.ui.platform.LocalDensity.current.density
    val romVersion = editorState.romLoadVersion

    var scale by remember(romVersion) { mutableStateOf(-1f) }
    var offset by remember(romVersion) { mutableStateOf(Offset.Zero) }
    var preview by remember(romVersion) { mutableStateOf<RogueDawnWorldPreview?>(null) }
    var renderError by remember(romVersion) { mutableStateOf<String?>(null) }
    var isRendering by remember(romVersion) { mutableStateOf(false) }

    LaunchedEffect(romVersion) {
        scale = -1f
        offset = Offset.Zero
    }

    LaunchedEffect(romVersion, editorState.rogueDawnRenderer, editorState.viewMode) {
        if (editorState.viewMode != EditorViewMode.WORLD_MAP) return@LaunchedEffect

        val renderer = editorState.rogueDawnRenderer
        if (renderer == null) {
            preview = null
            renderError = "No Rogue Dawn ROM loaded"
            return@LaunchedEffect
        }

        isRendering = true
        renderError = null
        try {
            preview = withContext(Dispatchers.Default) {
                val result = renderer.renderFullWorldMap()
                    ?: return@withContext null
                RogueDawnWorldPreview(
                    image = pixelsToImageBitmap(result.pixels, result.width, result.height),
                    width = result.width,
                    height = result.height,
                    boundsWidth = result.bounds.width,
                    boundsHeight = result.bounds.height,
                    placedRooms = result.placedRooms,
                    skippedRooms = result.skippedRooms
                )
            }
            val map = preview
            if (map != null) {
                val skipped = if (map.skippedRooms > 0) ", ${map.skippedRooms} skipped" else ""
                editorState.statusMessage =
                    "Rogue Dawn map rendered: ${map.width}x${map.height}, ${map.placedRooms} cells$skipped"
            }
        } catch (e: Exception) {
            preview = null
            renderError = "Rogue Dawn map render error: ${e.message}"
        } finally {
            isRendering = false
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
                            val currentScale = if (scale < 0f) 0.1f else scale
                            scale = (currentScale * zoom).coerceIn(0.03f, 4f)
                            offset += pan
                        }
                    }
            ) {
                if (scale < 0f && size.width > 0 && size.height > 0) {
                    val logicalW = size.width / density
                    val logicalH = size.height / density
                    val fitW = logicalW / map.width * 0.96f
                    val fitH = logicalH / map.height * 0.96f
                    scale = minOf(fitW, fitH).coerceIn(0.03f, 4f)
                    offset = Offset.Zero
                }

                val pxScale = scale * density
                val centerX = size.width / 2 + offset.x
                val centerY = size.height / 2 + offset.y
                val scaledWidth = map.image.width * pxScale
                val scaledHeight = map.image.height * pxScale
                val imgLeft = centerX - scaledWidth / 2
                val imgTop = centerY - scaledHeight / 2

                drawImage(
                    image = map.image,
                    dstOffset = IntOffset(imgLeft.toInt(), imgTop.toInt()),
                    dstSize = IntSize(scaledWidth.toInt(), scaledHeight.toInt()),
                    filterQuality = FilterQuality.None
                )

                if (pxScale >= 0.12f) {
                    val roomW = MapRenderer.ROOM_WIDTH_PX * pxScale
                    val roomH = MapRenderer.ROOM_HEIGHT_PX * pxScale
                    for (x in 0..map.boundsWidth) {
                        val lineX = imgLeft + x * roomW
                        drawLine(T.gridLine, Offset(lineX, imgTop), Offset(lineX, imgTop + scaledHeight))
                    }
                    for (y in 0..map.boundsHeight) {
                        val lineY = imgTop + y * roomH
                        drawLine(T.gridLine, Offset(imgLeft, lineY), Offset(imgLeft + scaledWidth, lineY))
                    }
                    drawRect(
                        color = T.border,
                        topLeft = Offset(imgLeft, imgTop),
                        size = Size(scaledWidth, scaledHeight),
                        style = Stroke(width = 1f)
                    )
                }
            }

            RogueDawnWorldOverlay(
                map = map,
                scale = scale,
                onZoomIn = { scale = (scale.coerceAtLeast(0.03f) * 1.25f).coerceIn(0.03f, 4f) },
                onZoomOut = { scale = (scale.coerceAtLeast(0.03f) / 1.25f).coerceIn(0.03f, 4f) },
                onFit = { scale = -1f; offset = Offset.Zero },
                modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp)
            )
        }

        if (isRendering) {
            Surface(
                color = T.surfaceVariant.copy(alpha = 0.88f),
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.align(Alignment.Center)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    CircularProgressIndicator(color = T.accent, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                    Text("Rendering Rogue Dawn map...", color = T.textPrimary, fontSize = 12.sp)
                }
            }
        }

        if (renderError != null) {
            Text(
                renderError!!,
                color = T.errorRed,
                modifier = Modifier.align(Alignment.Center).padding(16.dp)
            )
        }

        if (preview == null && !isRendering && renderError == null) {
            Text(
                "Rogue Dawn map preview will appear here",
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
                "Rogue Dawn map ${map.width}x${map.height}px (${map.placedRooms} cells)",
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
