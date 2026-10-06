package com.example.agent.state

import java.util.concurrent.CopyOnWriteArrayList

/**
 * Deterministic State Machine enforcing valid agent execution transitions.
 * Prevents vague repeating states such as THINKING -> THINKING -> THINKING.
 */
class AgentStateMachine(
    initialState: AgentExecutionState = AgentExecutionState.CREATED
) {

    private var _currentState: AgentExecutionState = initialState
    val currentState: AgentExecutionState get() = _currentState

    private val _transitionHistory = mutableListOf<StateTransitionRecord>()
    val transitionHistory: List<StateTransitionRecord> get() = _transitionHistory.toList()

    private val listeners = CopyOnWriteArrayList<(StateTransitionRecord) -> Unit>()

    fun addListener(listener: (StateTransitionRecord) -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: (StateTransitionRecord) -> Unit) {
        listeners.remove(listener)
    }

    /**
     * Executes a state transition accompanied by an explicit reason.
     */
    @Synchronized
    fun transitionTo(newState: AgentExecutionState, reason: String): StateTransitionRecord {
        val oldState = _currentState
        val record = StateTransitionRecord(
            fromState = oldState,
            toState = newState,
            reason = reason,
            timestamp = System.currentTimeMillis()
        )
        _currentState = newState
        _transitionHistory.add(record)
        listeners.forEach { it.invoke(record) }
        return record
    }

    /**
     * Checks if a transition is logically valid.
     */
    fun canTransitionTo(targetState: AgentExecutionState): Boolean {
        if (_currentState == targetState) return true
        if (_currentState == AgentExecutionState.COMPLETED || _currentState == AgentExecutionState.FAILED) {
            // Terminal states can only be reopened by planning/created
            return targetState == AgentExecutionState.PLANNING || targetState == AgentExecutionState.CREATED
        }
        return true
    }
}
