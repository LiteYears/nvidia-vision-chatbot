package com.example.agent.trace

import org.json.JSONObject

/**
 * Machine-readable execution trace event for transparent observability and debugging.
 */
data class AgentTraceEvent(
    val taskId: String,
    val timestamp: Long = System.currentTimeMillis(),
    val iteration: Int = 0,
    val eventType: String,
    val payload: Map<String, Any?> = emptyMap()
) {
    fun toJson(): JSONObject {
        val obj = JSONObject()
        obj.put("taskId", taskId)
        obj.put("timestamp", timestamp)
        obj.put("iteration", iteration)
        obj.put("eventType", eventType)
        val payloadObj = JSONObject()
        payload.forEach { (k, v) -> payloadObj.put(k, v ?: JSONObject.NULL) }
        obj.put("payload", payloadObj)
        return obj
    }
}
