package dev.sysmoon.ferry.net

import android.content.Context
import android.os.Build
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/** Finds other Ferry boxes on the network (they advertise _ferry._tcp). */
object NsdBrowser {
    data class Found(val name: String, val host: String, val port: Int, val id: String?) {
        /** "http://10.0.0.5:8765", or "http://[2a00:…]:8765" for an IPv6 host. */
        val url: String get() = "http://${urlHost(host)}:$port"
    }

    /** An address as it goes in a URL: IPv6 in brackets, without a zone ("fe80::1%wlan0" → "[fe80::1]"). */
    fun urlHost(address: String): String =
        if (':' in address) "[${address.substringBefore('%')}]" else address

    /** The address to use for a box: IPv4 if it has one, else a routable IPv6, else link-local. */
    fun pickHost(addresses: List<String>): String? =
        addresses.firstOrNull { ':' !in it }
            ?: addresses.firstOrNull { ':' in it && !it.lowercase().startsWith("fe80") }
            ?: addresses.firstOrNull()

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
                val addresses = if (Build.VERSION.SDK_INT >= 34) r.hostAddresses.mapNotNull { it.hostAddress }
                else listOfNotNull(r.host?.hostAddress)
                val host = pickHost(addresses) ?: continue
                val id = r.attributes["id"]?.let { String(it) }
                // One entry per box: a box seen over IPv4 and IPv6 keeps its IPv4 address.
                val key = id ?: r.serviceName
                val prev = found[key]
                if (prev == null || (':' in prev.host && ':' !in host)) found[key] = Found(r.serviceName, host, r.port, id)
            }
        } finally {
            runCatching { nsd.stopServiceDiscovery(listener) }
            delay(50)
        }
        return found.values.toList()
    }
}
