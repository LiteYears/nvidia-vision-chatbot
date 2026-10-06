package com.example.agent.verification

import org.json.JSONArray
import org.json.JSONObject

/**
 * Deterministic result of verifying an artifact, command, or task objective.
 */
data class VerificationResult(
    val target: String,
    val isPassed: Boolean,
    val details: String,
    val timestamp: Long = System.currentTimeMillis(),
    val criteriaChecked: List<String> = emptyList()
) {
    fun toJson(): JSONObject {
        val obj = JSONObject()
        obj.put("target", target)
        obj.put("isPassed", isPassed)
        obj.put("details", details)
        obj.put("timestamp", timestamp)
        val arr = JSONArray()
        criteriaChecked.forEach { arr.put(it) }
        obj.put("criteriaChecked", arr)
        return obj
    }

    companion object {
        fun fromJson(obj: JSONObject): VerificationResult {
            val criteria = mutableListOf<String>()
            obj.optJSONArray("criteriaChecked")?.let { arr ->
                for (i in 0 until arr.length()) criteria.add(arr.getString(i))
            }
            return VerificationResult(
                target = obj.optString("target", ""),
                isPassed = obj.optBoolean("isPassed", false),
                details = obj.optString("details", ""),
                timestamp = obj.optLong("timestamp", System.currentTimeMillis()),
                criteriaChecked = criteria
            )
        }
    }
}
