package org.example.task3

import org.example.utils.ChatMessage
import org.example.utils.ChatRequest
import org.example.utils.DEFAULT_MODEL
import org.example.utils.fetchResponse

// VxEy P(x, y) -> EyVx P(x, y)
fun task3() {
    val puzzle = "Дана следующая формула в исчислении предикатов первого порядка: \$\\forall x \\exists y P(x, y) \\rightarrow \\exists y \\forall x P(x, y)\$." +
            " Является ли она общезначимой (тавтологией)? Докажи свой ответ формально. Если формула не общезначима, построй контрпример с минимально возможным" +
            " доменом, четко задав универсум \$U\$ и интерпретацию предиката \$P\$"

    println("=== 1: ПРЯМОЙ ОТВЕТ ===")
    println(sendRequest(listOf(ChatMessage("user", puzzle))))

    println("\n=== 2: ПОШАГОВОЕ РЕШЕНИЕ ===")
    println(sendRequest(listOf(ChatMessage("user", "$puzzle\nРешай задачу пошагово."))))

    println("\n=== 3: ГЕНЕРАЦИЯ ПРОМПТА И РЕШЕНИЕ ===")
    val metaPrompt = "Составь промпт для LLM, чтобы она максимально точно решила следующую задачу: $puzzle. В ответе выведи только текст самого промпта."
    val generatedPrompt = sendRequest(listOf(ChatMessage("user", metaPrompt)))
    println("Сгенерированный промпт:\n$generatedPrompt\n\nОтвет модели:")
    println(sendRequest(listOf(ChatMessage("user", generatedPrompt))))

    println("\n=== 4: ГРУППА ЭКСПЕРТОВ ===")
    val expertPrompt = """
        $puzzle
        Для решения задачи создай группу из трех экспертов:
        1. Программист.
        2. Преподаватель математической логики.
        3. Критик.
        Каждый должен высказать свое мнение, после чего выдайте итоговый ответ.
    """.trimIndent()
    println(sendRequest(listOf(ChatMessage("user", expertPrompt))))
}

private fun sendRequest(messages: List<ChatMessage>): String {
    val requestData = ChatRequest(
        model = DEFAULT_MODEL,
        messages = messages,
    )
    return fetchResponse(requestData)
}