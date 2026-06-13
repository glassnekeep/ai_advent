package org.example.task10

import kotlinx.serialization.encodeToString
import org.example.utils.*
import java.io.File

enum class Strategy { SLIDING_WINDOW, STICKY_FACTS, TOPIC_ROUTER }

class AdvancedAgent(
    private val model: String = DEFAULT_MODEL,
    private val baseSystemPrompt: String = "Ты — полезный ИИ-помощник."
) {
    private var currentStrategy = Strategy.SLIDING_WINDOW
    private var windowSize = 4
    
    // Текущая история (для Window и Facts)
    private var history = mutableListOf<ChatMessage>()
    
    // Папка для топиков
    private val topicsDir = File("topics")

    // Sticky Facts
    private var facts: String = "Пока фактов нет."
    
    // Статистика
    private var totalPromptTokens = 0
    private var totalCompletionTokens = 0
    private var totalOverheadTokens = 0

    init {
        if (!topicsDir.exists()) topicsDir.mkdirs()
    }

    fun setStrategy(strategy: Strategy) {
        currentStrategy = strategy
        println("📡 Стратегия изменена на: $currentStrategy")
    }

    fun ask(userInput: String) {
        when (currentStrategy) {
            Strategy.TOPIC_ROUTER -> handleTopicRouter(userInput)
            Strategy.STICKY_FACTS -> {
                updateFacts(userInput)
                processStandardQuery(userInput)
            }
            Strategy.SLIDING_WINDOW -> processStandardQuery(userInput)
        }
    }

    /**
     * Стандартный процесс (Window / Facts)
     */
    private fun processStandardQuery(userInput: String) {
        history.add(ChatMessage(role = "user", content = userInput))

        val messagesToSend = mutableListOf<ChatMessage>()
        val systemContent = if (currentStrategy == Strategy.STICKY_FACTS) {
            "$baseSystemPrompt\n\nВАЖНЫЕ ФАКТЫ:\n$facts"
        } else {
            baseSystemPrompt
        }
        messagesToSend.add(ChatMessage(role = "system", content = systemContent))
        messagesToSend.addAll(history.takeLast(windowSize))

        val request = ChatRequest(model = model, messages = messagesToSend)
        val response = fetchResponseWithUsage(request)

        if (response != null) {
            val content = response.choices.firstOrNull()?.message?.content.orEmpty()
            updateStats(response.usage)
            history.add(ChatMessage(role = "assistant", content = content))
            println("\nАгент: $content")
            printStats(response.usage)
        }
    }

    /**
     * Реализация Topic Router
     */
    private fun handleTopicRouter(userInput: String) {
        println("🔍 [Overhead] Классификация топика...")
        
        val existingTopics = topicsDir.listFiles()?.map { it.nameWithoutExtension } ?: emptyList()
        val routerPrompt = """
            Определи основную тему запроса пользователя.
            Доступные темы: ${existingTopics.joinToString(", ")}
            Если тема соответствует одной из существующих, верни: TOPIC: название.
            Если тема новая, придумай короткое название (1-2 слова на англ) и верни: NEW_TOPIC: название.
            Запрос пользователя: $userInput
        """.trimIndent()

        val routerResponse = fetchResponseWithUsage(ChatRequest(model = model, messages = listOf(ChatMessage(role = "system", content = routerPrompt))))
        
        if (routerResponse != null) {
            val routerText = routerResponse.choices.firstOrNull()?.message?.content.orEmpty()
            totalOverheadTokens += routerResponse.usage?.total_tokens ?: 0
            
            val topicName = when {
                routerText.contains("NEW_TOPIC:") -> routerText.substringAfter("NEW_TOPIC:").trim().filter { it.isLetterOrDigit() || it == '_' }
                routerText.contains("TOPIC:") -> routerText.substringAfter("TOPIC:").trim()
                else -> "general"
            }
            
            println("📂 Выбран топик: $topicName")
            
            // Загружаем историю топика из файла
            val topicFile = File(topicsDir, "$topicName.json")
            val topicHistory = if (topicFile.exists()) {
                json.decodeFromString<MutableList<ChatMessage>>(topicFile.readText())
            } else {
                mutableListOf()
            }

            // Основной запрос в рамках топика
            topicHistory.add(ChatMessage(role = "user", content = userInput))
            val messages = mutableListOf(ChatMessage(role = "system", content = "$baseSystemPrompt\nТекущий топик: $topicName"))
            messages.addAll(topicHistory.takeLast(10)) // Ограничим историю внутри топика для экономии

            val response = fetchResponseWithUsage(ChatRequest(model = model, messages = messages))
            if (response != null) {
                val content = response.choices.firstOrNull()?.message?.content.orEmpty()
                updateStats(response.usage)
                topicHistory.add(ChatMessage(role = "assistant", content = content))
                
                // Сохраняем историю топика обратно в файл
                topicFile.writeText(json.encodeToString(topicHistory))
                
                println("\nАгент [$topicName]: $content")
                printStats(response.usage)
            }
        }
    }

    private fun updateFacts(newInput: String) {
        println("📝 [Overhead] Обновление фактов...")
        val updatePrompt = "Обнови список фактов. Текущие: $facts\nНовое: $newInput\nОтветь только списком фактов."
        val response = fetchResponseWithUsage(ChatRequest(model = model, messages = listOf(ChatMessage(role = "user", content = updatePrompt))))
        if (response != null) {
            facts = response.choices.firstOrNull()?.message?.content ?: facts
            totalOverheadTokens += response.usage?.total_tokens ?: 0
        }
    }

    private fun updateStats(usage: Usage?) {
        totalPromptTokens += usage?.prompt_tokens ?: 0
        totalCompletionTokens += usage?.completion_tokens ?: 0
    }

    fun listTopics() {
        val files = topicsDir.listFiles()?.map { it.name } ?: emptyList()
        println("📂 Файлы топиков в topics/: $files")
    }

    fun printCurrentFacts() {
        println("\n📋 ТЕКУЩИЕ ФАКТЫ: $facts")
    }

    private fun printStats(currentUsage: Usage?) {
        println("\n--- Статистика токенов ---")
        println("📊 Итого за сессию (Стратегия: $currentStrategy):")
        println("   Prompt/Completion: $totalPromptTokens/$totalCompletionTokens")
        println("   Overhead (Router/Facts): $totalOverheadTokens")
        println("   СУММА: ${totalPromptTokens + totalCompletionTokens + totalOverheadTokens}")
        println("--------------------------")
    }
}

fun task10() {
    println("=== Задание 10: Topic-Based Context (Advanced) ===")
    val agent = AdvancedAgent()

    println("\nИнтерактивный режим:")
    println("/strategy [window|facts|router] - выбор стратегии")
    println("/topics - список файлов топиков")
    println("/facts - текущие Sticky Facts")
    println("/exit - выход")

    while (true) {
        print("\nВы: ")
        val input = readlnOrNull()?.trim() ?: break
        if (input.isEmpty()) continue

        if (input.startsWith("/")) {
            val parts = input.split(" ")
            when (parts[0]) {
                "/exit" -> break
                "/strategy" -> {
                    when (parts.getOrNull(1)) {
                        "window" -> agent.setStrategy(Strategy.SLIDING_WINDOW)
                        "facts" -> agent.setStrategy(Strategy.STICKY_FACTS)
                        "router" -> agent.setStrategy(Strategy.TOPIC_ROUTER)
                        else -> println("Варианты: window, facts, router")
                    }
                }
                "/topics" -> agent.listTopics()
                "/facts" -> agent.printCurrentFacts()
                else -> println("Неизвестная команда.")
            }
        } else {
            println("Агент думает...")
            agent.ask(input)
        }
    }
}
