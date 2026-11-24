package com.mirrorapps.kit.sessions

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import org.json.JSONArray
import org.json.JSONObject

/**
 * The pure half of session recording: the chunk JSON, splitting, extras
 * capture, the enabled answer and the consent bodies. No Android calls, so
 * all of it runs in unit tests. [SessionRecorder] is the half that touches
 * the phone.
 *
 * The contract is `trip-review/SPEC.md` in the backend repo.
 * Every field name here comes from that file; the backend and the reviewer
 * read them by name.
 */
object SessionChunks {

    /** Per-window caps, so a runaway window can't eat the phone's memory. */
    const val MAX_LOG_LINES = 5_000
    const val MAX_NOTIF_EVENTS = 3_000
    const val MAX_OTHER_EVENTS = 3_000
    const val MAX_FIXES = 5_000

    /**
     * SPEC: a window with a fix faster than this (m/s) is worth uploading,
     * whatever else happened in it.
     */
    const val MOVING_SPEED_MPS = 2.0

    /** SPEC: max 4 MB per request. Split below it, with margin. */
    const val MAX_RAW_BYTES = 3_500_000

    /**
     * The backend stores each chunk deflated in one Firestore document and
     * answers 413 when the base64 of it passes 900 KB (about 675 KB
     * deflated). Gzip is the same deflate plus 18 bytes, so split well
     * before that.
     */
    const val MAX_GZIP_BYTES = 600_000

    /** Upload cadence when the backend doesn't say. SPEC default. */
    const val DEFAULT_UPLOAD_EVERY_SEC = 120

    /** Never upload more often than this, whatever the backend says. */
    const val MIN_UPLOAD_EVERY_SEC = 30

    // -------------------------------------------------------------------------
    // Enabled answer
    // -------------------------------------------------------------------------

    /** `GET /v1/trips/enabled?app=…`, parsed. */
    data class Enabled(val record: Boolean, val role: String?, val uploadEverySec: Int)

    /**
     * Parses a 200 body of `/v1/trips/enabled`. Null when it isn't JSON, so
     * the caller keeps its cached answer instead of trusting garbage.
     */
    fun parseEnabled(body: String): Enabled? {
        val j = try {
            JSONObject(body)
        } catch (e: Exception) {
            return null
        }
        val record = j.optBoolean("record", false)
        val role = if (j.has("role") && !j.isNull("role")) j.optString("role") else null
        val every = j.optInt("uploadEverySec", DEFAULT_UPLOAD_EVERY_SEC)
            .let { if (it <= 0) DEFAULT_UPLOAD_EVERY_SEC else it }
            .coerceAtLeast(MIN_UPLOAD_EVERY_SEC)
        return Enabled(record = record, role = role, uploadEverySec = every)
    }

    // -------------------------------------------------------------------------
    // Consent bodies
    // -------------------------------------------------------------------------

    /** Body of `POST /v1/trips/consent` when the user accepts the dialog. */
    fun consentOnBody(app: String, version: Int, platform: String): JSONObject = JSONObject()
        .put("app", app)
        .put("on", true)
        .put("version", version)
        .put("platform", platform)

    /** Body when the user turns sharing off, or deletes everything they shared. */
    fun consentOffBody(app: String, deleteData: Boolean): JSONObject {
        val j = JSONObject().put("app", app).put("on", false)
        if (deleteData) j.put("deleteData", true)
        return j
    }

    // -------------------------------------------------------------------------
    // Event pieces
    // -------------------------------------------------------------------------

    /**
     * Notification extras as SPEC wants them: every extra whose value is a
     * CharSequence, String, number or boolean, keys verbatim, CharSequence
     * through toString(). Bitmaps, Parcelables, Bundles, arrays and nulls are
     * skipped.
     *
     * Verbatim means content: MirrorMsg doesn't use this for message
     * notifications, it sends extras keys and shapes instead.
     */
    fun captureExtras(entries: Iterable<Pair<String, Any?>>): JSONObject {
        val out = JSONObject()
        for ((key, value) in entries) {
            val v = extraValue(value) ?: continue
            out.put(key, v)
        }
        return out
    }

    /** One extra's JSON value, or null to skip it. */
    fun extraValue(v: Any?): Any? = when (v) {
        null -> null
        is CharSequence -> v.toString()
        is Boolean -> v
        is Int -> v
        is Long -> v
        is Short -> v.toInt()
        is Byte -> v.toInt()
        // Through the decimal string, so 0.1f stays 0.1 rather than
        // 0.10000000149011612. JSON has no NaN or Infinity: those go as text.
        is Float -> if (v.isFinite()) v.toString().toDouble() else v.toString()
        is Double -> if (v.isFinite()) v else v.toString()
        is Number -> v.toString()
        else -> null
    }

    /**
     * Any detail value as JSON: lists, enums, nulls, numbers. Types the kit
     * doesn't know go through toString(); hosts convert their own types
     * (a lat/lng pair, say) before calling.
     */
    fun jsonValue(v: Any?): Any = when (v) {
        null -> JSONObject.NULL
        is Enum<*> -> v.name
        is Collection<*> -> JSONArray().also { a -> v.forEach { a.put(jsonValue(it)) } }
        is CharSequence -> v.toString()
        is Boolean, is Int, is Long -> v
        is Short -> v.toInt()
        is Byte -> v.toInt()
        is Double -> if (v.isFinite()) v else v.toString()
        is Float -> if (v.isFinite()) v.toString().toDouble() else v.toString()
        is JSONObject, is JSONArray -> v
        else -> v.toString()
    }

    /** An event: `{"t": ..., "k": ..., <details>}`. */
    fun event(t: Long, kind: String, details: Map<String, Any?>): JSONObject {
        val e = JSONObject().put("t", t).put("k", kind)
        for ((key, value) in details) e.put(key, jsonValue(value))
        return e
    }

    // -------------------------------------------------------------------------
    // Chunks
    // -------------------------------------------------------------------------

    /** One log line as SPEC wants it: `[t, line]`. */
    data class LogLine(val t: Long, val line: String)

    /**
     * One GPS fix as SPEC wants it: `[t, lat, lng, acc, speed, heading,
     * sent]`. Unknown accuracy, speed or heading is -1. [sent] is true when
     * this fix was the one forwarded to the watch.
     */
    data class Fix(
        val t: Long,
        val lat: Double,
        val lng: Double,
        val acc: Double = -1.0,
        val speed: Double = -1.0,
        val heading: Double = -1.0,
        val sent: Boolean = false,
    ) {
        /** Faster than [MOVING_SPEED_MPS]: SPEC's test for a window worth uploading. */
        val moving: Boolean get() = speed.isFinite() && speed > MOVING_SPEED_MPS

        /** `[t, lat, lng, acc, speed, heading, sent]`: degrees to 6 decimals. */
        fun json(): JSONArray = JSONArray()
            .put(t)
            .put(round(lat, 1e6))
            .put(round(lng, 1e6))
            .put(orUnknown(acc, 1e1))
            .put(orUnknown(speed, 1e2))
            .put(orUnknown(heading, 1e1))
            .put(if (sent) 1 else 0)

        private companion object {
            fun round(v: Double, scale: Double): Double = Math.round(v * scale) / scale

            /** Negative or not finite means unknown: -1, as SPEC says. */
            fun orUnknown(v: Double, scale: Double): Any =
                if (v.isFinite() && v >= 0) round(v, scale) else -1
        }
    }

    /** A chunk ready to send: gzipped JSON, with its uncompressed size. */
    class Piece(val seq: Int, val gzip: ByteArray, val rawBytes: Int, val end: String?) {
        /** The uncompressed JSON, for a backend that turns out not to take gzip. */
        fun plain(): ByteArray = GZIPInputStream(ByteArrayInputStream(gzip)).use { it.readBytes() }
    }

    /** Who is sending: the recorder's fixed fields. */
    data class Header(
        /** The session app: `mirrormap`, `mirrormov`, `mirrormsg`. */
        val app: String,
        /** `dash`, `pkjs`, `mirrormsg`. */
        val source: String,
        /** A random id per app process. */
        val session: String,
    )

    /**
     * The chunk body, per SPEC. `app` is the session app's id (the backend
     * refuses a chunk whose `app` isn't one); the host app's name and
     * version go in `client`. `fixes` is only there when the window has
     * some: recorders with their own GPS (Dash's MirrorMov recorder) add
     * them, the others (Dash's MirrorMap recorder) never do.
     */
    fun chunkJson(
        header: Header,
        seq: Int,
        t0: Long,
        t1: Long,
        client: JSONObject,
        end: String?,
        events: List<JSONObject>,
        logs: List<LogLine>,
        dropped: JSONObject? = null,
        fixes: List<Fix> = emptyList(),
    ): JSONObject {
        val ev = JSONArray()
        for (e in events) ev.put(e)
        val lg = JSONArray()
        for (l in logs) lg.put(JSONArray().put(l.t).put(l.line))
        val j = JSONObject()
            .put("v", 1)
            .put("app", header.app)
            .put("source", header.source)
            .put("session", header.session)
            .put("seq", seq)
            .put("t0", t0)
            .put("t1", t1)
            .put("client", client)
            .put("end", end ?: JSONObject.NULL)
        if (fixes.isNotEmpty()) {
            val fx = JSONArray()
            for (f in fixes) fx.put(f.json())
            j.put("fixes", fx)
        }
        j.put("events", ev).put("logs", lg)
        if (dropped != null) j.put("dropped", dropped)
        return j
    }

    fun gzip(bytes: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(bytes.size / 4 + 64)
        GZIPOutputStream(out).use { it.write(bytes) }
        return out.toByteArray()
    }

    /**
     * Turns a closed window into one or more chunks, numbered from
     * [firstSeq]. A window too big for one request is split in time order;
     * `end` goes only on the last piece, so the backend sees the session end
     * after everything in it.
     */
    fun buildPieces(
        window: SessionWindow,
        header: Header,
        firstSeq: Int,
        t1: Long,
        client: JSONObject,
        maxRawBytes: Int = MAX_RAW_BYTES,
        maxGzipBytes: Int = MAX_GZIP_BYTES,
    ): List<Piece> {
        val dropped = window.droppedJson()
        val whole = Part(window.events.toList(), window.logs.toList(), window.fixes.toList())
        val parts = partition(whole) { p ->
            val raw = chunkJson(header, firstSeq, window.t0, t1, client, window.end, p.events, p.logs, dropped, p.fixes)
                .toString().toByteArray(Charsets.UTF_8)
            raw.size <= maxRawBytes && gzip(raw).size <= maxGzipBytes
        }
        return parts.mapIndexed { i, p ->
            val last = i == parts.size - 1
            val end = if (last) window.end else null
            val raw = chunkJson(
                header, firstSeq + i, window.t0, t1, client, end, p.events, p.logs,
                if (i == 0) dropped else null, p.fixes,
            ).toString().toByteArray(Charsets.UTF_8)
            Piece(seq = firstSeq + i, gzip = gzip(raw), rawBytes = raw.size, end = end)
        }
    }

    /** What one piece carries. */
    private class Part(val events: List<JSONObject>, val logs: List<LogLine>, val fixes: List<Fix>) {
        val size: Int get() = events.size + logs.size + fixes.size
    }

    /**
     * Splits events, logs and fixes until each part [fits], by time:
     * everything at or before the median time goes first. A single item that
     * doesn't fit goes as it is, and the backend's 413 drops it.
     */
    private fun partition(p: Part, fits: (Part) -> Boolean): List<Part> {
        if (p.size <= 1 || fits(p)) return listOf(p)
        val times = (p.events.map { it.optLong("t") } + p.logs.map { it.t } + p.fixes.map { it.t }).sorted()
        val mid = times[(times.size - 1) / 2]
        var a = Part(
            p.events.filter { it.optLong("t") <= mid },
            p.logs.filter { it.t <= mid },
            p.fixes.filter { it.t <= mid },
        )
        var b = Part(
            p.events.filter { it.optLong("t") > mid },
            p.logs.filter { it.t > mid },
            p.fixes.filter { it.t > mid },
        )
        if (b.size == 0) {
            // Everything shares one timestamp: halve by position instead.
            a = Part(p.events.take(p.events.size / 2), p.logs.take(p.logs.size / 2), p.fixes.take(p.fixes.size / 2))
            b = Part(p.events.drop(p.events.size / 2), p.logs.drop(p.logs.size / 2), p.fixes.drop(p.fixes.size / 2))
        }
        return partition(a, fits) + partition(b, fits)
    }
}

/**
 * What happened in one upload window (~2 minutes). Not thread-safe:
 * [SessionRecorder] guards it with its lock.
 */
class SessionWindow(val t0: Long) {
    val events = ArrayList<JSONObject>()
    val logs = ArrayList<SessionChunks.LogLine>()
    val fixes = ArrayList<SessionChunks.Fix>()
    private var notifCount = 0
    var droppedLogs = 0
        private set
    var droppedFixes = 0
        private set
    var droppedNotifs = 0
        private set
    var droppedEvents = 0
        private set

    /** Something worth reviewing happened: the host decides what (see [SessionRecorder.add]). */
    var worthy = false
        private set

    /** Set once the host knows the session ended (`"arrived"`, `"companion_ended"`, ...). */
    var end: String? = null

    /** Adds an event unless its cap is reached. Notifications have their own cap. */
    fun addEvent(e: JSONObject, isNotif: Boolean, worthy: Boolean) {
        if (isNotif) {
            if (notifCount >= SessionChunks.MAX_NOTIF_EVENTS) {
                droppedNotifs++
                return
            }
            notifCount++
        } else if (events.size - notifCount >= SessionChunks.MAX_OTHER_EVENTS) {
            droppedEvents++
            return
        }
        events.add(e)
        if (worthy) this.worthy = true
    }

    fun addLog(t: Long, line: String) {
        if (logs.size >= SessionChunks.MAX_LOG_LINES) {
            droppedLogs++
            return
        }
        logs.add(SessionChunks.LogLine(t, line))
    }

    /**
     * Adds a GPS fix unless the cap is reached. A moving fix (SPEC: over
     * 2 m/s) makes the window worthy. A fix without a real position is
     * ignored: JSON has no NaN.
     */
    fun addFix(f: SessionChunks.Fix) {
        if (!f.lat.isFinite() || !f.lng.isFinite()) return
        if (fixes.size >= SessionChunks.MAX_FIXES) {
            droppedFixes++
            return
        }
        fixes.add(f)
        if (f.moving) worthy = true
    }

    /**
     * Upload this window? Only if something worthy happened in it (a worthy
     * event, a moving fix), the session ended, or the host says it's active
     * (navigation or an activity running); idle windows are dropped on the
     * phone. A window with nothing in it at all is never sent.
     */
    fun worthUploading(active: Boolean): Boolean {
        if (end != null) return true
        if (events.isEmpty() && logs.isEmpty() && fixes.isEmpty()) return false
        return worthy || active
    }

    /** What the caps threw away, or null when nothing was. */
    fun droppedJson(): JSONObject? {
        if (droppedLogs == 0 && droppedNotifs == 0 && droppedEvents == 0 && droppedFixes == 0) return null
        val j = JSONObject()
            .put("logs", droppedLogs)
            .put("notifs", droppedNotifs)
            .put("events", droppedEvents)
        if (droppedFixes > 0) j.put("fixes", droppedFixes)
        return j
    }
}
