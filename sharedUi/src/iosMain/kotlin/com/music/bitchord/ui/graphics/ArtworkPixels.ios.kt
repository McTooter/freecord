package com.music.bitchord.ui.graphics

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import coil3.Image
import coil3.request.ImageRequest
import coil3.toBitmap
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo

// Skia bitmaps are always CPU-readable, as on the desktop.
actual fun ImageRequest.Builder.forPixelAccess(): ImageRequest.Builder = this

actual fun Image.toImageBitmap(): ImageBitmap = toBitmap().asComposeImageBitmap()

actual fun imageBitmapOf(pixels: IntArray, width: Int, height: Int): ImageBitmap {
    val bitmap = Bitmap()
    bitmap.allocPixels(ImageInfo(width, height, ColorType.BGRA_8888, ColorAlphaType.UNPREMUL))
    // BGRA_8888 little-endian is ARGB as an int, byte for byte — packed by
    // hand because common code has no ByteBuffer to lean on.
    val bytes = ByteArray(pixels.size * 4)
    for (i in pixels.indices) {
        val argb = pixels[i]
        bytes[i * 4] = (argb and 0xFF).toByte()
        bytes[i * 4 + 1] = ((argb shr 8) and 0xFF).toByte()
        bytes[i * 4 + 2] = ((argb shr 16) and 0xFF).toByte()
        bytes[i * 4 + 3] = ((argb shr 24) and 0xFF).toByte()
    }
    bitmap.installPixels(bytes)
    bitmap.setImmutable()
    return bitmap.asComposeImageBitmap()
}

actual fun paletteSwatches(image: ImageBitmap, maxColors: Int, clearFilters: Boolean): List<Swatch> =
    PaletteQuantizer.swatches(image, maxColors, clearFilters)
