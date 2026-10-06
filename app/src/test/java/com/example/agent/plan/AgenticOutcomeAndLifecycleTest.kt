package com.example.agent.plan

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.agent.service.AgentExecutionCoordinator
import com.example.data.local.ChatDatabase
import com.example.data.local.ChatMessageEntity
import com.example.data.model.AgentSession
import com.example.data.model.AgentTaskStatus
import com.example.data.preferences.SettingsManager
import com.example.data.repository.AgentPlanRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AgenticOutcomeAndLifecycleTest {

    private lateinit var context: Context
    private lateinit var database: ChatDatabase
    private lateinit var repository: AgentPlanRepository
    private lateinit var settingsManager: SettingsManager
    private lateinit var planner: TaskPlanner

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, ChatDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = AgentPlanRepository(database.agentPlanDao(), database.chatDao())
        settingsManager = SettingsManager(context)
        planner = TaskPlanner()
    }

    @After
    fun tearDown() {
        database.close()
        AgentExecutionCoordinator.clearActiveSession()
    }

    @Test
    fun `test terminal is hidden by default and persists when enabled`() {
        // Must be OFF by default (PART 3)
        assertFalse(settingsManager.isShowTerminal())

        // Enable setting
        settingsManager.setShowTerminal(true)
        assertTrue(settingsManager.isShowTerminal())

        // Disable setting
        settingsManager.setShowTerminal(false)
        assertFalse(settingsManager.isShowTerminal())
    }

    @Test
    fun `test outcome planner initializes desired outcome, completion criteria, and context snapshot`() {
        val goal = "Implement a custom user avatar cache with local persistence and verify performance"
        val plan = planner.createInitialPlan("session-outcome-1", goal)

        assertNotNull(plan)
        assertEquals("session-outcome-1", plan.sessionId)
        assertFalse(plan.isCompleted)
        assertTrue(plan.subtasks.isNotEmpty())

        // Plan for outcome, not mere activity
        assertTrue(plan.desiredOutcome.isNotBlank())
        assertTrue(plan.completionCriteria.isNotEmpty())
        assertTrue(plan.strategy.isNotBlank())

        // Verify task context snapshot is populated
        val snapshot = plan.contextSnapshot
        assertNotNull(snapshot)
        assertEquals(goal, snapshot!!.objective)
        assertEquals(plan.desiredOutcome, snapshot.desiredOutcome)
        assertTrue(snapshot.pendingObjectives.isNotEmpty())
        assertEquals("UNVERIFIED", snapshot.verificationStatus)

        // Compact prompt representation contains key outcome sections
        val prompt = snapshot.toCompactPrompt()
        assertTrue(prompt.contains("ORIGINAL OBJECTIVE"))
        assertTrue(prompt.contains("COMPLETION CRITERIA"))
        assertTrue(prompt.contains("CURRENT STATE"))
    }

    @Test
    fun `test failure recording captures failed approach to avoid loops`() {
        val goal = "Fix syntax error in script"
        var plan = planner.createInitialPlan("session-failure-1", goal)
        val active = plan.activeSubtask!!

        plan = planner.recordToolFailure(
            plan = plan,
            subtaskId = active.id,
            toolName = "python_execute",
            error = "IndentationError: unexpected indent on line 12"
        )

        // Must record in snapshot failed approaches
        val failed = plan.contextSnapshot?.failedApproaches
        assertNotNull(failed)
        assertTrue(failed!!.isNotEmpty())
        assertTrue(failed.any { it.approach.contains("python_execute") && it.reason.contains("IndentationError") })

        val prompt = plan.contextSnapshot!!.toCompactPrompt()
        assertTrue(prompt.contains("FAILED APPROACHES (DO NOT REPEAT)"))
    }

    @Test
    fun `test deterministic task deletion cleans database, coordinator, and leaves zero ghosts`() = runBlocking {
        val sessionId = "delete-test-session"
        val initialPlan = planner.createInitialPlan(sessionId, "Build offline cache")
        val session = AgentSession(
            id = sessionId,
            goal = "Build offline cache",
            status = AgentTaskStatus.IN_PROGRESS,
            plan = initialPlan
        )

        // 1. Save to Room
        repository.saveAgentSession(session)
        database.chatDao().insertConversation(
            com.example.data.local.ConversationEntity(
                id = sessionId,
                title = "Build offline cache",
                createdAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis(),
                modelName = "default-model",
                isIncognito = false
            )
        )
        database.chatDao().insertMessage(
            ChatMessageEntity(
                id = "msg-1",
                conversationId = sessionId,
                role = "user",
                content = "Build offline cache",
                imageBase64 = null,
                imageUri = null,
                timestamp = System.currentTimeMillis(),
                modelUsed = "default-model"
            )
        )

        // 2. Set as active in coordinator
        AgentExecutionCoordinator.setActiveSession(session)
        assertEquals(sessionId, AgentExecutionCoordinator.activeSession.value?.id)

        // Verify it exists in Room flow
        val savedList = repository.observeAllAgentSessions().first()
        assertEquals(1, savedList.size)
        assertEquals(sessionId, savedList.first().id)

        // 3. Perform deterministic deletion
        AgentExecutionCoordinator.clearSessionIf(sessionId)
        assertNull(AgentExecutionCoordinator.activeSession.value)

        repository.deleteAgentSession(sessionId)

        // 4. Verify completely purged from Room
        val remainingList = repository.observeAllAgentSessions().first()
        assertTrue(remainingList.isEmpty())

        assertNull(repository.getAgentSession(sessionId))
        assertNull(repository.getPlan(sessionId))

        // Associated chat messages also cleaned up
        val remainingMessages = database.chatDao().getMessagesForConversation(sessionId).first()
        assertTrue(remainingMessages.isEmpty())
    }
}
