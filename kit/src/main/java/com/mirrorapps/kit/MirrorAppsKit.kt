package com.mirrorapps.kit

import android.content.Context
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import com.mirrorapps.kit.feedback.FeedbackBackend
import java.util.UUID
import kotlinx.coroutines.flow.StateFlow
import okhttp3.OkHttpClient
import org.json.JSONObject

/**
 * The kit's entry point. The host app calls [init] once from
 * `Application.onCreate`, after its own logging and credentials are up:
 *
 * ```
 * MirrorAppsKit.init(this, KitConfig(
 *     apps = listOf(KitApp("mirrormap", "MirrorMap"), KitApp("mirrormov", "MirrorMov")),
 *     feedback = FeedbackOptions(app = "dash", ...),
 *     host = KitHost("MirrorMap Dash", BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE.toLong(), BuildConfig.DEBUG),
 *     credential = credentials.accessCode,
 *     logSource = myLog,
 *     bundleContributors = listOf(...),
 *     theme = myTheme,
 *     httpClient = sharedClient,
 * ))
 * ```
 *
 * Public repo: nothing private belongs in the kit. Codes, keys and anything
 * secret stay in the host and reach the kit only at run time, through
 * [KitConfig].
 */
object MirrorAppsKit {
    @Volatile
    private var current: KitConfig? = null

    @Volatile
    private var context: Context? = null

    /** The config passed to [init]. Throws before [init]. */
    val config: KitConfig
        get() = current ?: error("MirrorAppsKit.init() wasn't called")

    val isInitialized: Boolean get() = current != null

    /** The application context. Throws before [init]. */
    val appContext: Context
        get() = context ?: error("MirrorAppsKit.init() wasn't called")

    fun init(context: Context, config: KitConfig) {
        require(config.apps.isNotEmpty()) { "KitConfig.apps is empty" }
        this.context = context.applicationContext
        current = config
    }

    /** The host's log, or logcat before [init]. */
    internal val log: KitLog
        get() = current?.logSource ?: LogcatLog

    /**
     * A random id per install, so the hub can rate-limit and resume chats
     * without an account. Stored in the host-named prefs file so an app that
     * had its own id before the kit keeps it.
     */
    fun installId(): String {
        val prefs = appContext.getSharedPreferences(config.feedback.installIdPrefs, Context.MODE_PRIVATE)
        return prefs.getString(KEY_INSTALL_ID, null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString(KEY_INSTALL_ID, it).apply()
        }
    }

    /** Small per-device conveniences of the kit's own. */
    internal fun prefs() = appContext.getSharedPreferences(KIT_PREFS, Context.MODE_PRIVATE)

    private const val KEY_INSTALL_ID = "install_id"
    private const val KIT_PREFS = "mirrorapps_kit"
}

/**
 * Everything the kit needs from its host.
 *
 * @property apps The apps this host serves, for session recording. Dash:
 *   MirrorMap and MirrorMov. MirrorMsg: MirrorMsg.
 * @property host Name and version of the host app, for bug reports and chunks.
 * @property credential The access code (or owner credential) that
 *   authenticates media uploads, sessions and Feedback. Null when there's none.
 * @property logSource Where the kit logs, and what a bug report attaches.
 * @property bundleContributors Host sections of a bug report's diagnostic
 *   bundle (permissions, settings, subscription state...).
 * @property theme The host's palette, so the kit's screens match its own.
 * @property httpClient The host's shared client; the kit derives its own
 *   timeouts from it.
 * @property baseUrl The backend.
 */
class KitConfig(
    val apps: List<KitApp>,
    val host: KitHost,
    val credential: StateFlow<String?>,
    val logSource: KitLog,
    val theme: KitTheme,
    val bundleContributors: List<BundleContributor> = emptyList(),
    val httpClient: OkHttpClient = OkHttpClient(),
    val baseUrl: String = "https://api.mirrormap.app",
    val feedback: FeedbackOptions = FeedbackOptions(),
) {
    /** The access code right now, or null. */
    fun credentialNow(): String? = credential.value?.takeIf { it.isNotBlank() }
}

/**
 * One app the host serves.
 *
 * @property id The hub's app id and the session `app`: `mirrormap`,
 *   `mirrormov`, `mirrormsg`. Wire values: never rename one.
 * @property label The app's display name.
 */
data class KitApp(val id: String, val label: String)

/** The host app's name and version. */
data class KitHost(
    val name: String,
    val versionName: String,
    val versionCode: Long,
    val debug: Boolean,
)

/**
 * Feedback tab settings that belong to the host.
 *
 * @property logsMode Reads the host's "Logs in bug reports" setting. The
 *   chat shows it (Full / Redacted / None) at its top, in every app.
 * @property setLogsMode Saves it. Allow on the "Send full logs?" dialog
 *   moves it to [LogsMode.FULL], so later reports don't ask again.
 * @property secrets Exact strings removed from every log in every mode (a
 *   dev code compiled into the app, the user's own code). Read at report
 *   time; never stored by the kit.
 * @property standIn A replacement for the hub (a debug-only stand-in), or
 *   null for the real hub. Read on every message, so a host setting can
 *   switch it.
 * @property installIdPrefs SharedPreferences file holding the install id.
 * @property logName Shown above the host log in the "What was sent"
 *   preview.
 * @property fullLogsText The explanation in the "Send full logs?" dialog:
 *   what full logs contain in this app, and that they're only sent with a
 *   report the user starts.
 * @property app The hub app id Feedback talks as. There's no app switch:
 *   Dash talks as `dash` and the hub works out MirrorMap, MirrorMov or Dash
 *   from what the user says and the bundle (Dash's `lastUsed` section).
 *   Null means the first of [KitConfig.apps] (MirrorMsg: `mirrormsg`).
 * @property logcat Whether a report attaches the host process's own logcat
 *   (warnings and up) next to the host log. MirrorMsg turns it off: its
 *   native bridges write to logcat without the app's scrubbing, so only its
 *   own scrubbed log goes in a report.
 */
class FeedbackOptions(
    val logsMode: () -> LogsMode = { LogsMode.REDACTED },
    val setLogsMode: (LogsMode) -> Unit = {},
    val secrets: () -> Collection<String> = { emptyList() },
    val standIn: () -> FeedbackBackend? = { null },
    val installIdPrefs: String = "mirrorapps_kit_feedback",
    val logName: String = "log",
    val fullLogsText: String = "Full logs can include private details. They're only sent with a " +
        "report you start, never on their own. You can change this at the top of the chat.",
    val app: String? = null,
    val logcat: Boolean = true,
)

/** How much of the log goes into a bug report. */
enum class LogsMode(val wire: String) {
    /** Everything except codes, tokens and emails. */
    FULL("full"),

    /** Location and other tagged values become placeholders. */
    REDACTED("redacted"),

    /** No logs at all. */
    NONE("none"),
}

/**
 * The host's log. The kit writes its own lines here, and a bug report
 * attaches [snapshotForReport].
 */
interface KitLog {
    fun d(tag: String, message: String)
    fun i(tag: String, message: String)
    fun w(tag: String, message: String, t: Throwable? = null)

    /**
     * The log for a bug report, oldest line first, with the privacy tags of
     * [com.mirrorapps.kit.privacy.PiiTags] still in place so the redactor
     * can find tagged values. Empty when the host has no log to share.
     */
    fun snapshotForReport(): String = ""
}

/** A section of the diagnostic bundle that only the host knows. */
fun interface BundleContributor {
    /**
     * Adds this host's sections to [bundle]. Runs on a background thread
     * when the user sends their first message. Nothing private: these
     * sections are sent in every logs mode.
     *
     * @param app The app the report is about (a [KitApp.id]).
     */
    fun contribute(context: Context, app: String, bundle: JSONObject)
}

/** The host's palette, so the kit's screens look like the rest of the app. */
interface KitTheme {
    @Composable
    fun colors(): KitColors
}

/**
 * Colors the kit draws with. Names follow Dash's palette: [accent] is the
 * one accent color (filled buttons, the selected switch half, links),
 * [accentFg] the text on it.
 */
@Immutable
data class KitColors(
    val bg: Color,
    val fg: Color,
    val cardBg: Color,
    val cardDivider: Color,
    val sectionFg: Color,
    val helpFg: Color,
    val accent: Color,
    val accentFg: Color,
    val disabledFg: Color,
)

/** Before [MirrorAppsKit.init]: logcat only. */
internal object LogcatLog : KitLog {
    override fun d(tag: String, message: String) {
        Log.d(tag, message)
    }

    override fun i(tag: String, message: String) {
        Log.i(tag, message)
    }

    override fun w(tag: String, message: String, t: Throwable?) {
        Log.w(tag, message, t)
    }
}
