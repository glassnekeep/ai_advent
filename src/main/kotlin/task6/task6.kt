package org.example.task6

import org.example.utils.ChatMessage
import org.example.utils.ChatRequest
import org.example.utils.DEFAULT_MODEL
import org.example.utils.fetchResponse

/**
 * Агент для взаимодействия с LLM, поддерживающий контекст диалога.
 * Инкапсулирует историю сообщений и логику общения.
 */
class SimpleAgent(
    private val model: String = DEFAULT_MODEL,
    private val systemPrompt: String = "Ты — полезный ИИ-помощник."
) {
    // История сообщений для поддержания контекста
    private val history = mutableListOf(
        ChatMessage(role = "system", content = systemPrompt)
    )

    /**
     * Отправляет сообщение в LLM, сохраняя историю.
     */
    fun ask(prompt: String): String {
        // Добавляем сообщение пользователя в историю
        history.add(ChatMessage(role = "user", content = prompt))

        val request = ChatRequest(
            model = model,
            messages = history
        )
        
        val response = fetchResponse(request)

        // Если запрос успешен, сохраняем ответ ассистента в историю
        if (!response.startsWith("Ошибка")) {
            history.add(ChatMessage(role = "assistant", content = response))
        }

        return response
    }

    /**
     * Очищает историю диалога.
     */
    fun reset() {
        history.clear()
        history.add(ChatMessage(role = "system", content = systemPrompt))
    }
}

fun task6() {
    println("=== Задание 6: Первый Агент (Чат-режим) ===")
    println("Введите 'exit' для выхода или 'reset' для очистки истории.")
    
    val agent = SimpleAgent()
    
    while (true) {
        print("\nВы: ")
        val input = readlnOrNull()?.trim() ?: break
        
        if (input.lowercase() == "exit") break
        if (input.lowercase() == "reset") {
            agent.reset()
            println("История диалога очищена.")
            continue
        }
        if (input.isEmpty()) continue

        println("Агент думает...")
        val response = agent.ask(input)
        
        println("\nАгент: $response")
    }
    
    println("\n=== Чат завершен ===")
}
