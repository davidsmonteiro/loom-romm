package ro.davidmontei.loom.data.db

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        PlatformEntity::class,
        RomEntity::class,
        RomFileEntity::class,
        InstallEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class LoomDatabase : RoomDatabase() {
    abstract fun catalogDao(): CatalogDao
}
