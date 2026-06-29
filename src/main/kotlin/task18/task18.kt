package org.example.task18

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.example.mcp.FinanceMcpAgent
import java.time.Instant

fun task18() = runBlocking {
    println("=== Задание 18: Периодический MCP-инструмент и 24/7 агент ===")

    val mcpServerUrl = System.getenv("FINANCE_MCP_URL")
        ?.trim()
        ?.takeIf { it.isNotBlank() }
        ?: "http://127.0.0.1:3000/mcp"
    val base = System.getenv("FX_BASE")?.trim()?.takeIf { it.isNotBlank() } ?: "EUR"
    val quote = System.getenv("FX_QUOTE")?.trim()?.takeIf { it.isNotBlank() } ?: "USD"
    val collectIntervalSeconds = System.getenv("FX_COLLECT_INTERVAL_SECONDS")?.toLongOrNull() ?: 10L
    val summaryIntervalSeconds = System.getenv("FX_SUMMARY_INTERVAL_SECONDS")?.toLongOrNull() ?: 10L
    val lastSamples = System.getenv("FX_SUMMARY_LAST")?.toIntOrNull() ?: 20
    val maxIterations = System.getenv("FX_SUMMARY_ITERATIONS")?.toIntOrNull()

    val agent = FinanceMcpAgent(mcpServerUrl)

    println("MCP server: $mcpServerUrl")
    println("Watch: $base/$quote every ${collectIntervalSeconds.coerceAtLeast(10)}s")
    println("Summary: every ${summaryIntervalSeconds.coerceAtLeast(10)}s, last=$lastSamples")
    println("Mode: ${if (maxIterations == null) "24/7" else "$maxIterations iterations"}")

    println("\n[MCP] start_exchange_rate_watch")
    println(agent.startExchangeRateWatch(base = base, quote = quote, intervalSeconds = collectIntervalSeconds))

    var iteration = 0
    while (maxIterations == null || iteration < maxIterations) {
        iteration += 1
        println("\n=== Periodic summary #$iteration at ${Instant.now()} ===")
        println("[MCP] get_exchange_rate_summary")
        println(agent.getExchangeRateSummary(base = base, quote = quote, last = lastSamples))

        if (maxIterations != null && iteration >= maxIterations) break
        delay(summaryIntervalSeconds.coerceAtLeast(10) * 1000)
    }
}
