package com.mirrorapps.kit.feedback.media

import com.mirrorapps.kit.MirrorAppsKit
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject

/** What kind of media, as the backend names it. */
enum class MediaKind(val wire: String, val maxBytes: Long) {
    // Limits from trip-review/SPEC.md ("Media"), checked on the phone too so
    // a file that's too big fails before anything is sent.
    IMAGE("image", 20L * 1024 * 1024),
    VIDEO("video", 500L * 1024 * 1024),
    AUDIO("audio", 100L * 1024 * 1024),
}

/**
 * A file ready to upload: what the backend is told about it, and how to read
 * it from any offset (an upload resumes where the connection dropped).
 *
 * @property recordedAt When the recording started, phone clock, ms. For a
 *   picked video, its creation time from the file's metadata.
 * @property durationMs Video and audio length; 0 for images.
 */
class MediaSource(
    val kind: MediaKind,
    val contentType: String,
    val bytes: Long,
    val recordedAt: Long,
    val durationMs: Long,
    val open: (offset: Long) -> InputStream,
)

/** How an upload ended. */
sealed class MediaUpload {
    /** Uploaded. [status] is `ready`, or `transcribing` for audio and video. */
    data class Done(val mediaId: String, val status: String) : MediaUpload()

    /** Not uploaded. [message] is for the user. */
    data class Failed(val message: String) : MediaUpload()
}

/** `GET /v1/media/<id>`. */
data class MediaState(val status: String, val transcript: String?)

/** Uploads media for the Feedback tab and reads back its transcript. */
interface MediaClient {
    /** Uploads [source]; [progress] gets 0..1 as bytes go. Never throws except on cancellation. */
    suspend fun upload(source: MediaSource, app: String, progress: (Float) -> Unit): MediaUpload

    /** The media's status and transcript, or null when the backend couldn't be reached. */
    suspend fun state(mediaId: String): MediaState?
}

/**
 * The backend's media endpoints (`trip-review/SPEC.md`, "Media"):
 *
 * 1. `POST /v1/media/start {kind, contentType, bytes, recordedAt,
 *    durationMs, app}` gives `{mediaId, uploadUrl}`;
 * 2. the file goes to `uploadUrl`, a Cloud Storage resumable session, in
 *    pieces with `Content-Range`, resuming from what Storage says it has
 *    when the connection drops;
 * 3. `POST /v1/media/<id>/done` gives `{status}`.
 *
 * Every backend call is Bearer the host's credential; the upload URL needs
 * none (it is the permission). Never logs the URL.
 */
class HttpMediaClient(
    sharedClient: OkHttpClient,
    private val base: String,
    private val credential: () -> String?,
    /** First wait after a failed piece; doubles each time. */
    private val backoffMs: Long = 2_000L,
) : MediaClient {

    private val client: OkHttpClient = sharedClient.newBuilder()
        .callTimeout(0, TimeUnit.SECONDS)
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()

    private val log get() = MirrorAppsKit.log

    override suspend fun upload(source: MediaSource, app: String, progress: (Float) -> Unit): MediaUpload =
        withContext(Dispatchers.IO) {
            val code = credential() ?: return@withContext MediaUpload.Failed(NO_CODE)
            if (source.bytes <= 0) return@withContext MediaUpload.Failed("That file is empty.")
            if (source.bytes > source.kind.maxBytes) return@withContext MediaUpload.Failed(TOO_LARGE)

            // 1. Start.
            val startBody = JSONObject()
                .put("kind", source.kind.wire)
                .put("contentType", source.contentType)
                .put("bytes", source.bytes)
                .put("recordedAt", source.recordedAt)
                .put("durationMs", source.durationMs)
                .put("app", app)
            val start = call(
                Request.Builder()
                    .url("$base/v1/media/start")
                    .header("Authorization", "Bearer $code")
                    .post(startBody.toString().toRequestBody(JSON))
                    .build(),
            )
            if (start.status != 200) {
                log.w(TAG, "start ${source.kind.wire} ${source.bytes}B: ${start.brief()}")
                return@withContext MediaUpload.Failed(startError(start))
            }
            val started = runCatching { JSONObject(start.body) }.getOrNull()
            val mediaId = started?.optString("mediaId").orEmpty()
            val uploadUrl = started?.optString("uploadUrl").orEmpty()
            // https only, except against a plain-http test server.
            val urlOk = uploadUrl.startsWith("https://") || (base.startsWith("http://") && uploadUrl.startsWith("http://"))
            if (mediaId.isEmpty() || !urlOk) {
                log.w(TAG, "start: unreadable answer")
                return@withContext MediaUpload.Failed(UNREACHABLE)
            }
            log.i(TAG, "start $mediaId: ${source.kind.wire} ${source.contentType} ${source.bytes}B")

            // 2. The file, to Cloud Storage.
            val sent = putResumable(uploadUrl, source, progress)
            if (sent != null) {
                log.w(TAG, "upload $mediaId failed: $sent")
                return@withContext MediaUpload.Failed(UNREACHABLE)
            }
            progress(1f)

            // 3. Done. A 409 means Storage doesn't have it all yet: once more.
            var done = finish(code, mediaId)
            if (done.status == 409) {
                delay(backoffMs)
                done = finish(code, mediaId)
            }
            val status = runCatching { JSONObject(done.body).optString("status") }.getOrNull().orEmpty()
            log.i(TAG, "done $mediaId: ${done.brief()}")
            when {
                done.status == 200 && status.isNotEmpty() -> MediaUpload.Done(mediaId, status)
                done.status == 413 -> MediaUpload.Failed(TOO_LARGE)
                done.status == 422 -> MediaUpload.Failed("That file couldn't be read. Try another one.")
                else -> MediaUpload.Failed(UNREACHABLE)
            }
        }

    override suspend fun state(mediaId: String): MediaState? = withContext(Dispatchers.IO) {
        val code = credential() ?: return@withContext null
        val r = call(
            Request.Builder()
                .url("$base/v1/media/$mediaId")
                .header("Authorization", "Bearer $code")
                .get()
                .build(),
        )
        if (r.status != 200) {
            log.d(TAG, "state $mediaId: ${r.brief()}")
            return@withContext null
        }
        val j = runCatching { JSONObject(r.body) }.getOrNull() ?: return@withContext null
        MediaState(
            status = j.optString("status"),
            transcript = if (j.has("transcript") && !j.isNull("transcript")) j.optString("transcript") else null,
        )
    }

    private fun finish(code: String, mediaId: String): Reply = call(
        Request.Builder()
            .url("$base/v1/media/$mediaId/done")
            .header("Authorization", "Bearer $code")
            .post(ByteArray(0).toRequestBody(null))
            .build(),
    )

    /**
     * PUTs the file to the resumable session in [CHUNK_BYTES] pieces.
     * Returns null when Storage has all of it, or why not.
     */
    private suspend fun putResumable(url: String, source: MediaSource, progress: (Float) -> Unit): String? {
        val total = source.bytes
        var offset = 0L
        var failures = 0
        val buffer = ByteArray(minOf(CHUNK_BYTES.toLong(), total).toInt())
        // One stream, read in order; reopened only when an upload resumes
        // somewhere else (content streams can only skip by reading).
        var input: InputStream? = null
        var inputAt = 0L
        try {
            while (offset < total) {
                kotlin.coroutines.coroutineContext.ensureActive()
                val length = minOf(CHUNK_BYTES.toLong(), total - offset).toInt()
                val outcome = try {
                    if (input == null || inputAt != offset) {
                        input?.close()
                        input = source.open(offset)
                        inputAt = offset
                    }
                    val read = readFully(input!!, buffer, length)
                    inputAt += read
                    if (read != length) return "file shorter than declared ($offset+$read of $total)"
                    client.newCall(
                        Request.Builder()
                            .url(url)
                            .header("Content-Range", "bytes $offset-${offset + length - 1}/$total")
                            .put(buffer.toRequestBody(null, 0, length))
                            .build(),
                    ).execute().use { it.toReply() }
                } catch (e: IOException) {
                    Reply(-1, e.javaClass.simpleName, null)
                }
                when {
                    outcome.status == 200 || outcome.status == 201 -> return null
                    outcome.status == 308 -> {
                        offset = ResumableRange.nextOffset(outcome.range)
                        failures = 0
                        progress(offset.toFloat() / total)
                    }
                    outcome.status in 400..499 && outcome.status != 408 && outcome.status != 429 ->
                        return "HTTP ${outcome.status}"
                    else -> {
                        // Network or server trouble: wait, ask Storage how much
                        // it has, and carry on from there.
                        failures++
                        if (failures > MAX_FAILURES) return "gave up: ${outcome.brief()}"
                        delay(backoffMs * (1L shl (failures - 1)))
                        val asked = queryOffset(url, total)
                        if (asked == DONE) return null
                        if (asked != null) offset = asked
                    }
                }
            }
        } finally {
            runCatching { input?.close() }
        }
        // Every byte was acknowledged with a 308: ask once more for the final answer.
        return if (queryOffset(url, total) == DONE) null else "Storage didn't finish the upload"
    }

    /** How many bytes Storage has: [DONE] when complete, null when it couldn't be asked. */
    private fun queryOffset(url: String, total: Long): Long? = try {
        client.newCall(
            Request.Builder()
                .url(url)
                .header("Content-Range", "bytes */$total")
                .put(ByteArray(0).toRequestBody(null))
                .build(),
        ).execute().use { r ->
            when (r.code) {
                200, 201 -> DONE
                308 -> ResumableRange.nextOffset(r.header("Range"))
                else -> null
            }
        }
    } catch (e: IOException) {
        null
    }

    private fun readFully(input: InputStream, buffer: ByteArray, length: Int): Int {
        var n = 0
        while (n < length) {
            val r = input.read(buffer, n, length - n)
            if (r < 0) break
            n += r
        }
        return n
    }

    private class Reply(val status: Int, val body: String, val range: String?) {
        fun brief(): String = if (status < 0) "no answer ($body)" else "HTTP $status ${body.take(80)}"
    }

    private fun Response.toReply() = Reply(code, body?.string().orEmpty(), header("Range"))

    private fun call(request: Request): Reply = try {
        client.newCall(request).execute().use { it.toReply() }
    } catch (e: IOException) {
        Reply(-1, e.javaClass.simpleName, null)
    }

    private fun startError(r: Reply): String {
        val error = runCatching { JSONObject(r.body).optString("error") }.getOrNull().orEmpty()
        return when {
            r.status == 401 -> NO_CODE
            r.status == 429 -> "That's today's limit for attachments. Try again tomorrow."
            r.status == 413 -> TOO_LARGE
            r.status == 400 -> "That file can't be sent ($error)."
            else -> UNREACHABLE
        }
    }

    private companion object {
        const val TAG = "FeedbackMedia"
        val JSON = "application/json".toMediaType()

        /** Storage wants pieces in multiples of 256 KiB; 8 MiB keeps retries cheap. */
        const val CHUNK_BYTES = 8 * 1024 * 1024
        const val MAX_FAILURES = 6
        const val DONE = -2L

        const val NO_CODE = "Attachments need an active subscription."
        const val TOO_LARGE = "That file is too big to send."
        const val UNREACHABLE = "Couldn't upload. Check your connection and try again."
    }
}

/** Cloud Storage's `Range: bytes=0-N` answer on a resumable upload. */
object ResumableRange {
    /** The next byte to send: N+1, or 0 when Storage has nothing yet (no header). */
    fun nextOffset(range: String?): Long {
        if (range.isNullOrBlank()) return 0L
        val end = range.substringAfterLast('-', "").trim().toLongOrNull() ?: return 0L
        return end + 1
    }
}
