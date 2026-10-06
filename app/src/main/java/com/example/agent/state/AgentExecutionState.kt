package com.example.agent.state

import com.example.data.model.AgentTaskStatus

/**
 * Explicit Agent State Machine states as defined by the Autonomous Task-Execution Engine architecture.
 *
 * Every transition must be accompanied by an observable reason:
 * - CREATED: Task initialized with goal
 * - PLANNING: Analyzing objective and formulating subtasks
 * - READY_TO_ACT: Valid tool call chosen and validated
 * - EXECUTING: Tool running in PRoot/Ubuntu workspace
 * - OBSERVING: Tool execution returned; capturing stdout/stderr/artifacts
 * - VERIFYING: Deterministic check of file/output validity
 * - REPLANNING: Adapting strategy after failure or loop detection
 * - WAITING_FOR_USER: Awaiting user input or sensitive permission
 * - COMPLETED: Objective deterministically verified and achieved
 * - FAILED: Terminal failure after exhausting reasonable recovery strategies
 * - BLOCKED: Environment or dependency preventing execution
 * - CANCELLED: Explicitly stopped/paused by user
 */
enum class AgentExecutionState(val displayName: String) {
    CREATED("Created"),
    PLANNING("Planning"),
    READY_TO_ACT("Ready to Act"),
    EXECUTING("Executing"),
    OBSERVING("Observing"),
    VERIFYING("Verifying"),
    REPLANNING("Replanning"),
    WAITING_FOR_USER("Waiting for User"),
    COMPLETED("Completed"),
    FAILED("Failed"),
    BLOCKED("Blocked"),
    CANCELLED("Cancelled");

    fun toAgentTaskStatus(): AgentTaskStatus = when (this) {
        CREATED -> AgentTaskStatus.INITIALIZING
        PLANNING -> AgentTaskStatus.THINKING
        READY_TO_ACT -> AgentTaskStatus.THINKING
        EXECUTING -> AgentTaskStatus.USING_TOOL
        OBSERVING -> AgentTaskStatus.OBSERVING
        VERIFYING -> AgentTaskStatus.OBSERVING
        REPLANNING -> AgentTaskStatus.THINKING
        WAITING_FOR_USER -> AgentTaskStatus.PAUSED
        COMPLETED -> AgentTaskStatus.COMPLETED
        FAILED -> AgentTaskStatus.FAILED
        BLOCKED -> AgentTaskStatus.FAILED
        CANCELLED -> AgentTaskStatus.PAUSED
    }

    companion object {
        fun fromAgentTaskStatus(status: AgentTaskStatus): AgentExecutionState = when (status) {
            AgentTaskStatus.INITIALIZING, AgentTaskStatus.CREATED -> CREATED
            AgentTaskStatus.THINKING, AgentTaskStatus.PLANNING -> PLANNING
            AgentTaskStatus.READY_TO_ACT -> READY_TO_ACT
            AgentTaskStatus.USING_TOOL, AgentTaskStatus.EXECUTING, AgentTaskStatus.IN_PROGRESS -> EXECUTING
            AgentTaskStatus.OBSERVING -> OBSERVING
            AgentTaskStatus.VERIFYING -> VERIFYING
            AgentTaskStatus.REPLANNING -> REPLANNING
            AgentTaskStatus.WAITING_FOR_USER -> WAITING_FOR_USER
            AgentTaskStatus.COMPLETED -> COMPLETED
            AgentTaskStatus.PAUSED, AgentTaskStatus.CANCELLED -> CANCELLED
            AgentTaskStatus.BLOCKED -> BLOCKED
            AgentTaskStatus.FAILED -> FAILED
        }
    }
}

/**
 * Record of a state machine transition with explicit deterministic reason.
 */
data class StateTransitionRecord(
    val fromState: AgentExecutionState,
    val toState: AgentExecutionState,
    val reason: String,
    val timestamp: Long = System.currentTimeMillis()
)
