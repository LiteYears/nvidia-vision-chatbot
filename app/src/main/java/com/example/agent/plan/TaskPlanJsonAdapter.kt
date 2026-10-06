package com.example.agent.plan

import org.json.JSONArray
import org.json.JSONObject

/**
 * Handles JSON serialization and deserialization of Subtasks and Task Context for Room persistence.
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
            obj.put("verificationCriteria", subtask.verificationCriteria ?: JSONObject.NULL)
            obj.put("verificationResult", subtask.verificationResult ?: JSONObject.NULL)
            obj.put("failureReason", subtask.failureReason ?: JSONObject.NULL)
            if (subtask.actions.isNotEmpty()) {
                val actArray = JSONArray()
                subtask.actions.forEach { actArray.put(it) }
                obj.put("actions", actArray)
            }
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
                val verificationCriteria = if (obj.isNull("verificationCriteria") || !obj.has("verificationCriteria")) null else obj.getString("verificationCriteria")
                val verificationResult = if (obj.isNull("verificationResult") || !obj.has("verificationResult")) null else obj.getString("verificationResult")
                val failureReason = if (obj.isNull("failureReason") || !obj.has("failureReason")) null else obj.getString("failureReason")
                val actionsList = mutableListOf<String>()
                if (obj.has("actions") && !obj.isNull("actions")) {
                    val actArray = obj.getJSONArray("actions")
                    for (a in 0 until actArray.length()) {
                        actionsList.add(actArray.getString(a))
                    }
                }

                list.add(
                    Subtask(
                        id = id.ifBlank { "subtask_$i" },
                        description = description,
                        status = status,
                        result = result,
                        orderIndex = orderIndex,
                        retryCount = retryCount,
                        updatedAt = updatedAt,
                        verificationCriteria = verificationCriteria,
                        verificationResult = verificationResult,
                        failureReason = failureReason,
                        actions = actionsList
                    )
                )
            }
        } catch (_: Exception) {
            // Graceful fallback on corrupt/invalid json
        }
        return list.sortedBy { it.orderIndex }
    }
}
