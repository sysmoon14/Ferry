package dev.sysmoon.ferry.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [SourceEntity::class, ChannelEntity::class, ProgrammeEntity::class],
    version = 4,
    exportSchema = true,
)
abstract class FerryDatabase : RoomDatabase() {
    abstract fun sources(): SourceDao
    abstract fun channels(): ChannelDao
    abstract fun programmes(): ProgrammeDao

    companion object {
        fun create(context: Context): FerryDatabase =
            Room.databaseBuilder(context, FerryDatabase::class.java, "ferry.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
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

        /** v3: duplicate streams folded into one row. Filled in on the next refresh. */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE channels ADD COLUMN primaryId TEXT")
                db.execSQL("ALTER TABLE channels ADD COLUMN quality INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE channels ADD COLUMN streamCount INTEGER NOT NULL DEFAULT 1")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_channels_primaryId` ON `channels` (`primaryId`)")
            }
        }

        /** v4: catch-up offered by a merged row's other streams. */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE channels ADD COLUMN rowCatchupDays INTEGER NOT NULL DEFAULT 0")
            }
        }
    }
}
