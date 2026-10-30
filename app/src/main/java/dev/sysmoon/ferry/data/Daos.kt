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

/** Ordering used everywhere a "channel list" is shown: favourites first, user order, then provider order. */
private const val LIST_ORDER = "favourite DESC, sortOrder, COALESCE(number, 2147483647), position"

@Dao
interface ChannelDao {
    @Query("SELECT * FROM channels WHERE hidden = 0 ORDER BY $LIST_ORDER")
    fun observeVisible(): Flow<List<ChannelEntity>>

    @Query("SELECT * FROM channels WHERE hidden = 0 ORDER BY $LIST_ORDER")
    suspend fun visible(): List<ChannelEntity>

    @Query("SELECT * FROM channels WHERE favourite = 1 AND hidden = 0 ORDER BY sortOrder, position")
    fun observeFavourites(): Flow<List<ChannelEntity>>

    @Query("SELECT * FROM channels WHERE favourite = 1 AND hidden = 0 ORDER BY sortOrder, position")
    suspend fun favourites(): List<ChannelEntity>

    @Query(
        """SELECT * FROM channels
           WHERE (:q = '' OR name LIKE '%' || :q || '%')
             AND (:group = '' OR groupTitle = :group)
           ORDER BY $LIST_ORDER LIMIT :limit OFFSET :offset"""
    )
    suspend fun search(q: String, group: String, limit: Int, offset: Int): List<ChannelEntity>

    @Query(
        """SELECT COUNT(*) FROM channels
           WHERE (:q = '' OR name LIKE '%' || :q || '%')
             AND (:group = '' OR groupTitle = :group)"""
    )
    suspend fun count(q: String, group: String): Int

    @Query("SELECT DISTINCT groupTitle FROM channels WHERE hidden = 0 AND groupTitle IS NOT NULL ORDER BY groupTitle")
    fun observeGroups(): Flow<List<String>>

    @Query("SELECT DISTINCT groupTitle FROM channels WHERE groupTitle IS NOT NULL ORDER BY groupTitle")
    suspend fun groups(): List<String>

    @Query("SELECT * FROM channels WHERE sourceId = :sourceId")
    suspend fun forSource(sourceId: Long): List<ChannelEntity>

    @Query("SELECT DISTINCT COALESCE(tvgId, name) FROM channels WHERE sourceId = :sourceId")
    suspend fun epgKeys(sourceId: Long): List<String>

    @Query("SELECT * FROM channels WHERE id = :id")
    suspend fun get(id: String): ChannelEntity?

    @Query("SELECT * FROM channels WHERE tvgId = :tvgId AND hidden = 0 ORDER BY favourite DESC LIMIT 1")
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

    @Query("UPDATE channels SET sortOrder = :order WHERE id = :id")
    suspend fun setSortOrder(id: String, order: Int)
}

@Dao
interface ProgrammeDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(programmes: List<ProgrammeEntity>)

    @Query("DELETE FROM programmes WHERE sourceId = :sourceId")
    suspend fun deleteForSource(sourceId: Long)

    @Query("DELETE FROM programmes WHERE stop < :before")
    suspend fun deleteEndedBefore(before: Long)

    /** Current + upcoming programmes for a channel. First row is "now" if start <= now. */
    @Query("SELECT * FROM programmes WHERE channelKey = :key AND stop > :now ORDER BY start LIMIT :limit")
    suspend fun upcoming(key: String, now: Long, limit: Int = 2): List<ProgrammeEntity>

    @Query("SELECT * FROM programmes WHERE channelKey IN (:keys) AND stop > :from AND start < :until ORDER BY channelKey, start")
    suspend fun window(keys: List<String>, from: Long, until: Long): List<ProgrammeEntity>
}
