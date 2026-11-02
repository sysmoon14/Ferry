package dev.sysmoon.ferry

import android.app.Application
import android.content.Context
import dev.sysmoon.ferry.data.BbcIplayer
import dev.sysmoon.ferry.data.SkyLineup
import dev.sysmoon.ferry.data.ChannelRepository
import dev.sysmoon.ferry.data.FerryDatabase
import dev.sysmoon.ferry.data.Settings
import dev.sysmoon.ferry.mqtt.MqttBridge
import dev.sysmoon.ferry.player.PlayerController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class FerryApp : Application(), coil.ImageLoaderFactory {
    lateinit var graph: AppGraph
        private set

    /**
     * Channel logos: ten thousand small images. A modest memory cache (8% of the app's heap
     * rather than Coil's default 25%) and a 48 MB disk cache keep logos quick without the
     * decode cache growing into tens of megabytes on a 2 GB TV.
     */
    override fun newImageLoader(): coil.ImageLoader = coil.ImageLoader.Builder(this)
        .memoryCache { coil.memory.MemoryCache.Builder(this).maxSizePercent(0.08).build() }
        .diskCache { coil.disk.DiskCache.Builder().directory(cacheDir.resolve("logos")).maxSizeBytes(48L * 1024 * 1024).build() }
        .respectCacheHeaders(false)
        .crossfade(false)
        .build()

    /** The graph, or null if a crash happens before it's built. */
    val graphOrNull: AppGraph? get() = if (::graph.isInitialized) graph else null

    override fun onCreate() {
        super.onCreate()
        CrashGuard.install(this)
        graph = AppGraph(this)
        CrashGuard.resumeAfterCrash(graph)
    }
}

/** Hand-rolled DI: small enough that Hilt would be overhead. Created on the main thread. */
class AppGraph(val context: Context) {
    /** Background work. An exception that escapes a job is recorded by [CrashGuard], not fatal. */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CrashGuard.handler)
    val db = FerryDatabase.create(context)
    val settings = Settings(context)
    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()
    val bbc = BbcIplayer(http)
    val sky = SkyLineup(http, context.filesDir)
    val repository = ChannelRepository(db, http, settings, bbc, sky, context.cacheDir)
    val player = PlayerController(context, db, settings, scope, bbc)
    val commands = RemoteCommands(this)
    val reminders = dev.sysmoon.ferry.data.ReminderManager(settings, scope)
    val sleep = dev.sysmoon.ferry.player.SleepTimer(scope) { player.stop() }
    val sync = dev.sysmoon.ferry.server.SyncManager(this)
    val football = dev.sysmoon.ferry.data.FootballData(http, context.cacheDir)
    val footballPicks = dev.sysmoon.ferry.data.FootballPicks(settings, player, scope)
    val matchFinder = dev.sysmoon.ferry.data.MatchFinder(db, settings, sky, footballPicks)
    /** True while Ferry's screen is showing (reminder pop-ups only appear then). */
    val uiVisible = kotlinx.coroutines.flow.MutableStateFlow(false)
    val updater = dev.sysmoon.ferry.update.Updater(context, http, scope) { uiVisible.value }
    val mqtt = MqttBridge(this)
}

val Context.graph: AppGraph get() = (applicationContext as FerryApp).graph
