package org.example.task5

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.example.utils.ChatMessage
import org.example.utils.ChatRequest
import org.example.utils.URL
import org.example.utils.client
import org.example.utils.json
import kotlin.system.measureTimeMillis

@Serializable
private data class DetailedChatResponse(
    val choices: List<Choice> = emptyList(),
    val usage: Usage? = null
)

@Serializable
private data class Choice(val message: ChatMessage)

@Serializable
private data class Usage(val total_tokens: Int)

private data class ModelConfig(
    val tier: String,
    val modelName: String,
    val url: String,
    val apiKey: String
)

fun task5() {
    val prompt = "Объясни что такое 3бет в покере и когда его стоит использовать"

    val deepseekKey = System.getenv("DEEPSEEK_API_KEY").orEmpty()
    val openRouterKey = System.getenv("OPENROUTER_API_KEY").orEmpty()
    val openRouterUrl = "https://openrouter.ai/api/v1/chat/completions"

    val models = listOf(
        ModelConfig("Слабая", "nvidia/nemotron-nano-9b-v2:free", openRouterUrl, openRouterKey),
        ModelConfig("Средняя", "google/gemma-4-31b-it:free", openRouterUrl, openRouterKey),
        ModelConfig("Сильная", "deepseek-chat", URL, deepseekKey)
    )

    for (config in models) {
        println("=== ${config.tier} | МОДЕЛЬ: ${config.modelName} ===")

        val requestData = ChatRequest(
            model = config.modelName,
            messages = listOf(ChatMessage(role = "user", content = prompt))
        )

        val requestBody = json.encodeToString(requestData)
            .toRequestBody("application/json; charset=utf-8".toMediaType())

        val request = Request.Builder()
            .url(config.url)
            .post(requestBody)
            .addHeader("Authorization", "Bearer ${config.apiKey}")
            .build()

        var tokens = 0
        var content = ""

        val time = measureTimeMillis {
            client.newCall(request).execute().use { response ->
                val responseBody = response.body?.string()
                if (response.isSuccessful && !responseBody.isNullOrBlank()) {
                    val parsed = json.decodeFromString<DetailedChatResponse>(responseBody)
                    content = parsed.choices.firstOrNull()?.message?.content.orEmpty()
                    tokens = parsed.usage?.total_tokens ?: 0
                } else {
                    content = "Ошибка HTTP Код: ${response.code}\nТекст: $responseBody"
                }
            }
        }

        println(content)
        println("\n[СТАТИСТИКА]")
        println("Время ответа: $time мс")
        println("Токенов: $tokens")
        println("======================================================\n")
    }
}