package org.example

import org.example.mcp.finance.FinanceMcpServer
import org.example.mcp.weather.WeatherMcpServer
import org.example.task16.task16
import org.example.task17.task17
import org.example.task18.task18
import org.example.task19.task19
import org.example.task20.task20

fun main(args: Array<String>) {
    when (args.firstOrNull()) {
        "finance-mcp-server" -> {
            val host = System.getenv("FINANCE_MCP_HOST")?.takeIf { it.isNotBlank() } ?: "0.0.0.0"
            val port = System.getenv("FINANCE_MCP_PORT")?.toIntOrNull() ?: 3000
            FinanceMcpServer.run(host = host, port = port)
        }
        "weather-mcp-server" -> {
            val host = System.getenv("WEATHER_MCP_HOST")?.takeIf { it.isNotBlank() } ?: "0.0.0.0"
            val port = System.getenv("WEATHER_MCP_PORT")?.toIntOrNull() ?: 3001
            WeatherMcpServer.run(host = host, port = port)
        }
        "task16" -> task16()
        "task17" -> task17()
        "task18" -> task18()
        "task19" -> task19()
        "task20" -> task20()
        else -> task20()
    }
}
