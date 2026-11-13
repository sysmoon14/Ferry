package dev.sysmoon.ferry.server

import dev.sysmoon.ferry.AppGraph
import dev.sysmoon.ferry.data.ChannelPrefs
import dev.sysmoon.ferry.data.ChannelRepository
import dev.sysmoon.ferry.data.MqttConfig
import dev.sysmoon.ferry.data.SourceEntity
import dev.sysmoon.ferry.data.SourceType
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

/**
 * Everything needed to set up another Ferry the same way: playlists (with logins), options, Sky
 * layout, group visibility and per-channel favourites / hidden / order. Device identity, pairing
 * and the guide data itself are left out — the other box downloads its own guide.
 */
@Serializable
data class FerryExport(
    val ferryExport: Int = 1,
    /** False when playlist logins and the MQTT password were left out. */
    val includesPasswords: Boolean = true,
    val exportedAt: Long = 0,
    val from: String = "",
    val sources: List<ExportSource> = emptyList(),
    val settings: ExportSettings = ExportSettings(),
    val channels: List<ExportChannel> = emptyList(),
)

@Serializable
data class ExportSource(
    /** This source's id on the exporting device; channels and Sky picks refer to it. */
    val ref: Long,
    val name: String,
    val type: SourceType,
    val url: String,
    val username: String? = null,
    val password: String? = null,
    val epgUrl: String? = null,
    val refreshHours: Int = 12,
)

@Serializable
data class ExportSettings(
    val hiddenGroups: List<String> = emptyList(),
    val mergeDuplicates: Boolean = true,
    val skyLayout: Boolean = false,
    val skyRegion: Int = 45,
    val skyExtraGroups: List<String> = emptyList(),
    /** Sky number → stream key ("<source ref>:x<stream id>"). */
    val skyOverrides: Map<String, String> = emptyMap(),
    val backupEpgUrls: List<String> = emptyList(),
    val resumeLastChannel: Boolean = true,
    val mqtt: MqttConfig? = null,
)

@Serializable
data class ExportChannel(
    val source: Long,
    val name: String,
    val tvgId: String? = null,
    val streamUrl: String,
    val favourite: Boolean = false,
    val hidden: Boolean = false,
    val sortOrder: Int = Int.MAX_VALUE,
)

@Serializable
data class ImportResult(val sources: Int, val channels: Int, val removedSources: Int)

object Backup {
    /** [secrets]: include playlist logins and the MQTT password (a Ferry that follows this one needs them). */
    suspend fun export(g: AppGraph, secrets: Boolean): FerryExport {
        val s = g.settings
        val hiddenGroups = s.hiddenGroups()
        // Only choices that differ from what the group settings would give anyway.
        val channels = g.db.channels().userPrefs()
            .filter { it.favourite || it.sortOrder != Int.MAX_VALUE || it.hidden != (it.groupTitle != null && it.groupTitle in hiddenGroups) }
            .map { ExportChannel(it.sourceId, it.name, it.tvgId, it.streamUrl, it.favourite, it.hidden, it.sortOrder) }
        return FerryExport(
            includesPasswords = secrets,
            exportedAt = System.currentTimeMillis(),
            from = s.deviceName(),
            sources = g.db.sources().all().map {
                ExportSource(it.id, it.name, it.type, it.url, it.username, it.password.takeIf { secrets }, it.epgUrl, it.refreshHours)
            },
            settings = ExportSettings(
                hiddenGroups = hiddenGroups.sorted(),
                mergeDuplicates = s.mergeDuplicates(),
                skyLayout = s.skyLayout(),
                skyRegion = s.skyRegion(),
                skyExtraGroups = s.skyExtraGroups().sorted(),
                skyOverrides = s.skyOverrides().mapKeys { it.key.toString() },
                backupEpgUrls = s.backupEpgUrls(),
                resumeLastChannel = s.resumeLastChannel(),
                mqtt = s.mqtt().let { if (secrets) it else it.copy(password = "") },
            ),
            channels = channels,
        )
    }

    /** Makes this device match [e]. Playlists already here (same server and login) are kept, so they don't re-download from scratch. */
    suspend fun import(g: AppGraph, e: FerryExport): ImportResult {
        require(e.ferryExport == 1) { "Unsupported export version ${e.ferryExport}" }
        val existing = g.db.sources().all()
        val idFor = HashMap<Long, Long>()
        for (src in e.sources) {
            val match = existing.firstOrNull {
                it.id !in idFor.values && it.type == src.type &&
                    it.url.trimEnd('/') == src.url.trimEnd('/') && it.username.orEmpty() == src.username.orEmpty()
            }
            idFor[src.ref] = if (match != null) {
                g.db.sources().update(
                    match.copy(
                        name = src.name, password = src.password ?: match.password,
                        epgUrl = src.epgUrl, refreshHours = src.refreshHours,
                    )
                )
                match.id
            } else {
                g.repository.addSource(
                    SourceEntity(
                        name = src.name, type = src.type, url = src.url, username = src.username,
                        password = src.password, epgUrl = src.epgUrl, refreshHours = src.refreshHours,
                    )
                )
            }
        }
        val removed = existing.filter { it.id !in idFor.values }
        removed.forEach { g.repository.deleteSource(it.id) }

        val s = g.settings
        with(e.settings) {
            s.setHiddenGroups(hiddenGroups.toSet())
            s.setMergeDuplicates(mergeDuplicates)
            s.setSky(skyLayout, skyRegion, skyExtraGroups.toSet())
            s.setSkyOverrides(skyOverrides.mapNotNull { (n, key) ->
                val number = n.toIntOrNull() ?: return@mapNotNull null
                val newId = idFor[key.substringBefore(':').toLongOrNull()] ?: return@mapNotNull null
                number to "$newId:${key.substringAfter(':')}"
            }.toMap())
            s.setBackupEpgUrls(backupEpgUrls)
            s.setResumeLastChannel(resumeLastChannel)
            // An export without passwords keeps the MQTT password already here.
            mqtt?.let { s.setMqtt(if (it.password.isEmpty()) it.copy(password = s.mqtt().password) else it) }
        }

        val byRef = e.channels.groupBy { it.source }
        idFor.forEach { (ref, id) ->
            g.repository.seedPrefs(id, byRef[ref].orEmpty().map {
                ChannelPrefs(ChannelRepository.channelId(id, it.tvgId, it.name), it.favourite, it.hidden, it.sortOrder, it.streamUrl)
            })
        }
        // Re-import every playlist so the imported choices, merging and Sky layout all apply.
        val ids = idFor.values.toList()
        g.scope.launch { ids.forEach { g.repository.refresh(it) } }
        return ImportResult(ids.size, e.channels.size, removed.size)
    }
}
