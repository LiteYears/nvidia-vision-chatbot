package com.example.agent.tools.python

import com.example.agent.python.EmbeddedPythonRuntime
import com.example.agent.tools.AgentTool
import com.example.agent.tools.ToolDefinition
import com.example.agent.tools.ToolParameter
import com.example.agent.tools.ToolResult
import com.example.agent.tools.workspace.AgentWorkspaceManager
import java.io.File
import java.util.UUID

/**
 * Dedicated Agent Mode tool for executing Python code and workspace scripts.
 *
 * Runs via an in-app embedded Python 3 runtime, guaranteeing reliable execution
 * on Android without relying on external system shell binaries.
 *
 * Enforces:
 * - Isolated workspace execution
 * - Strict execution timeouts (kills infinite loops)
 * - Output buffer limit enforcement
 * - Formatted Python-standard tracebacks for autonomous debugging loops
 */
class PythonExecuteTool(
    private val workspaceManager: AgentWorkspaceManager = AgentWorkspaceManager.getInstance(),
    private val pythonRuntime: EmbeddedPythonRuntime = EmbeddedPythonRuntime(workspaceManager)
) : AgentTool {

    override val definition: ToolDefinition = ToolDefinition(
        name = "python_execute",
        description = "Executes Python 3 code or scripts inside the agent workspace using an embedded in-app runtime. " +
            "Captures stdout, stderr, exit code, and execution time. Supports file I/O strictly isolated to the workspace, " +
            "standard libraries (math, json, random, sys, os, time, re), and autonomous coding/debugging workflows. " +
            "Returns formatted Python tracebacks (SyntaxError, NameError, TypeError, ZeroDivisionError, etc.) on errors.",
        parameters = listOf(
            ToolParameter(
                name = "script_path",
                type = "string",
                description = "Relative path of the Python script in the workspace (e.g. 'script.py' or 'test.py')",
                required = false
            ),
            ToolParameter(
                name = "code",
                type = "string",
                description = "Direct Python code string to execute (alternative to script_path)",
                required = false
            ),
            ToolParameter(
                name = "args",
                type = "array",
                description = "Optional command line arguments to pass to sys.argv",
                required = false
            ),
            ToolParameter(
                name = "timeout_ms",
                type = "number",
                description = "Maximum execution time in milliseconds (default: 5000 ms, maximum: 30000 ms)",
                required = false,
                default = 5000
            ),
            ToolParameter(
                name = "max_output_bytes",
                type = "number",
                description = "Maximum output bytes to capture from stdout/stderr (default: 32768 / 32 KB)",
                required = false,
                default = 32768
            )
        )
    )

    override suspend fun execute(arguments: Map<String, Any?>): ToolResult {
        val callId = UUID.randomUUID().toString()
        val scriptPathArg = arguments["script_path"]?.toString()?.trim()
            ?: arguments["file"]?.toString()?.trim()
            ?: arguments["script"]?.toString()?.trim()
        val directCodeArg = arguments["code"]?.toString()

        val timeoutMs = parseLong(arguments["timeout_ms"] ?: arguments["timeout"], default = 5000L)
            .coerceIn(100L, 30000L)
        val maxOutputBytes = parseInt(arguments["max_output_bytes"] ?: arguments["max_bytes"], default = 32768)
            .coerceIn(1024, 131072)

        val argsList = parseArgs(arguments["args"])

        val codeToRun: String
        val scriptName: String

        if (!scriptPathArg.isNullOrBlank()) {
            val scriptFile: File
            try {
                scriptFile = workspaceManager.resolvePath(scriptPathArg)
            } catch (e: SecurityException) {
                return ToolResult.failure(
                    callId = callId,
                    toolName = definition.name,
                    error = "Security validation failed: ${e.message}"
                )
            }

            if (!scriptFile.exists() || !scriptFile.isFile) {
                val availableFiles = try {
                    workspaceManager.listWorkspaceFiles()
                        .filter { !it.isDirectory && (it.relativePath.endsWith(".py") || it.relativePath.contains(".py")) }
                        .map { it.relativePath }
                } catch (_: Exception) {
                    emptyList()
                }
                val baseName = File(scriptPathArg).name
                val matching = availableFiles.filter { it.endsWith(baseName) || it.contains(baseName) }

                val errorMsg = buildString {
                    appendLine("FileNotFoundError: Python script does not exist in workspace: '$scriptPathArg'")
                    if (matching.isNotEmpty()) {
                        appendLine("\nDid you mean:")
                        for (m in matching) {
                            appendLine("  $m")
                        }
                    }
                    if (availableFiles.isNotEmpty()) {
                        appendLine("\nAvailable Python scripts:")
                        for (f in availableFiles.take(10)) {
                            appendLine("  - $f")
                        }
                    }
                    appendLine("\nRecovery advice:")
                    appendLine("Use 'file_write' to create the script file first, or check 'file_list' to inspect available files.")
                }

                return ToolResult.failure(
                    callId = callId,
                    toolName = definition.name,
                    error = errorMsg.trim()
                )
            }

            codeToRun = scriptFile.readText(Charsets.UTF_8)
            scriptName = scriptFile.name
        } else if (!directCodeArg.isNullOrBlank()) {
            codeToRun = directCodeArg
            scriptName = "inline_script.py"
        } else {
            return ToolResult.failure(
                callId = callId,
                toolName = definition.name,
                error = "Missing required parameter: Provide either 'script_path' (e.g. 'script.py') or 'code' to execute."
            )
        }

        val result = pythonRuntime.execute(
            code = codeToRun,
            filename = scriptName,
            args = argsList,
            timeoutMs = timeoutMs,
            maxOutputBytes = maxOutputBytes
        )

        return if (result.isSuccess) {
            val output = buildString {
                appendLine("Exit Code: 0 (Duration: ${result.durationMs}ms)")
                appendLine("Script: $scriptName")
                if (result.isTruncated) {
                    appendLine("[Output truncated: exceeded $maxOutputBytes bytes limit]")
                }
                if (result.stdout.isNotBlank()) {
                    appendLine("\n[stdout]")
                    append(result.stdout.trimEnd())
                }
                if (result.stderr.isNotBlank()) {
                    appendLine("\n[stderr]")
                    append(result.stderr.trimEnd())
                }
                if (result.stdout.isBlank() && result.stderr.isBlank()) {
                    appendLine("\n(Executed successfully with no output)")
                }
            }.trim()

            ToolResult.success(callId = callId, toolName = definition.name, result = output)
        } else {
            val errorOutput = buildString {
                appendLine("Exit Code: ${result.exitCode} (Duration: ${result.durationMs}ms)")
                appendLine("Script: $scriptName")
                if (result.errorType != null) {
                    appendLine("Error Classification: ${result.errorType}")
                }
                if (result.stderr.isNotBlank()) {
                    appendLine("\n[stderr]")
                    append(result.stderr.trimEnd())
                }
                if (result.stdout.isNotBlank()) {
                    appendLine("\n[stdout]")
                    append(result.stdout.trimEnd())
                }
            }.trim()

            ToolResult.failure(callId = callId, toolName = definition.name, error = errorOutput)
        }
    }

    private fun parseLong(value: Any?, default: Long): Long {
        return when (value) {
            is Number -> value.toLong()
            is String -> value.toLongOrNull() ?: default
            else -> default
        }
    }

    private fun parseInt(value: Any?, default: Int): Int {
        return when (value) {
            is Number -> value.toInt()
            is String -> value.toIntOrNull() ?: default
            else -> default
        }
    }

    private fun parseArgs(value: Any?): List<String> {
        return when (value) {
            is List<*> -> value.map { it?.toString() ?: "" }
            is String -> if (value.isBlank()) emptyList() else value.trim().split(Regex("\\s+"))
            else -> emptyList()
        }
    }
}
