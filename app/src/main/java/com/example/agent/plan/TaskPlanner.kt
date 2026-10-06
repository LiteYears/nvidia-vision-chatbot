package com.example.agent.plan

import java.util.UUID

/**
 * Core engine for structured, outcome-driven task planning, context maintenance,
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
     * Initializes an outcome-oriented TaskPlan with desired outcome, completion criteria,
     * and a persistent TaskContextSnapshot.
     */
    fun createInitialPlan(
        sessionId: String,
        goal: String,
        maxSteps: Int = DEFAULT_MAX_STEPS
    ): TaskPlan {
        val trimmedGoal = goal.trim()
        val decomposedSubtasks = decomposeGoalIntoSubtasks(trimmedGoal)
        val desiredOutcome = inferDesiredOutcome(trimmedGoal)
        val completionCriteria = inferCompletionCriteria(trimmedGoal)
        val strategy = inferStrategy(trimmedGoal)

        val contextSnapshot = TaskContextSnapshot(
            objective = trimmedGoal,
            desiredOutcome = desiredOutcome,
            completionCriteria = completionCriteria,
            currentState = "Initialized",
            knownFacts = listOf("Environment: Linux / Android workspace", "Session ID: $sessionId"),
            constraints = listOf("Require verification before conclusion", "Never repeat failed approaches"),
            completedObjectives = emptyList(),
            pendingObjectives = decomposedSubtasks.map { it.description },
            failedApproaches = emptyList(),
            successfulApproaches = emptyList(),
            importantArtifacts = emptyList(),
            currentHypothesis = "Strategy: $strategy",
            currentAction = decomposedSubtasks.firstOrNull()?.description ?: "",
            expectedResult = "Achieve initial sub-goal",
            actualResult = "",
            verificationStatus = "UNVERIFIED",
            nextBestAction = decomposedSubtasks.firstOrNull()?.description ?: ""
        )

        val plan = TaskPlan(
            sessionId = sessionId,
            goal = trimmedGoal,
            subtasks = decomposedSubtasks,
            currentSubtaskId = decomposedSubtasks.firstOrNull()?.id,
            maxSteps = maxSteps,
            stepCount = 0,
            isCompleted = false,
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis(),
            desiredOutcome = desiredOutcome,
            completionCriteria = completionCriteria,
            strategy = strategy,
            contextSnapshot = contextSnapshot
        )

        // Mark the first subtask as RUNNING to initialize execution
        return if (decomposedSubtasks.isNotEmpty()) {
            startSubtask(plan, decomposedSubtasks.first().id)
        } else {
            plan
        }
    }

    private fun inferDesiredOutcome(goal: String): String {
        val lower = goal.lowercase()
        return when {
            lower.contains("three") && lower.contains("framework") ->
                "Synthesize an accurate comparative analysis of 3 Android LLM frameworks with verified pros/cons and comprehensive report"
            lower.contains("fix") || lower.contains("repair") || lower.contains("bug") ->
                "Locate root cause, repair code with minimal targeted diff, test execution output, and verify zero errors"
            lower.contains("settings") || lower.contains("screen") ->
                "Complete end-to-end integration across architecture, persistence, and UI with verified state handling"
            lower.contains("python") || lower.contains("script") ->
                "Produce runnable, syntactically correct Python implementation verified by direct execution output"
            lower.contains("research") || lower.contains("search") || lower.contains("whois") ->
                "Gather verified factual data from authoritative sources with citations and synthesize cohesive result"
            else ->
                "Accomplish user goal with verified deliverables and zero unresolved errors"
        }
    }

    private fun inferCompletionCriteria(goal: String): List<String> {
        val lower = goal.lowercase()
        return when {
            lower.contains("three") && lower.contains("framework") -> listOf(
                "Identify exactly three distinct frameworks",
                "Extract concrete architecture and capability details for each",
                "Produce structured comparison table or metrics",
                "Verify comprehensive final report"
            )
            lower.contains("fix") || lower.contains("repair") || lower.contains("bug") -> listOf(
                "Identify specific syntax or logic flaw in source",
                "Apply clean patch/write without unintended regressions",
                "Execute code and observe clean 0 exit code",
                "Verify expected output matches specification"
            )
            lower.contains("settings") || lower.contains("screen") -> listOf(
                "Inspect existing preferences and architecture",
                "Implement setting state and UI toggle",
                "Verify setting persistence survives app reload",
                "Ensure clean compilation and test verification"
            )
            lower.contains("python") || lower.contains("script") -> listOf(
                "Script written to disk",
                "Executed with Python runtime with successful exit code",
                "Output verified for numerical/logical correctness"
            )
            else -> listOf(
                "All planned sub-goals verified",
                "Deliverables validated against user requirements",
                "Final output clearly communicated"
            )
        }
    }

    private fun inferStrategy(goal: String): String {
        val lower = goal.lowercase()
        return when {
            lower.contains("fix") || lower.contains("repair") ->
                "Inspect first -> Identify error -> Patch -> Execute test -> Verify output"
            lower.contains("multi-file") || lower.contains("settings") || lower.contains("feature") ->
                "Architecture exploration -> Minimal implementation -> Compile & Test -> Review & Verify"
            lower.contains("research") || lower.contains("search") ->
                "Targeted query -> Authoritative web open -> Cross-reference facts -> Synthesize citations"
            else ->
                "Understand objective -> Execute high-value actions -> Observe -> Verify outcome"
        }
    }

    /**
     * Parses the agent's LLM output to detect if the agent created or updated its own plan.
     */
    fun parsePlanFromAgentOutput(agentOutput: String, currentPlan: TaskPlan): TaskPlan? {
        if (agentOutput.isBlank()) return null

        val blockMatch = PLAN_CODE_BLOCK_REGEX.find(agentOutput)
        val candidateContent = if (blockMatch != null) {
            blockMatch.groupValues[1].trim()
        } else {
            val headerMatch = PLAN_HEADER_REGEX.find(agentOutput)
            headerMatch?.groupValues?.get(1)?.trim()
        }

        if (candidateContent.isNullOrBlank()) {
            if (agentOutput.contains("☐") || agentOutput.contains("☑") || agentOutput.contains("- [ ]") || agentOutput.contains("- [x]")) {
                return parseChecklistLines(agentOutput, currentPlan)
            }
            return null
        }

        return parseChecklistLines(candidateContent, currentPlan)
    }

    private fun parseChecklistLines(content: String, currentPlan: TaskPlan): TaskPlan? {
        val lines = content.lines()
        val parsedSubtasks = mutableListOf<Subtask>()
        var index = 0

        for (rawLine in lines) {
            val line = rawLine.trim()
            if (line.isBlank() || line.startsWith("#") || line.startsWith("//")) continue

            val status: SubtaskStatus?
            val description: String

            val normalizedLine = line.replaceFirst(Regex("""^\d+[\.\)]\s*"""), "").trim()

            when {
                normalizedLine.startsWith("☑") || normalizedLine.startsWith("✔") || normalizedLine.startsWith("[x]", ignoreCase = true) || normalizedLine.startsWith("- [x]", ignoreCase = true) || normalizedLine.startsWith("* [x]", ignoreCase = true) -> {
                    status = SubtaskStatus.COMPLETED
                    description = normalizedLine.replaceFirst(Regex("""^(?:☑|✔|[-*]?\s*\[x\])\s*""", RegexOption.IGNORE_CASE), "").trim()
                }
                normalizedLine.startsWith("⏳") || normalizedLine.startsWith("[/]", ignoreCase = true) || normalizedLine.startsWith("- [/]", ignoreCase = true) || normalizedLine.startsWith("[>]", ignoreCase = true) || normalizedLine.startsWith("- [>]", ignoreCase = true) -> {
                    status = SubtaskStatus.RUNNING
                    description = normalizedLine.replaceFirst(Regex("""^(?:⏳|[-*]?\s*\[[/>]\])\s*""", RegexOption.IGNORE_CASE), "").trim()
                }
                normalizedLine.startsWith("☒") || normalizedLine.startsWith("[!]", ignoreCase = true) || normalizedLine.startsWith("- [!]", ignoreCase = true) || normalizedLine.startsWith("❌") -> {
                    status = SubtaskStatus.FAILED
                    description = normalizedLine.replaceFirst(Regex("""^(?:☒|❌|[-*]?\s*\[!\])\s*""", RegexOption.IGNORE_CASE), "").trim()
                }
                normalizedLine.startsWith("☐") || normalizedLine.startsWith("[ ]") || normalizedLine.startsWith("- [ ]") || normalizedLine.startsWith("* [ ]") -> {
                    status = SubtaskStatus.PENDING
                    description = normalizedLine.replaceFirst(Regex("""^(?:☐|[-*]?\s*\[\s*\])\s*"""), "").trim()
                }
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

    fun startSubtask(plan: TaskPlan, subtaskId: String): TaskPlan {
        val updatedSubtasks = plan.subtasks.map { subtask ->
            if (subtask.id == subtaskId) {
                subtask.copy(status = SubtaskStatus.RUNNING, updatedAt = System.currentTimeMillis())
            } else if (subtask.status == SubtaskStatus.RUNNING && subtask.id != subtaskId) {
                subtask.copy(status = SubtaskStatus.PENDING)
            } else {
                subtask
            }
        }

        val active = updatedSubtasks.find { it.id == subtaskId }
        val updatedSnapshot = plan.contextSnapshot?.copy(
            currentAction = active?.description ?: "",
            nextBestAction = active?.description ?: "",
            currentState = "Executing: ${active?.description?.take(40)}",
            timestamp = System.currentTimeMillis()
        )

        return plan.copy(
            subtasks = updatedSubtasks,
            currentSubtaskId = subtaskId,
            contextSnapshot = updatedSnapshot,
            updatedAt = System.currentTimeMillis()
        )
    }

    fun startNextSubtask(plan: TaskPlan): TaskPlan {
        val nextSubtask = plan.subtasks.firstOrNull { it.status == SubtaskStatus.PENDING }
        return if (nextSubtask != null) {
            startSubtask(plan, nextSubtask.id)
        } else {
            plan
        }
    }

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

        val updatedSnapshot = plan.contextSnapshot?.copy(
            actualResult = snippet,
            successfulApproaches = plan.contextSnapshot.successfulApproaches + SuccessfulApproach(
                approach = "Tool $toolName",
                outcome = snippet.take(80)
            ),
            timestamp = System.currentTimeMillis()
        )

        return plan.copy(
            subtasks = updatedSubtasks,
            contextSnapshot = updatedSnapshot,
            updatedAt = System.currentTimeMillis()
        )
    }

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
                    failureReason = snippet,
                    retryCount = subtask.retryCount + 1,
                    updatedAt = System.currentTimeMillis()
                )
            } else {
                subtask
            }
        }

        val updatedSnapshot = plan.contextSnapshot?.copy(
            failedApproaches = plan.contextSnapshot.failedApproaches + FailedApproach(
                approach = "Tool '$toolName'",
                reason = snippet.take(120),
                alternativeSuggestion = "Diagnose stderr, apply script or command repair, and re-execute",
                timestamp = System.currentTimeMillis()
            ),
            verificationStatus = "FAILED_ATTEMPT_NEEDS_REPAIR",
            currentState = "Encountered failure with $toolName: analyzing stderr and repairing",
            nextBestAction = "Diagnose stderr from previous command and apply fix",
            timestamp = System.currentTimeMillis()
        )

        return plan.copy(
            subtasks = updatedSubtasks,
            contextSnapshot = updatedSnapshot,
            updatedAt = System.currentTimeMillis()
        )
    }

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
                        failureReason = failureDiagnostic,
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

        val updatedSnapshot = plan.contextSnapshot?.copy(
            currentState = "Plan adapted for recovery",
            nextBestAction = recoverySubtask.description,
            timestamp = System.currentTimeMillis()
        )

        return plan.copy(
            subtasks = reindexed,
            currentSubtaskId = recoverySubtask.id,
            contextSnapshot = updatedSnapshot,
            updatedAt = System.currentTimeMillis()
        )
    }

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

    fun verifyAndCompleteSubtask(
        plan: TaskPlan,
        subtaskId: String?,
        verificationNotes: String? = null
    ): TaskPlan {
        val targetId = subtaskId ?: plan.currentSubtaskId ?: return plan
        var completedDesc = ""

        val updatedSubtasks = plan.subtasks.map { subtask ->
            if (subtask.id == targetId) {
                completedDesc = subtask.description
                val newResult = if (!verificationNotes.isNullOrBlank()) {
                    verificationNotes
                } else {
                    subtask.result ?: "Verified objective achieved"
                }
                subtask.copy(
                    status = SubtaskStatus.COMPLETED,
                    result = newResult,
                    verificationResult = newResult,
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

        val updatedSnapshot = plan.contextSnapshot?.let { snap ->
            val updatedCompleted = if (completedDesc.isNotBlank() && !snap.completedObjectives.contains(completedDesc)) {
                snap.completedObjectives + completedDesc
            } else snap.completedObjectives

            val updatedPending = snap.pendingObjectives.filterNot { it == completedDesc }

            snap.copy(
                completedObjectives = updatedCompleted,
                pendingObjectives = updatedPending,
                currentState = if (allDone) "Completed" else "In Progress",
                verificationStatus = if (allDone) "VERIFIED_COMPLETE" else "IN_PROGRESS",
                nextBestAction = nextPending?.description ?: if (allDone) "Deliver final outcome" else "",
                timestamp = System.currentTimeMillis()
            )
        }

        return plan.copy(
            subtasks = updatedSubtasks,
            currentSubtaskId = nextPending?.id,
            isCompleted = allDone,
            contextSnapshot = updatedSnapshot,
            updatedAt = System.currentTimeMillis()
        )
    }

    fun incrementStep(plan: TaskPlan): TaskPlan {
        return plan.copy(
            stepCount = plan.stepCount + 1,
            updatedAt = System.currentTimeMillis()
        )
    }

    fun checkAndCompleteOverallPlan(plan: TaskPlan): TaskPlan {
        val allDone = plan.subtasks.isNotEmpty() && plan.subtasks.all { it.status == SubtaskStatus.COMPLETED }
        return if (allDone && !plan.isCompleted) {
            val updatedSnapshot = plan.contextSnapshot?.copy(
                currentState = "Completed",
                verificationStatus = "VERIFIED_COMPLETE",
                timestamp = System.currentTimeMillis()
            )
            plan.copy(isCompleted = true, contextSnapshot = updatedSnapshot, updatedAt = System.currentTimeMillis())
        } else {
            plan
        }
    }

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

        val executedCmd = if (toolLower in setOf("python_execute", "run_command", "bash", "terminal", "sh", "cmd", "exec")) {
            toolResult.lines().find { it.startsWith("Command:") }?.removePrefix("Command:")?.trim()?.lowercase() ?: ""
        } else ""

        val isSetupOrAuxiliaryCommand = executedCmd.startsWith("apt") ||
            executedCmd.startsWith("pip") ||
            executedCmd.startsWith("ls") ||
            executedCmd.startsWith("dir") ||
            executedCmd.startsWith("pwd") ||
            executedCmd.startsWith("cd") ||
            executedCmd.startsWith("mkdir") ||
            executedCmd.startsWith("which") ||
            executedCmd.startsWith("whoami")

        val isBashFileWrite = executedCmd.contains("cat <<") ||
            executedCmd.contains("cat >") ||
            executedCmd.contains("sed -i") ||
            executedCmd.contains("echo ") && executedCmd.contains(">")

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
                if (isBashFileWrite) {
                    descLower.contains("write") || descLower.contains("create") ||
                        descLower.contains("modify") || descLower.contains("patch") ||
                        descLower.contains("fix") || descLower.contains("repair") ||
                        descLower.contains("implement") || descLower.contains("code") ||
                        descLower.contains("script")
                } else if (isSetupOrAuxiliaryCommand) {
                    descLower.contains("inspect") || descLower.contains("setup") ||
                        descLower.contains("install") || descLower.contains("environment") ||
                        descLower.contains("locate") || descLower.contains("clarify") ||
                        descLower.contains("requirements") || descLower.contains("directories") ||
                        descLower.contains("directory") || descLower.contains("package")
                } else {
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
                        descLower.contains("fix") || descLower.contains("repair") ||
                        descLower.contains("service") || descLower.contains("api")
                }
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

        if (toolLower == "file_write" || toolLower == "file_patch" || isBashFileWrite) {
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

            val failedExecution = updatedPlan.subtasks.find {
                it.status == SubtaskStatus.FAILED &&
                    (it.id == active.id || it.orderIndex >= active.orderIndex) &&
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

        if (isFulfillable) {
            val completionNotes = "Verified: Tool '$toolName' executed successfully"
            updatedPlan = verifyAndCompleteSubtask(updatedPlan, active.id, completionNotes)

            val nextPending = updatedPlan.subtasks.firstOrNull { it.status == SubtaskStatus.PENDING }
            if (nextPending != null && updatedPlan.subtasks.none { it.status == SubtaskStatus.RUNNING }) {
                updatedPlan = startSubtask(updatedPlan, nextPending.id)
            }
        }

        return updatedPlan
    }

    fun hasPendingActionSubtasks(plan: TaskPlan): Boolean {
        return plan.subtasks.any { subtask ->
            (subtask.status == SubtaskStatus.PENDING || subtask.status == SubtaskStatus.RUNNING || subtask.status == SubtaskStatus.FAILED) &&
                !isSynthesisOrFinalSubtask(subtask.description)
        }
    }

    fun isSynthesisOrFinalSubtask(description: String): Boolean {
        val d = description.lowercase()
        return d.contains("synthesize") || d.contains("final deliverable") ||
            d.contains("finalize") || d.contains("complete objective") ||
            d.contains("create report") || d.contains("deliverable") ||
            (d.contains("verify") && (d.contains("report") || d.contains("deliverable") || d.contains("criteria") || d.contains("output") || d.contains("accuracy") || d.contains("result"))) ||
            (d.contains("inspect") && (d.contains("output") || d.contains("execution") || d.contains("result"))) ||
            d == "complete"
    }

    fun formatPlanForPrompt(plan: TaskPlan): String {
        return buildString {
            // 1. Output compact Task Context Snapshot if present
            plan.contextSnapshot?.let {
                appendLine(it.toCompactPrompt())
                appendLine()
            }

            appendLine("=== STRUCTURED OUTCOME-ORIENTED PLAN ===")
            appendLine("GOAL: \"${plan.goal}\"")
            if (plan.desiredOutcome.isNotBlank()) {
                appendLine("DESIRED OUTCOME: \"${plan.desiredOutcome}\"")
            }
            appendLine("OVERALL STATUS: ${if (plan.isCompleted) "COMPLETED" else "IN PROGRESS"}")
            appendLine("PROGRESS: ${plan.getMeaningfulProgress()}")
            appendLine()
            appendLine("VERIFIABLE SUB-GOALS:")
            plan.subtasks.forEachIndexed { idx, subtask ->
                val marker = when (subtask.status) {
                    SubtaskStatus.COMPLETED -> "[x]"
                    SubtaskStatus.RUNNING -> "[/]"
                    SubtaskStatus.FAILED -> "[!]"
                    SubtaskStatus.PENDING -> "[ ]"
                }
                val activeTag = if (subtask.id == plan.currentSubtaskId) " <-- CURRENT ACTIVE MILESTONE" else ""
                appendLine("${idx + 1}. $marker ${subtask.description} (${subtask.status.name})$activeTag")
                if (!subtask.result.isNullOrBlank()) {
                    appendLine("   Evidence: ${subtask.result}")
                }
            }
            appendLine()
            appendLine("EXECUTION PRINCIPLES:")
            appendLine("- Plan for OUTCOME, not mere step counters. Every action must advance closer to the verified result.")
            appendLine("- Verification first: Never mark an objective done without observing test/execution proof.")
            appendLine("- Anti-loop: Never repeat an approach that produced an error without changing strategy.")
            appendLine("=========================================")
        }
    }

    private fun decomposeGoalIntoSubtasks(goal: String): List<Subtask> {
        val lower = goal.lowercase()

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

        if (lower.contains("python") || lower.contains("script") || lower.contains("code") || lower.contains("write a program")) {
            return listOf(
                Subtask(description = "Analyze requirements and prepare solution", orderIndex = 0),
                Subtask(description = "Write script to workspace using file_write", orderIndex = 1),
                Subtask(description = "Execute script using python_execute", orderIndex = 2),
                Subtask(description = "Inspect and verify script execution output", orderIndex = 3),
                Subtask(description = "Synthesize final deliverable", orderIndex = 4)
            )
        }

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

        return listOf(
            Subtask(description = "Inspect workspace and clarify requirements", orderIndex = 0),
            Subtask(description = "Execute core task operations", orderIndex = 1),
            Subtask(description = "Verify deliverable and output accuracy", orderIndex = 2),
            Subtask(description = "Finalize results and complete objective", orderIndex = 3)
        )
    }
}
