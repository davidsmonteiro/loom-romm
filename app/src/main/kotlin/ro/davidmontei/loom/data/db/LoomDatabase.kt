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
        SaveKeyEntity::class,
        SyncedFolderEntity::class,
    ],
    version = 7,
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

/**
 * Version 4 (Eden saves): each Switch game's title ID, read from its image or chosen by the owner,
 * so a scan doesn't read the images again and the owner's answer is remembered.
 */
val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `switch_titles` (`rom_id` INTEGER NOT NULL, `title_id` TEXT NOT NULL, " +
                "`source` TEXT NOT NULL, `image_fingerprint` TEXT, `recorded_at` INTEGER NOT NULL, PRIMARY KEY(`rom_id`))",
        )
    }
}

/**
 * Version 5: the folder the owner chose to sync a Switch game's save from, when Eden kept it in
 * both its console-wide folder and the profile's.
 */
val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `synced_folders` (`rom_id` INTEGER NOT NULL, `folder` TEXT NOT NULL, " +
                "`chosen_at` INTEGER NOT NULL, PRIMARY KEY(`rom_id`))",
        )
    }
}

/**
 * Version 6 (RomM 5.4's send to device): a download job remembers the RomM install request it came
 * from. Jobs already queued came from the library, so they have none.
 */
val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `download_jobs` ADD COLUMN `install_request_id` TEXT")
    }
}

/**
 * Version 7 (DS and PSP saves): what names each game's save in its emulator's save folder is kept
 * per emulator and game, in one table: Eden's title IDs move there, read or chosen as they were,
 * and PPSSPP's disc IDs join them.
 */
val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `save_keys` (`emulator` TEXT NOT NULL, `rom_id` INTEGER NOT NULL, `save_key` TEXT NOT NULL, " +
                "`source` TEXT NOT NULL, `image_fingerprint` TEXT, `recorded_at` INTEGER NOT NULL, PRIMARY KEY(`emulator`, `rom_id`))",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_save_keys_emulator_save_key` ON `save_keys` (`emulator`, `save_key`)")
        db.execSQL(
            "INSERT INTO `save_keys` (`emulator`, `rom_id`, `save_key`, `source`, `image_fingerprint`, `recorded_at`) " +
                "SELECT 'eden', `rom_id`, `title_id`, `source`, `image_fingerprint`, `recorded_at` FROM `switch_titles`",
        )
        db.execSQL("DROP TABLE `switch_titles`")
    }
}
