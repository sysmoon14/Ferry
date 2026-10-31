package dev.sysmoon.ferry.net

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/** Finds other Ferry boxes on the network (they advertise _ferry._tcp). */
object NsdBrowser {
    data class Found(val name: String, val host: String, val port: Int, val id: String?)

    @Suppress("DEPRECATION")
    suspend fun discover(context: Context, timeoutMs: Long = 4_000): List<Found> {
        val nsd = context.getSystemService(NsdManager::class.java)
        val queue = Channel<NsdServiceInfo>(Channel.UNLIMITED)
        val listener = object : NsdManager.DiscoveryListener {
            override fun onServiceFound(info: NsdServiceInfo) { queue.trySend(info) }
            override fun onServiceLost(info: NsdServiceInfo) = Unit
            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) { queue.close() }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
        }
        runCatching { nsd.discoverServices(NsdAdvertiser.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener) }
            .onFailure { return emptyList() }
        val found = LinkedHashMap<String, Found>()
        val deadline = System.currentTimeMillis() + timeoutMs
        try {
            while (System.currentTimeMillis() < deadline) {
                val info = withTimeoutOrNull(deadline - System.currentTimeMillis()) { queue.receiveCatching().getOrNull() } ?: break
                // Resolve one at a time: older Android rejects concurrent resolves.
                val resolved = CompletableDeferred<NsdServiceInfo?>()
                runCatching {
                    nsd.resolveService(info, object : NsdManager.ResolveListener {
                        override fun onServiceResolved(i: NsdServiceInfo) { resolved.complete(i) }
                        override fun onResolveFailed(i: NsdServiceInfo, errorCode: Int) { resolved.complete(null) }
                    })
                }.onFailure { resolved.complete(null) }
                val r = withTimeoutOrNull(2_500) { resolved.await() } ?: continue
                val host = r.host?.hostAddress ?: continue
                val id = r.attributes["id"]?.let { String(it) }
                found["$host:${r.port}"] = Found(r.serviceName, host, r.port, id)
            }
        } finally {
            runCatching { nsd.stopServiceDiscovery(listener) }
            delay(50)
        }
        return found.values.toList()
    }
}
