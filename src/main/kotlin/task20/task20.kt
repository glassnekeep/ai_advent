package org.example.task20

import io.modelcontextprotocol.kotlin.sdk.types.Tool
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.example.mcp.FinanceMcpAgent
import org.example.utils.ChatMessage
import org.example.utils.ChatRequest
import org.example.utils.DEFAULT_MODEL
import org.example.utils.fetchResponseWithUsage

fun task20() = runBlocking {
    val financeUrl = System.getenv("FINANCE_MCP_URL")
        ?.trim()
        ?.takeIf { it.isNotBlank() }
        ?: "http://127.0.0.1:3000/mcp"
    val weatherUrl = System.getenv("WEATHER_MCP_URL")
        ?.trim()
        ?.takeIf { it.isNotBlank() }
        ?: "http://127.0.0.1:3001/mcp"

    MultiMcpToolCallingChat(
        servers = listOf(
            McpEndpoint(
                id = "finance",
                url = financeUrl,
                allowedToolNames = setOf("get_exchange_rate", "fetch_exchange_rates", "analyze_exchange_rates")
            ),
            McpEndpoint(
                id = "weather",
                url = weatherUrl,
                allowedToolNames = setOf("geocode_city", "get_weather_forecast", "summarize_weather")
            )
        )
    ).run()
}

private data class McpEndpoint(
    val id: String,
    val url: String,
    val allowedToolNames: Set<String>
)

private data class ConnectedMcpServer(
    val endpoint: McpEndpoint,
    val client: FinanceMcpAgent,
    val tools: List<Tool>
)

private class MultiMcpToolCallingChat(
    private val servers: List<McpEndpoint>,
    private val model: String = DEFAULT_MODEL,
    private val maxToolIterations: Int = 10
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun run() {
        println("=== Задание 20: Чат-агент с несколькими MCP-серверами ===")
        println("Команды: /servers, /tools, /exit")

        val connectedServers = servers.map { endpoint ->
            val client = FinanceMcpAgent(
                serverUrl = endpoint.url,
                clientName = "ai-advent-multi-mcp-agent"
            )
            val tools = client.listTools()
                .filter { it.name in endpoint.allowedToolNames }
                .sortedBy { it.name }

            require(tools.isNotEmpty()) {
                "MCP server '${endpoint.id}' returned no allowed tools. URL: ${endpoint.url}"
            }

            ConnectedMcpServer(endpoint = endpoint, client = client, tools = tools)
        }

        printServers(connectedServers)
        printTools(connectedServers)

        while (true) {
            print("\n[chat] user: ")
            val input = readlnOrNull()?.trim() ?: break
            if (input.isEmpty()) continue

            when (input) {
                "/exit" -> break
                "/servers" -> printServers(connectedServers)
                "/tools" -> printTools(connectedServers)
                else -> handleUserInput(input, connectedServers)
            }
        }
    }

    private suspend fun handleUserInput(input: String, servers: List<ConnectedMcpServer>) {
        val messages = mutableListOf(
            ChatMessage(role = "system", content = systemPrompt(servers)),
            ChatMessage(role = "user", content = input)
        )
        val toolCalls = mutableListOf<String>()

        repeat(maxToolIterations) {
            val raw = askModel(messages) ?: return
            val decision = MultiToolDecision.parse(raw, json)

            if (decision == null) {
                println("\nАгент: $raw")
                return
            }

            when (decision.action) {
                "final" -> {
                    println("\n[Flow] Вызовы MCP: ${if (toolCalls.isEmpty()) "нет" else toolCalls.joinToString(" -> ")}")
                    println("\nАгент: ${decision.answer.orEmpty()}")
                    return
                }
                "tool" -> {
                    val serverId = decision.server.orEmpty()
                    val toolName = decision.tool.orEmpty()
                    val server = servers.firstOrNull { it.endpoint.id == serverId }

                    if (server == null) {
                        println("\nАгент: Модель выбрала неизвестный MCP server: $serverId")
                        return
                    }
                    if (server.tools.none { it.name == toolName }) {
                        println("\nАгент: Модель выбрала недоступный tool '$toolName' на сервере '$serverId'.")
                        return
                    }

                    println("[LLM] выбрала MCP server: $serverId")
                    println("[LLM] выбрала tool: $toolName")
                    println("[MCP:$serverId] callTool $toolName(${decision.arguments})")

                    val result = server.client.callTextTool(toolName, decision.arguments)
                    println("[MCP:$serverId result]")
                    println(result)

                    toolCalls += "$serverId.$toolName"
                    messages += ChatMessage(role = "assistant", content = raw)
                    messages += ChatMessage(
                        role = "user",
                        content = """
                            TOOL RESULT
                            server: $serverId
                            tool: $toolName
                            result:
                            $result

                            Continue. If another MCP server/tool is needed, return the next tool JSON.
                            Otherwise return final JSON. Check the original user request before final.
                        """.trimIndent()
                    )
                }
                else -> {
                    println("\nАгент: Неизвестное действие модели: ${decision.action}")
                    return
                }
            }
        }

        println("\nАгент: Остановился после $maxToolIterations MCP-вызовов, чтобы не уйти в бесконечный цикл.")
    }

    private fun askModel(messages: List<ChatMessage>): String? {
        println("Агент думает...")
        val response = fetchResponseWithUsage(
            ChatRequest(
                model = model,
                messages = messages,
                temperature = 0.0
            )
        )
        return response?.choices?.firstOrNull()?.message?.content?.trim()
    }

    private fun systemPrompt(servers: List<ConnectedMcpServer>): String {
        return """
            Ты чат-агент с доступом к нескольким MCP-серверам. Пользователь видит обычный чат, но ты можешь вызывать MCP tools.

            Доступные MCP-серверы и tools:
            ${servers.joinToString("\n\n") { serverDescription(it) }}

            Правила:
            - Сам выбирай нужный MCP server и tool по смыслу запроса.
            - Если задача требует несколько действий, вызывай только один tool за один ответ.
            - После TOOL RESULT выбери следующий server/tool, если он нужен.
            - Перед final проверь исходный запрос пользователя: все явно запрошенные действия должны быть выполнены через подходящие tools.
            - Не выдумывай результаты tools и пути файлов.
            - Возвращай строго JSON без markdown.

            Формат вызова tool:
            {"action":"tool","server":"server_id","tool":"tool_name","arguments":{"arg":"value"}}

            Формат финального ответа:
            {"action":"final","answer":"ответ пользователю"}
        """.trimIndent()
    }

    private fun serverDescription(server: ConnectedMcpServer): String {
        return buildString {
            append("server: ${server.endpoint.id}\n")
            append("url: ${server.endpoint.url}\n")
            server.tools.forEach { tool ->
                append("- tool: ${tool.name}\n")
                append("  description: ${tool.description.orEmpty()}\n")
                append("  input_schema: ${tool.inputSchema}\n")
            }
        }.trimEnd()
    }

    private fun printServers(servers: List<ConnectedMcpServer>) {
        println("\nMCP servers:")
        servers.forEach { server ->
            println("- ${server.endpoint.id}: ${server.endpoint.url}")
        }
    }

    private fun printTools(servers: List<ConnectedMcpServer>) {
        println("\nДоступные MCP tools:")
        servers.forEach { server ->
            server.tools.forEach { tool ->
                println("- ${server.endpoint.id}.${tool.name}: ${tool.description.orEmpty()}")
            }
        }
    }
}

private data class MultiToolDecision(
    val action: String,
    val server: String?,
    val tool: String?,
    val arguments: Map<String, Any>,
    val answer: String?
) {
    companion object {
        fun parse(raw: String, json: Json): MultiToolDecision? {
            val jsonText = extractJsonObject(raw) ?: return null
            val root = runCatching { json.parseToJsonElement(jsonText).jsonObject }.getOrNull() ?: return null
            val action = root["action"]?.jsonPrimitive?.contentOrNull ?: return null
            val arguments = root["arguments"]?.jsonObject?.toPlainMap().orEmpty()

            return MultiToolDecision(
                action = action,
                server = root["server"]?.jsonPrimitive?.contentOrNull,
                tool = root["tool"]?.jsonPrimitive?.contentOrNull,
                arguments = arguments,
                answer = root["answer"]?.jsonPrimitive?.contentOrNull
            )
        }

        private fun extractJsonObject(raw: String): String? {
            val cleaned = raw
                .removePrefix("```json")
                .removePrefix("```")
                .removeSuffix("```")
                .trim()
            if (cleaned.startsWith("{") && cleaned.endsWith("}")) return cleaned

            val start = cleaned.indexOf('{')
            val end = cleaned.lastIndexOf('}')
            if (start < 0 || end <= start) return null
            return cleaned.substring(start, end + 1)
        }
    }
}

private fun JsonObject.toPlainMap(): Map<String, Any> {
    return entries.associate { (key, value) -> key to value.toPlainValue() }
}

private fun JsonElement.toPlainValue(): Any {
    return when (this) {
        is JsonObject -> toPlainMap()
        is JsonArray -> map { it.toPlainValue() }
        is JsonPrimitive -> {
            booleanOrNull
                ?: doubleOrNull
                ?: content
        }
        JsonNull -> ""
    }
}
