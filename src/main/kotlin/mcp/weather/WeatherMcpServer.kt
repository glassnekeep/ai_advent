package org.example.mcp.weather

import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.mcpStreamableHttp
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.time.Instant
import java.util.concurrent.TimeUnit

object WeatherMcpServer {
    fun run(host: String = "0.0.0.0", port: Int = 3001) {
        embeddedServer(CIO, host = host, port = port) {
            mcpStreamableHttp(path = "/mcp") {
                createServer()
            }
        }.start(wait = true)
    }

    fun createServer(api: WeatherApi = WeatherApi()): Server {
        val server = Server(
            serverInfo = Implementation(
                name = "ai-advent-weather-mcp",
                version = "1.0.0"
            ),
            options = ServerOptions(
                capabilities = ServerCapabilities(
                    tools = ServerCapabilities.Tools(listChanged = true)
                )
            )
        )

        server.addTool(
            name = "geocode_city",
            description = "Find latitude, longitude and country for a city name via Open-Meteo Geocoding API. Returns JSON that can be passed to get_weather_forecast.",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    put(
                        "city",
                        buildJsonObject {
                            put("type", "string")
                            put("description", "City name, for example Tokyo or Berlin.")
                        }
                    )
                    put(
                        "country",
                        buildJsonObject {
                            put("type", "string")
                            put("description", "Optional country hint, for example Japan or Germany.")
                        }
                    )
                },
                required = listOf("city")
            )
        ) { request: CallToolRequest ->
            val arguments = request.arguments
            val city = arguments?.get("city")?.jsonPrimitive?.contentOrNull.orEmpty()
            val country = arguments?.get("country")?.jsonPrimitive?.contentOrNull

            runCatching {
                api.geocode(city = city, country = country)
            }.fold(
                onSuccess = { text -> CallToolResult(content = listOf(TextContent(text))) },
                onFailure = { error ->
                    CallToolResult(
                        content = listOf(TextContent(error.message ?: "Unknown geocoding error.")),
                        isError = true
                    )
                }
            )
        }

        server.addTool(
            name = "get_weather_forecast",
            description = "Get current and daily weather forecast via Open-Meteo Forecast API. Accepts location_json from geocode_city or latitude/longitude directly.",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    put(
                        "location_json",
                        buildJsonObject {
                            put("type", buildJsonArray {
                                add(JsonPrimitive("string"))
                                add(JsonPrimitive("object"))
                            })
                            put("description", "JSON returned by geocode_city, either as a string or object.")
                        }
                    )
                    put("latitude", numberSchema("Latitude, used when location_json is not provided."))
                    put("longitude", numberSchema("Longitude, used when location_json is not provided."))
                    put(
                        "days",
                        buildJsonObject {
                            put("type", "integer")
                            put("description", "Forecast days from 1 to 7. Defaults to 3.")
                            put("minimum", 1)
                            put("maximum", 7)
                        }
                    )
                }
            )
        ) { request: CallToolRequest ->
            val arguments = request.arguments
            val location = arguments?.get("location_json")?.let(::parseLocationPayload)
            val latitude = location?.latitude ?: arguments?.get("latitude")?.jsonPrimitive?.doubleOrNull
            val longitude = location?.longitude ?: arguments?.get("longitude")?.jsonPrimitive?.doubleOrNull
            val city = location?.city
            val country = location?.country
            val days = arguments?.get("days")?.jsonPrimitive?.intOrNull ?: 3

            runCatching {
                require(latitude != null && longitude != null) {
                    "Either location_json or latitude/longitude must be provided."
                }
                api.forecast(
                    latitude = latitude,
                    longitude = longitude,
                    city = city,
                    country = country,
                    days = days.coerceIn(1, 7)
                )
            }.fold(
                onSuccess = { text -> CallToolResult(content = listOf(TextContent(text))) },
                onFailure = { error ->
                    CallToolResult(
                        content = listOf(TextContent(error.message ?: "Unknown weather forecast error.")),
                        isError = true
                    )
                }
            )
        }

        server.addTool(
            name = "summarize_weather",
            description = "Summarize JSON returned by get_weather_forecast. Does not fetch external data.",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    put(
                        "forecast_json",
                        buildJsonObject {
                            put("type", buildJsonArray {
                                add(JsonPrimitive("string"))
                                add(JsonPrimitive("object"))
                            })
                            put("description", "JSON returned by get_weather_forecast, either as a string or object.")
                        }
                    )
                },
                required = listOf("forecast_json")
            )
        ) { request: CallToolRequest ->
            val forecastJson = request.arguments?.get("forecast_json")?.asJsonPayload().orEmpty()

            runCatching {
                WeatherSummary.summarize(forecastJson)
            }.fold(
                onSuccess = { text -> CallToolResult(content = listOf(TextContent(text))) },
                onFailure = { error ->
                    CallToolResult(
                        content = listOf(TextContent(error.message ?: "Unknown weather summary error.")),
                        isError = true
                    )
                }
            )
        }

        return server
    }
}

class WeatherApi(
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()
) {
    private val json = Json { ignoreUnknownKeys = true }

    fun geocode(city: String, country: String?): String {
        require(city.isNotBlank()) { "city must not be blank." }

        val url = "https://geocoding-api.open-meteo.com/v1/search".toHttpUrl().newBuilder()
            .addQueryParameter("name", city)
            .addQueryParameter("count", "5")
            .addQueryParameter("language", "en")
            .addQueryParameter("format", "json")
            .build()

        val root = getJson(url.toString()).jsonObject
        val candidates = root["results"]?.jsonArray.orEmpty()
        val selected = candidates.firstOrNull { candidate ->
            country == null || candidate.jsonObject["country"]?.jsonPrimitive?.contentOrNull
                ?.contains(country, ignoreCase = true) == true
        } ?: candidates.firstOrNull()
            ?: throw IllegalStateException("Open-Meteo geocoding returned no results for '$city'.")

        val item = selected.jsonObject
        return Json.encodeToString(
            buildJsonObject {
                put("type", "geocoded_location")
                put("provider", "Open-Meteo Geocoding API")
                put("created_at", Instant.now().toString())
                put("city", item["name"]?.jsonPrimitive?.contentOrNull.orEmpty())
                put("country", item["country"]?.jsonPrimitive?.contentOrNull.orEmpty())
                put("timezone", item["timezone"]?.jsonPrimitive?.contentOrNull.orEmpty())
                put("latitude", item["latitude"]?.jsonPrimitive?.doubleOrNull ?: 0.0)
                put("longitude", item["longitude"]?.jsonPrimitive?.doubleOrNull ?: 0.0)
            }
        )
    }

    fun forecast(latitude: Double, longitude: Double, city: String?, country: String?, days: Int): String {
        val url = "https://api.open-meteo.com/v1/forecast".toHttpUrl().newBuilder()
            .addQueryParameter("latitude", latitude.toString())
            .addQueryParameter("longitude", longitude.toString())
            .addQueryParameter("current", "temperature_2m,wind_speed_10m")
            .addQueryParameter("daily", "temperature_2m_max,temperature_2m_min,precipitation_probability_max")
            .addQueryParameter("forecast_days", days.toString())
            .addQueryParameter("timezone", "auto")
            .build()

        val root = getJson(url.toString()).jsonObject
        return Json.encodeToString(
            buildJsonObject {
                put("type", "weather_forecast")
                put("provider", "Open-Meteo Forecast API")
                put("created_at", Instant.now().toString())
                put("city", city ?: "")
                put("country", country ?: "")
                put("latitude", latitude)
                put("longitude", longitude)
                put("timezone", root["timezone"]?.jsonPrimitive?.contentOrNull.orEmpty())
                put("current", root["current"] ?: JsonObject(emptyMap()))
                put("daily", root["daily"] ?: JsonObject(emptyMap()))
            }
        )
    }

    private fun getJson(url: String): JsonElement {
        val request = Request.Builder()
            .url(url)
            .get()
            .build()

        httpClient.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IllegalStateException("Open-Meteo HTTP ${response.code}: ${body.ifBlank { response.message }}")
            }
            if (body.isBlank()) {
                throw IllegalStateException("Open-Meteo returned an empty response.")
            }
            return json.parseToJsonElement(body)
        }
    }
}

private data class WeatherLocation(
    val city: String,
    val country: String,
    val latitude: Double,
    val longitude: Double
)

private object WeatherSummary {
    fun summarize(forecastJson: String): String {
        val root = Json.parseToJsonElement(forecastJson).jsonObject
        val city = root["city"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val country = root["country"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val current = root["current"]?.jsonObject ?: JsonObject(emptyMap())
        val daily = root["daily"]?.jsonObject ?: JsonObject(emptyMap())

        val dates = daily["time"]?.jsonArray.orEmpty().map { it.jsonPrimitive.contentOrNull.orEmpty() }
        val maxTemps = daily["temperature_2m_max"]?.jsonArray.orEmpty().map { it.jsonPrimitive.doubleOrNull }
        val minTemps = daily["temperature_2m_min"]?.jsonArray.orEmpty().map { it.jsonPrimitive.doubleOrNull }
        val rainProb = daily["precipitation_probability_max"]?.jsonArray.orEmpty().map { it.jsonPrimitive.intOrNull }
        val avgMax = maxTemps.filterNotNull().average().takeIf { !it.isNaN() }
        val maxRain = rainProb.filterNotNull().maxOrNull()

        val markdown = buildString {
            appendLine("# Weather Summary")
            appendLine()
            appendLine("- Location: ${listOf(city, country).filter { it.isNotBlank() }.joinToString(", ").ifBlank { "unknown" }}")
            appendLine("- Current temperature: ${current["temperature_2m"]?.jsonPrimitive?.contentOrNull ?: "unknown"} C")
            appendLine("- Current wind speed: ${current["wind_speed_10m"]?.jsonPrimitive?.contentOrNull ?: "unknown"} km/h")
            if (avgMax != null) appendLine("- Average daily max temperature: ${"%.1f".format(avgMax)} C")
            if (maxRain != null) appendLine("- Max precipitation probability: $maxRain%")
            appendLine()
            appendLine("| Date | Min C | Max C | Rain probability |")
            appendLine("|---|---:|---:|---:|")
            dates.forEachIndexed { index, date ->
                appendLine("| $date | ${minTemps.getOrNull(index) ?: "?"} | ${maxTemps.getOrNull(index) ?: "?"} | ${rainProb.getOrNull(index) ?: "?"}% |")
            }
        }.trim()

        return Json.encodeToString(
            buildJsonObject {
                put("type", "weather_summary")
                put("created_at", Instant.now().toString())
                put("city", city)
                put("country", country)
                put("average_max_temperature_c", avgMax ?: 0.0)
                put("max_precipitation_probability", maxRain ?: 0)
                put("markdown_summary", markdown)
            }
        )
    }
}

private fun parseLocationPayload(element: JsonElement): WeatherLocation {
    val root = Json.parseToJsonElement(element.asJsonPayload()).jsonObject
    return WeatherLocation(
        city = root["city"]?.jsonPrimitive?.contentOrNull.orEmpty(),
        country = root["country"]?.jsonPrimitive?.contentOrNull.orEmpty(),
        latitude = root["latitude"]?.jsonPrimitive?.doubleOrNull
            ?: throw IllegalArgumentException("location_json.latitude is required."),
        longitude = root["longitude"]?.jsonPrimitive?.doubleOrNull
            ?: throw IllegalArgumentException("location_json.longitude is required.")
    )
}

private fun JsonElement.asJsonPayload(): String {
    return if (this is JsonPrimitive) {
        contentOrNull ?: toString()
    } else {
        toString()
    }
}

private fun numberSchema(description: String) = buildJsonObject {
    put("type", "number")
    put("description", description)
}
