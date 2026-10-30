package dev.sysmoon.ferry

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import dev.sysmoon.ferry.net.NsdAdvertiser
import dev.sysmoon.ferry.server.ConfigServer
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Keeps the config server, MQTT bridge and playlist refresh alive while the TV is on,
 * even when Ferry isn't the foreground app — that's what makes HA control work.
 */
class FerryService : Service() {
    private lateinit var server: ConfigServer
    private lateinit var nsd: NsdAdvertiser
    private var refreshJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        startInForeground()
        val g = graph
        server = ConfigServer(g).also { it.start(PORT) }
        nsd = NsdAdvertiser(this)
        g.scope.launch {
            g.settings.pin() // make sure a pairing PIN exists before anyone opens Settings
            nsd.register(g.settings.deviceName(), PORT, g.settings.deviceId())
        }
        g.mqtt.start()
        refreshJob = g.scope.launch {
            while (isActive) {
                runCatching { g.repository.refreshDue() }.onFailure { Log.w(TAG, "refresh loop", it) }
                delay(30 * 60_000L)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        refreshJob?.cancel()
        graph.mqtt.stop()
        nsd.unregister()
        server.stop()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startInForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.service_channel), NotificationManager.IMPORTANCE_MIN)
        )
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.service_running))
            .setOngoing(true)
            .build()
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
    }

    companion object {
        const val PORT = 8765
        private const val CHANNEL = "service"
        private const val NOTIFICATION_ID = 1
        private const val TAG = "FerryService"

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, FerryService::class.java))
        }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) FerryService.start(context)
    }
}
