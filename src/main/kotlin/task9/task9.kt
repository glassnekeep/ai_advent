package org.example.task9

import org.example.utils.*

import java.io.File

/**
 * Агент с опциональным механизмом сжатия истории.
 */
class InteractiveCompressingAgent(
    private val model: String = DEFAULT_MODEL,
    private val baseSystemPrompt: String = "Ты — полезный ИИ-помощник.",
    private val useCompression: Boolean = true,
    private val maxRawMessages: Int = 10,
    private val compressChunkSize: Int = 4,
    private val debugFilePath: String = "debug_summary.md"
) {
    private val history = mutableListOf<ChatMessage>()
    private var currentSummary: String? = null
    
    // Статистика
    private var totalPromptTokens = 0
    private var totalCompletionTokens = 0
    private var totalOverheadTokens = 0

    private fun saveSummaryToDebugFile(summary: String) {
        try {
            val file = File(debugFilePath)
            file.writeText("# Текущее Summary для отладки\n\n$summary\n\n--- Последнее обновление: ${java.util.Date()} ---")
        } catch (e: Exception) {
            println("⚠️ Ошибка при записи в дебаг-файл: ${e.message}")
        }
    }

    fun ask(prompt: String): String {
        // 1. Сжатие (только если включено и достигнут порог)
        if (useCompression && history.size >= maxRawMessages) {
            compressHistory()
        }

        // 2. Добавляем новый промпт
        history.add(ChatMessage(role = "user", content = prompt))

        // 3. Формируем системный промпт с учетом summary
        val effectiveSystemPrompt = if (useCompression && currentSummary != null) {
            "$baseSystemPrompt\n\nКОНТЕКСТ ПРОШЛЫХ СООБЩЕНИЙ (КРАТКО):\n$currentSummary"
        } else {
            baseSystemPrompt
        }

        val requestMessages = mutableListOf<ChatMessage>()
        requestMessages.add(ChatMessage(role = "system", content = effectiveSystemPrompt))
        requestMessages.addAll(history)

        val request = ChatRequest(model = model, messages = requestMessages)
        val response = fetchResponseWithUsage(request)

        if (response != null) {
            val content = response.choices.firstOrNull()?.message?.content.orEmpty()
            val usage = response.usage

            if (usage != null) {
                totalPromptTokens += usage.prompt_tokens
                totalCompletionTokens += usage.completion_tokens
            }

            history.add(ChatMessage(role = "assistant", content = content))
            
            // Вывод ответа и статистики
            println("\nАгент: $content")
            printStats(usage)
            
            return content
        }

        return "Ошибка API"
    }

    private fun compressHistory() {
        println("\n🔄 [Сжатие] Лимит сообщений ($maxRawMessages) достигнут. Сжимаем старые $compressChunkSize сообщения...")

        val chunkToCompress = history.take(compressChunkSize)
        val formattedChunk = chunkToCompress.joinToString("\n") { "${it.role}: ${it.content}" }

        val summarizerSystemPrompt = """
            Ты — архивариус. Твоя задача — сжать историю диалога. 
            Извлеки все важные факты. Если есть предыдущее резюме, обнови его.
            Будь кратким.
        """.trimIndent()

        val summarizerMessages = mutableListOf<ChatMessage>()
        summarizerMessages.add(ChatMessage(role = "system", content = summarizerSystemPrompt))
        if (currentSummary != null) {
            summarizerMessages.add(ChatMessage(role = "assistant", content = "Текущее резюме: $currentSummary"))
        }
        summarizerMessages.add(ChatMessage(role = "user", content = "Новые сообщения:\n$formattedChunk"))

        val request = ChatRequest(model = model, messages = summarizerMessages)
        val response = fetchResponseWithUsage(request)

        if (response != null) {
            currentSummary = response.choices.firstOrNull()?.message?.content
            
            // Записываем в файл для отладки
            currentSummary?.let { saveSummaryToDebugFile(it) }

            val usage = response.usage
            if (usage != null) {
                totalOverheadTokens += usage.total_tokens
                println("✅ Сжато. Потрачено на суммаризацию: ${usage.total_tokens} токенов.")
            }
            repeat(compressChunkSize) { history.removeAt(0) }
        }
    }

    private fun printStats(currentUsage: Usage?) {
        println("\n--- Статистика токенов ---")
        if (currentUsage != null) {
            println("👉 Текущий запрос (prompt + история): ${currentUsage.prompt_tokens}")
            println("👉 Ответ модели: ${currentUsage.completion_tokens}")
        }
        println("📊 Итого за сессию:")
        println("   Промпт (всего): $totalPromptTokens")
        println("   Ответы (всего): $totalCompletionTokens")
        if (useCompression) {
            println("   Затраты на сжатие (Overhead): $totalOverheadTokens")
            println("   ОБЩАЯ СТОИМОСТЬ: ${totalPromptTokens + totalCompletionTokens + totalOverheadTokens}")
        } else {
            println("   ОБЩАЯ СТОИМОСТЬ: ${totalPromptTokens + totalCompletionTokens}")
        }
        if (currentSummary != null) {
            println("📝 Текущее Summary: ${currentSummary?.take(50)}...")
        }
        println("--------------------------")
    }
}

fun task9() {
    println("=== Задание 9: Управление контекстом (Interactive) ===")
    
    print("Включить сжатие истории? (y/n): ")
    val choice = readlnOrNull()?.trim()?.lowercase()
    val useCompression = choice == "y" || choice == "yes"
    
    var maxMessages = 10
    if (useCompression) {
        print("Сколько сообщений хранить 'как есть' перед сжатием? (по умолчанию 10): ")
        val inputLimit = readlnOrNull()?.trim()
        if (!inputLimit.isNullOrEmpty()) {
            maxMessages = inputLimit.toIntOrNull() ?: 10
        }
    }
    
    val agent = InteractiveCompressingAgent(
        useCompression = useCompression,
        maxRawMessages = maxMessages,
        compressChunkSize = maxOf(2, maxMessages / 3) // Сжимаем примерно треть истории за раз
    )

    println("\nАгент запущен. Режим сжатия: ${if (useCompression) "ВКЛ (лимит $maxMessages сообщений)" else "ВЫКЛ"}")
    println("Введите запрос или 'exit' для выхода.")

    while (true) {
        print("\nВы: ")
        val input = readlnOrNull()?.trim() ?: break
        if (input.lowercase() == "exit") break
        if (input.isEmpty()) continue

        println("Агент думает...")
        agent.ask(input)
    }
}
