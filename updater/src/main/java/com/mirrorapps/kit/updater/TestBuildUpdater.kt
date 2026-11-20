package com.mirrorapps.kit.updater

import android.Manifest
import android.app.Activity
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.content.SharedPreferences
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Lets an app's debug build install newer test builds by itself.
 *
 * A check asks the backend for the newest test build
 * (`GET <baseUrl>/v1/test-builds/<appId>/latest`, the owner's credential as
 * the Bearer token). When its versionCode is higher than the running one
 * and automatic install is on, it downloads the APK
 * (`GET .../<tag>/apk`, which redirects to the file) straight into a
 * [PackageInstaller] session and commits it asking for no user action.
 * Android grants that once this app installed the previous build itself;
 * until then (or while "Install unknown apps" is off for the app) it asks,
 * and this posts one notification, "<label> test N is ready: tap to
 * install", that opens the system prompt.
 *
 * The host calls [init] from `Application.onCreate` (debug builds only) and
 * runs [check] on a schedule of its own. [init] also checks whenever one
 * of the app's screens comes to the front, at most once per
 * [Config.resumeCheckGapMs]. Every step goes to [Config.log].
 *
 * Nothing here knows a repo, a URL or a credential: the host passes them.
 */
object TestBuildUpdater {

    /** What the host supplies. */
    class Config(
        /** The app's id on the backend, e.g. `dash`. */
        val appId: String,
        /** Short name for the notification: "<appLabel> test N is ready". */
        val appLabel: String,
        /** The backend, without a trailing slash. */
        val baseUrl: String,
        /** The running build's versionCode. */
        val installedVersionCode: Long,
        /** The host's client; the download gets longer timeouts from it. */
        val http: OkHttpClient,
        /** The owner's credential, sent as the Bearer token; null if there's none yet. */
        val credential: () -> String?,
        /** Where each step is logged. */
        val log: Log,
        /** Small icon for the notification. */
        val smallIcon: Int,
        /**
         * A reason to hold an install for now (navigating, recording), or
         * null to go ahead. Asked right before a download starts; a held
         * build is picked up by a later check.
         */
        val holdInstall: () -> String? = { null },
        /** Least time between the checks run when the app comes to the front. */
        val resumeCheckGapMs: Long = 60_000L,
    )

    /** One line per step. The host adds its own tag. */
    interface Log {
        fun i(message: String)
        fun w(message: String, error: Throwable? = null)
    }

    enum class Phase {
        IDLE, CHECKING, UP_TO_DATE, NEWER_BUT_OFF, HELD, DOWNLOADING, INSTALLING,
        WAITING_FOR_TAP, FAILED,
    }

    /** For a settings row. */
    data class State(
        val installedCode: Long = 0L,
        /** The newest build the last check saw (kept across restarts). */
        val newest: TestBuild? = null,
        val autoInstall: Boolean = true,
        val phase: Phase = Phase.IDLE,
        /** Why the last check stopped short, in a few words. */
        val note: String? = null,
    )

    private const val PREFS = "mirrorapps_kit_test_builds"
    private const val K_AUTO = "auto_install"
    private const val K_NEWEST = "newest_json"
    private const val K_PENDING_CODE = "pending_code"
    private const val K_PENDING_TAG = "pending_tag"
    private const val K_PENDING_SESSION = "pending_session"
    private const val K_PENDING_AT = "pending_at"
    private const val K_REFUSED_CODE = "refused_code"
    private const val K_REFUSED_WHY = "refused_why"

    private const val CHANNEL_ID = "mirrorapps_test_builds"
    private const val NOTIFICATION_ID = 0x7e57

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val running = Mutex()

    @Volatile private var config: Config? = null
    @Volatile private var app: Application? = null
    @Volatile private var lastCheckAt = 0L
    @Volatile private var resumed = 0

    private lateinit var prefs: SharedPreferences

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    /**
     * Sets the updater up and runs the first check. Call once, from
     * `Application.onCreate`, in debug builds only.
     */
    fun init(application: Application, cfg: Config) {
        if (config != null) return
        app = application
        prefs = application.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        config = cfg

        // A build handed to Android before the last restart: did it land?
        val pendingCode = prefs.getLong(K_PENDING_CODE, 0L)
        if (TestBuildRules.pendingLanded(pendingCode, cfg.installedVersionCode)) {
            cfg.log.i("installed ${prefs.getString(K_PENDING_TAG, "?")} ($pendingCode)")
            clearPending()
            NotificationManagerCompat.from(application).cancel(NOTIFICATION_ID)
        }

        _state.value = State(
            installedCode = cfg.installedVersionCode,
            newest = prefs.getString(K_NEWEST, null)?.let { TestBuild.parse(it) },
            autoInstall = prefs.getBoolean(K_AUTO, true),
        )

        application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                resumed++
                if (System.currentTimeMillis() - lastCheckAt >= cfg.resumeCheckGapMs) {
                    checkSoon("app opened")
                }
            }

            override fun onActivityPaused(activity: Activity) {
                resumed = (resumed - 1).coerceAtLeast(0)
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })

        checkSoon("start")
    }

    /** The user's switch. On by default. */
    fun setAutoInstall(on: Boolean) {
        val cfg = config ?: return
        prefs.edit().putBoolean(K_AUTO, on).apply()
        _state.update { it.copy(autoInstall = on) }
        cfg.log.i("automatic install ${if (on) "on" else "off"}")
        if (on) checkSoon("switched on")
    }

    /** Runs [check] in the background. */
    fun checkSoon(reason: String) {
        scope.launch { check(reason) }
    }

    /**
     * Checks once, and downloads and hands the build to Android when it
     * should. Returns when the build is committed (Android's answer comes
     * later, to [TestBuildInstallReceiver]). A check already running makes
     * this one a no-op.
     */
    suspend fun check(reason: String): State {
        val cfg = config ?: return _state.value
        if (!running.tryLock()) return _state.value
        try {
            lastCheckAt = System.currentTimeMillis()
            val cred = cfg.credential()
            if (cred.isNullOrBlank()) {
                cfg.log.i("check ($reason): no access code yet, skipped")
                setPhase(Phase.IDLE, "no access code yet")
                return _state.value
            }
            setPhase(Phase.CHECKING)
            val newest = withContext(Dispatchers.IO) { fetchLatest(cfg, cred) } ?: return _state.value
            prefs.edit().putString(K_NEWEST, newest.toJson()).apply()
            _state.update { it.copy(newest = newest) }

            val installed = cfg.installedVersionCode
            val waitingCode = prefs.getLong(K_PENDING_CODE, 0L)
            val waitingSession = prefs.getInt(K_PENDING_SESSION, 0)
            val alive = waitingSession != 0 && sessionAlive(waitingSession)
            val head = "check ($reason): newest $newest, installed $installed"
            when (TestBuildRules.decide(
                newest, installed, _state.value.autoInstall,
                waitingCode, prefs.getLong(K_PENDING_AT, 0L), alive,
                refusedCode = prefs.getLong(K_REFUSED_CODE, 0L),
            )) {
                Decision.CANT_INSTALL -> {
                    val why = prefs.getString(K_REFUSED_WHY, null) ?: "Android refused it"
                    cfg.log.i("$head, not downloading it again: $why")
                    setPhase(Phase.FAILED, why)
                }
                Decision.UP_TO_DATE -> {
                    cfg.log.i("$head, up to date")
                    if (waitingCode != 0L) clearPending()
                    setPhase(Phase.UP_TO_DATE)
                }
                Decision.NEWER_BUT_OFF -> {
                    cfg.log.i("$head, automatic install is off")
                    setPhase(Phase.NEWER_BUT_OFF)
                }
                Decision.WAITING_FOR_TAP -> {
                    cfg.log.i("$head, still waiting for the tap to install")
                    setPhase(Phase.WAITING_FOR_TAP)
                }
                Decision.INSTALL -> {
                    val hold = cfg.holdInstall()
                    if (hold != null) {
                        cfg.log.i("$head, holding the install: $hold")
                        setPhase(Phase.HELD, hold)
                    } else {
                        cfg.log.i("$head, downloading…")
                        if (alive) abandon(waitingSession)
                        withContext(Dispatchers.IO) { install(cfg, cred, newest) }
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            cfg.log.w("check ($reason) failed: ${t.message ?: t.javaClass.simpleName}", t)
            setPhase(Phase.FAILED, t.message ?: t.javaClass.simpleName)
        } finally {
            running.unlock()
        }
        return _state.value
    }

    // ---- Network ---------------------------------------------------------------

    /** The newest build, or null (logged) when there's none or the check failed. */
    private fun fetchLatest(cfg: Config, cred: String): TestBuild? {
        val req = Request.Builder()
            .url("${cfg.baseUrl}/v1/test-builds/${cfg.appId}/latest")
            .header("Authorization", "Bearer $cred")
            .build()
        cfg.http.newCall(req).execute().use { r ->
            val body = r.body?.string().orEmpty()
            if (r.code == 404) {
                // The backend answers 404 to anyone but the owner, too.
                cfg.log.i("no test build found (404: none published, or this access code isn't the owner's)")
                setPhase(Phase.FAILED, "no test build found")
                return null
            }
            if (!r.isSuccessful) {
                cfg.log.w("check failed: HTTP ${r.code}")
                setPhase(Phase.FAILED, "check failed: HTTP ${r.code}")
                return null
            }
            return TestBuild.parse(body) ?: run {
                cfg.log.w("check failed: unreadable answer")
                setPhase(Phase.FAILED, "unreadable answer")
                null
            }
        }
    }

    /**
     * Downloads [build] into a new install session and commits it. The
     * APK request is redirected to the file's host; OkHttp drops the
     * Authorization header when it follows a redirect to another host.
     */
    private fun install(cfg: Config, cred: String, build: TestBuild) {
        val ctx = app ?: return
        setPhase(Phase.DOWNLOADING)
        val installer = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(ctx.packageName)
            if (build.size > 0) setSize(build.size)
            setInstallReason(PackageManager.INSTALL_REASON_USER)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
        }
        val id = installer.createSession(params)
        try {
            val download = cfg.http.newBuilder()
                .callTimeout(15, TimeUnit.MINUTES)
                .readTimeout(60, TimeUnit.SECONDS)
                .followRedirects(true)
                .followSslRedirects(true)
                .build()
            val req = Request.Builder()
                .url("${cfg.baseUrl}/v1/test-builds/${cfg.appId}/${build.tag}/apk")
                .header("Authorization", "Bearer $cred")
                .build()
            download.newCall(req).execute().use { r ->
                if (!r.isSuccessful) throw IOException("download: HTTP ${r.code}")
                val body = r.body ?: throw IOException("download: no body")
                installer.openSession(id).use { session ->
                    val bytes = session.openWrite("base.apk", 0, if (build.size > 0) build.size else -1).use { out ->
                        val n = body.byteStream().use { it.copyTo(out, 64 * 1024) }
                        session.fsync(out)
                        n
                    }
                    if (build.size > 0 && bytes != build.size) {
                        throw IOException("download: got $bytes of ${build.size} bytes")
                    }
                    cfg.log.i("downloaded ${TestBuildRules.megabytes(bytes)}, installing $build")
                    setPhase(Phase.INSTALLING)
                    // Written before commit: a self-update ends this process,
                    // and the next start reads it to log "installed".
                    prefs.edit()
                        .putLong(K_PENDING_CODE, build.versionCode)
                        .putString(K_PENDING_TAG, build.tag)
                        .putInt(K_PENDING_SESSION, id)
                        .putLong(K_PENDING_AT, System.currentTimeMillis())
                        .commit()
                    session.commit(statusReceiver(ctx, id))
                }
            }
        } catch (t: Throwable) {
            abandon(id)
            if (prefs.getInt(K_PENDING_SESSION, 0) == id) clearPending()
            throw t
        }
    }

    // ---- Android's answer ------------------------------------------------------

    private fun statusReceiver(ctx: Context, sessionId: Int): IntentSender {
        val intent = Intent(ctx, TestBuildInstallReceiver::class.java).setPackage(ctx.packageName)
        // Mutable: the installer adds the status extras. Explicit, so allowed.
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
        return PendingIntent.getBroadcast(ctx, sessionId, intent, flags).intentSender
    }

    /** Called by [TestBuildInstallReceiver]. */
    internal fun onInstallStatus(ctx: Context, intent: Intent) {
        val cfg = config ?: return
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        val tag = prefs.getString(K_PENDING_TAG, null) ?: "the test build"
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = confirmIntent(intent)
                if (confirm == null) {
                    cfg.log.w("$tag needs a tap, but Android sent no prompt")
                    clearPending()
                    setPhase(Phase.FAILED, "Android sent no prompt")
                    return
                }
                val how = askForTap(ctx, cfg, confirm, tag)
                cfg.log.i("$tag needs a tap to install " +
                    "(install unknown apps ${if (ctx.packageManager.canRequestPackageInstalls()) "allowed" else "not allowed yet"}): $how")
                setPhase(Phase.WAITING_FOR_TAP)
            }
            PackageInstaller.STATUS_SUCCESS -> {
                // Usually not seen: a self-update ends this process first, and
                // the next start logs "installed" instead.
                cfg.log.i("installed $tag")
                clearPending()
                NotificationManagerCompat.from(ctx).cancel(NOTIFICATION_ID)
                setPhase(Phase.UP_TO_DATE)
            }
            else -> {
                val why = statusName(status) + (message?.let { ": $it" } ?: "")
                cfg.log.w("install of $tag failed: $why")
                val code = prefs.getLong(K_PENDING_CODE, 0L)
                if (TestBuildRules.refusedForGood(status) && code > 0L) {
                    val note = if (status == PackageInstaller.STATUS_FAILURE_CONFLICT) {
                        "$tag is signed with a different key than this build: uninstall ${cfg.appLabel} once and install it by hand"
                    } else {
                        "Android can't install $tag (${statusName(status)})"
                    }
                    prefs.edit().putLong(K_REFUSED_CODE, code).putString(K_REFUSED_WHY, note).apply()
                    cfg.log.w("$note; waiting for a newer test build")
                }
                clearPending()
                setPhase(Phase.FAILED, "install failed: ${statusName(status)}")
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun confirmIntent(intent: Intent): Intent? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
        } else {
            intent.getParcelableExtra(Intent.EXTRA_INTENT)
        }

    /**
     * Shows the system prompt to the user: one notification, or, when
     * notifications are off and a screen of the app is open, the prompt
     * itself. Returns what it did, for the log.
     */
    private fun askForTap(ctx: Context, cfg: Config, confirm: Intent, tag: String): String {
        confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val nm = NotificationManagerCompat.from(ctx)
        val allowed = nm.areNotificationsEnabled() &&
            (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED)
        if (allowed) {
            try {
                val sys = ctx.getSystemService(NotificationManager::class.java)
                sys?.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "Test builds", NotificationManager.IMPORTANCE_DEFAULT),
                )
                val number = TestBuild.testNumber(tag)?.toString() ?: tag
                val open = PendingIntent.getActivity(
                    ctx, NOTIFICATION_ID, confirm,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
                val n = NotificationCompat.Builder(ctx, CHANNEL_ID)
                    .setSmallIcon(cfg.smallIcon)
                    .setContentTitle("${cfg.appLabel} test $number is ready: tap to install")
                    .setContentIntent(open)
                    .setAutoCancel(true)
                    .build()
                nm.notify(NOTIFICATION_ID, n)
                return "notification posted"
            } catch (e: SecurityException) {
                cfg.log.w("couldn't post the notification", e)
            }
        }
        if (resumed > 0) {
            ctx.startActivity(confirm)
            return "notifications are off, so the prompt was opened"
        }
        // Nothing can be shown now: let the next check (the app coming to
        // the front) fetch it again instead of waiting for a tap.
        prefs.edit().putLong(K_PENDING_AT, 0L).apply()
        return "notifications are off; it will ask the next time ${cfg.appLabel} is open"
    }

    private fun statusName(status: Int): String = when (status) {
        PackageInstaller.STATUS_FAILURE -> "failed"
        PackageInstaller.STATUS_FAILURE_BLOCKED -> "blocked"
        PackageInstaller.STATUS_FAILURE_ABORTED -> "cancelled"
        PackageInstaller.STATUS_FAILURE_INVALID -> "invalid APK"
        PackageInstaller.STATUS_FAILURE_CONFLICT -> "conflict (signed with another key?)"
        PackageInstaller.STATUS_FAILURE_STORAGE -> "not enough storage"
        PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> "incompatible"
        else -> "status $status"
    }

    // ---- Bookkeeping -----------------------------------------------------------

    private fun sessionAlive(id: Int): Boolean =
        try {
            app?.packageManager?.packageInstaller?.getSessionInfo(id) != null
        } catch (e: Exception) {
            false
        }

    private fun abandon(id: Int) {
        try {
            app?.packageManager?.packageInstaller?.abandonSession(id)
        } catch (e: Exception) {
            // Already gone.
        }
    }

    private fun clearPending() {
        prefs.edit()
            .remove(K_PENDING_CODE)
            .remove(K_PENDING_TAG)
            .remove(K_PENDING_SESSION)
            .remove(K_PENDING_AT)
            .apply()
    }

    private fun setPhase(phase: Phase, note: String? = null) {
        _state.update { it.copy(phase = phase, note = note) }
    }

    private fun TestBuild.toJson(): String = org.json.JSONObject()
        .put("tag", tag)
        .put("versionCode", versionCode)
        .put("versionName", versionName)
        .put("publishedAt", publishedAt)
        .put("size", size)
        .toString()
}
