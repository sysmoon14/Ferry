package dev.sysmoon.ferry.server

import dev.sysmoon.ferry.data.ChannelEntity
import dev.sysmoon.ferry.data.displayName
import dev.sysmoon.ferry.data.archiveDays
import dev.sysmoon.ferry.data.MqttConfig
import dev.sysmoon.ferry.data.SourceEntity
import dev.sysmoon.ferry.data.SourceType
import dev.sysmoon.ferry.player.NowPlaying
import kotlinx.serialization.Serializable

@Serializable data class PairRequest(val pin: String)
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

@Serializable data class OptionsDto(val mergeDuplicates: Boolean)

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
)

fun MqttConfig.toDto() = MqttDto(enabled, host, port, username, password.isNotEmpty(), discoveryPrefix, baseTopic)

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
)

fun NowPlaying.toStatus(deviceName: String) = StatusDto(
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
)

@Serializable data class BbcRegionDto(val id: String, val label: String)
@Serializable data class BbcCheckDto(val channel: String, val ok: Boolean, val service: String?, val detail: String)
