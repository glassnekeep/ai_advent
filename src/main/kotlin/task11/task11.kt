package org.example.task11

import org.example.agents.memory.MarkdownMemoryStore
import org.example.agents.memory.MemoryLayer
import org.example.agents.memory.MemoryLayeredAgent

fun task11() {
    println("=== Задание 11: Явная модель памяти ассистента ===")
    println("Память автоматически обновляется в markdown-файлах.")

    val agent = MemoryLayeredAgent(store = MarkdownMemoryStore("assistant_memory"))

    println("\nКоманды:")
    println("/memory                   - показать все слои памяти")
    println("/files                    - показать файлы памяти")
    println("/clear short|work|long    - очистить слой памяти")
    println("/exit                     - выход")

    try {
        while (true) {
            print("\n${agent.statusLine()} Вы: ")
            val input = readlnOrNull()?.trim() ?: break
            if (input.isEmpty()) continue

            if (input.startsWith("/")) {
                if (!handleCommand(input, agent)) break
            } else {
                println("Агент думает...")
                agent.ask(input)
            }
        }
    } finally {
        agent.finishSession()
    }
}

private fun handleCommand(input: String, agent: MemoryLayeredAgent): Boolean {
    val parts = input.split(" ", limit = 3)

    when (parts[0]) {
        "/exit" -> return false
        "/memory" -> agent.printMemory()
        "/files" -> agent.printStoragePaths()
        "/clear" -> {
            val layer = parseLayer(parts.getOrNull(1))
            if (layer == null) {
                println("Используйте: /clear short|work|long")
                return true
            }

            agent.clear(layer)
            println("Очищен слой: $layer")
        }
        else -> println("Неизвестная команда.")
    }

    return true
}

private fun parseLayer(value: String?): MemoryLayer? {
    return when (value) {
        "short" -> MemoryLayer.SHORT_TERM
        "work", "working" -> MemoryLayer.WORKING
        "long" -> MemoryLayer.LONG_TERM
        else -> null
    }
}
