package com.example.agent.plan

import java.util.UUID

/**
 * Status of an individual subtask / subgoal within an outcome-driven task plan.
 */
enum class SubtaskStatus(val displayName: String) {
    PENDING("Pending"),
    RUNNING("Running"),
    COMPLETED("Completed"),
    FAILED("Failed")
}

/**
 * Structured record of an approach that failed, preventing repetition loops.
 */
data class FailedApproach(
    val approach: String,
    val reason: String,
    val alternativeSuggestion: String = "",
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Structured record of an approach that succeeded.
 */
data class SuccessfulApproach(
    val approach: String,
    val outcome: String,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * A discrete, outcome-driven sub-goal within a TaskPlan.
 *
 * Rather than merely tracking raw step counters, sub-goals represent
 * verifiable milestones required to achieve the overall outcome.
 */
data class Subtask(
    val id: String = UUID.randomUUID().toString(),
    val description: String,
    val status: SubtaskStatus = SubtaskStatus.PENDING,
    val result: String? = null,
    val orderIndex: Int = 0,
    val retryCount: Int = 0,
    val updatedAt: Long = System.currentTimeMillis(),
    val verificationCriteria: String? = null,
    val verificationResult: String? = null,
    val failureReason: String? = null,
    val actions: List<String> = emptyList()
)

/**
 * Compact machine-readable task state snapshot maintaining continuity across turns.
 * Injected into LLM reasoning cycles to prevent context drift and forgotten objectives.
 */
data class TaskContextSnapshot(
    val objective: String,
    val desiredOutcome: String = "",
    val completionCriteria: List<String> = emptyList(),
    val currentState: String = "Active",
    val knownFacts: List<String> = emptyList(),
    val constraints: List<String> = emptyList(),
    val completedObjectives: List<String> = emptyList(),
    val pendingObjectives: List<String> = emptyList(),
    val failedApproaches: List<FailedApproach> = emptyList(),
    val successfulApproaches: List<SuccessfulApproach> = emptyList(),
    val importantArtifacts: List<String> = emptyList(),
    val currentHypothesis: String = "",
    val currentAction: String = "",
    val expectedResult: String = "",
    val actualResult: String = "",
    val verificationStatus: String = "UNVERIFIED",
    val nextBestAction: String = "",
    val timestamp: Long = System.currentTimeMillis()
) {
    fun toCompactPrompt(): String {
        return buildString {
            appendLine("=== PERSISTENT TASK CONTEXT SNAPSHOT ===")
            appendLine("ORIGINAL OBJECTIVE: $objective")
            if (desiredOutcome.isNotBlank()) {
                appendLine("DESIRED OUTCOME: $desiredOutcome")
            }
            if (completionCriteria.isNotEmpty()) {
                appendLine("COMPLETION CRITERIA:")
                completionCriteria.forEach { appendLine("  - $it") }
            }
            appendLine("CURRENT STATE: $currentState | VERIFICATION: $verificationStatus")
            if (knownFacts.isNotEmpty()) {
                appendLine("KNOWN FACTS:")
                knownFacts.takeLast(6).forEach { appendLine("  • $it") }
            }
            if (constraints.isNotEmpty()) {
                appendLine("CONSTRAINTS: ${constraints.joinToString("; ")}")
            }
            if (completedObjectives.isNotEmpty()) {
                appendLine("COMPLETED MILESTONES:")
                completedObjectives.forEach { appendLine("  ✔ $it") }
            }
            if (pendingObjectives.isNotEmpty()) {
                appendLine("PENDING SUB-GOALS:")
                pendingObjectives.forEach { appendLine("  ⏳ $it") }
            }
            if (failedApproaches.isNotEmpty()) {
                appendLine("FAILED APPROACHES (DO NOT REPEAT):")
                failedApproaches.takeLast(4).forEach { 
                    appendLine("  ✗ ${it.approach}: ${it.reason} -> ${it.alternativeSuggestion}")
                }
            }
            if (importantArtifacts.isNotEmpty()) {
                appendLine("IMPORTANT ARTIFACTS: ${importantArtifacts.takeLast(8).joinToString(", ")}")
            }
            if (nextBestAction.isNotBlank()) {
                appendLine("NEXT BEST ACTION: $nextBestAction")
            }
            appendLine("=========================================")
        }
    }
}

/**
 * Structured task plan containing outcome-oriented sub-goals and context snapshot.
 */
data class TaskPlan(
    val sessionId: String,
    val goal: String,
    val subtasks: List<Subtask> = emptyList(),
    val currentSubtaskId: String? = null,
    val maxSteps: Int = 100,
    val stepCount: Int = 0,
    val isCompleted: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val desiredOutcome: String = "",
    val completionCriteria: List<String> = emptyList(),
    val strategy: String = "",
    val contextSnapshot: TaskContextSnapshot? = null
) {
    val activeSubtask: Subtask?
        get() = currentSubtaskId?.let { id -> subtasks.find { it.id == id } }
            ?: subtasks.firstOrNull { it.status == SubtaskStatus.RUNNING }
            ?: subtasks.firstOrNull { it.status == SubtaskStatus.PENDING }

    val completedCount: Int
        get() = subtasks.count { it.status == SubtaskStatus.COMPLETED }

    val totalCount: Int
        get() = subtasks.size

    val allSubtasksCompleted: Boolean
        get() = subtasks.isNotEmpty() &&
            subtasks.any { it.status == SubtaskStatus.COMPLETED } &&
            subtasks.none { it.status == SubtaskStatus.PENDING || it.status == SubtaskStatus.RUNNING }

    val isStepLimitExceeded: Boolean
        get() = stepCount >= maxSteps

    fun getMeaningfulProgress(): String {
        return if (totalCount > 0) {
            "$completedCount of $totalCount milestones achieved"
        } else {
            "Analyzing objective"
        }
    }
}
