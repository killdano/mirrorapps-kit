package com.mirrorapps.kit.sessions

import android.content.Context
import android.content.SharedPreferences
import com.mirrorapps.kit.MirrorAppsKit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

/**
 * What one recorder records, and how it names itself.
 *
 * @property app The session app: `mirrormap`, `mirrormov`, `mirrormsg`.
 * @property source The chunk `source`: `dash`, `mirrormsg`.
 * @property platform The consent `platform`: where sharing was switched on.
 * @property consentVersion Wording version of the host's consent dialog.
 *   Matches `CONSENT_VERSION` on the backend; bump both when the meaning
 *   changes.
 * @property sessionPrefix Start of the per-process session id.
 * @property prefsName SharedPreferences file for the cached answer. A host
 *   that recorded before the kit passes its old file name, so the cached
 *   answer and a pending "off" survive the move.
 * @property client The chunk's `client` object: host name, version,
 *   platform... Called on a background thread for every chunk.
 * @property active True while the host is in the middle of something
 *   (navigation running): quiet windows are kept instead of dropped.
 * @property noun What one session is called in log lines ("trip").
 */
class SessionSpec(
    val app: String,
    val source: String,
    val platform: String,
    val consentVersion: Int,
    val sessionPrefix: String,
    val prefsName: String,
    val client: () -> JSONObject,
    val active: () -> Boolean = { false },
    val noun: String = "session",
)

/**
 * Session recording for one app, per `trip-review/SPEC.md` in
 * the backend repo. Generalised from Dash's trip recorder: the
 * host owns the hooks that produce events (notifications, watch events,
 * whatever its app sees) and calls [add] and [onLog]; the recorder owns the
 * rest:
 *
 * - asks `GET /v1/trips/enabled?app=…` at start, whenever the credential
 *   changes, and every 6 hours, and caches the answer;
 * - while the backend says to record, every `uploadEverySec` turns the
 *   window into chunks, drops idle windows, and uploads, keeping failed
 *   uploads queued for 30 minutes;
 * - consent: [setConsent] for the "Share my …" switch and its delete.
 *
 * Hooks are called on any thread. They only append to memory under [lock];
 * JSON building for the upload, gzip and the network all happen on
 * [scope]. When recording is off, [isRecording] is one volatile read, so
 * hosts check it before building an event.
 */
class SessionRecorder(val spec: SessionSpec) {

    /** What the backend last said, as the "Share my …" row shows it. */
    data class State(
        val record: Boolean = false,
        val role: String? = null,
        val uploadEverySec: Int = SessionChunks.DEFAULT_UPLOAD_EVERY_SEC,
        val checkedAt: Long = 0L,
    ) {
        val isOwner: Boolean get() = record && role == ROLE_OWNER
    }

    sealed class ConsentResult {
        /** Done. [deleted] is how many sessions the backend deleted (delete only). */
        data class Ok(val deleted: Int) : ConsentResult()

        /** 409 consent_outdated: the wording changed; show the dialog again. */
        data object Outdated : ConsentResult()

        /** No credential on this phone. */
        data object NoCode : ConsentResult()

        /** Anything else; [status] is -1 when the backend never answered. */
        data class Failed(val status: Int) : ConsentResult()
    }

    private val tag = "Sessions/" + spec.app

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    @Volatile private var code: String? = null
    @Volatile private var recording = false
    @Volatile private var gzipOk = true
    @Volatile private var pendingOff = false
    @Volatile private var lastCheckAttemptAt = 0L

    /** True while recording. Hosts check this before building an event. */
    val isRecording: Boolean get() = recording

    private class Queued(val piece: SessionChunks.Piece, val t1: Long)

    /** Guards [window] and [queue]. Held only for appends and swaps. */
    private val lock = Any()
    private var window = SessionWindow(System.currentTimeMillis())
    private val queue = ArrayDeque<Queued>()

    /** One flush-and-upload at a time, so chunks go out in seq order. */
    private val flushMutex = Mutex()
    private var seq = 0

    /** SPEC: a random id per app process. */
    private val header = SessionChunks.Header(
        app = spec.app,
        source = spec.source,
        session = spec.sessionPrefix + "-" + System.currentTimeMillis().toString(36) + "-" +
            (Math.random() * 1e9).toLong().toString(36),
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var prefs: SharedPreferences? = null
    private var client: SessionClient? = null
    @Volatile private var started = false

    private val log get() = MirrorAppsKit.log

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    /**
     * Call once, after [MirrorAppsKit.init]. Restores the cached answer,
     * asks the backend again whenever the credential appears or changes, and
     * starts the upload ticker.
     */
    fun start(context: Context) {
        if (started) return
        started = true
        val config = MirrorAppsKit.config
        prefs = context.applicationContext.getSharedPreferences(spec.prefsName, Context.MODE_PRIVATE)
        client = SessionClient(config.httpClient, config.baseUrl)
        val cached = loadState()
        _state.value = cached
        pendingOff = prefs?.getBoolean(K_PENDING_OFF, false) == true
        val pendingNote = if (pendingOff) ", turning off is still pending" else ""
        log.i(tag, "${spec.noun} recording " + describe(cached) + " (cached)" + pendingNote)
        scope.launch {
            config.credential.collect { onCodeChanged(it) }
        }
        scope.launch { tickLoop() }
    }

    private suspend fun onCodeChanged(c: String?) {
        code = c?.takeIf { it.isNotBlank() }
        updateRecording()
        if (code != null) {
            retryPendingOff()
            refreshEnabled()
        }
    }

    private suspend fun tickLoop() {
        while (true) {
            delay(_state.value.uploadEverySec.coerceAtLeast(SessionChunks.MIN_UPLOAD_EVERY_SEC) * 1000L)
            try {
                val now = System.currentTimeMillis()
                if (code != null &&
                    now - _state.value.checkedAt >= RECHECK_MS &&
                    now - lastCheckAttemptAt >= RECHECK_RETRY_MS
                ) {
                    refreshEnabled()
                }
                retryPendingOff()
                flush(null)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                log.w(tag, "${spec.noun} tick failed", e)
            }
        }
    }

    // -------------------------------------------------------------------------
    // Enabled
    // -------------------------------------------------------------------------

    /**
     * Asks `GET /v1/trips/enabled` and applies the answer. Returns false
     * when there was no usable answer; the cached one stays.
     */
    suspend fun refreshEnabled(): Boolean {
        val c = code ?: return false
        val cl = client ?: return false
        lastCheckAttemptAt = System.currentTimeMillis()
        val r = cl.enabled(c, spec.app)
        if (r.status != 200) {
            log.w(tag, "enabled: ${statusText(r)}; keeping cached answer (${describe(_state.value)})")
            return false
        }
        val e = SessionChunks.parseEnabled(r.body)
        if (e == null) {
            log.w(tag, "enabled: unreadable answer; keeping cached answer")
            return false
        }
        applyEnabled(e)
        return true
    }

    private fun applyEnabled(e: SessionChunks.Enabled) {
        // An off the user asked for and the backend hasn't heard yet wins.
        val record = e.record && !pendingOff
        val s = State(
            record = record,
            role = if (record) e.role else null,
            uploadEverySec = e.uploadEverySec,
            checkedAt = System.currentTimeMillis(),
        )
        _state.value = s
        saveState(s)
        updateRecording()
        log.i(tag, "${spec.noun} recording " + describe(s))
    }

    private fun updateRecording() {
        val now = _state.value.record && code != null
        val was = recording
        recording = now
        if (was && !now) {
            synchronized(lock) {
                window = SessionWindow(System.currentTimeMillis())
                queue.clear()
            }
        }
    }

    /** Stops recording and forgets everything not yet uploaded. */
    private fun turnOffLocally() {
        val s = _state.value.copy(record = false, role = null)
        _state.value = s
        saveState(s)
        updateRecording()
        synchronized(lock) {
            window = SessionWindow(System.currentTimeMillis())
            queue.clear()
        }
    }

    // -------------------------------------------------------------------------
    // Consent ("Share my …")
    // -------------------------------------------------------------------------

    /**
     * `POST /v1/trips/consent`. On: `{app, on: true, version, platform}`,
     * then asks `/v1/trips/enabled` again. Off: `{app, on: false}`, with
     * `deleteData: true` to delete everything already shared.
     *
     * Turning off stops recording on the phone at once, before the backend
     * answers; if it can't be reached, the off is retried every tick and no
     * answer from the backend turns recording back on meanwhile.
     *
     * Runs on the recorder's own scope, so leaving the screen doesn't cancel
     * a request halfway.
     */
    suspend fun setConsent(on: Boolean, deleteData: Boolean = false): ConsentResult =
        scope.async { doSetConsent(on, deleteData) }.await()

    private suspend fun doSetConsent(on: Boolean, deleteData: Boolean): ConsentResult {
        val c = code ?: return ConsentResult.NoCode
        val cl = client ?: return ConsentResult.NoCode
        if (on) {
            setPendingOff(false)
        } else {
            setPendingOff(true)
            turnOffLocally()
        }
        val body = if (on) {
            SessionChunks.consentOnBody(spec.app, spec.consentVersion, spec.platform)
        } else {
            SessionChunks.consentOffBody(spec.app, deleteData)
        }
        val r = cl.consent(c, body)
        log.i(tag, "consent on=$on" + (if (deleteData) " deleteData" else "") + ": ${statusText(r)}")
        return when (r.status) {
            200 -> {
                if (!on) setPendingOff(false)
                val deleted = try {
                    JSONObject(r.body).optInt("deleted", 0)
                } catch (e: Exception) {
                    0
                }
                if (!refreshEnabled() && on) {
                    // Consent is saved; the check just didn't get through.
                    // Start now and let the next check correct it.
                    applyEnabled(SessionChunks.Enabled(true, "user", _state.value.uploadEverySec))
                }
                ConsentResult.Ok(deleted)
            }
            409 -> ConsentResult.Outdated
            else -> {
                // A 4xx is a final answer (say, a code the backend doesn't
                // know, which records nothing anyway): don't retry the off.
                if (!on && r.status in 400..499) setPendingOff(false)
                ConsentResult.Failed(r.status)
            }
        }
    }

    private fun setPendingOff(v: Boolean) {
        pendingOff = v
        prefs?.edit()?.putBoolean(K_PENDING_OFF, v)?.apply()
    }

    private suspend fun retryPendingOff() {
        if (!pendingOff) return
        val c = code ?: return
        val cl = client ?: return
        val r = cl.consent(c, SessionChunks.consentOffBody(spec.app, deleteData = false))
        if (r.status == 200 || r.status in 400..499) {
            setPendingOff(false)
            log.i(tag, "consent off, retried: ${statusText(r)}")
        }
    }

    // -------------------------------------------------------------------------
    // Hooks
    // -------------------------------------------------------------------------

    /**
     * Adds an event to the current window. [isNotif] puts it under the
     * notification cap; [worthy] marks the window worth uploading (for
     * MirrorMap, anything trip-like: a navigation notification, a share,
     * a route push). Never throws.
     */
    fun add(event: JSONObject, isNotif: Boolean = false, worthy: Boolean = true) {
        if (!recording) return
        synchronized(lock) { window.addEvent(event, isNotif, worthy) }
    }

    /** One log line, unredacted. Must not log: hosts call this from their logger. */
    fun onLog(t: Long, line: String) {
        if (!recording) return
        synchronized(lock) { window.addLog(t, line) }
    }

    /**
     * The host knows the session ended (`"arrived"`, `"companion_ended"`).
     * Uploads right away instead of waiting for the next tick.
     */
    fun end(reason: String) {
        if (!recording) return
        scope.launch {
            try {
                flush(reason)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                log.w(tag, "${spec.noun} end flush failed", e)
            }
        }
    }

    // -------------------------------------------------------------------------
    // Upload
    // -------------------------------------------------------------------------

    /** Closes the current window into chunks (unless idle) and uploads the queue. */
    private suspend fun flush(end: String?) {
        flushMutex.withLock { flushLocked(end) }
    }

    /** MUST hold [flushMutex]. */
    private suspend fun flushLocked(end: String?) {
        val now = System.currentTimeMillis()
        val closed = synchronized(lock) {
            val w = window
            window = SessionWindow(now)
            w
        }
        if (end != null) closed.end = end
        if (recording && closed.worthUploading(active = activeSafely())) {
            val pieces = SessionChunks.buildPieces(closed, header, seq, now, clientInfo())
            seq += pieces.size
            synchronized(lock) { pieces.forEach { queue.addLast(Queued(it, now)) } }
        }
        synchronized(lock) { prune(now) }
        val c = code
        if (recording && c != null) upload(c)
    }

    private fun activeSafely(): Boolean = try {
        spec.active()
    } catch (t: Throwable) {
        false
    }

    private fun clientInfo(): JSONObject {
        val j = try {
            spec.client()
        } catch (t: Throwable) {
            JSONObject()
        }
        return j.put("role", _state.value.role ?: JSONObject.NULL)
    }

    /** MUST hold [lock]. */
    private fun prune(now: Long) {
        while (queue.isNotEmpty() && now - queue.first().t1 > MAX_QUEUE_AGE_MS) queue.removeFirst()
        var bytes = queue.sumOf { it.piece.gzip.size.toLong() }
        while (queue.size > 1 && bytes > MAX_QUEUE_BYTES) bytes -= queue.removeFirst().piece.gzip.size
    }

    private suspend fun upload(c: String) {
        val cl = client ?: return
        while (true) {
            val q = synchronized(lock) { queue.firstOrNull() } ?: return
            val useGzip = gzipOk
            val r = cl.chunk(c, q.piece, useGzip)
            when {
                r.status in 200..299 -> {
                    remove(q)
                    log.d(
                        tag,
                        "chunk seq=${q.piece.seq} sent, ${q.piece.rawBytes}B" +
                            (if (useGzip) " (${q.piece.gzip.size}B gzip)" else "") +
                            (q.piece.end?.let { ", end=$it" } ?: ""),
                    )
                }
                useGzip && (r.status == 400 || r.status == 415) -> {
                    // Unexpected, since SPEC allows gzip: try the same chunk
                    // plain, and stay plain for this process.
                    gzipOk = false
                    log.w(tag, "chunk: gzip refused (${statusText(r)}); sending uncompressed from now on")
                }
                r.status == 403 -> {
                    log.i(tag, "chunk: ${statusText(r)}; recording off, queue cleared")
                    turnOffLocally()
                    return
                }
                r.status == 400 || r.status == 413 -> {
                    remove(q)
                    log.w(tag, "chunk seq=${q.piece.seq} dropped: ${statusText(r)}")
                }
                else -> {
                    val left = synchronized(lock) { queue.size }
                    log.d(tag, "chunk seq=${q.piece.seq} not sent (${statusText(r)}); $left queued for the next try")
                    return
                }
            }
        }
    }

    private fun remove(q: Queued) {
        synchronized(lock) { queue.remove(q) }
    }

    // -------------------------------------------------------------------------
    // Persistence and text
    // -------------------------------------------------------------------------

    private fun loadState(): State {
        val p = prefs ?: return State()
        return State(
            record = p.getBoolean(K_RECORD, false),
            role = p.getString(K_ROLE, null),
            uploadEverySec = p.getInt(K_EVERY, SessionChunks.DEFAULT_UPLOAD_EVERY_SEC),
            checkedAt = p.getLong(K_CHECKED_AT, 0L),
        )
    }

    private fun saveState(s: State) {
        prefs?.edit()
            ?.putBoolean(K_RECORD, s.record)
            ?.putString(K_ROLE, s.role)
            ?.putInt(K_EVERY, s.uploadEverySec)
            ?.putLong(K_CHECKED_AT, s.checkedAt)
            ?.apply()
    }

    private fun describe(s: State): String =
        if (s.record) "on (${s.role ?: "?"}), upload every ${s.uploadEverySec}s" else "off"

    /** The status and the start of the body; never the request, which carries the code. */
    private fun statusText(r: SessionClient.Reply): String =
        if (r.status < 0) "no answer (${r.body.take(80)})" else "HTTP ${r.status} ${r.body.take(80)}"

    companion object {
        const val ROLE_OWNER = "owner"

        // Pref keys, unchanged from Dash's trip recorder so its cached
        // answer carries over.
        private const val K_RECORD = "record"
        private const val K_ROLE = "role"
        private const val K_EVERY = "upload_every_sec"
        private const val K_CHECKED_AT = "checked_at"
        private const val K_PENDING_OFF = "pending_off"

        /** SPEC: ask `/v1/trips/enabled` at launch and every 6 hours. */
        private const val RECHECK_MS = 6L * 60 * 60 * 1000

        /** After a failed check, wait this long before asking again. */
        private const val RECHECK_RETRY_MS = 15L * 60 * 1000

        /** Failed uploads stay queued this long, oldest dropped first. */
        private const val MAX_QUEUE_AGE_MS = 30L * 60 * 1000

        /** And never more than this much gzipped data, whatever the age. */
        private const val MAX_QUEUE_BYTES = 16L * 1024 * 1024
    }
}
