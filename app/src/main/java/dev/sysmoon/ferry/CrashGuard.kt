package dev.sysmoon.ferry

import android.content.Context
import android.content.SharedPreferences
import dev.sysmoon.ferry.Logs
import dev.sysmoon.ferry.player.PlayTarget
import dev.sysmoon.ferry.player.PlaybackState
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** The most recent problem Ferry hit: a crash, or an error it caught and carried on from. */
data class CrashReport(
    val at: Long,
    /** True if it closed Ferry; false if Ferry logged it and kept going. */
    val fatal: Boolean,
    val error: String,
    val thread: String,
    val version: String,
    val stack: String,
    /** Crashes and caught errors since Ferry was installed. */
    val count: Int,
    /** Ferry reopened and went back to the channel after this crash. */
    val resumed: Boolean = false,
)

/**
 * Crash protection.
 *  - A stray exception in background work is logged and recorded instead of closing Ferry
 *    ([handler], on the app scope).
 *  - A real crash is recorded, and if a live channel was on, Ferry reopens and resumes it when
 *    Android restarts the process (the foreground service is sticky), at most twice in 10 minutes
 *    so a crash loop can't keep it cycling.
 *  - The last one is shown in Home Assistant ("Last crash" sensor) and at /api/debug/crash.
 */
object CrashGuard {
    private const val TAG = "FerryCrash"
    private const val PREFS = "ferry_crash"
    private const val RESUME_WITHIN_MS = 2 * 60_000L
    private const val MAX_RESUMES = 2
    private const val RESUME_WINDOW_MS = 10 * 60_000L

    private lateinit var prefs: SharedPreferences
    private val _last = MutableStateFlow<CrashReport?>(null)
    val last: StateFlow<CrashReport?> = _last.asStateFlow()

    /** For the app scope: an exception escaping a background coroutine is recorded, not fatal. */
    val handler = CoroutineExceptionHandler { context, e ->
        Logs.e(TAG, "Caught error in background work ($context)", e)
        record(e, Thread.currentThread().name, fatal = false)
    }

    /** First thing in [FerryApp.onCreate], before the app graph exists. */
    fun install(context: Context) {
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        _last.value = read()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            runCatching {
                record(e, thread.name, fatal = true)
                // Remember what was on so the restarted process can put it back.
                val graph = (context.applicationContext as? FerryApp)?.graphOrNull
                val s = graph?.player?.state?.value
                val live = s?.channel != null && !s.catchup &&
                    (s.state == PlaybackState.PLAYING || s.state == PlaybackState.BUFFERING)
                val now = System.currentTimeMillis()
                val recent = prefs.getString("resumes", "").orEmpty().split(',')
                    .mapNotNull { it.toLongOrNull() }.filter { it > now - RESUME_WINDOW_MS }
                if (live && recent.size >= MAX_RESUMES) Logs.w(TAG, "Crashed ${recent.size + 1} times in 10 minutes: not reopening")
                if (live && recent.size < MAX_RESUMES) {
                    val id = s!!.channel!!.id
                    prefs.edit()
                        .putString("resume_channel", id)
                        .putLong("resume_at", now)
                        .putString("resumes", (recent + now).joinToString(","))
                        .commit() // the process is about to die: write now
                    // Android restarts the service only after a back-off (25 s+), so ask it to open
                    // Ferry on the channel in a couple of seconds instead.
                    reopenSoon(context, id)
                }
            }
            previous?.uncaughtException(thread, e)
        }
    }

    private fun reopenSoon(context: Context, channelId: String) {
        val intent = DeepLinks.playIntent(context, PlayTarget.Id(channelId))
        val pending = android.app.PendingIntent.getActivity(
            context, 7001, intent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
        )
        val alarms = context.getSystemService(android.app.AlarmManager::class.java)
        val at = System.currentTimeMillis() + 2_000
        // Exact alarms need extra permission from Android 12; a normal one is close enough there.
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            alarms.set(android.app.AlarmManager.RTC_WAKEUP, at, pending)
        } else {
            alarms.setExact(android.app.AlarmManager.RTC_WAKEUP, at, pending)
        }
    }

    /**
     * After the graph is up: if Ferry crashed mid-channel moments ago, make sure it's back on that
     * channel (normally the reopen alarm has already done it; this covers it not firing).
     */
    fun resumeAfterCrash(graph: AppGraph) {
        val id = prefs.getString("resume_channel", null) ?: return
        val at = prefs.getLong("resume_at", 0)
        prefs.edit().remove("resume_channel").remove("resume_at").apply()
        if (System.currentTimeMillis() - at > RESUME_WITHIN_MS) return
        graph.scope.launch {
            delay(4_000) // give the reopen alarm's deep link time to start the channel
            if (graph.player.state.value.channel == null) {
                Logs.i(TAG, "Reopening Ferry on the channel that was playing before the crash")
                graph.commands.play(PlayTarget.Id(id))
            } else Logs.i(TAG, "Back on the channel that was playing before the crash")
            _last.value?.let { r ->
                _last.value = r.copy(resumed = true)
                prefs.edit().putBoolean("resumed", true).apply()
            }
        }
    }

    fun record(e: Throwable, thread: String, fatal: Boolean) {
        if (!::prefs.isInitialized) return
        val report = CrashReport(
            at = System.currentTimeMillis(),
            fatal = fatal,
            error = "${e.javaClass.simpleName}: ${e.message.orEmpty()}".take(300),
            thread = thread,
            version = BuildConfig.VERSION_NAME,
            stack = android.util.Log.getStackTraceString(e).take(4_000),
            count = prefs.getInt("count", 0) + 1,
        )
        prefs.edit()
            .putLong("at", report.at)
            .putBoolean("fatal", report.fatal)
            .putString("error", report.error)
            .putString("thread", report.thread)
            .putString("version", report.version)
            .putString("stack", report.stack)
            .putInt("count", report.count)
            .putBoolean("resumed", false)
            .commit()
        _last.value = report
    }

    private fun read(): CrashReport? {
        val at = prefs.getLong("at", 0).takeIf { it > 0 } ?: return null
        return CrashReport(
            at = at,
            fatal = prefs.getBoolean("fatal", true),
            error = prefs.getString("error", "").orEmpty(),
            thread = prefs.getString("thread", "").orEmpty(),
            version = prefs.getString("version", "").orEmpty(),
            stack = prefs.getString("stack", "").orEmpty(),
            count = prefs.getInt("count", 0),
            resumed = prefs.getBoolean("resumed", false),
        )
    }
}
