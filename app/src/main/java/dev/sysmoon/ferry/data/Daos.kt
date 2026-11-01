package dev.sysmoon.ferry.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface SourceDao {
    @Query("SELECT * FROM sources ORDER BY id")
    fun observeAll(): Flow<List<SourceEntity>>

    @Query("SELECT * FROM sources ORDER BY id")
    suspend fun all(): List<SourceEntity>

    @Query("SELECT * FROM sources WHERE id = :id")
    suspend fun get(id: Long): SourceEntity?

    @Insert
    suspend fun insert(source: SourceEntity): Long

    @Update
    suspend fun update(source: SourceEntity)

    @Query("DELETE FROM sources WHERE id = :id")
    suspend fun delete(id: Long)
}

data class SkyCandidateRow(
    val id: String, val name: String, val tvgId: String?, val groupTitle: String?, val quality: Int, val position: Int,
    val streamUrl: String,
    val sourceId: Long,
)

data class GuideRow(val epgKey: String, val name: String, val tvgId: String?, val skyNumber: Int?)

data class KeyDays(val epgKey: String, val days: Int)

data class GroupSummary(val name: String, val count: Int)

data class UserPrefRow(
    val sourceId: Long, val name: String, val tvgId: String?, val groupTitle: String?, val streamUrl: String,
    val favourite: Boolean, val hidden: Boolean, val sortOrder: Int,
)

data class ChannelPrefs(val id: String, val favourite: Boolean, val hidden: Boolean, val sortOrder: Int, val streamUrl: String)

/** Ordering used everywhere a "channel list" is shown: favourites first, user order, then provider order. */
// sourceId keeps each playlist together: otherwise two sources that both number from 1
// (e.g. BBC iPlayer and an Xtream account) interleave in "All channels".
private const val LIST_ORDER = "favourite DESC, sortOrder, sourceId, COALESCE(number, 2147483647), position"

@Dao
interface ChannelDao {
    @Query("SELECT * FROM channels WHERE hidden = 0 AND primaryId IS NULL ORDER BY $LIST_ORDER")
    fun observeVisible(): Flow<List<ChannelEntity>>

    @Query("SELECT * FROM channels WHERE hidden = 0 AND primaryId IS NULL ORDER BY $LIST_ORDER")
    suspend fun visible(): List<ChannelEntity>

    /** Guide: one group at a time, capped, so huge lineups never load in full. */
    @Query("SELECT * FROM channels WHERE hidden = 0 AND primaryId IS NULL AND groupTitle = :group ORDER BY $LIST_ORDER LIMIT :limit")
    fun observeGroup(group: String, limit: Int): Flow<List<ChannelEntity>>

    @Query("SELECT * FROM channels WHERE hidden = 0 AND primaryId IS NULL ORDER BY $LIST_ORDER LIMIT :limit")
    fun observeFirst(limit: Int): Flow<List<ChannelEntity>>

    @Query("SELECT COUNT(*) FROM channels WHERE hidden = 0 AND primaryId IS NULL")
    fun observeVisibleCount(): Flow<Int>

    @Query("SELECT * FROM channels WHERE hidden = 0 AND primaryId IS NULL AND groupTitle = :group ORDER BY $LIST_ORDER")
    suspend fun inGroup(group: String): List<ChannelEntity>

    /** Ids only, in guide order: cheap even for 10k+ channels. */
    @Query("SELECT id FROM channels WHERE hidden = 0 AND primaryId IS NULL ORDER BY $LIST_ORDER")
    suspend fun visibleIds(): List<String>

    @Query("SELECT * FROM channels WHERE hidden = 0 AND number = :number ORDER BY primaryId IS NOT NULL, favourite DESC LIMIT 1")
    suspend fun byNumber(number: Int): ChannelEntity?

    @Query("SELECT * FROM channels WHERE hidden = 0 AND primaryId IS NULL ORDER BY $LIST_ORDER LIMIT 1 OFFSET :offset")
    suspend fun atPosition(offset: Int): ChannelEntity?

    @Query("SELECT * FROM channels WHERE hidden = 0 AND name = :name COLLATE NOCASE ORDER BY primaryId IS NOT NULL, favourite DESC LIMIT 1")
    suspend fun byName(name: String): ChannelEntity?

    @Query("SELECT * FROM channels WHERE favourite = 1 AND hidden = 0 AND primaryId IS NULL ORDER BY sortOrder, position")
    fun observeFavourites(): Flow<List<ChannelEntity>>

    @Query("SELECT * FROM channels WHERE favourite = 1 AND hidden = 0 AND primaryId IS NULL ORDER BY sortOrder, position")
    suspend fun favourites(): List<ChannelEntity>

    @Query(
        """SELECT * FROM channels
           WHERE primaryId IS NULL AND (:q = '' OR name LIKE '%' || :q || '%')
             AND (:group = '' OR groupTitle = :group)
           ORDER BY $LIST_ORDER LIMIT :limit OFFSET :offset"""
    )
    suspend fun search(q: String, group: String, limit: Int, offset: Int): List<ChannelEntity>

    @Query(
        """SELECT COUNT(*) FROM channels
           WHERE primaryId IS NULL AND (:q = '' OR name LIKE '%' || :q || '%')
             AND (:group = '' OR groupTitle = :group)"""
    )
    suspend fun count(q: String, group: String): Int

    /** Groups in the provider's order (first appearance), sources in the order they were added. */
    @Query("SELECT groupTitle FROM channels WHERE hidden = 0 AND groupTitle IS NOT NULL GROUP BY groupTitle ORDER BY MIN(sourceId * 10000000 + position)")
    fun observeGroups(): Flow<List<String>>

    @Query("SELECT groupTitle FROM channels WHERE groupTitle IS NOT NULL GROUP BY groupTitle ORDER BY MIN(sourceId * 10000000 + position)")
    suspend fun groups(): List<String>

    /** Only rows the user has customised; everything else is rebuilt from the playlist. */
    @Query(
        """SELECT id, favourite, hidden, sortOrder, streamUrl FROM channels
           WHERE sourceId = :sourceId AND (favourite = 1 OR hidden = 1 OR sortOrder != 2147483647)"""
    )
    suspend fun prefsForSource(sourceId: Long): List<ChannelPrefs>

    @Query(
        """SELECT sourceId, name, tvgId, groupTitle, streamUrl, favourite, hidden, sortOrder FROM channels
           WHERE favourite = 1 OR hidden = 1 OR sortOrder != 2147483647"""
    )
    suspend fun userPrefs(): List<UserPrefRow>

    /** Every row shown in lists (hidden groups included), for the match finder. */
    @Query("SELECT * FROM channels WHERE primaryId IS NULL")
    suspend fun allPrimaries(): List<ChannelEntity>

    @Query("SELECT * FROM channels WHERE id IN (:ids)")
    fun observeByIds(ids: List<String>): Flow<List<ChannelEntity>>

    @Query(
        """SELECT * FROM channels WHERE hidden = 0 AND primaryId IS NULL AND name LIKE '%' || :q || '%'
           ORDER BY skyNumber IS NULL, skyNumber, sourceId, position LIMIT :limit"""
    )
    suspend fun searchVisible(q: String, limit: Int): List<ChannelEntity>

    /** Visible rows whose guide key is one of [keys] (tvg-id, or the name when there is none). */
    @Query("SELECT * FROM channels WHERE hidden = 0 AND primaryId IS NULL AND (tvgId IN (:keys) OR (tvgId IS NULL AND name IN (:keys)))")
    suspend fun visibleByEpgKeys(keys: List<String>): List<ChannelEntity>

    @Query("SELECT DISTINCT COALESCE(tvgId, name) FROM channels WHERE sourceId = :sourceId")
    suspend fun epgKeys(sourceId: Long): List<String>

    /** EPG keys that have catch-up, so their past programmes are kept for the archive window. */
    @Query(
        """SELECT COALESCE(tvgId, name) AS epgKey, MAX(catchupDays) AS days FROM channels
           WHERE sourceId = :sourceId AND catchupDays > 0 GROUP BY epgKey"""
    )
    suspend fun catchupKeys(sourceId: Long): List<KeyDays>

    @Query("SELECT * FROM channels WHERE id = :id")
    suspend fun get(id: String): ChannelEntity?

    // ---- Sky layout
    @Query("SELECT id, name, tvgId, groupTitle, quality, position, streamUrl, sourceId FROM channels WHERE primaryId IS NULL AND hidden = 0")
    suspend fun skyCandidates(): List<SkyCandidateRow>

    @Query("UPDATE channels SET skyNumber = NULL, skySection = NULL WHERE skyNumber IS NOT NULL")
    suspend fun clearSky()

    @Query("UPDATE channels SET skyNumber = :number, skySection = :section WHERE id = :id")
    suspend fun setSky(id: String, number: Int, section: String)

    /** Sky sections that have channels, in Sky's order (by their lowest number). */
    @Query("SELECT skySection FROM channels WHERE skyNumber IS NOT NULL AND hidden = 0 AND primaryId IS NULL GROUP BY skySection ORDER BY MIN(skyNumber)")
    fun observeSkySections(): Flow<List<String>>

    @Query("SELECT * FROM channels WHERE skySection = :section AND hidden = 0 AND primaryId IS NULL ORDER BY skyNumber")
    fun observeSkySection(section: String): Flow<List<ChannelEntity>>

    @Query("SELECT * FROM channels WHERE skyNumber IS NOT NULL AND hidden = 0 AND primaryId IS NULL ORDER BY skyNumber")
    fun observeSkyAll(): Flow<List<ChannelEntity>>

    /** Zap order in the Sky layout: one section, or every Sky channel when [section] is null. */
    @Query("SELECT id FROM channels WHERE skyNumber IS NOT NULL AND hidden = 0 AND primaryId IS NULL AND (:section IS NULL OR skySection = :section) ORDER BY skyNumber")
    suspend fun skyIds(section: String?): List<String>

    @Query("SELECT * FROM channels WHERE skyNumber = :number AND hidden = 0 AND primaryId IS NULL LIMIT 1")
    suspend fun bySkyNumber(number: Int): ChannelEntity?

    // ---- Guide gaps
    @Query("SELECT COALESCE(tvgId, name) AS epgKey, name, tvgId, skyNumber FROM channels WHERE primaryId IS NULL AND hidden = 0")
    suspend fun guideRows(): List<GuideRow>

    /** A row's streams, best first: the primary itself plus any duplicates folded into it. */
    @Query("SELECT * FROM channels WHERE id = :primaryId OR primaryId = :primaryId ORDER BY primaryId IS NOT NULL, quality DESC, position")
    suspend fun streamsOf(primaryId: String): List<ChannelEntity>

    @Query("SELECT * FROM channels WHERE tvgId = :tvgId AND hidden = 0 ORDER BY primaryId IS NOT NULL, favourite DESC LIMIT 1")
    suspend fun byTvgId(tvgId: String): ChannelEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(channels: List<ChannelEntity>)

    @Query("DELETE FROM channels WHERE sourceId = :sourceId")
    suspend fun deleteForSource(sourceId: Long)

    @Query("UPDATE channels SET favourite = :favourite WHERE id = :id")
    suspend fun setFavourite(id: String, favourite: Boolean)

    @Query("UPDATE channels SET hidden = :hidden WHERE id = :id")
    suspend fun setHidden(id: String, hidden: Boolean)

    @Query("UPDATE channels SET hidden = :hidden WHERE groupTitle = :group")
    suspend fun setGroupHidden(group: String, hidden: Boolean)

    /** Callers chunk [groups] to stay under SQLite's bound-variable limit. */
    @Query("UPDATE channels SET hidden = :hidden WHERE groupTitle IN (:groups)")
    suspend fun setGroupsHidden(groups: List<String>, hidden: Boolean)

    @Query("SELECT groupTitle AS name, SUM(primaryId IS NULL) AS count FROM channels WHERE groupTitle IS NOT NULL GROUP BY groupTitle ORDER BY MIN(sourceId * 10000000 + position)")
    suspend fun groupSummary(): List<GroupSummary>

    @Query("UPDATE channels SET sortOrder = :order WHERE id = :id")
    suspend fun setSortOrder(id: String, order: Int)
}

@Dao
interface ProgrammeDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(programmes: List<ProgrammeEntity>)

    @Query("DELETE FROM programmes WHERE sourceId = :sourceId")
    suspend fun deleteForSource(sourceId: Long)

    /** After a refresh: drop the rows a source wrote before [generation] (the ones it no longer lists). */
    @Query("DELETE FROM programmes WHERE sourceId = :sourceId AND generation < :generation")
    suspend fun deleteStale(sourceId: Long, generation: Long)

    /** Re-emits whenever the programmes table changes (e.g. an EPG refresh lands). */
    @Query("SELECT COUNT(*) FROM programmes")
    fun observeCount(): Flow<Int>

    /** Guide keys that have something on in [from, until), from any source. */
    /** Channels with listings from a real source (gap-fill rows, sourceId < 0, don't count). */
    @Query("SELECT DISTINCT channelKey FROM programmes WHERE sourceId >= 0 AND stop > :from AND start < :until")
    suspend fun coveredKeys(from: Long, until: Long): List<String>

    @Query("DELETE FROM programmes WHERE stop < :before")
    suspend fun deleteEndedBefore(before: Long)

    /** Current + upcoming programmes for a channel. First row is "now" if start <= now. */
    @Query("SELECT * FROM programmes WHERE channelKey = :key AND stop > :now ORDER BY start LIMIT :limit")
    suspend fun upcoming(key: String, now: Long, limit: Int = 2): List<ProgrammeEntity>

    @Query("SELECT * FROM programmes WHERE channelKey IN (:keys) AND stop > :from AND start < :until ORDER BY channelKey, start")
    suspend fun window(keys: List<String>, from: Long, until: Long): List<ProgrammeEntity>

    /** Programmes on air across [from]…[until] (started by [from], still on at [until]). */
    @Query("SELECT * FROM programmes WHERE start <= :from AND stop >= :until")
    suspend fun spanning(from: Long, until: Long): List<ProgrammeEntity>

    @Query("SELECT * FROM programmes WHERE title LIKE '%' || :q || '%' AND stop > :from ORDER BY start LIMIT :limit")
    suspend fun search(q: String, from: Long, limit: Int): List<ProgrammeEntity>
}
