package com.example.agent.state

import com.example.agent.artifact.Artifact
import com.example.agent.plan.FailedApproach
import com.example.agent.plan.TaskPlan
import com.example.agent.verification.VerificationResult
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Structured record of an action attempted by the agent.
 */
data class ActionRecord(
    val actionId: String = UUID.randomUUID().toString(),
    val toolName: String,
    val arguments: Map<String, Any?> = emptyMap(),
    val isSuccess: Boolean,
    val exitCode: Int? = null,
    val outputSummary: String = "",
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Authoritative, persistent single source of truth for an autonomous agent run.
 *
 * Owned by the application runtime rather than ephemeral conversational memory.
 */
data class TaskState(
    val taskId: String,
    val originalObjective: TaskObjective,
    val desiredOutcome: String = originalObjective.desiredOutcome,
    val constraints: List<String> = originalObjective.constraints,
    val completionCriteria: List<String> = originalObjective.completionCriteria,
    val currentState: AgentExecutionState = AgentExecutionState.CREATED,
    val currentPlan: TaskPlan,
    val currentAction: String? = null,
    val knownFacts: List<String> = emptyList(),
    val discoveries: List<String> = emptyList(),
    val completedObjectives: List<String> = emptyList(),
    val pendingObjectives: List<String> = emptyList(),
    val actionsAttempted: List<ActionRecord> = emptyList(),
    val successfulActions: List<ActionRecord> = emptyList(),
    val failedActions: List<ActionRecord> = emptyList(),
    val failedApproaches: List<FailedApproach> = emptyList(),
    val importantResults: List<String> = emptyList(),
    val artifacts: List<Artifact> = emptyList(),
    val verificationResults: List<VerificationResult> = emptyList(),
    val nextBestAction: String? = null,
    val iterationCount: Int = 0,
    val lastProgressTimestamp: Long = System.currentTimeMillis()
) {

    /**
     * Constructs a high-density, authoritative context prompt for the LLM
     * to prevent context loss, objective drift, or repeated failure cycles.
     */
    fun toContextPrompt(): String {
        return buildString {
            appendLine("══════════════════════════════════════════════════════════════════════")
            appendLine("                 AUTONOMOUS RUNTIME TASK STATE")
            appendLine("══════════════════════════════════════════════════════════════════════")
            appendLine("TASK ID: $taskId | STATE: ${currentState.name} | ITERATION: $iterationCount")
            appendLine("ORIGINAL OBJECTIVE: ${originalObjective.description}")
            if (desiredOutcome.isNotBlank()) {
                appendLine("DESIRED OUTCOME: $desiredOutcome")
            }
            if (completionCriteria.isNotEmpty()) {
                appendLine("COMPLETION CRITERIA:")
                completionCriteria.forEach { appendLine("  [ ] $it") }
            }
            if (constraints.isNotEmpty()) {
                appendLine("CONSTRAINTS & RULES:")
                constraints.forEach { appendLine("  ⚠ $it") }
            }

            if (artifacts.isNotEmpty()) {
                appendLine("\nREGISTERED DELIVERABLES & ARTIFACTS (${artifacts.size}):")
                artifacts.forEach { art ->
                    val mark = if (art.valid) "✓ [VERIFIED]" else "✗ [UNVERIFIED]"
                    appendLine("  $mark ${art.filename} (${art.size} bytes, ${art.mimeType})")
                    if (art.verificationDetails.isNotBlank()) {
                        appendLine("     -> ${art.verificationDetails}")
                    }
                }
            }

            if (failedApproaches.isNotEmpty()) {
                appendLine("\nFAILED APPROACHES (DO NOT REPEAT):")
                failedApproaches.takeLast(4).forEach {
                    appendLine("  ✗ ${it.approach}: ${it.reason}")
                    if (it.alternativeSuggestion.isNotBlank()) {
                        appendLine("    Alternative: ${it.alternativeSuggestion}")
                    }
                }
            }

            if (knownFacts.isNotEmpty()) {
                appendLine("\nDISCOVERIES & KNOWN FACTS:")
                knownFacts.takeLast(5).forEach { appendLine("  • $it") }
            }

            if (nextBestAction?.isNotBlank() == true) {
                appendLine("\nNEXT RECOMMENDED ACTION: $nextBestAction")
            }
            appendLine("══════════════════════════════════════════════════════════════════════")
        }
    }
}
