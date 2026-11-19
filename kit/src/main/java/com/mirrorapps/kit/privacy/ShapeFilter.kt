package com.mirrorapps.kit.privacy

import org.json.JSONObject

/**
 * The shape filter: what MirrorMsg sends instead of message content
 * (`trip-review/SPEC.md` in the backend repo, MirrorMsg section).
 *
 * **No message content ever leaves the phone.** Every text that came from a
 * notification, a conversation or a reply is replaced by its shape:
 *
 * - letters become `x`, capitals (and title case) `X`, in any script;
 * - digits and other numerals become `9`;
 * - each emoji becomes one `E`, however many code points it's built from
 *   (skin tones, ZWJ sequences, flags, keycaps, variation selectors);
 * - punctuation, symbols such as `$ + =`, and spacing are kept as they are;
 * - combining marks and invisible format characters are dropped (they
 *   belong to the letter before them); anything else becomes `?`.
 *
 * Plus its length, in code points of the original. Sender and conversation
 * names become `Name` and a length.
 *
 * Package names, channel ids, extras keys, flags, counts and timings are not
 * content and stay verbatim; this filter is only for text.
 *
 * Pure Kotlin (org.json aside), unit-tested in `ShapeFilterTest`.
 */
object ShapeFilter {

    /** What a name becomes. */
    const val NAME = "Name"

    /** The shape of [text]. */
    fun shape(text: CharSequence): String {
        val s = text.toString()
        val out = StringBuilder(s.length)
        var i = 0
        // True right after an emoji, while its modifiers and joined parts follow.
        var inEmoji = false
        while (i < s.length) {
            val cp = s.codePointAt(i)
            i += Character.charCount(cp)
            if (inEmoji && continuesEmoji(cp)) {
                if (cp == ZWJ && i < s.length) {
                    // The next code point is part of this emoji too.
                    val next = s.codePointAt(i)
                    if (isEmoji(next)) i += Character.charCount(next)
                }
                continue
            }
            inEmoji = false
            when {
                isRegionalIndicator(cp) -> {
                    // A flag is two regional indicators: one E for the pair.
                    if (i < s.length) {
                        val next = s.codePointAt(i)
                        if (isRegionalIndicator(next)) i += Character.charCount(next)
                    }
                    out.append('E')
                    inEmoji = true
                }
                isKeycapBase(cp) && keycapFollows(s, i) -> {
                    out.append('E')
                    inEmoji = true
                }
                Character.isLetter(cp) ->
                    out.append(if (Character.isUpperCase(cp) || Character.isTitleCase(cp)) 'X' else 'x')
                isNumber(cp) -> out.append('9')
                isEmoji(cp) -> {
                    out.append('E')
                    inEmoji = true
                }
                Character.isWhitespace(cp) || Character.isSpaceChar(cp) -> out.appendCodePoint(cp)
                isPunctuationOrSymbol(cp) -> out.appendCodePoint(cp)
                isDropped(cp) -> Unit
                else -> out.append('?')
            }
        }
        return out.toString()
    }

    /** Length as this filter counts it: code points of the original. */
    fun length(text: CharSequence): Int = text.toString().let { it.codePointCount(0, it.length) }

    /** `{"shape": "Xxxx", "len": 4}`, or JSON null for null text. */
    fun shapeJson(text: CharSequence?): Any {
        if (text == null) return JSONObject.NULL
        return JSONObject().put("shape", shape(text)).put("len", length(text))
    }

    /** `{"name": "Name", "len": 5}`, or JSON null for a null name. */
    fun nameJson(name: CharSequence?): Any {
        if (name == null) return JSONObject.NULL
        return JSONObject().put("name", NAME).put("len", length(name))
    }

    /**
     * Filters one log line. Values tagged with [PiiTags.KIND_NAME] become
     * `[Name len=5]`; every other tagged value becomes
     * `[shape "Xxxx xx" len=7]`. Untagged text is left alone, so an app
     * whose logs go through this filter tags every name and every message
     * text it logs (`PiiTags.wrap(PiiTags.KIND_MESSAGE, text)`).
     */
    fun filterLogLine(line: String): String {
        if (line.indexOf(PiiTags.OPEN) < 0) return line.replace(PiiTags.CLOSE.toString(), "")
        val out = StringBuilder(line.length)
        var i = 0
        while (i < line.length) {
            val c = line[i]
            if (c != PiiTags.OPEN) {
                if (c != PiiTags.CLOSE) out.append(c)
                i++
                continue
            }
            val kind = if (i + 1 < line.length) line[i + 1] else ' '
            // A tag whose closer never arrived runs to the end of the line.
            val close = line.indexOf(PiiTags.CLOSE, i + 2).let { if (it < 0) line.length else it }
            val value = if (i + 2 <= close) line.substring(i + 2, close) else ""
            val clean = value.replace(PiiTags.OPEN.toString(), "")
            if (kind == PiiTags.KIND_NAME) {
                out.append("[").append(NAME).append(" len=").append(length(clean)).append("]")
            } else {
                out.append("[shape \"").append(shape(clean)).append("\" len=").append(length(clean)).append("]")
            }
            i = close + 1
        }
        return out.toString()
    }

    // -------------------------------------------------------------------------

    private const val ZWJ = 0x200D
    private const val KEYCAP = 0x20E3

    private fun isNumber(cp: Int): Boolean = when (Character.getType(cp).toByte()) {
        Character.DECIMAL_DIGIT_NUMBER, Character.LETTER_NUMBER, Character.OTHER_NUMBER -> true
        else -> false
    }

    private fun isRegionalIndicator(cp: Int) = cp in 0x1F1E6..0x1F1FF

    /** `1️⃣`, `#️⃣`: a digit, `#` or `*`, an optional VS16, then U+20E3. */
    private fun isKeycapBase(cp: Int) = cp in '0'.code..'9'.code || cp == '#'.code || cp == '*'.code

    private fun keycapFollows(s: String, from: Int): Boolean {
        var i = from
        if (i < s.length && s.codePointAt(i) == 0xFE0F) i++
        return i < s.length && s.codePointAt(i) == KEYCAP
    }

    /** Pictographs, dingbats and other symbols drawn as emoji. */
    private fun isEmoji(cp: Int): Boolean =
        cp in 0x1F000..0x1FAFF ||
            cp in 0x2600..0x27BF ||
            cp in 0x2B00..0x2BFF ||
            cp in 0x2300..0x23FF ||
            cp in 0x2190..0x21FF ||
            cp in 0x25A0..0x25FF ||
            cp == 0x00A9 || cp == 0x00AE || cp == 0x203C || cp == 0x2049 ||
            cp == 0x2122 || cp == 0x2139 || cp == 0x3030 || cp == 0x303D ||
            cp == 0x3297 || cp == 0x3299 ||
            Character.getType(cp).toByte() == Character.OTHER_SYMBOL

    /** What can follow an emoji and belongs to it. */
    private fun continuesEmoji(cp: Int): Boolean =
        cp == ZWJ ||
            cp == 0xFE0F || cp == 0xFE0E ||
            cp == KEYCAP ||
            cp in 0x1F3FB..0x1F3FF || // skin tones
            cp in 0xE0020..0xE007F // tag sequences (subdivision flags)

    private fun isPunctuationOrSymbol(cp: Int): Boolean = when (Character.getType(cp).toByte()) {
        Character.CONNECTOR_PUNCTUATION,
        Character.DASH_PUNCTUATION,
        Character.START_PUNCTUATION,
        Character.END_PUNCTUATION,
        Character.INITIAL_QUOTE_PUNCTUATION,
        Character.FINAL_QUOTE_PUNCTUATION,
        Character.OTHER_PUNCTUATION,
        Character.MATH_SYMBOL,
        Character.CURRENCY_SYMBOL,
        Character.MODIFIER_SYMBOL -> true
        else -> false
    }

    /** Combining marks, joiners and other invisible format characters. */
    private fun isDropped(cp: Int): Boolean = when (Character.getType(cp).toByte()) {
        Character.NON_SPACING_MARK,
        Character.COMBINING_SPACING_MARK,
        Character.ENCLOSING_MARK,
        Character.FORMAT -> true
        else -> cp == 0xFE0F || cp == 0xFE0E
    }
}
