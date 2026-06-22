package org.example.agents.state

import org.example.agents.memory.AssistantMemory
import org.example.agents.memory.MarkdownMemoryStore
import org.example.utils.ChatMessage
import org.example.utils.ChatRequest
import org.example.utils.DEFAULT_MODEL
import org.example.utils.fetchResponseWithUsage

enum class TaskPhase {
    PLANNING,
    EXECUTION,
    VALIDATION,
    DONE
}

data class TaskState(
    val taskTitle: String,
    val phase: TaskPhase,
    val currentStep: String,
    val expectedAction: String,
    val updatedAtMillis: Long
)

data class LifecycleDecision(
    val allowed: Boolean,
    val state: TaskState,
    val message: String? = null
)

class WorkingMemoryTaskStateStore(private val memoryStore: MarkdownMemoryStore = MarkdownMemoryStore()) {
    fun load(): TaskState? {
        val section = extractTaskStateSection(memoryStore.load().working) ?: return null
        if (section.contains("- Task title: Нет активной задачи")) return null

        val title = readField(section, "Task title") ?: return null
        val phase = readField(section, "Phase")?.let { runCatching { TaskPhase.valueOf(it) }.getOrNull() } ?: return null
        val currentStep = readField(section, "Current step") ?: defaultStep(phase)
        val expectedAction = readField(section, "Expected action") ?: defaultExpectedAction(phase)
        val updatedAtMillis = readField(section, "Updated at millis")?.toLongOrNull() ?: System.currentTimeMillis()

        return TaskState(
            taskTitle = title,
            phase = phase,
            currentStep = currentStep,
            expectedAction = expectedAction,
            updatedAtMillis = updatedAtMillis
        )
    }

    fun save(state: TaskState) {
        replaceTaskStateSection(format(state))
    }

    fun clear() {
        replaceTaskStateSection(
            """
            ## Task State

            - Task title: Нет активной задачи
            - Phase: DONE
            - Current step: Нет активного шага
            - Expected action: Создайте новую задачу
            - Updated at millis: ${System.currentTimeMillis()}
            """.trimIndent()
        )
    }

    private fun replaceTaskStateSection(section: String) {
        val memory = memoryStore.load()
        val workingWithoutState = removeTaskStateSection(memory.working).trimEnd()
        val updatedWorking = buildString {
            if (workingWithoutState.isNotBlank()) {
                append(workingWithoutState)
                append("\n\n")
            }
            append(TASK_STATE_START)
            append("\n")
            append(section.trim())
            append("\n")
            append(TASK_STATE_END)
            append("\n")
        }

        memoryStore.save(
            AssistantMemory(
                shortTerm = memory.shortTerm,
                working = updatedWorking,
                longTerm = memory.longTerm
            )
        )
    }

    private fun format(state: TaskState): String {
        return """
            ## Task State

            - Task title: ${state.taskTitle}
            - Phase: ${state.phase}
            - Current step: ${state.currentStep}
            - Expected action: ${state.expectedAction}
            - Updated at millis: ${state.updatedAtMillis}
            """.trimIndent()
    }

    private fun readField(content: String, name: String): String? {
        val prefix = "- $name:"
        return content.lineSequence()
            .firstOrNull { it.trimStart().startsWith(prefix) }
            ?.substringAfter(prefix)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
    }

    private fun extractTaskStateSection(working: String): String? {
        val start = working.indexOf(TASK_STATE_START)
        val end = working.indexOf(TASK_STATE_END)
        if (start < 0 || end < 0 || end <= start) return null
        return working.substring(start + TASK_STATE_START.length, end).trim()
    }

    private fun removeTaskStateSection(working: String): String {
        val start = working.indexOf(TASK_STATE_START)
        val end = working.indexOf(TASK_STATE_END)
        if (start < 0 || end < 0 || end <= start) return working
        return (working.substring(0, start) + working.substring(end + TASK_STATE_END.length)).trim()
    }

    private companion object {
        const val TASK_STATE_START = "<!-- TASK_STATE_START -->"
        const val TASK_STATE_END = "<!-- TASK_STATE_END -->"
    }
}

class TaskStateMachine(
    private val store: WorkingMemoryTaskStateStore = WorkingMemoryTaskStateStore(),
    private val model: String = DEFAULT_MODEL
) {
    fun current(): TaskState? = store.load()

    fun ensureTaskFor(userInput: String): TaskState {
        val current = current()
        if (current != null && current.phase != TaskPhase.DONE) return current

        return start(makeTitle(userInput))
    }

    fun start(taskTitle: String): TaskState {
        val state = newState(
            taskTitle = taskTitle,
            phase = TaskPhase.PLANNING,
            currentStep = "Понять задачу, требования и критерии успеха",
            expectedAction = "Сформировать или уточнить план перед выполнением"
        )
        store.save(state)
        return state
    }

    fun handleUserRequest(userInput: String): LifecycleDecision {
        val current = current()
        if (current == null || current.phase == TaskPhase.DONE) {
            return LifecycleDecision(
                allowed = true,
                state = start(makeTitle(userInput)),
                message = "Начинаю новую задачу с этапа PLANNING."
            )
        }

        val desiredPhase = inferDesiredPhase(userInput)
        if (desiredPhase == null || desiredPhase == current.phase) {
            return LifecycleDecision(allowed = true, state = current)
        }

        if (!isAllowedTransition(current.phase, desiredPhase)) {
            return LifecycleDecision(
                allowed = false,
                state = current,
                message = invalidTransitionMessage(current.phase, desiredPhase)
            )
        }

        if (current.phase == TaskPhase.PLANNING && desiredPhase == TaskPhase.EXECUTION && !isPlanApproval(userInput)) {
            return LifecycleDecision(
                allowed = false,
                state = current,
                message = "Я не могу перейти к реализации до утверждения плана. Сейчас этап PLANNING: сначала нужно согласовать план и критерии готовности."
            )
        }

        if (current.phase == TaskPhase.VALIDATION && desiredPhase == TaskPhase.DONE && !isExplicitDoneConfirmation(userInput)) {
            return LifecycleDecision(
                allowed = false,
                state = current,
                message = "Я не могу завершить задачу без явного подтверждения. Сейчас этап VALIDATION: нужно проверить результат или явно сказать, что задача завершена."
            )
        }

        val transitioned = newState(
            taskTitle = current.taskTitle,
            phase = desiredPhase,
            currentStep = defaultStep(desiredPhase),
            expectedAction = defaultExpectedAction(desiredPhase)
        )
        store.save(transitioned)

        return LifecycleDecision(
            allowed = true,
            state = transitioned,
            message = "Переход состояния: ${current.phase} -> $desiredPhase."
        )
    }

    fun updateAfterIteration(userInput: String, assistantResponse: String): TaskState {
        val before = current() ?: start(makeTitle(userInput))
        val proposed = proposeNextState(before, userInput, assistantResponse)
        val normalized = proposed?.let { normalizeTransition(before, it, userInput) } ?: fallbackNextState(before)
        store.save(normalized)
        return normalized
    }

    fun clear() {
        store.clear()
    }

    fun allowedTransitionsDescription(): String {
        return """
            EXPLICIT LIFECYCLE TRANSITIONS:
            - PLANNING -> EXECUTION: only after explicit plan approval.
            - EXECUTION -> VALIDATION: after implementation/result is produced.
            - VALIDATION -> DONE: only after explicit completion confirmation.
            - EXECUTION -> PLANNING: if requirements or plan need revision.
            - VALIDATION -> EXECUTION: if validation finds changes or fixes.
            - DONE -> PLANNING: only for a new user request after the previous task is complete.
            Forbidden:
            - PLANNING -> VALIDATION
            - PLANNING -> DONE
            - EXECUTION -> DONE
            - Starting a new task before current task is DONE
        """.trimIndent()
    }

    fun promptBlock(): String {
        val state = current() ?: return """
            TASK STATE:
            - Active task: none
            - Expected action: treat the next user request as a new task and start from PLANNING
        """.trimIndent()

        return """
            TASK STATE MACHINE:
            - The assistant manages transitions automatically.
            - User does not manually choose phases.
            ${allowedTransitionsDescription()}
            - A new user request cannot start a new task until the current task is DONE.
            - VALIDATION can become DONE only after explicit user confirmation that the task is complete.
            - Always tell the user what phase you are in.
            - Work only according to the current phase and expected action.

            CURRENT TASK STATE:
            - Task title: ${state.taskTitle}
            - Phase: ${state.phase}
            - Current step: ${state.currentStep}
            - Expected action: ${state.expectedAction}
        """.trimIndent()
    }

    private fun proposeNextState(
        before: TaskState,
        userInput: String,
        assistantResponse: String
    ): TaskState? {
        val prompt = """
            You update a deterministic task state machine for an assistant.
            Choose the next state after one assistant iteration.

            Allowed phases:
            PLANNING -> EXECUTION -> VALIDATION -> DONE
            Backward if needed:
            EXECUTION -> PLANNING
            VALIDATION -> EXECUTION

            Rules:
            - Do not skip phases.
            - If planning is incomplete, stay in PLANNING.
            - Do not move PLANNING -> EXECUTION unless the user explicitly approves the plan.
            - If execution produced a concrete result, move EXECUTION -> VALIDATION.
            - Do not move VALIDATION -> DONE unless the user explicitly confirms completion.
            - If the user asks a question, clarification, change, improvement, or correction during VALIDATION, stay in VALIDATION or move back to EXECUTION.
            - If user asks for changes during validation, move VALIDATION -> EXECUTION.
            - If the current phase is not DONE, treat the user request as part of the current task, never as a new task.
            - Keep current_step short and specific.
            - expected_action must describe what the assistant should do next.
            - Respond only in the exact format below.

            CURRENT STATE:
            Task title: ${before.taskTitle}
            Phase: ${before.phase}
            Current step: ${before.currentStep}
            Expected action: ${before.expectedAction}

            USER REQUEST:
            $userInput

            ASSISTANT RESPONSE:
            $assistantResponse

            TASK_TITLE: ${before.taskTitle}
            PHASE: PLANNING|EXECUTION|VALIDATION|DONE
            CURRENT_STEP: ...
            EXPECTED_ACTION: ...
        """.trimIndent()

        val response = fetchResponseWithUsage(
            ChatRequest(
                model = model,
                messages = listOf(ChatMessage(role = "user", content = prompt)),
                temperature = 0.0
            )
        ) ?: return null

        val raw = response.choices.firstOrNull()?.message?.content.orEmpty()
        val phase = readLineValue(raw, "PHASE")?.let { runCatching { TaskPhase.valueOf(it) }.getOrNull() } ?: return null

        return newState(
            taskTitle = readLineValue(raw, "TASK_TITLE") ?: before.taskTitle,
            phase = phase,
            currentStep = readLineValue(raw, "CURRENT_STEP") ?: defaultStep(phase),
            expectedAction = readLineValue(raw, "EXPECTED_ACTION") ?: defaultExpectedAction(phase)
        )
    }

    private fun normalizeTransition(before: TaskState, proposed: TaskState, userInput: String): TaskState {
        val allowed = when (before.phase) {
            TaskPhase.PLANNING -> setOf(TaskPhase.PLANNING, TaskPhase.EXECUTION)
            TaskPhase.EXECUTION -> setOf(TaskPhase.PLANNING, TaskPhase.EXECUTION, TaskPhase.VALIDATION)
            TaskPhase.VALIDATION -> setOf(TaskPhase.EXECUTION, TaskPhase.VALIDATION, TaskPhase.DONE)
            TaskPhase.DONE -> setOf(TaskPhase.PLANNING)
        }

        if (proposed.phase == TaskPhase.DONE && !isExplicitDoneConfirmation(userInput)) {
            return newState(
                taskTitle = before.taskTitle,
                phase = TaskPhase.VALIDATION,
                currentStep = "Проверить результат и обработать уточнения пользователя",
                expectedAction = "Ответить на уточнение в рамках текущей задачи; завершать только после явного подтверждения"
            )
        }

        if (before.phase == TaskPhase.PLANNING && proposed.phase == TaskPhase.EXECUTION && !isPlanApproval(userInput)) {
            return newState(
                taskTitle = before.taskTitle,
                phase = TaskPhase.PLANNING,
                currentStep = "Дождаться утверждения плана",
                expectedAction = "Пользователь должен явно утвердить план перед реализацией"
            )
        }

        if (proposed.phase in allowed) return proposed

        return newState(
            taskTitle = before.taskTitle,
            phase = before.phase,
            currentStep = before.currentStep,
            expectedAction = "Продолжить текущий этап: ${before.expectedAction}"
        )
    }

    private fun fallbackNextState(before: TaskState): TaskState {
        return newState(
            taskTitle = before.taskTitle,
            phase = before.phase,
            currentStep = before.currentStep,
            expectedAction = before.expectedAction
        )
    }

    private fun newState(
        taskTitle: String,
        phase: TaskPhase,
        currentStep: String,
        expectedAction: String
    ): TaskState {
        return TaskState(
            taskTitle = taskTitle,
            phase = phase,
            currentStep = currentStep,
            expectedAction = expectedAction,
            updatedAtMillis = System.currentTimeMillis()
        )
    }

    private fun readLineValue(text: String, key: String): String? {
        val prefix = "$key:"
        return text.lineSequence()
            .firstOrNull { it.trimStart().startsWith(prefix) }
            ?.substringAfter(prefix)
            ?.trim()
            ?.takeIf { it.isNotBlank() && it != "..." }
    }

    private fun makeTitle(userInput: String): String {
        return userInput.replace("\n", " ").trim().take(80).ifBlank { "Новая задача" }
    }

    private fun isAllowedTransition(from: TaskPhase, to: TaskPhase): Boolean {
        return to in when (from) {
            TaskPhase.PLANNING -> setOf(TaskPhase.PLANNING, TaskPhase.EXECUTION)
            TaskPhase.EXECUTION -> setOf(TaskPhase.PLANNING, TaskPhase.EXECUTION, TaskPhase.VALIDATION)
            TaskPhase.VALIDATION -> setOf(TaskPhase.EXECUTION, TaskPhase.VALIDATION, TaskPhase.DONE)
            TaskPhase.DONE -> setOf(TaskPhase.PLANNING)
        }
    }

    private fun inferDesiredPhase(userInput: String): TaskPhase? {
        val normalized = userInput.lowercase()

        if (isExplicitDoneConfirmation(normalized) || listOf("финал", "заверш").any { normalized.contains(it) }) {
            return TaskPhase.DONE
        }

        if (listOf("валид", "проверь", "провер", "review", "тест", "validation").any { normalized.contains(it) }) {
            return TaskPhase.VALIDATION
        }

        if (isPlanApproval(normalized) || listOf("реализ", "сделай", "напиши код", "implementation", "execute").any { normalized.contains(it) }) {
            return TaskPhase.EXECUTION
        }

        if (listOf("план", "сплан", "требован", "архитект").any { normalized.contains(it) }) {
            return TaskPhase.PLANNING
        }

        return null
    }

    private fun invalidTransitionMessage(from: TaskPhase, to: TaskPhase): String {
        return when {
            from == TaskPhase.PLANNING && to == TaskPhase.VALIDATION ->
                "Я не могу перейти к валидации до реализации. Сейчас этап PLANNING: сначала нужно утвердить план, затем выполнить реализацию."
            from == TaskPhase.PLANNING && to == TaskPhase.DONE ->
                "Я не могу завершить задачу из planning. Нужно пройти execution и validation."
            from == TaskPhase.EXECUTION && to == TaskPhase.DONE ->
                "Я не могу завершить задачу без validation. Сейчас этап EXECUTION: сначала нужно проверить результат."
            else ->
                "Недопустимый переход состояния: $from -> $to. Продолжаю текущий этап $from."
        }
    }

    private fun isExplicitDoneConfirmation(userInput: String): Boolean {
        val normalized = userInput.lowercase()
        val doneMarkers = listOf(
            "готово",
            "задача завершена",
            "можно завершать",
            "заверши задачу",
            "закрывай задачу",
            "done",
            "finish task",
            "complete task"
        )

        return doneMarkers.any { marker -> normalized.contains(marker) }
    }

    private fun isPlanApproval(userInput: String): Boolean {
        val normalized = userInput.lowercase()
        val approvalMarkers = listOf(
            "план утвержден",
            "утверждаю план",
            "план ок",
            "план согласован",
            "согласен с планом",
            "approve plan",
            "plan approved"
        )

        return approvalMarkers.any { marker -> normalized.contains(marker) }
    }
}

fun defaultStep(phase: TaskPhase): String {
    return when (phase) {
        TaskPhase.PLANNING -> "Собрать требования и подготовить план"
        TaskPhase.EXECUTION -> "Выполнить согласованный план"
        TaskPhase.VALIDATION -> "Проверить результат и найти пробелы"
        TaskPhase.DONE -> "Зафиксировать завершение задачи"
    }
}

fun defaultExpectedAction(phase: TaskPhase): String {
    return when (phase) {
        TaskPhase.PLANNING -> "Согласовать план и критерии готовности"
        TaskPhase.EXECUTION -> "Выполнить следующий шаг реализации"
        TaskPhase.VALIDATION -> "Проверить результат, тесты, риски и соответствие требованиям"
        TaskPhase.DONE -> "Следующий обычный запрос пользователя станет новой задачей"
    }
}
