package org.example.task12

import org.example.agents.memory.MarkdownMemoryStore
import org.example.agents.memory.MemoryLayer
import org.example.agents.memory.MemoryLayeredAgent
import org.example.agents.memory.userMemoryStore
import org.example.agents.profile.UserProfileOnboarding

fun task12() {
    println("=== Задание 12: Персонализированный агент с профилем пользователя ===")

    val userName = UserProfileOnboarding.askUserName()
    val store = userMemoryStore(userName)
    UserProfileOnboarding.ensureProfile(userName, store)

    val agent = MemoryLayeredAgent(
        store = store,
        sessionContext = UserProfileOnboarding.sessionContext(userName)
    )

    println("\nКоманды:")
    println("/profile                  - показать профиль текущего пользователя")
    println("/memory                   - показать все слои памяти")
    println("/files                    - показать файлы памяти")
    println("/clear short|work|long    - очистить слой памяти")
    println("/exit                     - выход")

    try {
        while (true) {
            print("\n${agent.statusLine()} $userName: ")
            val input = readlnOrNull()?.trim() ?: break
            if (input.isEmpty()) continue

            if (input.startsWith("/")) {
                if (!handleCommand(input, userName, agent)) break
            } else {
                println("Агент думает...")
                agent.ask(input)
            }
        }
    } finally {
        agent.finishSession()
    }
}

private fun handleCommand(input: String, userName: String, agent: MemoryLayeredAgent): Boolean {
    val parts = input.split(" ", limit = 3)

    when (parts[0]) {
        "/exit" -> return false
        "/profile" -> UserProfileOnboarding.printCurrentProfile(userName, userMemoryStore(userName))
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
