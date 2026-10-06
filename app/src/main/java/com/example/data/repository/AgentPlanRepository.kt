package com.example.data.repository

import com.example.agent.plan.TaskPlan
import com.example.data.local.AgentPlanDao
import com.example.data.local.AgentPlanEntity
import com.example.data.local.AgentSessionEntity
import com.example.data.local.ChatDao
import com.example.data.model.AgentSession
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class AgentPlanRepository(
    private val agentPlanDao: AgentPlanDao,
    private val chatDao: ChatDao? = null
) {

    suspend fun savePlan(plan: TaskPlan) {
        agentPlanDao.insertOrUpdatePlan(AgentPlanEntity.fromDomain(plan))
    }

    suspend fun getPlan(sessionId: String): TaskPlan? {
        return agentPlanDao.getPlanBySessionIdSync(sessionId)?.toDomain()
    }

    fun observePlan(sessionId: String): Flow<TaskPlan?> {
        return agentPlanDao.getPlanFlow(sessionId).map { it?.toDomain() }
    }

    fun observeAllPlans(): Flow<List<TaskPlan>> {
        return agentPlanDao.getAllPlansFlow().map { entities ->
            entities.map { it.toDomain() }
        }
    }

    suspend fun saveAgentSession(session: AgentSession) {
        agentPlanDao.insertOrUpdateSession(AgentSessionEntity.fromDomain(session))
        session.plan?.let { plan ->
            agentPlanDao.insertOrUpdatePlan(AgentPlanEntity.fromDomain(plan))
        }
    }

    suspend fun getAgentSession(sessionId: String): AgentSession? {
        val sessionEntity = agentPlanDao.getSessionByIdSync(sessionId) ?: return null
        val plan = agentPlanDao.getPlanBySessionIdSync(sessionId)?.toDomain()
        return sessionEntity.toDomain(plan)
    }

    suspend fun getAllAgentSessions(): List<AgentSession> {
        val sessionEntities = agentPlanDao.getAllSessionsSync()
        return sessionEntities.map { entity ->
            val plan = agentPlanDao.getPlanBySessionIdSync(entity.id)?.toDomain()
            entity.toDomain(plan)
        }
    }

    fun observeAllAgentSessions(): Flow<List<AgentSession>> {
        return agentPlanDao.getAllSessionsFlow().map { sessionEntities ->
            sessionEntities.map { entity ->
                val plan = agentPlanDao.getPlanBySessionIdSync(entity.id)?.toDomain()
                entity.toDomain(plan)
            }
        }
    }

    suspend fun deleteAgentSession(sessionId: String) {
        agentPlanDao.deletePlan(sessionId)
        agentPlanDao.deleteSession(sessionId)
        try {
            chatDao?.deleteMessagesForConversation(sessionId)
        } catch (_: Exception) {}
    }
}
