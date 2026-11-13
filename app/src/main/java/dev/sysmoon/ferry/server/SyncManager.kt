package dev.sysmoon.ferry.server

import dev.sysmoon.ferry.Logs
import dev.sysmoon.ferry.AppGraph
import dev.sysmoon.ferry.net.NsdBrowser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.security.MessageDigest

@Serializable data class SyncStatusDto(val following: Boolean, val name: String?, val url: String?, val lastSync: Long, val lastError: String?)
@Serializable data class PeerDto(val name: String, val url: String)
@Serializable data class FollowRequest(val url: String, val pin: String)

/**
 * "Keep in sync": this box follows another Ferry, pulling its export and applying it whenever it
 * changes (checked every 15 minutes, and on demand). One-way: changes made here are overwritten.
 */
class SyncManager(private val g: AppGraph) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val lock = Mutex()

    suspend fun status(): SyncStatusDto {
        val s = g.settings.sync()
        return SyncStatusDto(s.url != null, s.name, s.url, s.lastAt, s.error)
    }

    /** Other Ferry boxes on the network, excluding this one. */
    suspend fun peers(): List<PeerDto> {
        val me = g.settings.deviceId()
        return NsdBrowser.discover(g.context)
            .filter { it.id != me }
            .map { PeerDto(it.name, "http://${it.host}:${it.port}") }
    }

    /** Pairs with [url] using its on-screen PIN, then syncs straight away. */
    suspend fun follow(url: String, pin: String): SyncStatusDto = withContext(Dispatchers.IO) {
        val base = normalise(url)
        val me = dev.sysmoon.ferry.net.Network.localIpv4()
        require(listOfNotNull(me, "127.0.0.1", "localhost").none { base.contains("://$it:") }) { "That's this box — open the web page of the box you're setting up instead" }
        val text = kotlinx.serialization.serializer<String>()
        val pairBody = """{"pin":${json.encodeToString(text, pin.trim())},"label":${json.encodeToString(text, "Ferry: " + g.settings.deviceName())}}"""
        val token = call(Request.Builder().url("$base/api/pair").post(pairBody.toRequestBody(JSON)).build())
            .let { json.parseToJsonElement(it).jsonObject["token"]?.jsonPrimitive?.content }
            ?: error("That Ferry didn't accept the PIN")
        val name = runCatching {
            json.parseToJsonElement(call(authed("$base/api/status", token))).jsonObject["deviceName"]?.jsonPrimitive?.content
        }.getOrNull() ?: base
        g.settings.setSyncPeer(base, token, name)
        syncNow()
        status()
    }

    suspend fun stop(): SyncStatusDto { g.settings.setSyncPeer(null, null, null); return status() }

    /** Pulls the leader's settings and applies them if anything changed. */
    suspend fun syncNow(): SyncStatusDto = lock.withLock {
        val s = g.settings.sync()
        val url = s.url ?: return status()
        try {
            val body = withContext(Dispatchers.IO) { call(authed("$url/api/export?secrets=1", s.token.orEmpty())) }
            val export = json.decodeFromString(FerryExport.serializer(), body)
            val hash = fingerprint(export)
            if (hash != s.hash) {
                Logs.i(TAG, "Settings changed on ${s.name}: applying")
                Backup.import(g, export)
            }
            g.settings.setSyncResult(hash, null)
        } catch (e: Exception) {
            Logs.w(TAG, "Sync from $url failed", e)
            g.settings.setSyncResult(null, e.message ?: e.javaClass.simpleName)
        }
        status()
    }

    /**
     * Content hash that ignores when/where the export was made and the leader's own source ids,
     * so an unchanged setup never triggers a re-import.
     */
    private fun fingerprint(e: FerryExport): String {
        val index = e.sources.mapIndexed { i, src -> src.ref to i.toLong() }.toMap()
        fun remapKey(k: String) = "${index[k.substringBefore(':').toLongOrNull()]}:${k.substringAfter(':')}"
        val normal = e.copy(
            exportedAt = 0, from = "",
            sources = e.sources.map { it.copy(ref = index.getValue(it.ref)) },
            channels = e.channels.map { it.copy(source = index[it.source] ?: -1) }
                .sortedWith(compareBy({ it.source }, { it.name }, { it.streamUrl })),
            settings = e.settings.copy(skyOverrides = e.settings.skyOverrides.mapValues { remapKey(it.value) }.toSortedMap()),
        )
        val bytes = MessageDigest.getInstance("SHA-1").digest(json.encodeToString(FerryExport.serializer(), normal).toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun authed(url: String, token: String) = Request.Builder().url(url)
        .header("Authorization", "Bearer $token").header("User-Agent", "Ferry/${dev.sysmoon.ferry.BuildConfig.VERSION_NAME}").build()

    private fun call(request: Request): String = g.http.newCall(request).execute().use { r ->
        val text = r.body?.string().orEmpty()
        if (r.code == 401) error("The other Ferry no longer accepts this box — follow it again with its PIN")
        if (!r.isSuccessful) error(runCatching { json.parseToJsonElement(text).jsonObject["error"]?.jsonPrimitive?.content }.getOrNull() ?: "HTTP ${r.code}")
        text
    }

    private fun normalise(url: String): String {
        var u = url.trim().trimEnd('/')
        if (!u.startsWith("http://") && !u.startsWith("https://")) u = "http://$u"
        if (Regex("""^https?://[^/:]+$""").matches(u)) u += ":${dev.sysmoon.ferry.FerryService.PORT}"
        return u
    }

    private companion object {
        const val TAG = "FerrySync"
        val JSON = "application/json".toMediaType()
    }
}
