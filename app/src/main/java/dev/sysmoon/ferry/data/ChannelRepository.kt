package dev.sysmoon.ferry.data

import android.util.Log
import android.util.Xml
import androidx.room.withTransaction
import dev.sysmoon.ferry.parse.M3uEntry
import dev.sysmoon.ferry.parse.M3uParser
import dev.sysmoon.ferry.parse.XmltvParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedInputStream
import java.io.InputStream
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.zip.GZIPInputStream

class ChannelRepository(
    private val db: FerryDatabase,
    private val http: OkHttpClient,
    private val settings: Settings,
    private val bbc: BbcIplayer,
) {
    private val refreshLock = Mutex()
    private val xtream = XtreamClient(http)

    suspend fun addSource(source: SourceEntity): Long = db.sources().insert(source)

    suspend fun deleteSource(id: Long) = db.withTransaction {
        db.channels().deleteForSource(id)
        db.programmes().deleteForSource(id)
        db.sources().delete(id)
    }

    /** Refresh every source whose refresh interval has elapsed (or all, if [force]). */
    suspend fun refreshDue(force: Boolean = false) {
        val now = System.currentTimeMillis()
        db.sources().all()
            .filter { force || now - it.lastRefreshed > it.refreshHours * 3_600_000L }
            .forEach { refresh(it.id) }
        db.programmes().deleteEndedBefore(now - 6 * 3_600_000L)
    }

    suspend fun refresh(sourceId: Long) = refreshLock.withLock {
        withContext(Dispatchers.IO) {
            val src = db.sources().get(sourceId) ?: return@withContext
            try {
                val (playlistUrl, epgUrl) = resolveUrls(src)
                val started = System.currentTimeMillis()
                val count = when (src.type) {
                    SourceType.XTREAM -> storeChannels(
                        src,
                        xtream.liveChannels(
                            XtreamClient.Account(src.url.trimEnd('/'), src.username.orEmpty(), src.password.orEmpty())
                        ).asSequence(),
                    )
                    SourceType.M3U -> refreshChannels(src, playlistUrl)
                    SourceType.BBC -> storeChannels(src, bbc.entries(bbcRegion(src)).asSequence())
                }
                Log.i(TAG, "${src.name}: $count channels in ${System.currentTimeMillis() - started}ms")
                if (src.type == SourceType.BBC) refreshBbcSchedules(src)
                else epgUrl?.let { refreshEpg(src, it) }
                db.sources().update(
                    src.copy(lastRefreshed = System.currentTimeMillis(), lastError = null, channelCount = count)
                )
            } catch (e: Exception) {
                Log.w(TAG, "Refresh failed for ${src.name}", e)
                db.sources().update(src.copy(lastError = e.message ?: e.javaClass.simpleName))
            }
        }
    }

    private suspend fun refreshChannels(src: SourceEntity, url: String): Int =
        open(url).bufferedReader().use { reader ->
            // Xtream-style M3Us also list films and series; Ferry is live TV only.
            storeChannels(src, M3uParser.parse(reader).filterNot { it.isVod() })
        }

    private suspend fun storeChannels(src: SourceEntity, entries: Sequence<M3uEntry>): Int {
        // Keep favourites / hidden / ordering when the provider reshuffles the playlist.
        val existing = db.channels().prefsForSource(src.id).associateBy { it.id }
        val hiddenGroups = settings.hiddenGroups()
        val channels = entries
            .mapIndexed { index, entry -> entry.toChannel(src.id, index, existing, hiddenGroups) }
            .distinctBy { it.id }
            .toList()
        require(channels.isNotEmpty()) { "No live channels found" }
        db.withTransaction {
            db.channels().deleteForSource(src.id)
            channels.chunked(500).forEach { db.channels().upsert(it) }
        }
        return channels.size
    }

    private suspend fun refreshBbcSchedules(src: SourceEntity) {
        val programmes = bbc.schedules(bbcRegion(src), src.id)
        db.programmes().deleteForSource(src.id)
        programmes.chunked(BATCH).forEach { db.programmes().insert(it) }
        Log.i(TAG, "${src.name}: ${programmes.size} BBC schedule entries")
    }

    private fun bbcRegion(src: SourceEntity) =
        src.url.takeIf { it in BbcIplayer.REGIONS } ?: BbcIplayer.DEFAULT_REGION

    private suspend fun refreshEpg(src: SourceEntity, url: String) {
        val keys = db.channels().epgKeys(src.id).toHashSet()
        val cutoff = System.currentTimeMillis() - 3_600_000L
        val batch = ArrayList<ProgrammeEntity>(BATCH)
        db.programmes().deleteForSource(src.id)
        open(url).use { input ->
            XmltvParser.parse(input, Xml.newPullParser(), accept = { it in keys }) { p ->
                if (p.stop < cutoff) return@parse
                batch += ProgrammeEntity(p.channel, p.start, p.stop, p.title, p.description, src.id)
                if (batch.size >= BATCH) {
                    // Parser callback isn't suspending; we're already on Dispatchers.IO.
                    runBlocking { db.programmes().insert(batch.toList()) }
                    batch.clear()
                }
            }
        }
        if (batch.isNotEmpty()) db.programmes().insert(batch)
    }

    private fun open(url: String): InputStream {
        val response = http.newCall(Request.Builder().url(url).header("User-Agent", USER_AGENT).build()).execute()
        if (!response.isSuccessful) {
            response.close()
            error("HTTP ${response.code} from ${redact(url)}")
        }
        val stream = BufferedInputStream(response.body!!.byteStream())
        // Sniff gzip magic bytes: many EPG feeds are .xml.gz without Content-Encoding.
        stream.mark(2)
        val gz = stream.read() == 0x1f && stream.read() == 0x8b
        stream.reset()
        return if (gz) GZIPInputStream(stream) else stream
    }

    companion object {
        private const val TAG = "ChannelRepository"
        private const val BATCH = 2_000
        const val USER_AGENT = "Ferry/0.1 (Android TV)"

        fun resolveUrls(src: SourceEntity): Pair<String, String?> = when (src.type) {
            SourceType.M3U -> src.url to src.epgUrl?.takeIf { it.isNotBlank() }
            SourceType.BBC -> "" to null
            SourceType.XTREAM -> {
                val base = src.url.trimEnd('/')
                val auth = "username=${enc(src.username)}&password=${enc(src.password)}"
                "$base/get.php?$auth&type=m3u_plus&output=ts" to
                    (src.epgUrl?.takeIf { it.isNotBlank() } ?: "$base/xmltv.php?$auth")
            }
        }

        private fun enc(s: String?) = URLEncoder.encode(s.orEmpty(), "UTF-8")

        /** Never log or surface credentials in errors. */
        fun redact(url: String) = url
            .replace(Regex("(username|password)=[^&]*"), "$1=***")
            .replace(Regex("/(live|movie|series)/[^/]+/[^/]+/"), "/$1/***/***/")

        private val VOD_PATH = Regex("/(movie|series)/[^/]+/[^/]+/")
        private val VOD_EXT = Regex("\\.(mkv|mp4|avi|m4v)(\\?.*)?$", RegexOption.IGNORE_CASE)
        fun M3uEntry.isVod() = VOD_PATH.containsMatchIn(url) || VOD_EXT.containsMatchIn(url)

        fun channelId(sourceId: Long, entry: M3uEntry): String {
            val raw = "$sourceId|${entry.tvgId.orEmpty()}|${entry.name}"
            val digest = MessageDigest.getInstance("SHA-1").digest(raw.toByteArray())
            return digest.take(8).joinToString("") { "%02x".format(it) }
        }

        fun M3uEntry.toChannel(
            sourceId: Long,
            index: Int,
            existing: Map<String, ChannelPrefs>,
            hiddenGroups: Set<String> = emptySet(),
        ): ChannelEntity {
            val id = channelId(sourceId, this)
            val prev = existing[id]
            return ChannelEntity(
                id = id,
                sourceId = sourceId,
                name = name,
                tvgId = tvgId,
                logo = logo,
                groupTitle = group,
                streamUrl = url,
                position = index,
                number = number,
                favourite = prev?.favourite ?: false,
                hidden = prev?.hidden ?: (group != null && group in hiddenGroups),
                sortOrder = prev?.sortOrder ?: Int.MAX_VALUE,
            )
        }
    }
}
