package dev.sysmoon.ferry.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [SourceEntity::class, ChannelEntity::class, ProgrammeEntity::class],
    version = 2,
    exportSchema = true,
)
abstract class FerryDatabase : RoomDatabase() {
    abstract fun sources(): SourceDao
    abstract fun channels(): ChannelDao
    abstract fun programmes(): ProgrammeDao

    companion object {
        fun create(context: Context): FerryDatabase =
            Room.databaseBuilder(context, FerryDatabase::class.java, "ferry.db")
                .addMigrations(MIGRATION_1_2)
                // Last resort only; real migrations keep favourites and hidden groups.
                .fallbackToDestructiveMigration()
                .build()

        /** v2: catch-up support. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE channels ADD COLUMN catchupDays INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE sources ADD COLUMN serverTimezone TEXT")
            }
        }
    }
}
