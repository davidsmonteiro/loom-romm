package ro.davidmontei.loom.di

import android.content.Context
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
import ro.davidmontei.loom.data.sent.SendToDeviceStore
import ro.davidmontei.loom.data.settings.SettingsStore
import ro.davidmontei.loom.data.settings.WriteThroughDataStore
import ro.davidmontei.loom.net.ServerInfo
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
}
