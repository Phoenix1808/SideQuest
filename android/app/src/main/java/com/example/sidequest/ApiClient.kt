package com.example.sidequest

import com.google.gson.FieldNamingPolicy
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

class ApiException(message: String) : Exception(message)

object ApiClient {

    private val gson: Gson = GsonBuilder()
        .setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
        .create()

    private val jsonType = "application/json; charset=utf-8".toMediaType()

    // Long timeouts: Render's free tier sleeps and can take ~50s to wake up,
    // and looking up nearby places on OpenStreetMap can take a few seconds.
    private val client = OkHttpClient.Builder()
        .connectTimeout(90, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        // Every request carries the shared app key so the backend knows it's our app
        .addInterceptor { chain ->
            chain.proceed(chain.request().newBuilder().header("X-App-Key", BuildConfig.APP_KEY).build())
        }
        .build()

    suspend fun quests(body: QuestRequest): QuestResponse {
        val request = Request.Builder()
            .url(BuildConfig.BACKEND_URL + "quests")
            .post(gson.toJson(body).toRequestBody(jsonType))
            .build()
        return call(request, QuestResponse::class.java)
    }

    // Sends the photo proof; the open vision model decides if it completes the quest.
    suspend fun verify(photo: ByteArray, title: String, proof: String): VerifyResponse {
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("title", title)
            .addFormDataPart("proof", proof)
            .addFormDataPart("photo", "proof.jpg", photo.toRequestBody("image/jpeg".toMediaType()))
            .build()
        val request = Request.Builder()
            .url(BuildConfig.BACKEND_URL + "verify")
            .post(body)
            .build()
        return call(request, VerifyResponse::class.java)
    }

    private suspend fun <T> call(request: Request, type: Class<T>): T = withContext(Dispatchers.IO) {
        try {
            client.newCall(request).execute().use { response ->
                val text = response.body.string()
                if (!response.isSuccessful) {
                    throw ApiException(errorDetail(text) ?: "Server error ${response.code}")
                }
                gson.fromJson(text, type)
            }
        } catch (e: IOException) {
            throw ApiException("Can't reach the Side Quest server. Check your internet and try again.")
        }
    }

    // FastAPI errors look like {"detail": "..."}
    private fun errorDetail(text: String): String? = try {
        JsonParser.parseString(text).asJsonObject.get("detail")?.asString
    } catch (e: Exception) {
        null
    }
}
