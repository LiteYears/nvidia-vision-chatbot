package com.example.agent.plan

import java.util.UUID

/**
 * Core engine for structured task planning, subtask state transitions,
 * verification enforcement, and runaway step prevention.
 */
class TaskPlanner {

    companion object {
        const val DEFAULT_MAX_STEPS = 15

        private val PLAN_CODE_BLOCK_REGEX = Regex(
            """```(?:plan|task_plan|checklist)?\s*([\s\S]*?)\s*```""",
            RegexOption.IGNORE_CASE
        )

        private val PLAN_HEADER_REGEX = Regex(
            """(?:^|\n)\s*(?:PLAN|TASK PLAN|CURRENT PLAN):\s*\n([\s\S]*?)(?=\n\s*(?:GOAL|OBJECTIVE|NOTES|EXECUTION|$))""",
            RegexOption.IGNORE_CASE
        )
    }

    /**
     * Initializes a structured TaskPlan for a given goal.
     */
    fun createInitialPlan(
        sessionId: String,
        goal: String,
        maxSteps: Int = DEFAULT_MAX_STEPS
    ): TaskPlan {
        val trimmedGoal = goal.trim()
        val decomposedSubtasks = decomposeGoalIntoSubtasks(trimmedGoal)

        val plan = TaskPlan(
            sessionId = sessionId,
            goal = trimmedGoal,
            subtasks = decomposedSubtasks,
            currentSubtaskId = decomposedSubtasks.firstOrNull()?.id,
            maxSteps = maxSteps,
            stepCount = 0,
            isCompleted = false,
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )

        // Mark the first subtask as RUNNING to initialize execution
        return if (decomposedSubtasks.isNotEmpty()) {
            startSubtask(plan, decomposedSubtasks.first().id)
        } else {
            plan
        }
    }

    /**
     * Parses the agent's LLM output to detect if the agent created or updated its own plan.
     * Supports markdown checklists (☐, ☑, [ ], [x], [/], [!]), JSON blocks, and PLAN: headers.
     */
    fun parsePlanFromAgentOutput(agentOutput: String, currentPlan: TaskPlan): TaskPlan? {
        if (agentOutput.isBlank()) return null

        // 1. Look for ```plan ... ``` code block
        val blockMatch = PLAN_CODE_BLOCK_REGEX.find(agentOutput)
        val candidateContent = if (blockMatch != null) {
            blockMatch.groupValues[1].trim()
        } else {
            // 2. Look for PLAN: section header
            val headerMatch = PLAN_HEADER_REGEX.find(agentOutput)
            headerMatch?.groupValues?.get(1)?.trim()
        }

        if (candidateContent.isNullOrBlank()) {
            // Check for explicit inline checklist items if present
            if (agentOutput.contains("☐") || agentOutput.contains("☑") || agentOutput.contains("- [ ]") || agentOutput.contains("- [x]")) {
                return parseChecklistLines(agentOutput, currentPlan)
            }
            return null
        }

        return parseChecklistLines(candidateContent, currentPlan)
    }

    /**
     * Parses checklist lines and merges them with the existing plan.
     */
    private fun parseChecklistLines(content: String, currentPlan: TaskPlan): TaskPlan? {
        val lines = content.lines()
        val parsedSubtasks = mutableListOf<Subtask>()
        var index = 0

        for (rawLine in lines) {
            val line = rawLine.trim()
            if (line.isBlank() || line.startsWith("#") || line.startsWith("//")) continue

            val status: SubtaskStatus?
            val description: String

            when {
                // Completed markers
                line.startsWith("☑") || line.startsWith("✔") || line.startsWith("[x]", ignoreCase = true) || line.startsWith("- [x]", ignoreCase = true) || line.startsWith("* [x]", ignoreCase = true) -> {
                    status = SubtaskStatus.COMPLETED
                    description = line.replaceFirst(Regex("""^(?:☑|✔|[-*]?\s*\[x\])\s*""", RegexOption.IGNORE_CASE), "").trim()
                }
                // Running / In-progress markers
                line.startsWith("⏳") || line.startsWith("[/]", ignoreCase = true) || line.startsWith("- [/]", ignoreCase = true) || line.startsWith("[>]", ignoreCase = true) || line.startsWith("- [>]", ignoreCase = true) -> {
                    status = SubtaskStatus.RUNNING
                    description = line.replaceFirst(Regex("""^(?:⏳|[-*]?\s*\[[/>]\])\s*""", RegexOption.IGNORE_CASE), "").trim()
                }
                // Failed markers
                line.startsWith("☒") || line.startsWith("[!]", ignoreCase = true) || line.startsWith("- [!]", ignoreCase = true) || line.startsWith("❌") -> {
                    status = SubtaskStatus.FAILED
                    description = line.replaceFirst(Regex("""^(?:☒|❌|[-*]?\s*\[!\])\s*""", RegexOption.IGNORE_CASE), "").trim()
                }
                // Pending markers
                line.startsWith("☐") || line.startsWith("[ ]") || line.startsWith("- [ ]") || line.startsWith("* [ ]") -> {
                    status = SubtaskStatus.PENDING
                    description = line.replaceFirst(Regex("""^(?:☐|[-*]?\s*\[\s*\])\s*"""), "").trim()
                }
                // Numbered lines: 1. Subtask description
                line.matches(Regex("""^\d+[\.\)]\s+.*""")) -> {
                    status = SubtaskStatus.PENDING
                    description = line.replaceFirst(Regex("""^\d+[\.\)]\s+"""), "").trim()
                }
                else -> {
                    status = null
                    description = ""
                }
            }

            if (status != null && description.isNotBlank()) {
                // Try matching with existing subtask by description similarity to preserve IDs and results
                val existing = currentPlan.subtasks.find {
                    it.description.equals(description, ignoreCase = true) ||
                        it.description.contains(description, ignoreCase = true) ||
                        description.contains(it.description, ignoreCase = true)
                }

                parsedSubtasks.add(
                    Subtask(
                        id = existing?.id ?: UUID.randomUUID().toString(),
                        description = description,
                        status = status,
                        result = existing?.result,
                        orderIndex = index++,
                        retryCount = existing?.retryCount ?: 0,
                        updatedAt = System.currentTimeMillis()
                    )
                )
            }
        }

        if (parsedSubtasks.isEmpty()) return null

        val isAllDone = parsedSubtasks.all { it.status == SubtaskStatus.COMPLETED }
        val activeSubtaskId = parsedSubtasks.firstOrNull { it.status == SubtaskStatus.RUNNING }?.id
            ?: parsedSubtasks.firstOrNull { it.status == SubtaskStatus.PENDING }?.id
            ?: currentPlan.currentSubtaskId

        return currentPlan.copy(
            subtasks = parsedSubtasks,
            currentSubtaskId = activeSubtaskId,
            isCompleted = isAllDone,
            updatedAt = System.currentTimeMillis()
        )
    }

    /**
     * Starts execution of a subtask, transitioning it to RUNNING.
     */
    fun startSubtask(plan: TaskPlan, subtaskId: String): TaskPlan {
        val updatedSubtasks = plan.subtasks.map { subtask ->
            if (subtask.id == subtaskId) {
                subtask.copy(status = SubtaskStatus.RUNNING, updatedAt = System.currentTimeMillis())
            } else if (subtask.status == SubtaskStatus.RUNNING && subtask.id != subtaskId) {
                // If another task was running and not completed, keep it pending
                subtask.copy(status = SubtaskStatus.PENDING)
            } else {
                subtask
            }
        }

        return plan.copy(
            subtasks = updatedSubtasks,
            currentSubtaskId = subtaskId,
            updatedAt = System.currentTimeMillis()
        )
    }

    /**
     * Advances to the next pending subtask in order.
     */
    fun startNextSubtask(plan: TaskPlan): TaskPlan {
        val nextSubtask = plan.subtasks.firstOrNull { it.status == SubtaskStatus.PENDING }
        return if (nextSubtask != null) {
            startSubtask(plan, nextSubtask.id)
        } else {
            plan
        }
    }

    /**
     * Called after a successful tool operation.
     * Updates the subtask with intermediate tool results, but DOES NOT mark it COMPLETED!
     * The agent must explicitly verify that the subtask's objective was achieved.
     */
    fun recordToolSuccess(
        plan: TaskPlan,
        subtaskId: String?,
        toolName: String,
        toolResult: String
    ): TaskPlan {
        val targetId = subtaskId ?: plan.currentSubtaskId ?: return plan
        val snippet = if (toolResult.length > 250) toolResult.take(250) + "..." else toolResult

        val updatedSubtasks = plan.subtasks.map { subtask ->
            if (subtask.id == targetId) {
                subtask.copy(
                    result = "Tool '$toolName' output: $snippet",
                    updatedAt = System.currentTimeMillis()
                    // Status deliberately remains RUNNING!
                )
            } else {
                subtask
            }
        }

        return plan.copy(
            subtasks = updatedSubtasks,
            updatedAt = System.currentTimeMillis()
        )
    }

    /**
     * Called after a tool execution failure.
     * Records the failure on the subtask and transitions it to FAILED so the agent can retry.
     */
    fun recordToolFailure(
        plan: TaskPlan,
        subtaskId: String?,
        toolName: String,
        error: String
    ): TaskPlan {
        val targetId = subtaskId ?: plan.currentSubtaskId ?: return plan
        val snippet = if (error.length > 250) error.take(250) + "..." else error

        val updatedSubtasks = plan.subtasks.map { subtask ->
            if (subtask.id == targetId) {
                subtask.copy(
                    status = SubtaskStatus.FAILED,
                    result = "Failed with tool '$toolName': $snippet",
                    retryCount = subtask.retryCount + 1,
                    updatedAt = System.currentTimeMillis()
                )
            } else {
                subtask
            }
        }

        return plan.copy(
            subtasks = updatedSubtasks,
            updatedAt = System.currentTimeMillis()
        )
    }

    /**
     * Resets a FAILED subtask back to RUNNING to allow retry or an alternate approach.
     */
    fun retrySubtask(plan: TaskPlan, subtaskId: String): TaskPlan {
        val updatedSubtasks = plan.subtasks.map { subtask ->
            if (subtask.id == subtaskId) {
                subtask.copy(
                    status = SubtaskStatus.RUNNING,
                    updatedAt = System.currentTimeMillis()
                )
            } else {
                subtask
            }
        }

        return plan.copy(
            subtasks = updatedSubtasks,
            currentSubtaskId = subtaskId,
            updatedAt = System.currentTimeMillis()
        )
    }

    /**
     * Verifies that the subtask's objective was achieved and transitions it to COMPLETED.
     * If all subtasks are verified, marks the overall plan as completed.
     */
    fun verifyAndCompleteSubtask(
        plan: TaskPlan,
        subtaskId: String?,
        verificationNotes: String? = null
    ): TaskPlan {
        val targetId = subtaskId ?: plan.currentSubtaskId ?: return plan

        val updatedSubtasks = plan.subtasks.map { subtask ->
            if (subtask.id == targetId) {
                val newResult = if (!verificationNotes.isNullOrBlank()) {
                    verificationNotes
                } else {
                    subtask.result ?: "Verified objective achieved"
                }
                subtask.copy(
                    status = SubtaskStatus.COMPLETED,
                    result = newResult,
                    updatedAt = System.currentTimeMillis()
                )
            } else {
                subtask
            }
        }

        val allDone = updatedSubtasks.all { it.status == SubtaskStatus.COMPLETED }
        val nextPending = updatedSubtasks.firstOrNull { it.status == SubtaskStatus.PENDING }

        return plan.copy(
            subtasks = updatedSubtasks,
            currentSubtaskId = nextPending?.id,
            isCompleted = allDone,
            updatedAt = System.currentTimeMillis()
        )
    }

    /**
     * Increments the executed step count to guard against runaway executions.
     */
    fun incrementStep(plan: TaskPlan): TaskPlan {
        return plan.copy(
            stepCount = plan.stepCount + 1,
            updatedAt = System.currentTimeMillis()
        )
    }

    /**
     * Checks if all subtasks are COMPLETED and marks the overall plan COMPLETED.
     */
    fun checkAndCompleteOverallPlan(plan: TaskPlan): TaskPlan {
        val allDone = plan.subtasks.isNotEmpty() && plan.subtasks.all { it.status == SubtaskStatus.COMPLETED }
        return if (allDone && !plan.isCompleted) {
            plan.copy(isCompleted = true, updatedAt = System.currentTimeMillis())
        } else {
            plan
        }
    }

    /**
     * Formats the task plan into a compact, structured prompt block for the LLM.
     */
    fun formatPlanForPrompt(plan: TaskPlan): String {
        return buildString {
            appendLine("=== STRUCTURED TASK PLAN ===")
            appendLine("GOAL: \"${plan.goal}\"")
            appendLine("OVERALL STATUS: ${if (plan.isCompleted) "COMPLETED" else "IN PROGRESS"}")
            appendLine("STEP LIMIT: ${plan.stepCount}/${plan.maxSteps} steps executed")
            appendLine()
            appendLine("SUBTASKS:")
            plan.subtasks.forEachIndexed { idx, subtask ->
                val marker = when (subtask.status) {
                    SubtaskStatus.COMPLETED -> "[x]"
                    SubtaskStatus.RUNNING -> "[/]"
                    SubtaskStatus.FAILED -> "[!]"
                    SubtaskStatus.PENDING -> "[ ]"
                }
                val activeTag = if (subtask.id == plan.currentSubtaskId) " <-- CURRENT ACTIVE SUBTASK" else ""
                appendLine("${idx + 1}. $marker ${subtask.description} (${subtask.status.name})$activeTag")
                if (!subtask.result.isNullOrBlank()) {
                    appendLine("   Result/Diagnostic: ${subtask.result}")
                }
            }
            appendLine()
            appendLine("PLANNING GUIDELINES:")
            appendLine("- Before executing a tool, know which subtask you are addressing.")
            appendLine("- A successful tool output DOES NOT complete a subtask; you must verify that the objective was truly achieved.")
            appendLine("- If a tool fails, observe diagnostics, then retry or choose an alternative subtask/tool.")
            appendLine("- You can dynamically update your plan by outputting a ```plan checklist in your response.")
            appendLine("- When all subtasks are verified, conclude the task and provide your final response.")
            appendLine("============================")
        }
    }

    /**
     * Decomposes a goal into ordered subtasks based on domain heuristics.
     */
    private fun decomposeGoalIntoSubtasks(goal: String): List<Subtask> {
        val lower = goal.lowercase()

        // Example from requirements: "Research three Android LLM frameworks, compare them, and create a report."
        if (lower.contains("three") && (lower.contains("framework") || lower.contains("compare") || lower.contains("report"))) {
            return listOf(
                Subtask(description = "Find relevant frameworks", orderIndex = 0),
                Subtask(description = "Research framework 1", orderIndex = 1),
                Subtask(description = "Research framework 2", orderIndex = 2),
                Subtask(description = "Research framework 3", orderIndex = 3),
                Subtask(description = "Compare findings", orderIndex = 4),
                Subtask(description = "Create report", orderIndex = 5),
                Subtask(description = "Verify report", orderIndex = 6),
                Subtask(description = "Complete", orderIndex = 7)
            )
        }

        // Python code / script / run
        if (lower.contains("python") || lower.contains("script") || lower.contains("code") || lower.contains("write a program")) {
            return listOf(
                Subtask(description = "Analyze requirements and prepare solution", orderIndex = 0),
                Subtask(description = "Write script to workspace using file_write", orderIndex = 1),
                Subtask(description = "Execute script using python_execute", orderIndex = 2),
                Subtask(description = "Inspect and verify script execution output", orderIndex = 3),
                Subtask(description = "Synthesize final deliverable", orderIndex = 4)
            )
        }

        // Research / Search / Benchmark
        if (lower.contains("research") || lower.contains("search") || lower.contains("find") || lower.contains("compare")) {
            return listOf(
                Subtask(description = "Identify research targets and query parameters", orderIndex = 0),
                Subtask(description = "Perform search and gather technical data", orderIndex = 1),
                Subtask(description = "Analyze and cross-reference findings", orderIndex = 2),
                Subtask(description = "Synthesize deliverable report", orderIndex = 3),
                Subtask(description = "Verify findings satisfy goal criteria", orderIndex = 4)
            )
        }

        // Generic autonomous workflow
        return listOf(
            Subtask(description = "Clarify scope and break down requirements", orderIndex = 0),
            Subtask(description = "Execute core task operations", orderIndex = 1),
            Subtask(description = "Verify deliverable and output accuracy", orderIndex = 2),
            Subtask(description = "Finalize results and complete objective", orderIndex = 3)
        )
    }
}
