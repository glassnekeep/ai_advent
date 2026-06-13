package org.example.task8

import org.example.utils.*

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

    fun ask(prompt: String): String {
        history.add(ChatMessage(role = "user", content = prompt))

        val request = ChatRequest(
            model = model,
            messages = history
        )

        val response = fetchResponseWithUsage(request)

        if (response != null) {
            val content = response.choices.firstOrNull()?.message?.content.orEmpty()
            val usage = response.usage

            // Сначала сохраняем в историю
            history.add(ChatMessage(role = "assistant", content = content))
            
            // Возвращаем контент, но перед этим выведем статистику (или выведем её в Main после вызова)
            // Чтобы статистика была ПОСЛЕ ответа в консоли, выведем её здесь, 
            // но в task8() нужно будет сначала напечатать ответ.
            // Однако, лучше всего печатать ответ ВНУТРИ ask или возвращать объект с данными.
            // Для простоты — печатаем ответ здесь, а потом статистику.
            
            println("\nАгент: $content")

            if (usage != null) {
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

            return content
        }

        return "Ошибка: Не удалось получить ответ от API."
    }

    fun reset() {
        history.clear()
        history.add(ChatMessage(role = "system", content = systemPrompt))
        sessionPromptTokens = 0
        sessionCompletionTokens = 0
        println("\n[Контекст и счетчики токенов сброшены]")
    }
}

fun task8() {
    println("=== Задание 8: Интерактивный агент с подсчетом токенов ===")
    val agent = TokenAgent()

    println("Введите ваш запрос.")
    println("Для выхода введите 'exit', для сброса истории — 'reset'.")
    println("Попробуйте отправить очень длинный текст, чтобы увидеть лимиты.")

    while (true) {
        print("\nВы: ")
        val input = readlnOrNull()?.trim() ?: break
        
        if (input.lowercase() == "exit") break
        if (input.lowercase() == "reset") {
            agent.reset()
            continue
        }
        if (input.isEmpty()) continue

        println("Агент думает...")
        agent.ask(input) // Печать ответа и статистики теперь внутри ask
    }
}
