package ro.davidmontei.loom.di

import android.content.Context
import androidx.work.WorkManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import ro.davidmontei.loom.data.db.CatalogDao
import ro.davidmontei.loom.data.db.DownloadDao
import ro.davidmontei.loom.data.db.LoomDatabase
import ro.davidmontei.loom.data.download.DownloadNotifications
import ro.davidmontei.loom.data.download.DownloadRepository
import ro.davidmontei.loom.data.download.QueueKicker
import ro.davidmontei.loom.data.download.RoomJobStore
import ro.davidmontei.loom.data.download.StorageVolumes
import ro.davidmontei.loom.data.mapping.MappingRepository
import ro.davidmontei.loom.data.settings.SettingsStore
import ro.davidmontei.loom.net.ActiveServer
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DownloadModule {
    @Provides fun downloadDao(db: LoomDatabase): DownloadDao = db.downloadDao()

    @Provides @Singleton fun roomJobStore(dao: DownloadDao) = RoomJobStore(dao)

    @Provides @Singleton fun storageVolumes(@ApplicationContext context: Context) = StorageVolumes(context)

    @Provides @Singleton fun queueKicker(workManager: WorkManager) = QueueKicker(workManager)

    @Provides @Singleton fun downloadNotifications(@ApplicationContext context: Context) = DownloadNotifications(context)

    @Provides @Singleton
    fun downloadRepository(
        dao: DownloadDao,
        catalog: CatalogDao,
        server: ActiveServer,
        mapping: MappingRepository,
        settings: SettingsStore,
        volumes: StorageVolumes,
        kicker: QueueKicker,
    ) = DownloadRepository(dao, catalog, server, mapping, settings, volumes, kicker)
}
