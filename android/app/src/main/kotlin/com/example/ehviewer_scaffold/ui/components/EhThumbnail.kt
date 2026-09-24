package com.example.ehviewer_scaffold.ui.components

import android.util.LruCache
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import coil3.compose.AsyncImage
import coil3.imageLoader
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.request.crossfade
import coil3.toBitmap
import com.example.ehviewer_scaffold.rust.GalleryThumbnail
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async

/**
 * In-memory LRU cache for shared thumbnail sprite sheets.
 * When multiple thumbnails reference the same sprite URL, it is decoded once
 * and immediately reused across all cells without duplicate network calls or re-decoding.
 */
object SpriteSheetCache {
    private val cache = LruCache<String, ImageBitmap>(32)
    private val inFlight = java.util.concurrent.ConcurrentHashMap<String, Deferred<ImageBitmap?>>()
    private val coroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun get(url: String): ImageBitmap? = synchronized(cache) {
        cache.get(url)
    }

    fun put(url: String, bitmap: ImageBitmap) = synchronized(cache) {
        cache.put(url, bitmap)
    }

    suspend fun getOrLoad(
        url: String,
        loader: suspend () -> ImageBitmap?
    ): ImageBitmap? {
        get(url)?.let { return it }

        val deferred = inFlight.computeIfAbsent(url) {
            coroutineScope.async {
                try {
                    loader()
                } finally {
                    inFlight.remove(url)
                }
            }
        }

        val result = try {
            deferred.await()
        } catch (e: Throwable) {
            inFlight.remove(url)
            null
        }
        if (result != null) {
            put(url, result)
        } else {
            inFlight.remove(url)
        }
        return result
    }
}

/**
 * Normalizes thumbnail URLs to ensure protocol-relative or cleartext HTTP schemes
 * are safely converted to HTTPS.
 */
fun normalizeThumbUrl(raw: String): String {
    val t = raw.trim()
    return when {
        t.startsWith("//") -> "https:$t"
        t.startsWith("http://") -> "https://" + t.removePrefix("http://")
        else -> t
    }
}

/**
 * High-performance thumbnail component supporting both E-Hentai CSS sprite sheets
 * (Mode A: div.gdtm or div#gdt > a, 20 thumbnails per sprite image) and direct thumbnails (Mode B: div.gdtl).
 */
@Composable
fun EhThumbnail(
    thumb: GalleryThumbnail,
    contentDescription: String? = null,
    modifier: Modifier = Modifier
) {
    val cleanUrl = normalizeThumbUrl(thumb.url)
    if (cleanUrl.isBlank()) {
        Box(modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant))
        return
    }

    val isSprite = thumb.width > 0 && thumb.height > 0
    val context = LocalContext.current

    // Modifier that pins the cell to the thumbnail's own aspect ratio and lets the
    // image fit inside it. Callers pass a fixed-size modifier, so honour theirs and
    // only synthesise an aspectRatio when their modifier has no size of its own.
    val aspectModifier = if (thumb.width > 0 && thumb.height > 0) {
        Modifier.aspectRatio(thumb.width.toFloat() / thumb.height.toFloat())
    } else {
        Modifier
    }

    if (!isSprite) {
        // Mode B: Direct standalone thumbnail
        val imageRequest = remember(cleanUrl) {
            ImageRequest.Builder(context)
                .data(cleanUrl)
                .crossfade(false)
                .allowHardware(true)
                .build()
        }
        AsyncImage(
            model = imageRequest,
            contentDescription = contentDescription,
            // Fit, not Crop: wide covers keep their full width. `modifier` already
            // supplies the on-screen frame, so there is nothing to letterbox unless
            // the shape genuinely differs — in which case a bar beats lost edges.
            contentScale = ContentScale.Fit,
            modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant)
        )
    } else {
        // Mode A: CSS Sprite sheet sub-region rendering
        var bitmap by remember(cleanUrl) { mutableStateOf<ImageBitmap?>(SpriteSheetCache.get(cleanUrl)) }

        LaunchedEffect(cleanUrl) {
            if (bitmap == null) {
                val cached = SpriteSheetCache.get(cleanUrl)
                if (cached != null) {
                    bitmap = cached
                    return@LaunchedEffect
                }
                val loaded = SpriteSheetCache.getOrLoad(cleanUrl) {
                    val spriteRequest = ImageRequest.Builder(context)
                        .data(cleanUrl)
                        .crossfade(false)
                        .allowHardware(false)
                        .build()
                    try {
                        val result = context.imageLoader.execute(spriteRequest)
                        if (result is SuccessResult) {
                            val rawBitmap = (result.image as? coil3.BitmapImage)?.bitmap ?: result.image.toBitmap()
                            rawBitmap.asImageBitmap()
                        } else {
                            if (result is ErrorResult) {
                                android.util.Log.e("EhThumbnail", "Failed to load sprite: $cleanUrl", result.throwable)
                            }
                            null
                        }
                    } catch (e: Exception) {
                        android.util.Log.e("EhThumbnail", "Exception loading sprite: $cleanUrl", e)
                        null
                    }
                }
                bitmap = loaded
            }
        }

        Box(
            modifier = modifier
                .then(aspectModifier)
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            if (bitmap != null) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val b = bitmap!!
                    val maxW = b.width
                    val maxH = b.height

                    // Support resolution scaling (e.g. 2x retina sprites vs CSS dimensions)
                    val expectedMaxX = thumb.offsetX + thumb.width
                    val scaleX = if (expectedMaxX > maxW && expectedMaxX > 0) {
                        maxW.toFloat() / expectedMaxX
                    } else 1f
                    val scaleY = scaleX

                    val sx = (thumb.offsetX * scaleX).toInt().coerceIn(0, (maxW - 1).coerceAtLeast(0))
                    val sy = (thumb.offsetY * scaleY).toInt().coerceIn(0, (maxH - 1).coerceAtLeast(0))
                    val sw = (thumb.width * scaleX).toInt().coerceIn(1, maxW - sx)
                    val sh = (thumb.height * scaleY).toInt().coerceIn(1, maxH - sy)

                    if (sw > 0 && sh > 0 && size.width > 0 && size.height > 0) {
                        val dstW = size.width.toInt()
                        val dstH = size.height.toInt()
                        val targetAspect = size.width / size.height
                        val srcAspect = sw.toFloat() / sh.toFloat()

                        // Centre-crop only when the destination is genuinely a
                        // different shape. When the cell matches the tile's aspect
                        // ratio (the normal case) this resolves to the full tile,
                        // so wide thumbnails are no longer sliced down to portrait.
                        val (cropW, cropH) = if (srcAspect > targetAspect) {
                            val targetW = (sh * targetAspect).toInt().coerceIn(1, sw)
                            targetW to sh
                        } else {
                            val targetH = (sw / targetAspect).toInt().coerceIn(1, sh)
                            sw to targetH
                        }

                        // Preserve the full tile whenever it already fits: letting
                        // drawImage scale it beats cropping pixels away.
                        val (drawW, drawH) = if (cropW == sw && cropH == sh) {
                            sw to sh
                        } else {
                            cropW to cropH
                        }

                        val cropX = sx + ((sw - drawW) / 2).coerceAtLeast(0)
                        val cropY = sy + ((sh - drawH) / 2).coerceAtLeast(0)

                        drawImage(
                            image = b,
                            srcOffset = IntOffset(cropX, cropY),
                            srcSize = IntSize(drawW, drawH),
                            dstOffset = IntOffset.Zero,
                            dstSize = IntSize(dstW, dstH)
                        )
                    }
                }
            }
        }
    }
}
