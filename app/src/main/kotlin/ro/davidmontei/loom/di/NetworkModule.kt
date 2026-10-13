package ro.davidmontei.loom.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.logging.HttpLoggingInterceptor
import ro.davidmontei.loom.BuildConfig
import ro.davidmontei.loom.net.ActiveServer
import ro.davidmontei.loom.net.AuthInterceptor
import java.util.concurrent.TimeUnit
import javax.inject.Qualifier
import javax.inject.Singleton

/** No auth, no cookies: probes and pairing. */
@Qualifier @Retention(AnnotationRetention.BINARY) annotation class BareHttp

/** Authenticated JSON API calls and cover art. */
@Qualifier @Retention(AnnotationRetention.BINARY) annotation class ApiHttp

/** Authenticated, unlogged, untimed streaming for ROM downloads. */
@Qualifier @Retention(AnnotationRetention.BINARY) annotation class DownloadHttp

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    /**
     * The root client. No CookieJar on purpose: RomM's hybrid auth prefers a session cookie over
     * the bearer token, so a stray cookie could silently act as a different user.
     */
    @Provides @Singleton @BareHttp
    fun bareClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .apply {
            if (BuildConfig.DEBUG) addInterceptor(
                HttpLoggingInterceptor().apply {
                    level = HttpLoggingInterceptor.Level.BASIC
                    redactHeader("Authorization")
                },
            )
        }
        .build()

    @Provides @Singleton @ApiHttp
    fun apiClient(@BareHttp bare: OkHttpClient, server: dagger.Lazy<ActiveServer>): OkHttpClient =
        bare.newBuilder()
            .addInterceptor(AuthInterceptor(LazyAuthSource(server)))
            .build()

    /**
     * Downloads: no logging (a body logger would buffer gigabytes), no call timeout, HTTP/1.1 so
     * parallel jobs don't share one HTTP/2 connection. The engine sets `Accept-Encoding: identity`
     * on each request so Range offsets refer to the bytes on disk.
     */
    @Provides @Singleton @DownloadHttp
    fun downloadClient(server: dagger.Lazy<ActiveServer>): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.SECONDS)
        .protocols(listOf(Protocol.HTTP_1_1))
        .retryOnConnectionFailure(true)
        .addInterceptor(AuthInterceptor(LazyAuthSource(server)))
        .build()

    /** Breaks the ActiveServer ↔ OkHttpClient construction cycle. */
    private class LazyAuthSource(private val server: dagger.Lazy<ActiveServer>) : ro.davidmontei.loom.net.AuthSource {
        override fun serverUrl() = server.get().serverUrl()
        override fun token() = server.get().token()
    }
}
