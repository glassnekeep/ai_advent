package org.example.agents.invariants

import org.example.agents.memory.MarkdownMemoryStore
import org.example.agents.state.TaskState
import org.example.agents.profile.UserProfileOnboarding
import org.example.utils.ChatMessage
import org.example.utils.ChatRequest
import org.example.utils.DEFAULT_MODEL
import org.example.utils.fetchResponseWithUsage
import java.io.File

data class InvariantCheckResult(
    val hasConflict: Boolean,
    val explanation: String
)

class MarkdownInvariantStore(private val filePath: String = "assistant_memory/invariants.md") {
    private val file = File(filePath)

    init {
        file.parentFile?.mkdirs()
        if (!file.exists()) {
            file.writeText("# Invariants\n\n- Пока нет данных.\n")
        }
    }

    fun loadAll(): String = file.readText()

    fun loadForUser(userName: String): String {
        val content = file.readText()
        return extractUserSection(userName, content) ?: "- Для пользователя '$userName' инварианты не заданы."
    }

    fun ensureForUser(userName: String, memoryStore: MarkdownMemoryStore) {
        if (extractUserSection(userName, file.readText()) != null) {
            println("\nНайдены инварианты пользователя '$userName'.")
            return
        }

        println("\nИнварианты для пользователя '$userName' не найдены.")

        val profile = UserProfileOnboarding.profileContext(userName, memoryStore)
            ?: "- Профиль пользователя не найден. Используйте только явно заданные запреты."

        val section = """
            ## User Invariants: $userName

            ### Source profile
            $profile

            ### Hard invariants
            - Использовать профиль как источник выбранного стека, приоритетов, ограничений и предпочтений.
            - Не предлагать технологии вне предпочтительного стека без явного согласования с пользователем.
            - Не нарушать ограничения, указанные в профиле пользователя.
        """.trimIndent()

        val existing = file.readText()
            .replace("- Пока нет данных.", "")
            .trimEnd()

        file.writeText(
            buildString {
                append(existing.ifBlank { "# Invariants" })
                append("\n\n")
                append(section)
                append("\n")
            }
        )

        println("\nИнварианты сохранены в $filePath.")
    }

    fun printForUser(userName: String) {
        println(loadForUser(userName).trim())
    }

    fun path(): String = file.path

    private fun extractUserSection(userName: String, content: String): String? {
        val marker = "## User Invariants: ${userName.trim()}"
        val start = content.indexOf(marker, ignoreCase = true)
        if (start < 0) return null

        val next = content.indexOf("\n## User Invariants:", start + marker.length, ignoreCase = true)
        return if (next < 0) {
            content.substring(start)
        } else {
            content.substring(start, next)
        }
    }
}

class InvariantGuard(private val model: String = DEFAULT_MODEL) {
    fun check(
        userInput: String,
        taskState: TaskState?,
        invariants: String
    ): InvariantCheckResult {
        val prompt = """
            You are an invariant checker for an assistant.
            Decide whether the user request conflicts with the invariants.

            Rules:
            - If the request asks for a solution that violates architecture, stack constraints, technical decisions, business rules, or quality constraints, mark conflict.
            - If the request can be answered while respecting invariants, mark no conflict.
            - If there is a conflict, explain briefly and propose an allowed alternative.
            - Respond only in the exact format below.

            INVARIANTS:
            $invariants

            TASK STATE:
            ${taskState ?: "No active task"}

            USER REQUEST:
            $userInput

            CONFLICT: YES|NO
            EXPLANATION: ...
        """.trimIndent()

        val response = fetchResponseWithUsage(
            ChatRequest(
                model = model,
                messages = listOf(ChatMessage(role = "user", content = prompt)),
                temperature = 0.0
            )
        )

        if (response == null) {
            return InvariantCheckResult(
                hasConflict = true,
                explanation = "Не удалось проверить запрос на соответствие инвариантам, поэтому я не буду предлагать решение, которое может нарушить ограничения."
            )
        }

        val content = response.choices.firstOrNull()?.message?.content.orEmpty()
        val conflict = readValue(content, "CONFLICT")?.equals("YES", ignoreCase = true) == true
        val explanation = readValue(content, "EXPLANATION")
            ?: if (conflict) "Запрос конфликтует с заданными инвариантами." else "Конфликта с инвариантами не найдено."

        return InvariantCheckResult(
            hasConflict = conflict,
            explanation = explanation
        )
    }

    private fun readValue(content: String, key: String): String? {
        val prefix = "$key:"
        return content.lineSequence()
            .firstOrNull { it.trimStart().startsWith(prefix) }
            ?.substringAfter(prefix)
            ?.trim()
            ?.takeIf { it.isNotBlank() && it != "..." }
    }
}
