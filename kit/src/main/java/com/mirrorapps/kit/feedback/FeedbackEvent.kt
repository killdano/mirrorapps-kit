package com.mirrorapps.kit.feedback

import org.json.JSONObject

/**
 * What the hub streams back for one message, as `text/event-stream`.
 *
 * ```
 * event: session          data: {"sessionId":"…"}
 * event: delta            data: {"text":"…"}            appended to the reply
 * event: consent_request  data: {"reason":"…"}          wants full logs
 * event: offer_preview    data: {}                      offers to show the data
 * event: outcome          data: {"kind":"resolved|filed|more_info","issueUrl":"…"}
 * event: error            data: {"message":"…"}
 * event: done             data: {}
 * ```
 *
 * Unknown event names are ignored, so the hub can add events without
 * breaking older app builds. The hub depends on these names: add events,
 * never rename one.
 */
sealed class FeedbackEvent {
    data class Session(val sessionId: String) : FeedbackEvent()
    data class Delta(val text: String) : FeedbackEvent()
    data class ConsentRequest(val reason: String) : FeedbackEvent()
    data object OfferPreview : FeedbackEvent()
    data class Outcome(val kind: String, val issueUrl: String?) : FeedbackEvent()
    data class Error(val message: String) : FeedbackEvent()
    data object Done : FeedbackEvent()

    companion object {
        /** Longest consent reason shown in the dialog. */
        const val MAX_REASON_CHARS = 200

        fun from(event: SseParser.Event): FeedbackEvent? {
            val json = runCatching { JSONObject(event.data.ifBlank { "{}" }) }.getOrNull()
                ?: return if (event.name == "delta") Delta(event.data) else null
            return when (event.name) {
                "session" -> json.optString("sessionId").takeIf { it.isNotEmpty() }?.let { Session(it) }
                "delta" -> Delta(json.optString("text"))
                "consent_request" -> ConsentRequest(json.optString("reason").take(MAX_REASON_CHARS))
                "offer_preview" -> OfferPreview
                "outcome" -> Outcome(
                    kind = json.optString("kind"),
                    issueUrl = json.optString("issueUrl").takeIf { it.startsWith("https://") },
                )
                "error" -> Error(json.optString("message").ifEmpty { "Something went wrong" })
                "done" -> Done
                else -> null
            }
        }
    }
}

/**
 * Minimal Server-Sent Events parser: feed it lines, get an [Event] at each
 * blank line. Handles `event:` and multi-line `data:`, skips `:` comments and
 * `id:`/`retry:`.
 */
class SseParser {
    data class Event(val name: String, val data: String)

    private var name: String? = null
    private val data = StringBuilder()
    private var hasData = false

    fun feed(line: String): Event? {
        if (line.isEmpty()) {
            if (!hasData && name == null) return null
            val event = Event(name ?: "message", data.toString())
            name = null
            data.setLength(0)
            hasData = false
            return event
        }
        if (line.startsWith(":")) return null
        val colon = line.indexOf(':')
        val field = if (colon < 0) line else line.substring(0, colon)
        var value = if (colon < 0) "" else line.substring(colon + 1)
        if (value.startsWith(" ")) value = value.substring(1)
        when (field) {
            "event" -> name = value
            "data" -> {
                if (hasData) data.append('\n')
                data.append(value)
                hasData = true
            }
        }
        return null
    }
}
