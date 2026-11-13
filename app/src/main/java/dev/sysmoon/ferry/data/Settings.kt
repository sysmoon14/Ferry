package dev.sysmoon.ferry.data

import android.content.Context
import android.os.Build
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import kotlinx.serialization.builtins.serializer
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.security.SecureRandom
import java.util.UUID

private val Context.dataStore by preferencesDataStore("settings")

@kotlinx.serialization.Serializable
data class MqttConfig(
    val enabled: Boolean = false,
    val host: String = "",
    val port: Int = 1883,
    val username: String = "",
    val password: String = "",
    val discoveryPrefix: String = "homeassistant",
    val baseTopic: String = "ferry",
    /** Connect to the broker over TLS (usually port 8883), checked against the TV's trusted certificates. */
    val tls: Boolean = false,
)

/** A phone, Home Assistant or another Ferry that paired with this box. */
data class PairedDevice(val token: String, val label: String, val pairedAt: Long, val lastUsedAt: Long) {
    /** Safe to show and to use in URLs: it doesn't reveal the token. */
    val id: String get() = Settings.tokenId(token)
}

class Settings(private val context: Context) {
    private val store get() = context.dataStore
    private val random = SecureRandom()

    private object Keys {
        val deviceId = stringPreferencesKey("device_id")
        val owner = stringPreferencesKey("owner_android_id")
        val recents = stringPreferencesKey("recent_channels")
        val myTeams = stringSetPreferencesKey("my_teams")
        val competitions = stringSetPreferencesKey("competitions")
        val footballPicks = stringPreferencesKey("football_picks")
        val sportsGuides = booleanPreferencesKey("sports_guides")
        val reminders = stringPreferencesKey("reminders")
        val syncUrl = stringPreferencesKey("sync_url")
        val syncToken = stringPreferencesKey("sync_token")
        val syncName = stringPreferencesKey("sync_name")
        val syncHash = stringPreferencesKey("sync_hash")
        val syncAt = stringPreferencesKey("sync_at")
        val syncError = stringPreferencesKey("sync_error")
        val deviceName = stringPreferencesKey("device_name")
        val pin = stringPreferencesKey("pair_pin")
        val tokens = stringSetPreferencesKey("tokens")
        val mqttTls = booleanPreferencesKey("mqtt_tls")
        val guideComfortable = booleanPreferencesKey("guide_comfortable")
        val recentSearches = stringPreferencesKey("recent_searches")
        val lastChannel = stringPreferencesKey("last_channel")
        val hiddenGroups = stringSetPreferencesKey("hidden_groups")
        val mergeDuplicates = booleanPreferencesKey("merge_duplicates")
        val skyLayout = booleanPreferencesKey("sky_layout")
        val skyRegion = intPreferencesKey("sky_region")
        val skyExtraGroups = stringSetPreferencesKey("sky_extra_groups")
        val skyOverrides = stringSetPreferencesKey("sky_overrides")
        val backupEpgUrls = stringPreferencesKey("backup_epg_urls")
        val resumeLastChannel = booleanPreferencesKey("resume_last_channel")
        val tunnelling = booleanPreferencesKey("tunnelling")
        val zoom = stringPreferencesKey("zoom")
        val idleStopHours = intPreferencesKey("idle_stop_hours")
        val lastSection = stringPreferencesKey("last_section")
        val lastGroup = stringPreferencesKey("last_group")
        val mqttEnabled = booleanPreferencesKey("mqtt_enabled")
        val mqttHost = stringPreferencesKey("mqtt_host")
        val mqttPort = intPreferencesKey("mqtt_port")
        val mqttUser = stringPreferencesKey("mqtt_user")
        val mqttPass = stringPreferencesKey("mqtt_pass")
        val mqttPrefix = stringPreferencesKey("mqtt_prefix")
        val mqttBase = stringPreferencesKey("mqtt_base")
    }

    val mqtt: Flow<MqttConfig> = store.data.map { it.toMqtt() }

    suspend fun mqtt(): MqttConfig = mqtt.first()

    suspend fun setMqtt(cfg: MqttConfig) = store.edit {
        it[Keys.mqttEnabled] = cfg.enabled
        it[Keys.mqttHost] = cfg.host.trim()
        it[Keys.mqttPort] = cfg.port
        it[Keys.mqttUser] = cfg.username
        it[Keys.mqttPass] = cfg.password
        it[Keys.mqttPrefix] = cfg.discoveryPrefix.ifBlank { "homeassistant" }
        it[Keys.mqttBase] = cfg.baseTopic.ifBlank { "ferry" }
        it[Keys.mqttTls] = cfg.tls
    }

    private fun Preferences.toMqtt() = MqttConfig(
        enabled = this[Keys.mqttEnabled] ?: false,
        host = this[Keys.mqttHost] ?: "",
        port = this[Keys.mqttPort] ?: 1883,
        username = this[Keys.mqttUser] ?: "",
        password = this[Keys.mqttPass] ?: "",
        discoveryPrefix = this[Keys.mqttPrefix] ?: "homeassistant",
        baseTopic = this[Keys.mqttBase] ?: "ferry",
        tls = this[Keys.mqttTls] ?: false,
    )

    /**
     * Data copied from another box (adb backup, a cloned install) carries that box's identity, so
     * both would show up in Home Assistant as the same device. When the data was written on a
     * different device, drop the copied identity and pairing so this one gets its own.
     */
    suspend fun claimDevice() {
        val me = android.provider.Settings.Secure.getString(context.contentResolver, android.provider.Settings.Secure.ANDROID_ID)
            ?: return
        store.edit { p ->
            val owner = p[Keys.owner]
            if (owner != null && owner != me) {
                p.remove(Keys.deviceId); p.remove(Keys.deviceName); p.remove(Keys.pin)
                p.remove(Keys.tokens); p.remove(Keys.lastChannel)
            }
            p[Keys.owner] = me
        }
    }

    /** Fill foreign sports channels' listings from other countries' guides (on by default). */
    suspend fun sportsGuides(): Boolean = store.data.first()[Keys.sportsGuides] ?: true
    suspend fun setSportsGuides(on: Boolean) = store.edit { it[Keys.sportsGuides] = on }

    /** Football: ESPN ids of the user's clubs. */
    val myTeams: Flow<Set<String>> = store.data.map { it[Keys.myTeams] ?: emptySet() }
    suspend fun toggleMyTeam(id: String) = store.edit { p ->
        val old = p[Keys.myTeams] ?: emptySet()
        p[Keys.myTeams] = if (id in old) old - id else old + id
    }

    /** Competitions shown on the Sports screen (Competition names); the defaults until set. */
    val competitions: Flow<Set<String>> = store.data.map { it[Keys.competitions] ?: Competition.DEFAULT }
    suspend fun competitions(): Set<String> = competitions.first()
    suspend fun setCompetitions(names: Set<String>) = store.edit { it[Keys.competitions] = names }

    /** Channels that showed each competition before (competition name → channel ids, newest first). */
    suspend fun footballPicks(): Map<String, List<String>> =
        store.data.first()[Keys.footballPicks]?.let { runCatching { json.decodeFromString(picksSerializer, it) }.getOrNull() } ?: emptyMap()
    suspend fun recordFootballPick(competition: String, channelId: String) = store.edit { p ->
        val old = p[Keys.footballPicks]?.let { runCatching { json.decodeFromString(picksSerializer, it) }.getOrNull() } ?: emptyMap()
        val list = (listOf(channelId) + old[competition].orEmpty().filterNot { it == channelId }).take(12)
        p[Keys.footballPicks] = json.encodeToString(picksSerializer, old + (competition to list))
    }

    /** Recently watched channel ids, newest first. */
    val recents: Flow<List<String>> = store.data.map { p -> p[Keys.recents]?.split(',')?.filter { it.isNotEmpty() } ?: emptyList() }
    suspend fun recents(): List<String> = recents.first()
    suspend fun pushRecent(id: String) = store.edit { p ->
        val old = p[Keys.recents]?.split(',')?.filter { it.isNotEmpty() } ?: emptyList()
        p[Keys.recents] = (listOf(id) + (old - id)).take(RECENTS_MAX).joinToString(",")
    }

    /** The last few searches that led somewhere (something was played or a reminder set), newest first. */
    val recentSearches: Flow<List<String>> = store.data.map { p -> p[Keys.recentSearches]?.split('\n')?.filter { it.isNotBlank() } ?: emptyList() }
    suspend fun pushRecentSearch(query: String) = store.edit { p ->
        val q = query.trim().replace('\n', ' ')
        if (q.length < 2) return@edit
        val old = p[Keys.recentSearches]?.split('\n')?.filter { it.isNotBlank() } ?: emptyList()
        p[Keys.recentSearches] = (listOf(q) + old.filterNot { it.equals(q, ignoreCase = true) }).take(8).joinToString("\n")
    }

    val reminders: Flow<List<Reminder>> = store.data.map { p ->
        p[Keys.reminders]?.let { runCatching { json.decodeFromString(reminderList, it) }.getOrNull() } ?: emptyList()
    }
    suspend fun updateReminders(transform: (List<Reminder>) -> List<Reminder>) = store.edit { p ->
        val old = p[Keys.reminders]?.let { runCatching { json.decodeFromString(reminderList, it) }.getOrNull() } ?: emptyList()
        p[Keys.reminders] = json.encodeToString(reminderList, transform(old))
    }

    /** Following another Ferry (Backup & sync → Keep in sync). */
    data class SyncState(val url: String?, val token: String?, val name: String?, val hash: String?, val lastAt: Long, val error: String?)
    suspend fun sync(): SyncState = store.data.first().let { p ->
        SyncState(p[Keys.syncUrl], p[Keys.syncToken], p[Keys.syncName], p[Keys.syncHash], p[Keys.syncAt]?.toLongOrNull() ?: 0, p[Keys.syncError])
    }
    suspend fun setSyncPeer(url: String?, token: String?, name: String?) = store.edit { p ->
        if (url == null) { p.remove(Keys.syncUrl); p.remove(Keys.syncToken); p.remove(Keys.syncName); p.remove(Keys.syncHash); p.remove(Keys.syncError) }
        else { p[Keys.syncUrl] = url; p[Keys.syncToken] = token.orEmpty(); p[Keys.syncName] = name.orEmpty(); p.remove(Keys.syncHash) }
    }
    suspend fun setSyncResult(hash: String?, error: String?) = store.edit { p ->
        p[Keys.syncAt] = System.currentTimeMillis().toString()
        if (hash != null) p[Keys.syncHash] = hash
        if (error != null) p[Keys.syncError] = error else p.remove(Keys.syncError)
    }

    suspend fun setSkyOverrides(overrides: Map<Int, String>) = store.edit { p ->
        p[Keys.skyOverrides] = overrides.map { (n, k) -> "$n=$k" }.toSet()
    }

    /** Short stable id used in MQTT topics and unique_ids. */
    suspend fun deviceId(): String = getOrCreate(Keys.deviceId) { UUID.randomUUID().toString().take(8) }

    val deviceName: Flow<String> = store.data.map { it[Keys.deviceName] ?: defaultName() }
    suspend fun deviceName(): String = deviceName.first()
    suspend fun setDeviceName(name: String) = store.edit { it[Keys.deviceName] = name.trim().ifBlank { defaultName() } }

    val pin: Flow<String?> = store.data.map { it[Keys.pin] }
    suspend fun pin(): String = getOrCreate(Keys.pin) { newPin() }
    suspend fun regeneratePin() = store.edit { it[Keys.pin] = newPin() }

    // Paired devices. Each entry is "token<TAB>pairedAt<TAB>lastUsedAt<TAB>label"; an entry with no
    // tabs is a token from before devices were listed.
    private fun parseDevice(entry: String): PairedDevice {
        val p = entry.split('\t', limit = 4)
        return if (p.size < 4) PairedDevice(p[0], "Paired before devices were listed", 0, 0)
        else PairedDevice(p[0], p[3], p[1].toLongOrNull() ?: 0, p[2].toLongOrNull() ?: 0)
    }
    private fun PairedDevice.encode() = "$token\t$pairedAt\t$lastUsedAt\t${label.replace('\t', ' ')}"

    /** Everything paired with this box, most recently used first. */
    suspend fun pairedDevices(): List<PairedDevice> =
        (store.data.first()[Keys.tokens] ?: emptySet()).map(::parseDevice).sortedByDescending { it.lastUsedAt }

    suspend fun addToken(token: String, label: String) = store.edit {
        val now = System.currentTimeMillis()
        val device = PairedDevice(token, label.trim().take(60).ifBlank { "Unnamed device" }, now, now)
        it[Keys.tokens] = (it[Keys.tokens] ?: emptySet()) + device.encode()
    }

    /**
     * True if [token] is paired. A device that hasn't called for [TOKEN_IDLE_DAYS] days is unpaired
     * instead; each use is noted (at most once an hour, to spare the flash).
     */
    suspend fun useToken(token: String): Boolean {
        val now = System.currentTimeMillis()
        val bytes = token.toByteArray()
        val device = (store.data.first()[Keys.tokens] ?: return false).asSequence().map(::parseDevice)
            .firstOrNull { java.security.MessageDigest.isEqual(it.token.toByteArray(), bytes) } ?: return false
        // Paired before expiry existed (no dates): the clock starts now.
        val last = if (device.lastUsedAt > 0) device.lastUsedAt else now
        if (now - last > TOKEN_IDLE_DAYS * 86_400_000L) { revokeToken(device.id); return false }
        if (now - device.lastUsedAt > 3_600_000L) store.edit { p ->
            p[Keys.tokens] = (p[Keys.tokens] ?: emptySet()).map(::parseDevice).map {
                if (it.token == device.token) it.copy(pairedAt = if (it.pairedAt == 0L) now else it.pairedAt, lastUsedAt = now) else it
            }.map { it.encode() }.toSet()
        }
        return true
    }

    suspend fun revokeToken(id: String) = store.edit { p ->
        p[Keys.tokens] = (p[Keys.tokens] ?: emptySet()).filter { parseDevice(it).id != id }.toSet()
    }
    suspend fun revokeAllTokens() = store.edit { it[Keys.tokens] = emptySet() }

    /** Groups the user has hidden; applied to new channels on every refresh. */
    suspend fun hiddenGroups(): Set<String> = store.data.first()[Keys.hiddenGroups] ?: emptySet()
    suspend fun setHiddenGroups(groups: Set<String>) = store.edit { it[Keys.hiddenGroups] = groups }

    /** Fold backup / other-quality copies of a channel into one row (on by default). */
    suspend fun mergeDuplicates(): Boolean = store.data.first()[Keys.mergeDuplicates] ?: true
    suspend fun setMergeDuplicates(merge: Boolean) = store.edit { it[Keys.mergeDuplicates] = merge }

    /** Sky layout: channels numbered and grouped like a Sky box, plus any provider groups picked as extras. */
    val skyLayout: Flow<Boolean> = store.data.map { it[Keys.skyLayout] ?: false }
    suspend fun skyLayout(): Boolean = skyLayout.first()
    suspend fun skyRegion(): Int = store.data.first()[Keys.skyRegion] ?: SkyLineup.DEFAULT_REGION
    val skyExtraGroups: Flow<Set<String>> = store.data.map { it[Keys.skyExtraGroups] ?: emptySet() }
    suspend fun skyExtraGroups(): Set<String> = skyExtraGroups.first()
    suspend fun setSky(enabled: Boolean, region: Int, extraGroups: Set<String>) = store.edit {
        it[Keys.skyLayout] = enabled
        it[Keys.skyRegion] = region
        it[Keys.skyExtraGroups] = extraGroups
    }

    /** Hand-picked Sky matches: Sky number → the channel's [streamKey]. */
    suspend fun skyOverrides(): Map<Int, String> = (store.data.first()[Keys.skyOverrides] ?: emptySet())
        .mapNotNull { e -> e.split('=', limit = 2).takeIf { it.size == 2 }?.let { (n, k) -> n.toIntOrNull()?.let { it to k } } }
        .toMap()
    suspend fun setSkyOverride(number: Int, streamKey: String?) = store.edit { prefs ->
        val rest = (prefs[Keys.skyOverrides] ?: emptySet()).filterNot { it.startsWith("$number=") }.toSet()
        prefs[Keys.skyOverrides] = if (streamKey == null) rest else rest + "$number=$streamKey"
    }

    /** Extra XMLTV feeds used only for channels the provider's guide doesn't cover. One URL per line. */
    suspend fun backupEpgUrls(): List<String> = (store.data.first()[Keys.backupEpgUrls] ?: "")
        .lines().map { it.trim() }.filter { it.startsWith("http") }
    suspend fun setBackupEpgUrls(urls: List<String>) = store.edit { it[Keys.backupEpgUrls] = urls.joinToString("\n") }

    /** Open straight into the last watched channel when Ferry is launched. */
    /** Tunnelled playback (decoder straight to display). Switched off for good on a box whose audio path can't do it. */
    suspend fun tunnelling(): Boolean = store.data.first()[Keys.tunnelling] ?: true
    suspend fun setTunnelling(on: Boolean) = store.edit { it[Keys.tunnelling] = on }
    suspend fun resumeLastChannel(): Boolean = store.data.first()[Keys.resumeLastChannel] ?: true
    suspend fun setResumeLastChannel(on: Boolean) = store.edit { it[Keys.resumeLastChannel] = on }
    /** Picture zoom mode name (see player Zoom). */
    suspend fun zoom(): String = store.data.first()[Keys.zoom] ?: "FIT"
    suspend fun setZoom(name: String) = store.edit { it[Keys.zoom] = name }
    /** Bigger rows and text in the TV guide, for watching from further away. */
    val guideComfortable: Flow<Boolean> = store.data.map { it[Keys.guideComfortable] ?: false }
    suspend fun setGuideComfortable(on: Boolean) = store.edit { it[Keys.guideComfortable] = on }
    /** Hours of unattended playback before "Still watching?" (0 = never ask). */
    val idleStopHours: Flow<Int> = store.data.map { it[Keys.idleStopHours] ?: DEFAULT_IDLE_STOP_HOURS }
    suspend fun idleStopHours(): Int = idleStopHours.first()
    suspend fun setIdleStopHours(hours: Int) = store.edit { it[Keys.idleStopHours] = hours.coerceIn(0, 24) }
    /** Where the guide was last left: nav rail section and group, restored on the next launch. */
    suspend fun lastPlace(): Pair<String?, String?> = store.data.first().let { it[Keys.lastSection] to it[Keys.lastGroup] }
    suspend fun setLastPlace(section: String?, group: String?) = store.edit {
        if (section == null) it.remove(Keys.lastSection) else it[Keys.lastSection] = section
        if (group == null) it.remove(Keys.lastGroup) else it[Keys.lastGroup] = group
    }

    suspend fun lastChannelId(): String? = store.data.first()[Keys.lastChannel]
    suspend fun setLastChannelId(id: String) = store.edit { it[Keys.lastChannel] = id }

    fun newToken(): String = ByteArray(24).also(random::nextBytes).joinToString("") { "%02x".format(it) }

    private fun newPin() = "%06d".format(random.nextInt(1_000_000))
    /** The name set in Android TV's own settings ("Living Room TV"), falling back to the model. */
    private fun defaultName(): String =
        android.provider.Settings.Global.getString(context.contentResolver, "device_name")
            ?.takeIf { it.isNotBlank() }
            ?: "Ferry ${Build.MODEL}".trim()

    companion object {
        const val DEFAULT_IDLE_STOP_HOURS = 4
        /** A paired device that hasn't called for this long is unpaired. */
        const val TOKEN_IDLE_DAYS = 90L
        /** A short id for a pairing token that doesn't reveal it. */
        fun tokenId(token: String): String =
            java.security.MessageDigest.getInstance("SHA-256").digest(token.toByteArray()).take(6).joinToString("") { "%02x".format(it) }
        private const val RECENTS_MAX = 20
        private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        private val reminderList = kotlinx.serialization.builtins.ListSerializer(Reminder.serializer())
        private val picksSerializer = kotlinx.serialization.builtins.MapSerializer(
            String.serializer(),
            kotlinx.serialization.builtins.ListSerializer(String.serializer()),
        )
    }

    private suspend fun getOrCreate(key: Preferences.Key<String>, create: () -> String): String {
        store.data.first()[key]?.let { return it }
        var value = ""
        store.edit { prefs -> value = prefs[key] ?: create().also { prefs[key] = it } }
        return value
    }
}
