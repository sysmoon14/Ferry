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
    private val sky: SkyLineup,
    private val cacheDir: java.io.File,
) {
    private val refreshLock = Mutex()
    private val xtream = XtreamClient(http)

    data class SkyReport(
        val region: Int,
        val total: Int,
        val matched: Int,
        val unmatched: List<SkyLineup.SkyChannel>,
        val error: String? = null,
        /** Matches found by comparing schedules or picked by hand (412 SkySp NFL HD → Sky Sports Action). */
        val notes: List<SkyNote> = emptyList(),
    )
    data class SkyNote(val number: Int, val title: String, val channel: String, val how: String)

    /** Outcome of the last Sky layout pass, for the web page. */
    @Volatile var skyReport: SkyReport? = null
        private set

    /**
     * Sky layout: number and group matched channels like a Sky box. Runs after every refresh
     * (refreshing rebuilds channel rows) and whenever the Sky settings or visible groups change.
     */
    suspend fun applySky() = withContext(Dispatchers.IO) {
        if (!settings.skyLayout()) {
            db.channels().clearSky()
            skyReport = null
            return@withContext
        }
        val region = settings.skyRegion()
        val lineup = try {
            sky.channels(region)
        } catch (e: Exception) {
            Log.w(TAG, "Sky line-up unavailable", e)
            skyReport = SkyReport(region, 0, 0, emptyList(), e.message ?: e.javaClass.simpleName)
            return@withContext
        }
        val rows = db.channels().skyCandidates()
        val notes = mutableListOf<SkyNote>()

        // 1. Hand-picked matches win, keyed by stream so they survive provider renames.
        val rowByKey = rows.associateBy { streamKey(it.sourceId, it.streamUrl) }
        val manual = settings.skyOverrides().mapNotNull { (number, key) ->
            val sky = lineup.firstOrNull { it.number == number } ?: return@mapNotNull null
            val row = rowByKey[key] ?: return@mapNotNull null
            notes += SkyNote(sky.number, sky.title, row.name, "picked")
            SkyMatcher.Match(row.id, sky)
        }
        val manualIds = manual.map { it.channelId }.toSet()
        val manualNumbers = manual.map { it.sky.number }.toSet()

        // 2. Names and guide IDs.
        val candidates = rows.filter { it.id !in manualIds }
            .map { SkyMatcher.Candidate(it.id, it.name, it.tvgId, it.groupTitle, it.quality, it.position) }
        val byName = SkyMatcher.match(lineup.filter { it.number !in manualNumbers }, candidates, region)

        // 3. Schedules, for renames the playlist hasn't caught up with.
        val usedIds = manualIds + byName.matches.map { it.channelId }
        val bySchedule = runCatching { matchBySchedule(byName.unmatched, rows.filter { it.id !in usedIds }) }
            .onFailure { Log.w(TAG, "Sky schedule matching failed", it) }
            .getOrDefault(emptyList())
        val nameById = rows.associate { it.id to it.name }
        bySchedule.forEach { notes += SkyNote(it.sky.number, it.sky.title, nameById[it.channelId].orEmpty(), "schedule") }

        val matches = manual + byName.matches + bySchedule
        val unmatched = byName.unmatched.filter { u -> bySchedule.none { it.sky.number == u.number } }
        db.withTransaction {
            db.channels().clearSky()
            matches.forEach { db.channels().setSky(it.channelId, it.sky.number, it.sky.section) }
        }
        val total = matches.size + unmatched.size
        skyReport = SkyReport(region, total, matches.size, unmatched, notes = notes)
        Log.i(TAG, "Sky layout: matched ${matches.size} of $total for region $region (${bySchedule.size} by schedule)")
    }

    /** Compares Sky's schedule for unmatched Sky channels with the playlist guide (next ~10 hours). */
    private suspend fun matchBySchedule(
        unmatched: List<SkyLineup.SkyChannel>,
        rows: List<SkyCandidateRow>,
    ): List<SkyMatcher.Match> {
        val uk = rows.filter {
            it.tvgId?.lowercase()?.endsWith(".uk") == true || it.groupTitle?.startsWith("UK", ignoreCase = true) == true
        }
        if (unmatched.isEmpty() || uk.isEmpty()) return emptyList()
        val now = System.currentTimeMillis()
        val from = now - 2 * 3_600_000L
        val until = now + 10 * 3_600_000L
        val today = java.time.LocalDate.now(java.time.ZoneId.of("Europe/London"))
        val sids = unmatched.map { it.sid }.filter { it.isNotBlank() }
        if (sids.isEmpty()) return emptyList()
        val schedules = HashMap<String, MutableList<SkyLineup.SkyEvent>>()
        for (day in listOf(today, today.plusDays(1))) {
            sky.schedules(sids, day).forEach { (sid, events) -> schedules.getOrPut(sid) { mutableListOf() } += events }
        }
        val idsByKey = uk.groupBy({ it.tvgId ?: it.name }, { it.id })
        val guide = idsByKey.keys.toList().chunked(500).flatMap { db.programmes().window(it, from, until) }
            .flatMap { p -> idsByKey[p.channelKey].orEmpty().map { id -> SkyMatcher.GuideEntry(id, p.start, p.title) } }
        return SkyMatcher.matchBySchedule(unmatched, schedules, guide, from, until)
    }

    data class GapReport(
        val missing: Int,
        val fromSky: Int,
        val fromBackup: Int,
        val errors: List<String> = emptyList(),
        /** Sports channels (hidden ones included) filled from other countries' guides. */
        val fromSportsGuides: Int = 0,
        val at: Long = System.currentTimeMillis(),
    )

    /** Outcome of the last guide gap fill, for the web page. */
    @Volatile var gapReport: GapReport? = null
        private set

    /**
     * Backup guide data for channels the provider's guide doesn't cover (next 6 hours empty):
     * Sky's own schedule for channels the Sky layout matched, then any backup XMLTV feeds,
     * matched by guide ID or channel name. Never overrides the provider's listings.
     */
    suspend fun fillGuideGaps() = withContext(Dispatchers.IO) {
        db.programmes().deleteForSource(SKY_GUIDE_SOURCE)
        db.programmes().deleteForSource(BACKUP_GUIDE_SOURCE)
        db.programmes().deleteForSource(SPORTS_GUIDE_SOURCE)
        val now = System.currentTimeMillis()
        val covered = db.programmes().coveredKeys(now, now + 6 * 3_600_000L).toHashSet()
        val missing = db.channels().guideRows().filter { it.epgKey !in covered }.distinctBy { it.epgKey }
        val filled = HashSet<String>()
        val errors = mutableListOf<String>()

        // 1. Sky's schedule, for Sky-matched channels.
        if (settings.skyLayout()) runCatching {
            val sidByNumber = sky.channels(settings.skyRegion()).associate { it.number to it.sid }
            val wanted = missing.mapNotNull { r -> r.skyNumber?.let { sidByNumber[it] }?.takeIf { it.isNotBlank() }?.let { it to r.epgKey } }
            if (wanted.isNotEmpty()) {
                val today = java.time.LocalDate.now(java.time.ZoneId.of("Europe/London"))
                val events = HashMap<String, MutableList<SkyLineup.SkyEvent>>()
                for (day in listOf(today, today.plusDays(1))) {
                    sky.schedules(wanted.map { it.first }, day).forEach { (sid, e) -> events.getOrPut(sid) { mutableListOf() } += e }
                }
                val programmes = wanted.flatMap { (sid, key) ->
                    val list = events[sid].orEmpty().sortedBy { it.start }.distinctBy { it.start }
                    list.mapIndexedNotNull { i, e ->
                        val stop = if (e.durationMs > 0) e.start + e.durationMs else list.getOrNull(i + 1)?.start ?: (e.start + 1_800_000)
                        if (stop < now - 3_600_000L) null
                        else ProgrammeEntity(key, e.start, stop, e.title, e.synopsis, SKY_GUIDE_SOURCE)
                    }
                }
                programmes.chunked(BATCH).forEach { db.programmes().insert(it) }
                filled += programmes.map { it.channelKey }
            }
        }.onFailure { errors += "Sky schedule: ${it.message ?: it.javaClass.simpleName}" }
        val fromSky = filled.size

        // 2. Backup XMLTV feeds, for whatever is still empty.
        for (url in settings.backupEpgUrls()) {
            val left = missing.filter { it.epgKey !in filled }
            if (left.isEmpty()) break
            val byTvg = left.filter { it.tvgId != null }.groupBy({ it.tvgId!!.lowercase() }, { it.epgKey })
            val byName = left.groupBy({ SkyMatcher.canon(it.name) }, { it.epgKey })
            val claimed = HashSet<String>()
            val idMap = HashMap<String, List<String>>()
            val batch = ArrayList<ProgrammeEntity>(BATCH)
            runCatching {
                cachedFeed(url).use { input ->
                    XmltvParser.parse(input, Xml.newPullParser(), accept = { it in idMap },
                        onChannel = { id, names ->
                            val keys = (byTvg[id.lowercase()] ?: names.firstNotNullOfOrNull { byName[SkyMatcher.canon(it)] })
                                ?.filter { it !in claimed }
                            if (!keys.isNullOrEmpty()) { idMap[id] = keys; claimed += keys }
                        },
                    ) { p ->
                        if (p.stop < now - 3_600_000L) return@parse
                        idMap[p.channel]?.forEach { key ->
                            batch += ProgrammeEntity(key, p.start, p.stop, p.title, p.description, BACKUP_GUIDE_SOURCE)
                            filled += key
                        }
                        if (batch.size >= BATCH) { runBlocking { db.programmes().insert(batch.toList()) }; batch.clear() }
                    }
                }
                if (batch.isNotEmpty()) db.programmes().insert(batch)
            }.onFailure { errors += "${redact(url)}: ${it.message ?: it.javaClass.simpleName}" }
        }
        val fromBackup = filled.size - fromSky
        val fromSports = if (settings.sportsGuides()) fillSportsGuides(covered, filled, now, errors) else 0
        gapReport = GapReport(missing.size, fromSky, fromBackup, errors, fromSportsGuides = fromSports)
        Log.i(TAG, "Guide gaps: ${missing.size} empty, Sky filled $fromSky, backup feeds $fromBackup, sports guides $fromSports")
    }

    /**
     * Sports channels without listings, hidden groups included, from other countries' guides
     * (beIN's own feed, then up to [MAX_COUNTRY_FEEDS] countries: football-rights markets first).
     * Only the next two days are kept, to keep the database small.
     */
    private suspend fun fillSportsGuides(covered: Set<String>, alreadyFilled: Set<String>, now: Long, errors: MutableList<String>): Int {
        data class Row(val key: String, val tvg: String?, val name: String, val country: String?)
        val rows = db.channels().allPrimaries()
            .filter { it.epgKey !in covered && it.epgKey !in alreadyFilled && TeamNames.isSportsChannel(it) }
            .map { Row(it.epgKey, it.tvgId, it.name, SportsGuides.country(it.name, it.groupTitle, it.tvgId)) }
            .distinctBy { it.key }
        if (rows.isEmpty()) return 0
        // Countries that have unguided sports channels, football-rights markets first, then by count.
        val counts = rows.mapNotNull { it.country }.groupingBy { it }.eachCount()
        val feeds = listOf(SportsGuides.BEIN) + counts.keys.sortedWith(
            compareBy<String> { SportsGuides.FOOTBALL_PRIORITY.indexOf(it).let { i -> if (i < 0) 999 else i } }
                .thenByDescending { counts[it] }
        ).take(MAX_COUNTRY_FEEDS)
        val filled = HashSet<String>()
        // epgshare01's file names change now and then (US1 went away): read its listing (daily).
        val index = runCatching {
            cachedFeed(SportsGuides.INDEX_URL, maxAgeMs = 24 * 3_600_000L).use { SportsGuides.parseIndex(it.reader().readText()) }
        }.getOrNull()?.takeIf { it.isNotEmpty() }
        for (code in feeds) for (file in SportsGuides.filesFor(code, index)) {
            val left = rows.filter { it.key !in filled && (code == SportsGuides.BEIN || it.country == code) }
            if (left.isEmpty()) continue
            val byId = left.filter { it.tvg != null }.groupBy({ SportsGuides.idKey(it.tvg!!) }, { it })
            val byName = left.groupBy({ SportsGuides.nameKey(it.name) }, { it })
            val claimed = HashSet<String>()
            val idMap = HashMap<String, List<String>>()
            val batch = ArrayList<ProgrammeEntity>(BATCH)
            runCatching {
                cachedFeed(SportsGuides.fileUrl(file)).use { input ->
                    XmltvParser.parse(input, Xml.newPullParser(), accept = { it in idMap },
                        onChannel = { id, names ->
                            // Same-named channels exist in many countries (beIN Sports 1 FR/US/MENA…).
                            val feedCountry = SportsGuides.feedCountry(id) ?: code.takeIf { it != SportsGuides.BEIN }
                            val matches = (byId[SportsGuides.idKey(id)]
                                ?: names.firstNotNullOfOrNull { n -> byName[SportsGuides.nameKey(n)] }
                                    ?.filter { r -> r.country == null || feedCountry == null || r.country == feedCountry })
                                ?.map { it.key }?.filter { it !in claimed }
                            if (!matches.isNullOrEmpty()) { idMap[id] = matches; claimed += matches }
                        },
                    ) { p ->
                        if (p.stop < now - 6 * 3_600_000L || p.start > now + 2 * 86_400_000L) return@parse
                        idMap[p.channel]?.forEach { key ->
                            batch += ProgrammeEntity(key, p.start, p.stop, p.title, p.description, SPORTS_GUIDE_SOURCE)
                            filled += key
                        }
                        if (batch.size >= BATCH) { runBlocking { db.programmes().insert(batch.toList()) }; batch.clear() }
                    }
                }
                if (batch.isNotEmpty()) db.programmes().insert(batch)
            }.onFailure { errors += "Sports guide $file: ${it.message ?: it.javaClass.simpleName}" }
        }
        return filled.size
    }

    /** Backup feeds are big and change slowly: download at most every 6 hours. */
    private fun cachedFeed(url: String, maxAgeMs: Long = 6 * 3_600_000L): InputStream {
        val file = java.io.File(cacheDir, "backup_epg_" + Integer.toHexString(url.hashCode()))
        if (!file.exists() || System.currentTimeMillis() - file.lastModified() > maxAgeMs) {
            val response = http.newCall(Request.Builder().url(url).header("User-Agent", USER_AGENT).build()).execute()
            response.use {
                if (!it.isSuccessful) error("HTTP ${it.code}")
                val tmp = java.io.File(file.path + ".part")
                tmp.outputStream().use { out -> it.body!!.byteStream().copyTo(out) }
                tmp.renameTo(file)
            }
        }
        return gunzipIfNeeded(BufferedInputStream(file.inputStream()))
    }

    suspend fun addSource(source: SourceEntity): Long = db.sources().insert(source)

    private val seededPrefs = java.util.concurrent.ConcurrentHashMap<Long, List<ChannelPrefs>>()

    /** Favourites / hidden / order to apply on [sourceId]'s next refresh (settings import). */
    fun seedPrefs(sourceId: Long, prefs: List<ChannelPrefs>) { seededPrefs[sourceId] = prefs }

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
        db.programmes().deleteEndedBefore(now - 8 * 86_400_000L) // catch-up archive window
    }

    suspend fun refresh(sourceId: Long) = refreshLock.withLock {
        withContext(Dispatchers.IO) {
            val src = db.sources().get(sourceId) ?: return@withContext
            try {
                val (playlistUrl, epgUrl) = resolveUrls(src)
                val started = System.currentTimeMillis()
                var timezone = src.serverTimezone
                val count = when (src.type) {
                    SourceType.XTREAM -> {
                        val live = xtream.liveChannels(
                            XtreamClient.Account(src.url.trimEnd('/'), src.username.orEmpty(), src.password.orEmpty())
                        )
                        timezone = live.timezone ?: timezone
                        storeChannels(src, live.entries.asSequence())
                    }
                    SourceType.M3U -> refreshChannels(src, playlistUrl)
                    SourceType.BBC -> storeChannels(src, bbc.entries(bbcRegion(src)).asSequence())
                }
                Log.i(TAG, "${src.name}: $count channels in ${System.currentTimeMillis() - started}ms")
                if (src.type == SourceType.BBC) refreshBbcSchedules(src)
                else epgUrl?.let { refreshEpg(src, it) }
                db.sources().update(
                    src.copy(
                        lastRefreshed = System.currentTimeMillis(), lastError = null, channelCount = count,
                        serverTimezone = timezone,
                    )
                )
            } catch (e: Exception) {
                Log.w(TAG, "Refresh failed for ${src.name}", e)
                db.sources().update(src.copy(lastError = e.message ?: e.javaClass.simpleName))
            }
            runCatching { applySky() }.onFailure { Log.w(TAG, "Sky layout failed", it) }
            runCatching { fillGuideGaps() }.onFailure { Log.w(TAG, "Guide gap fill failed", it) }
        }
    }

    private suspend fun refreshChannels(src: SourceEntity, url: String): Int =
        open(url).bufferedReader().use { reader ->
            // Xtream-style M3Us also list films and series; Ferry is live TV only.
            storeChannels(src, M3uParser.parse(reader).filterNot { it.isVod() })
        }

    private suspend fun storeChannels(src: SourceEntity, entries: Sequence<M3uEntry>): Int {
        // Keep favourites / hidden / ordering when the provider reshuffles the playlist. After an
        // import, the imported choices replace this device's own.
        val prefs = seededPrefs.remove(src.id) ?: db.channels().prefsForSource(src.id)
        val existing = prefs.associateBy { it.id }
        // A renamed channel gets a new id (it hashes the name); find its prefs by stream instead.
        val existingByStream = prefs.associateBy { streamKey(src.id, it.streamUrl) }
        val hiddenGroups = settings.hiddenGroups()
        val parsed = entries
            .mapIndexed { index, entry -> entry.toChannel(src.id, index, existing, hiddenGroups, existingByStream) }
            .distinctBy { it.id }
            .toList()
        require(parsed.isNotEmpty()) { "No live channels found" }
        val channels = Duplicates.merge(parsed, settings.mergeDuplicates())
        Log.i(TAG, "${src.name}: ${channels.count { it.primaryId != null }} duplicate streams folded")
        db.withTransaction {
            db.channels().deleteForSource(src.id)
            channels.chunked(500).forEach { db.channels().upsert(it) }
        }
        return channels.count { it.primaryId == null }
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
        val now = System.currentTimeMillis()
        val cutoff = now - 3_600_000L
        // Channels with catch-up keep their past programmes for the archive window (max 7 days).
        val archive = db.channels().catchupKeys(src.id)
            .associate { it.epgKey to now - it.days.coerceAtMost(7) * 86_400_000L }
        val batch = ArrayList<ProgrammeEntity>(BATCH)
        db.programmes().deleteForSource(src.id)
        open(url).use { input ->
            XmltvParser.parse(input, Xml.newPullParser(), accept = { it in keys }) { p ->
                if (p.stop < (archive[p.channel] ?: cutoff)) return@parse
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
        return gunzipIfNeeded(BufferedInputStream(response.body!!.byteStream()))
    }

    /** Sniff gzip magic bytes: many EPG feeds are .xml.gz without Content-Encoding. */
    private fun gunzipIfNeeded(stream: BufferedInputStream): InputStream {
        stream.mark(2)
        val gz = stream.read() == 0x1f && stream.read() == 0x8b
        stream.reset()
        return if (gz) GZIPInputStream(stream) else stream
    }

    companion object {
        private const val TAG = "ChannelRepository"
        private const val BATCH = 2_000
        /** Pseudo source ids for backup guide data, so each fill can replace its own rows. */
        const val SKY_GUIDE_SOURCE = -1L
        const val BACKUP_GUIDE_SOURCE = -2L
        /** Other countries' sports guides (epgshare01). */
        const val SPORTS_GUIDE_SOURCE = -3L
        private const val MAX_COUNTRY_FEEDS = 14
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

        fun channelId(sourceId: Long, entry: M3uEntry): String = channelId(sourceId, entry.tvgId, entry.name)

        fun channelId(sourceId: Long, tvgId: String?, name: String): String {
            val raw = "$sourceId|${tvgId.orEmpty()}|$name"
            val digest = MessageDigest.getInstance("SHA-1").digest(raw.toByteArray())
            return digest.take(8).joinToString("") { "%02x".format(it) }
        }

        fun M3uEntry.toChannel(
            sourceId: Long,
            index: Int,
            existing: Map<String, ChannelPrefs>,
            hiddenGroups: Set<String> = emptySet(),
            existingByStream: Map<String, ChannelPrefs> = emptyMap(),
        ): ChannelEntity {
            val id = channelId(sourceId, this)
            val prev = existing[id] ?: existingByStream[streamKey(sourceId, url)]
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
                catchupDays = catchupDays ?: 0,
                favourite = prev?.favourite ?: false,
                hidden = prev?.hidden ?: (group != null && group in hiddenGroups),
                sortOrder = prev?.sortOrder ?: Int.MAX_VALUE,
            )
        }
    }
}
