package com.example.agent.artifact

import org.json.JSONObject
import java.util.UUID

/**
 * Representation of a verifiable deliverable produced by an agent task.
 */
data class Artifact(
    val id: String = UUID.randomUUID().toString(),
    val taskId: String,
    val path: String,
    val filename: String,
    val mimeType: String,
    val size: Long,
    val createdAt: Long = System.currentTimeMillis(),
    val modifiedAt: Long = System.currentTimeMillis(),
    val exists: Boolean = true,
    val valid: Boolean = false,
    val downloadable: Boolean = true,
    val previewable: Boolean = false,
    val verificationStatus: String = "UNVERIFIED", // UNVERIFIED, VALID, INVALID
    val verificationDetails: String = ""
) {
    fun toJson(): JSONObject {
        val obj = JSONObject()
        obj.put("id", id)
        obj.put("taskId", taskId)
        obj.put("path", path)
        obj.put("filename", filename)
        obj.put("mimeType", mimeType)
        obj.put("size", size)
        obj.put("createdAt", createdAt)
        obj.put("modifiedAt", modifiedAt)
        obj.put("exists", exists)
        obj.put("valid", valid)
        obj.put("downloadable", downloadable)
        obj.put("previewable", previewable)
        obj.put("verificationStatus", verificationStatus)
        obj.put("verificationDetails", verificationDetails)
        return obj
    }

    companion object {
        fun fromJson(obj: JSONObject): Artifact {
            return Artifact(
                id = obj.optString("id", UUID.randomUUID().toString()),
                taskId = obj.optString("taskId", ""),
                path = obj.optString("path", ""),
                filename = obj.optString("filename", ""),
                mimeType = obj.optString("mimeType", "application/octet-stream"),
                size = obj.optLong("size", 0L),
                createdAt = obj.optLong("createdAt", System.currentTimeMillis()),
                modifiedAt = obj.optLong("modifiedAt", System.currentTimeMillis()),
                exists = obj.optBoolean("exists", true),
                valid = obj.optBoolean("valid", false),
                downloadable = obj.optBoolean("downloadable", true),
                previewable = obj.optBoolean("previewable", false),
                verificationStatus = obj.optString("verificationStatus", "UNVERIFIED"),
                verificationDetails = obj.optString("verificationDetails", "")
            )
        }
    }
}
