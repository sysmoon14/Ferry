package dev.sysmoon.ferry.mqtt

import dev.sysmoon.ferry.Logs
import com.hivemq.client.mqtt.MqttClient
import com.hivemq.client.mqtt.datatypes.MqttQos
import com.hivemq.client.mqtt.mqtt3.Mqtt3AsyncClient
import dev.sysmoon.ferry.AppGraph
import dev.sysmoon.ferry.BuildConfig
import dev.sysmoon.ferry.FerryService
import dev.sysmoon.ferry.net.Network
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
 *  - sensor.<name>_next_match  kick-off of the next match for My teams, with title/deep link attributes
 *  - event.<name>_events       "reminder" (a reminded programme is starting), "sleep" (timer stopped
 *                              playback), "failover" (fell back to another stream), "backstop" (retuned a frozen stream)
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
 *  base/crash          JSON: last crash / caught error (retained)
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
                        .onFailure { Logs.w(TAG, "MQTT session ended: ${it.message}") }
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
                    .collect { client.publish("$base/state", stateJson(it, if (it.channel != null) g.player.stats() else null).toString()) }
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
                // Update entity: what's installed vs the latest GitHub release. Checked every few
                // hours even if nobody opens Ferry, so HA shows when an update is waiting.
                kotlinx.coroutines.flow.combine(g.updater.state, g.updater.latest) { s, latest -> s to latest }
                    .collect { (s, latest) -> client.publish("$base/update", updateJson(s, latest).toString()) }
            }
            launch {
                while (true) { g.updater.checkIfDue(); delay(30 * 60_000L) }
            }
            launch {
                // Last crash / caught error, so HA can show (and alert on) it.
                dev.sysmoon.ferry.CrashGuard.last.collect { r -> client.publish("$base/crash", crashJson(r).toString()) }
            }
            launch {
                g.sleep.slept.collect {
                    client.publish("$base/event", buildJsonObject { put("event_type", "sleep") }.toString(), retain = false)
                }
            }
            launch {
                // Next match for the user's clubs (My teams on the Sports screen), so an automation can
                // turn the TV on and open the finder at kick-off: ferry://football/<id>.
                val tick = kotlinx.coroutines.flow.flow { while (true) { emit(Unit); delay(15 * 60_000L) } }
                kotlinx.coroutines.flow.combine(g.settings.myTeams, g.settings.competitions, tick) { teams, comps, _ -> teams to comps }
                    .collect { (teams, comps) -> client.publish("$base/next_match", nextMatchJson(teams, comps).toString()) }
            }
            launch {
                // Stream trouble as it happens: a fallback to another stream, or the backstop retuning.
                g.player.events.collect { e ->
                    client.publish("$base/event", buildJsonObject {
                        when (e) {
                            is dev.sysmoon.ferry.player.PlayerEvent.Failover -> {
                                put("event_type", "failover"); put("channel", e.channel); put("reason", e.reason)
                                put("from_stream", e.fromStream); put("to_stream", e.toStream); put("streams", e.streamCount)
                            }
                            is dev.sysmoon.ferry.player.PlayerEvent.Backstop -> {
                                put("event_type", "backstop"); put("channel", e.channel); put("reason", e.reason); put("action", e.action)
                            }
                        }
                    }.toString(), retain = false)
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
            // From HA's update entity: download and open the install prompt on the TV.
            "update" -> if (payload == "check") g.updater.check() else g.updater.install()
            else -> Logs.d(TAG, "Unknown command $command")
        }
    }

    // ---------- Discovery

    private fun device(id: String, name: String) = buildJsonObject {
        putJsonArray("identifiers") { add(JsonPrimitive("ferry_$id")) }
        put("name", name)
        put("manufacturer", "Ferry")
        put("model", "Android TV IPTV")
        put("sw_version", BuildConfig.VERSION_NAME)
        // HA shows this as "Visit" on the device page: the box's web setup page.
        Network.localIpv4()?.let { put("configuration_url", "http://$it:${FerryService.PORT}") }
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
        c.publish("$p/sensor/ferry_$id/next_match/config", json(common(id, name, base, "next_match", "Next match") + mapOf(
            "state_topic" to "$base/next_match",
            "value_template" to "{{ value_json.kickoff if value_json.kickoff else None }}",
            "device_class" to "timestamp",
            "json_attributes_topic" to "$base/next_match",
            "json_attributes_template" to "{{ value_json.attributes | tojson }}",
            "icon" to "mdi:soccer",
        )))
        c.publish("$p/update/ferry_$id/ferry/config", json(common(id, name, base, "ferry_update", "Ferry") + mapOf(
            "state_topic" to "$base/update",
            "command_topic" to "$base/cmd/update",
            "payload_install" to "install",
            "entity_category" to "config",
            "icon" to "mdi:update",
        )))
        c.publish("$p/sensor/ferry_$id/last_crash/config", json(common(id, name, base, "last_crash", "Last crash") + mapOf(
            "state_topic" to "$base/crash",
            "value_template" to "{{ value_json.at if value_json.at else None }}",
            "device_class" to "timestamp",
            "json_attributes_topic" to "$base/crash",
            "json_attributes_template" to "{{ value_json.attributes | tojson }}",
            "entity_category" to "diagnostic",
            "icon" to "mdi:alert-octagon-outline",
        )))
        c.publish("$p/event/ferry_$id/events/config", json(common(id, name, base, "events", "Events") + mapOf(
            "state_topic" to "$base/event",
            "event_types" to JsonArray(listOf("reminder", "sleep", "failover", "backstop").map(::JsonPrimitive)),
            "icon" to "mdi:bell",
        )))
    }

    private fun updateJson(s: dev.sysmoon.ferry.update.UpdateState, latest: dev.sysmoon.ferry.update.Release?): JsonObject = buildJsonObject {
        val installed = g.updater.installedVersion
        // A nightly (x.y.z+dev.n) counts as x.y.z, so HA only flags real newer releases.
        val newer = latest?.takeIf { dev.sysmoon.ferry.update.Updater.isNewer(it.version, installed) }
        put("installed_version", installed)
        put("latest_version", newer?.version ?: installed)
        put("title", "Ferry")
        newer?.let {
            put("release_url", it.pageUrl)
            put("release_summary", it.notes.take(250))
        }
        when (s) {
            is dev.sysmoon.ferry.update.UpdateState.Downloading -> { put("in_progress", true); put("update_percentage", s.percent) }
            is dev.sysmoon.ferry.update.UpdateState.Installing -> put("in_progress", true)
            else -> put("in_progress", false)
        }
    }

    private suspend fun nextMatchJson(teams: Set<String>, comps: Set<String>): JsonObject = buildJsonObject {
        val now = System.currentTimeMillis()
        val next = if (teams.isEmpty()) null else runCatching {
            dev.sysmoon.ferry.data.Competition.enabled(comps).flatMap { c ->
                runCatching { g.football.fixtures(c, dev.sysmoon.ferry.data.FootballData.today(), 7) }.getOrDefault(emptyList())
            }.filter { (it.home.id in teams || it.away.id in teams) && it.kickoff + 2 * 3_600_000L > now }
                .minByOrNull { it.kickoff }
        }.getOrNull()
        put("kickoff", next?.let { Instant.ofEpochMilli(it.kickoff).toString() })
        put("attributes", buildJsonObject {
            if (next == null) { put("my_teams", teams.size); return@buildJsonObject }
            put("title", next.title)
            put("competition", next.competition.label)
            put("home", next.home.name)
            put("away", next.away.name)
            put("home_logo", next.home.logo)
            put("away_logo", next.away.logo)
            put("state", next.state.name.lowercase())
            put("score", if (next.homeScore != null) "${next.homeScore}–${next.awayScore}" else null)
            put("deep_link", "ferry://football/${next.id}")
            put("event_id", next.id)
        })
    }

    private fun crashJson(r: dev.sysmoon.ferry.CrashReport?): JsonObject = buildJsonObject {
        put("at", r?.let { Instant.ofEpochMilli(it.at).toString() })
        put("attributes", buildJsonObject {
            if (r == null) return@buildJsonObject
            put("error", r.error)
            put("closed_ferry", r.fatal)
            put("reopened_channel", r.resumed)
            put("thread", r.thread)
            put("version", r.version)
            put("count", r.count)
            // The top of the stack: enough to find the line without flooding HA.
            put("where", r.stack.lineSequence().map { it.trim() }.filter { it.startsWith("at dev.sysmoon") }.take(3).joinToString(" | "))
            // What Ferry was doing just before (its own log; the full log is at /api/debug/log).
            put("log", dev.sysmoon.ferry.Logs.dump(25).take(8_000))
        })
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

    private fun stateJson(s: NowPlaying, stats: dev.sysmoon.ferry.player.PlayerStats?): JsonObject = buildJsonObject {
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
        put("stream", s.stream?.name?.takeIf { s.streamCount > 1 })
        put("streams", s.streamCount)
        put("hdr", s.hdr)
        // Picture quality, so HA can graph dropped frames or flag a stream that fell back to SD.
        put("resolution", stats?.resolution)
        put("frame_rate", stats?.frameRate?.takeIf { it > 0 }?.let { Math.round(it) })
        put("bitrate_kbps", stats?.bitrate?.takeIf { it > 0 }?.div(1000))
        put("video_codec", stats?.videoCodec)
        put("audio_codec", stats?.audioCodec)
        put("dropped_frames", stats?.droppedFrames)
        put("buffer_seconds", stats?.bufferedMs?.div(1000))
        put("tunnelled", stats?.tunnelled)
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
