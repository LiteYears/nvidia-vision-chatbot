package com.example.agent

import com.example.agent.plan.SubtaskStatus
import com.example.agent.plan.TaskPlanner
import com.example.agent.python.EmbeddedPythonRuntime
import com.example.agent.tools.ToolCallParser
import com.example.agent.tools.workspace.AgentWorkspaceManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class WordDocumentAndFailureRecoveryTest {

    private lateinit var tempDir: File
    private lateinit var workspaceManager: AgentWorkspaceManager
    private lateinit var pythonRuntime: EmbeddedPythonRuntime
    private lateinit var planner: TaskPlanner

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("agent_test_ws").toFile()
        workspaceManager = AgentWorkspaceManager.init(tempDir)
        pythonRuntime = EmbeddedPythonRuntime(workspaceManager)
        planner = TaskPlanner()
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun `test python docx module generates valid word document with cool design`() {
        val pythonCode = """
            from docx import Document
            from docx.shared import Inches, Pt, RGBColor
            from docx.enum.text import WD_ALIGN_PARAGRAPH

            doc = Document()
            doc.add_heading("Cool Modern Report", level=1)
            doc.add_paragraph("This is mock text designed for testing Word file generation.")
            doc.add_paragraph("Another paragraph with key takeaways and summary.")
            doc.save("cool_design.docx")
        """.trimIndent()

        val result = pythonRuntime.execute(
            code = pythonCode,
            filename = "generate_doc.py",
            timeoutMs = 5000L
        )

        assertEquals("Python script should exit 0. Stderr: ${result.stderr}", 0, result.exitCode)
        val generatedFile = File(workspaceManager.getWorkspaceDir(), "cool_design.docx")
        assertTrue("Generated .docx file must exist on disk", generatedFile.exists())
        assertTrue("Generated .docx file must have non-zero size", generatedFile.length() > 500)

        val zip = java.util.zip.ZipFile(generatedFile)
        val entryNames = zip.entries().asSequence().map { it.name }.toSet()
        assertTrue(entryNames.contains("[Content_Types].xml"))
        assertTrue(entryNames.contains("_rels/.rels"))
        assertTrue(entryNames.contains("word/document.xml"))
        assertTrue(entryNames.contains("word/styles.xml"))
        zip.close()
    }

    @Test
    fun `test python zipfile module allows custom docx packaging`() {
        val pythonCode = """
            import zipfile

            with zipfile.ZipFile("test_package.docx", "w") as z:
                z.writestr("[Content_Types].xml", "<Types/>")
                z.writestr("_rels/.rels", "<Relationships/>")
                z.writestr("word/document.xml", "<w:document><w:body><w:p><w:r><w:t>Hello</w:t></w:r></w:p></w:body></w:document>")

            print("Packaged successfully")
        """.trimIndent()

        val result = pythonRuntime.execute(
            code = pythonCode,
            filename = "package_doc.py"
        )

        assertEquals("Zip packaging should exit 0", 0, result.exitCode)
        assertTrue(result.stdout.contains("Packaged successfully"))
        val pkg = File(workspaceManager.getWorkspaceDir(), "test_package.docx")
        assertTrue(pkg.exists())
    }

    @Test
    fun `test failure does not abort task and allows repair cycle`() {
        val goal = "Create me a Word file that has some mock text but a cool design."
        var plan = planner.createInitialPlan("session-word-doc", goal)

        // 1. Initial inspection
        plan = planner.advanceSubtaskOnToolSuccess(plan, plan.activeSubtask?.id, "run_command", "Command: ls -la\nExit Code: 0\n[stdout]\ntotal 0")

        // 2. Setup packages (apt/pip) - does NOT prematurely complete core execution subtask!
        plan = planner.advanceSubtaskOnToolSuccess(plan, plan.activeSubtask?.id, "run_command", "Command: pip install python-docx\nExit Code: 0\nSuccessfully installed")

        // Active subtask remains pending or running for core execution
        assertTrue("Action subtasks must still be pending", planner.hasPendingActionSubtasks(plan))

        // 3. Initial script run produces error (exit code 1)
        val activeId = plan.activeSubtask?.id
        val stderrTrace = "Traceback (most recent call last):\n  File 'generate_doc.py', line 5\n    doc.add_header('Invalid')\nAttributeError: 'Document' object has no attribute 'add_header'"
        plan = planner.recordToolFailure(plan, activeId, "run_command", stderrTrace)

        // Verifies failure was recorded with diagnostic and subtask is ready for repair
        val failedSubtask = plan.subtasks.find { it.id == activeId }!!
        assertEquals(SubtaskStatus.FAILED, failedSubtask.status)
        assertEquals(1, failedSubtask.retryCount)
        assertTrue("Subtask must record error diagnostic", failedSubtask.failureReason!!.contains("AttributeError"))

        // Task is NOT terminated! It still has pending action subtasks
        assertTrue("Failed subtask must keep pending action status for repair", planner.hasPendingActionSubtasks(plan))

        // 4. Agent repairs the script via bash heredoc
        val repairCmd = "Command: cat << 'EOF' > generate_doc.py\nfrom docx import Document\ndoc = Document()\ndoc.add_heading('Repaired', 1)\ndoc.save('doc.docx')\nEOF\nExit Code: 0"
        plan = planner.advanceSubtaskOnToolSuccess(plan, activeId, "run_command", repairCmd)

        // Verifies that repairing the code resets the failed subtask to RUNNING!
        val repairedSubtask = plan.subtasks.find { it.id == activeId }!!
        assertEquals("Code repair must reset subtask to RUNNING for re-execution", SubtaskStatus.RUNNING, repairedSubtask.status)

        // 5. Re-executing python script succeeds
        val reRunCmd = "Command: python3 generate_doc.py\nExit Code: 0\n(Command executed successfully with no output)"
        plan = planner.advanceSubtaskOnToolSuccess(plan, activeId, "run_command", reRunCmd)
        assertEquals("Successful re-execution must complete subtask", SubtaskStatus.COMPLETED, plan.subtasks.find { it.id == activeId }!!.status)
    }

    @Test
    fun `test tool call parser handles bash block and native tool calls without crashing on null`() {
        val nullCall = ToolCallParser.parse("null")
        assertEquals(null, nullCall)

        val bashBlock = """
            I will generate the document now:
            ```bash
            python3 generate_doc.py
            ```
        """.trimIndent()
        val parsedBash = ToolCallParser.parse(bashBlock)
        assertNotNull(parsedBash)
        assertEquals("bash", parsedBash?.toolName)
        assertEquals("python3 generate_doc.py", parsedBash?.arguments?.get("command"))

        val nativeToolCall = """
            ```tool_call
            {"tool": "run_command", "arguments": {"command": "python3 generate_doc.py"}}
            ```
        """.trimIndent()
        val parsedNative = ToolCallParser.parse(nativeToolCall)
        assertNotNull(parsedNative)
        assertEquals("run_command", parsedNative?.toolName)
        assertEquals("python3 generate_doc.py", parsedNative?.arguments?.get("command"))
    }
}
