package org.example.task2

import org.example.utils.ChatMessage
import org.example.utils.ChatRequest
import org.example.utils.DEFAULT_MODEL
import org.example.utils.fetchResponse

fun task2() {
    val baseQuestion = "Объясни, что такое чистая функция в функциональном программировании."

    println("=== ЗАПРОС 1: БЕЗ ОГРАНИЧЕНИЙ ===")
    val request1 = ChatRequest(
        model = DEFAULT_MODEL,
        messages = listOf(ChatMessage(role = "user", content = baseQuestion))
    )
    println(fetchResponse(request1))

    println("=== ЗАПРОС 2: С ОГРАНИЧЕНИЯМИ ===")
    val constrainedQuestion = """
        $baseQuestion
        Формат: JSON с ключами: "term", "short_definition". Никакого дополнительного текста.
        Ограничение длины: Значение ключа "short_definition" должно быть не длиннее 15 слов.
        Условие завершения: Сразу после закрывающей фигурной скобки напиши <END>
    """.trimIndent()

    val request2 = ChatRequest(
        model = DEFAULT_MODEL,
        messages = listOf(ChatMessage(role = "user", content = constrainedQuestion)),
        max_tokens = 100,
        stop = listOf("<END>")
    )
    println(fetchResponse(request2))
}