package com.mirrorapps.kit.privacy

/**
 * The format for tagging private values in log lines, so [Redactor] removes
 * them by type instead of guessing from the text.
 *
 * A tagged value is wrapped in two private-use characters, with a one-letter
 * kind after the opener: `` `D` value ``. Hosts tag at the line
 * that writes the value (Dash's `Pii`, MirrorMov's `MovPii`) and strip the
 * markers for display with [strip]. Every writer must use exactly this
 * format; changing it is a change in every app.
 */
object PiiTags {
    const val OPEN = ''
    const val CLOSE = ''

    /** A destination name or text, as Maps or the user gave it. */
    const val KIND_DESTINATION = 'D'

    /** Address text the user typed or shared. */
    const val KIND_ADDRESS = 'A'

    /** A Maps link, short or resolved. */
    const val KIND_URL = 'U'

    /** Maps notification text: turn instructions, street and stop names. */
    const val KIND_NAV_TEXT = 'N'

    /** Any other place name. */
    const val KIND_PLACE = 'P'

    /** A contact or conversation name. */
    const val KIND_NAME = 'C'

    /** Message text: notifications, conversations, replies. */
    const val KIND_MESSAGE = 'M'

    /** Wraps [value] with [kind]. `null` stays the plain text "null". */
    fun wrap(kind: Char, value: Any?): String {
        if (value == null) return "null"
        val s = value.toString().replace(OPEN.toString(), "").replace(CLOSE.toString(), "")
        return "$OPEN$kind$s$CLOSE"
    }

    /** Removes the markers and keeps the values. For display only. */
    fun strip(text: String): String {
        if (text.indexOf(OPEN) < 0 && text.indexOf(CLOSE) < 0) return text
        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when (c) {
                OPEN -> i += 2
                CLOSE -> i += 1
                else -> {
                    out.append(c)
                    i += 1
                }
            }
        }
        return out.toString()
    }
}
