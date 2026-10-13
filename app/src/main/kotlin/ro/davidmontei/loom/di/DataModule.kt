package ro.davidmontei.loom.di

import android.content.Context
import androidx.room.Room
import androidx.work.WorkManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import ro.davidmontei.loom.data.catalog.AfterCatalogSync
import ro.davidmontei.loom.data.catalog.CatalogSyncScheduler
import ro.davidmontei.loom.data.catalog.CatalogSyncer
import ro.davidmontei.loom.data.db.CatalogDao
import ro.davidmontei.loom.data.db.LoomDatabase
import ro.davidmontei.loom.data.settings.SettingsStore
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DataModule {

    @Provides @Singleton
    fun database(@ApplicationContext context: Context): LoomDatabase =
        Room.databaseBuilder(context, LoomDatabase::class.java, "loom.db").build()

    @Provides fun catalogDao(db: LoomDatabase): CatalogDao = db.catalogDao()

    @Provides @Singleton
    fun workManager(@ApplicationContext context: Context): WorkManager = WorkManager.getInstance(context)

    @Provides @Singleton
    fun catalogSyncer(dao: CatalogDao, settings: SettingsStore) = CatalogSyncer(dao, settings)

    // Replaced by install reconciliation once the storage layer exists.
    @Provides @Singleton
    fun afterCatalogSync(): AfterCatalogSync = AfterCatalogSync { }

    @Provides @Singleton
    fun catalogSyncScheduler(workManager: WorkManager) = CatalogSyncScheduler(workManager)
}
