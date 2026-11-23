package com.mirrorapps.kit.privacy

/**
 * Removes private data from log text before it leaves the phone in a bug
 * report.
 *
 * Two kinds of removal:
 *
 * - **Always**, in every mode: access codes (`mm_live_` + last 4 survives so
 *   the agent can tell a code exists), the exact strings in [secrets] (the
 *   dev and reviewer codes), purchase and bearer tokens, emails, Bluetooth
 *   addresses.
 * - **Location**, only in [Mode.REDACTED]: values tagged with [PiiTags], plus
 *   anything that looks like a coordinate pair, a Maps link or a map tile
 *   path. Each becomes a numbered placeholder such as `[LOCATION 3]`; the
 *   same value always gets the same number, and [values] maps every
 *   placeholder back to its original so Dash can send only what the user
 *   allows. Names and message text tagged by an app (MirrorMsg) go the
 *   same way.
 *
 * One instance per bug report, so numbering is shared across every text it
 * redacts (the host's log and logcat). Holds the originals in memory only.
 *
 * Pure Kotlin, no Android types: unit-tested in `RedactorTest`.
 */
class Redactor(
    private val mode: Mode,
    secrets: Collection<String> = emptyList(),
) {
    enum class Mode {
        /** Location stays in; only the always-removed data goes. */
        FULL,
        /** Location becomes numbered placeholders. */
        REDACTED,
    }

    private val secrets: List<String> = secrets
        .filter { it.length >= MIN_SECRET_LENGTH }
        .distinct()
        .sortedByDescending { it.length }

    private val numbering = LinkedHashMap<String, LinkedHashMap<String, Int>>()
    private val originals = LinkedHashMap<String, String>()

    /** Placeholder (`"LOCATION 3"`, no brackets) to the value it replaced. */
    val values: Map<String, String> get() = originals

    /** Distinct values replaced, per placeholder type. */
    val counts: Map<String, Int> get() = numbering.mapValues { it.value.size }

    fun redact(text: String): String {
        var s = text
        for (secret in secrets) s = s.replace(secret, "[DEV_CODE]")
        s = ACCESS_CODE.replace(s) { m ->
            val suffix = m.groupValues[2]
            val tail = if (suffix.length >= MIN_CODE_SUFFIX_FOR_TAIL) suffix.takeLast(4) else ""
            "mm_${m.groupValues[1]}_…$tail"
        }
        s = TOKEN_PREFIX.replace(s, "$1[TOKEN]")
        s = BEARER.replace(s, "Bearer [TOKEN]")
        s = EMAIL.replace(s, "[EMAIL]")
        s = BT_ADDRESS.replace(s, "[BT_ADDR]")

        s = TAGGED.replace(s) { m ->
            val value = m.groupValues[2]
            if (mode == Mode.FULL) value
            else placeholder(typeOf(m.groupValues[1][0]), value)
        }
        // A tag whose closer never arrived (logcat cuts very long lines):
        // treat the rest of that line as the value, so it can't leak.
        s = UNCLOSED.replace(s) { m ->
            val value = m.groupValues[2]
            if (mode == Mode.FULL) value
            else placeholder(typeOf(m.groupValues[1][0]), value)
        }
        s = s.replace(PiiTags.OPEN.toString(), "").replace(PiiTags.CLOSE.toString(), "")

        if (mode == Mode.REDACTED) {
            s = MAPS_URL.replace(s) { placeholder(TYPE_URL, it.value) }
            s = LAT_LNG_NAMED.replace(s) { m ->
                if (isCoordinate(m.groupValues[1], m.groupValues[2])) {
                    placeholder(TYPE_LOCATION, "${m.groupValues[1]},${m.groupValues[2]}")
                } else m.value
            }
            s = COORD_PAIR.replace(s) { m ->
                if (isCoordinate(m.groupValues[1], m.groupValues[2])) {
                    placeholder(TYPE_LOCATION, "${m.groupValues[1]},${m.groupValues[2]}")
                } else m.value
            }
            s = TILE_PATH.replace(s) { m ->
                val z = m.groupValues[1]
                "/$z/${placeholder(TYPE_TILE, "$z/${m.groupValues[2]}/${m.groupValues[3]}")}.png"
            }
        }
        return s
    }

    private fun placeholder(type: String, value: String): String {
        val byValue = numbering.getOrPut(type) { LinkedHashMap() }
        val n = byValue.getOrPut(value) { byValue.size + 1 }
        val key = "$type $n"
        originals[key] = value
        return "[$key]"
    }

    private fun isCoordinate(a: String, b: String): Boolean {
        val lat = a.toDoubleOrNull() ?: return false
        val lng = b.toDoubleOrNull() ?: return false
        return lat in -90.0..90.0 && lng in -180.0..180.0
    }

    companion object {
        const val TYPE_DESTINATION = "DESTINATION"
        const val TYPE_ADDRESS = "ADDRESS"
        const val TYPE_URL = "URL"
        const val TYPE_NAV_TEXT = "NAV_TEXT"
        const val TYPE_PLACE = "PLACE"
        const val TYPE_LOCATION = "LOCATION"
        const val TYPE_TILE = "TILE"
        const val TYPE_NAME = "NAME"
        const val TYPE_MESSAGE = "MESSAGE"

        private const val MIN_SECRET_LENGTH = 6
        private const val MIN_CODE_SUFFIX_FOR_TAIL = 16

        fun typeOf(kind: Char): String = when (kind) {
            PiiTags.KIND_DESTINATION -> TYPE_DESTINATION
            PiiTags.KIND_ADDRESS -> TYPE_ADDRESS
            PiiTags.KIND_URL -> TYPE_URL
            PiiTags.KIND_NAV_TEXT -> TYPE_NAV_TEXT
            PiiTags.KIND_NAME -> TYPE_NAME
            PiiTags.KIND_MESSAGE -> TYPE_MESSAGE
            else -> TYPE_PLACE
        }

        private val TAGGED = Regex(
            "${PiiTags.OPEN}([A-Z])([^${PiiTags.OPEN}${PiiTags.CLOSE}]*)${PiiTags.CLOSE}"
        )

        private val UNCLOSED = Regex("${PiiTags.OPEN}([A-Z])([^${PiiTags.OPEN}${PiiTags.CLOSE}\n]*)")

        /** `mm_live_…` codes, full or already truncated, plus a trailing ellipsis. */
        private val ACCESS_CODE = Regex("""\bmm_(live|beta|test)_([A-Za-z0-9_-]*)…?""")

        private val TOKEN_PREFIX = Regex("""(tokenPrefix=)\S+""")
        private val BEARER = Regex("""Bearer\s+\S+""")
        private val EMAIL = Regex("""[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}""")
        private val BT_ADDRESS = Regex("""\b(?:[0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}\b""")

        private val MAPS_URL = Regex(
            """https?://(?:maps\.app\.goo\.gl|goo\.gl/maps|maps\.google\.[a-z.]+|""" +
                """(?:www\.)?google\.[a-z.]+/maps)[^\s'"<>)\]]*"""
        )

        /** `lat=1.23, lng=4.56`, `LatLng(lat=…, lng=…)`, `lat: … lon: …`. */
        private val LAT_LNG_NAMED = Regex(
            """lat(?:itude)?\s*[=:]\s*(-?\d{1,2}(?:\.\d+)?)[,;\s]+""" +
                """(?:lng|lon|long|longitude)\s*[=:]\s*(-?\d{1,3}(?:\.\d+)?)"""
        )

        /**
         * `40.748817,-73.985428` and `(40.748817, -73.985428)`. Three or more
         * decimals on both sides: phone fixes print with many, while the
         * distances and speeds in these logs print with one or two.
         */
        private val COORD_PAIR = Regex(
            """(?<![\d.])(-?\d{1,2}\.\d{3,}),\s*(-?\d{1,3}\.\d{3,})(?![\d.])"""
        )

        /** `/z/x/y.png` tile requests; x and y locate the tile on the map. */
        private val TILE_PATH = Regex("""/(\d{1,2})/(\d{1,7})/(\d{1,7})\.png""")
    }
}
