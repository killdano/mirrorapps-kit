package com.mirrorapps.kit.feedback.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.util.Size

/**
 * Small previews of picked pictures and videos, for the chat: the composer
 * row and the sent message show what was attached. Read once, the moment
 * the user picks the file (the picker's read permission may not last), and
 * kept in memory only, like the rest of the chat.
 */
internal object Thumbnails {
    /** Longest side, in pixels. Enough for a 180 dp preview. */
    private const val MAX_PX = 480

    /** A preview of [uri], or null when there's none (voice, unreadable file). */
    fun load(context: Context, uri: Uri, kind: MediaKind): Bitmap? {
        if (kind == MediaKind.AUDIO) return null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching {
                context.contentResolver.loadThumbnail(uri, Size(MAX_PX, MAX_PX), null)
            }.getOrNull()?.let { return it }
        }
        return if (kind == MediaKind.IMAGE) runCatching { decodeImage(context, uri) }.getOrNull() else null
    }

    /** Fallback for pictures: a sampled decode, turned the way the camera held it. */
    private fun decodeImage(context: Context, uri: Uri): Bitmap? {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        val w = bounds.outWidth
        val h = bounds.outHeight
        if (w <= 0 || h <= 0) return null
        var sample = 1
        while (maxOf(w, h) / (sample * 2) >= MAX_PX) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val bitmap = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
            ?: return null
        val degrees = runCatching {
            resolver.openInputStream(uri)?.use { input ->
                when (ExifInterface(input).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                    else -> 0f
                }
            }
        }.getOrNull() ?: 0f
        if (degrees == 0f) return bitmap
        val turn = Matrix().apply { postRotate(degrees) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, turn, true)
    }
}
