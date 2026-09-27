package com.example.agent.tools.workspace

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.Base64

/**
 * Manages isolated, persistent directories for each Agent session.
 * Provides strict path resolution and validation to prevent path traversal
 * and access outside the designated agent workspace.
 *
 * Capabilities:
 * - Pre-initialized standard workspace directory structure (src, scripts, data, output, docs, lib, tests, bin)
 * - Handling for all file types (text, code, data, config, images, archives, binaries)
 * - Binary I/O via ByteArray and Base64 encoding/decoding
 * - Robust path resolution, folder creation, and workspace boundary security
 */
class AgentWorkspaceManager(
    val baseDir: File = defaultBaseDir(),
    val autoInitStandardFolders: Boolean = true
) {
    @Volatile
    var activeSessionId: String = "default"

    init {
        if (!baseDir.exists()) {
            baseDir.mkdirs()
        }
    }

    /**
     * Standard directories initialized in each agent workspace sandbox.
     */
    val standardDirectories: List<String> = STANDARD_DIRECTORIES

    /**
     * Retrieves the isolated directory for a specific session.
     * Automatically ensures standard directories and project files exist if enabled.
     */
    fun getWorkspaceDir(sessionId: String = activeSessionId): File {
        val safeSessionId = sanitizeSessionId(sessionId)
        val dir = File(baseDir, safeSessionId)
        val isNew = !dir.exists()
        if (isNew) {
            dir.mkdirs()
        }
        val canonical = dir.canonicalFile
        if (autoInitStandardFolders) {
            ensureStandardDirectories(canonical)
        }
        return canonical
    }

    /**
     * Ensures all standard development directories and initial project files exist.
     */
    fun ensureStandardDirectories(workspaceRoot: File) {
        for (folder in STANDARD_DIRECTORIES) {
            val subDir = File(workspaceRoot, folder)
            if (!subDir.exists()) {
                subDir.mkdirs()
            }
        }

        val readmeFile = File(workspaceRoot, "README.md")
        if (!readmeFile.exists()) {
            readmeFile.writeText(
                """# Agent Workspace Sandbox
This workspace is pre-configured with standard development folders:
- `src/`: Source code modules (Python, JavaScript/TypeScript, Kotlin, C++)
- `scripts/`: Standalone automation, data processing, and execution scripts
- `data/`: Datasets, raw inputs, JSON, and CSV configurations
- `output/`: Generated artifacts, exports, and build outputs
- `docs/`: Documentation, specifications, and project notes
- `lib/`: Local libraries, pip modules, and dependencies
- `tests/`: Automated test suites and verification scripts
- `bin/`: CLI utilities and local executable tools
""".trimIndent()
            )
        }

        val gitignoreFile = File(workspaceRoot, ".gitignore")
        if (!gitignoreFile.exists()) {
            gitignoreFile.writeText(
                """__pycache__/
*.py[cod]
*$py.class
node_modules/
.npm/
*.tmp
*.log
.cache/
""".trimIndent()
            )
        }
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
     * Creates a directory (and any necessary parent directories) strictly within the workspace.
     */
    fun createDirectory(relativePath: String, sessionId: String = activeSessionId): File {
        val dir = resolvePath(relativePath, sessionId)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
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
     * Reads raw bytes from a workspace file up to [maxBytes].
     */
    fun readBytes(relativePath: String, maxBytes: Int = 262144, sessionId: String = activeSessionId): ByteArray {
        val targetFile = resolvePath(relativePath, sessionId)
        if (!targetFile.exists() || !targetFile.isFile) {
            throw NoSuchFileException(targetFile, null, "File does not exist: '$relativePath'")
        }
        val bytesToRead = minOf(targetFile.length(), maxBytes.toLong()).toInt()
        val buffer = ByteArray(bytesToRead)
        var totalRead = 0
        FileInputStream(targetFile).use { fis ->
            while (totalRead < bytesToRead) {
                val count = fis.read(buffer, totalRead, bytesToRead - totalRead)
                if (count == -1) break
                totalRead += count
            }
        }
        return if (totalRead == bytesToRead) buffer else buffer.copyOf(totalRead)
    }

    /**
     * Writes raw bytes to a workspace file. Automatically creates parent directories.
     */
    fun writeBytes(
        relativePath: String,
        bytes: ByteArray,
        append: Boolean = false,
        sessionId: String = activeSessionId
    ): File {
        val targetFile = resolvePath(relativePath, sessionId)
        targetFile.parentFile?.let { parent ->
            if (!parent.exists()) {
                parent.mkdirs()
            }
        }
        FileOutputStream(targetFile, append).use { fos ->
            fos.write(bytes)
            fos.flush()
        }
        return targetFile
    }

    /**
     * Reads a workspace file as a Base64-encoded string (ideal for binary inspection and transfer).
     */
    fun readBase64(relativePath: String, maxBytes: Int = 262144, sessionId: String = activeSessionId): String {
        val bytes = readBytes(relativePath, maxBytes, sessionId)
        return Base64.getEncoder().encodeToString(bytes)
    }

    /**
     * Writes a Base64-encoded string as raw binary bytes to a workspace file.
     */
    fun writeBase64(
        relativePath: String,
        base64Content: String,
        append: Boolean = false,
        sessionId: String = activeSessionId
    ): File {
        val cleaned = base64Content.trim().replace(Regex("\\s+"), "")
        val bytes = Base64.getDecoder().decode(cleaned)
        return writeBytes(relativePath, bytes, append, sessionId)
    }

    /**
     * Checks if a file is binary (either through known binary file extensions or inspection of initial bytes).
     */
    fun isBinaryFile(file: File): Boolean {
        if (!file.exists() || file.isDirectory) return false
        val ext = file.extension.lowercase()

        if (KNOWN_BINARY_EXTENSIONS.contains(ext)) return true
        if (KNOWN_TEXT_EXTENSIONS.contains(ext)) return false

        // Sample up to first 512 bytes to check for null bytes or control characters
        return try {
            val sampleSize = minOf(file.length(), 512L).toInt()
            if (sampleSize == 0) return false
            val buffer = ByteArray(sampleSize)
            FileInputStream(file).use { fis ->
                fis.read(buffer, 0, sampleSize)
            }
            var nullOrControlCount = 0
            for (b in buffer) {
                val byteVal = b.toInt() and 0xFF
                if (byteVal == 0) return true // Immediate binary indicator
                if (byteVal < 7 || (byteVal in 14..31)) {
                    nullOrControlCount++
                }
            }
            nullOrControlCount.toDouble() / sampleSize > 0.15
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Retrieves detailed file category, MIME type, and description.
     */
    fun getFileTypeInfo(file: File): FileTypeInfo {
        val ext = file.extension.lowercase()
        val info = EXTENSION_MAP[ext]
        if (info != null) return info

        val isBin = isBinaryFile(file)
        return if (isBin) {
            FileTypeInfo(
                extension = ext,
                category = FileCategory.BINARY,
                mimeType = "application/octet-stream",
                isBinary = true,
                description = "Binary data/file"
            )
        } else {
            FileTypeInfo(
                extension = ext,
                category = FileCategory.DOCUMENT,
                mimeType = "text/plain",
                isBinary = false,
                description = "Text file"
            )
        }
    }

    /**
     * Extracts a ZIP archive from an InputStream into a target directory inside the workspace.
     * Enforces path safety to protect against Zip-Slip vulnerabilities.
     */
    fun extractZipStream(
        inputStream: java.io.InputStream,
        destinationDir: File = getWorkspaceDir(),
        archiveName: String = "project.zip",
        overwrite: Boolean = true
    ): ZipExtractResult {
        if (!destinationDir.exists()) {
            destinationDir.mkdirs()
        }
        val canonicalDest = destinationDir.canonicalFile
        val canonicalDestPath = canonicalDest.canonicalPath
        val extractedPaths = mutableListOf<String>()
        var totalBytes = 0L

        return try {
            java.util.zip.ZipInputStream(inputStream).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    val name = entry.name.replace('\\', '/')
                    // Skip root directory or macOS noise
                    if (name.startsWith("__MACOSX") || name.endsWith(".DS_Store")) {
                        zis.closeEntry()
                        entry = zis.nextEntry
                        continue
                    }

                    val targetFile = File(canonicalDest, name).canonicalFile
                    val targetPath = targetFile.canonicalPath

                    // Zip-Slip security check: target must be inside destinationDir
                    if (!targetPath.startsWith(canonicalDestPath + File.separator) && targetPath != canonicalDestPath) {
                        throw SecurityException("Malicious zip entry detected attempting path traversal: '$name'")
                    }

                    if (entry.isDirectory) {
                        targetFile.mkdirs()
                    } else {
                        targetFile.parentFile?.mkdirs()
                        if (overwrite || !targetFile.exists()) {
                            FileOutputStream(targetFile).use { fos ->
                                val buffer = ByteArray(8192)
                                var readCount: Int
                                while (zis.read(buffer).also { readCount = it } != -1) {
                                    fos.write(buffer, 0, readCount)
                                    totalBytes += readCount
                                }
                            }
                            val rel = getRelativePath(targetFile)
                            extractedPaths.add(rel)
                        }
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }

            ZipExtractResult(
                archiveName = archiveName,
                totalFiles = extractedPaths.size,
                totalBytes = totalBytes,
                extractedPaths = extractedPaths,
                isSuccess = true
            )
        } catch (e: Exception) {
            ZipExtractResult(
                archiveName = archiveName,
                totalFiles = extractedPaths.size,
                totalBytes = totalBytes,
                extractedPaths = extractedPaths,
                isSuccess = false,
                errorMessage = e.message ?: e.javaClass.simpleName
            )
        }
    }

    /**
     * Extracts a local ZIP file located inside the workspace into a destination directory.
     */
    fun extractZipFile(
        zipFile: File,
        destinationDir: File = getWorkspaceDir(),
        overwrite: Boolean = true
    ): ZipExtractResult {
        if (!zipFile.exists() || !zipFile.isFile) {
            return ZipExtractResult(
                archiveName = zipFile.name,
                totalFiles = 0,
                totalBytes = 0L,
                extractedPaths = emptyList(),
                isSuccess = false,
                errorMessage = "ZIP archive file not found: '${zipFile.path}'"
            )
        }
        return FileInputStream(zipFile).use { fis ->
            extractZipStream(fis, destinationDir, zipFile.name, overwrite)
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
        val STANDARD_DIRECTORIES = listOf("src", "scripts", "data", "output", "docs", "lib", "tests", "bin")

        val KNOWN_BINARY_EXTENSIONS = setOf(
            "png", "jpg", "jpeg", "gif", "webp", "bmp", "ico", "tiff",
            "zip", "tar", "gz", "tgz", "bz2", "xz", "7z", "whl", "jar", "apk",
            "db", "sqlite", "sqlite3", "parquet", "arrow", "bin", "dat",
            "pdf", "wasm", "so", "dylib", "dll", "exe", "pyc", "class"
        )

        val KNOWN_TEXT_EXTENSIONS = setOf(
            "py", "js", "mjs", "cjs", "ts", "tsx", "jsx", "kt", "java", "c", "cpp", "h", "hpp",
            "rs", "go", "sh", "bash", "zsh", "sql", "html", "htm", "css", "scss", "xml",
            "json", "yaml", "yml", "toml", "ini", "env", "properties", "csv", "tsv",
            "md", "markdown", "txt", "rst", "log"
        )

        val EXTENSION_MAP = mapOf(
            // Code
            "py" to FileTypeInfo("py", FileCategory.SOURCE_CODE, "text/x-python", false, "Python script"),
            "js" to FileTypeInfo("js", FileCategory.SOURCE_CODE, "application/javascript", false, "JavaScript source"),
            "ts" to FileTypeInfo("ts", FileCategory.SOURCE_CODE, "application/typescript", false, "TypeScript source"),
            "tsx" to FileTypeInfo("tsx", FileCategory.SOURCE_CODE, "application/typescript", false, "React TypeScript component"),
            "jsx" to FileTypeInfo("jsx", FileCategory.SOURCE_CODE, "application/javascript", false, "React JSX component"),
            "kt" to FileTypeInfo("kt", FileCategory.SOURCE_CODE, "text/x-kotlin", false, "Kotlin source"),
            "java" to FileTypeInfo("java", FileCategory.SOURCE_CODE, "text/x-java", false, "Java source"),
            "sh" to FileTypeInfo("sh", FileCategory.SOURCE_CODE, "application/x-sh", false, "Shell script"),
            "c" to FileTypeInfo("c", FileCategory.SOURCE_CODE, "text/x-c", false, "C source"),
            "cpp" to FileTypeInfo("cpp", FileCategory.SOURCE_CODE, "text/x-c++", false, "C++ source"),
            "rs" to FileTypeInfo("rs", FileCategory.SOURCE_CODE, "text/x-rust", false, "Rust source"),
            "go" to FileTypeInfo("go", FileCategory.SOURCE_CODE, "text/x-go", false, "Go source"),
            "sql" to FileTypeInfo("sql", FileCategory.SOURCE_CODE, "application/sql", false, "SQL query/schema"),
            "html" to FileTypeInfo("html", FileCategory.SOURCE_CODE, "text/html", false, "HTML web document"),
            "css" to FileTypeInfo("css", FileCategory.SOURCE_CODE, "text/css", false, "CSS stylesheet"),

            // Data & Config
            "json" to FileTypeInfo("json", FileCategory.DATA, "application/json", false, "JSON data"),
            "csv" to FileTypeInfo("csv", FileCategory.DATA, "text/csv", false, "CSV table data"),
            "tsv" to FileTypeInfo("tsv", FileCategory.DATA, "text/tab-separated-values", false, "TSV data"),
            "yaml" to FileTypeInfo("yaml", FileCategory.CONFIG, "application/x-yaml", false, "YAML configuration"),
            "yml" to FileTypeInfo("yml", FileCategory.CONFIG, "application/x-yaml", false, "YAML configuration"),
            "toml" to FileTypeInfo("toml", FileCategory.CONFIG, "application/toml", false, "TOML configuration"),
            "ini" to FileTypeInfo("ini", FileCategory.CONFIG, "text/plain", false, "INI configuration"),
            "env" to FileTypeInfo("env", FileCategory.CONFIG, "text/plain", false, "Environment variables file"),
            "parquet" to FileTypeInfo("parquet", FileCategory.DATA, "application/octet-stream", true, "Apache Parquet dataset"),
            "db" to FileTypeInfo("db", FileCategory.DATA, "application/vnd.sqlite3", true, "Database file"),
            "sqlite" to FileTypeInfo("sqlite", FileCategory.DATA, "application/vnd.sqlite3", true, "SQLite database"),
            "sqlite3" to FileTypeInfo("sqlite3", FileCategory.DATA, "application/vnd.sqlite3", true, "SQLite 3 database"),

            // Documents
            "md" to FileTypeInfo("md", FileCategory.DOCUMENT, "text/markdown", false, "Markdown document"),
            "txt" to FileTypeInfo("txt", FileCategory.DOCUMENT, "text/plain", false, "Plain text document"),
            "pdf" to FileTypeInfo("pdf", FileCategory.DOCUMENT, "application/pdf", true, "PDF document"),

            // Images
            "png" to FileTypeInfo("png", FileCategory.IMAGE, "image/png", true, "PNG image"),
            "jpg" to FileTypeInfo("jpg", FileCategory.IMAGE, "image/jpeg", true, "JPEG image"),
            "jpeg" to FileTypeInfo("jpeg", FileCategory.IMAGE, "image/jpeg", true, "JPEG image"),
            "gif" to FileTypeInfo("gif", FileCategory.IMAGE, "image/gif", true, "GIF image"),
            "webp" to FileTypeInfo("webp", FileCategory.IMAGE, "image/webp", true, "WebP image"),
            "svg" to FileTypeInfo("svg", FileCategory.IMAGE, "image/svg+xml", false, "SVG vector image"),

            // Archives
            "zip" to FileTypeInfo("zip", FileCategory.ARCHIVE, "application/zip", true, "ZIP archive"),
            "tar" to FileTypeInfo("tar", FileCategory.ARCHIVE, "application/x-tar", true, "TAR archive"),
            "gz" to FileTypeInfo("gz", FileCategory.ARCHIVE, "application/gzip", true, "GZIP compressed file"),
            "whl" to FileTypeInfo("whl", FileCategory.ARCHIVE, "application/zip", true, "Python wheel package"),
            "jar" to FileTypeInfo("jar", FileCategory.ARCHIVE, "application/java-archive", true, "Java archive")
        )

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

enum class FileCategory {
    SOURCE_CODE,
    DATA,
    CONFIG,
    DOCUMENT,
    IMAGE,
    ARCHIVE,
    BINARY,
    UNKNOWN
}

data class FileTypeInfo(
    val extension: String,
    val category: FileCategory,
    val mimeType: String,
    val isBinary: Boolean,
    val description: String
)

data class ZipExtractResult(
    val archiveName: String,
    val totalFiles: Int,
    val totalBytes: Long,
    val extractedPaths: List<String>,
    val isSuccess: Boolean,
    val errorMessage: String? = null
)

