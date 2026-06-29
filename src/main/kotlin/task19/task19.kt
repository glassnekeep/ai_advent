package org.example.task19

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

fun task19() = runBlocking {
    val mcpServerUrl = System.getenv("FINANCE_MCP_URL")
        ?.trim()
        ?.takeIf { it.isNotBlank() }
        ?: "http://127.0.0.1:3000/mcp"

    McpToolCallingChat(
        mcpServerUrl = mcpServerUrl,
        allowedToolNames = setOf(
            "fetch_exchange_rates",
            "analyze_exchange_rates",
            "save_exchange_report"
        )
    ).run()
}

private class McpToolCallingChat(
    private val mcpServerUrl: String,
    private val allowedToolNames: Set<String>,
    private val model: String = DEFAULT_MODEL,
    private val maxToolIterations: Int = 8
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val mcpAgent = FinanceMcpAgent(mcpServerUrl)

    suspend fun run() {
        println("=== Задание 19: Чат-агент с несколькими MCP tools ===")
        println("MCP server: $mcpServerUrl")
        println("Команды: /tools, /mcp, /exit")

        val tools = mcpAgent.listTools()
            .filter { it.name in allowedToolNames }
            .sortedBy { it.name }

        require(tools.size == allowedToolNames.size) {
            "MCP server returned ${tools.size} task19 tools, expected ${allowedToolNames.size}: $allowedToolNames"
        }

        println("\nДоступные task19 tools:")
        tools.forEach { tool -> println("- ${tool.name}: ${tool.description.orEmpty()}") }

        while (true) {
            print("\n[chat] user: ")
            val input = readlnOrNull()?.trim() ?: break
            if (input.isEmpty()) continue

            when (input) {
                "/exit" -> break
                "/mcp" -> println("MCP endpoint: $mcpServerUrl")
                "/tools" -> tools.forEach { tool -> println("- ${tool.name}: ${tool.description.orEmpty()}") }
                else -> handleUserInput(input, tools)
            }
        }
    }

    private suspend fun handleUserInput(input: String, tools: List<Tool>) {
        val messages = mutableListOf(
            ChatMessage(role = "system", content = systemPrompt(tools)),
            ChatMessage(role = "user", content = input)
        )

        repeat(maxToolIterations) {
            val raw = askModel(messages) ?: return
            val decision = ToolDecision.parse(raw, json)

            if (decision == null) {
                println("\nАгент: $raw")
                return
            }

            when (decision.action) {
                "final" -> {
                    println("\nАгент: ${decision.answer.orEmpty()}")
                    return
                }
                "tool" -> {
                    val toolName = decision.tool.orEmpty()
                    if (toolName !in allowedToolNames) {
                        println("\nАгент: Модель запросила недоступный tool: $toolName")
                        return
                    }

                    println("[LLM] выбрала tool: $toolName")
                    println("[MCP] callTool $toolName(${decision.arguments})")
                    val result = mcpAgent.callTextTool(toolName, decision.arguments)
                    println("[MCP result]")
                    println(result)

                    messages += ChatMessage(role = "assistant", content = raw)
                    messages += ChatMessage(
                        role = "user",
                        content = """
                            TOOL RESULT
                            tool: $toolName
                            result:
                            $result

                            Continue. If another MCP tool is needed, return the next tool JSON. Otherwise return final JSON.
                        """.trimIndent()
                    )
                }
                else -> {
                    println("\nАгент: Неизвестное действие модели: ${decision.action}")
                    return
                }
            }
        }

        println("\nАгент: Остановился после $maxToolIterations tool-итераций, чтобы не уйти в бесконечный цикл.")
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

    private fun systemPrompt(tools: List<Tool>): String {
        return """
            Ты чат-агент с доступом к MCP tools. Пользователь видит обычный чат, но ты можешь вызывать tools.

            Доступные MCP tools:
            ${tools.joinToString("\n\n") { toolDescription(it) }}

            Правила:
            - Сам решай, нужен ли tool, и какой именно.
            - Если задача требует несколько действий, вызывай только один tool за один ответ.
            - После TOOL RESULT выбери следующий tool, если он нужен.
            - Перед final проверь исходный запрос пользователя: все явно запрошенные действия должны быть выполнены через tools.
            - Не выдумывай результаты tools.
            - Возвращай строго JSON без markdown.

            Формат вызова tool:
            {"action":"tool","tool":"tool_name","arguments":{"arg":"value"}}

            Формат финального ответа:
            {"action":"final","answer":"ответ пользователю"}
        """.trimIndent()
    }

    private fun toolDescription(tool: Tool): String {
        return buildString {
            append("- name: ${tool.name}\n")
            append("  description: ${tool.description.orEmpty()}\n")
            append("  input_schema: ${tool.inputSchema}")
        }
    }
}

private data class ToolDecision(
    val action: String,
    val tool: String?,
    val arguments: Map<String, Any>,
    val answer: String?
) {
    companion object {
        fun parse(raw: String, json: Json): ToolDecision? {
            val jsonText = extractJsonObject(raw) ?: return null
            val root = runCatching { json.parseToJsonElement(jsonText).jsonObject }.getOrNull() ?: return null
            val action = root["action"]?.jsonPrimitive?.contentOrNull ?: return null
            val arguments = root["arguments"]?.jsonObject?.toPlainMap().orEmpty()

            return ToolDecision(
                action = action,
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
