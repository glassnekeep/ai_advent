package org.example.agents

import org.example.utils.ChatMessage
import org.example.utils.ChatRequest
import org.example.utils.DEFAULT_MODEL
import org.example.utils.Usage
import org.example.utils.fetchResponseWithUsage

enum class Strategy { SLIDING_WINDOW, STICKY_FACTS, BRANCHING }

class AdvancedAgent(
    private val model: String = DEFAULT_MODEL,
    private val baseSystemPrompt: String = "Ты — полезный ИИ-помощник."
) {
    private var currentStrategy = Strategy.SLIDING_WINDOW
    private var windowSize = 4

    private var history = mutableListOf<ChatMessage>()

    private val branches = mutableMapOf<String, MutableList<ChatMessage>>()
    private var currentBranchName = "main"

    private var facts: String = "Пока фактов нет."

    private var totalPromptTokens = 0
    private var totalCompletionTokens = 0
    private var totalOverheadTokens = 0

    init {
        branches[currentBranchName] = history
    }

    fun setStrategy(strategy: Strategy) {
        currentStrategy = strategy
        println("📡 Стратегия изменена на: $currentStrategy")
    }

    fun setWindowSize(size: Int) {
        windowSize = size
        println("🪟 Размер окна установлен на: $size")
    }

    fun ask(userInput: String) {
        if (currentStrategy == Strategy.STICKY_FACTS) {
            updateFacts(userInput)
        }

        if (currentStrategy == Strategy.BRANCHING) {
            autoDetectBranch(userInput)
        }

        history.add(ChatMessage(role = "user", content = userInput))

        val messagesToSend = mutableListOf<ChatMessage>()

        val systemContent = if (currentStrategy == Strategy.STICKY_FACTS) {
            "$baseSystemPrompt\n\nВАЖНЫЕ ФАКТЫ:\n$facts"
        } else {
            baseSystemPrompt
        }
        messagesToSend.add(ChatMessage(role = "system", content = systemContent))

        val relevantHistory = if (currentStrategy == Strategy.SLIDING_WINDOW || currentStrategy == Strategy.STICKY_FACTS) {
            history.takeLast(windowSize)
        } else {
            history
        }
        messagesToSend.addAll(relevantHistory)

        val request = ChatRequest(model = model, messages = messagesToSend)
        val response = fetchResponseWithUsage(request)

        if (response != null) {
            val content = response.choices.firstOrNull()?.message?.content.orEmpty()
            val usage = response.usage

            if (usage != null) {
                totalPromptTokens += usage.prompt_tokens
                totalCompletionTokens += usage.completion_tokens
            }

            history.add(ChatMessage(role = "assistant", content = content))

            println("\nАгент: $content")
            printStats(usage)
        }
    }

    private fun autoDetectBranch(userInput: String) {
        println("🔍 [Overhead] Определение ветки контекста...")
        val branchList = branches.keys.joinToString(", ")
        val classifyPrompt = """
            Тебе даны названия текущих веток диалога: $branchList
            Текущая ветка: $currentBranchName

            Пользователь написал: "$userInput"

            Твоя задача — определить, к какой из существующих веток относится этот вопрос, или нужно создать новую.
            Если вопрос относится к одной из существующих веток, верни ТОЛЬКО её название.
            Если вопрос открывает новую тему, не относящуюся к существующим, верни ТОЛЬКО слово "NEW:название_ветки" (коротко на английском, без пробелов).
            Если не уверен, верни название текущей ветки.

            Ответ должен содержать только название ветки или команду NEW.
        """.trimIndent()

        val request = ChatRequest(
            model = model,
            messages = listOf(ChatMessage(role = "user", content = classifyPrompt))
        )
        val response = fetchResponseWithUsage(request)
        if (response != null) {
            val decision = response.choices.firstOrNull()?.message?.content?.trim() ?: currentBranchName
            totalOverheadTokens += response.usage?.total_tokens ?: 0

            if (decision.startsWith("NEW:")) {
                val newBranchName = decision.substringAfter("NEW:").take(20).replace(" ", "_")
                createBranch(newBranchName)
            } else if (branches.containsKey(decision)) {
                if (decision != currentBranchName) {
                    switchBranch(decision)
                }
            }
        }
    }

    private fun updateFacts(newInput: String) {
        println("📝 [Overhead] Обновление фактов...")
        val updatePrompt = """
            Ты — аналитик. Твоя задача — поддерживать список важных фактов из диалога.
            Текущие факты: $facts
            Новое сообщение от пользователя: $newInput
            Выдай обновленный список фактов (кратко, тезисно). Если новых фактов нет, верни старый список.
            Отвечай только списком фактов.
        """.trimIndent()

        val request = ChatRequest(
            model = model,
            messages = listOf(ChatMessage(role = "user", content = updatePrompt))
        )
        val response = fetchResponseWithUsage(request)
        if (response != null) {
            facts = response.choices.firstOrNull()?.message?.content ?: facts
            totalOverheadTokens += response.usage?.total_tokens ?: 0
        }
    }

    fun createBranch(name: String) {
        branches[name] = history.toMutableList()
        switchBranch(name)
        println("🌿 Создана новая ветка: $name (на основе текущего контекста)")
    }

    fun switchBranch(name: String) {
        if (branches.containsKey(name)) {
            currentBranchName = name
            history = branches[name]!!
            println("🔄 Переключено на ветку: $name")
        } else {
            println("❌ Ветка $name не найдена.")
        }
    }

    fun listBranches() {
        println("📂 Доступные ветки: ${branches.keys.joinToString(", ")} (Текущая: $currentBranchName)")
    }

    fun printCurrentFacts() {
        println("\n📋 ТЕКУЩИЕ ФАКТЫ (Sticky Facts):")
        println(facts)
    }

    private fun printStats(currentUsage: Usage?) {
        println("\n--- Статистика токенов ---")
        if (currentUsage != null) {
            println("👉 Последний запрос (prompt): ${currentUsage.prompt_tokens}")
            println("👉 Последний ответ (completion): ${currentUsage.completion_tokens}")
        }
        println("📊 Итого за сессию:")
        println("   Стратегия: $currentStrategy")
        println("   Ветка: $currentBranchName")
        println("   Prompt (всего): $totalPromptTokens")
        println("   Completion (всего): $totalCompletionTokens")
        println("   Overhead (факты/ветки): $totalOverheadTokens")
        println("   СУММА: ${totalPromptTokens + totalCompletionTokens + totalOverheadTokens}")
        println("--------------------------")
    }

    fun getStatusLine(): String {
        return "[$currentStrategy | Branch: $currentBranchName]"
    }
}
