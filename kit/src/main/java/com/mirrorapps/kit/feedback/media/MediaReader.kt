package com.mirrorapps.kit.feedback.media

import android.content.Context
import android.database.Cursor
import android.media.ExifInterface
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import com.mirrorapps.kit.MirrorAppsKit
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * Turns a picked picture or video into a [MediaSource]: its type, exact
 * size, and when it was recorded.
 *
 * `recordedAt` matters most for videos: the reviewer lines video second `s`
 * up with log time `recordedAt + s * 1000`, so it's the file's own creation
 * time from its metadata, never the time it was picked or uploaded. Where
 * each value came from is logged, so a misaligned video can be traced.
 */
object MediaReader {
    private const val TAG = "FeedbackMedia"

    /** Earliest believable timestamp. Older is a camera clock that was never set. */
    private const val MIN_TIME_MS = 1_577_836_800_000L // 2020-01-01T00:00:00Z

    /** Reads [uri] on the calling (background) thread. Null when it can't be read at all. */
    fun read(context: Context, uri: Uri, kind: MediaKind): MediaSource? {
        val resolver = context.contentResolver
        val type = resolver.getType(uri)?.lowercase(Locale.ROOT)
            ?.takeIf { it.startsWith(kind.wire + "/") }
            ?: defaultType(kind)
        val columns = columns(context, uri)
        var bytes = columns.size ?: statSize(context, uri)
        var opener: (Long) -> InputStream = { offset -> openAt(context, uri, offset) }
        if (bytes == null || bytes <= 0) {
            // The provider won't say how big it is: copy it, so the size
            // promised to the backend is exact.
            val copy = copyToCache(context, uri) ?: return null
            bytes = copy.length()
            opener = { offset -> FileInputStream(copy).also { skipFully(it, offset) } }
        }

        var duration = 0L
        var recordedAt: Long? = null
        var from = "now"
        if (kind == MediaKind.VIDEO) {
            val meta = videoMetadata(context, uri)
            duration = meta.first ?: 0L
            meta.second?.let { recordedAt = it; from = "metadata" }
        }
        if (recordedAt == null) columns.taken?.let { recordedAt = it; from = "date taken" }
        if (recordedAt == null && kind == MediaKind.IMAGE) {
            exifTime(context, uri)?.let { recordedAt = it; from = "exif" }
        }
        val at = recordedAt ?: System.currentTimeMillis()
        MirrorAppsKit.log.i(TAG, "picked ${kind.wire}: $type ${bytes}B, ${duration}ms, recordedAt from $from")
        return MediaSource(
            kind = kind,
            contentType = type,
            bytes = bytes,
            recordedAt = at,
            durationMs = duration,
            open = opener,
        )
    }

    /** A file the app wrote itself (a voice recording). */
    fun fromFile(file: File, kind: MediaKind, contentType: String, recordedAt: Long, durationMs: Long) = MediaSource(
        kind = kind,
        contentType = contentType,
        bytes = file.length(),
        recordedAt = recordedAt,
        durationMs = durationMs,
        open = { offset -> FileInputStream(file).also { skipFully(it, offset) } },
    )

    private fun defaultType(kind: MediaKind) = when (kind) {
        MediaKind.IMAGE -> "image/jpeg"
        MediaKind.VIDEO -> "video/mp4"
        MediaKind.AUDIO -> "audio/mp4"
    }

    private class Columns(val size: Long?, val taken: Long?)

    /**
     * Size and date taken from the provider. The photo picker answers
     * `date_taken_ms`; MediaStore answers `datetaken` (ms) and
     * `date_added` (seconds), which is only a last resort.
     */
    private fun columns(context: Context, uri: Uri): Columns = try {
        context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            if (!c.moveToFirst()) return@use Columns(null, null)
            val size = c.long(OpenableColumns.SIZE)
            val taken = listOfNotNull(
                c.long("date_taken_ms"),
                c.long("datetaken"),
                c.long("date_added")?.let { it * 1000 },
            ).firstOrNull { it >= MIN_TIME_MS }
            Columns(size, taken)
        } ?: Columns(null, null)
    } catch (e: Exception) {
        Columns(null, null)
    }

    private fun Cursor.long(name: String): Long? {
        val i = getColumnIndex(name)
        if (i < 0 || isNull(i)) return null
        return try {
            getLong(i)
        } catch (e: Exception) {
            null
        }
    }

    private fun statSize(context: Context, uri: Uri): Long? = try {
        context.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize.takeIf { s -> s > 0 } }
    } catch (e: Exception) {
        null
    }

    private fun openAt(context: Context, uri: Uri, offset: Long): InputStream {
        val input = context.contentResolver.openInputStream(uri)
            ?: throw java.io.FileNotFoundException("no stream")
        skipFully(input, offset)
        return input
    }

    private fun skipFully(input: InputStream, offset: Long) {
        var left = offset
        val scratch = ByteArray(64 * 1024)
        while (left > 0) {
            val skipped = input.skip(left)
            if (skipped > 0) {
                left -= skipped
                continue
            }
            // skip() may refuse; read instead.
            val r = input.read(scratch, 0, minOf(scratch.size.toLong(), left).toInt())
            if (r < 0) throw java.io.EOFException("file ended at ${offset - left} of $offset")
            left -= r
        }
    }

    private fun copyToCache(context: Context, uri: Uri): File? = try {
        val dir = File(context.cacheDir, "kit_media").apply { mkdirs() }
        val out = File(dir, "pick-" + System.currentTimeMillis())
        context.contentResolver.openInputStream(uri)?.use { input ->
            out.outputStream().use { input.copyTo(it) }
        } ?: return null
        out.deleteOnExit()
        out
    } catch (e: Exception) {
        MirrorAppsKit.log.w(TAG, "copy failed", e)
        null
    }

    /** Duration and creation time (ms) from a video's own metadata. */
    private fun videoMetadata(context: Context, uri: Uri): Pair<Long?, Long?> {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(context, uri)
            val duration = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
            val created = parseVideoDate(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DATE))
            duration to created
        } catch (e: Exception) {
            null to null
        } finally {
            runCatching { r.release() }
        }
    }

    /**
     * `METADATA_KEY_DATE` as Android formats it: `20261118T143012.000Z`
     * (UTC), sometimes without the milliseconds. Null when absent, unreadable
     * or before 2020 (QuickTime's zero date is 1904).
     */
    fun parseVideoDate(value: String?): Long? {
        val v = value?.trim().orEmpty()
        if (v.isEmpty()) return null
        for (pattern in listOf("yyyyMMdd'T'HHmmss.SSS'Z'", "yyyyMMdd'T'HHmmss'Z'", "yyyyMMdd'T'HHmmss")) {
            val f = SimpleDateFormat(pattern, Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
                isLenient = false
            }
            val t = try {
                f.parse(v)?.time
            } catch (e: ParseException) {
                null
            }
            if (t != null) return t.takeIf { it >= MIN_TIME_MS }
        }
        return null
    }

    /** A picture's EXIF capture time, in the phone's time zone unless EXIF names one. */
    private fun exifTime(context: Context, uri: Uri): Long? = try {
        context.contentResolver.openInputStream(uri)?.use { input ->
            val exif = ExifInterface(input)
            val stamp = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
                ?: exif.getAttribute(ExifInterface.TAG_DATETIME)
                ?: return@use null
            val f = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US)
            f.parse(stamp)?.time?.takeIf { it >= MIN_TIME_MS }
        }
    } catch (e: Exception) {
        null
    }
}
