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
        val deviceName = stringPreferencesKey("device_name")
        val pin = stringPreferencesKey("pair_pin")
        val tokens = stringSetPreferencesKey("tokens")
        val lastChannel = stringPreferencesKey("last_channel")
        val hiddenGroups = stringSetPreferencesKey("hidden_groups")
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
