package org.example.mcp

import io.ktor.client.HttpClient
import io.ktor.client.plugins.sse.SSE
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.StreamableHttpClientTransport
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.Tool

class McpSdkDiscoveryClient(
    private val serverUrl: String,
    private val clientName: String = "ai-advent-kotlin-client",
    private val clientVersion: String = "1.0.0"
) {
    suspend fun listTools(): List<Tool> {
        val httpClient = HttpClient { install(SSE) }
        try {
            val client = Client(
                clientInfo = Implementation(
                    name = clientName,
                    version = clientVersion
                )
            )

            val transport = StreamableHttpClientTransport(
                client = httpClient,
                url = serverUrl
            )

            client.connect(transport)
            return client.listTools().tools
        } finally {
            httpClient.close()
        }
    }
}
