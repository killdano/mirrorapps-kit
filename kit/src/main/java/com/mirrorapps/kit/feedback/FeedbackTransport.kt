package com.mirrorapps.kit.feedback

import com.mirrorapps.kit.MirrorAppsKit
import com.mirrorapps.kit.feedback.media.MediaClient
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * One user message: the app it's about, the text, the bundle on the first
 * message of a chat, and the ids of media uploaded for it.
 */
data class FeedbackMessage(
    val app: String,
    val sessionId: String?,
    val text: String,
    val bundle: JSONObject?,
    val media: List<String> = emptyList(),
)

/** Where Feedback messages go: the hub, or a host's debug-only stand-in. */
interface FeedbackTransport {
    /** Sends [message] and streams the reply. Never throws; failures arrive as [FeedbackEvent.Error]. */
    fun send(message: FeedbackMessage): Flow<FeedbackEvent>

    /**
     * Answers a [FeedbackEvent.ConsentRequest] and streams the agent's
     * follow-up. [bundle] is the full-log bundle when [granted], else null.
     */
    fun consent(app: String, sessionId: String, granted: Boolean, bundle: JSONObject?): Flow<FeedbackEvent>
}

/**
 * Everything the tab talks to, replaced at once: a host's debug-only
 * stand-in implements both halves, so nothing leaves the phone while it's on.
 */
interface FeedbackBackend : FeedbackTransport, MediaClient

/**
 * The real hub, over HTTPS. Request bodies are gzipped JSON (a full log
 * buffer runs to a megabyte or more); replies to messages stream as
 * `text/event-stream`, parsed by [SseParser].
 *
 * Wire format, which the hub depends on: `{app, sessionId?, text, bundle?,
 * media?}` to the message URL, `{app, sessionId, granted, bundle?}` to the
 * consent URL. Add fields; never rename one.
 */
class HttpFeedbackTransport(
    sharedClient: OkHttpClient,
    private val messageUrl: String,
    private val consentUrl: String,
    private val installId: String,
    private val accessCode: () -> String?,
) : FeedbackTransport {

    // A shared client's short read timeout would cut off a reply the agent
    // is still thinking about between tokens.
    private val client: OkHttpClient = sharedClient.newBuilder()
        .callTimeout(0, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    override fun send(message: FeedbackMessage): Flow<FeedbackEvent> {
        val body = JSONObject().apply {
            put("app", message.app)
            message.sessionId?.let { put("sessionId", it) }
            put("text", message.text)
            message.bundle?.let { put("bundle", it) }
            if (message.media.isNotEmpty()) put("media", JSONArray(message.media))
        }
        return stream(messageUrl, body)
    }

    override fun consent(app: String, sessionId: String, granted: Boolean, bundle: JSONObject?): Flow<FeedbackEvent> {
        val body = JSONObject().apply {
            put("app", app)
            put("sessionId", sessionId)
            put("granted", granted)
            bundle?.let { put("bundle", it) }
        }
        return stream(consentUrl, body)
    }

    private fun stream(url: String, body: JSONObject): Flow<FeedbackEvent> = flow {
        val request = request(url, body).newBuilder()
            .header("Accept", "text/event-stream")
            .build()
        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    MirrorAppsKit.log.w(TAG, "${request.url.encodedPath}: HTTP ${response.code}")
                    emit(FeedbackEvent.Error("Feedback isn't reachable right now (${response.code})."))
                    return@use
                }
                val source = response.body?.source()
                if (source == null) {
                    emit(FeedbackEvent.Error("Feedback sent an empty reply."))
                    return@use
                }
                val parser = SseParser()
                while (true) {
                    val line = source.readUtf8Line() ?: break
                    val sse = parser.feed(line) ?: continue
                    val event = FeedbackEvent.from(sse) ?: continue
                    emit(event)
                    if (event is FeedbackEvent.Done) break
                }
            }
        } catch (e: IOException) {
            MirrorAppsKit.log.w(TAG, "${request.url.encodedPath} failed", e)
            emit(FeedbackEvent.Error("Couldn't reach Feedback. Check your connection and try again."))
        }
    }.flowOn(Dispatchers.IO)

    private fun request(url: String, json: JSONObject): Request {
        val builder = Request.Builder()
            .url(url)
            .header("Content-Encoding", "gzip")
            .header("X-Install-Id", installId)
            .post(gzip(json.toString()).toRequestBody(JSON))
        accessCode()?.let { builder.header("Authorization", "Bearer $it") }
        return builder.build()
    }

    private fun gzip(text: String): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(text.toByteArray(Charsets.UTF_8)) }
        return out.toByteArray()
    }

    private companion object {
        const val TAG = "FeedbackHttp"
        val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
