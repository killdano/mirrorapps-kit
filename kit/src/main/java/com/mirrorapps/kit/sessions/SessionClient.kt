package com.mirrorapps.kit.sessions

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * The three session endpoints (`trip-review/SPEC.md` in
 * the backend repo). Every call is Bearer the host's credential.
 * Callers are always on a background coroutine.
 */
class SessionClient(
    sharedClient: OkHttpClient,
    private val base: String,
) {
    /** A chunk can be a few hundred KB on a slow connection; give it time. */
    private val client: OkHttpClient = sharedClient.newBuilder()
        .callTimeout(60, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    /** HTTP status and body; status -1 when the request never got an answer. */
    data class Reply(val status: Int, val body: String)

    suspend fun enabled(code: String, app: String): Reply = call(
        Request.Builder()
            .url("$base/v1/trips/enabled".toHttpUrl().newBuilder().addQueryParameter("app", app).build())
            .get()
            .header("Authorization", "Bearer $code")
            .build(),
    )

    suspend fun consent(code: String, body: JSONObject): Reply = call(
        Request.Builder()
            .url("$base/v1/trips/consent")
            .post(body.toString().toRequestBody(JSON))
            .header("Authorization", "Bearer $code")
            .build(),
    )

    /** Uploads one chunk, gzipped (SPEC allows it) unless [gzip] is false. */
    suspend fun chunk(code: String, piece: SessionChunks.Piece, gzip: Boolean): Reply {
        val b = Request.Builder()
            .url("$base/v1/trips/chunk")
            .header("Authorization", "Bearer $code")
        if (gzip) {
            b.header("Content-Encoding", "gzip").post(piece.gzip.toRequestBody(JSON))
        } else {
            b.post(piece.plain().toRequestBody(JSON))
        }
        return call(b.build())
    }

    private suspend fun call(request: Request): Reply = withContext(Dispatchers.IO) {
        try {
            client.newCall(request).execute().use { r ->
                Reply(r.code, r.body?.string().orEmpty())
            }
        } catch (e: IOException) {
            Reply(-1, e.javaClass.simpleName + ": " + (e.message ?: ""))
        }
    }

    private companion object {
        val JSON = "application/json".toMediaType()
    }
}
