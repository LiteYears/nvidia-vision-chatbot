package com.example.agent.trace

import android.util.Log
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Global observability logger recording structured runtime events for any task.
 */
object AgentTraceLogger {

    private const val TAG = "AgentTraceLogger"
    private val eventQueue = ConcurrentLinkedQueue<AgentTraceEvent>()

    fun record(event: AgentTraceEvent) {
        eventQueue.add(event)
        // Keep queue bounded
        while (eventQueue.size > 2000) {
            eventQueue.poll()
        }
        try {
            Log.d(TAG, "[${event.eventType}] Task=${event.taskId} Iteration=${event.iteration}: ${event.payload}")
        } catch (_: Exception) {}
    }

    fun record(
        taskId: String,
        eventType: String,
        iteration: Int = 0,
        payload: Map<String, Any?> = emptyMap()
    ) {
        record(
            AgentTraceEvent(
                taskId = taskId,
                iteration = iteration,
                eventType = eventType,
                payload = payload
            )
        )
    }

    fun getEventsForTask(taskId: String): List<AgentTraceEvent> {
        return eventQueue.filter { it.taskId == taskId }
    }

    fun clearEventsForTask(taskId: String) {
        eventQueue.removeIf { it.taskId == taskId }
    }
}
