package org.example.mcp.finance

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
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.time.Instant
import java.util.Locale

object FinanceMcpServer {
    fun run(host: String = "0.0.0.0", port: Int = 3000) {
        val monitor = ExchangeRateMonitor()
        embeddedServer(CIO, host = host, port = port) {
            mcpStreamableHttp(path = "/mcp") {
                createServer(monitor = monitor)
            }
        }.start(wait = true)
    }

    fun createServer(
        api: ExchangeRateApi = ExchangeRateApi(),
        monitor: ExchangeRateMonitor = ExchangeRateMonitor(api = api)
    ): Server {
        val server = Server(
            serverInfo = Implementation(
                name = "ai-advent-finance-mcp",
                version = "1.0.0"
            ),
            options = ServerOptions(
                capabilities = ServerCapabilities(
                    tools = ServerCapabilities.Tools(listChanged = true)
                )
            )
        )

        server.addTool(
            name = "get_exchange_rate",
            description = "Get a real exchange rate and converted amount via Frankfurter public API.",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    put(
                        "base",
                        buildJsonObject {
                            put("type", "string")
                            put("description", "Base currency ISO code, for example EUR")
                        }
                    )
                    put(
                        "quote",
                        buildJsonObject {
                            put("type", "string")
                            put("description", "Quote currency ISO code, for example USD")
                        }
                    )
                    put(
                        "amount",
                        buildJsonObject {
                            put("type", "number")
                            put("description", "Amount to convert. Defaults to 1.")
                            put("minimum", 0)
                            put("maximum", 1000000000)
                        }
                    )
                },
                required = listOf("base", "quote")
            )
        ) { request: CallToolRequest ->
            val arguments = request.arguments
            val base = arguments?.get("base")?.jsonPrimitive?.contentOrNull()?.trim().orEmpty()
            val quote = arguments?.get("quote")?.jsonPrimitive?.contentOrNull()?.trim().orEmpty()
            val amount = arguments?.get("amount")?.jsonPrimitive?.doubleOrNull ?: 1.0

            runCatching {
                api.format(api.getRate(baseCurrency = base, quoteCurrency = quote, amount = amount))
            }.fold(
                onSuccess = { text ->
                    CallToolResult(content = listOf(TextContent(text)))
                },
                onFailure = { error ->
                    CallToolResult(
                        content = listOf(TextContent(error.message ?: "Unknown exchange rate error.")),
                        isError = true
                    )
                }
            )
        }

        server.addTool(
            name = "start_exchange_rate_watch",
            description = "Start a periodic exchange-rate collection job. Samples are persisted to JSON.",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    put("base", currencySchema("Base currency ISO code, for example EUR"))
                    put("quote", currencySchema("Quote currency ISO code, for example USD"))
                    put(
                        "interval_seconds",
                        buildJsonObject {
                            put("type", "integer")
                            put("description", "Collection interval in seconds. Minimum is 10.")
                            put("minimum", 10)
                        }
                    )
                },
                required = listOf("base", "quote")
            )
        ) { request: CallToolRequest ->
            val arguments = request.arguments
            val base = arguments?.get("base")?.jsonPrimitive?.contentOrNull()?.trim().orEmpty()
            val quote = arguments?.get("quote")?.jsonPrimitive?.contentOrNull()?.trim().orEmpty()
            val intervalSeconds = arguments?.get("interval_seconds")?.jsonPrimitive?.longOrNull ?: 60L

            runCatching {
                monitor.start(baseCurrency = base, quoteCurrency = quote, intervalSeconds = intervalSeconds)
            }.fold(
                onSuccess = { text -> CallToolResult(content = listOf(TextContent(text))) },
                onFailure = { error ->
                    CallToolResult(
                        content = listOf(TextContent(error.message ?: "Unknown watch start error.")),
                        isError = true
                    )
                }
            )
        }

        server.addTool(
            name = "stop_exchange_rate_watch",
            description = "Stop the currently running exchange-rate collection job.",
            inputSchema = ToolSchema()
        ) {
            CallToolResult(content = listOf(TextContent(monitor.stop())))
        }

        server.addTool(
            name = "get_exchange_rate_summary",
            description = "Return aggregated summary for collected exchange-rate samples.",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    put("base", currencySchema("Base currency ISO code, for example EUR"))
                    put("quote", currencySchema("Quote currency ISO code, for example USD"))
                    put(
                        "last",
                        buildJsonObject {
                            put("type", "integer")
                            put("description", "How many latest samples to aggregate. Defaults to 20.")
                            put("minimum", 1)
                            put("maximum", 500)
                        }
                    )
                },
                required = listOf("base", "quote")
            )
        ) { request: CallToolRequest ->
            val arguments = request.arguments
            val base = arguments?.get("base")?.jsonPrimitive?.contentOrNull()?.trim().orEmpty()
            val quote = arguments?.get("quote")?.jsonPrimitive?.contentOrNull()?.trim().orEmpty()
            val last = arguments?.get("last")?.jsonPrimitive?.intOrNull ?: 20

            runCatching {
                monitor.summary(baseCurrency = base, quoteCurrency = quote, last = last)
            }.fold(
                onSuccess = { text -> CallToolResult(content = listOf(TextContent(text))) },
                onFailure = { error ->
                    CallToolResult(
                        content = listOf(TextContent(error.message ?: "Unknown summary error.")),
                        isError = true
                    )
                }
            )
        }

        server.addTool(
            name = "fetch_exchange_rates",
            description = "Fetch current exchange rates for one base currency and multiple quote currencies. Returns JSON that can be passed to analyze_exchange_rates.",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    put("base", currencySchema("Base currency ISO code, for example EUR"))
                    put(
                        "quotes",
                        buildJsonObject {
                            put("type", "array")
                            put("description", "Quote currency ISO codes, for example [\"USD\", \"GBP\", \"JPY\"].")
                            put(
                                "items",
                                buildJsonObject {
                                    put("type", "string")
                                }
                            )
                            put("minItems", 1)
                            put("maxItems", 10)
                        }
                    )
                    put(
                        "amount",
                        buildJsonObject {
                            put("type", "number")
                            put("description", "Amount to convert. Defaults to 1.")
                            put("minimum", 0)
                            put("maximum", 1000000000)
                        }
                    )
                },
                required = listOf("base", "quotes")
            )
        ) { request: CallToolRequest ->
            val arguments = request.arguments
            val base = arguments?.get("base")?.jsonPrimitive?.contentOrNull()?.trim().orEmpty()
            val quotes = arguments?.get("quotes")?.let(::parseQuotes).orEmpty()
            val amount = arguments?.get("amount")?.jsonPrimitive?.doubleOrNull ?: 1.0

            runCatching {
                require(quotes.isNotEmpty()) { "quotes must contain at least one currency code." }
                val results = quotes.distinct().map { quote ->
                    api.getRate(baseCurrency = base, quoteCurrency = quote, amount = amount)
                }

                Json.encodeToString(
                    buildJsonObject {
                        put("type", "exchange_rates")
                        put("provider", "Frankfurter public exchange rates API")
                        put("created_at", Instant.now().toString())
                        put("base", results.first().base)
                        put("amount", amount)
                        put(
                            "rates",
                            buildJsonArray {
                                results.forEach { result ->
                                    add(
                                        buildJsonObject {
                                            put("quote", result.quote)
                                            put("date", result.date)
                                            put("rate", result.rate)
                                            put("converted_amount", result.convertedAmount.toPlainString())
                                        }
                                    )
                                }
                            }
                        )
                    }
                )
            }.fold(
                onSuccess = { json -> CallToolResult(content = listOf(TextContent(json))) },
                onFailure = { error ->
                    CallToolResult(
                        content = listOf(TextContent(error.message ?: "Unknown exchange rates fetch error.")),
                        isError = true
                    )
                }
            )
        }

        server.addTool(
            name = "analyze_exchange_rates",
            description = "Analyze JSON returned by fetch_exchange_rates and produce a compact JSON report with markdown_summary. Accepts rates_json as a JSON string or object. Does not fetch external data.",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    put(
                        "rates_json",
                        buildJsonObject {
                            put("type", buildJsonArray {
                                add(JsonPrimitive("string"))
                                add(JsonPrimitive("object"))
                            })
                            put("description", "Raw JSON returned by fetch_exchange_rates, either as a string or as an object.")
                        }
                    )
                },
                required = listOf("rates_json")
            )
        ) { request: CallToolRequest ->
            val arguments = request.arguments
            val ratesJson = arguments?.get("rates_json")?.asJsonPayload().orEmpty()

            runCatching {
                val parsed = Json.parseToJsonElement(ratesJson).jsonObject
                val base = parsed["base"]?.jsonPrimitive?.content.orEmpty()
                val amount = parsed["amount"]?.jsonPrimitive?.doubleOrNull ?: 1.0
                val rates = parsed["rates"]?.jsonArray.orEmpty().map { element ->
                    val row = element.jsonObject
                    AnalyzedRate(
                        quote = row["quote"]?.jsonPrimitive?.content.orEmpty(),
                        date = row["date"]?.jsonPrimitive?.content.orEmpty(),
                        rate = row["rate"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
                        convertedAmount = row["converted_amount"]?.jsonPrimitive?.content.orEmpty()
                    )
                }.filter { it.quote.isNotBlank() }

                require(rates.isNotEmpty()) { "rates_json does not contain rates." }

                val strongest = rates.maxBy { it.rate }
                val weakest = rates.minBy { it.rate }
                val sorted = rates.sortedByDescending { it.rate }
                val markdown = buildString {
                    appendLine("# Exchange Rate Summary")
                    appendLine()
                    appendLine("- Base: $base")
                    appendLine("- Amount: $amount $base")
                    appendLine("- Best converted value: ${strongest.convertedAmount} ${strongest.quote} at ${strongest.rate}")
                    appendLine("- Lowest converted value: ${weakest.convertedAmount} ${weakest.quote} at ${weakest.rate}")
                    appendLine()
                    appendLine("| Quote | Rate | Converted amount | Date |")
                    appendLine("|---|---:|---:|---|")
                    sorted.forEach { rate ->
                        appendLine("| ${rate.quote} | ${rate.rate} | ${rate.convertedAmount} | ${rate.date} |")
                    }
                }.trim()

                Json.encodeToString(
                    buildJsonObject {
                        put("type", "exchange_rate_analysis")
                        put("created_at", Instant.now().toString())
                        put("base", base)
                        put("amount", amount)
                        put("strongest_quote", strongest.quote)
                        put("weakest_quote", weakest.quote)
                        put("markdown_summary", markdown)
                    }
                )
            }.fold(
                onSuccess = { json -> CallToolResult(content = listOf(TextContent(json))) },
                onFailure = { error ->
                    CallToolResult(
                        content = listOf(TextContent(error.message ?: "Unknown exchange rates analysis error.")),
                        isError = true
                    )
                }
            )
        }

        server.addTool(
            name = "save_exchange_report",
            description = "Save markdown_summary from analyze_exchange_rates JSON to assistant_memory/mcp/reports and return JSON with the saved file path. Accepts report_json as a JSON string or object.",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    put(
                        "report_json",
                        buildJsonObject {
                            put("type", buildJsonArray {
                                add(JsonPrimitive("string"))
                                add(JsonPrimitive("object"))
                            })
                            put("description", "JSON returned by analyze_exchange_rates, either as a string or as an object.")
                        }
                    )
                    put(
                        "file_name",
                        buildJsonObject {
                            put("type", "string")
                            put("description", "Markdown file name, for example eur_report.md. Defaults to exchange_report.md.")
                        }
                    )
                },
                required = listOf("report_json")
            )
        ) { request: CallToolRequest ->
            val arguments = request.arguments
            val reportJson = arguments?.get("report_json")?.asJsonPayload().orEmpty()
            val fileName = arguments?.get("file_name")?.jsonPrimitive?.contentOrNull()
                ?.takeIf { it.isNotBlank() }
                ?: "exchange_report.md"

            runCatching {
                val parsed = Json.parseToJsonElement(reportJson).jsonObject
                val markdown = parsed["markdown_summary"]?.jsonPrimitive?.contentOrNull()
                    ?.takeIf { it.isNotBlank() }
                    ?: throw IllegalArgumentException("report_json must contain non-empty markdown_summary.")

                val directory = File("assistant_memory/mcp/reports")
                directory.mkdirs()
                val file = File(directory, safeReportFileName(fileName))
                file.writeText("$markdown\n")

                Json.encodeToString(
                    buildJsonObject {
                        put("type", "exchange_report_saved")
                        put("path", file.path)
                        put("bytes", file.length())
                    }
                )
            }.fold(
                onSuccess = { json -> CallToolResult(content = listOf(TextContent(json))) },
                onFailure = { error ->
                    CallToolResult(
                        content = listOf(TextContent(error.message ?: "Unknown exchange report save error.")),
                        isError = true
                    )
                }
            )
        }

        return server
    }
}

private data class AnalyzedRate(
    val quote: String,
    val date: String,
    val rate: Double,
    val convertedAmount: String
)

private fun parseQuotes(element: JsonElement): List<String> {
    return when (element) {
        is JsonArray -> element.mapNotNull { it.jsonPrimitive.contentOrNull()?.trim()?.uppercase(Locale.US) }
        is JsonPrimitive -> element.contentOrNull()
            ?.split(",")
            ?.map { it.trim().uppercase(Locale.US) }
            .orEmpty()
        else -> emptyList()
    }.filter { it.isNotBlank() }
}

private fun JsonElement.asJsonPayload(): String {
    return if (this is JsonPrimitive) {
        contentOrNull() ?: toString()
    } else {
        toString()
    }
}

private fun safeReportFileName(value: String): String {
    val cleaned = value
        .replace(Regex("[^A-Za-z0-9._-]+"), "_")
        .trim('_')
        .ifBlank { "exchange_report.md" }
    return if (cleaned.endsWith(".md", ignoreCase = true)) cleaned else "$cleaned.md"
}

private fun JsonPrimitive.contentOrNull(): String? {
    return runCatching { content }.getOrNull()
}

private val JsonPrimitive.intOrNull: Int?
    get() = runCatching { content.toInt() }.getOrNull()

private val JsonPrimitive.longOrNull: Long?
    get() = runCatching { content.toLong() }.getOrNull()

private fun currencySchema(description: String) = buildJsonObject {
    put("type", "string")
    put("description", description)
}
