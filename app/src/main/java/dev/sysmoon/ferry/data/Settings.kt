package dev.sysmoon.ferry.data

import android.content.Context
import android.os.Build
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
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
)

class Settings(private val context: Context) {
    private val store get() = context.dataStore
    private val random = SecureRandom()

    private object Keys {
        val deviceId = stringPreferencesKey("device_id")
        val owner = stringPreferencesKey("owner_android_id")
        val deviceName = stringPreferencesKey("device_name")
        val pin = stringPreferencesKey("pair_pin")
        val tokens = stringSetPreferencesKey("tokens")
        val lastChannel = stringPreferencesKey("last_channel")
        val hiddenGroups = stringSetPreferencesKey("hidden_groups")
        val mergeDuplicates = booleanPreferencesKey("merge_duplicates")
        val skyLayout = booleanPreferencesKey("sky_layout")
        val skyRegion = intPreferencesKey("sky_region")
        val skyExtraGroups = stringSetPreferencesKey("sky_extra_groups")
        val skyOverrides = stringSetPreferencesKey("sky_overrides")
        val backupEpgUrls = stringPreferencesKey("backup_epg_urls")
        val resumeLastChannel = booleanPreferencesKey("resume_last_channel")
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
    }

    private fun Preferences.toMqtt() = MqttConfig(
        enabled = this[Keys.mqttEnabled] ?: false,
        host = this[Keys.mqttHost] ?: "",
        port = this[Keys.mqttPort] ?: 1883,
        username = this[Keys.mqttUser] ?: "",
        password = this[Keys.mqttPass] ?: "",
        discoveryPrefix = this[Keys.mqttPrefix] ?: "homeassistant",
        baseTopic = this[Keys.mqttBase] ?: "ferry",
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

    suspend fun tokens(): Set<String> = store.data.first()[Keys.tokens] ?: emptySet()
    suspend fun addToken(token: String) = store.edit { it[Keys.tokens] = (it[Keys.tokens] ?: emptySet()) + token }
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
    suspend fun resumeLastChannel(): Boolean = store.data.first()[Keys.resumeLastChannel] ?: true
    suspend fun setResumeLastChannel(on: Boolean) = store.edit { it[Keys.resumeLastChannel] = on }

    suspend fun lastChannelId(): String? = store.data.first()[Keys.lastChannel]
    suspend fun setLastChannelId(id: String) = store.edit { it[Keys.lastChannel] = id }

    fun newToken(): String = ByteArray(24).also(random::nextBytes).joinToString("") { "%02x".format(it) }

    private fun newPin() = "%06d".format(random.nextInt(1_000_000))
    /** The name set in Android TV's own settings ("Living Room TV"), falling back to the model. */
    private fun defaultName(): String =
        android.provider.Settings.Global.getString(context.contentResolver, "device_name")
            ?.takeIf { it.isNotBlank() }
            ?: "Ferry ${Build.MODEL}".trim()

    private suspend fun getOrCreate(key: Preferences.Key<String>, create: () -> String): String {
        store.data.first()[key]?.let { return it }
        var value = ""
        store.edit { prefs -> value = prefs[key] ?: create().also { prefs[key] = it } }
        return value
    }
}
