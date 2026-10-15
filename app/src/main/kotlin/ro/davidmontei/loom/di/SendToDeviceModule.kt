package ro.davidmontei.loom.di

import android.content.Context
import androidx.work.WorkManager
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import ro.davidmontei.loom.data.auth.CredentialStore
import ro.davidmontei.loom.data.download.DownloadNotifications
import ro.davidmontei.loom.data.download.DownloadRepository
import ro.davidmontei.loom.data.download.QueueGame
import ro.davidmontei.loom.data.sent.ClaimScheduler
import ro.davidmontei.loom.data.sent.SendToDevice
import ro.davidmontei.loom.data.sent.SendToDeviceStore
import ro.davidmontei.loom.data.settings.SettingsStore
import ro.davidmontei.loom.data.settings.WriteThroughDataStore
import ro.davidmontei.loom.net.ActiveServer
import ro.davidmontei.loom.net.ServerInfo
import ro.davidmontei.loom.net.ServerReachability
import javax.inject.Qualifier
import javax.inject.Singleton

@Qualifier @Retention(AnnotationRetention.BINARY) annotation class SendToDevicePrefs

/** Games sent from RomM's web UI ("send to device", RomM 5.4). */
@Module
@InstallIn(SingletonComponent::class)
object SendToDeviceModule {

    /** Its own file, read once and then kept in memory like the settings (see [WriteThroughDataStore]). */
    @Provides @Singleton @SendToDevicePrefs
    fun sendToDevicePrefs(@ApplicationContext context: Context, @AppScope scope: CoroutineScope): DataStore<Preferences> =
        WriteThroughDataStore(PreferenceDataStoreFactory.create { context.preferencesDataStoreFile("send_to_device") }, scope)

    @Provides @Singleton
    fun sendToDeviceStore(@SendToDevicePrefs prefs: DataStore<Preferences>) = SendToDeviceStore(prefs)

    @Provides @Singleton
    fun serverInfo(settings: SettingsStore, sendToDevice: SendToDeviceStore) = ServerInfo(settings, sendToDevice)

    @Provides @Singleton
    fun claimScheduler(workManager: WorkManager) = ClaimScheduler(workManager)

    @Provides @Singleton
    fun sendToDevice(
        settings: SettingsStore,
        credentials: CredentialStore,
        store: SendToDeviceStore,
        server: ActiveServer,
        serverInfo: ServerInfo,
        reachability: ServerReachability,
        queue: QueueGame,
        downloads: DownloadRepository,
        notifications: DownloadNotifications,
        claims: ClaimScheduler,
        @AppScope scope: CoroutineScope,
    ) = SendToDevice(settings, credentials, store, server, serverInfo, reachability, queue, downloads, notifications, claims, scope)
}
