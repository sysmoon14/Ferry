package dev.sysmoon.ferry.mqtt

import android.util.Log
import com.hivemq.client.mqtt.MqttClient
import com.hivemq.client.mqtt.datatypes.MqttQos
import com.hivemq.client.mqtt.mqtt3.Mqtt3AsyncClient
import dev.sysmoon.ferry.AppGraph
import dev.sysmoon.ferry.BuildConfig
import dev.sysmoon.ferry.data.MqttConfig
import dev.sysmoon.ferry.player.NowPlaying
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.future.await
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.time.Instant

/**
 * Home Assistant bridge over MQTT discovery. Creates one HA device per TV with:
 *  - select.<name>_channel     favourites; choosing one tunes the TV
 *  - sensor.<name>_state       idle / buffering / playing / paused / error
 *  - sensor.<name>_channel_name, _programme, _next_programme, _progress  (+ JSON attributes)
 *  - button.<name>_channel_up / _channel_down / _stop / _pause / _previous_channel / _live
 *  - image.<name>_channel_logo, number.<name>_sleep_timer, sensor.<name>_next_reminder
 *  - event.<name>_events       "reminder" (a reminded programme is starting) and "sleep" (timer stopped playback)
 *
 * Topics (base = <baseTopic>/<deviceId>):
 *  base/availability   online | offline (LWT, retained)
 *  base/state          JSON status (retained)
 *  base/cmd/channel    favourite name (from the select)
 *  base/cmd/play       "tvg:BBCOne.uk" | "id:<channelId>" | "number:101" | "101" | channel name
 *  base/cmd/key        up | down | stop | pause | previous_channel | live
 *  base/cmd/sleep      minutes (0 = off)
 *  base/logo           current channel's logo URL (retained)
 *  base/reminders      JSON: next reminder + list (retained)
 *  base/event          JSON events (not retained)
 */
class MqttBridge(private val g: AppGraph) {
    private var job: Job? = null

    fun start() {
        if (job?.isActive == true) return
        job = g.scope.launch {
            g.settings.mqtt.distinctUntilChanged().collectLatest { cfg ->
                if (!cfg.enabled || cfg.host.isBlank()) return@collectLatest
                // Reconnect loop: collectLatest cancels this when settings change.
                while (true) {
                    runCatching { runConnected(cfg) }
                        .onFailure { Log.w(TAG, "MQTT session ended: ${it.message}") }
                    delay(15_000)
                }
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    private suspend fun runConnected(cfg: MqttConfig): Unit = coroutineScope {
        val id = g.settings.deviceId()
        val name = g.settings.deviceName()
        val base = "${cfg.baseTopic}/$id"
        val client = buildClient(cfg, id)

        connect(client, cfg, availabilityTopic = "$base/availability")
        try {
            client.publish("$base/availability", "online")

            client.subscribeWith().topicFilter("$base/cmd/#").qos(MqttQos.AT_LEAST_ONCE)
                .callback { msg ->
                    val payload = String(msg.payloadAsBytes).trim()
                    val topic = msg.topic.toString()
                    g.scope.launch { handleCommand(topic.substringAfter("$base/cmd/"), payload) }
                }
                .send().await()

            // Static entities once; the select is re-published whenever favourites change.
            publishStaticDiscovery(client, cfg, id, name, base)
            publishExtras(client, cfg, id, name, base)
            launch {
                g.db.channels().observeFavourites()
                    .map { favs -> favs.map { it.name }.distinct() }
                    .distinctUntilChanged()
                    .collect { names -> publishSelect(client, cfg, id, name, base, names) }
            }
            launch {
                // Re-published every minute too, so the sleep timer's minutes count down in HA.
                val minutes = kotlinx.coroutines.flow.flow { while (true) { emit(Unit); delay(60_000) } }
                kotlinx.coroutines.flow.combine(g.player.state, g.sleep.endsAt, minutes) { st, _, _ -> st }
                    .collect { client.publish("$base/state", stateJson(it).toString()) }
            }
            launch {
                g.player.state.map { it.channel?.logo }.distinctUntilChanged().collect { logo ->
                    if (!logo.isNullOrBlank()) client.publish("$base/logo", logo)
                }
            }
            launch {
                g.reminders.reminders.collect { client.publish("$base/reminders", remindersJson(it).toString()) }
            }
            launch {
                g.reminders.fired.collect { r ->
                    client.publish("$base/event", buildJsonObject {
                        put("event_type", "reminder")
                        put("title", r.title)
                        put("channel", r.channelName)
                        put("channel_id", r.channelId)
                        put("start", Instant.ofEpochMilli(r.start).toString())
                    }.toString(), retain = false)
                }
            }
            launch {
                g.sleep.slept.collect {
                    client.publish("$base/event", buildJsonObject { put("event_type", "sleep") }.toString(), retain = false)
                }
            }
            awaitCancellation()
        } finally {
            runCatching {
                client.publish("$base/availability", "offline")
                client.disconnect().await()
            }
        }
    }

    private suspend fun handleCommand(command: String, payload: String) {
        when (command) {
            "channel" -> if (payload != NO_FAVOURITES) g.commands.playPayload(payload)
            "play" -> g.commands.playPayload(payload)
            "key" -> g.commands.key(payload)
            "sleep" -> payload.toFloatOrNull()?.let { g.sleep.set(it.toInt()) }
            else -> Log.d(TAG, "Unknown command $command")
        }
    }

    // ---------- Discovery

    private fun device(id: String, name: String) = buildJsonObject {
        putJsonArray("identifiers") { add(JsonPrimitive("ferry_$id")) }
        put("name", name)
        put("manufacturer", "Ferry")
        put("model", "Android TV IPTV")
        put("sw_version", BuildConfig.VERSION_NAME)
    }

    private fun common(id: String, name: String, base: String, key: String, label: String) = buildMap<String, Any?> {
        put("name", label)
        put("unique_id", "ferry_${id}_$key")
        put("object_id", "${slug(name)}_$key")
        put("availability_topic", "$base/availability")
        put("device", device(id, name))
    }

    private suspend fun publishStaticDiscovery(c: Mqtt3AsyncClient, cfg: MqttConfig, id: String, name: String, base: String) {
        val p = cfg.discoveryPrefix
        val sensors = listOf(
            Triple("state", "State", "{{ value_json.state }}"),
            Triple("channel_name", "Channel", "{{ value_json.channel or 'None' }}"),
            Triple("programme", "Programme", "{{ value_json.programme or 'None' }}"),
            Triple("next_programme", "Next programme", "{{ value_json.next or 'None' }}"),
        )
        for ((key, label, template) in sensors) {
            c.publish("$p/sensor/ferry_$id/$key/config", json(common(id, name, base, key, label) + mapOf(
                "state_topic" to "$base/state",
                "value_template" to template,
                "json_attributes_topic" to "$base/state",
                "icon" to "mdi:television-classic",
            )))
        }
        c.publish("$p/sensor/ferry_$id/progress/config", json(common(id, name, base, "progress", "Programme progress") + mapOf(
            "state_topic" to "$base/state",
            "value_template" to "{{ value_json.progress if value_json.progress is not none else 'unknown' }}",
            "unit_of_measurement" to "%",
            "icon" to "mdi:progress-clock",
        )))
        for ((key, label, cmd) in listOf(
            Triple("channel_up", "Channel up", "up"),
            Triple("channel_down", "Channel down", "down"),
            Triple("stop", "Stop", "stop"),
            Triple("pause", "Pause / play", "pause"),
            Triple("previous_channel", "Previous channel", "previous_channel"),
            Triple("live", "Back to live", "live"),
        )) {
            c.publish("$p/button/ferry_$id/$key/config", json(common(id, name, base, key, label) + mapOf(
                "command_topic" to "$base/cmd/key",
                "payload_press" to cmd,
            )))
        }
    }

    private suspend fun publishExtras(c: Mqtt3AsyncClient, cfg: MqttConfig, id: String, name: String, base: String) {
        val p = cfg.discoveryPrefix
        c.publish("$p/image/ferry_$id/channel_logo/config", json(common(id, name, base, "channel_logo", "Channel logo") + mapOf(
            "url_topic" to "$base/logo",
        )))
        c.publish("$p/number/ferry_$id/sleep_timer/config", json(common(id, name, base, "sleep_timer", "Sleep timer") + mapOf(
            "command_topic" to "$base/cmd/sleep",
            "state_topic" to "$base/state",
            "value_template" to "{{ value_json.sleep_minutes or 0 }}",
            "min" to 0, "max" to 180, "step" to 15,
            "unit_of_measurement" to "min",
            "mode" to "slider",
            "icon" to "mdi:sleep",
        )))
        c.publish("$p/sensor/ferry_$id/next_reminder/config", json(common(id, name, base, "next_reminder", "Next reminder") + mapOf(
            "state_topic" to "$base/reminders",
            "value_template" to "{{ value_json.next or 'None' }}",
            "json_attributes_topic" to "$base/reminders",
            "icon" to "mdi:bell-ring-outline",
        )))
        c.publish("$p/event/ferry_$id/events/config", json(common(id, name, base, "events", "Events") + mapOf(
            "state_topic" to "$base/event",
            "event_types" to JsonArray(listOf("reminder", "sleep").map(::JsonPrimitive)),
            "icon" to "mdi:bell",
        )))
    }

    private fun remindersJson(list: List<dev.sysmoon.ferry.data.Reminder>): JsonObject = buildJsonObject {
        val next = list.minByOrNull { it.start }
        val time = java.time.format.DateTimeFormatter.ofPattern("EEE HH:mm").withZone(java.time.ZoneId.systemDefault())
        put("next", next?.let { "${it.title} (${time.format(Instant.ofEpochMilli(it.start))}, ${it.channelName})" })
        put("next_start", next?.start?.let { Instant.ofEpochMilli(it).toString() })
        put("count", list.size)
        putJsonArray("reminders") {
            list.sortedBy { it.start }.forEach { r ->
                add(buildJsonObject {
                    put("title", r.title); put("channel", r.channelName); put("channel_id", r.channelId)
                    put("start", Instant.ofEpochMilli(r.start).toString())
                })
            }
        }
    }

    private suspend fun publishSelect(
        c: Mqtt3AsyncClient, cfg: MqttConfig, id: String, name: String, base: String, favourites: List<String>,
    ) {
        // HA requires at least one option; show a hint until favourites exist.
        val options = favourites.ifEmpty { listOf(NO_FAVOURITES) }
        c.publish("${cfg.discoveryPrefix}/select/ferry_$id/channel/config", json(common(id, name, base, "channel", "Favourite channel") + mapOf(
            "command_topic" to "$base/cmd/channel",
            "state_topic" to "$base/state",
            // Only favourites are options; anything else reports "None" (HA's reset payload) to avoid warnings.
            "value_template" to "{{ value_json.favourite or 'None' }}",
            "options" to JsonArray(options.map(::JsonPrimitive)),
            "icon" to "mdi:remote-tv",
        )))
    }

    private fun stateJson(s: NowPlaying): JsonObject = buildJsonObject {
        put("state", s.state.name.lowercase())
        put("channel", s.channel?.name)
        put("favourite", s.channel?.takeIf { it.favourite }?.name)
        put("channel_id", s.channel?.id)
        put("tvg_id", s.channel?.tvgId)
        put("logo", s.channel?.logo)
        put("group", s.channel?.groupTitle)
        put("programme", s.now?.title)
        put("description", s.now?.description)
        put("programme_start", s.now?.start?.let { Instant.ofEpochMilli(it).toString() })
        put("programme_end", s.now?.stop?.let { Instant.ofEpochMilli(it).toString() })
        put("next", s.next?.title)
        put("next_start", s.next?.start?.let { Instant.ofEpochMilli(it).toString() })
        put("progress", s.progress?.let { (it * 100).toInt() })
        put("error", s.error)
        put("catchup", s.catchup)
        put("sleep_minutes", g.sleep.minutesLeft())
        putJsonObject("deep_link") {
            s.channel?.let { ch -> put("url", ch.tvgId?.let { "ferry://tvg/$it" } ?: "ferry://channel/${ch.id}") }
        }
    }

    private fun json(map: Map<String, Any?>): String = JsonObject(map.mapValues { (_, v) ->
        when (v) {
            null -> kotlinx.serialization.json.JsonNull
            is kotlinx.serialization.json.JsonElement -> v
            is Number -> JsonPrimitive(v)
            is Boolean -> JsonPrimitive(v)
            else -> JsonPrimitive(v.toString())
        }
    }).toString()

    companion object {
        private const val TAG = "MqttBridge"
        const val NO_FAVOURITES = "(add favourites in Ferry)"

        private fun slug(s: String) = s.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_').ifEmpty { "ferry" }

        private fun buildClient(cfg: MqttConfig, id: String): Mqtt3AsyncClient =
            MqttClient.builder()
                .useMqttVersion3()
                .identifier("ferry-$id-${System.currentTimeMillis() % 10_000}")
                .serverHost(cfg.host)
                .serverPort(cfg.port)
                .buildAsync()

        private suspend fun connect(client: Mqtt3AsyncClient, cfg: MqttConfig, availabilityTopic: String?) {
            val builder = client.connectWith().cleanSession(true).keepAlive(30)
            if (cfg.username.isNotBlank()) {
                builder.simpleAuth().username(cfg.username).password(cfg.password.toByteArray()).applySimpleAuth()
            }
            if (availabilityTopic != null) {
                builder.willPublish().topic(availabilityTopic).payload("offline".toByteArray())
                    .qos(MqttQos.AT_LEAST_ONCE).retain(true).applyWillPublish()
            }
            withTimeout(10_000) { builder.send().await() }
        }

        private suspend fun Mqtt3AsyncClient.publish(topic: String, payload: String, retain: Boolean = true) {
            publishWith().topic(topic).payload(payload.toByteArray()).qos(MqttQos.AT_LEAST_ONCE).retain(retain)
                .send().await()
        }

        /** Used by the web UI's "Test connection" button. */
        suspend fun test(cfg: MqttConfig): Result<Unit> = runCatching {
            require(cfg.host.isNotBlank()) { "Host is required" }
            val client = buildClient(cfg, "test")
            connect(client, cfg, availabilityTopic = null)
            client.disconnect().await()
        }
    }
}
