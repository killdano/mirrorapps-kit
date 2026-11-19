package com.mirrorapps.kit.feedback

import android.content.Context
import android.os.Build
import android.os.Process
import com.mirrorapps.kit.LogsMode
import com.mirrorapps.kit.MirrorAppsKit
import com.mirrorapps.kit.privacy.Redactor
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * The diagnostic bundle sent with a bug report. Built only when the user
 * sends their first Feedback message (or allows full logs), never in the
 * background.
 *
 * The kit writes `schema`, `app` (the host's version) and `device`; each
 * host [com.mirrorapps.kit.BundleContributor] adds its own sections
 * (permissions, settings, subscription...); then `logs`.
 *
 * Log text goes through [Redactor] according to the "Logs in bug reports"
 * setting: Full keeps location, Redacted turns it into placeholders, None
 * leaves the logs out. Codes, tokens and emails are removed in every mode.
 *
 * The hub reads `logs.dashLog`, `logs.dashLogLines`, `logs.logcat` and
 * `logs.redactions` by name, for every app. Add fields; never rename one.
 */
object DiagnosticBundle {
    private const val TAG = "DiagnosticBundle"

    /** Last lines of the host's own logcat, warnings and errors only. */
    private const val LOGCAT_LINES = 2000
    private const val LOGCAT_TIMEOUT_S = 5L

    const val SCHEMA = 1

    suspend fun build(context: Context, app: String, mode: LogsMode): JSONObject = withContext(Dispatchers.IO) {
        val config = MirrorAppsKit.config
        val bundle = JSONObject()
        bundle.put("schema", SCHEMA)
        bundle.put("app", JSONObject().apply {
            put("name", config.host.name)
            put("versionName", config.host.versionName)
            put("versionCode", config.host.versionCode)
            put("debug", config.host.debug)
            put("about", app)
        })
        bundle.put("device", JSONObject().apply {
            put("manufacturer", Build.MANUFACTURER)
            put("model", Build.MODEL)
            put("android", Build.VERSION.RELEASE)
            put("sdk", Build.VERSION.SDK_INT)
            put("locale", Locale.getDefault().toLanguageTag())
        })
        for (contributor in config.bundleContributors) {
            try {
                contributor.contribute(context, app, bundle)
            } catch (t: Throwable) {
                // One broken section must not cost the report.
                MirrorAppsKit.log.w(TAG, "contributor failed: ${contributor.javaClass.simpleName}", t)
            }
        }
        bundle.put("logs", logs(mode))
        MirrorAppsKit.log.i(TAG, "built: logs=${mode.wire}")
        bundle
    }

    private fun logs(mode: LogsMode) = JSONObject().apply {
        put("mode", mode.wire)
        if (mode == LogsMode.NONE) return@apply
        val config = MirrorAppsKit.config
        val redactor = Redactor(
            mode = if (mode == LogsMode.FULL) Redactor.Mode.FULL else Redactor.Mode.REDACTED,
            secrets = runCatching { config.feedback.secrets() }.getOrDefault(emptyList()) +
                listOfNotNull(config.credentialNow()),
        )
        val hostLog = runCatching { config.logSource.snapshotForReport() }.getOrDefault("")
        put("dashLog", redactor.redact(hostLog))
        put("dashLogLines", if (hostLog.isEmpty()) 0 else hostLog.count { it == '\n' } + 1)
        put("logcat", redactor.redact(logcat()))
        put("redactions", JSONObject(redactor.counts as Map<*, *>))
    }

    /**
     * The host's own process only (Android lets an app read its own logcat
     * without READ_LOGS), warnings and up: crashes and system complaints
     * that never reach the host's log.
     */
    private fun logcat(): String = runCatching {
        val proc = ProcessBuilder(
            "logcat", "-d", "-t", LOGCAT_LINES.toString(),
            "--pid=${Process.myPid()}", "*:W",
        ).redirectErrorStream(true).start()
        val text = proc.inputStream.bufferedReader().use { it.readText() }
        proc.waitFor(LOGCAT_TIMEOUT_S, TimeUnit.SECONDS)
        text
    }.getOrElse { "(logcat unavailable: ${it.javaClass.simpleName})" }
}
