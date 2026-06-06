package org.example.task4

import org.example.utils.ChatMessage
import org.example.utils.ChatRequest
import org.example.utils.DEFAULT_MODEL
import org.example.utils.ThinkingConfig
import org.example.utils.fetchResponse

fun task4() {
    val prompt = "Расскажи как разыгрывать с utg королей на префлопе в техасском покере"

    val temperatures = listOf(0.0, 0.7, 1.2)

    for (temp in temperatures) {
        println("=== ТЕМПЕРАТУРА: $temp ===")

        val request = ChatRequest(
            model = DEFAULT_MODEL,
            messages = listOf(ChatMessage(role = "user", content = prompt)),
            temperature = temp,
            thinking = ThinkingConfig(type = "disabled")
        )

        println(fetchResponse(request))
        println("\n======================================================\n")
    }
}