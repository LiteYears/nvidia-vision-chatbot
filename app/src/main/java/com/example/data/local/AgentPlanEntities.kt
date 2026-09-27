package com.example.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.example.agent.plan.TaskPlan
import com.example.agent.plan.TaskPlanJsonAdapter
import com.example.data.model.AgentSession
import com.example.data.model.AgentTaskStatus

@Entity(tableName = "agent_plans")
data class AgentPlanEntity(
    @PrimaryKey val sessionId: String,
    val goal: String,
    val subtasksJson: String,
    val currentSubtaskId: String?,
    val stepCount: Int,
    val maxSteps: Int,
    val isCompleted: Boolean,
    val createdAt: Long,
    val updatedAt: Long
) {
    fun toDomain(): TaskPlan = TaskPlan(
        sessionId = sessionId,
        goal = goal,
        subtasks = TaskPlanJsonAdapter.deserializeSubtasks(subtasksJson),
        currentSubtaskId = currentSubtaskId,
        maxSteps = maxSteps,
        stepCount = stepCount,
        isCompleted = isCompleted,
        createdAt = createdAt,
        updatedAt = updatedAt
    )

    companion object {
        fun fromDomain(plan: TaskPlan): AgentPlanEntity = AgentPlanEntity(
            sessionId = plan.sessionId,
            goal = plan.goal,
            subtasksJson = TaskPlanJsonAdapter.serializeSubtasks(plan.subtasks),
            currentSubtaskId = plan.currentSubtaskId,
            stepCount = plan.stepCount,
            maxSteps = plan.maxSteps,
            isCompleted = plan.isCompleted,
            createdAt = plan.createdAt,
            updatedAt = plan.updatedAt
        )
    }
}

@Entity(tableName = "agent_sessions")
data class AgentSessionEntity(
    @PrimaryKey val id: String,
    val goal: String,
    val status: String,
    val createdAt: Long,
    val updatedAt: Long,
    val modelUsed: String
) {
    fun toDomain(plan: TaskPlan? = null): AgentSession = AgentSession(
        id = id,
        goal = goal,
        status = try {
            AgentTaskStatus.valueOf(status)
        } catch (e: Exception) {
            AgentTaskStatus.IN_PROGRESS
        },
        createdAt = createdAt,
        updatedAt = updatedAt,
        modelUsed = modelUsed,
        plan = plan
    )

    companion object {
        fun fromDomain(session: AgentSession): AgentSessionEntity = AgentSessionEntity(
            id = session.id,
            goal = session.goal,
            status = session.status.name,
            createdAt = session.createdAt,
            updatedAt = session.updatedAt,
            modelUsed = session.modelUsed
        )
    }
}
