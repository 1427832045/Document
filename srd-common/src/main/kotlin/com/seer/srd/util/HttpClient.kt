package com.seer.srd.util

import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import okhttp3.logging.HttpLoggingInterceptor.Level
import org.slf4j.LoggerFactory
import retrofit2.Retrofit
import retrofit2.converter.jackson.JacksonConverterFactory
import retrofit2.converter.jaxb.JaxbConverterFactory
import retrofit2.converter.scalars.ScalarsConverterFactory
import java.util.concurrent.TimeUnit

object HttpClient {
    
    private val logger = LoggerFactory.getLogger(HttpClient::class.java)
    
    fun <T> buildHttpClient(baseUrl: String, remoteInterface: Class<T>, level: Level = Level.NONE): T {
        val interceptor = HttpLoggingInterceptor { msg -> logger.debug(msg) }
        interceptor.level = level
        val client = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .addInterceptor(interceptor)
            .build()
        val retrofit = Retrofit.Builder()
            .client(client)
            .baseUrl(baseUrl)
            .addConverterFactory(ScalarsConverterFactory.create())
            .addConverterFactory(JaxbConverterFactory.create())
            .addConverterFactory(JacksonConverterFactory.create())
            .build()
        return retrofit.create(remoteInterface)
    }
}