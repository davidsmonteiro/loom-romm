package ro.davidmontei.loom.di

import android.content.Context
import androidx.work.WorkManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import ro.davidmontei.loom.data.auth.CredentialStore
import ro.davidmontei.loom.data.db.CatalogDao
import ro.davidmontei.loom.data.db.LoomDatabase
import ro.davidmontei.loom.data.db.SaveDao
import ro.davidmontei.loom.data.download.StorageVolumes
import ro.davidmontei.loom.data.saves.SaveRepository
import ro.davidmontei.loom.data.saves.SaveSyncEngine
import ro.davidmontei.loom.data.saves.SaveSyncNotifications
import ro.davidmontei.loom.data.saves.SaveSyncScheduler
import ro.davidmontei.loom.data.saves.SaveSyncTriggers
import ro.davidmontei.loom.data.saves.SaveUnitResolver
import ro.davidmontei.loom.data.settings.SettingsStore
import ro.davidmontei.loom.net.ActiveServer
import java.io.File
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object SaveModule {
    @Provides fun saveDao(db: LoomDatabase): SaveDao = db.saveDao()

    @Provides @Singleton
    fun saveUnitResolver(settings: SettingsStore, volumes: StorageVolumes) = SaveUnitResolver(settings, volumes)

    @Provides @Singleton
    fun saveSyncEngine(
        catalog: CatalogDao,
        saves: SaveDao,
        server: ActiveServer,
        credentials: CredentialStore,
        settings: SettingsStore,
        units: SaveUnitResolver,
        volumes: StorageVolumes,
        @ApplicationContext context: Context,
    ) = SaveSyncEngine(catalog, saves, server, credentials, settings, units, volumes, File(context.cacheDir, "save-sync"))

    @Provides @Singleton
    fun saveRepository(engine: SaveSyncEngine, saves: SaveDao, @AppScope scope: CoroutineScope) = SaveRepository(engine, saves, scope)

    @Provides @Singleton fun saveSyncScheduler(workManager: WorkManager) = SaveSyncScheduler(workManager)

    @Provides @Singleton
    fun saveSyncTriggers(settings: SettingsStore, scheduler: SaveSyncScheduler) = SaveSyncTriggers(settings, scheduler)

    @Provides @Singleton fun saveSyncNotifications(@ApplicationContext context: Context) = SaveSyncNotifications(context)
}
