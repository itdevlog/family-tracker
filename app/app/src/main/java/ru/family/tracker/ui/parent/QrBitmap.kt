package ru.family.tracker.ui.parent

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter

/** Four-module quiet zone keeps the QR scannable on dark backgrounds. */
private const val QR_MARGIN = 4

/**
 * Renders [content] as a square QR bitmap with a quiet zone.
 *
 * Returns null when ZXing refuses the payload (for example, when it is too long for a QR
 * code), so callers can show a readable error instead of crashing. Encoding must run off
 * the main thread; callers use `produceState`/`Dispatchers.Default`.
 */
fun encodeQr(content: String, sizePx: Int = 768): Bitmap? = try {
    val matrix = QRCodeWriter().encode(
        content,
        BarcodeFormat.QR_CODE,
        sizePx,
        sizePx,
        mapOf(EncodeHintType.MARGIN to QR_MARGIN),
    )

    val pixels = IntArray(sizePx * sizePx)
    for (y in 0 until sizePx) {
        val row = y * sizePx
        for (x in 0 until sizePx) {
            pixels[row + x] = if (matrix[x, y]) Color.BLACK else Color.WHITE
        }
    }

    Bitmap.createBitmap(pixels, sizePx, sizePx, Bitmap.Config.RGB_565)
} catch (_: Exception) {
    null
}
