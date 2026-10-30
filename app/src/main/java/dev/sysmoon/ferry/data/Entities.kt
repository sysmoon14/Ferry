package dev.sysmoon.ferry.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

enum class SourceType { M3U, XTREAM, BBC }

@Entity(tableName = "sources")
data class SourceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val type: SourceType,
    /** M3U: playlist URL. Xtream: server base URL (http://host:port). BBC: BBC One region service id. */
    val url: String,
    val username: String? = null,
    val password: String? = null,
    /** Optional override; Xtream sources default to the provider's xmltv.php. */
    val epgUrl: String? = null,
    val refreshHours: Int = 12,
    val lastRefreshed: Long = 0,
    val lastError: String? = null,
    val channelCount: Int = 0,
    /** Xtream server time zone (from server_info); catch-up start times are in this zone. */
    val serverTimezone: String? = null,
)

@Entity(
    tableName = "channels",
    indices = [Index("sourceId"), Index("tvgId"), Index("favourite"), Index("groupTitle"), Index("primaryId")],
)
data class ChannelEntity(
    /** Stable across refreshes: hash of source + tvg-id + name (URLs often carry rotating tokens). */
    @PrimaryKey val id: String,
    val sourceId: Long,
    val name: String,
    val tvgId: String?,
    val logo: String?,
    val groupTitle: String?,
    val streamUrl: String,
    /** Position in the provider playlist. */
    val position: Int,
    /** Provider channel number (tvg-chno) if supplied. */
    val number: Int?,
    /** Days of catch-up the provider keeps for this channel (0 = none). */
    @ColumnInfo(defaultValue = "0") val catchupDays: Int = 0,
    /** Set on a duplicate (backup / other quality) of another channel: the id of the row shown in lists. */
    val primaryId: String? = null,
    /** Stream quality score from [StreamQuality]; higher plays first. */
    @ColumnInfo(defaultValue = "0") val quality: Int = 0,
    /** On a primary: how many streams the row has (itself + duplicates). */
    @ColumnInfo(defaultValue = "1") val streamCount: Int = 1,
    /** On a primary: the longest catch-up any of its streams has (it may not be the best stream). */
    @ColumnInfo(defaultValue = "0") val rowCatchupDays: Int = 0,
    // User-owned fields, preserved across refreshes
    val favourite: Boolean = false,
    val hidden: Boolean = false,
    val sortOrder: Int = Int.MAX_VALUE,
)

/** Key used to match XMLTV <programme channel="...">. Extension so Room doesn't try to map it. */
val ChannelEntity.epgKey: String get() = tvgId ?: name

/** Days of catch-up this row offers, from whichever of its streams has an archive. */
val ChannelEntity.archiveDays: Int get() = maxOf(catchupDays, rowCatchupDays)

/** Name for lists: merged rows drop the quality tags that only described one of their streams. */
val ChannelEntity.displayName: String get() = if (streamCount > 1) StreamQuality.displayName(name) else name

@Entity(
    tableName = "programmes",
    primaryKeys = ["channelKey", "start"],
    indices = [Index("stop"), Index("sourceId")],
)
data class ProgrammeEntity(
    val channelKey: String,
    val start: Long,
    val stop: Long,
    val title: String,
    val description: String?,
    val sourceId: Long,
)
