package org.example.utils

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

const val URL = "https://api.deepseek.com/chat/completions"
const val DEFAULT_MODEL = "deepseek-chat"

val json = Json {
    encodeDefaults = false
    ignoreUnknownKeys = true
}

@Serializable
data class ChatMessage(val role: String, val content: String)

@Serializable
data class ChatRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val temperature: Double? = null,
    val max_tokens: Int? = null,
    val stop: List<String>? = null,
    val thinking: ThinkingConfig? = null
)

@Serializable
data class ChatResponse(
    val choices: List<Choice>,
    val usage: Usage? = null
)

@Serializable
data class Choice(val message: ChatMessage)

@Serializable
data class Usage(
    val prompt_tokens: Int = 0,
    val completion_tokens: Int = 0,
    val total_tokens: Int = 0
)

@Serializable
data class ThinkingConfig(val type: String)

val client = OkHttpClient.Builder()
    .readTimeout(60, TimeUnit.SECONDS)
    .build()

fun getApiKey(): String = System.getenv("DEEPSEEK_API_KEY").orEmpty()

inline fun <reified T> buildRequest(requestData: T, url: String = URL, apiKey: String = getApiKey()): Request {
    val requestBody = json.encodeToString(requestData)
        .toRequestBody("application/json; charset=utf-8".toMediaType())

    return Request.Builder()
        .url(url)
        .post(requestBody)
        .addHeader("Authorization", "Bearer $apiKey")
        .build()
}

fun fetchResponse(requestData: ChatRequest): String {
    val response = fetchResponseWithUsage(requestData)
    return response?.choices?.firstOrNull()?.message?.content ?: "Ошибка при получении ответа"
}

fun fetchResponseWithUsage(requestData: ChatRequest): ChatResponse? {
    val request = buildRequest(requestData)

    return try {
        client.newCall(request).execute().use { response ->
            val responseBody = response.body?.string()

            if (!response.isSuccessful) {
                println("Ошибка\n код: ${response.code}\n Тело ответа: $responseBody")
                null
            } else if (responseBody.isNullOrBlank()) {
                println("Ошибка: Пустой ответ от сервера")
                null
            } else {
                json.decodeFromString<ChatResponse>(responseBody)
            }
        }
    } catch (e: Exception) {
        println("Ошибка при выполнении запроса: ${e.message}")
        null
    }
}