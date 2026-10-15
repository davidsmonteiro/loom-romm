package ro.davidmontei.loom.di

import android.content.Context
import android.net.ConnectivityManager
import androidx.room.Room
import androidx.work.WorkManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import ro.davidmontei.loom.data.catalog.AfterCatalogSync
import ro.davidmontei.loom.data.catalog.CatalogRefreshScheduler
import ro.davidmontei.loom.data.catalog.CatalogSyncScheduler
import ro.davidmontei.loom.data.catalog.CatalogSyncer
import ro.davidmontei.loom.data.catalog.CoverStore
import ro.davidmontei.loom.data.db.CatalogDao
import ro.davidmontei.loom.data.db.LoomDatabase
import ro.davidmontei.loom.data.db.MIGRATION_1_2
import ro.davidmontei.loom.data.db.MIGRATION_2_3
import ro.davidmontei.loom.data.db.MIGRATION_3_4
import ro.davidmontei.loom.data.db.MIGRATION_4_5
import ro.davidmontei.loom.data.db.MIGRATION_5_6
import ro.davidmontei.loom.data.db.MIGRATION_6_7
import ro.davidmontei.loom.data.download.StorageVolumes
import ro.davidmontei.loom.data.install.InstallManager
import ro.davidmontei.loom.data.mapping.MappingRepository
import ro.davidmontei.loom.data.settings.SettingsStore
import ro.davidmontei.loom.net.CoverNetwork
import ro.davidmontei.loom.net.onUnmeteredNetwork
import kotlinx.coroutines.CoroutineScope
import okhttp3.OkHttpClient
import java.io.File
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DataModule {

    @Provides @Singleton
    fun database(@ApplicationContext context: Context): LoomDatabase =
        Room.databaseBuilder(context, LoomDatabase::class.java, "loom.db")
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7).build()

    @Provides fun catalogDao(db: LoomDatabase): CatalogDao = db.catalogDao()

    @Provides @Singleton
    fun workManager(@ApplicationContext context: Context): WorkManager = WorkManager.getInstance(context)

    @Provides @Singleton
    fun catalogSyncer(dao: CatalogDao, settings: SettingsStore) = CatalogSyncer(dao, settings)

    @Provides @Singleton
    fun installManager(catalog: CatalogDao, settings: SettingsStore, mapping: MappingRepository, volumes: StorageVolumes) =
        InstallManager(catalog, settings, mapping, volumes)

    /** After every sync, the catalog's view of what's on the SD card is checked against reality. */
    @Provides @Singleton
    fun afterCatalogSync(installs: InstallManager): AfterCatalogSync = AfterCatalogSync { installs.reconcile() }

    /** Whether covers may be fetched now: "Only on Wi-Fi" holds them back on a metered network. */
    @Provides @Singleton
    fun coverNetwork(@ApplicationContext context: Context, settings: SettingsStore): CoverNetwork {
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        return CoverNetwork(settings) { connectivity?.onUnmeteredNetwork() == true }
    }

    @Provides @Singleton
    fun coverStore(
        @ApplicationContext context: Context,
        @ApiHttp http: OkHttpClient,
        @AppScope scope: CoroutineScope,
        network: CoverNetwork,
    ) = CoverStore(File(context.cacheDir, "cover-thumbs"), http, scope, network::allowed)

    @Provides @Singleton
    fun catalogSyncScheduler(workManager: WorkManager) = CatalogSyncScheduler(workManager)

    @Provides @Singleton
    fun catalogRefreshScheduler(workManager: WorkManager) = CatalogRefreshScheduler(workManager)
}
