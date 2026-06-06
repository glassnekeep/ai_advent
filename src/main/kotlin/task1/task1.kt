package org.example.task1

import org.example.utils.ChatMessage
import org.example.utils.ChatRequest
import org.example.utils.DEFAULT_MODEL
import org.example.utils.fetchResponse

fun task1() {
    val requestData = ChatRequest(
        model = DEFAULT_MODEL,
        messages = listOf(ChatMessage(role = "user", content = "Сколько улиц играется в техасском холдеме?"))
    )

    println(fetchResponse(requestData))
}