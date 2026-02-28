package com.metroid.editor.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.metroid.editor.data.Room
import com.metroid.editor.rom.MapRenderer
import java.awt.image.BufferedImage

@Composable
fun MapViewer(
    room: Room,
    renderer: MapRenderer,
    modifier: Modifier = Modifier
) {
    var scale by remember { mutableStateOf(2f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var renderedImage by remember { mutableStateOf<ImageBitmap?>(null) }
    var renderError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(room) {
        try {
            val result = renderer.renderRoom(room)
            renderedImage = pixelsToImageBitmap(result.pixels, result.width, result.height)
            renderError = null
        } catch (e: Exception) {
            renderError = "Render error: ${e.message}"
            renderedImage = null
        }
    }

    Box(
        modifier = modifier
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    scale = (scale * zoom).coerceIn(0.5f, 8f)
                    offset += pan
                }
            }
    ) {
        val image = renderedImage
        if (image != null) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val centerX = size.width / 2 + offset.x
                val centerY = size.height / 2 + offset.y
                val scaledWidth = image.width * scale
                val scaledHeight = image.height * scale

                drawImage(
                    image = image,
                    dstOffset = androidx.compose.ui.unit.IntOffset(
                        (centerX - scaledWidth / 2).toInt(),
                        (centerY - scaledHeight / 2).toInt()
                    ),
                    dstSize = IntSize(scaledWidth.toInt(), scaledHeight.toInt()),
                    filterQuality = FilterQuality.None  // Pixel-perfect scaling
                )
            }
        }

        if (renderError != null) {
            Text(
                renderError!!,
                color = Color(0xFFFF6666),
                modifier = Modifier.align(Alignment.Center).padding(16.dp)
            )
        }

        // Zoom indicator
        Text(
            "${(scale * 100).toInt()}%",
            color = Color(0xFF606080),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp)
        )
    }
}

/**
 * Convert an ARGB pixel array to a Compose ImageBitmap.
 */
fun pixelsToImageBitmap(pixels: IntArray, width: Int, height: Int): ImageBitmap {
    val bufferedImage = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
    bufferedImage.setRGB(0, 0, width, height, pixels, 0, width)
    return bufferedImage.toComposeImageBitmap()
}
