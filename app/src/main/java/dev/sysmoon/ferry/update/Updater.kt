package dev.sysmoon.ferry.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import dev.sysmoon.ferry.Logs
import dev.sysmoon.ferry.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File

/** A published GitHub release newer than (or the same as) what's installed. */
data class Release(
    val version: String,
    val title: String,
    val notes: String,
    val pageUrl: String,
    val apkUrl: String,
)

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val release: Release) : UpdateState
    data class Downloading(val release: Release, val percent: Int) : UpdateState
    /** The system's install confirmation is on screen (or about to be). */
    data class Installing(val release: Release) : UpdateState
    data class Failed(val message: String, val release: Release? = null) : UpdateState
}

/**
 * Self-update from GitHub releases. Tagging `vX.Y.Z` makes CI publish a release with ferry.apk;
 * Ferry checks the latest release (at launch, at most every few hours, or on demand), offers it,
 * downloads it and hands it to Android's package installer, which asks the viewer to confirm.
 * Every build is signed with the same key, so it installs over the top keeping all settings.
 */
class Updater(
    private val context: Context,
    private val http: OkHttpClient,
    private val scope: CoroutineScope,
    /** Ferry's screen is showing (it may then open the installer). */
    private val uiVisible: () -> Boolean,
) {
    /** Install confirmation Android couldn't show yet (Ferry in the background); shown on next open. */
    @Volatile private var pendingConfirm: Intent? = null
    private var confirmTimeout: kotlinx.coroutines.Job? = null

    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    /** Latest release seen (null until the first successful check). */
    private val _latest = MutableStateFlow<Release?>(null)
    val latest: StateFlow<Release?> = _latest.asStateFlow()

    val installedVersion: String get() = BuildConfig.VERSION_NAME

    private val lock = Mutex()
    private var lastCheck = 0L

    /** Checks unless it did so recently. */
    fun checkIfDue() {
        if (System.currentTimeMillis() - lastCheck < CHECK_EVERY_MS) return
        check()
    }

    fun check() {
        scope.launch {
            lock.withLock {
                val busy = _state.value is UpdateState.Downloading || _state.value is UpdateState.Installing
                if (busy) return@withLock
                _state.value = UpdateState.Checking
                _state.value = runCatching { fetchLatest() }.fold(
                    onSuccess = { r ->
                        lastCheck = System.currentTimeMillis()
                        _latest.value = r
                        if (r != null && isNewer(r.version, installedVersion)) UpdateState.Available(r) else UpdateState.UpToDate
                    },
                    onFailure = {
                        Logs.w(TAG, "Update check failed", it)
                        UpdateState.Failed("couldn't reach GitHub: ${it.message ?: it.javaClass.simpleName}")
                    },
                )
            }
        }
    }

    /** "Later" on the prompt: keep it quiet until the next launch/check. */
    fun dismiss() {
        if (_state.value is UpdateState.Available || _state.value is UpdateState.Failed) _state.value = UpdateState.Idle
    }

    /** Downloads [release] (or the latest found) and opens the system install prompt. */
    fun install(release: Release? = (_state.value as? UpdateState.Available)?.release ?: _latest.value) {
        val r = release ?: return
        scope.launch {
            lock.withLock {
                try {
                    _state.value = UpdateState.Downloading(r, 0)
                    val apk = download(r)
                    _state.value = UpdateState.Installing(r)
                    withContext(Dispatchers.IO) { startInstall(apk) }
                } catch (e: Exception) {
                    Logs.w(TAG, "Update failed", e)
                    _state.value = UpdateState.Failed(e.message ?: e.javaClass.simpleName, r)
                }
            }
        }
    }

    /** Called by [InstallResultReceiver]. */
    internal fun onInstallResult(status: Int, message: String?, confirm: Intent?) {
        val r = (_state.value as? UpdateState.Installing)?.release
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> confirm?.let {
                it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                // Only while Ferry is on screen: with "Display over other apps" granted the installer
                // would otherwise pop up over whatever is being watched (seen over Silo when Home
                // Assistant pressed Install). Held until the next time Ferry opens instead.
                if (!uiVisible()) {
                    pendingConfirm = it
                    _state.value = UpdateState.Failed("open Ferry on the TV to finish installing", r)
                    return
                }
                showConfirm(it, r)
            }
            PackageInstaller.STATUS_SUCCESS -> _state.value = UpdateState.Idle // the process is replaced anyway
            else -> _state.value = UpdateState.Failed(
                if (status == PackageInstaller.STATUS_FAILURE_ABORTED) "the update was cancelled" else (message ?: "install failed ($status)"),
                r,
            )
        }
    }

    /** Called when Ferry's screen opens: shows an install confirmation that was held back. */
    fun resumePendingInstall() {
        val confirm = pendingConfirm ?: return
        pendingConfirm = null
        val r = (_state.value as? UpdateState.Failed)?.release ?: _latest.value ?: return
        _state.value = UpdateState.Installing(r)
        showConfirm(confirm, r)
    }

    private fun showConfirm(confirm: Intent, r: Release?) {
        runCatching { context.startActivity(confirm) }.onFailure { e ->
            _state.value = UpdateState.Failed("couldn't open the installer: ${e.message}", r)
            return
        }
        // Nobody answered (or the prompt never appeared): don't sit "in progress" forever, so the
        // prompt / Home Assistant can offer it again.
        confirmTimeout?.cancel()
        confirmTimeout = scope.launch {
            kotlinx.coroutines.delay(5 * 60_000L)
            if (_state.value is UpdateState.Installing && r != null) _state.value = UpdateState.Available(r)
        }
    }

    private suspend fun fetchLatest(): Release? = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url("https://api.github.com/repos/$REPO/releases/latest")
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "Ferry/${BuildConfig.VERSION_NAME}")
            .build()
        http.newCall(req).execute().use { res ->
            if (res.code == 404) return@withContext null // no releases yet
            check(res.isSuccessful) { "HTTP ${res.code}" }
            parseRelease(res.body!!.string())
        }
    }

    private suspend fun download(r: Release): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "update").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val out = File(dir, "ferry-${r.version}.apk")
        val req = Request.Builder().url(r.apkUrl).header("User-Agent", "Ferry/${BuildConfig.VERSION_NAME}").build()
        http.newCall(req).execute().use { res ->
            check(res.isSuccessful) { "download failed (HTTP ${res.code})" }
            val body = res.body!!
            val total = body.contentLength().takeIf { it > 0 }
            body.byteStream().use { input ->
                out.outputStream().use { output ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    var lastPct = -1
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        done += n
                        val pct = total?.let { (done * 100 / it).toInt() } ?: 0
                        if (pct != lastPct) { lastPct = pct; _state.value = UpdateState.Downloading(r, pct) }
                    }
                }
            }
        }
        out
    }

    private fun startInstall(apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // Silent when Ferry installed the current version itself (Android 12+); otherwise
                // Android asks the viewer to confirm.
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            apk.inputStream().use { input ->
                session.openWrite("ferry.apk", 0, apk.length()).use { out ->
                    input.copyTo(out)
                    session.fsync(out)
                }
            }
            val intent = Intent(context, InstallResultReceiver::class.java)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
            val pending = PendingIntent.getBroadcast(context, id, intent, flags)
            session.commit(pending.intentSender)
        }
    }

    companion object {
        private const val TAG = "FerryUpdate"
        const val REPO = "sysmoon14/Ferry"
        private const val CHECK_EVERY_MS = 30 * 60_000L

        private val json = Json { ignoreUnknownKeys = true }

        /** Parses GitHub's release JSON; null if it has no ferry.apk. */
        fun parseRelease(body: String): Release? {
            val o = json.parseToJsonElement(body).jsonObject
            val tag = o["tag_name"]?.jsonPrimitive?.content ?: return null
            val apk = o["assets"]?.jsonArray?.map { it.jsonObject }
                ?.firstOrNull { it["name"]?.jsonPrimitive?.content == "ferry.apk" }
                ?.get("browser_download_url")?.jsonPrimitive?.content ?: return null
            return Release(
                version = tag.removePrefix("v"),
                title = o["name"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() } ?: tag,
                notes = o["body"]?.jsonPrimitive?.content.orEmpty(),
                pageUrl = o["html_url"]?.jsonPrimitive?.content.orEmpty(),
                apkUrl = apk,
            )
        }

        /**
         * Semantic-version comparison of the numeric core. Build metadata (`+dev.57`, used by
         * nightly builds) and pre-release tags are ignored, so a nightly built after v0.3.0 counts
         * as 0.3.0 and is only offered 0.3.1 or later.
         */
        fun isNewer(candidate: String, installed: String): Boolean {
            fun core(v: String) = v.removePrefix("v").substringBefore('+').substringBefore('-')
                .split('.').map { it.toIntOrNull() ?: 0 }.let { it + List(maxOf(0, 3 - it.size)) { 0 } }
            val a = core(candidate)
            val b = core(installed)
            for (i in 0 until maxOf(a.size, b.size)) {
                val x = a.getOrElse(i) { 0 }
                val y = b.getOrElse(i) { 0 }
                if (x != y) return x > y
            }
            return false
        }
    }
}

/** Receives the package installer's progress for a self-update. */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        @Suppress("DEPRECATION")
        val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
        (context.applicationContext as dev.sysmoon.ferry.FerryApp).graph.updater.onInstallResult(status, message, confirm)
    }
}
