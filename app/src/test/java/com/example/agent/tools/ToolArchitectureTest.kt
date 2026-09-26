package com.example.agent.tools

import com.example.agent.tools.builtin.CalculatorTool
import com.example.data.model.AgentTaskStatus
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ToolArchitectureTest {

    private lateinit var registry: ToolRegistry

    @Before
    fun setUp() {
        registry = ToolRegistry.defaultRegistry()
    }

    @Test
    fun testToolDiscovery() {
        val tools = registry.getAllTools()
        assertTrue("Tool registry should contain at least 1 tool", tools.isNotEmpty())

        val calcTool = registry.getTool("calculator")
        assertNotNull("Calculator tool must be discoverable", calcTool)
        assertEquals("calculator", calcTool?.definition?.name)

        val definitions = registry.getToolDefinitions()
        assertTrue(definitions.any { it.name == "calculator" })
        assertTrue(definitions.any { it.name == "web_search" })

        val promptBlock = registry.formatToolsForPrompt()
        assertTrue(promptBlock.contains("### AVAILABLE TOOLS"))
        assertTrue(promptBlock.contains("calculator"))
        assertTrue(promptBlock.contains("web_search"))
        assertTrue(promptBlock.contains("expression"))
        assertTrue(promptBlock.contains("query"))
        assertTrue(promptBlock.contains("TOOL CALL PROTOCOL"))
    }

    @Test
    fun testCalculatorExecutionSuccess() = runBlocking {
        val calcTool = registry.getTool("calculator")!!

        // Basic arithmetic
        val result1 = calcTool.execute(mapOf("expression" to "25 * 4 + 10"))
        assertTrue(result1.isSuccess)
        assertEquals("110", result1.result)
        assertNull(result1.error)

        // Decimal and division
        val result2 = calcTool.execute(mapOf("expression" to "10 / 4"))
        assertTrue(result2.isSuccess)
        assertEquals("2.5", result2.result)

        // Functions: sqrt and powers
        val result3 = calcTool.execute(mapOf("expression" to "sqrt(144) + 2^3"))
        assertTrue(result3.isSuccess)
        assertEquals("20", result3.result)
    }

    @Test
    fun testCalculatorExecutionFailure() = runBlocking {
        val calcTool = registry.getTool("calculator")!!

        // Division by zero
        val result1 = calcTool.execute(mapOf("expression" to "100 / 0"))
        assertFalse(result1.isSuccess)
        assertNotNull(result1.error)
        assertTrue(result1.error!!.contains("Division by zero", ignoreCase = true))

        // Missing argument
        val result2 = calcTool.execute(emptyMap())
        assertFalse(result2.isSuccess)
        assertNotNull(result2.error)
        assertTrue(result2.error!!.contains("Missing required parameter", ignoreCase = true))

        // Syntax error
        val result3 = calcTool.execute(mapOf("expression" to "25 +* 4"))
        assertFalse(result3.isSuccess)
        assertNotNull(result3.error)
    }

    @Test
    fun testToolCallParsing() {
        val sampleLlmOutput = """
            I will calculate the required latency budget:
            ```tool_call
            {
              "tool": "calculator",
              "arguments": {
                "expression": "1000 / 60"
              }
            }
            ```
            This gives the per-frame target in milliseconds.
        """.trimIndent()

        val toolCall = ToolCallParser.parse(sampleLlmOutput)
        assertNotNull("ToolCall should be parsed from markdown code block", toolCall)
        assertEquals("calculator", toolCall?.toolName)
        assertEquals("1000 / 60", toolCall?.arguments?.get("expression"))

        val stripped = ToolCallParser.stripToolCalls(sampleLlmOutput)
        assertFalse(stripped.contains("```tool_call"))
        assertTrue(stripped.contains("I will calculate the required latency budget:"))
    }

    @Test
    fun testExtensibleModularity() = runBlocking {
        // Create and register a custom tool dynamically without changing agent core
        val mockTool = object : AgentTool {
            override val definition = ToolDefinition(
                name = "text_analyzer",
                description = "Counts words in a string",
                parameters = listOf(ToolParameter("text", "string", "Input text", true))
            )

            override suspend fun execute(arguments: Map<String, Any?>): ToolResult {
                val text = arguments["text"]?.toString() ?: ""
                val count = text.split(Regex("\\s+")).count { it.isNotBlank() }
                return ToolResult.success("test-id", definition.name, count.toString())
            }
        }

        registry.register(mockTool)

        val retrieved = registry.getTool("text_analyzer")
        assertNotNull(retrieved)

        val toolCall = ToolCall(
            toolName = "text_analyzer",
            arguments = mapOf("text" to "NVIDIA Vision Agent Architecture Foundation")
        )
        val result = registry.execute(toolCall)
        assertTrue(result.isSuccess)
        assertEquals("5", result.result)

        // Clean unregister
        registry.unregister("text_analyzer")
        assertNull(registry.getTool("text_analyzer"))
    }

    @Test
    fun testCompleteToolExecutionAndFeedbackFlow() = runBlocking {
        // 1. Tool discovery
        val availablePrompt = registry.formatToolsForPrompt()
        assertTrue(availablePrompt.contains("calculator"))

        // 2. Request tool with structured arguments
        val agentRawOutput = """
            To determine the horizontal resolution downscale, I need to divide 3840 by 2:
            ```tool_call
            {
              "tool": "calculator",
              "arguments": {
                "expression": "3840 / 2"
              }
            }
            ```
        """.trimIndent()

        val toolCall = ToolCallParser.parse(agentRawOutput)
        assertNotNull(toolCall)
        assertEquals("calculator", toolCall?.toolName)
        assertEquals("3840 / 2", toolCall?.arguments?.get("expression"))

        // 3. Execute requested tool safely
        val result = registry.execute(toolCall!!)

        // 4. Receive structured result
        assertTrue(result.isSuccess)
        assertEquals("1920", result.result)
        assertNull(result.error)

        // 5. Report success or failure back to the agent
        val feedbackContent = "[TOOL_RESULT: ${result.toolName}]\n" +
            "Status: ${if (result.isSuccess) "SUCCESS" else "FAILURE"}\n" +
            "Result: ${result.result}\n" +
            "Please formulate your final response to the user incorporating this tool result."

        assertTrue(feedbackContent.contains("[TOOL_RESULT: calculator]"))
        assertTrue(feedbackContent.contains("Status: SUCCESS"))
        assertTrue(feedbackContent.contains("Result: 1920"))
    }

    @Test
    fun testMultiStepAutonomousToolLoop() = runBlocking {
        // Track the progression of states: THINKING -> USING_TOOL -> OBSERVING -> THINKING -> USING_TOOL -> OBSERVING -> THINKING -> COMPLETED
        val stateHistory = mutableListOf<AgentTaskStatus>()

        // Simulated multi-step AI model responses:
        // Turn 1: Model requests pixel count: 3840 * 2160
        // Turn 2: Model requests memory in MB: 8294400 * 4 / 1048576
        // Turn 3: Model provides final answer without tool calls
        val simulatedTurnResponses = listOf(
            """```tool_call
            {
              "tool": "calculator",
              "arguments": { "expression": "3840 * 2160" }
            }
            ```""".trimIndent(),
            """```tool_call
            {
              "tool": "calculator",
              "arguments": { "expression": "8294400 * 4 / 1048576" }
            }
            ```""".trimIndent(),
            "A 4K frame has 8,294,400 pixels and requires approximately 31.64 MB uncompressed."
        )

        val executedTools = mutableListOf<ToolResult>()
        var turnIndex = 0
        var isLoopComplete = false

        while (!isLoopComplete && turnIndex < simulatedTurnResponses.size) {
            stateHistory.add(AgentTaskStatus.THINKING)
            val currentResponse = simulatedTurnResponses[turnIndex++]
            val toolCall = ToolCallParser.parse(currentResponse)

            if (toolCall != null) {
                stateHistory.add(AgentTaskStatus.USING_TOOL)
                val toolResult = registry.execute(toolCall)
                executedTools.add(toolResult)

                stateHistory.add(AgentTaskStatus.OBSERVING)
                // Result is fed back to model for the next turn
                assertTrue(toolResult.isSuccess)
            } else {
                stateHistory.add(AgentTaskStatus.COMPLETED)
                isLoopComplete = true
            }
        }

        // Verify loop completed all steps
        assertTrue("Loop should terminate when final answer has no tool call", isLoopComplete)
        assertEquals(2, executedTools.size)
        assertEquals("8294400", executedTools[0].result)
        assertEquals("31.640625", executedTools[1].result)

        // Verify full state progression sequence
        val expectedStates = listOf(
            AgentTaskStatus.THINKING,
            AgentTaskStatus.USING_TOOL,
            AgentTaskStatus.OBSERVING,
            AgentTaskStatus.THINKING,
            AgentTaskStatus.USING_TOOL,
            AgentTaskStatus.OBSERVING,
            AgentTaskStatus.THINKING,
            AgentTaskStatus.COMPLETED
        )
        assertEquals(expectedStates, stateHistory)
    }

    @Test
    fun testMaxStepLimitPreventsInfiniteLoop() = runBlocking {
        val maxSteps = 4
        var executedCount = 0
        var loopActive = true

        val runawayModelResponse = """```tool_call
        {
          "tool": "calculator",
          "arguments": { "expression": "1 + 1" }
        }
        ```""".trimIndent()

        while (loopActive && executedCount < maxSteps) {
            val toolCall = ToolCallParser.parse(runawayModelResponse)
            if (toolCall != null) {
                executedCount++
                val result = registry.execute(toolCall)
                assertTrue(result.isSuccess)
            } else {
                loopActive = false
            }
        }

        // Verify loop halted strictly at maxSteps
        assertEquals(maxSteps, executedCount)
    }

    @Test
    fun testAgentTaskLifecycleStates() {
        assertEquals("Thinking", AgentTaskStatus.THINKING.displayName)
        assertEquals("Using Tool", AgentTaskStatus.USING_TOOL.displayName)
        assertEquals("Observing", AgentTaskStatus.OBSERVING.displayName)
        assertEquals("Completed", AgentTaskStatus.COMPLETED.displayName)
        assertEquals("Failed", AgentTaskStatus.FAILED.displayName)
    }
}
