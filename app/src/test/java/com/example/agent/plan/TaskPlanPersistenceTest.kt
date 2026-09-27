package com.example.agent.plan

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.ChatDatabase
import com.example.data.model.AgentSession
import com.example.data.model.AgentTaskStatus
import com.example.data.repository.AgentPlanRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class TaskPlanPersistenceTest {

    private lateinit var database: ChatDatabase
    private lateinit var repository: AgentPlanRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, ChatDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = AgentPlanRepository(database.agentPlanDao())
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `test task plan persists and restores from Room database`() = runBlocking {
        val subtasks = listOf(
            Subtask(id = "sub-1", description = "Find relevant frameworks", status = SubtaskStatus.COMPLETED, result = "Found 3 frameworks", orderIndex = 0),
            Subtask(id = "sub-2", description = "Research framework 1", status = SubtaskStatus.RUNNING, result = "In progress", orderIndex = 1),
            Subtask(id = "sub-3", description = "Research framework 2", status = SubtaskStatus.PENDING, orderIndex = 2)
        )
        val originalPlan = TaskPlan(
            sessionId = "persisted-session-123",
            goal = "Research three Android LLM frameworks, compare them, and create a report.",
            subtasks = subtasks,
            currentSubtaskId = "sub-2",
            maxSteps = 15,
            stepCount = 4,
            isCompleted = false
        )

        // Save plan
        repository.savePlan(originalPlan)

        // Load plan
        val restored = repository.getPlan("persisted-session-123")
        assertNotNull("Restored plan should not be null", restored)
        assertEquals("persisted-session-123", restored!!.sessionId)
        assertEquals(originalPlan.goal, restored.goal)
        assertEquals(15, restored.maxSteps)
        assertEquals(4, restored.stepCount)
        assertEquals("sub-2", restored.currentSubtaskId)
        assertEquals(3, restored.subtasks.size)

        // Check subtasks and order
        assertEquals("sub-1", restored.subtasks[0].id)
        assertEquals("Find relevant frameworks", restored.subtasks[0].description)
        assertEquals(SubtaskStatus.COMPLETED, restored.subtasks[0].status)
        assertEquals("Found 3 frameworks", restored.subtasks[0].result)

        assertEquals("sub-2", restored.subtasks[1].id)
        assertEquals("Research framework 1", restored.subtasks[1].description)
        assertEquals(SubtaskStatus.RUNNING, restored.subtasks[1].status)

        assertEquals("sub-3", restored.subtasks[2].id)
        assertEquals("Research framework 2", restored.subtasks[2].description)
        assertEquals(SubtaskStatus.PENDING, restored.subtasks[2].status)
    }

    @Test
    fun `test agent session persists and restores with attached task plan`() = runBlocking {
        val plan = TaskPlan(
            sessionId = "session-full-cycle",
            goal = "Automate Android reports",
            subtasks = listOf(
                Subtask(id = "step-1", description = "Decompose tasks", status = SubtaskStatus.COMPLETED, orderIndex = 0),
                Subtask(id = "step-2", description = "Run diagnostics", status = SubtaskStatus.COMPLETED, orderIndex = 1)
            ),
            isCompleted = true
        )

        val session = AgentSession(
            id = "session-full-cycle",
            goal = "Automate Android reports",
            status = AgentTaskStatus.COMPLETED,
            plan = plan
        )

        repository.saveAgentSession(session)

        val restoredSession = repository.getAgentSession("session-full-cycle")
        assertNotNull(restoredSession)
        assertEquals("session-full-cycle", restoredSession!!.id)
        assertEquals(AgentTaskStatus.COMPLETED, restoredSession.status)
        assertNotNull(restoredSession.plan)
        assertTrue(restoredSession.plan!!.isCompleted)
        assertEquals(2, restoredSession.plan!!.subtasks.size)
        assertEquals("Decompose tasks", restoredSession.plan!!.subtasks[0].description)
    }

    @Test
    fun `test updating subtask status persists to Room database`() = runBlocking {
        val planner = TaskPlanner()
        var plan = planner.createInitialPlan("session-transition", "Compile report")
        repository.savePlan(plan)

        val active = plan.activeSubtask!!
        // Record tool success
        plan = planner.recordToolSuccess(plan, active.id, "file_read", "Read 50 lines")
        repository.savePlan(plan)

        var loaded = repository.getPlan("session-transition")!!
        var targetSubtask = loaded.subtasks.find { it.id == active.id }!!
        assertEquals(SubtaskStatus.RUNNING, targetSubtask.status)
        assertTrue(targetSubtask.result!!.contains("file_read"))

        // Verify and complete
        plan = planner.verifyAndCompleteSubtask(plan, active.id, "Report data confirmed")
        repository.savePlan(plan)

        loaded = repository.getPlan("session-transition")!!
        targetSubtask = loaded.subtasks.find { it.id == active.id }!!
        assertEquals(SubtaskStatus.COMPLETED, targetSubtask.status)
        assertEquals("Report data confirmed", targetSubtask.result)
    }
}
