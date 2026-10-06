package com.example.agent.state

import org.json.JSONArray
import org.json.JSONObject

/**
 * Explicit, structured task objective defining requirements, constraints,
 * completion criteria, and expected deliverables.
 */
data class TaskObjective(
    val description: String,
    val desiredOutcome: String,
    val constraints: List<String> = emptyList(),
    val completionCriteria: List<String> = emptyList(),
    val requiredArtifacts: List<String> = emptyList()
) {
    fun toJson(): String {
        val obj = JSONObject()
        obj.put("description", description)
        obj.put("desiredOutcome", desiredOutcome)

        val constraintsArr = JSONArray()
        constraints.forEach { constraintsArr.put(it) }
        obj.put("constraints", constraintsArr)

        val criteriaArr = JSONArray()
        completionCriteria.forEach { criteriaArr.put(it) }
        obj.put("completionCriteria", criteriaArr)

        val artifactsArr = JSONArray()
        requiredArtifacts.forEach { artifactsArr.put(it) }
        obj.put("requiredArtifacts", artifactsArr)

        return obj.toString()
    }

    companion object {
        fun fromJson(jsonStr: String): TaskObjective? {
            if (jsonStr.isBlank()) return null
            return try {
                val obj = JSONObject(jsonStr)
                val description = obj.optString("description", "")
                val desiredOutcome = obj.optString("desiredOutcome", "")

                val constraints = mutableListOf<String>()
                obj.optJSONArray("constraints")?.let { arr ->
                    for (i in 0 until arr.length()) constraints.add(arr.getString(i))
                }

                val completionCriteria = mutableListOf<String>()
                obj.optJSONArray("completionCriteria")?.let { arr ->
                    for (i in 0 until arr.length()) completionCriteria.add(arr.getString(i))
                }

                val requiredArtifacts = mutableListOf<String>()
                obj.optJSONArray("requiredArtifacts")?.let { arr ->
                    for (i in 0 until arr.length()) requiredArtifacts.add(arr.getString(i))
                }

                TaskObjective(
                    description = description,
                    desiredOutcome = desiredOutcome,
                    constraints = constraints,
                    completionCriteria = completionCriteria,
                    requiredArtifacts = requiredArtifacts
                )
            } catch (_: Exception) {
                null
            }
        }

        /**
         * Infers an explicit TaskObjective from the user's natural language goal.
         */
        fun fromUserGoal(goal: String): TaskObjective {
            val trimmed = goal.trim()
            val lower = trimmed.lowercase()

            val constraints = mutableListOf<String>()
            val criteria = mutableListOf<String>()
            val artifacts = mutableListOf<String>()

            // Detect DOCX / Word file objectives
            if (lower.contains("word") || lower.contains(".docx") || lower.contains("docx")) {
                val filename = when {
                    lower.contains("administrative") -> "administrative_document.docx"
                    lower.contains("report") -> "report.docx"
                    lower.contains("design") -> "designed_document.docx"
                    else -> "document.docx"
                }
                artifacts.add(filename)
                criteria.add("A valid Microsoft Word (.docx) document exists on disk")
                criteria.add(".docx file is a valid OpenXML ZIP package containing word/document.xml and [Content_Types].xml")
                criteria.add("Document body contains required text/mock content and styling")
                constraints.add("Binary .docx format must NOT be fabricated with plain text writing tools")
                constraints.add("Use Python python-docx or standard packaging utilities to construct the document")
            }

            // Detect PDF objectives
            if (lower.contains("pdf") || lower.contains(".pdf")) {
                artifacts.add("document.pdf")
                criteria.add("A valid PDF document exists with valid header (%PDF-)")
            }

            // Detect APK objectives
            if (lower.contains("apk") || lower.contains(".apk")) {
                artifacts.add("app-debug.apk")
                criteria.add("A valid Android APK package exists containing classes.dex or AndroidManifest.xml")
            }

            // Detect mock data / administrative data
            if (lower.contains("mock data") || lower.contains("mock text") || lower.contains("administrative")) {
                criteria.add("Mock administrative data or structured records are fully populated")
            }

            // Detect visual design requirements
            if (lower.contains("cool") || lower.contains("design") || lower.contains("professional") || lower.contains("styled")) {
                criteria.add("Visual styling, headings, typography, or structural design are applied")
            }

            val desiredOutcome = if (artifacts.isNotEmpty()) {
                "Create and deliver ${artifacts.joinToString(", ")} satisfying all format, content, and design criteria."
            } else {
                "Successfully execute and verify all required steps for: $trimmed"
            }

            if (criteria.isEmpty()) {
                criteria.add("Task execution is verified with non-zero exit status checks and valid output")
            }

            return TaskObjective(
                description = trimmed,
                desiredOutcome = desiredOutcome,
                constraints = constraints,
                completionCriteria = criteria,
                requiredArtifacts = artifacts
            )
        }
    }
}
