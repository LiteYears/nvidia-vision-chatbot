package com.example.agent.plan

import java.util.UUID

/**
 * Status of an individual subtask within a structured task plan.
 */
enum class SubtaskStatus(val displayName: String) {
    PENDING("Pending"),
    RUNNING("Running"),
    COMPLETED("Completed"),
    FAILED("Failed")
}

/**
 * A discrete, ordered subtask within a TaskPlan.
 *
 * @property id Unique identifier for the subtask.
 * @property description Human-readable description of what this subtask achieves.
 * @property status Current lifecycle state: PENDING, RUNNING, COMPLETED, or FAILED.
 * @property result Optional result or diagnostic feedback from executing tools for this subtask.
 * @property orderIndex Ordinal position of this subtask within the plan.
 * @property retryCount Number of times this subtask was retried following a failure.
 * @property updatedAt Timestamp of the last state transition or result update.
 */
data class Subtask(
    val id: String = UUID.randomUUID().toString(),
    val description: String,
    val status: SubtaskStatus = SubtaskStatus.PENDING,
    val result: String? = null,
    val orderIndex: Int = 0,
    val retryCount: Int = 0,
    val updatedAt: Long = System.currentTimeMillis()
)

/**
 * Structured task plan containing ordered subtasks for an agent session.
 *
 * @property sessionId ID of the associated AgentSession.
 * @property goal High-level user objective.
 * @property subtasks Ordered sequence of subtasks needed to achieve the goal.
 * @property currentSubtaskId ID of the subtask currently active/being worked on.
 * @property maxSteps Runaway execution guard: maximum tool/plan steps permitted.
 * @property stepCount Total steps executed so far.
 * @property isCompleted True when all subtasks have been verified and the overall task is finished.
 * @property createdAt Timestamp when plan was created.
 * @property updatedAt Timestamp of last plan update.
 */
data class TaskPlan(
    val sessionId: String,
    val goal: String,
    val subtasks: List<Subtask> = emptyList(),
    val currentSubtaskId: String? = null,
    val maxSteps: Int = 15,
    val stepCount: Int = 0,
    val isCompleted: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
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
        get() = subtasks.isNotEmpty() && subtasks.all { it.status == SubtaskStatus.COMPLETED }

    val isStepLimitExceeded: Boolean
        get() = stepCount >= maxSteps
}
