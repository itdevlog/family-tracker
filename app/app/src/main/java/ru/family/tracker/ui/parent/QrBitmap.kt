package ru.family.tracker.ui.parent

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter

/**
 * Renders [content] as a square QR bitmap with a one-module quiet zone.
 *
 * Returns null when ZXing refuses the payload (for example, when it is too long for a QR
 * code), so callers can show a readable error instead of crashing.
 */
fun encodeQr(content: String, sizePx: Int = 768): Bitmap? = runCatching {
    val matrix = QRCodeWriter().encode(
        content,
        BarcodeFormat.QR_CODE,
        sizePx,
        sizePx,
        mapOf(EncodeHintType.MARGIN to 1),
    )

    val pixels = IntArray(sizePx * sizePx)
    for (y in 0 until sizePx) {
        val row = y * sizePx
        for (x in 0 until sizePx) {
            pixels[row + x] = if (matrix[x, y]) Color.BLACK else Color.WHITE
        }
    }

    Bitmap.createBitmap(pixels, sizePx, sizePx, Bitmap.Config.RGB_565)
}.getOrNull()
