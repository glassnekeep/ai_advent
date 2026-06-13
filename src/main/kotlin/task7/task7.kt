package org.example.task7

import kotlinx.serialization.encodeToString
import org.example.utils.ChatMessage
import org.example.utils.ChatRequest
import org.example.utils.DEFAULT_MODEL
import org.example.utils.fetchResponse
import org.example.utils.json
import java.io.File

/**
 * Интерфейс для управления историей диалога.
 */
interface ChatHistory {
    fun save(messages: List<ChatMessage>)
    fun load(): List<ChatMessage>
    fun clear()
}

/**
 * Реализация истории диалога с сохранением в JSON файл.
 */
class JsonChatHistory(private val filePath: String = "chat_history.json") : ChatHistory {
    private val file = File(filePath)

    override fun save(messages: List<ChatMessage>) {
        val jsonString = json.encodeToString(messages)
        file.writeText(jsonString)
    }

    override fun load(): List<ChatMessage> {
        if (!file.exists()) return emptyList()
        return try {
            json.decodeFromString<List<ChatMessage>>(file.readText())
        } catch (e: Exception) {
            emptyList()
        }
    }

    override fun clear() {
        if (file.exists()) file.delete()
    }
}

/**
 * Агент с поддержкой сохранения контекста между запусками.
 */
class PersistentAgent(
    private val model: String = DEFAULT_MODEL,
    private val systemPrompt: String = "Ты — полезный ИИ-помощник.",
    private val historyManager: ChatHistory = JsonChatHistory()
) {
    private val history = mutableListOf<ChatMessage>()

    init {
        val savedHistory = historyManager.load()
        if (savedHistory.isNotEmpty()) {
            history.addAll(savedHistory)
        } else {
            history.add(ChatMessage(role = "system", content = systemPrompt))
            historyManager.save(history)
        }
    }

    fun ask(prompt: String): String {
        history.add(ChatMessage(role = "user", content = prompt))
        
        val request = ChatRequest(
            model = model,
            messages = history
        )
        
        val response = fetchResponse(request)

        if (!response.startsWith("Ошибка")) {
            history.add(ChatMessage(role = "assistant", content = response))
            historyManager.save(history) // Сохраняем после каждого успешного ответа
        }

        return response
    }

    fun getHistory(): List<ChatMessage> = history

    fun reset() {
        history.clear()
        history.add(ChatMessage(role = "system", content = systemPrompt))
        historyManager.clear()
        historyManager.save(history)
    }
}

fun task7() {
    println("=== Задание 7: Сохранение контекста ===")
    
    val agent = PersistentAgent()
    val currentHistory = agent.getHistory().filter { it.role != "system" }
    
    if (currentHistory.isNotEmpty()) {
        println("--- Загружена история предыдущего диалога ---")
        currentHistory.forEach { msg ->
            val label = if (msg.role == "user") "Вы" else "Агент"
            println("$label: ${msg.content}")
        }
        println("-------------------------------------------")
    }

    println("Введите 'exit' для выхода, 'reset' для очистки истории.")
    
    while (true) {
        print("\nВы: ")
        val input = readlnOrNull()?.trim() ?: break
        
        if (input.lowercase() == "exit") break
        if (input.lowercase() == "reset") {
            agent.reset()
            println("История очищена.")
            continue
        }
        if (input.isEmpty()) continue

        println("Агент думает...")
        val response = agent.ask(input)
        
        println("\nАгент: $response")
    }
    
    println("\n=== Сессия завершена. История сохранена. ===")
}
