package ro.davidmontei.loom.romm

import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import ro.davidmontei.loom.romm.api.RommApi

/** Builds a [RommApi] for one base URL on a caller-supplied OkHttp client (auth, logging, TLS). */
object RommServiceFactory {
    fun create(baseUrl: HttpUrl, client: OkHttpClient): RommApi =
        Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(client)
            .addConverterFactory(RommJson.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(RommApi::class.java)
}
