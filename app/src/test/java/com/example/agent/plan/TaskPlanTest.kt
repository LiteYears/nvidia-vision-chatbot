package com.example.agent.plan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class TaskPlanTest {

    private lateinit var planner: TaskPlanner

    @Before
    fun setUp() {
        planner = TaskPlanner()
    }

    @Test
    fun `test initial plan creation decomposes goal into ordered subtasks`() {
        val goal = "Research three Android LLM frameworks, compare them, and create a report."
        val plan = planner.createInitialPlan(sessionId = "test-session-1", goal = goal, maxSteps = 15)

        assertEquals("test-session-1", plan.sessionId)
        assertEquals(goal, plan.goal)
        assertEquals(15, plan.maxSteps)
        assertEquals(0, plan.stepCount)
        assertFalse(plan.isCompleted)
        assertTrue(plan.subtasks.size >= 5)

        // Subtasks should be ordered
        for (i in 0 until plan.subtasks.size) {
            assertEquals(i, plan.subtasks[i].orderIndex)
        }

        // First subtask is initialized to RUNNING
        val first = plan.subtasks.first()
        assertEquals(SubtaskStatus.RUNNING, first.status)
        assertEquals(first.id, plan.currentSubtaskId)
        assertEquals(first.id, plan.activeSubtask?.id)

        // Other subtasks remain PENDING
        for (i in 1 until plan.subtasks.size) {
            assertEquals(SubtaskStatus.PENDING, plan.subtasks[i].status)
        }
    }

    @Test
    fun `test tool success updates subtask result but does NOT mark it COMPLETED`() {
        val goal = "Write a python script to calculate primes"
        var plan = planner.createInitialPlan("session-prime", goal)
        val active = plan.activeSubtask
        assertNotNull(active)

        // Execute a tool operation successfully
        val toolOutput = "Created file prime.py with 25 lines"
        plan = planner.recordToolSuccess(
            plan = plan,
            subtaskId = active!!.id,
            toolName = "file_write",
            toolResult = toolOutput
        )

        val updatedSubtask = plan.subtasks.find { it.id == active.id }
        assertNotNull(updatedSubtask)
        // Must still be RUNNING! A tool succeeding does not mean subtask objective is verified
        assertEquals(SubtaskStatus.RUNNING, updatedSubtask!!.status)
        assertTrue(updatedSubtask.result!!.contains("file_write"))
        assertFalse(plan.isCompleted)
    }

    @Test
    fun `test tool failure marks subtask FAILED and increments retry count`() {
        val goal = "Execute benchmark in python"
        var plan = planner.createInitialPlan("session-fail", goal)
        val active = plan.activeSubtask!!

        // Fail a tool
        plan = planner.recordToolFailure(
            plan = plan,
            subtaskId = active.id,
            toolName = "python_execute",
            error = "SyntaxError: invalid syntax on line 4"
        )

        val failedSubtask = plan.subtasks.find { it.id == active.id }!!
        assertEquals(SubtaskStatus.FAILED, failedSubtask.status)
        assertEquals(1, failedSubtask.retryCount)
        assertTrue(failedSubtask.result!!.contains("SyntaxError"))

        // Retry subtask resets it back to RUNNING
        plan = planner.retrySubtask(plan, active.id)
        val retriedSubtask = plan.subtasks.find { it.id == active.id }!!
        assertEquals(SubtaskStatus.RUNNING, retriedSubtask.status)
        assertEquals(active.id, plan.currentSubtaskId)
    }

    @Test
    fun `test subtask verification and overall plan completion`() {
        val customSubtasks = listOf(
            Subtask(id = "sub-1", description = "Find relevant frameworks", status = SubtaskStatus.RUNNING, orderIndex = 0),
            Subtask(id = "sub-2", description = "Research framework 1", status = SubtaskStatus.PENDING, orderIndex = 1)
        )
        var plan = TaskPlan(
            sessionId = "session-verify",
            goal = "Quick research",
            subtasks = customSubtasks,
            currentSubtaskId = "sub-1",
            maxSteps = 10
        )

        // Verify subtask 1
        plan = planner.verifyAndCompleteSubtask(plan, "sub-1", "Frameworks identified: ONNX, Mediapipe, MLC-LLM")
        val sub1 = plan.subtasks.find { it.id == "sub-1" }!!
        assertEquals(SubtaskStatus.COMPLETED, sub1.status)
        assertEquals("Frameworks identified: ONNX, Mediapipe, MLC-LLM", sub1.result)
        assertFalse("Plan should not be complete yet since sub-2 is still pending", plan.isCompleted)

        // Start subtask 2
        plan = planner.startSubtask(plan, "sub-2")
        assertEquals(SubtaskStatus.RUNNING, plan.subtasks.find { it.id == "sub-2" }!!.status)

        // Verify subtask 2
        plan = planner.verifyAndCompleteSubtask(plan, "sub-2", "Researched ONNX Runtime Mobile")
        val sub2 = plan.subtasks.find { it.id == "sub-2" }!!
        assertEquals(SubtaskStatus.COMPLETED, sub2.status)

        // Now all subtasks are verified! Overall plan should be marked COMPLETED
        assertTrue(plan.allSubtasksCompleted)
        assertTrue(plan.isCompleted)
    }

    @Test
    fun `test maximum step limit prevents runaway execution`() {
        var plan = TaskPlan(
            sessionId = "runaway-test",
            goal = "Infinite loop test",
            maxSteps = 3,
            stepCount = 0
        )

        assertFalse(plan.isStepLimitExceeded)

        plan = planner.incrementStep(plan)
        assertEquals(1, plan.stepCount)
        assertFalse(plan.isStepLimitExceeded)

        plan = planner.incrementStep(plan)
        assertEquals(2, plan.stepCount)
        assertFalse(plan.isStepLimitExceeded)

        plan = planner.incrementStep(plan)
        assertEquals(3, plan.stepCount)
        assertTrue("Step count reached maxSteps limit", plan.isStepLimitExceeded)
    }

    @Test
    fun `test agent can parse and dynamically update its own plan from markdown checklist`() {
        val initial = planner.createInitialPlan("session-dyn", "Research topic")

        val agentOutput = """
            I have analyzed the problem and here is my updated plan:
            ```plan
            ☑ Find relevant frameworks
            ⏳ Research framework 1
            ☐ Research framework 2
            ☐ Compare findings
            ```
            I will now execute web search for framework 1.
        """.trimIndent()

        val updatedPlan = planner.parsePlanFromAgentOutput(agentOutput, initial)
        assertNotNull(updatedPlan)
        assertEquals(4, updatedPlan!!.subtasks.size)

        assertEquals("Find relevant frameworks", updatedPlan.subtasks[0].description)
        assertEquals(SubtaskStatus.COMPLETED, updatedPlan.subtasks[0].status)

        assertEquals("Research framework 1", updatedPlan.subtasks[1].description)
        assertEquals(SubtaskStatus.RUNNING, updatedPlan.subtasks[1].status)

        assertEquals("Research framework 2", updatedPlan.subtasks[2].description)
        assertEquals(SubtaskStatus.PENDING, updatedPlan.subtasks[2].status)

        assertEquals("Compare findings", updatedPlan.subtasks[3].description)
        assertEquals(SubtaskStatus.PENDING, updatedPlan.subtasks[3].status)
    }

    @Test
    fun `test agent can parse plan with brackets notation`() {
        val initial = planner.createInitialPlan("session-brackets", "Data processing")

        val agentText = """
            PLAN:
            - [x] Download dataset
            - [/] Parse CSV records
            - [!] Clean corrupted rows
            - [ ] Export summary
        """.trimIndent()

        val parsed = planner.parsePlanFromAgentOutput(agentText, initial)
        assertNotNull(parsed)
        assertEquals(4, parsed!!.subtasks.size)

        assertEquals(SubtaskStatus.COMPLETED, parsed.subtasks[0].status)
        assertEquals("Download dataset", parsed.subtasks[0].description)

        assertEquals(SubtaskStatus.RUNNING, parsed.subtasks[1].status)
        assertEquals("Parse CSV records", parsed.subtasks[1].description)

        assertEquals(SubtaskStatus.FAILED, parsed.subtasks[2].status)
        assertEquals("Clean corrupted rows", parsed.subtasks[2].description)

        assertEquals(SubtaskStatus.PENDING, parsed.subtasks[3].status)
        assertEquals("Export summary", parsed.subtasks[3].description)
    }
}
