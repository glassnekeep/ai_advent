package org.example.mcp

import io.ktor.client.HttpClient
import io.ktor.client.plugins.sse.SSE
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.StreamableHttpClientTransport
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.Tool

class FinanceMcpAgent(
    private val serverUrl: String,
    private val clientName: String = "ai-advent-finance-agent",
    private val clientVersion: String = "1.0.0"
) {
    suspend fun getExchangeRate(base: String, quote: String, amount: Double = 1.0): String {
        return callTextTool(
            name = "get_exchange_rate",
            arguments = mapOf(
                "base" to base,
                "quote" to quote,
                "amount" to amount
            )
        )
    }

    suspend fun startExchangeRateWatch(base: String, quote: String, intervalSeconds: Long): String {
        return callTextTool(
            name = "start_exchange_rate_watch",
            arguments = mapOf(
                "base" to base,
                "quote" to quote,
                "interval_seconds" to intervalSeconds
            )
        )
    }

    suspend fun stopExchangeRateWatch(): String {
        return callTextTool(
            name = "stop_exchange_rate_watch",
            arguments = emptyMap()
        )
    }

    suspend fun getExchangeRateSummary(base: String, quote: String, last: Int = 20): String {
        return callTextTool(
            name = "get_exchange_rate_summary",
            arguments = mapOf(
                "base" to base,
                "quote" to quote,
                "last" to last
            )
        )
    }

    suspend fun listTools(): List<Tool> {
        val httpClient = HttpClient { install(SSE) }
        try {
            val client = Client(
                clientInfo = Implementation(
                    name = clientName,
                    version = clientVersion
                )
            )
            val transport = StreamableHttpClientTransport(client = httpClient, url = serverUrl)
            client.connect(transport)

            return client.listTools().tools
        } finally {
            httpClient.close()
        }
    }

    suspend fun callTextTool(name: String, arguments: Map<String, Any>): String {
        val httpClient = HttpClient { install(SSE) }
        try {
            val client = Client(
                clientInfo = Implementation(
                    name = clientName,
                    version = clientVersion
                )
            )
            val transport = StreamableHttpClientTransport(client = httpClient, url = serverUrl)
            client.connect(transport)

            val result = client.callTool(name = name, arguments = arguments)

            return result.content.joinToString("\n") { content ->
                if (content is TextContent) content.text else content.toString()
            }
        } finally {
            httpClient.close()
        }
    }
}
