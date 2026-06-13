package org.example.task8

import org.example.utils.*
import java.io.File

/**
 * Агент с подсчетом токенов для каждого запроса и всей истории.
 */
class TokenAgent(
    private val model: String = DEFAULT_MODEL,
    private val systemPrompt: String = "Ты — полезный ИИ-помощник."
) {
    private val history = mutableListOf<ChatMessage>()
    
    // Накопленные токены за всю сессию
    private var sessionPromptTokens = 0
    private var sessionCompletionTokens = 0

    init {
        history.add(ChatMessage(role = "system", content = systemPrompt))
    }

    fun ask(prompt: String, printStats: Boolean = true): String {
        history.add(ChatMessage(role = "user", content = prompt))

        val request = ChatRequest(
            model = model,
            messages = history
        )

        val response = fetchResponseWithUsage(request)

        if (response != null) {
            val content = response.choices.firstOrNull()?.message?.content.orEmpty()
            val usage = response.usage

            history.add(ChatMessage(role = "assistant", content = content))
            
            println("\nАгент: $content")

            if (usage != null && printStats) {
                printUsage(usage)
            }

            return content
        } else {
            // Если произошла ошибка (например, переполнение), 
            // стоит сообщить об этом и, возможно, удалить последнее сообщение из истории,
            // чтобы можно было продолжить после очистки или если это был случайный сбой.
            // Но для демонстрации "поломки" оставим как есть.
            println("\n[!] Ошибка при запросе. Возможно, превышен лимит контекста.")
        }

        return "Ошибка: Не удалось получить ответ от API."
    }

    private fun printUsage(usage: Usage) {
        sessionPromptTokens += usage.prompt_tokens
        sessionCompletionTokens += usage.completion_tokens

        println("\n--- Статистика токенов ---")
        println("👉 Текущий запрос + История (prompt): ${usage.prompt_tokens}")
        println("👉 Ответ модели (completion): ${usage.completion_tokens}")
        println("👉 Всего за этот шаг: ${usage.total_tokens}")
        println("--------------------------")
        println("📊 Итого за сессию:")
        println("   Промпт (всего): $sessionPromptTokens")
        println("   Ответы (всего): $sessionCompletionTokens")
        println("   Общая стоимость (токены): ${sessionPromptTokens + sessionCompletionTokens}")
        println("--------------------------")
    }

    fun addHistorySilent(role: String, content: String) {
        history.add(ChatMessage(role = role, content = content))
    }

    fun reset() {
        history.clear()
        history.add(ChatMessage(role = "system", content = systemPrompt))
        sessionPromptTokens = 0
        sessionCompletionTokens = 0
        println("\n[Контекст и счетчики токенов сброшены]")
    }

    fun historySize() = history.size
}

fun task8() {
    println("=== Задание 8: Интерактивный агент с подсчетом токенов ===")
    val agent = TokenAgent()

    println("Введите ваш запрос.")
    println("Доступные команды:")
    println("  exit                - выход")
    println("  reset               - сброс истории")
    println("  /status             - показать размер текущей истории")
    println("  /load <filename>    - загрузить текстовый файл из папки task8")

    while (true) {
        print("\nВы: ")
        val input = readlnOrNull()?.trim() ?: break
        
        val lowerInput = input.lowercase()
        if (lowerInput == "exit") break
        if (lowerInput == "reset") {
            agent.reset()
            continue
        }

        if (lowerInput == "/status") {
            println("📊 Сообщений в истории: ${agent.historySize()}")
            continue
        }
        
        if (lowerInput.startsWith("/load")) {
            val fileName = input.split(" ").getOrNull(1)
            if (fileName == null) {
                println("❌ Укажите имя файла: /load <filename>")
                continue
            }

            val file = File("src/main/kotlin/task8/$fileName")
            if (file.exists()) {
                println("📂 Загрузка $fileName...")
                try {
                    val content = file.readText()
                    println("📊 Прочитано ${content.length} символов. Отправка в контекст...")
                    agent.ask("Вот содержимое файла $fileName:\n\n$content")
                    println("✅ Файл $fileName успешно добавлен в историю.")
                } catch (e: Exception) {
                    println("❌ Ошибка при чтении файла: ${e.message}")
                }
            } else {
                println("❌ Файл $fileName не найден в src/main/kotlin/task8/")
            }
            continue
        }

        if (input.isEmpty()) continue

        println("Агент думает...")
        agent.ask(input) // Печать ответа и статистики теперь внутри ask
    }
}
