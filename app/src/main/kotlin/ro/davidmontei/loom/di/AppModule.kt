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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient
import ro.davidmontei.loom.data.auth.CredentialStore
import ro.davidmontei.loom.data.auth.KeystoreTokenCipher
import ro.davidmontei.loom.data.auth.TokenCipher
import ro.davidmontei.loom.data.mapping.MappingRepository
import ro.davidmontei.loom.data.settings.SettingsStore
import ro.davidmontei.loom.net.ActiveServer
import ro.davidmontei.loom.romm.pairing.RommConnector
import javax.inject.Qualifier
import javax.inject.Singleton

@Qualifier @Retention(AnnotationRetention.BINARY) annotation class AppScope
@Qualifier @Retention(AnnotationRetention.BINARY) annotation class SettingsPrefs
@Qualifier @Retention(AnnotationRetention.BINARY) annotation class CredentialPrefs

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides @Singleton @AppScope
    fun appScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Provides @Singleton @SettingsPrefs
    fun settingsPrefs(@ApplicationContext context: Context): DataStore<Preferences> =
        PreferenceDataStoreFactory.create { context.preferencesDataStoreFile("settings") }

    @Provides @Singleton @CredentialPrefs
    fun credentialPrefs(@ApplicationContext context: Context): DataStore<Preferences> =
        PreferenceDataStoreFactory.create { context.preferencesDataStoreFile("credentials") }

    @Provides @Singleton
    fun settingsStore(@SettingsPrefs prefs: DataStore<Preferences>) = SettingsStore(prefs)

    @Provides @Singleton
    fun mappingRepository(@SettingsPrefs prefs: DataStore<Preferences>) = MappingRepository(prefs)

    @Provides @Singleton
    fun tokenCipher(): TokenCipher = KeystoreTokenCipher()

    @Provides @Singleton
    fun credentialStore(
        @CredentialPrefs prefs: DataStore<Preferences>,
        cipher: TokenCipher,
        @AppScope scope: CoroutineScope,
    ) = CredentialStore(prefs, cipher, scope)

    @Provides @Singleton
    fun rommConnector(@BareHttp client: OkHttpClient) = RommConnector(client)

    @Provides @Singleton
    fun activeServer(
        settings: SettingsStore,
        credentials: CredentialStore,
        @ApiHttp client: OkHttpClient,
        @AppScope scope: CoroutineScope,
    ) = ActiveServer(settings, credentials, client, scope)
}
