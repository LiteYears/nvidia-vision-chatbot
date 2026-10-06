package com.example.agent.state

import com.example.agent.artifact.ArtifactDetector
import com.example.agent.tools.ToolCallParser
import com.example.agent.verification.TaskVerificationEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class AutonomousAgentArchitectureTest {

    @Test
    fun testStateMachineTransitions() {
        val machine = AgentStateMachine(initialState = AgentExecutionState.CREATED)
        assertEquals(AgentExecutionState.CREATED, machine.currentState)

        assertTrue(machine.transitionTo(AgentExecutionState.PLANNING, "Task initialized, breaking down objective"))
        assertEquals(AgentExecutionState.PLANNING, machine.currentState)

        assertTrue(machine.transitionTo(AgentExecutionState.READY_TO_ACT, "Parsed executable tool action"))
        assertEquals(AgentExecutionState.READY_TO_ACT, machine.currentState)

        assertTrue(machine.transitionTo(AgentExecutionState.EXECUTING, "Dispatched tool call"))
        assertEquals(AgentExecutionState.EXECUTING, machine.currentState)

        assertTrue(machine.transitionTo(AgentExecutionState.OBSERVING, "Tool execution completed with output"))
        assertEquals(AgentExecutionState.OBSERVING, machine.currentState)

        assertTrue(machine.transitionTo(AgentExecutionState.VERIFYING, "Checking artifacts and outputs"))
        assertEquals(AgentExecutionState.VERIFYING, machine.currentState)

        assertTrue(machine.transitionTo(AgentExecutionState.COMPLETED, "All deliverables verified and objective met"))
        assertEquals(AgentExecutionState.COMPLETED, machine.currentState)

        val history = machine.getHistory()
        assertEquals(6, history.size)
    }

    @Test
    fun testFlailingAndLoopDetection() {
        val machine = AgentStateMachine(initialState = AgentExecutionState.PLANNING)
        val action = "run_command: cat << EOF > generate.py"

        assertFalse(machine.recordActionAndCheckFlailing(action))
        assertFalse(machine.recordActionAndCheckFlailing(action))
        // 3rd time repeating identical action should trigger flailing
        assertTrue(machine.recordActionAndCheckFlailing(action))
    }

    @Test
    fun testToolCallParserRobustness() {
        // Natural language followed by markdown tool call
        val textWithTool = """
            I need to create a Word document now with cool design.
            ```tool_call
            {
              "tool": "file_write",
              "arguments": {
                "path": "test.txt",
                "content": "Hello World"
              }
            }
            ```
        """.trimIndent()
        val parsed = ToolCallParser.parse(textWithTool)
        assertNotNull(parsed)
        assertEquals("file_write", parsed?.toolName)
        assertEquals("test.txt", parsed?.arguments?.get("path"))

        // Pure bash block fallback
        val bashBlock = """
            Let's run the generator:
            ```bash
            python3 generate.py
            ```
        """.trimIndent()
        val parsedBash = ToolCallParser.parse(bashBlock)
        assertNotNull(parsedBash)
        assertEquals("bash", parsedBash?.toolName)
        assertEquals("python3 generate.py", parsedBash?.arguments?.get("command"))

        // CLI formatted invocation
        val cliInvocation = "file_write path=output.txt content=Hello there"
        val parsedCli = ToolCallParser.parse(cliInvocation)
        assertNotNull(parsedCli)
        assertEquals("file_write", parsedCli?.toolName)
    }

    @Test
    fun testArtifactDetectionAndVerification() {
        val tempDir = Files.createTempDirectory("test_artifacts").toFile()
        try {
            val file = File(tempDir, "sample.txt")
            file.writeText("Artifact content here")

            val detector = ArtifactDetector(tempDir)
            val artifacts = detector.scanWorkspace("test-task")
            assertTrue(artifacts.isNotEmpty())
            val sampleArtifact = artifacts.find { it.filename == "sample.txt" }
            assertNotNull(sampleArtifact)
            assertTrue(sampleArtifact!!.size > 0)

            val verificationEngine = TaskVerificationEngine()
            val result = verificationEngine.verifyFile(file)
            assertTrue(result.passed)
            assertEquals(1.0f, result.confidence, 0.01f)
        } finally {
            tempDir.deleteRecursively()
        }
    }
}
