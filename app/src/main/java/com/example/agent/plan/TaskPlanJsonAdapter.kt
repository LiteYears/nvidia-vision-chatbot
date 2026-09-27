package com.example.agent.plan

import org.json.JSONArray
import org.json.JSONObject

/**
 * Handles JSON serialization and deserialization of Subtasks for Room persistence.
 */
object TaskPlanJsonAdapter {

    fun serializeSubtasks(subtasks: List<Subtask>): String {
        val array = JSONArray()
        for (subtask in subtasks) {
            val obj = JSONObject()
            obj.put("id", subtask.id)
            obj.put("description", subtask.description)
            obj.put("status", subtask.status.name)
            obj.put("result", subtask.result ?: JSONObject.NULL)
            obj.put("orderIndex", subtask.orderIndex)
            obj.put("retryCount", subtask.retryCount)
            obj.put("updatedAt", subtask.updatedAt)
            array.put(obj)
        }
        return array.toString()
    }

    fun deserializeSubtasks(jsonString: String?): List<Subtask> {
        if (jsonString.isNullOrBlank()) return emptyList()
        val list = mutableListOf<Subtask>()
        try {
            val array = JSONArray(jsonString)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val id = obj.optString("id")
                val description = obj.optString("description")
                val statusStr = obj.optString("status", SubtaskStatus.PENDING.name)
                val status = try {
                    SubtaskStatus.valueOf(statusStr)
                } catch (e: Exception) {
                    SubtaskStatus.PENDING
                }
                val result = if (obj.isNull("result") || !obj.has("result")) null else obj.getString("result")
                val orderIndex = obj.optInt("orderIndex", i)
                val retryCount = obj.optInt("retryCount", 0)
                val updatedAt = obj.optLong("updatedAt", System.currentTimeMillis())

                list.add(
                    Subtask(
                        id = id.ifBlank { "subtask_$i" },
                        description = description,
                        status = status,
                        result = result,
                        orderIndex = orderIndex,
                        retryCount = retryCount,
                        updatedAt = updatedAt
                    )
                )
            }
        } catch (_: Exception) {
            // Graceful fallback on corrupt/invalid json
        }
        return list.sortedBy { it.orderIndex }
    }
}
