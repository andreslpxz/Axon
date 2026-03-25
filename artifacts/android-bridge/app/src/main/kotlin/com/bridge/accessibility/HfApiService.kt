package com.bridge.accessibility

import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Path

interface HfApiService {
    @POST("models/{modelId}")
    suspend fun getEmbeddings(
        @Path("modelId") modelId: String = "sentence-transformers/all-MiniLM-L6-v2",
        @Header("Authorization") authHeader: String,
        @Body request: Map<String, String>
    ): List<Float>
}

object HfRetrofitClient {
    private const val BASE_URL = "https://api-inference.huggingface.co/"

    val hfService: HfApiService by lazy {
        retrofit2.Retrofit.Builder()
            .baseUrl(BASE_URL)
            .addConverterFactory(retrofit2.converter.gson.GsonConverterFactory.create())
            .build()
            .create(HfApiService::class.java)
    }
}
