package org.example.mcp.finance

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Locale
import java.util.concurrent.TimeUnit

data class ExchangeRateResult(
    val base: String,
    val quote: String,
    val date: String,
    val rate: Double,
    val amount: Double,
    val convertedAmount: BigDecimal
)

class ExchangeRateApi(
    private val baseUrl: String = "https://api.frankfurter.dev",
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()
) {
    private val json = Json { ignoreUnknownKeys = true }

    fun getRate(baseCurrency: String, quoteCurrency: String, amount: Double): ExchangeRateResult {
        val base = normalizeCurrency(baseCurrency)
        val quote = normalizeCurrency(quoteCurrency)
        require(base != quote) { "base and quote currencies must be different." }
        require(amount > 0.0 && amount <= 1_000_000_000.0) { "amount must be between 0 and 1,000,000,000." }

        val url = "$baseUrl/v2/rates".toHttpUrl().newBuilder()
            .addQueryParameter("base", base)
            .addQueryParameter("quotes", quote)
            .build()

        val request = Request.Builder()
            .url(url)
            .get()
            .build()

        httpClient.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IllegalStateException("Frankfurter API HTTP ${response.code}: ${body.ifBlank { response.message }}")
            }
            if (body.isBlank()) {
                throw IllegalStateException("Frankfurter API returned an empty response.")
            }

            val parsed = json.parseToJsonElement(body)
            val row = firstRateRow(parsed)
            val date = row["date"]?.jsonPrimitive?.content.orEmpty()
            val rate = row["rate"]?.jsonPrimitive?.doubleOrNull
                ?: row["rates"]?.jsonObject?.get(quote)?.jsonPrimitive?.doubleOrNull
                ?: throw IllegalStateException("Frankfurter API response does not contain rate for $quote.")

            val converted = BigDecimal.valueOf(amount)
                .multiply(BigDecimal.valueOf(rate))
                .setScale(4, RoundingMode.HALF_UP)

            return ExchangeRateResult(
                base = base,
                quote = quote,
                date = date,
                rate = rate,
                amount = amount,
                convertedAmount = converted
            )
        }
    }

    fun format(result: ExchangeRateResult): String {
        return buildString {
            append("${result.amount} ${result.base} = ${result.convertedAmount} ${result.quote}")
            append("\nRate: 1 ${result.base} = ${result.rate} ${result.quote}")
            append("\nDate: ${result.date}")
            append("\nProvider: Frankfurter public exchange rates API")
        }
    }

    private fun normalizeCurrency(value: String): String {
        val normalized = value.trim().uppercase(Locale.US)
        require(Regex("[A-Z]{3}").matches(normalized)) {
            "Currency code must be a 3-letter ISO code, for example EUR, USD, GBP."
        }
        return normalized
    }

    private fun firstRateRow(element: JsonElement): JsonObject {
        if (element is JsonArray) {
            return element.jsonArray.firstOrNull()?.jsonObject
                ?: throw IllegalStateException("Frankfurter API returned an empty rates array.")
        }

        return element.jsonObject
    }
}
