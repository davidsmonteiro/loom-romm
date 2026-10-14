package ro.davidmontei.loom.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import ro.davidmontei.loom.data.auth.CredentialStore
import ro.davidmontei.loom.data.db.CatalogDao
import ro.davidmontei.loom.data.db.LoomDatabase
import ro.davidmontei.loom.data.db.SaveDao
import ro.davidmontei.loom.data.download.StorageVolumes
import ro.davidmontei.loom.data.saves.SaveSyncEngine
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
}
