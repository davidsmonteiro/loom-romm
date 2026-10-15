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
import ro.davidmontei.loom.net.ReachabilityInterceptor
import ro.davidmontei.loom.net.ReachabilityWatch
import ro.davidmontei.loom.net.ServerInfo
import ro.davidmontei.loom.net.ServerReachability
import java.util.concurrent.TimeUnit
import javax.inject.Qualifier
import javax.inject.Singleton

/** No auth, no cookies: probes and pairing. */
@Qualifier @Retention(AnnotationRetention.BINARY) annotation class BareHttp

/** Authenticated JSON API calls and cover art. */
@Qualifier @Retention(AnnotationRetention.BINARY) annotation class ApiHttp

/** Authenticated, unlogged, untimed streaming for ROM downloads. */
@Qualifier @Retention(AnnotationRetention.BINARY) annotation class DownloadHttp

/** RomM's device socket: the token goes in its connect payload, and it says nothing of reachability. */
@Qualifier @Retention(AnnotationRetention.BINARY) annotation class SocketHttp

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

    /** Whether RomM answered the last request: every request to it through the clients below reports. */
    @Provides @Singleton
    fun serverReachability() = ServerReachability()

    /** Asks RomM again when there's reason to (see [ReachabilityWatch]), with a heartbeat, whose news is kept. */
    @Provides @Singleton
    fun reachabilityWatch(reachability: ServerReachability, server: ActiveServer, serverInfo: ServerInfo) =
        ReachabilityWatch(reachability) { server.call { api -> serverInfo.heartbeat(api) } }

    @Provides @Singleton @ApiHttp
    fun apiClient(@BareHttp bare: OkHttpClient, server: dagger.Lazy<ActiveServer>, reachability: ServerReachability): OkHttpClient =
        bare.newBuilder()
            .addInterceptor(AuthInterceptor(LazyAuthSource(server)))
            .addInterceptor(ReachabilityInterceptor(reachability, LazyAuthSource(server)))
            .build()

    /**
     * Downloads: no logging (a body logger would buffer gigabytes), no call timeout, HTTP/1.1 so
     * parallel jobs don't share one HTTP/2 connection. The engine sets `Accept-Encoding: identity`
     * on each request so Range offsets refer to the bytes on disk.
     */
    @Provides @Singleton @DownloadHttp
    fun downloadClient(server: dagger.Lazy<ActiveServer>, reachability: ServerReachability): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.SECONDS)
        .protocols(listOf(Protocol.HTTP_1_1))
        .retryOnConnectionFailure(true)
        .addInterceptor(AuthInterceptor(LazyAuthSource(server)))
        .addInterceptor(ReachabilityInterceptor(reachability, LazyAuthSource(server)))
        .build()

    /**
     * RomM's device socket ([ro.davidmontei.loom.data.sent.LiveNudges]), without the API's interceptors:
     * no Authorization header, as the token goes only in the namespace's connect payload, and no
     * reachability reports, as a proxy in front of RomM that won't pass WebSockets answers the upgrade
     * with a 502 while RomM itself is fine, which mustn't make the library say it's offline.
     */
    @Provides @Singleton @SocketHttp
    fun socketClient(@BareHttp bare: OkHttpClient): OkHttpClient = bare.newBuilder().build()

    /** Breaks the ActiveServer ↔ OkHttpClient construction cycle. */
    private class LazyAuthSource(private val server: dagger.Lazy<ActiveServer>) : ro.davidmontei.loom.net.AuthSource {
        override fun serverUrl() = server.get().serverUrl()
        override fun token() = server.get().token()
    }
}
