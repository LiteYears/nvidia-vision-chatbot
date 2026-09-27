package com.example.agent.plan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests auditing and verifying task planning behavior for complex development requests:
 * 1. Multi-file development workflow (inspect architecture -> locate files -> understand code ->
 *    plan minimal changes -> modify files -> compile/test -> repair & retest -> verify).
 * 2. Plan adaptation and recovery when an initial implementation approach fails.
 * 3. Prevention of premature completion claims without compilation/test verification.
 * 4. Dynamic subtask insertion and revision during execution.
 */
class ComplexDevelopmentTaskPlanningTest {

    private lateinit var planner: TaskPlanner

    @Before
    fun setUp() {
        planner = TaskPlanner()
    }

    @Test
    fun `test complex multi-file development task decomposes into architectural inspect-plan-modify-verify workflow`() {
        val goal = "Add a settings screen that lets the user enable/disable web browsing, integrate it with the existing agent, and make sure the setting persists."
        var plan = planner.createInitialPlan(sessionId = "dev-session-settings", goal = goal, maxSteps = 25)

        // 1. Initial Plan Verification: Dynamic decomposition for complex feature requests
        assertEquals(6, plan.subtasks.size)
        assertTrue(plan.subtasks[0].description.contains("Inspect project architecture", ignoreCase = true))
        assertEquals(SubtaskStatus.RUNNING, plan.subtasks[0].status)
        assertEquals(plan.subtasks[0].id, plan.activeSubtask?.id)

        assertTrue(plan.subtasks[1].description.contains("Analyze existing implementation", ignoreCase = true))
        assertEquals(SubtaskStatus.PENDING, plan.subtasks[1].status)

        assertTrue(plan.subtasks[2].description.contains("Implement modifications", ignoreCase = true))
        assertEquals(SubtaskStatus.PENDING, plan.subtasks[2].status)

        assertTrue(plan.subtasks[3].description.contains("Compile, run tests", ignoreCase = true))
        assertEquals(SubtaskStatus.PENDING, plan.subtasks[3].status)

        assertTrue(plan.subtasks[4].description.contains("Review test results", ignoreCase = true))
        assertEquals(SubtaskStatus.PENDING, plan.subtasks[4].status)

        assertTrue(plan.subtasks[5].description.contains("Verify final state", ignoreCase = true))
        assertEquals(SubtaskStatus.PENDING, plan.subtasks[5].status)

        assertTrue("Multi-file task must have pending action subtasks initially", planner.hasPendingActionSubtasks(plan))
        assertFalse("Plan cannot be marked complete at start", plan.isCompleted)

        // 2. Stage 1: Inspect architecture and locate relevant files
        // Agent calls file_list or file_tree to locate settings, agent session, and UI files
        plan = planner.advanceSubtaskOnToolSuccess(
            plan = plan,
            subtaskId = plan.subtasks[0].id,
            toolName = "file_list",
            toolResult = "Found SettingsManager.kt, AgentSession.kt, ChatViewModel.kt, SettingsScreen.kt"
        )
        assertEquals(SubtaskStatus.COMPLETED, plan.subtasks[0].status)
        assertEquals(SubtaskStatus.RUNNING, plan.subtasks[1].status)
        assertEquals(plan.subtasks[1].id, plan.activeSubtask?.id)

        // 3. Stage 2: Analyze existing implementation and plan minimal changes
        // Agent reads files to understand architecture before editing
        plan = planner.advanceSubtaskOnToolSuccess(
            plan = plan,
            subtaskId = plan.subtasks[1].id,
            toolName = "file_read",
            toolResult = "class SettingsManager: SharedPreferences wrapper. AgentSession: has webBrowsingEnabled flag."
        )
        assertEquals(SubtaskStatus.COMPLETED, plan.subtasks[1].status)
        assertEquals(SubtaskStatus.RUNNING, plan.subtasks[2].status)
        assertEquals(plan.subtasks[2].id, plan.activeSubtask?.id)

        // 4. Stage 3: Multi-file implementation
        // Modify file 1: SettingsManager.kt
        plan = planner.recordToolSuccess(
            plan = plan,
            subtaskId = plan.subtasks[2].id,
            toolName = "file_patch",
            toolResult = "Patched SettingsManager.kt with webBrowsingEnabled preference"
        )
        // Subtask 2 remains RUNNING while editing multiple files
        assertEquals(SubtaskStatus.RUNNING, plan.subtasks[2].status)

        // Modify file 2: AgentSession.kt
        plan = planner.recordToolSuccess(
            plan = plan,
            subtaskId = plan.subtasks[2].id,
            toolName = "file_patch",
            toolResult = "Patched AgentSession.kt to consume webBrowsingEnabled setting"
        )
        assertEquals(SubtaskStatus.RUNNING, plan.subtasks[2].status)

        // Modify file 3: ChatViewModel.kt and advance
        plan = planner.advanceSubtaskOnToolSuccess(
            plan = plan,
            subtaskId = plan.subtasks[2].id,
            toolName = "file_patch",
            toolResult = "Patched ChatViewModel.kt to bind UI setting toggle and persist state"
        )
        assertEquals(SubtaskStatus.COMPLETED, plan.subtasks[2].status)
        // Transitions to Stage 4: Compile & run tests
        assertEquals(SubtaskStatus.RUNNING, plan.subtasks[3].status)
        assertEquals(plan.subtasks[3].id, plan.activeSubtask?.id)

        // 5. Verification Gate: Test/compilation cannot be bypassed
        assertTrue("Pending action subtasks must remain until compilation/tests run", planner.hasPendingActionSubtasks(plan))
        assertFalse(plan.isCompleted)

        // 6. Stage 4: Compile and test via run_command
        plan = planner.advanceSubtaskOnToolSuccess(
            plan = plan,
            subtaskId = plan.subtasks[3].id,
            toolName = "run_command",
            toolResult = "BUILD SUCCESSFUL in 8s. 14 tests completed, 0 failed."
        )
        assertEquals(SubtaskStatus.COMPLETED, plan.subtasks[3].status)
        // Subtask 4 (review & repair) advances as tests passed cleanly
        plan = planner.advanceSubtaskOnToolSuccess(
            plan = plan,
            subtaskId = plan.subtasks[4].id,
            toolName = "run_command",
            toolResult = "All assertions passed; no repairs required."
        )
        assertEquals(SubtaskStatus.COMPLETED, plan.subtasks[4].status)

        // 7. Stage 5: Final verification and deliverable completion
        plan = planner.verifyAndCompleteSubtask(
            plan = plan,
            subtaskId = plan.subtasks[5].id,
            verificationNotes = "Settings screen implemented, web browsing preference wired to AgentSession, persisted via SharedPreferences, all unit tests passed."
        )
        assertEquals(SubtaskStatus.COMPLETED, plan.subtasks[5].status)
        assertTrue("All subtasks must be completed", plan.allSubtasksCompleted)
        assertTrue("Plan must be marked completed", plan.isCompleted)
        assertFalse("No pending action subtasks remain", planner.hasPendingActionSubtasks(plan))
    }

    @Test
    fun `test plan adaptation when initial implementation approach fails`() {
        val goal = "Integrate advanced web crawler into agent search pipeline."
        var plan = planner.createInitialPlan(sessionId = "adapt-session-crawler", goal = goal, maxSteps = 20)

        // Fast-forward through inspection and planning to implementation
        plan = planner.advanceSubtaskOnToolSuccess(plan, plan.subtasks[0].id, "file_list", "Found search tools")
        plan = planner.advanceSubtaskOnToolSuccess(plan, plan.subtasks[1].id, "file_read", "Inspected SearchEngine.kt")
        assertEquals(SubtaskStatus.RUNNING, plan.subtasks[2].status)

        // Attempt Implementation Approach A (e.g. headless chromium crawler)
        plan = planner.advanceSubtaskOnToolSuccess(
            plan = plan,
            subtaskId = plan.subtasks[2].id,
            toolName = "file_patch",
            toolResult = "Added ChromiumCrawler integration to SearchEngine.kt"
        )
        assertEquals(SubtaskStatus.RUNNING, plan.subtasks[3].status)

        // Compilation / Test of Approach A FAILS
        val compileSubtaskId = plan.subtasks[3].id
        val failureError = "CompilationError: Unresolved reference: com.chromium.crawler. Headless Chrome binary not available on Linux ARM64."
        plan = planner.recordToolFailure(
            plan = plan,
            subtaskId = compileSubtaskId,
            toolName = "run_command",
            error = failureError
        )

        val failedCompileSubtask = plan.subtasks.find { it.id == compileSubtaskId }!!
        assertEquals(SubtaskStatus.FAILED, failedCompileSubtask.status)
        assertTrue(failedCompileSubtask.result!!.contains("CompilationError"))
        assertEquals(1, failedCompileSubtask.retryCount)

        // Agent diagnoses error and ADAPTS PLAN:
        // Approach A cannot work due to ARM64 binary incompatibility.
        // Agent generates an adapted plan checklist using Fallback Approach B (DefaultWebPageReader & HtmlContentExtractor)
        val adaptedAgentPlanOutput = """
            Approach A failed because Chromium binary is incompatible with ARM64 environment.
            ADAPTED PLAN:
            - [x] Inspect project architecture and locate relevant files
            - [x] Analyze existing implementation and plan minimal changes
            - [!] Implement modifications across relevant files
            - [!] Compile, run tests, or execute checks to verify implementation
            - [/] Fallback: Implement lightweight HtmlContentExtractor and JsoupReader
            - [ ] Compile and verify fallback crawler implementation
            - [ ] Verify final integration
        """.trimIndent()

        val adaptedPlan = planner.parsePlanFromAgentOutput(adaptedAgentPlanOutput, plan)
        assertNotNull("Planner must successfully parse adapted plan from agent output", adaptedPlan)
        assertEquals(7, adaptedPlan!!.subtasks.size)

        // Verify status preservation of completed, failed, and running subtasks
        assertEquals(SubtaskStatus.COMPLETED, adaptedPlan.subtasks[0].status)
        assertEquals(SubtaskStatus.COMPLETED, adaptedPlan.subtasks[1].status)
        assertEquals(SubtaskStatus.FAILED, adaptedPlan.subtasks[2].status)
        assertEquals(SubtaskStatus.FAILED, adaptedPlan.subtasks[3].status)
        assertEquals(SubtaskStatus.RUNNING, adaptedPlan.subtasks[4].status)
        assertEquals("Fallback: Implement lightweight HtmlContentExtractor and JsoupReader", adaptedPlan.subtasks[4].description)
        assertEquals(SubtaskStatus.PENDING, adaptedPlan.subtasks[5].status)

        var runningAdaptedPlan = adaptedPlan

        // Agent implements fallback Approach B
        runningAdaptedPlan = planner.advanceSubtaskOnToolSuccess(
            plan = runningAdaptedPlan,
            subtaskId = runningAdaptedPlan.subtasks[4].id,
            toolName = "file_patch",
            toolResult = "Patched SearchEngine.kt with JsoupReader and HtmlContentExtractor fallback"
        )
        assertEquals(SubtaskStatus.COMPLETED, runningAdaptedPlan.subtasks[4].status)
        assertEquals(SubtaskStatus.RUNNING, runningAdaptedPlan.subtasks[5].status)

        // Compile and verify fallback implementation
        runningAdaptedPlan = planner.advanceSubtaskOnToolSuccess(
            plan = runningAdaptedPlan,
            subtaskId = runningAdaptedPlan.subtasks[5].id,
            toolName = "run_command",
            toolResult = "BUILD SUCCESSFUL. All crawler tests passed with JsoupReader."
        )
        assertEquals(SubtaskStatus.COMPLETED, runningAdaptedPlan.subtasks[5].status)

        // Final verification
        runningAdaptedPlan = planner.verifyAndCompleteSubtask(
            plan = runningAdaptedPlan,
            subtaskId = runningAdaptedPlan.subtasks[6].id,
            verificationNotes = "Crawler pipeline successfully integrated using resilient JsoupReader fallback."
        )
        assertEquals(SubtaskStatus.COMPLETED, runningAdaptedPlan.subtasks[6].status)
        assertTrue(runningAdaptedPlan.allSubtasksCompleted)
        assertTrue(runningAdaptedPlan.isCompleted)
    }

    @Test
    fun `test adaptPlanForFailure programmatic API inserts recovery subtask cleanly`() {
        val goal = "Refactor database caching layer"
        var plan = planner.createInitialPlan(sessionId = "adapt-api", goal = goal)

        val active = plan.activeSubtask!!
        // Active subtask fails
        val failureMsg = "SQLiteException: table already exists with mismatched schema"
        val recoverySubtask = Subtask(
            description = "Run migration schema repair script",
            status = SubtaskStatus.RUNNING,
            orderIndex = 1
        )

        val adapted = planner.adaptPlanForFailure(
            plan = plan,
            failedSubtaskId = active.id,
            failureDiagnostic = failureMsg,
            recoverySubtask = recoverySubtask
        )

        // The failed subtask should be marked FAILED
        val failedSubtask = adapted.subtasks.find { it.id == active.id }!!
        assertEquals(SubtaskStatus.FAILED, failedSubtask.status)
        assertTrue(failedSubtask.result!!.contains(failureMsg))

        // Recovery subtask should be inserted immediately after the failed subtask and be RUNNING
        val recoveryIndex = adapted.subtasks.indexOfFirst { it.description == "Run migration schema repair script" }
        assertTrue("Recovery subtask must be present", recoveryIndex >= 0)
        assertEquals(SubtaskStatus.RUNNING, adapted.subtasks[recoveryIndex].status)
        assertEquals(adapted.subtasks[recoveryIndex].id, adapted.currentSubtaskId)
    }

    @Test
    fun `test agent cannot claim completion when verification subtask is pending`() {
        val goal = "Implement notification dispatch service"
        var plan = planner.createInitialPlan(sessionId = "no-fake-complete", goal = goal)

        // Inspect and edit files
        plan = planner.advanceSubtaskOnToolSuccess(plan, plan.subtasks[0].id, "file_list", "Located files")
        plan = planner.advanceSubtaskOnToolSuccess(plan, plan.subtasks[1].id, "file_read", "Read NotificationService.kt")
        plan = planner.advanceSubtaskOnToolSuccess(plan, plan.subtasks[2].id, "file_patch", "Added dispatch logic")

        // Subtask 3 is compile/run tests - still pending/running!
        assertTrue(planner.hasPendingActionSubtasks(plan))
        assertFalse(plan.isCompleted)

        // Subtask 3 is compile/run tests, and subtask 4/5 are review and verify
        assertEquals(SubtaskStatus.RUNNING, plan.subtasks[3].status)
        assertTrue(plan.subtasks[3].description.contains("Compile, run tests", ignoreCase = true))

        // Trying to verify before compilation must not mark plan complete
        val prematureVerification = planner.verifyAndCompleteSubtask(plan, plan.subtasks[5].id, "Premature claim")
        assertFalse("Plan cannot be complete while compile/test subtask 3 has not run", prematureVerification.isCompleted)
    }

    @Test
    fun `test dynamic subtask insertion preserves order and active state`() {
        val goal = "Add analytics tracker to checkout flow"
        var plan = planner.createInitialPlan(sessionId = "insert-subtask", goal = goal)

        val firstId = plan.subtasks[0].id
        val extraSubtask = Subtask(
            description = "Check privacy compliance and GDPR consent configuration",
            status = SubtaskStatus.PENDING,
            orderIndex = 1
        )

        val updatedPlan = planner.insertSubtaskAfter(plan, firstId, extraSubtask)
        assertEquals(plan.subtasks.size + 1, updatedPlan.subtasks.size)

        // Verify order indices are strictly sequential 0..size-1
        for (i in updatedPlan.subtasks.indices) {
            assertEquals(i, updatedPlan.subtasks[i].orderIndex)
        }

        assertEquals("Check privacy compliance and GDPR consent configuration", updatedPlan.subtasks[1].description)
        assertEquals(SubtaskStatus.PENDING, updatedPlan.subtasks[1].status)
    }
}
