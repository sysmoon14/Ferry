package dev.sysmoon.ferry.server

import dev.sysmoon.ferry.data.ChannelEntity
import dev.sysmoon.ferry.data.displayName
import dev.sysmoon.ferry.data.archiveDays
import dev.sysmoon.ferry.data.MqttConfig
import dev.sysmoon.ferry.data.SourceEntity
import dev.sysmoon.ferry.data.SourceType
import dev.sysmoon.ferry.player.NowPlaying
import kotlinx.serialization.Serializable

/** [label] names the device being paired ("Ferry: Kitchen TV"); the server falls back to the User-Agent. */
@Serializable data class PairRequest(val pin: String, val label: String? = null)
@Serializable data class PairedDeviceDto(val id: String, val label: String, val pairedAt: Long, val lastUsedAt: Long, val current: Boolean)
@Serializable data class PairResponse(val token: String)
@Serializable data class ErrorDto(val error: String)
@Serializable data class OkDto(val ok: Boolean = true)

/** Credentials are write-only: reads only say whether one is set. */
@Serializable
data class SourceDto(
    val id: Long,
    val name: String,
    val type: SourceType,
    val url: String,
    val username: String?,
    val hasPassword: Boolean,
    val epgUrl: String?,
    val refreshHours: Int,
    val lastRefreshed: Long,
    val lastError: String?,
    val channelCount: Int,
)

fun SourceEntity.toDto() = SourceDto(
    id, name, type, url, username, !password.isNullOrEmpty(), epgUrl, refreshHours, lastRefreshed, lastError, channelCount,
)

@Serializable
data class SourceInput(
    val name: String,
    val type: SourceType = SourceType.M3U,
    val url: String,
    val username: String? = null,
    /** null on update = keep existing password */
    val password: String? = null,
    val epgUrl: String? = null,
    val refreshHours: Int = 12,
)

@Serializable
data class ChannelDto(
    val id: String,
    val name: String,
    val tvgId: String?,
    val logo: String?,
    val group: String?,
    val number: Int?,
    val favourite: Boolean,
    val hidden: Boolean,
    /** Streams folded into this row (1 = no duplicates). */
    val streams: Int = 1,
    val catchupDays: Int = 0,
)

fun ChannelEntity.toDto() = ChannelDto(id, displayName, tvgId, logo, groupTitle, number, favourite, hidden, streamCount, archiveDays)

@Serializable data class OptionsDto(
    val mergeDuplicates: Boolean,
    val resumeLastChannel: Boolean = true,
    val backupEpgUrls: List<String> = emptyList(),
    val sportsGuides: Boolean = true,
    val guideGaps: GuideGapsDto? = null,
    /** Hours of playback with the remote untouched before "Still watching?" (0 = never). */
    val idleStopHours: Int = 4,
    /** Competitions on the Sports screen (names from [allCompetitions]). */
    val competitions: List<String> = emptyList(),
    val allCompetitions: List<CompetitionDto> = emptyList(),
)
@Serializable data class CompetitionDto(val name: String, val label: String)
@Serializable data class GuideGapsDto(val missing: Int, val fromSky: Int, val fromBackup: Int, val errors: List<String>, val at: Long, val fromSportsGuides: Int = 0)
/** Partial update: only the fields sent are changed. */
@Serializable data class OptionsInput(
    val mergeDuplicates: Boolean? = null,
    val resumeLastChannel: Boolean? = null,
    val backupEpgUrls: List<String>? = null,
    val sportsGuides: Boolean? = null,
    val idleStopHours: Int? = null,
    val competitions: List<String>? = null,
)

@Serializable data class SkyRegionDto(val id: Int, val name: String)
@Serializable data class SkyChannelDto(val number: Int, val title: String, val section: String)
@Serializable data class SkyStatusDto(
    val enabled: Boolean,
    val region: Int,
    val regions: List<SkyRegionDto>,
    val extraGroups: List<String>,
    val matched: Int,
    val total: Int,
    val unmatched: List<SkyChannelDto>,
    val error: String?,
    val notes: List<SkyNoteDto> = emptyList(),
)
@Serializable data class SkyNoteDto(val number: Int, val title: String, val channel: String, val how: String)
@Serializable data class SkyOverrideInput(val number: Int, val channelId: String? = null)
@Serializable data class SkyInput(val enabled: Boolean, val region: Int, val extraGroups: List<String> = emptyList())

@Serializable data class ChannelPage(val total: Int, val items: List<ChannelDto>)
@Serializable data class ChannelPatch(val favourite: Boolean? = null, val hidden: Boolean? = null)
@Serializable data class GroupPatch(val group: String, val hidden: Boolean)
@Serializable data class GroupDto(val name: String, val count: Int, val hidden: Boolean)
@Serializable data class VisibleGroupsRequest(val visible: List<String>)
@Serializable data class OrderRequest(val ids: List<String>)

@Serializable
data class MqttDto(
    val enabled: Boolean,
    val host: String,
    val port: Int,
    val username: String,
    val hasPassword: Boolean,
    val discoveryPrefix: String,
    val baseTopic: String,
    val tls: Boolean = false,
)

fun MqttConfig.toDto() = MqttDto(enabled, host, port, username, password.isNotEmpty(), discoveryPrefix, baseTopic, tls)

@Serializable
data class MqttInput(
    val enabled: Boolean,
    val host: String,
    val port: Int = 1883,
    val username: String = "",
    /** null = keep existing */
    val password: String? = null,
    val discoveryPrefix: String = "homeassistant",
    val baseTopic: String = "ferry",
    val tls: Boolean = false,
)

@Serializable data class TestResult(val ok: Boolean, val message: String)

@Serializable data class PlayRequest(
    val channelId: String? = null,
    val tvgId: String? = null,
    val number: Int? = null,
    val name: String? = null,
)
@Serializable data class KeyRequest(val key: String)
@Serializable data class DeviceDto(val name: String)

@Serializable
data class StatusDto(
    val deviceName: String,
    val state: String,
    val channel: ChannelDto?,
    val programme: String?,
    val programmeStart: Long?,
    val programmeEnd: Long?,
    val next: String?,
    val nextStart: Long?,
    val error: String?,
    val catchup: Boolean = false,
    /** Which of the channel's streams is playing and how many it has. */
    val stream: String? = null,
    val streams: Int = 1,
    val hdr: Boolean = false,
    /** What the player reports about the stream (null when nothing is playing). */
    val playback: PlaybackDto? = null,
)

@Serializable
data class PlaybackDto(
    val resolution: String?,
    val frameRate: Float,
    val bitrate: Int,
    val videoCodec: String?,
    val audioCodec: String?,
    val audioChannels: Int,
    val decoder: String?,
    val droppedFrames: Int,
    val bufferedMs: Long,
    val tunnelled: Boolean,
)

fun dev.sysmoon.ferry.player.PlayerStats.toDto() = PlaybackDto(
    resolution, frameRate, bitrate, videoCodec, audioCodec, audioChannels, decoder, droppedFrames, bufferedMs, tunnelled,
)

fun NowPlaying.toStatus(deviceName: String, stats: dev.sysmoon.ferry.player.PlayerStats? = null) = StatusDto(
    deviceName = deviceName,
    state = state.name.lowercase(),
    channel = channel?.toDto(),
    programme = now?.title,
    programmeStart = now?.start,
    programmeEnd = now?.stop,
    next = next?.title,
    nextStart = next?.start,
    error = error,
    catchup = catchup,
    stream = stream?.name,
    streams = streamCount,
    hdr = hdr,
    playback = if (channel != null) stats?.toDto() else null,
)

@Serializable data class BbcRegionDto(val id: String, val label: String)
@Serializable data class BbcCheckDto(val channel: String, val ok: Boolean, val service: String?, val detail: String)
