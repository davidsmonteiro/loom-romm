package ro.davidmontei.loom.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        PlatformEntity::class,
        RomEntity::class,
        RomFileEntity::class,
        InstallEntity::class,
        DownloadJobEntity::class,
        DownloadPartEntity::class,
        SaveBaselineEntity::class,
        SaveConflictEntity::class,
        SaveUnitEntity::class,
    ],
    version = 3,
    exportSchema = true,
)
abstract class LoomDatabase : RoomDatabase() {
    abstract fun catalogDao(): CatalogDao
    abstract fun downloadDao(): DownloadDao
    abstract fun saveDao(): SaveDao
}

/**
 * Version 2 (save sync): ROMs keep the title ID and save target RomM read from them, and three
 * tables hold each game's save baseline, its conflict if any, and the last scan of its save. The
 * new ROM columns start empty; the next catalog sync is a full one that fills them.
 */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `roms` ADD COLUMN `title_id` TEXT")
        db.execSQL("ALTER TABLE `roms` ADD COLUMN `save_target` TEXT")
        db.execSQL("ALTER TABLE `roms` ADD COLUMN `save_target_layout` TEXT")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `save_baselines` (`rom_id` INTEGER NOT NULL, `slot` TEXT NOT NULL, " +
                "`local_hash` TEXT NOT NULL, `server_save_id` INTEGER NOT NULL, `server_hash` TEXT, " +
                "`server_updated_at` TEXT NOT NULL, `synced_at` INTEGER NOT NULL, PRIMARY KEY(`rom_id`, `slot`))",
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `save_conflicts` (`rom_id` INTEGER NOT NULL, `slot` TEXT NOT NULL, " +
                "`reason` TEXT NOT NULL, `local_hash` TEXT, `local_modified_ms` INTEGER, `local_bytes` INTEGER, " +
                "`server_save_id` INTEGER, `server_hash` TEXT, `server_updated_at` TEXT, `server_emulator` TEXT, " +
                "`detected_at` INTEGER NOT NULL, `skipped` INTEGER NOT NULL, PRIMARY KEY(`rom_id`))",
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `save_units` (`rom_id` INTEGER NOT NULL, `emulator` TEXT NOT NULL, " +
                "`unit_path` TEXT, `fingerprint` TEXT, `file_count` INTEGER NOT NULL, `total_bytes` INTEGER NOT NULL, " +
                "`newest_modified_ms` INTEGER, `local_hash` TEXT, `state` TEXT NOT NULL, `detail` TEXT, " +
                "`checked_at` INTEGER NOT NULL, PRIMARY KEY(`rom_id`))",
        )
    }
}

/**
 * Version 3: a save baseline records the folder it was synced from. Baselines already there get
 * none, so each game's next sync compares its save with RomM's as a first sync would: identical
 * saves are adopted, different ones become conflicts, and nothing is uploaded over RomM's.
 */
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `save_baselines` ADD COLUMN `unit_path` TEXT")
    }
}
