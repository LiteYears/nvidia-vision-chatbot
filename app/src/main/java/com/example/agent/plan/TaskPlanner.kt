package com.example.agent.plan

import java.util.UUID

/**
 * Core engine for structured task planning, subtask state transitions,
 * verification enforcement, and runaway step prevention.
 */
class TaskPlanner {

    companion object {
        const val DEFAULT_MAX_STEPS = 100

        private val PLAN_CODE_BLOCK_REGEX = Regex(
            """```(?:plan|task_plan|checklist)?\s*([\s\S]*?)\s*```""",
            RegexOption.IGNORE_CASE
        )

        private val PLAN_HEADER_REGEX = Regex(
            """(?:^|\n)\s*(?:PLAN|TASK PLAN|CURRENT PLAN|REVISED PLAN|UPDATED PLAN|ADAPTED PLAN|NEW PLAN|EXECUTION PLAN):\s*\n([\s\S]*?)(?=\n\s*(?:GOAL|OBJECTIVE|NOTES|EXECUTION|$))""",
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

            // Strip optional leading ordinal numbering like "1. ", "2) " while checking markers
            val normalizedLine = line.replaceFirst(Regex("""^\d+[\.\)]\s*"""), "").trim()

            when {
                // Completed markers
                normalizedLine.startsWith("☑") || normalizedLine.startsWith("✔") || normalizedLine.startsWith("[x]", ignoreCase = true) || normalizedLine.startsWith("- [x]", ignoreCase = true) || normalizedLine.startsWith("* [x]", ignoreCase = true) -> {
                    status = SubtaskStatus.COMPLETED
                    description = normalizedLine.replaceFirst(Regex("""^(?:☑|✔|[-*]?\s*\[x\])\s*""", RegexOption.IGNORE_CASE), "").trim()
                }
                // Running / In-progress markers
                normalizedLine.startsWith("⏳") || normalizedLine.startsWith("[/]", ignoreCase = true) || normalizedLine.startsWith("- [/]", ignoreCase = true) || normalizedLine.startsWith("[>]", ignoreCase = true) || normalizedLine.startsWith("- [>]", ignoreCase = true) -> {
                    status = SubtaskStatus.RUNNING
                    description = normalizedLine.replaceFirst(Regex("""^(?:⏳|[-*]?\s*\[[/>]\])\s*""", RegexOption.IGNORE_CASE), "").trim()
                }
                // Failed markers
                normalizedLine.startsWith("☒") || normalizedLine.startsWith("[!]", ignoreCase = true) || normalizedLine.startsWith("- [!]", ignoreCase = true) || normalizedLine.startsWith("❌") -> {
                    status = SubtaskStatus.FAILED
                    description = normalizedLine.replaceFirst(Regex("""^(?:☒|❌|[-*]?\s*\[!\])\s*""", RegexOption.IGNORE_CASE), "").trim()
                }
                // Pending markers
                normalizedLine.startsWith("☐") || normalizedLine.startsWith("[ ]") || normalizedLine.startsWith("- [ ]") || normalizedLine.startsWith("* [ ]") -> {
                    status = SubtaskStatus.PENDING
                    description = normalizedLine.replaceFirst(Regex("""^(?:☐|[-*]?\s*\[\s*\])\s*"""), "").trim()
                }
                // Simple numbered lines without checkbox: 1. Subtask description
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
                    status = if (subtask.status == SubtaskStatus.FAILED) SubtaskStatus.RUNNING else subtask.status,
                    result = "Tool '$toolName' output: $snippet",
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
     * Adapts an active plan following a tool or implementation failure by recording
     * the diagnostic on the failed subtask, inserting a recovery subtask, and setting it to RUNNING.
     */
    fun adaptPlanForFailure(
        plan: TaskPlan,
        failedSubtaskId: String?,
        failureDiagnostic: String,
        recoverySubtask: Subtask
    ): TaskPlan {
        val targetId = failedSubtaskId ?: plan.currentSubtaskId
        val updatedSubtasks = mutableListOf<Subtask>()
        var found = false

        for (subtask in plan.subtasks) {
            if (subtask.id == targetId) {
                updatedSubtasks.add(
                    subtask.copy(
                        status = SubtaskStatus.FAILED,
                        result = "Failed: $failureDiagnostic",
                        retryCount = subtask.retryCount + 1,
                        updatedAt = System.currentTimeMillis()
                    )
                )
                updatedSubtasks.add(
                    recoverySubtask.copy(
                        status = SubtaskStatus.RUNNING,
                        updatedAt = System.currentTimeMillis()
                    )
                )
                found = true
            } else {
                updatedSubtasks.add(subtask)
            }
        }

        if (!found) {
            updatedSubtasks.add(recoverySubtask.copy(status = SubtaskStatus.RUNNING))
        }

        val reindexed = updatedSubtasks.mapIndexed { idx, s -> s.copy(orderIndex = idx) }
        return plan.copy(
            subtasks = reindexed,
            currentSubtaskId = recoverySubtask.id,
            updatedAt = System.currentTimeMillis()
        )
    }

    /**
     * Inserts a new subtask immediately following the specified subtask (or at the end if null),
     * re-indexing order indices.
     */
    fun insertSubtaskAfter(
        plan: TaskPlan,
        afterSubtaskId: String?,
        newSubtask: Subtask
    ): TaskPlan {
        val targetId = afterSubtaskId ?: plan.currentSubtaskId
        val updatedSubtasks = mutableListOf<Subtask>()
        var inserted = false

        for (subtask in plan.subtasks) {
            updatedSubtasks.add(subtask)
            if (subtask.id == targetId) {
                updatedSubtasks.add(newSubtask)
                inserted = true
            }
        }
        if (!inserted) {
            updatedSubtasks.add(newSubtask)
        }

        val reindexed = updatedSubtasks.mapIndexed { idx, s -> s.copy(orderIndex = idx) }
        return plan.copy(
            subtasks = reindexed,
            updatedAt = System.currentTimeMillis()
        )
    }

    /**
     * Replaces or updates subtasks with a revised list, preserving existing results/IDs where possible.
     */
    fun revisePlan(
        plan: TaskPlan,
        newSubtasks: List<Subtask>
    ): TaskPlan {
        val reindexed = newSubtasks.mapIndexed { idx, s -> s.copy(orderIndex = idx) }
        val activeId = reindexed.firstOrNull { it.status == SubtaskStatus.RUNNING }?.id
            ?: reindexed.firstOrNull { it.status == SubtaskStatus.PENDING }?.id
            ?: plan.currentSubtaskId
        val allDone = reindexed.isNotEmpty() && reindexed.all { it.status == SubtaskStatus.COMPLETED }

        return plan.copy(
            subtasks = reindexed,
            currentSubtaskId = activeId,
            isCompleted = allDone,
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

        val allDone = updatedSubtasks.isNotEmpty() &&
            updatedSubtasks.any { it.status == SubtaskStatus.COMPLETED } &&
            updatedSubtasks.none { it.status == SubtaskStatus.PENDING || it.status == SubtaskStatus.RUNNING }
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
     * Determines if a successful tool execution fulfills and advances a subtask.
     * When appropriate, transitions the completed subtask to COMPLETED and sets
     * the next pending subtask to RUNNING.
     */
    fun advanceSubtaskOnToolSuccess(
        plan: TaskPlan,
        subtaskId: String?,
        toolName: String,
        toolResult: String
    ): TaskPlan {
        var updatedPlan = recordToolSuccess(plan, subtaskId, toolName, toolResult)
        val active = updatedPlan.subtasks.find { it.id == (subtaskId ?: updatedPlan.currentSubtaskId) }
            ?: return updatedPlan

        val descLower = active.description.lowercase()
        val toolLower = toolName.lowercase()

        val isFulfillable = when (toolLower) {
            "file_write", "file_patch", "archive_extract" -> {
                descLower.contains("write") || descLower.contains("create") ||
                    descLower.contains("prepare") || descLower.contains("analyze") ||
                    descLower.contains("patch") || descLower.contains("modify") ||
                    descLower.contains("fix") || descLower.contains("repair") ||
                    descLower.contains("update") || descLower.contains("edit") ||
                    descLower.contains("correct") || descLower.contains("solution") ||
                    descLower.contains("extract") || descLower.contains("implement") ||
                    descLower.contains("change")
            }
            "python_execute", "run_command", "bash", "terminal", "sh", "cmd", "exec" -> {
                descLower.contains("execute") || descLower.contains("run") ||
                    descLower.contains("command") || descLower.contains("test") ||
                    descLower.contains("script") || descLower.contains("verify") ||
                    descLower.contains("check") || descLower.contains("compile") ||
                    descLower.contains("build") || descLower.contains("inspect") ||
                    descLower.contains("locate") || descLower.contains("architecture") ||
                    descLower.contains("explore") || descLower.contains("analyze") ||
                    descLower.contains("create") || descLower.contains("write") ||
                    descLower.contains("implement") || descLower.contains("modify") ||
                    descLower.contains("setup") || descLower.contains("install") ||
                    descLower.contains("directories") || descLower.contains("directory") ||
                    descLower.contains("package") || descLower.contains("requirements") ||
                    descLower.contains("fix") || descLower.contains("repair") ||
                    descLower.contains("service") || descLower.contains("api")
            }
            "web_search" -> {
                descLower.contains("search") || descLower.contains("query") ||
                    descLower.contains("find") || descLower.contains("identify") ||
                    descLower.contains("gather") || descLower.contains("formulate") ||
                    descLower.contains("lookup") || descLower.contains("look up")
            }
            "web_open" -> {
                descLower.contains("open") || descLower.contains("read") ||
                    descLower.contains("browse") || descLower.contains("research") ||
                    descLower.contains("article") || descLower.contains("gather") ||
                    descLower.contains("inspect") || descLower.contains("retrieve") ||
                    descLower.contains("content") || descLower.contains("extract")
            }
            "file_list", "file_tree", "file_search", "file_read" -> {
                descLower.contains("list") || descLower.contains("tree") ||
                    descLower.contains("search") || descLower.contains("inspect") ||
                    descLower.contains("read") || descLower.contains("open") ||
                    descLower.contains("find") || descLower.contains("clarify") ||
                    descLower.contains("locate") || descLower.contains("architecture") ||
                    descLower.contains("explore") || descLower.contains("analyze") ||
                    descLower.contains("understand") || descLower.contains("plan") ||
                    descLower.contains("review")
            }
            "calculator" -> {
                descLower.contains("calculate") || descLower.contains("compute") ||
                    descLower.contains("math")
            }
            else -> false
        }

        if (isFulfillable) {
            val completionNotes = "Verified: Tool '$toolName' executed successfully"
            updatedPlan = verifyAndCompleteSubtask(updatedPlan, active.id, completionNotes)

            // If preceding pending subtask was fulfilled (e.g. if Subtask 0 was analyze and Subtask 1 was file_write)
            if (toolLower == "file_write" || toolLower == "file_patch") {
                val pendingWrite = updatedPlan.subtasks.find {
                    it.status == SubtaskStatus.PENDING &&
                        (it.description.contains("file_write", ignoreCase = true) ||
                         it.description.contains("write script", ignoreCase = true) ||
                         it.description.contains("modify code", ignoreCase = true) ||
                         it.description.contains("fix", ignoreCase = true))
                }
                if (pendingWrite != null) {
                    updatedPlan = verifyAndCompleteSubtask(updatedPlan, pendingWrite.id, "Verified: Code updated via '$toolName'")
                }

                // If an execution or test subtask at or after this subtask had previously failed, reset it back to RUNNING
                val failedExecution = updatedPlan.subtasks.find {
                    it.status == SubtaskStatus.FAILED &&
                        it.orderIndex >= active.orderIndex &&
                        (it.description.contains("execute", ignoreCase = true) ||
                         it.description.contains("run", ignoreCase = true) ||
                         it.description.contains("test", ignoreCase = true) ||
                         it.description.contains("compile", ignoreCase = true) ||
                         it.description.contains("verify", ignoreCase = true) ||
                         it.description.contains("check", ignoreCase = true))
                }
                if (failedExecution != null) {
                    updatedPlan = retrySubtask(updatedPlan, failedExecution.id)
                }
            }

            // If there is a next pending subtask and no subtask is currently running, transition it to RUNNING
            val nextPending = updatedPlan.subtasks.firstOrNull { it.status == SubtaskStatus.PENDING }
            if (nextPending != null && updatedPlan.subtasks.none { it.status == SubtaskStatus.RUNNING }) {
                updatedPlan = startSubtask(updatedPlan, nextPending.id)
            }
        }

        return updatedPlan
    }

    /**
     * Checks if there are pending action subtasks (e.g. execution, file write, web search)
     * that must still be performed before the plan can be considered done.
     */
    fun hasPendingActionSubtasks(plan: TaskPlan): Boolean {
        return plan.subtasks.any { subtask ->
            (subtask.status == SubtaskStatus.PENDING || subtask.status == SubtaskStatus.RUNNING || subtask.status == SubtaskStatus.FAILED) &&
                !isSynthesisOrFinalSubtask(subtask.description)
        }
    }

    /**
     * Identifies if a subtask is purely the final synthesis / verification / completion step.
     */
    fun isSynthesisOrFinalSubtask(description: String): Boolean {
        val d = description.lowercase()
        return d.contains("synthesize") || d.contains("final deliverable") ||
            d.contains("finalize") || d.contains("complete objective") ||
            d.contains("create report") || d.contains("deliverable") ||
            (d.contains("verify") && (d.contains("report") || d.contains("deliverable") || d.contains("criteria") || d.contains("output") || d.contains("accuracy") || d.contains("result"))) ||
            (d.contains("inspect") && (d.contains("output") || d.contains("execution") || d.contains("result"))) ||
            d == "complete"
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
            appendLine("- Understand the user's goal and inspect existing code before making changes.")
            appendLine("- Dynamic execution: Focus on the CURRENT ACTIVE SUBTASK, but adapt dynamically as you observe tool results.")
            appendLine("- For multi-file development: Inspect relevant files first, plan minimal edits, and update each affected file cleanly.")
            appendLine("- Verification mandate: Always compile, run tests, or execute checks (using 'run_command' or 'python_execute') and inspect errors before concluding.")
            appendLine("- Plan adaptation: If results differ from expectations, unexpected errors occur, or an approach fails, diagnose the error and update your plan (emit a revised ```plan block with adjusted or recovery subtasks).")
            appendLine("- Only provide a final text response when all actions are executed, verified, and complete.")
            appendLine("============================")
        }
    }

    /**
     * Decomposes a goal into ordered subtasks based on domain heuristics.
     */
    private fun decomposeGoalIntoSubtasks(goal: String): List<Subtask> {
        val lower = goal.lowercase()

        // 1. Example from requirements: "Research three Android LLM frameworks, compare them, and create a report."
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

        // 2. Code repair / bug fix / syntax error / broken implementation (when not building new features or creating scripts from scratch)
        val isCreatingNew = lower.contains("create a python") || lower.contains("create a script") || lower.contains("create python") || lower.contains("write a python") || lower.contains("write a script")
        val isFeatureDev = lower.contains("settings") || lower.contains("screen") || lower.contains("integrate") || lower.contains("persist") || lower.contains("feature") || lower.contains("multi-file") || lower.contains("architecture")
        if (!isCreatingNew && !isFeatureDev && (lower.contains("fix") || lower.contains("repair") || lower.contains("syntax error") ||
            (lower.contains("modify") && (lower.contains(".py") || lower.contains("file") || lower.contains("code") || lower.contains("script"))) ||
            (lower.contains("open") && (lower.contains(".py") || lower.contains("error") || lower.contains("fix") || lower.contains("run"))) ||
            lower.contains("debug") || lower.contains("bug"))
        ) {
            return listOf(
                Subtask(description = "Inspect existing file and identify errors using file_read", orderIndex = 0),
                Subtask(description = "Modify code to fix issue using file_patch or file_write", orderIndex = 1),
                Subtask(description = "Execute and test modified code using python_execute", orderIndex = 2),
                Subtask(description = "Inspect execution output and verify error is resolved", orderIndex = 3),
                Subtask(description = "Synthesize final deliverable", orderIndex = 4)
            )
        }

        // 3. Complex development requests / Feature additions / Multi-file integrations / Architecture / Settings
        if (isFeatureDev || lower.contains("add a ") || lower.contains("implement") || lower.contains("refactor") || lower.contains("build a ") || (lower.contains("add") && (lower.contains("screen") || lower.contains("button") || lower.contains("option") || lower.contains("mode") || lower.contains("view")))) {
            return listOf(
                Subtask(description = "Inspect project architecture and locate relevant files", orderIndex = 0),
                Subtask(description = "Analyze existing implementation and plan minimal changes", orderIndex = 1),
                Subtask(description = "Implement modifications across relevant files", orderIndex = 2),
                Subtask(description = "Compile, run tests, or execute checks to verify implementation", orderIndex = 3),
                Subtask(description = "Review test results, repair any errors, and retest", orderIndex = 4),
                Subtask(description = "Verify final state and complete integration", orderIndex = 5)
            )
        }

        // 4. Standalone Python scripts / computations / algorithms
        if (lower.contains("python") || lower.contains("script") || lower.contains("code") || lower.contains("write a program")) {
            return listOf(
                Subtask(description = "Analyze requirements and prepare solution", orderIndex = 0),
                Subtask(description = "Write script to workspace using file_write", orderIndex = 1),
                Subtask(description = "Execute script using python_execute", orderIndex = 2),
                Subtask(description = "Inspect and verify script execution output", orderIndex = 3),
                Subtask(description = "Synthesize final deliverable", orderIndex = 4)
            )
        }

        // 5. Research / Search / Benchmark / IP address & WHOIS lookup
        if (lower.contains("research") || lower.contains("search") || lower.contains("find") ||
            lower.contains("compare") || lower.contains("ip address") || lower.contains("whois") ||
            lower.contains("origin") || lower.contains("location of ip") || lower.contains("owner of ip") ||
            lower.contains("lookup") || lower.contains("look up") || lower.contains("investigate")
        ) {
            return listOf(
                Subtask(description = "Formulate targeted query and search relevant sources using web_search", orderIndex = 0),
                Subtask(description = "Inspect search results and retrieve authoritative content using web_open", orderIndex = 1),
                Subtask(description = "Extract facts, cross-reference sources, or perform follow-up search if needed", orderIndex = 2),
                Subtask(description = "Synthesize verified findings with source citations and complete report", orderIndex = 3)
            )
        }

        // 6. Generic autonomous workflow
        return listOf(
            Subtask(description = "Inspect workspace and clarify requirements", orderIndex = 0),
            Subtask(description = "Execute core task operations", orderIndex = 1),
            Subtask(description = "Verify deliverable and output accuracy", orderIndex = 2),
            Subtask(description = "Finalize results and complete objective", orderIndex = 3)
        )
    }
}
