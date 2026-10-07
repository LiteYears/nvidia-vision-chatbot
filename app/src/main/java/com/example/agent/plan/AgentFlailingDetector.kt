package com.example.agent.plan

import java.util.LinkedList

/**
 * Categorization of agent execution loops or repetitive flailing behavior.
 */
enum class FlailingType {
    NONE,
    REPEATED_IDENTICAL_FAILURE,
    REPEATED_IDENTICAL_ACTION,
    PING_PONG_CYCLE,
    CONSECUTIVE_FAILURE_STREAK
}

/**
 * Diagnostics and corrective guidance generated when an execution loop is detected.
 */
data class FlailingReport(
    val type: FlailingType,
    val severityCount: Int,
    val toolName: String,
    val description: String,
    val guidanceDirective: String,
    val shouldAutoAdaptPlan: Boolean
)

/**
 * Signature representing a single tool invocation for loop and repetition analysis.
 */
data class ToolCallSignature(
    val toolName: String,
    val normalizedArgKey: String,
    val isSuccess: Boolean,
    val errorSnippet: String,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Production-grade loop and flailing detector for the autonomous agent.
 *
 * Tracks the agent's tool execution trajectory to prevent runaway repetitions, such as:
 * 1. Calling the exact same failing tool and command repeatedly (e.g. `cat << EOF` or `python3 missing.py`).
 * 2. Ping-pong oscillation between two failing approaches (A -> B -> A -> B).
 * 3. Extended streaks of consecutive failures without workspace state inspection.
 *
 * When detected, injects high-priority anti-loop directives into observation feedback
 * and instructs the task planner to adapt the plan with an explicit recovery subtask.
 */
class AgentFlailingDetector(
    private val maxHistorySize: Int = 15
) {
    private val history = LinkedList<ToolCallSignature>()

    /**
     * Records a tool execution into the trajectory history.
     */
    fun recordExecution(
        toolName: String,
        arguments: Map<String, Any?>,
        isSuccess: Boolean,
        error: String?
    ) {
        val argKey = extractNormalizedArgKey(toolName, arguments)
        val snippet = (error ?: "").take(120).trim()

        history.addLast(
            ToolCallSignature(
                toolName = toolName.lowercase().trim(),
                normalizedArgKey = argKey,
                isSuccess = isSuccess,
                errorSnippet = snippet
            )
        )

        while (history.size > maxHistorySize) {
            history.removeFirst()
        }
    }

    /**
     * Analyzes recent execution history to detect repetitive loops or flailing.
     */
    fun detectFlailing(): FlailingReport? {
        if (history.size < 2) return null

        // 1. Detect consecutive identical failing calls (e.g. same command failing 2+ times in a row)
        val last = history.last()
        if (!last.isSuccess) {
            var consecutiveIdenticalFails = 0
            for (i in history.indices.reversed()) {
                val item = history[i]
                if (!item.isSuccess && item.toolName == last.toolName && item.normalizedArgKey == last.normalizedArgKey) {
                    consecutiveIdenticalFails++
                } else {
                    break
                }
            }

            if (consecutiveIdenticalFails >= 2) {
                val shouldAdapt = consecutiveIdenticalFails >= 3
                val directive = buildString {
                    appendLine("[ANTI-LOOP DIRECTIVE: Repetitive Tool Failure Detected]")
                    appendLine("You have attempted '${last.toolName}' with identical arguments $consecutiveIdenticalFails times, resulting in repeated failures.")
                    appendLine("Command/target: \"${last.normalizedArgKey}\"")
                    appendLine("Failure: ${last.errorSnippet}")
                    appendLine()
                    appendLine("MANDATORY STRATEGY SWITCH:")
                    appendLine("1. DO NOT retry this exact command or tool call again.")
                    appendLine("2. Inspect workspace files using 'file_read' or 'file_list' to verify actual directory layout and contents.")
                    appendLine("3. If a file or directory is missing, create it first using 'file_write' or 'directory_create'.")
                    appendLine("4. If a script threw an error, read the exact error traceback, correct the script with 'file_patch', and re-run.")
                    if (shouldAdapt) {
                        appendLine("5. Your current subtask has failed repeatedly. Adapt your plan by emitting a revised ```plan block with an explicit diagnostic/recovery step.")
                    }
                }.trim()

                return FlailingReport(
                    type = FlailingType.REPEATED_IDENTICAL_FAILURE,
                    severityCount = consecutiveIdenticalFails,
                    toolName = last.toolName,
                    description = "Repeated identical failure on '${last.toolName}' ($consecutiveIdenticalFails times)",
                    guidanceDirective = directive,
                    shouldAutoAdaptPlan = shouldAdapt
                )
            }
        } else {
            // Check for identical repeated actions even if successful (no progress loop)
            var consecutiveIdentical = 0
            for (i in history.indices.reversed()) {
                val item = history[i]
                if (item.toolName == last.toolName && item.normalizedArgKey == last.normalizedArgKey) {
                    consecutiveIdentical++
                } else break
            }
            if (consecutiveIdentical >= 3) {
                val directive = buildString {
                    appendLine("[ANTI-LOOP DIRECTIVE: Redundant Invariant Action Repeated]")
                    appendLine("You have called '${last.toolName}' with identical arguments $consecutiveIdentical times consecutively without taking further action.")
                    appendLine("Target: \"${last.normalizedArgKey}\"")
                    appendLine("You already have the observation from this action. Do not run it again. Advance directly to your next step or deliver your final answer.")
                }.trim()

                return FlailingReport(
                    type = FlailingType.REPEATED_IDENTICAL_ACTION,
                    severityCount = consecutiveIdentical,
                    toolName = last.toolName,
                    description = "Repeated identical redundant action '${last.toolName}' ($consecutiveIdentical times)",
                    guidanceDirective = directive,
                    shouldAutoAdaptPlan = true
                )
            }
        }

        // 2. Detect Ping-Pong oscillation (e.g. A -> B -> A -> B) with failure
        if (history.size >= 4) {
            val s0 = history[history.size - 1]
            val s1 = history[history.size - 2]
            val s2 = history[history.size - 3]
            val s3 = history[history.size - 4]

            val isAlternating = s0.toolName == s2.toolName && s0.normalizedArgKey == s2.normalizedArgKey &&
                s1.toolName == s3.toolName && s1.normalizedArgKey == s3.normalizedArgKey &&
                (s0.toolName != s1.toolName || s0.normalizedArgKey != s1.normalizedArgKey)

            val hasFailures = !s0.isSuccess || !s1.isSuccess || !s2.isSuccess || !s3.isSuccess

            if (isAlternating && hasFailures) {
                val directive = buildString {
                    appendLine("[ANTI-LOOP DIRECTIVE: Alternating Ping-Pong Oscillation Detected]")
                    appendLine("You are oscillating back and forth between two actions without achieving a successful resolution:")
                    appendLine("  Action A: ${s1.toolName} -> \"${s1.normalizedArgKey}\"")
                    appendLine("  Action B: ${s0.toolName} -> \"${s0.normalizedArgKey}\"")
                    appendLine()
                    appendLine("MANDATORY STRATEGY SWITCH:")
                    appendLine("- Stop alternating between these two actions.")
                    appendLine("- Step back, read the error message carefully, and inspect the workspace state.")
                    appendLine("- Use 'file_read' to inspect relevant files or adapt your plan to take an alternative approach.")
                }.trim()

                return FlailingReport(
                    type = FlailingType.PING_PONG_CYCLE,
                    severityCount = 4,
                    toolName = s0.toolName,
                    description = "Alternating between '${s1.toolName}' and '${s0.toolName}' in a loop",
                    guidanceDirective = directive,
                    shouldAutoAdaptPlan = true
                )
            }
        }

        // 3. Detect consecutive failure streak of 3+ across different actions
        var failStreak = 0
        for (i in history.indices.reversed()) {
            if (!history[i].isSuccess) {
                failStreak++
            } else {
                break
            }
        }

        if (failStreak >= 3) {
            val directive = buildString {
                appendLine("[ANTI-LOOP DIRECTIVE: Consecutive Tool Failure Streak]")
                appendLine("The last $failStreak tool actions have all failed.")
                appendLine("You are attempting actions blindly without checking why previous steps failed.")
                appendLine()
                appendLine("MANDATORY ACTION:")
                appendLine("- Stop executing blind commands.")
                appendLine("- Inspect the workspace using 'file_list' or 'file_read' to understand the actual environment.")
                appendLine("- Resolve missing files, fix syntax issues, or adapt your plan.")
            }.trim()

            return FlailingReport(
                type = FlailingType.CONSECUTIVE_FAILURE_STREAK,
                severityCount = failStreak,
                toolName = last.toolName,
                description = "Streak of $failStreak consecutive tool failures",
                guidanceDirective = directive,
                shouldAutoAdaptPlan = failStreak >= 4
            )
        }

        return null
    }

    /**
     * Checks how many consecutive times this exact tool and argument signature has failed recently.
     */
    fun getConsecutiveFailures(toolName: String, arguments: Map<String, Any?>): Int {
        val argKey = extractNormalizedArgKey(toolName, arguments)
        val normTool = toolName.lowercase().trim()
        var count = 0
        for (i in history.indices.reversed()) {
            val item = history[i]
            if (item.toolName == normTool && item.normalizedArgKey == argKey) {
                if (!item.isSuccess) {
                    count++
                } else {
                    break
                }
            } else {
                break
            }
        }
        return count
    }

    /**
     * Checks whether an action has already failed repeatedly without successful recovery.
     */
    fun isActionRepeatedFailure(toolName: String, arguments: Map<String, Any?>, threshold: Int = 2): Boolean {
        return getConsecutiveFailures(toolName, arguments) >= threshold
    }

    /**
     * Clears all recorded trajectory history.
     */
    fun reset() {
        history.clear()
    }

    private fun extractNormalizedArgKey(toolName: String, args: Map<String, Any?>): String {
        return when (toolName.lowercase().trim()) {
            "run_command", "bash", "terminal", "sh", "cmd", "exec" -> {
                val cmd = (args["command"] ?: args["cmd"] ?: args["code"] ?: "").toString().trim()
                val cwd = (args["working_dir"] ?: args["cwd"] ?: "").toString().trim()
                if (cwd.isNotBlank() && cwd != ".") "cwd:$cwd|cmd:$cmd" else cmd
            }
            "python_execute" -> {
                val script = (args["script_path"] ?: args["file"] ?: args["script"])?.toString()?.trim()
                if (!script.isNullOrBlank()) "script:$script" else (args["code"] ?: "").toString().trim().take(80)
            }
            "file_read", "file_write", "file_patch", "file_delete" -> {
                val path = (args["path"] ?: args["file"] ?: args["file_path"] ?: args["filePath"])?.toString()?.trim() ?: ""
                "file:$path"
            }
            "file_search" -> {
                val q = (args["query"] ?: args["search"] ?: "").toString().trim()
                val p = (args["path"] ?: ".").toString().trim()
                "search:$p:$q"
            }
            "web_search" -> {
                val q = (args["query"] ?: args["q"] ?: "").toString().trim()
                "web_search:$q"
            }
            "web_open" -> {
                val u = (args["url"] ?: "").toString().trim()
                "web_open:$u"
            }
            else -> {
                args.entries.sortedBy { it.key }.joinToString("&") { "${it.key}=${it.value}" }
            }
        }
    }
}
