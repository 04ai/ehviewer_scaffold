package com.example.ehviewer_scaffold.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import coil3.compose.rememberAsyncImagePainter
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.request.crossfade
import coil3.toBitmap
import com.example.ehviewer_scaffold.rust.GalleryThumbnail

/**
 * High-performance thumbnail component supporting both E-Hentai CSS sprite sheets
 * (Mode A: div.gdtm, 20 thumbnails per sprite image) and direct thumbnails (Mode B: div.gdtl).
 *
 * For sprite sheets:
 * Coil caches the shared sprite image once in memory. All 20 thumbnails reuse the same
 * cached Bitmap and draw their respective sub-rectangles via Canvas, eliminating redundant
 * downloads and preventing duplicate top-left corner rendering.
 */
@Composable
fun EhThumbnail(
    thumb: GalleryThumbnail,
    contentDescription: String? = null,
    modifier: Modifier = Modifier
) {
    if (thumb.url.isBlank()) {
        Box(modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant))
        return
    }

    val isSprite = thumb.width > 0 && thumb.height > 0
    val context = LocalContext.current

    if (!isSprite) {
        // Mode B: Direct standalone thumbnail
        val imageRequest = remember(thumb.url) {
            ImageRequest.Builder(context)
                .data(thumb.url)
                .crossfade(false)
                .allowHardware(true)
                .build()
        }
        AsyncImage(
            model = imageRequest,
            contentDescription = contentDescription,
            contentScale = ContentScale.Crop,
            modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant)
        )
    } else {
        // Mode A: CSS Sprite sheet sub-region rendering
        val spriteRequest = remember(thumb.url) {
            ImageRequest.Builder(context)
                .data(thumb.url)
                .crossfade(false)
                .allowHardware(false) // Must be software bitmap so Canvas.drawImage can read pixels
                .build()
        }

        val painter = rememberAsyncImagePainter(model = spriteRequest)
        val painterState by painter.state.collectAsState()

        Box(
            modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            when (val s = painterState) {
                is AsyncImagePainter.State.Success -> {
                    val bitmap = remember(s) {
                        try {
                            (s.result.image as? coil3.BitmapImage)?.bitmap ?: s.result.image.toBitmap()
                        } catch (_: Throwable) {
                            null
                        }
                    }

                    if (bitmap != null) {
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            val maxW = bitmap.width
                            val maxH = bitmap.height
                            val sx = thumb.offsetX.coerceIn(0, (maxW - 1).coerceAtLeast(0))
                            val sy = thumb.offsetY.coerceIn(0, (maxH - 1).coerceAtLeast(0))
                            val sw = thumb.width.coerceAtMost(maxW - sx)
                            val sh = thumb.height.coerceAtMost(maxH - sy)

                            if (sw > 0 && sh > 0 && size.width > 0 && size.height > 0) {
                                val targetAspect = size.width / size.height
                                val srcAspect = sw.toFloat() / sh.toFloat()

                                val (cropW, cropH) = if (srcAspect > targetAspect) {
                                    // Source is wider than cell: crop horizontally centered
                                    val targetW = (sh * targetAspect).toInt().coerceIn(1, sw)
                                    targetW to sh
                                } else {
                                    // Source is taller than cell: crop vertically centered
                                    val targetH = (sw / targetAspect).toInt().coerceIn(1, sh)
                                    sw to targetH
                                }

                                val cropX = sx + ((sw - cropW) / 2).coerceAtLeast(0)
                                val cropY = sy + ((sh - cropH) / 2).coerceAtLeast(0)

                                drawImage(
                                    image = bitmap.asImageBitmap(),
                                    srcOffset = IntOffset(cropX, cropY),
                                    srcSize = IntSize(cropW, cropH),
                                    dstOffset = IntOffset.Zero,
                                    dstSize = IntSize(size.width.toInt(), size.height.toInt())
                                )
                            }
                        }
                    }
                }
                else -> {
                    // Loading or Error placeholder: background color set on outer Box
                }
            }
        }
    }
}
