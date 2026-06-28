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
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

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

        return server
    }
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
