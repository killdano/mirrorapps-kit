package com.mirrorapps.kit.updater

import org.json.JSONObject

/**
 * The newest test build, as the backend's `GET /v1/test-builds/<app>/latest`
 * answers it: `{tag, versionCode, versionName, publishedAt, size}`.
 */
data class TestBuild(
    /** `test-N`. */
    val tag: String,
    val versionCode: Long,
    val versionName: String,
    val publishedAt: String,
    /** APK size in bytes, 0 if unknown. */
    val size: Long,
) {
    /** N of `test-N`. */
    val number: Int? get() = testNumber(tag)

    /** `test-23 (100023)`, for log lines. */
    override fun toString(): String = "$tag ($versionCode)"

    companion object {
        private val TAG = Regex("^test-([1-9][0-9]{0,6})$")

        /** N from a `test-N` tag, or null for any other tag. */
        fun testNumber(tag: String): Int? = TAG.matchEntire(tag)?.groupValues?.get(1)?.toInt()

        /**
         * Reads the backend's answer. Null when it isn't a test build: a
         * missing or malformed tag, or no versionCode.
         */
        fun parse(json: String): TestBuild? {
            val o = try {
                JSONObject(json)
            } catch (e: Exception) {
                return null
            }
            val tag = o.optString("tag")
            val code = o.optLong("versionCode", 0L)
            if (testNumber(tag) == null || code <= 0L) return null
            return TestBuild(
                tag = tag,
                versionCode = code,
                versionName = o.optString("versionName"),
                publishedAt = o.optString("publishedAt"),
                size = o.optLong("size", 0L).coerceAtLeast(0L),
            )
        }
    }
}

/** What a check decided to do. */
enum class Decision {
    /** The installed build is the newest, or newer. */
    UP_TO_DATE,

    /** A newer build exists but automatic install is off. */
    NEWER_BUT_OFF,

    /** Android is still waiting for the user's tap on this very build. */
    WAITING_FOR_TAP,

    /** Download and install it. */
    INSTALL,

    /**
     * Android already refused this very build for good (signed with a
     * different key, or a broken APK): downloading it again can't help.
     */
    CANT_INSTALL,
}

/** The pure half of a check, so it can be tested without a phone. */
object TestBuildRules {

    /** How long a "tap to install" prompt stands before the build is fetched again. */
    const val TAP_WAIT_MS: Long = 12L * 60 * 60 * 1000

    /**
     * What to do about [newest].
     *
     * @param installedCode the running build's versionCode.
     * @param autoInstall the user's switch.
     * @param waitingCode versionCode of a build already handed to Android
     *     and waiting for the user's tap, or 0.
     * @param waitingSince when that build was handed over (ms).
     * @param waitingSessionAlive whether Android still holds that session.
     * @param refusedCode versionCode of a build Android refused for good, or 0.
     */
    fun decide(
        newest: TestBuild,
        installedCode: Long,
        autoInstall: Boolean,
        waitingCode: Long = 0L,
        waitingSince: Long = 0L,
        waitingSessionAlive: Boolean = false,
        now: Long = System.currentTimeMillis(),
        refusedCode: Long = 0L,
    ): Decision = when {
        newest.versionCode <= installedCode -> Decision.UP_TO_DATE
        refusedCode > 0L && newest.versionCode == refusedCode -> Decision.CANT_INSTALL
        !autoInstall -> Decision.NEWER_BUT_OFF
        waitingCode == newest.versionCode && waitingSessionAlive &&
            now - waitingSince in 0 until TAP_WAIT_MS -> Decision.WAITING_FOR_TAP
        else -> Decision.INSTALL
    }

    /**
     * Whether a build handed to Android earlier is now running: the
     * installed versionCode reached it.
     */
    fun pendingLanded(pendingCode: Long, installedCode: Long): Boolean =
        pendingCode > 0L && installedCode >= pendingCode

    /**
     * Whether an install failure will happen again for the same build:
     * a key mismatch (`STATUS_FAILURE_CONFLICT`, 5), an incompatible build
     * (`STATUS_FAILURE_INCOMPATIBLE`, 7) or a broken APK
     * (`STATUS_FAILURE_INVALID`, 4). A cancel, a block or full storage can
     * pass, so those builds are tried again.
     */
    fun refusedForGood(status: Int): Boolean = status == 4 || status == 5 || status == 7

    /** `23.4 MB`, for log lines. */
    fun megabytes(bytes: Long): String = String.format(java.util.Locale.US, "%.1f MB", bytes / 1_000_000.0)
}
