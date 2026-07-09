package ru.ngscanner.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.ngscanner.llm.LlmImage
import java.io.ByteArrayOutputStream

/**
 * Готовит выбранное фото к отправке в vision-модель: даунскейл по длинной
 * стороне (экономия визуальных токенов), JPEG и base64 без переносов.
 *
 * TODO: коррекция поворота по EXIF (сейчас фото с камеры может прийти боком —
 * vision-модели обычно справляются, но стоит добавить androidx.exifinterface).
 */
object ImageEncoder {

    suspend fun encode(
        context: Context,
        uri: Uri,
        maxEdgePx: Int = 1568,
        jpegQuality: Int = 85,
    ): LlmImage? = withContext(Dispatchers.IO) {
        runCatching {
            val resolver = context.contentResolver
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, maxEdgePx)
            }
            val decoded = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
                ?: return@runCatching null
            val scaled = downscale(decoded, maxEdgePx)
            val out = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, jpegQuality, out)
            LlmImage(Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP), "image/jpeg")
        }.getOrNull()
    }

    private fun sampleSize(w: Int, h: Int, maxEdge: Int): Int {
        var s = 1
        var lw = w
        var lh = h
        while (maxOf(lw, lh) / 2 >= maxEdge) {
            lw /= 2
            lh /= 2
            s *= 2
        }
        return s
    }

    private fun downscale(src: Bitmap, maxEdge: Int): Bitmap {
        val long = maxOf(src.width, src.height)
        if (long <= maxEdge) return src
        val scale = maxEdge.toFloat() / long
        return Bitmap.createScaledBitmap(src, (src.width * scale).toInt(), (src.height * scale).toInt(), true)
    }
}
