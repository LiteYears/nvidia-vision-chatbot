package com.example.agent.artifact

import com.example.agent.verification.TaskVerificationEngine
import java.io.File
import java.util.UUID

/**
 * Automates the discovery, inspection, and verification of filesystem artifacts
 * created or modified during agent task execution.
 */
class ArtifactDetector(
    private val workspaceDir: File,
    private val taskId: String
) {

    data class FileSnapshot(
        val path: String,
        val size: Long,
        val lastModified: Long
    )

    private val ignoredDirectories = setOf(".git", ".gradle", "build", "node_modules", ".cache", "tmp")

    fun captureSnapshot(): Map<String, FileSnapshot> {
        val map = mutableMapOf<String, FileSnapshot>()
        if (!workspaceDir.exists() || !workspaceDir.isDirectory) return map

        workspaceDir.walkTopDown()
            .onEnter { dir -> !ignoredDirectories.contains(dir.name) }
            .filter { it.isFile }
            .forEach { file ->
                val rel = file.relativeTo(workspaceDir).path
                map[rel] = FileSnapshot(rel, file.length(), file.lastModified())
            }

        return map
    }

    /**
     * Compares the workspace before and after an operation to detect newly created
     * or updated deliverables.
     */
    fun detectNewOrModifiedArtifacts(
        beforeSnapshot: Map<String, FileSnapshot>,
        existingArtifacts: List<Artifact> = emptyList()
    ): List<Artifact> {
        val currentSnapshot = captureSnapshot()
        val detected = mutableListOf<Artifact>()

        for ((relPath, snap) in currentSnapshot) {
            val file = File(workspaceDir, relPath)
            if (!file.exists() || file.length() == 0L) continue

            val before = beforeSnapshot[relPath]
            val isNew = before == null
            val isModified = before != null && (before.size != snap.size || before.lastModified != snap.lastModified)

            if (isNew || isModified) {
                // Determine MIME type
                val mimeType = resolveMimeType(file)
                val verification = TaskVerificationEngine.verifyFile(file)

                val artifact = Artifact(
                    id = existingArtifacts.find { it.path == relPath }?.id ?: UUID.randomUUID().toString(),
                    taskId = taskId,
                    path = relPath,
                    filename = file.name,
                    mimeType = mimeType,
                    size = file.length(),
                    createdAt = before?.lastModified ?: snap.lastModified,
                    modifiedAt = snap.lastModified,
                    exists = true,
                    valid = verification.isPassed,
                    downloadable = true,
                    previewable = isPreviewable(mimeType),
                    verificationStatus = if (verification.isPassed) "VALID" else "INVALID",
                    verificationDetails = verification.details
                )
                detected.add(artifact)
            }
        }

        return detected
    }

    private fun resolveMimeType(file: File): String {
        val name = file.name.lowercase()
        return when {
            name.endsWith(".docx") -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            name.endsWith(".doc") -> "application/msword"
            name.endsWith(".pdf") -> "application/pdf"
            name.endsWith(".apk") -> "application/vnd.android.package-archive"
            name.endsWith(".zip") -> "application/zip"
            name.endsWith(".tar") -> "application/x-tar"
            name.endsWith(".gz") -> "application/gzip"
            name.endsWith(".json") -> "application/json"
            name.endsWith(".csv") -> "text/csv"
            name.endsWith(".md") -> "text/markdown"
            name.endsWith(".txt") -> "text/plain"
            name.endsWith(".py") -> "text/x-python"
            name.endsWith(".sh") -> "text/x-shellscript"
            name.endsWith(".png") -> "image/png"
            name.endsWith(".jpg") || name.endsWith(".jpeg") -> "image/jpeg"
            name.endsWith(".svg") -> "image/svg+xml"
            name.endsWith(".html") -> "text/html"
            else -> "application/octet-stream"
        }
    }

    private fun isPreviewable(mimeType: String): Boolean {
        return mimeType.startsWith("text/") ||
            mimeType == "application/json" ||
            mimeType.startsWith("image/")
    }
}
