package dev.sysmoon.ferry.data

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
)

@Entity(
    tableName = "channels",
    indices = [Index("sourceId"), Index("tvgId"), Index("favourite"), Index("groupTitle")],
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
    // User-owned fields, preserved across refreshes
    val favourite: Boolean = false,
    val hidden: Boolean = false,
    val sortOrder: Int = Int.MAX_VALUE,
)

/** Key used to match XMLTV <programme channel="...">. Extension so Room doesn't try to map it. */
val ChannelEntity.epgKey: String get() = tvgId ?: name

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
