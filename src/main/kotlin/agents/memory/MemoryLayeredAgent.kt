package org.example.agents.memory

import org.example.utils.ChatMessage
import org.example.utils.ChatRequest
import org.example.utils.DEFAULT_MODEL
import org.example.utils.Usage
import org.example.utils.fetchResponseWithUsage
import java.io.File
import java.util.Locale

enum class MemoryLayer {
    SHORT_TERM,
    WORKING,
    LONG_TERM
}

data class AssistantMemory(
    val shortTerm: String,
    val working: String,
    val longTerm: String
)

class MarkdownMemoryStore(private val directoryPath: String = "assistant_memory") {
    private val directory = File(directoryPath)
    private val shortTermFile = File(directory, "short_term.md")
    private val workingFile = File(directory, "working.md")
    private val longTermFile = File(directory, "long_term.md")

    init {
        directory.mkdirs()
        ensureFile(shortTermFile, "Short-Term Memory")
        ensureFile(workingFile, "Working Memory")
        ensureFile(longTermFile, "Long-Term Memory")
    }

    fun load(): AssistantMemory {
        return AssistantMemory(
            shortTerm = shortTermFile.readText(),
            working = workingFile.readText(),
            longTerm = longTermFile.readText()
        )
    }

    fun save(memory: AssistantMemory) {
        shortTermFile.writeText(normalize(memory.shortTerm, "Short-Term Memory"))
        workingFile.writeText(normalize(memory.working, "Working Memory"))
        longTermFile.writeText(normalize(memory.longTerm, "Long-Term Memory"))
    }

    fun clear(layer: MemoryLayer) {
        when (layer) {
            MemoryLayer.SHORT_TERM -> shortTermFile.writeText(emptyMarkdown("Short-Term Memory"))
            MemoryLayer.WORKING -> workingFile.writeText(emptyMarkdown("Working Memory"))
            MemoryLayer.LONG_TERM -> longTermFile.writeText(emptyMarkdown("Long-Term Memory"))
        }
    }

    fun paths(): String {
        return listOf(shortTermFile, workingFile, longTermFile)
            .joinToString("\n") { "- ${it.path}" }
    }

    fun directoryPath(): String = directory.path

    private fun ensureFile(file: File, title: String) {
        if (!file.exists()) {
            file.writeText(emptyMarkdown(title))
        }
    }

    private fun normalize(content: String, title: String): String {
        val trimmed = content.trim()
        if (trimmed.isBlank()) return emptyMarkdown(title)
        return "$trimmed\n"
    }

    private fun emptyMarkdown(title: String): String {
        return "# $title\n\n- Пока нет данных.\n"
    }
}

fun userMemoryStore(userName: String, rootDirectory: String = "assistant_memory/users"): MarkdownMemoryStore {
    return MarkdownMemoryStore("$rootDirectory/${safeUserId(userName)}")
}

fun safeUserId(userName: String): String {
    return userName.trim()
        .lowercase(Locale.getDefault())
        .replace(Regex("[^a-zа-я0-9._-]+"), "_")
        .trim('_')
        .ifBlank { "default" }
}

class MemoryLayeredAgent(
    private val model: String = DEFAULT_MODEL,
    private val baseSystemPrompt: String = "Ты — полезный ИИ-помощник.",
    private val store: MarkdownMemoryStore = MarkdownMemoryStore(),
    private val sessionContext: String = "",
    private val sessionContextProvider: () -> String = { sessionContext }
) {
    private var memory = store.load()
    private var totalPromptTokens = 0
    private var totalCompletionTokens = 0
    private var totalMemoryOverheadTokens = 0

    fun ask(userInput: String): String? {
        memory = store.load()
        memory = memory.copy(shortTerm = appendTurn(memory.shortTerm, "Пользователь", userInput))
        store.save(memory)

        val response = fetchResponseWithUsage(
            ChatRequest(
                model = model,
                messages = buildMessages(userInput)
            )
        )

        if (response != null) {
            val content = response.choices.firstOrNull()?.message?.content.orEmpty()
            val usage = response.usage

            if (usage != null) {
                totalPromptTokens += usage.prompt_tokens
                totalCompletionTokens += usage.completion_tokens
            }

            memory = memory.copy(shortTerm = appendTurn(memory.shortTerm, "Ассистент", content))
            store.save(memory)

            println("\nАгент: $content")
            updateMemoryLayers(userInput, content)
            printStats(usage)
            return content
        }

        return null
    }

    fun clear(layer: MemoryLayer) {
        store.clear(layer)
        memory = store.load()
    }

    fun finishSession() {
        memory = store.load()

        if (countFacts(memory.shortTerm) == 0) {
            clear(MemoryLayer.SHORT_TERM)
            println("\n[Session] Short-term memory очищена.")
            return
        }

        println("\n[Session] Завершаю сессию: переношу полезный контекст в working memory...")

        val updatedWorking = updateWorkingMemoryFromSession()
        if (updatedWorking != null) {
            memory = memory.copy(
                working = preserveTaskStateBlock(
                    previousWorking = memory.working,
                    updatedWorking = updatedWorking
                )
            )
            store.save(memory)
            println("[Session] Working memory обновлена.")
        } else {
            println("[Session] Working memory не обновлена: не удалось получить summary.")
        }

        clear(MemoryLayer.SHORT_TERM)
        println("[Session] Short-term memory очищена.")
    }

    fun printMemory() {
        memory = store.load()

        println("\n=== Short-term memory: текущий диалог ===")
        println(memory.shortTerm.trim())

        println("\n=== Working memory: данные текущей задачи ===")
        println(memory.working.trim())

        println("\n=== Long-term memory: профиль, решения, знания ===")
        println(memory.longTerm.trim())
    }

    fun printStoragePaths() {
        println(store.paths())
    }

    fun statusLine(): String {
        memory = store.load()
        return "[short=${countFacts(memory.shortTerm)}, working=${countFacts(memory.working)}, long=${countFacts(memory.longTerm)}]"
    }

    private fun buildMessages(userInput: String): List<ChatMessage> {
        val systemPrompt = buildString {
            append(baseSystemPrompt)
            val currentSessionContext = sessionContextProvider().trim()
            if (currentSessionContext.isNotBlank()) {
                append("\n\nSESSION CONTEXT:\n")
                append(currentSessionContext)
            }
            append("\n\nУ тебя есть явная модель памяти. Используй слои строго по назначению:")
            append("\n- short-term: текущий диалог.")
            append("\n- working: данные текущей задачи, временные требования, ограничения и промежуточные решения.")
            append("\n- long-term: устойчивый профиль пользователя, принятые решения и знания, которые стоит помнить между задачами.")
            append("\nНе выдумывай данные памяти. Если нужного факта нет в слоях ниже, скажи об этом.")
            append("\n\nSHORT-TERM MEMORY:\n")
            append(memory.shortTerm)
            append("\n\nWORKING MEMORY:\n")
            append(memory.working)
            append("\n\nLONG-TERM MEMORY:\n")
            append(memory.longTerm)
        }

        return listOf(
            ChatMessage(role = "system", content = systemPrompt),
            ChatMessage(role = "user", content = userInput)
        )
    }

    private fun updateMemoryLayers(userInput: String, assistantResponse: String) {
        println("\n[Memory] Обновляю слои памяти...")

        val updatePrompt = """
            Ты управляешь памятью ассистента по принципу sticky facts.
            Нужно обновить три markdown-файла памяти на основе последнего обмена.

            Правила слоев:
            1. Short-Term Memory: только компактная история текущего диалога и свежий локальный контекст.
            2. Working Memory: факты текущей задачи, временные требования, ограничения, выбранные решения и незавершенные действия.
            3. Long-Term Memory: устойчивые предпочтения пользователя, профиль, повторно полезные решения и знания между задачами.

            Явно выбирай слой:
            - сохраняй существующие разделы "## User Profile: ..." в long-term memory, если пользователь прямо не попросил удалить или изменить профиль;
            - сохраняй служебный блок "<!-- TASK_STATE_START --> ... <!-- TASK_STATE_END -->" в working memory без удаления, если он там есть;
            - не клади временные детали задачи в long-term;
            - не клади профиль пользователя в working, если это устойчивое предпочтение;
            - удаляй устаревшее, объединяй дубли, оставляй только факты;
            - если слой пуст, оставь "- Пока нет данных.";
            - ответь строго в формате ниже, без дополнительного текста.

            ТЕКУЩАЯ SHORT-TERM MEMORY:
            ${memory.shortTerm}

            ТЕКУЩАЯ WORKING MEMORY:
            ${memory.working}

            ТЕКУЩАЯ LONG-TERM MEMORY:
            ${memory.longTerm}

            ПОСЛЕДНЕЕ СООБЩЕНИЕ ПОЛЬЗОВАТЕЛЯ:
            $userInput

            ПОСЛЕДНИЙ ОТВЕТ АССИСТЕНТА:
            $assistantResponse

            === SHORT_TERM ===
            # Short-Term Memory

            - ...

            === WORKING ===
            # Working Memory

            - ...

            === LONG_TERM ===
            # Long-Term Memory

            - ...
        """.trimIndent()

        val response = fetchResponseWithUsage(
            ChatRequest(
                model = model,
                messages = listOf(ChatMessage(role = "user", content = updatePrompt)),
                temperature = 0.0
            )
        )

        if (response == null) {
            println("[Memory] Не удалось обновить память.")
            return
        }

        totalMemoryOverheadTokens += response.usage?.total_tokens ?: 0

        val raw = response.choices.firstOrNull()?.message?.content.orEmpty()
        val updated = parseMemoryUpdate(raw)
        if (updated == null) {
            println("[Memory] Не удалось разобрать ответ обновления, сохранена только short-term история.")
            return
        }

        memory = updated.copy(
            working = preserveTaskStateBlock(
                previousWorking = memory.working,
                updatedWorking = updated.working
            )
        )
        store.save(memory)
        println("[Memory] Слои обновлены.")
    }

    private fun updateWorkingMemoryFromSession(): String? {
        val prompt = """
            Нужно завершить сессию ассистента.
            Short-term memory будет очищена, поэтому перенеси в working memory только то, что поможет продолжить текущую задачу в следующей сессии.

            Правила:
            - сохрани незавершенные задачи, решения, ограничения, важные вводные и следующие шаги;
            - не копируй весь диалог;
            - не добавляй профиль пользователя и долговременные предпочтения;
            - сохрани служебный блок "<!-- TASK_STATE_START --> ... <!-- TASK_STATE_END -->" в working memory без удаления, если он там есть;
            - не добавляй светскую беседу и временные фразы;
            - объедини дубли и удали устаревшее;
            - если полезного рабочего контекста нет, верни текущую working memory без изменений;
            - ответь только новым содержимым markdown-файла working memory.

            ТЕКУЩАЯ WORKING MEMORY:
            ${memory.working}

            SHORT-TERM MEMORY ТЕКУЩЕЙ СЕССИИ:
            ${memory.shortTerm}
        """.trimIndent()

        val response = fetchResponseWithUsage(
            ChatRequest(
                model = model,
                messages = listOf(ChatMessage(role = "user", content = prompt)),
                temperature = 0.0
            )
        ) ?: return null

        totalMemoryOverheadTokens += response.usage?.total_tokens ?: 0
        return response.choices.firstOrNull()?.message?.content?.trim()?.ifBlank { null }
    }

    private fun parseMemoryUpdate(raw: String): AssistantMemory? {
        val short = extractSection(raw, "SHORT_TERM")
        val working = extractSection(raw, "WORKING")
        val long = extractSection(raw, "LONG_TERM")

        if (short == null || working == null || long == null) return null

        return AssistantMemory(
            shortTerm = short,
            working = working,
            longTerm = long
        )
    }

    private fun extractSection(raw: String, name: String): String? {
        val marker = "=== $name ==="
        val start = raw.indexOf(marker)
        if (start < 0) return null

        val contentStart = start + marker.length
        val nextStart = raw.indexOf("===", contentStart)
        val content = if (nextStart < 0) {
            raw.substring(contentStart)
        } else {
            raw.substring(contentStart, nextStart)
        }

        return content.trim().ifBlank { null }
    }

    private fun appendTurn(markdown: String, speaker: String, text: String): String {
        val cleaned = text.replace("\n", " ").trim()
        val line = "- $speaker: $cleaned"
        val withoutPlaceholder = markdown
            .replace("- Пока нет данных.", "")
            .trimEnd()

        return "$withoutPlaceholder\n$line\n"
    }

    private fun countFacts(markdown: String): Int {
        return markdown.lineSequence()
            .count { it.trimStart().startsWith("- ") && !it.contains("Пока нет данных") }
    }

    private fun preserveTaskStateBlock(previousWorking: String, updatedWorking: String): String {
        val previousBlock = extractTaskStateBlock(previousWorking) ?: return updatedWorking
        if (extractTaskStateBlock(updatedWorking) != null) return updatedWorking

        val cleaned = updatedWorking.trimEnd()
        return buildString {
            if (cleaned.isNotBlank()) {
                append(cleaned)
                append("\n\n")
            }
            append(previousBlock)
            append("\n")
        }
    }

    private fun extractTaskStateBlock(markdown: String): String? {
        val start = markdown.indexOf(TASK_STATE_START)
        val end = markdown.indexOf(TASK_STATE_END)
        if (start < 0 || end < 0 || end <= start) return null
        return markdown.substring(start, end + TASK_STATE_END.length).trim()
    }

    private fun printStats(currentUsage: Usage?) {
        println("\n--- Статистика памяти и токенов ---")
        println("Слои памяти: ${statusLine()}")
        if (currentUsage != null) {
            println("Последний prompt: ${currentUsage.prompt_tokens}")
            println("Последний completion: ${currentUsage.completion_tokens}")
        }
        println("Prompt всего: $totalPromptTokens")
        println("Completion всего: $totalCompletionTokens")
        println("Overhead обновления памяти: $totalMemoryOverheadTokens")
        println("--------------------------")
    }

    private companion object {
        const val TASK_STATE_START = "<!-- TASK_STATE_START -->"
        const val TASK_STATE_END = "<!-- TASK_STATE_END -->"
    }
}
