package org.example.task10

import org.example.agents.AdvancedAgent
import org.example.agents.Strategy

fun task10() {
    println("=== Задание 10: Разные стратегии управления контекстом ===")
    val agent = AdvancedAgent()

    println("\nВыберите начальную стратегию:")
    println("1. Sliding Window (Скользящее окно)")
    println("2. Sticky Facts (Липкие факты)")
    println("3. Branching (Автоматическое ветвление)")
    
    while (true) {
        print("Ваш выбор (1-3): ")
        val choice = readlnOrNull()?.trim()
        when (choice) {
            "1" -> { agent.setStrategy(Strategy.SLIDING_WINDOW); break }
            "2" -> { agent.setStrategy(Strategy.STICKY_FACTS); break }
            "3" -> { agent.setStrategy(Strategy.BRANCHING); break }
            else -> println("Пожалуйста, введите число от 1 до 3.")
        }
    }

    println("\nДоступные команды в процессе диалога:")
    println("/strategy [window|facts|branch] - сменить стратегию")
    println("/window [n] - задать размер окна")
    println("/facts - показать накопленные факты")
    println("/branch list - список веток (для автоматического режима)")
    println("/exit - выход")

    while (true) {
        print("\n${agent.getStatusLine()} Вы: ")
        val input = readlnOrNull()?.trim() ?: break
        if (input.isEmpty()) continue

        if (input.startsWith("/")) {
            val parts = input.split(" ")
            when (parts[0]) {
                "/exit" -> break
                "/strategy" -> {
                    when (parts.getOrNull(1)) {
                        "window" -> agent.setStrategy(Strategy.SLIDING_WINDOW)
                        "facts" -> agent.setStrategy(Strategy.STICKY_FACTS)
                        "branch" -> agent.setStrategy(Strategy.BRANCHING)
                        else -> println("Неизвестная стратегия.")
                    }
                }
                "/window" -> {
                    val size = parts.getOrNull(1)?.toIntOrNull() ?: 4
                    agent.setWindowSize(size)
                }
                "/facts" -> agent.printCurrentFacts()
                "/branch" -> {
                    when (parts.getOrNull(1)) {
                        "new" -> parts.getOrNull(2)?.let { agent.createBranch(it) }
                        "switch" -> parts.getOrNull(2)?.let { agent.switchBranch(it) }
                        "list" -> agent.listBranches()
                        else -> println("Используйте: /branch [new|switch|list] [name]")
                    }
                }
                else -> println("Неизвестная команда.")
            }
        } else {
            println("Агент думает...")
            agent.ask(input)
        }
    }
}
