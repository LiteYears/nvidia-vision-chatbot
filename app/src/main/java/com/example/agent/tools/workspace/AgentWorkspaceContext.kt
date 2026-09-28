package com.example.agent.tools.workspace

import com.example.agent.capability.RuntimeCapabilityDetector
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Project types recognized by the agent's workspace discovery engine.
 */
enum class ProjectType(val displayName: String) {
    ANDROID_GRADLE("Android (Gradle multi-module)"),
    GRADLE_JVM("Gradle (JVM/Kotlin/Java)"),
    MAVEN_JVM("Maven (JVM/Java)"),
    NODE_NPM("Node.js / NPM"),
    PYTHON("Python"),
    RUST_CARGO("Rust (Cargo)"),
    GO_MODULE("Go Module"),
    STANDALONE_SCRIPT("Standalone Script / Shell"),
    GENERIC("Generic Directory")
}

/**
 * Details of a discovered project root.
 */
data class DetectedProject(
    val rootPath: String,
    val type: ProjectType,
    val name: String,
    val keyComponents: List<String> = emptyList(),
    val buildCommands: List<String> = emptyList(),
    val testCommands: List<String> = emptyList()
)

/**
 * Information regarding a file inspected by the agent.
 */
data class InspectedFileInfo(
    val path: String,
    val sizeBytes: Long,
    val lastInspectedAt: Long = System.currentTimeMillis(),
    val summary: String? = null
)

/**
 * Classification of changes applied to a workspace file.
 */
enum class FileChangeType {
    CREATED,
    PATCHED,
    OVERWRITTEN,
    DELETED
}

/**
 * Verification state of files modified during the active task.
 */
enum class VerificationStatus {
    NEEDS_VERIFICATION,
    VERIFIED,
    FAILED_VERIFICATION
}

/**
 * Record of a file modification made during the task.
 */
data class ModifiedFileInfo(
    val path: String,
    val changeType: FileChangeType,
    val modifiedAt: Long = System.currentTimeMillis(),
    val summary: String,
    val verificationStatus: VerificationStatus = VerificationStatus.NEEDS_VERIFICATION
)

/**
 * Execution record of a command run in the workspace.
 */
data class CommandRecord(
    val command: String,
    val exitCode: Int,
    val isSuccess: Boolean,
    val outputSnippet: String,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Active failure or error diagnostics left by previous tool/command actions.
 */
data class EnvironmentFailure(
    val toolOrCommand: String,
    val errorMessage: String,
    val diagnosticHint: String? = null,
    val failedAt: Long = System.currentTimeMillis(),
    var isResolved: Boolean = false
)

/**
 * Lightweight, task-scoped workspace and environment context state.
 *
 * Operates like a real coding harness by tracking:
 * 1. Current workspace/project root and detected architecture/runtimes.
 * 2. Discovered directory structure and known files (avoids repeated blind searches).
 * 3. Files inspected during this task (avoids redundant reads).
 * 4. Modifications made during the task (flags changed state and mandates verification).
 * 5. Command & tool execution history with exit codes.
 * 6. Environment failures & error state left by previous actions.
 */
class AgentWorkspaceContext(
    val sessionId: String = "default",
    val workspaceManager: AgentWorkspaceManager = AgentWorkspaceManager.getInstance(),
    private val capabilityDetector: RuntimeCapabilityDetector = RuntimeCapabilityDetector()
) {
    val workspaceRoot: File
        get() = workspaceManager.getWorkspaceDir(sessionId)

    var detectedProject: DetectedProject? = null
        private set

    val knownFiles = ConcurrentHashMap<String, Long>() // relative path -> sizeBytes
    val inspectedFiles = ConcurrentHashMap<String, InspectedFileInfo>()
    val modifiedFiles = ConcurrentHashMap<String, ModifiedFileInfo>()
    val commandHistory = mutableListOf<CommandRecord>()
    var lastFailure: EnvironmentFailure? = null
        private set

    /**
     * Inspects the workspace root and discovers project structure, known files, and recommended commands.
     * Lightweight: walks top-down up to [maxDepth] without loading file bodies.
     */
    fun discoverWorkspace(maxDepth: Int = 8): DetectedProject {
        val root = workspaceRoot
        if (!root.exists()) {
            root.mkdirs()
        }

        val discovered = mutableListOf<String>()
        val files = root.walkTopDown()
            .maxDepth(maxDepth)
            .filter { !it.name.startsWith(".") || it.name == ".gitignore" }
            .take(300)
            .toList()

        for (f in files) {
            val rel = workspaceManager.getRelativePath(f, sessionId)
            if (rel.isNotBlank() && rel != ".") {
                discovered.add(rel)
                knownFiles[rel] = f.length()
            }
        }

        val project = identifyProject(discovered, root)
        detectedProject = project
        return project
    }

    /**
     * Ingests a list of discovered relative paths (e.g. from file_list or file_tree tool outputs).
     */
    fun recordDiscoveredFiles(relativePaths: Collection<String>) {
        for (raw in relativePaths) {
            val clean = raw.trim().replace('\\', '/')
            if (clean.isNotBlank() && clean != ".") {
                knownFiles.putIfAbsent(clean, 0L)
            }
        }
        if (detectedProject == null || detectedProject?.type == ProjectType.GENERIC) {
            detectedProject = identifyProject(knownFiles.keys.toList(), workspaceRoot)
        }
    }

    /**
     * Records that a file was inspected (e.g. via file_read or file_search).
     */
    fun recordFileInspection(path: String, sizeBytes: Long = 0L, summary: String? = null) {
        val clean = cleanPath(path)
        knownFiles[clean] = sizeBytes
        inspectedFiles[clean] = InspectedFileInfo(
            path = clean,
            sizeBytes = sizeBytes,
            lastInspectedAt = System.currentTimeMillis(),
            summary = summary
        )
    }

    /**
     * Records a file modification (via file_write or file_patch).
     * Automatically sets verification status to NEEDS_VERIFICATION so the agent
     * is aware the workspace state has changed and must verify affected components.
     */
    fun recordFileModification(
        path: String,
        changeType: FileChangeType,
        summary: String
    ) {
        val clean = cleanPath(path)
        knownFiles[clean] = try {
            workspaceManager.resolvePath(clean, sessionId).length()
        } catch (_: Exception) {
            0L
        }

        modifiedFiles[clean] = ModifiedFileInfo(
            path = clean,
            changeType = changeType,
            modifiedAt = System.currentTimeMillis(),
            summary = summary,
            verificationStatus = VerificationStatus.NEEDS_VERIFICATION
        )

        // If this modification aims to fix a previous failure, mark active failure as addressed
        lastFailure?.let {
            if (it.errorMessage.contains(clean, ignoreCase = true) || summary.contains("fix", ignoreCase = true)) {
                it.isResolved = true
            }
        }
    }

    /**
     * Records file deletion.
     */
    fun recordFileDeletion(path: String) {
        val clean = cleanPath(path)
        knownFiles.remove(clean)
        modifiedFiles[clean] = ModifiedFileInfo(
            path = clean,
            changeType = FileChangeType.DELETED,
            modifiedAt = System.currentTimeMillis(),
            summary = "File deleted from workspace",
            verificationStatus = VerificationStatus.NEEDS_VERIFICATION
        )
    }

    /**
     * Records command execution (from run_command or python_execute).
     * Automatically updates verification state when compilation or test checks succeed.
     */
    fun recordCommandExecution(
        command: String,
        exitCode: Int,
        output: String,
        isSuccess: Boolean
    ) {
        val snippet = if (output.length > 300) output.take(300) + "..." else output
        val record = CommandRecord(
            command = command,
            exitCode = exitCode,
            isSuccess = isSuccess,
            outputSnippet = snippet,
            timestamp = System.currentTimeMillis()
        )
        commandHistory.add(record)

        val isTestOrBuild = command.contains("test", ignoreCase = true) ||
            command.contains("build", ignoreCase = true) ||
            command.contains("gradle", ignoreCase = true) ||
            command.contains("pytest", ignoreCase = true) ||
            command.contains("npm run", ignoreCase = true) ||
            command.contains("python", ignoreCase = true)

        if (isSuccess) {
            if (isTestOrBuild) {
                // Verification succeeded: mark unverified modifications as VERIFIED
                for ((path, mod) in modifiedFiles) {
                    if (mod.verificationStatus == VerificationStatus.NEEDS_VERIFICATION) {
                        modifiedFiles[path] = mod.copy(verificationStatus = VerificationStatus.VERIFIED)
                    }
                }
                lastFailure?.isResolved = true
            }
        } else {
            // Failure occurred: record environment error state
            lastFailure = EnvironmentFailure(
                toolOrCommand = command,
                errorMessage = snippet,
                diagnosticHint = extractDiagnosticHint(snippet),
                failedAt = System.currentTimeMillis(),
                isResolved = false
            )
            if (isTestOrBuild) {
                for ((path, mod) in modifiedFiles) {
                    if (mod.verificationStatus == VerificationStatus.NEEDS_VERIFICATION) {
                        modifiedFiles[path] = mod.copy(verificationStatus = VerificationStatus.FAILED_VERIFICATION)
                    }
                }
            }
        }
    }

    /**
     * Records a tool-level failure (e.g. tool execution exception or error).
     */
    fun recordToolFailure(toolName: String, error: String, hint: String? = null) {
        lastFailure = EnvironmentFailure(
            toolOrCommand = toolName,
            errorMessage = error,
            diagnosticHint = hint ?: extractDiagnosticHint(error),
            failedAt = System.currentTimeMillis(),
            isResolved = false
        )
    }

    /**
     * Returns true if any files modified during this task are still pending verification.
     */
    fun hasUnverifiedModifications(): Boolean {
        return modifiedFiles.values.any { it.verificationStatus == VerificationStatus.NEEDS_VERIFICATION }
    }

    /**
     * Returns relevant known files matching given keywords, allowing the agent
     * to immediately locate candidate files without blind full-project scans.
     */
    fun findRelevantFiles(keywords: List<String>): List<String> {
        if (keywords.isEmpty()) return knownFiles.keys.take(10).toList()
        val lowerKeywords = keywords.map { it.lowercase() }
        val standardConfigs = setOf("settings.gradle", "settings.gradle.kts", "build.gradle", "build.gradle.kts", "pom.xml", "package.json")
        val isExplicitBuildSearch = lowerKeywords.any { it == "gradle" || it == "build" || it == "pom" || it == "package.json" }
        return knownFiles.keys
            .filter { path ->
                val fileName = path.substringAfterLast('/')
                if (!isExplicitBuildSearch && standardConfigs.contains(fileName)) {
                    return@filter false
                }
                val lower = path.lowercase()
                lowerKeywords.any { kw -> lower.contains(kw) }
            }
            .sortedBy { it.length }
    }

    /**
     * Formats a lightweight, high-signal summary of the workspace and environment state
     * for injection into the agent's turn context.
     */
    fun formatContextForPrompt(): String {
        val project = detectedProject ?: identifyProject(knownFiles.keys.toList(), workspaceRoot)
        val sb = StringBuilder()
        sb.appendLine("### WORKSPACE & ENVIRONMENT CONTEXT")
        sb.appendLine("- Workspace Root: ${workspaceRoot.name} (`${workspaceRoot.path}`)")
        sb.appendLine("- Project Architecture: ${project.type.displayName}")
        if (project.keyComponents.isNotEmpty()) {
            sb.appendLine("- Key Components: ${project.keyComponents.joinToString(", ")}")
        }
        if (project.buildCommands.isNotEmpty() || project.testCommands.isNotEmpty()) {
            val builds = (project.buildCommands + project.testCommands).distinct().joinToString(" | ")
            sb.appendLine("- Recommended Commands: $builds")
        }

        // Known files summary
        if (knownFiles.isNotEmpty()) {
            val fileListStr = knownFiles.keys.take(8).joinToString(", ")
            val moreCount = (knownFiles.size - 8).coerceAtLeast(0)
            val extra = if (moreCount > 0) " (+ $moreCount more files)" else ""
            sb.appendLine("- Known Files (${knownFiles.size} tracked): $fileListStr$extra")
        }

        // Inspected files
        if (inspectedFiles.isNotEmpty()) {
            val inspectedList = inspectedFiles.keys.take(6).joinToString(", ")
            sb.appendLine("- Already Inspected (avoid redundant re-reads): $inspectedList")
        }

        // Modified files with verification status
        if (modifiedFiles.isNotEmpty()) {
            sb.appendLine("- Task Modifications:")
            for ((path, mod) in modifiedFiles) {
                val tag = when (mod.verificationStatus) {
                    VerificationStatus.NEEDS_VERIFICATION -> "[MODIFIED - STATE CHANGED: NEEDS VERIFICATION]"
                    VerificationStatus.VERIFIED -> "[VERIFIED]"
                    VerificationStatus.FAILED_VERIFICATION -> "[VERIFICATION FAILED: REPAIR REQUIRED]"
                }
                sb.appendLine("  * `$path` (${mod.changeType.name}) $tag")
            }
        }

        // Active failure or error diagnostics
        val failure = lastFailure
        if (failure != null && !failure.isResolved) {
            sb.appendLine("- Active Environment Error:")
            sb.appendLine("  * Failed in: `${failure.toolOrCommand}`")
            sb.appendLine("  * Error: ${failure.errorMessage.take(160)}")
            if (!failure.diagnosticHint.isNullOrBlank()) {
                sb.appendLine("  * Diagnostic Hint: ${failure.diagnosticHint}")
            }
        }

        return sb.toString().trimEnd()
    }

    /**
     * Generates a context-aware feedback notice following tool execution.
     */
    fun formatPostActionNotice(toolName: String, isSuccess: Boolean): String? {
        if (!isSuccess && lastFailure != null) {
            return "[WORKSPACE ERROR STATE: '${lastFailure?.toolOrCommand}' failed. Review diagnostics above and adapt your approach or repair files.]"
        }
        if (toolName == "file_write" || toolName == "file_patch") {
            val unverified = modifiedFiles.filter { it.value.verificationStatus == VerificationStatus.NEEDS_VERIFICATION }.keys
            return "[WORKSPACE STATE CHANGED: ${unverified.joinToString(", ")} modified on disk. You must run tests or compile checks before claiming completion.]"
        }
        return null
    }

    private fun cleanPath(raw: String): String {
        var clean = raw.trim().replace('\\', '/')
        if (clean.startsWith("./")) clean = clean.substring(2)
        val rootPath = workspaceRoot.path.replace('\\', '/')
        if (clean.startsWith(rootPath)) {
            clean = clean.removePrefix(rootPath).removePrefix("/")
        }
        return clean.ifBlank { "." }
    }

    private fun identifyProject(paths: Collection<String>, root: File): DetectedProject {
        val lowerPaths = paths.map { it.lowercase() }.toSet()

        // 1. Android Gradle project
        val isAndroid = lowerPaths.any { it.contains("androidmanifest.xml") } ||
            (lowerPaths.any { it.endsWith("build.gradle") || it.endsWith("build.gradle.kts") } &&
                lowerPaths.any { it.contains("app/") || it.contains("src/main/") })

        if (isAndroid) {
            val manifestFile = paths.firstOrNull { it.contains("androidmanifest", ignoreCase = true) }
            val key = mutableListOf<String>()
            if (manifestFile != null) key.add(manifestFile)
            key.addAll(paths.filter {
                it != manifestFile && (
                    it.endsWith("build.gradle") || it.endsWith("build.gradle.kts") ||
                    it.endsWith(".kt") || it.endsWith(".java")
                )
            }.take(5))
            return DetectedProject(
                rootPath = root.path,
                type = ProjectType.ANDROID_GRADLE,
                name = root.name,
                keyComponents = key,
                buildCommands = listOf("./gradlew assembleDebug", "./gradlew build"),
                testCommands = listOf("./gradlew testDebugUnitTest")
            )
        }

        // 2. Gradle JVM
        if (lowerPaths.any { it.endsWith("build.gradle") || it.endsWith("build.gradle.kts") || it.endsWith("settings.gradle") }) {
            return DetectedProject(
                rootPath = root.path,
                type = ProjectType.GRADLE_JVM,
                name = root.name,
                keyComponents = paths.filter { it.contains("src/main") || it.endsWith(".gradle") || it.endsWith(".gradle.kts") }.take(4),
                buildCommands = listOf("./gradlew build"),
                testCommands = listOf("./gradlew test")
            )
        }

        // 3. Maven JVM
        if (lowerPaths.any { it.endsWith("pom.xml") }) {
            return DetectedProject(
                rootPath = root.path,
                type = ProjectType.MAVEN_JVM,
                name = root.name,
                keyComponents = listOf("pom.xml", "src/main/java"),
                buildCommands = listOf("mvn compile"),
                testCommands = listOf("mvn test")
            )
        }

        // 4. Node / NPM
        if (lowerPaths.any { it.endsWith("package.json") }) {
            return DetectedProject(
                rootPath = root.path,
                type = ProjectType.NODE_NPM,
                name = root.name,
                keyComponents = paths.filter { it == "package.json" || it.startsWith("src/") }.take(4),
                buildCommands = listOf("npm run build", "npm install"),
                testCommands = listOf("npm test")
            )
        }

        // 5. Python
        if (lowerPaths.any { it.endsWith("requirements.txt") || it.endsWith("pyproject.toml") || it.endsWith("setup.py") || it.endsWith(".py") }) {
            val pyFiles = paths.filter { it.endsWith(".py") }.take(4)
            return DetectedProject(
                rootPath = root.path,
                type = ProjectType.PYTHON,
                name = root.name,
                keyComponents = pyFiles.ifEmpty { listOf("requirements.txt") },
                buildCommands = listOf("python3 <script>"),
                testCommands = listOf("pytest", "python3 -m unittest", "python_execute")
            )
        }

        // 6. Rust Cargo
        if (lowerPaths.any { it.endsWith("cargo.toml") }) {
            return DetectedProject(
                rootPath = root.path,
                type = ProjectType.RUST_CARGO,
                name = root.name,
                keyComponents = listOf("Cargo.toml", "src/main.rs"),
                buildCommands = listOf("cargo build"),
                testCommands = listOf("cargo test")
            )
        }

        // 7. Go
        if (lowerPaths.any { it.endsWith("go.mod") }) {
            return DetectedProject(
                rootPath = root.path,
                type = ProjectType.GO_MODULE,
                name = root.name,
                keyComponents = listOf("go.mod"),
                buildCommands = listOf("go build"),
                testCommands = listOf("go test ./...")
            )
        }

        return DetectedProject(
            rootPath = root.path,
            type = ProjectType.GENERIC,
            name = root.name,
            keyComponents = paths.take(4),
            buildCommands = emptyList(),
            testCommands = emptyList()
        )
    }

    private fun extractDiagnosticHint(error: String): String {
        return when {
            error.contains("Unresolved reference", ignoreCase = true) ->
                "Missing symbol or import. Inspect imports and dependency configurations."
            error.contains("SyntaxError", ignoreCase = true) ->
                "Syntax error detected. Inspect line numbers and patch affected code."
            error.contains("NoSuchMethodError", ignoreCase = true) || error.contains("ClassNotFoundException", ignoreCase = true) ->
                "API or binary mismatch. Check runtime dependencies and method signatures."
            error.contains("Permission denied", ignoreCase = true) ->
                "Execution permission missing. Check file permissions or use safe tool runner."
            else -> "Inspect error output and adapt implementation or test alternative approach."
        }
    }
}
