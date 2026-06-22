package org.example.task13

import org.example.agents.memory.MarkdownMemoryStore
import org.example.agents.memory.MemoryLayer
import org.example.agents.memory.MemoryLayeredAgent
import org.example.agents.memory.userMemoryStore
import org.example.agents.profile.UserProfileOnboarding
import org.example.agents.state.TaskPhase
import org.example.agents.state.TaskState
import org.example.agents.state.TaskStateMachine
import org.example.agents.state.WorkingMemoryTaskStateStore

fun task13() {
    val userName = UserProfileOnboarding.askUserName()
    val memoryStore = userMemoryStore(userName)
    UserProfileOnboarding.ensureProfile(userName, memoryStore)
    val stateMachine = TaskStateMachine(WorkingMemoryTaskStateStore(memoryStore))
    val agent = MemoryLayeredAgent(
        store = memoryStore,
        sessionContextProvider = {
            """
            ${UserProfileOnboarding.sessionContext(userName)}

            ${stateMachine.promptBlock()}

            PAUSE/RESUME RULE:
            Working memory is the source of truth after a pause.
            Continue from the current phase, current step and expected action without asking the user to repeat previous explanations.
            """.trimIndent()
        }
    )

    stateMachine.current()?.let {
        println("\nНайдена сохраненная задача в working memory:")
        printState(it)
    } ?: println("\nАктивной задачи нет. Первый обычный запрос станет новой задачей.")

    println("\nКоманды:")
    println("/profile                  - показать профиль текущего пользователя")
    println("/state                    - показать состояние задачи")
    println("/reset-state              - очистить состояние задачи")
    println("/memory                   - показать память")
    println("/files                    - показать файлы")
    println("/clear short|work|long    - очистить слой памяти")
    println("/exit                     - пауза/выход")

    try {
        while (true) {
            print("\n${status(stateMachine)} $userName: ")
            val input = readlnOrNull()?.trim() ?: break
            if (input.isEmpty()) continue

            if (input.startsWith("/")) {
                if (!handleCommand(input, userName, stateMachine, agent)) break
            } else {
                val stateBefore = stateMachine.ensureTaskFor(input)
                println(formatStageMessage("Сейчас", stateBefore))

                println("Агент думает...")
                val response = agent.ask(input)
                if (response != null) {
                    val stateAfter = stateMachine.updateAfterIteration(input, response)
                    println(formatStageMessage("Следующий этап", stateAfter))
                }
            }
        }
    } finally {
        agent.finishSession()
        println("[State] Состояние задачи сохранено в working memory.")
    }
}

private fun handleCommand(
    input: String,
    userName: String,
    stateMachine: TaskStateMachine,
    agent: MemoryLayeredAgent
): Boolean {
    val parts = input.split(" ", limit = 2)

    when (parts[0]) {
        "/exit" -> return false
        "/profile" -> UserProfileOnboarding.printCurrentProfile(userName, userMemoryStore(userName))
        "/state" -> {
            val state = stateMachine.current()
            if (state == null) println("Нет активной задачи.") else printState(state)
        }
        "/reset-state" -> {
            stateMachine.clear()
            println("Состояние задачи очищено.")
        }
        "/memory" -> agent.printMemory()
        "/files" -> {
            agent.printStoragePaths()
            println("- user working.md section <!-- TASK_STATE_START -->")
        }
        "/clear" -> {
            val layer = parts.getOrNull(1)?.trim()?.let { parseMemoryLayer(it) }
            if (layer == null) {
                println("Используйте: /clear short|work|long")
                return true
            }
            agent.clear(layer)
            println("Очищен слой памяти: $layer")
        }
        else -> println("Неизвестная команда.")
    }

    return true
}

private fun printState(state: TaskState) {
    println("Task: ${state.taskTitle}")
    println("Phase: ${state.phase}")
    println("Current step: ${state.currentStep}")
    println("Expected action: ${state.expectedAction}")
}

private fun status(stateMachine: TaskStateMachine): String {
    val state = stateMachine.current() ?: return "[no-task]"
    return "[${state.phase} | ${state.currentStep}]"
}

private fun formatStageMessage(prefix: String, state: TaskState): String {
    val label = when (state.phase) {
        TaskPhase.PLANNING -> "planning"
        TaskPhase.EXECUTION -> "execution"
        TaskPhase.VALIDATION -> "validation"
        TaskPhase.DONE -> "done"
    }

    return "[$prefix: $label] Шаг: ${state.currentStep}. Ожидаемое действие: ${state.expectedAction}"
}

private fun parseMemoryLayer(value: String): MemoryLayer? {
    return when (value) {
        "short" -> MemoryLayer.SHORT_TERM
        "work", "working" -> MemoryLayer.WORKING
        "long" -> MemoryLayer.LONG_TERM
        else -> null
    }
}
