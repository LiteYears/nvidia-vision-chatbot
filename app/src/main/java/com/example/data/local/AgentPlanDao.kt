package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface AgentPlanDao {

    @Query("SELECT * FROM agent_plans WHERE sessionId = :sessionId LIMIT 1")
    fun getPlanFlow(sessionId: String): Flow<AgentPlanEntity?>

    @Query("SELECT * FROM agent_plans WHERE sessionId = :sessionId LIMIT 1")
    suspend fun getPlanBySessionIdSync(sessionId: String): AgentPlanEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdatePlan(plan: AgentPlanEntity)

    @Query("DELETE FROM agent_plans WHERE sessionId = :sessionId")
    suspend fun deletePlan(sessionId: String)

    @Query("SELECT * FROM agent_plans ORDER BY updatedAt DESC")
    fun getAllPlansFlow(): Flow<List<AgentPlanEntity>>

    @Query("SELECT * FROM agent_plans ORDER BY updatedAt DESC")
    suspend fun getAllPlansSync(): List<AgentPlanEntity>

    // Session persistence
    @Query("SELECT * FROM agent_sessions ORDER BY updatedAt DESC")
    fun getAllSessionsFlow(): Flow<List<AgentSessionEntity>>

    @Query("SELECT * FROM agent_sessions ORDER BY updatedAt DESC")
    suspend fun getAllSessionsSync(): List<AgentSessionEntity>

    @Query("SELECT * FROM agent_sessions WHERE id = :id LIMIT 1")
    suspend fun getSessionByIdSync(id: String): AgentSessionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdateSession(session: AgentSessionEntity)

    @Query("DELETE FROM agent_sessions WHERE id = :id")
    suspend fun deleteSession(id: String)

    @Query("DELETE FROM agent_plans")
    suspend fun deleteAllPlans()

    @Query("DELETE FROM agent_sessions")
    suspend fun deleteAllSessions()
}
