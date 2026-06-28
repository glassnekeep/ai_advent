package org.example

import org.example.mcp.finance.FinanceMcpServer
import org.example.task16.task16
import org.example.task17.task17
import org.example.task18.task18

fun main(args: Array<String>) {
    when (args.firstOrNull()) {
        "finance-mcp-server" -> {
            val host = System.getenv("FINANCE_MCP_HOST")?.takeIf { it.isNotBlank() } ?: "0.0.0.0"
            val port = System.getenv("FINANCE_MCP_PORT")?.toIntOrNull() ?: 3000
            FinanceMcpServer.run(host = host, port = port)
        }
        "task16" -> task16()
        "task17" -> task17()
        "task18" -> task18()
        else -> task18()
    }
}
