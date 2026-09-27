package com.example.agent.tools.workspace

import java.io.File

/**
 * Manages isolated, persistent directories for each Agent session.
 * Provides strict path resolution and validation to prevent path traversal
 * and access outside the designated agent workspace.
 */
class AgentWorkspaceManager(
    val baseDir: File = defaultBaseDir()
) {
    @Volatile
    var activeSessionId: String = "default"

    init {
        if (!baseDir.exists()) {
            baseDir.mkdirs()
        }
    }

    /**
     * Retrieves the isolated directory for a specific session.
     */
    fun getWorkspaceDir(sessionId: String = activeSessionId): File {
        val safeSessionId = sanitizeSessionId(sessionId)
        val dir = File(baseDir, safeSessionId)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir.canonicalFile
    }

    /**
     * Resolves a relative path strictly inside the session workspace.
     * Throws SecurityException if the target path attempts to escape the workspace.
     */
    fun resolvePath(relativePath: String, sessionId: String = activeSessionId): File {
        if (relativePath.contains('\u0000')) {
            throw SecurityException("Invalid character in path: null byte detected.")
        }

        val trimmed = relativePath.trim()
        val workspaceRoot = getWorkspaceDir(sessionId)

        val target = if (trimmed.isEmpty() || trimmed == "." || trimmed == "./" || trimmed == "/") {
            workspaceRoot
        } else {
            val fileCandidate = File(trimmed)
            if (fileCandidate.isAbsolute) {
                fileCandidate.canonicalFile
            } else {
                File(workspaceRoot, trimmed).canonicalFile
            }
        }

        val rootPath = workspaceRoot.canonicalPath
        val targetPath = target.canonicalPath

        // Strict boundary check: target must be the root itself or inside rootPath/
        val isInside = targetPath == rootPath || targetPath.startsWith(rootPath + File.separator)
        if (!isInside) {
            throw SecurityException("Access denied: Path '$relativePath' attempts to escape the agent workspace.")
        }

        return target
    }

    /**
     * Returns the relative path of a file compared to the workspace root.
     */
    fun getRelativePath(file: File, sessionId: String = activeSessionId): String {
        val root = getWorkspaceDir(sessionId)
        return try {
            root.toPath().relativize(file.canonicalFile.toPath()).toString().replace('\\', '/')
        } catch (_: Exception) {
            file.name
        }
    }

    /**
     * Deletes an entire session workspace (e.g. on explicit task deletion).
     */
    fun deleteWorkspace(sessionId: String): Boolean {
        val safeSessionId = sanitizeSessionId(sessionId)
        val dir = File(baseDir, safeSessionId)
        return if (dir.exists()) dir.deleteRecursively() else true
    }

    private fun sanitizeSessionId(id: String): String {
        val cleaned = id.replace(Regex("[^a-zA-Z0-9_-]"), "_").trim()
        return cleaned.ifBlank { "default" }
    }

    companion object {
        @Volatile
        private var instance: AgentWorkspaceManager? = null

        fun getInstance(): AgentWorkspaceManager {
            return instance ?: synchronized(this) {
                instance ?: AgentWorkspaceManager().also { instance = it }
            }
        }

        fun init(baseDir: File): AgentWorkspaceManager {
            return synchronized(this) {
                val mgr = AgentWorkspaceManager(baseDir)
                instance = mgr
                mgr
            }
        }

        private fun defaultBaseDir(): File {
            val userHome = System.getProperty("user.home")
            val base = if (!userHome.isNullOrBlank()) {
                File(userHome, ".agent_workspaces")
            } else {
                File(System.getProperty("java.io.tmpdir", "/tmp"), "agent_workspaces")
            }
            base.mkdirs()
            return base
        }
    }
}
