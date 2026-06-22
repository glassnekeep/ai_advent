package org.example.agents.profile

import org.example.agents.memory.AssistantMemory
import org.example.agents.memory.MarkdownMemoryStore

data class UserProfile(
    val name: String,
    val role: String,
    val productType: String,
    val backendStack: String,
    val frontendStack: String,
    val databaseStack: String,
    val infrastructure: String,
    val priorities: String,
    val constraints: String,
    val answerStyle: String
)

object UserProfileOnboarding {
    fun askUserName(): String {
        return askRequired("Ваше имя")
    }

    fun ensureProfile(userName: String, store: MarkdownMemoryStore) {
        if (!hasUserProfile(store, userName)) {
            println("\nПрофиль для пользователя '$userName' не найден.")
            println("Ответьте на вопросы, чтобы ассистент учитывал контекст проектирования IT-продукта.")
            val profile = askProfile(userName)
            saveUserProfile(store, profile)
            println("\nПрофиль сохранен в долговременную память.")
        } else {
            println("\nНайден профиль пользователя '$userName' в долговременной памяти.")
        }
    }

    fun printCurrentProfile(name: String, store: MarkdownMemoryStore) {
        val profile = profileContext(name, store)
        if (profile == null) {
            println("Профиль '$name' не найден.")
        } else {
            println(profile.trim())
        }
    }

    fun profileContext(name: String, store: MarkdownMemoryStore): String? {
        return extractProfile(name, store.load().longTerm)
    }

    fun sessionContext(userName: String): String {
        return """
            Текущий пользователь: $userName.
            Используй профиль этого пользователя из LONG-TERM MEMORY автоматически.
            Если в долговременной памяти есть профили других пользователей, не применяй их к текущему пользователю.
            Адаптируй стиль, формат, технические предложения и ограничения под профиль текущего пользователя.
        """.trimIndent()
    }

    private fun askProfile(name: String): UserProfile {
        return UserProfile(
            name = name,
            role = askRequired("Ваша роль в продукте/команде"),
            productType = askRequired("Какой IT-продукт вы обычно проектируете или хотите проектировать"),
            backendStack = askRequired("Предпочтительный backend-стек"),
            frontendStack = askRequired("Предпочтительный frontend/mobile-стек"),
            databaseStack = askRequired("Предпочтительные базы данных/хранилища"),
            infrastructure = askRequired("Предпочтения по инфраструктуре, cloud, deployment, CI/CD"),
            priorities = askRequired("Что важнее всего: скорость разработки, надежность, безопасность, стоимость, UX, масштабируемость, поддерживаемость"),
            constraints = askRequired("Ограничения: сроки, бюджет, команда, compliance, legacy, запреты по технологиям"),
            answerStyle = askRequired("Какой стиль ответов удобнее: кратко/подробно, списки/таблицы, с кодом/без кода, с рисками/без рисков")
        )
    }

    private fun askRequired(question: String): String {
        while (true) {
            print("$question: ")
            val answer = readlnOrNull()?.trim().orEmpty()
            if (answer.isNotBlank()) return answer
            println("Ответ не должен быть пустым.")
        }
    }

    private fun hasUserProfile(store: MarkdownMemoryStore, name: String): Boolean {
        val marker = profileMarker(name)
        return store.load().longTerm.contains(marker, ignoreCase = true)
    }

    private fun saveUserProfile(store: MarkdownMemoryStore, profile: UserProfile) {
        val memory = store.load()
        val longTerm = memory.longTerm
            .replace("- Пока нет данных.", "")
            .trimEnd()

        val updatedLongTerm = buildString {
            append(longTerm)
            if (longTerm.isNotBlank()) append("\n\n")
            append(formatProfile(profile))
            append("\n")
        }

        store.save(
            AssistantMemory(
                shortTerm = memory.shortTerm,
                working = memory.working,
                longTerm = updatedLongTerm
            )
        )
    }

    private fun formatProfile(profile: UserProfile): String {
        return """
            ## ${profileMarker(profile.name)}

            - Имя: ${profile.name}
            - Роль: ${profile.role}
            - Тип продукта: ${profile.productType}
            - Backend stack: ${profile.backendStack}
            - Frontend/mobile stack: ${profile.frontendStack}
            - Database/storage stack: ${profile.databaseStack}
            - Infrastructure/deployment: ${profile.infrastructure}
            - Приоритеты проектирования: ${profile.priorities}
            - Ограничения: ${profile.constraints}
            - Предпочтения по ответам: ${profile.answerStyle}
        """.trimIndent()
    }

    private fun profileMarker(name: String): String {
        return "User Profile: ${name.trim()}"
    }

    private fun extractProfile(name: String, longTerm: String): String? {
        val marker = "## ${profileMarker(name)}"
        val start = longTerm.indexOf(marker, ignoreCase = true)
        if (start < 0) return null

        val nextProfile = longTerm.indexOf("\n## User Profile:", start + marker.length, ignoreCase = true)
        return if (nextProfile < 0) {
            longTerm.substring(start)
        } else {
            longTerm.substring(start, nextProfile)
        }
    }
}
