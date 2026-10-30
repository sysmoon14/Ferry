package dev.sysmoon.ferry.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [SourceEntity::class, ChannelEntity::class, ProgrammeEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class FerryDatabase : RoomDatabase() {
    abstract fun sources(): SourceDao
    abstract fun channels(): ChannelDao
    abstract fun programmes(): ProgrammeDao

    companion object {
        fun create(context: Context): FerryDatabase =
            Room.databaseBuilder(context, FerryDatabase::class.java, "ferry.db")
                // Pre-1.0: schema churn is expected; playlists re-import on next refresh.
                .fallbackToDestructiveMigration()
                .build()
    }
}
