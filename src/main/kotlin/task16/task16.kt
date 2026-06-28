package org.example.task16

import io.modelcontextprotocol.kotlin.sdk.types.Tool
import kotlinx.coroutines.runBlocking
import org.example.mcp.McpSdkDiscoveryClient

fun task16() = runBlocking {
    println("=== Задание 16: Подключение MCP и список инструментов ===")

    val serverUrl = System.getenv("MCP_SERVER_URL")
        ?.trim()
        ?.takeIf { it.isNotBlank() }
        ?: "https://mcp.deepwiki.com/mcp"

    println("MCP server: $serverUrl")

    val tools = McpSdkDiscoveryClient(serverUrl).listTools()
    println("Соединение установлено.")
    printTools(tools)
}

private fun printTools(tools: List<Tool>) {
    println("\nДоступные инструменты (${tools.size}):")

    if (tools.isEmpty()) {
        println("- MCP server вернул пустой список tools.")
        return
    }

    tools.forEachIndexed { index, tool ->
        println("${index + 1}. ${tool.name}")
        tool.description?.takeIf { it.isNotBlank() }?.let { println("   $it") }
        println("   inputSchema:")
        println("     required: ${tool.inputSchema.required}")
        println("     properties: ${tool.inputSchema.properties}")
    }
}
