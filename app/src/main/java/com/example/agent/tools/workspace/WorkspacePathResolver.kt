package com.example.agent.tools.workspace

import java.io.File

/**
 * Authoritative central resolver for all workspace filesystem paths.
 *
 * Guarantees:
 * 1. The runtime, not the LLM, owns and governs the host workspace path hierarchy.
 * 2. Logical paths presented to the LLM (e.g. '/workspace', '/workspace/docs/file.txt', 'docs/file.txt')
 *    are deterministically translated into the host sandbox directory.
 * 3. Android private storage prefixes (e.g. '/data/data/com.example.../files/agent_workspaces/<id>/...')
 *    inadvertently echoed or emitted by models are safely recognized, stripped, and scoped to the task workspace.
 * 4. Path traversal outside the task sandbox is strictly prevented.
 */
class WorkspacePathResolver(
    private val workspaceManager: AgentWorkspaceManager = AgentWorkspaceManager.getInstance()
) {

    companion object {
        const val LOGICAL_WORKSPACE_ROOT = "/workspace"

        fun getInstance(workspaceManager: AgentWorkspaceManager = AgentWorkspaceManager.getInstance()): WorkspacePathResolver {
            return workspaceManager.pathResolver
        }
    }

    /**
     * Returns the authoritative host File directory for the given task.
     */
    fun getWorkspaceHostDir(taskId: String): File {
        return workspaceManager.getWorkspaceDir(taskId)
    }

    /**
     * Resolves any user, model, or tool input path strictly into a File within the task workspace.
     *
     * Supports:
     * - Empty, ".", "./", "/workspace", "/workspace/", "~", "~/workspace" -> workspace root
     * - Relative paths: "documents/admin.docx" -> workspaceRoot/documents/admin.docx
     * - Logical absolute paths: "/workspace/documents/admin.docx" -> workspaceRoot/documents/admin.docx
     * - Ubuntu home paths: "/home/ubuntu/workspace/foo" -> workspaceRoot/foo
     * - Android host paths: "/data/data/.../agent_workspaces/<id>/foo" -> workspaceRoot/foo
     */
    fun resolve(taskId: String, inputPath: String?): File {
        val workspaceRoot = getWorkspaceHostDir(taskId).canonicalFile
        val raw = inputPath?.trim()?.trim('\'', '"') ?: ""

        if (raw.isBlank() || raw == "." || raw == "./" || raw == "/" || raw == "~" ||
            raw == LOGICAL_WORKSPACE_ROOT || raw == "$LOGICAL_WORKSPACE_ROOT/" ||
            raw == "workspace" || raw == "workspace/" ||
            raw == "/home/ubuntu" || raw == "/home/ubuntu/" ||
            raw == "/home/ubuntu/workspace" || raw == "/home/ubuntu/workspace/" ||
            raw == "~/workspace" || raw == "~/workspace/" ||
            raw == "/root" || raw == "/root/" || raw == "/home" || raw == "/home/"
        ) {
            return workspaceRoot
        }

        var normalized = raw.replace('\\', '/')

        // 1. If path contains Android package data directory or host workspace directory, strip host prefix
        val hostRootPath = workspaceRoot.canonicalPath
        if (normalized.startsWith(hostRootPath)) {
            normalized = normalized.removePrefix(hostRootPath).trimStart('/')
        } else if (normalized.contains("/agent_workspaces/")) {
            // e.g. /data/data/com.cybertermux.agent.mpkatm/files/agent_workspaces/980ea8b5-a188-4417-976f-b536fbd296d9/docs/test
            val afterWs = normalized.substringAfter("/agent_workspaces/")
            val afterSession = if (afterWs.contains('/')) afterWs.substringAfter('/') else ""
            normalized = afterSession
        } else if (normalized.startsWith("/data/data/") || normalized.startsWith("/data/user/0/")) {
            // Strip any other Android data directory attempt, keeping trailing relative parts
            val parts = normalized.split('/').filter { it.isNotBlank() }
            val relevant = parts.dropWhile { it == "data" || it == "user" || it == "0" || it.startsWith("com.") || it == "files" }
            normalized = relevant.joinToString("/")
        }

        // 2. Strip logical Linux workspace prefixes
        if (normalized.startsWith("$LOGICAL_WORKSPACE_ROOT/")) {
            normalized = normalized.removePrefix("$LOGICAL_WORKSPACE_ROOT/").trimStart('/')
        } else if (normalized.startsWith("/home/ubuntu/workspace/")) {
            normalized = normalized.removePrefix("/home/ubuntu/workspace/").trimStart('/')
        } else if (normalized.startsWith("~/workspace/")) {
            normalized = normalized.removePrefix("~/workspace/").trimStart('/')
        } else if (normalized.startsWith("/home/ubuntu/")) {
            val sub = normalized.removePrefix("/home/ubuntu/").trimStart('/')
            normalized = if (sub.startsWith("workspace/")) sub.removePrefix("workspace/").trimStart('/')
            else if (sub == "workspace") ""
            else sub
        } else if (normalized.startsWith("workspace/")) {
            normalized = normalized.removePrefix("workspace/").trimStart('/')
        }

        if (normalized.isBlank()) {
            return workspaceRoot
        }

        // 3. Resolve relative to active current working directory if available and relative
        val candidate = File(normalized)
        val resolved = if (candidate.isAbsolute) {
            // If absolute and starts with workspaceRoot, keep it, otherwise clamp to workspaceRoot
            val can = candidate.canonicalFile
            if (can.canonicalPath.startsWith(hostRootPath)) can else File(workspaceRoot, normalized.trimStart('/')).canonicalFile
        } else {
            val cwd = workspaceManager.getCurrentWorkingDir(taskId)
            val inCwd = if (cwd != workspaceRoot && cwd.exists()) File(cwd, normalized).canonicalFile else null
            val inRoot = File(workspaceRoot, normalized).canonicalFile

            when {
                inCwd != null && inCwd.exists() -> inCwd
                inRoot.exists() -> inRoot
                inCwd != null && cwd.exists() && !normalized.startsWith(cwd.name + "/") -> inCwd
                else -> inRoot
            }
        }

        // 4. Enforce strict workspace boundaries
        val canonical = resolved.canonicalFile
        if (canonical.canonicalPath != hostRootPath && !canonical.canonicalPath.startsWith(hostRootPath + File.separator)) {
            // Prevent path traversal
            return workspaceRoot
        }

        return canonical
    }

    /**
     * Resolves a working directory specifically, validating whether it exists and is a directory.
     * Returns a [WorkingDirectoryResolution] containing the resolved File, logical path, and existence status.
     */
    fun resolveWorkingDirectory(taskId: String, requestedDir: String?): WorkingDirectoryResolution {
        val workspaceRoot = getWorkspaceHostDir(taskId).canonicalFile
        val raw = requestedDir?.trim()?.trim('\'', '"') ?: ""

        val candidate = File(raw)
        val isSystemDir = candidate.isAbsolute && candidate.exists() && candidate.isDirectory

        val targetFile = if (raw.isBlank() || raw == "." || raw == "./") {
            val cwd = workspaceManager.getCurrentWorkingDir(taskId)
            if (cwd.exists() && cwd.isDirectory) cwd else workspaceRoot
        } else if (isSystemDir) {
            candidate.canonicalFile
        } else {
            resolve(taskId, raw)
        }

        val isWithin = targetFile.canonicalPath == workspaceRoot.canonicalPath ||
            targetFile.canonicalPath.startsWith(workspaceRoot.canonicalPath + File.separator)

        val relativePath = if (isWithin) {
            try {
                workspaceRoot.toPath().relativize(targetFile.toPath()).toString().replace('\\', '/')
            } catch (_: Exception) {
                "."
            }
        } else {
            targetFile.canonicalPath
        }

        val logicalPath = if (!isWithin) {
            targetFile.canonicalPath
        } else if (relativePath.isBlank() || relativePath == ".") {
            LOGICAL_WORKSPACE_ROOT
        } else {
            "$LOGICAL_WORKSPACE_ROOT/$relativePath"
        }

        return WorkingDirectoryResolution(
            hostFile = targetFile,
            logicalPath = logicalPath,
            relativePath = if (relativePath.isBlank()) "." else relativePath,
            exists = targetFile.exists(),
            isDirectory = targetFile.isDirectory,
            isWithinWorkspace = isWithin
        )
    }

    /**
     * Converts a host File within the workspace to its logical '/workspace/...' path.
     */
    fun toLogicalPath(taskId: String, file: File): String {
        val workspaceRoot = getWorkspaceHostDir(taskId).canonicalFile
        return try {
            val rel = workspaceRoot.toPath().relativize(file.canonicalFile.toPath()).toString().replace('\\', '/')
            if (rel.isBlank() || rel == ".") LOGICAL_WORKSPACE_ROOT else "$LOGICAL_WORKSPACE_ROOT/$rel"
        } catch (_: Exception) {
            LOGICAL_WORKSPACE_ROOT
        }
    }

    /**
     * Converts a host File within the workspace to its relative path (e.g. 'documents/test.txt').
     */
    fun toRelativePath(taskId: String, file: File): String {
        val workspaceRoot = getWorkspaceHostDir(taskId).canonicalFile
        return try {
            val rel = workspaceRoot.toPath().relativize(file.canonicalFile.toPath()).toString().replace('\\', '/')
            if (rel.isBlank()) "." else rel
        } catch (_: Exception) {
            file.name
        }
    }
}

/**
 * Structured resolution outcome for a requested working directory.
 */
data class WorkingDirectoryResolution(
    val hostFile: File,
    val logicalPath: String,
    val relativePath: String,
    val exists: Boolean,
    val isDirectory: Boolean,
    val isWithinWorkspace: Boolean
)
