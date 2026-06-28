package org.example.task17

import kotlinx.coroutines.runBlocking
import org.example.agents.mcp.McpEnabledAssistant

fun task17() = runBlocking {
    val mcpServerUrl = System.getenv("FINANCE_MCP_URL")
        ?.trim()
        ?.takeIf { it.isNotBlank() }
        ?: "http://127.0.0.1:3000/mcp"

    val useTaskStateMachine = System.getenv("MCP_AGENT_STATE_MACHINE")
        ?.trim()
        ?.equals("true", ignoreCase = true)
        ?: false

    McpEnabledAssistant(
        mcpServerUrl = mcpServerUrl,
        title = "=== Задание 17: Агент с MCP-инструментом ===",
        useTaskStateMachine = useTaskStateMachine
    ).run()
}
