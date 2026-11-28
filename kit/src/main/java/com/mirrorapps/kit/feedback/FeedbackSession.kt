package com.mirrorapps.kit.feedback

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import com.mirrorapps.kit.LogsMode
import com.mirrorapps.kit.MirrorAppsKit
import com.mirrorapps.kit.feedback.media.HttpMediaClient
import com.mirrorapps.kit.feedback.media.MediaClient
import com.mirrorapps.kit.feedback.media.MediaKind
import com.mirrorapps.kit.feedback.media.MediaReader
import com.mirrorapps.kit.feedback.media.MediaSource
import com.mirrorapps.kit.feedback.media.MediaUpload
import com.mirrorapps.kit.feedback.media.Thumbnails
import com.mirrorapps.kit.feedback.media.VoiceRecorder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * The one Feedback conversation, shared by every screen that shows the tab
 * (a home tab, a full-screen activity from a paywall), so switching screens
 * never loses a reply in flight.
 *
 * Memory only. The transcript, the session id and the last bundle are gone
 * on New chat, after [IDLE_RESET_MS] without activity, or
 * when the process dies. Media leaves the phone only when the user attaches
 * it, and goes to the backend the moment it's attached (so a voice message's
 * transcript can come back before sending).
 */
object FeedbackSession {
    private const val TAG = "FeedbackSession"

    /** A conversation left this long starts over the next time the tab opens. */
    private const val IDLE_RESET_MS = 30L * 60L * 1000L

    /** SPEC: at most this many media ids per message. */
    const val MAX_MEDIA = 10

    /** How long to wait for a voice message's transcript. */
    private const val TRANSCRIPT_WAIT_MS = 3L * 60L * 1000L
    private const val TRANSCRIPT_POLL_MS = 3_000L

    enum class Role { USER, AGENT, STATUS }

    data class Item(
        val id: Int,
        val role: Role,
        val text: String,
        /** Agent offered to show what was sent: render a View chip. */
        val offersPreview: Boolean = false,
        /**
         * What the user attached to this message without a preview, as
         * labels ("Voice 0:12"). Pictures and videos show as [thumbs].
         */
        val attachments: List<String> = emptyList(),
        /** Previews of the pictures and videos the user attached, in order. */
        val thumbs: List<Bitmap> = emptyList(),
    )

    enum class AttachState { UPLOADING, TRANSCRIBING, READY, FAILED }

    /** Media in the composer, waiting to go with the next message. */
    data class Attachment(
        val id: Int,
        val kind: MediaKind,
        val label: String,
        val state: AttachState,
        val progress: Float = 0f,
        val mediaId: String? = null,
        val error: String? = null,
        /** A small preview of a picture or video, once it's been read. */
        val thumb: Bitmap? = null,
    ) {
        /** Uploaded: it can go with a message (a transcript may still be coming). */
        val sendable: Boolean get() = mediaId != null && (state == AttachState.READY || state == AttachState.TRANSCRIBING)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _items = MutableStateFlow<List<Item>>(emptyList())
    val items: StateFlow<List<Item>> = _items

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy

    /** The agent's reason, while the full-logs dialog should be showing. */
    private val _consentReason = MutableStateFlow<String?>(null)
    val consentReason: StateFlow<String?> = _consentReason

    /** Exactly what was last sent, for the View chip. */
    private val _lastBundle = MutableStateFlow<JSONObject?>(null)
    val lastBundle: StateFlow<JSONObject?> = _lastBundle

    /** The text box. Here rather than in the screen so a transcript can fill it. */
    private val _draft = MutableStateFlow("")
    val draft: StateFlow<String> = _draft

    private val _attachments = MutableStateFlow<List<Attachment>>(emptyList())
    val attachments: StateFlow<List<Attachment>> = _attachments

    /** When the voice message being recorded started, or null. */
    private val _recordingSince = MutableStateFlow<Long?>(null)
    val recordingSince: StateFlow<Long?> = _recordingSince

    /**
     * The host's "Logs in bug reports" setting, for the choice at the top of
     * the chat. Read on first use and again whenever the chat comes on
     * screen, because the host may change it elsewhere.
     */
    private val _logsMode = MutableStateFlow(LogsMode.REDACTED)
    private var logsModeRead = false
    val logsMode: StateFlow<LogsMode> get() {
        if (!logsModeRead) {
            logsModeRead = true
            _logsMode.value = hostLogsMode()
        }
        return _logsMode
    }

    /**
     * The hub app id this chat talks as: [com.mirrorapps.kit.FeedbackOptions.app],
     * else the host's first app. Read after init.
     */
    private val _app = MutableStateFlow("")
    val app: StateFlow<String> get() {
        if (_app.value.isEmpty()) _app.value = initialApp()
        return _app
    }

    private var sessionId: String? = null
    private var nextId = 0
    private var lastActivityMs = 0L
    private val uploads = HashMap<Int, Job>()
    private var voice: VoiceRecorder? = null

    /** True when replies come from the host's stand-in. */
    val usingStandIn: Boolean
        get() = MirrorAppsKit.config.feedback.standIn() != null

    private var http: FeedbackTransport? = null
    private var httpMedia: MediaClient? = null

    private fun transport(): FeedbackTransport {
        MirrorAppsKit.config.feedback.standIn()?.let { return it }
        val config = MirrorAppsKit.config
        return http ?: HttpFeedbackTransport(
            sharedClient = config.httpClient,
            messageUrl = "${config.baseUrl}/v1/feedback/message",
            consentUrl = "${config.baseUrl}/v1/feedback/consent",
            installId = MirrorAppsKit.installId(),
            accessCode = { config.credentialNow() },
        ).also { http = it }
    }

    private fun media(): MediaClient {
        MirrorAppsKit.config.feedback.standIn()?.let { return it }
        val config = MirrorAppsKit.config
        return httpMedia ?: HttpMediaClient(
            sharedClient = config.httpClient,
            base = config.baseUrl,
            credential = { config.credentialNow() },
        ).also { httpMedia = it }
    }

    private val log get() = MirrorAppsKit.log

    // -------------------------------------------------------------------------
    // Chat
    // -------------------------------------------------------------------------

    /** Call when the chat comes on screen. */
    fun onShown() {
        _logsMode.value = hostLogsMode()
        if (!_busy.value && _items.value.isNotEmpty() &&
            System.currentTimeMillis() - lastActivityMs > IDLE_RESET_MS
        ) {
            reset()
        }
    }

    /** New chat. Drops the transcript, the session and the bundle; the composer stays. */
    fun reset() {
        if (_busy.value) return
        sessionId = null
        _items.value = emptyList()
        _consentReason.value = null
        _lastBundle.value = null
        log.d(TAG, "reset")
    }

    private fun initialApp(): String {
        val config = MirrorAppsKit.config
        return config.feedback.app?.takeIf { it.isNotBlank() } ?: config.apps.first().id
    }

    /**
     * The user picked Full, Redacted or None at the top of the chat. Saved
     * through the host; a chat's first message sends its logs this way.
     */
    fun chooseLogs(mode: LogsMode) {
        runCatching { MirrorAppsKit.config.feedback.setLogsMode(mode) }
        _logsMode.value = hostLogsMode()
        log.i(TAG, "logs: ${_logsMode.value.wire} (chosen in chat)")
    }

    fun setDraft(text: String) {
        _draft.value = text
    }

    /** Ready to send: some text or uploaded media, nothing still uploading, not recording. */
    fun canSend(): Boolean {
        if (_busy.value || _recordingSince.value != null) return false
        val atts = _attachments.value
        if (atts.any { it.state == AttachState.UPLOADING }) return false
        return _draft.value.isNotBlank() || atts.any { it.sendable }
    }

    /** Sends the text box and the uploaded attachments. */
    fun send(context: Context) {
        if (!canSend()) return
        val ctx = context.applicationContext
        val text = _draft.value.trim()
        val sending = _attachments.value.filter { it.sendable }.take(MAX_MEDIA)
        val mediaIds = sending.mapNotNull { it.mediaId }
        _draft.value = ""
        // Sent ones leave the composer; failed ones go too (nothing to retry).
        _attachments.value = _attachments.value.filter { a ->
            a.state != AttachState.FAILED && sending.none { it.id == a.id }
        }
        _busy.value = true
        lastActivityMs = System.currentTimeMillis()
        append(
            Item(
                nextId++,
                Role.USER,
                text,
                attachments = sending.filter { it.thumb == null }.map { it.label },
                thumbs = sending.mapNotNull { it.thumb },
            ),
        )
        val about = app.value
        scope.launch {
            val bundle = if (sessionId == null) {
                DiagnosticBundle.build(ctx, about, hostLogsMode()).also { _lastBundle.value = it }
            } else null
            log.i(TAG, "send: app=$about first=${bundle != null} media=${mediaIds.size} standIn=$usingStandIn")
            collectReply(transport().send(FeedbackMessage(about, sessionId, text, bundle, mediaIds)), ctx)
        }
    }

    /**
     * The user's answer to the full-logs dialog. Allow is standing
     * permission: it moves "Logs in bug reports" to Full, so later reports
     * don't ask again.
     */
    fun answerConsent(context: Context, granted: Boolean) {
        _consentReason.value = null
        val sid = sessionId ?: return
        val ctx = context.applicationContext
        if (granted) {
            runCatching { MirrorAppsKit.config.feedback.setLogsMode(LogsMode.FULL) }
            _logsMode.value = hostLogsMode()
        }
        log.i(TAG, "consent: granted=$granted")
        sendConsent(ctx, sid, granted)
    }

    private fun sendConsent(ctx: Context, sid: String, granted: Boolean) {
        _busy.value = true
        lastActivityMs = System.currentTimeMillis()
        val about = app.value
        scope.launch {
            val bundle = if (granted) {
                DiagnosticBundle.build(ctx, about, LogsMode.FULL).also { _lastBundle.value = it }
            } else null
            collectReply(transport().consent(about, sid, granted, bundle), ctx)
        }
    }

    private fun hostLogsMode(): LogsMode =
        runCatching { MirrorAppsKit.config.feedback.logsMode() }.getOrDefault(LogsMode.REDACTED)

    private suspend fun collectReply(reply: Flow<FeedbackEvent>, ctx: Context) {
        val agentId = nextId++
        append(Item(agentId, Role.AGENT, ""))
        var pendingConsent: String? = null
        try {
            reply.collect { event ->
                lastActivityMs = System.currentTimeMillis()
                when (event) {
                    is FeedbackEvent.Session -> sessionId = event.sessionId
                    is FeedbackEvent.Delta -> update(agentId) { it.copy(text = it.text + event.text) }
                    is FeedbackEvent.OfferPreview -> update(agentId) { it.copy(offersPreview = true) }
                    is FeedbackEvent.ConsentRequest -> pendingConsent = event.reason
                    is FeedbackEvent.Outcome -> when (event.kind) {
                        "filed" -> status("Sent as a bug report")
                        "resolved" -> status("Marked as solved")
                        else -> Unit
                    }
                    is FeedbackEvent.Error -> status(event.message)
                    is FeedbackEvent.Done -> Unit
                }
            }
        } finally {
            _items.value = _items.value.filterNot { it.id == agentId && it.text.isEmpty() && !it.offersPreview }
            _busy.value = false
        }
        val reason = pendingConsent ?: return
        val sid = sessionId ?: return
        // Already on Full: the user said yes once, don't ask again.
        if (hostLogsMode() == LogsMode.FULL) {
            log.i(TAG, "consent: auto (logs already Full)")
            sendConsent(ctx, sid, granted = true)
        } else {
            _consentReason.value = reason
        }
    }

    // -------------------------------------------------------------------------
    // Media
    // -------------------------------------------------------------------------

    /** A picture or video from the picker: read it and upload it now. */
    fun attach(context: Context, uri: Uri, kind: MediaKind) {
        if (_attachments.value.size >= MAX_MEDIA) {
            status("That's the most attachments one message can carry.")
            return
        }
        val ctx = context.applicationContext
        val id = nextId++
        add(Attachment(id, kind, labelFor(kind, 0L), AttachState.UPLOADING))
        uploads[id] = scope.launch {
            // The preview first: it's quick, and the picker's permission to
            // read the file may not outlast the upload.
            val thumb = withContext(Dispatchers.IO) { Thumbnails.load(ctx, uri, kind) }
            if (thumb != null) change(id) { it.copy(thumb = thumb) }
            val source = withContext(Dispatchers.IO) {
                runCatching { MediaReader.read(ctx, uri, kind) }.getOrNull()
            }
            if (source == null) {
                change(id) { it.copy(state = AttachState.FAILED, error = "Couldn't read that file.") }
                return@launch
            }
            change(id) { it.copy(label = labelFor(kind, source.durationMs)) }
            upload(id, source)
        }
    }

    /** Starts a voice message. The caller has the RECORD_AUDIO permission. */
    fun startVoice(context: Context): Boolean {
        if (_recordingSince.value != null) return true
        if (_attachments.value.size >= MAX_MEDIA) {
            status("That's the most attachments one message can carry.")
            return false
        }
        val r = voice ?: VoiceRecorder(context.applicationContext).also { voice = it }
        if (!r.start()) {
            status("Couldn't use the microphone.")
            return false
        }
        _recordingSince.value = r.startedAt
        return true
    }

    /** Stops the voice message and uploads it; its transcript fills the text box. */
    fun stopVoice() {
        val r = voice ?: return
        _recordingSince.value = null
        val source = r.stop() ?: return
        val id = nextId++
        add(Attachment(id, MediaKind.AUDIO, labelFor(MediaKind.AUDIO, source.durationMs), AttachState.UPLOADING))
        uploads[id] = scope.launch { upload(id, source) }
    }

    fun cancelVoice() {
        voice?.cancel()
        _recordingSince.value = null
    }

    /** Takes an attachment out of the composer, stopping its upload. */
    fun removeAttachment(id: Int) {
        uploads.remove(id)?.cancel()
        _attachments.value = _attachments.value.filterNot { it.id == id }
    }

    private suspend fun upload(id: Int, source: MediaSource) {
        val result = media().upload(source, app.value) { p ->
            scope.launch { change(id) { it.copy(progress = p) } }
        }
        when (result) {
            is MediaUpload.Failed -> change(id) { it.copy(state = AttachState.FAILED, error = result.message) }
            is MediaUpload.Done -> {
                val transcribing = result.status == "transcribing"
                change(id) {
                    it.copy(
                        state = if (transcribing && source.kind == MediaKind.AUDIO) AttachState.TRANSCRIBING else AttachState.READY,
                        mediaId = result.mediaId,
                        progress = 1f,
                    )
                }
                if (transcribing && source.kind == MediaKind.AUDIO) awaitTranscript(id, result.mediaId)
            }
        }
        uploads.remove(id)
    }

    /** Polls until the voice message is transcribed, then puts the words in the text box. */
    private suspend fun awaitTranscript(id: Int, mediaId: String) {
        val until = System.currentTimeMillis() + TRANSCRIPT_WAIT_MS
        while (System.currentTimeMillis() < until) {
            delay(TRANSCRIPT_POLL_MS)
            if (_attachments.value.none { it.id == id }) return
            val s = media().state(mediaId) ?: continue
            when (s.status) {
                "transcribing", "uploading" -> continue
                "ready" -> {
                    val words = s.transcript?.trim().orEmpty()
                    if (words.isNotEmpty()) {
                        val d = _draft.value.trimEnd()
                        _draft.value = if (d.isEmpty()) words else "$d $words"
                    }
                    change(id) { it.copy(state = AttachState.READY) }
                    log.i(TAG, "voice transcript: ${words.length} chars")
                    return
                }
                else -> {
                    // No words (the backend couldn't transcribe it): the
                    // recording can still go with the message.
                    change(id) { it.copy(state = AttachState.READY) }
                    log.i(TAG, "voice transcript: ${s.status}")
                    return
                }
            }
        }
        change(id) { it.copy(state = AttachState.READY) }
        log.i(TAG, "voice transcript: still not ready, sending without it")
    }

    private fun labelFor(kind: MediaKind, durationMs: Long): String {
        val name = when (kind) {
            MediaKind.IMAGE -> "Picture"
            MediaKind.VIDEO -> "Video"
            MediaKind.AUDIO -> "Voice"
        }
        if (durationMs <= 0) return name
        val s = (durationMs + 500) / 1000
        return "$name ${s / 60}:${(s % 60).toString().padStart(2, '0')}"
    }

    // -------------------------------------------------------------------------

    private fun status(text: String) {
        append(Item(nextId++, Role.STATUS, text))
    }

    private fun append(item: Item) {
        _items.value = _items.value + item
    }

    private fun update(id: Int, change: (Item) -> Item) {
        _items.value = _items.value.map { if (it.id == id) change(it) else it }
    }

    private fun add(a: Attachment) {
        _attachments.value = _attachments.value + a
    }

    private fun change(id: Int, f: (Attachment) -> Attachment) {
        _attachments.value = _attachments.value.map { if (it.id == id) f(it) else it }
    }
}
