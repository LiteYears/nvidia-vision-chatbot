package com.example.agent.tools

import com.example.agent.tools.workspace.AgentWorkspaceManager
import com.example.agent.tools.workspace.FileDeleteTool
import com.example.agent.tools.workspace.FileListTool
import com.example.agent.tools.workspace.FileReadTool
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

class WorkspaceFileToolsTest {

    private lateinit var tempBaseDir: File
    private lateinit var workspaceManager: AgentWorkspaceManager
    private lateinit var listTool: FileListTool
    private lateinit var readTool: FileReadTool
    private lateinit var writeTool: FileWriteTool
    private lateinit var deleteTool: FileDeleteTool
    private lateinit var registry: ToolRegistry

    @Before
    fun setUp() {
        tempBaseDir = Files.createTempDirectory("agent_workspace_test_").toFile()
        workspaceManager = AgentWorkspaceManager(tempBaseDir)
        workspaceManager.activeSessionId = "session_test_1"

        listTool = FileListTool(workspaceManager)
        readTool = FileReadTool(workspaceManager)
        writeTool = FileWriteTool(workspaceManager)
        deleteTool = FileDeleteTool(workspaceManager)

        registry = ToolRegistry.defaultRegistry(workspaceManager)
    }

    @After
    fun tearDown() {
        tempBaseDir.deleteRecursively()
    }

    @Test
    fun testFileWriteAndRead() = runBlocking {
        // Write a simple file
        val writeResult = writeTool.execute(
            mapOf(
                "path" to "hello.txt",
                "content" to "Hello Nvidia Vision Agent!"
            )
        )
        assertTrue(writeResult.isSuccess)
        assertTrue(writeResult.result?.contains("Successfully written") == true)

        // Read it back
        val readResult = readTool.execute(mapOf("path" to "hello.txt"))
        assertTrue(readResult.isSuccess)
        assertEquals("Hello Nvidia Vision Agent!", readResult.result)
    }

    @Test
    fun testFileWriteCreatesMissingParentDirectories() = runBlocking {
        // Writing to a deeply nested path that does not exist yet
        val deepPath = "src/modules/pipeline/config.json"
        val jsonContent = "{\"model\": \"nvidia/vision\", \"version\": 1.0}"

        val writeResult = writeTool.execute(
            mapOf(
                "path" to deepPath,
                "content" to jsonContent
            )
        )
        assertTrue(writeResult.isSuccess)

        // Read it back from deep path
        val readResult = readTool.execute(mapOf("path" to deepPath))
        assertTrue(readResult.isSuccess)
        assertEquals(jsonContent, readResult.result)

        // Verify underlying file system structure
        val resolved = workspaceManager.resolvePath(deepPath)
        assertTrue(resolved.exists())
        assertTrue(resolved.isFile)
        assertTrue(resolved.parentFile!!.exists())
    }

    @Test
    fun testFileWriteAppend() = runBlocking {
        val path = "log.txt"
        writeTool.execute(mapOf("path" to path, "content" to "Line 1\n"))
        writeTool.execute(mapOf("path" to path, "content" to "Line 2\n", "append" to true))
        writeTool.execute(mapOf("path" to path, "content" to "Line 3", "append" to true))

        val readResult = readTool.execute(mapOf("path" to path))
        assertTrue(readResult.isSuccess)
        assertEquals("Line 1\nLine 2\nLine 3", readResult.result)
    }

    @Test
    fun testFileListRootAndSubdirectories() = runBlocking {
        // Empty directory test
        val emptyListResult = listTool.execute(mapOf("path" to "."))
        assertTrue(emptyListResult.isSuccess)
        assertTrue(emptyListResult.result?.contains("empty") == true)

        // Populate workspace with files and subdirs
        writeTool.execute(mapOf("path" to "readme.md", "content" to "# Project"))
        writeTool.execute(mapOf("path" to "docs/architecture.md", "content" to "Architecture details"))
        writeTool.execute(mapOf("path" to "docs/api/endpoints.txt", "content" to "GET /v1/chat"))

        // Non-recursive listing of root
        val rootList = listTool.execute(mapOf("path" to ".", "recursive" to false))
        assertTrue(rootList.isSuccess)
        val rootOutput = rootList.result ?: ""
        assertTrue(rootOutput.contains("readme.md"))
        assertTrue(rootOutput.contains("docs"))

        // Recursive listing
        val recursiveList = listTool.execute(mapOf("path" to ".", "recursive" to true))
        assertTrue(recursiveList.isSuccess)
        val recursiveOutput = recursiveList.result ?: ""
        assertTrue(recursiveOutput.contains("readme.md"))
        assertTrue(recursiveOutput.contains("docs/architecture.md"))
        assertTrue(recursiveOutput.contains("docs/api/endpoints.txt"))
    }

    @Test
    fun testFileReadSizeLimits() = runBlocking {
        // Create 2000-byte file
        val bigContent = "A".repeat(2000)
        writeTool.execute(mapOf("path" to "big.txt", "content" to bigContent))

        // Read with max_bytes = 512 (which is the min clamp)
        val readResult = readTool.execute(
            mapOf(
                "path" to "big.txt",
                "max_bytes" to 512
            )
        )
        assertTrue(readResult.isSuccess)
        val output = readResult.result ?: ""
        assertTrue(output.contains("[Warning: Output truncated at 512 bytes. Total file size is 2000 bytes.]"))
        assertTrue(output.startsWith("A".repeat(512)))
    }

    @Test
    fun testFileReadMissingAndDirectory() = runBlocking {
        // Missing file
        val missingResult = readTool.execute(mapOf("path" to "non_existent.txt"))
        assertFalse(missingResult.isSuccess)
        assertTrue(missingResult.error?.contains("File not found") == true)

        // Empty path
        val emptyPathResult = readTool.execute(mapOf("path" to ""))
        assertFalse(emptyPathResult.isSuccess)
        assertTrue(emptyPathResult.error?.contains("Missing or empty required parameter") == true)

        // Attempting to read a directory as a file
        writeTool.execute(mapOf("path" to "subfolder/test.txt", "content" to "data"))
        val dirReadResult = readTool.execute(mapOf("path" to "subfolder"))
        assertFalse(dirReadResult.isSuccess)
        assertTrue(dirReadResult.error?.contains("is a directory, not a file") == true)
    }

    @Test
    fun testFileDeleteFileAndDirectory() = runBlocking {
        writeTool.execute(mapOf("path" to "to_delete.txt", "content" to "delete me"))
        writeTool.execute(mapOf("path" to "dir_to_delete/nested.txt", "content" to "nested content"))

        // Delete single file
        val deleteFileResult = deleteTool.execute(mapOf("path" to "to_delete.txt"))
        assertTrue(deleteFileResult.isSuccess)
        assertFalse(workspaceManager.resolvePath("to_delete.txt").exists())

        // Non-empty directory without recursive flag should fail
        val deleteDirFail = deleteTool.execute(mapOf("path" to "dir_to_delete", "recursive" to false))
        assertFalse(deleteDirFail.isSuccess)
        assertTrue(deleteDirFail.error?.contains("not empty") == true)

        // Non-empty directory with recursive flag should succeed
        val deleteDirSuccess = deleteTool.execute(mapOf("path" to "dir_to_delete", "recursive" to true))
        assertTrue(deleteDirSuccess.isSuccess)
        assertFalse(workspaceManager.resolvePath("dir_to_delete").exists())

        // Deleting non-existent file should fail
        val deleteMissing = deleteTool.execute(mapOf("path" to "ghost.txt"))
        assertFalse(deleteMissing.isSuccess)
        assertTrue(deleteMissing.error?.contains("does not exist") == true)
    }

    @Test
    fun testCannotDeleteWorkspaceRoot() = runBlocking {
        val deleteRoot1 = deleteTool.execute(mapOf("path" to "."))
        assertFalse(deleteRoot1.isSuccess)
        assertTrue(deleteRoot1.error?.contains("Cannot delete the workspace root directory") == true)

        val deleteRoot2 = deleteTool.execute(mapOf("path" to ""))
        assertFalse(deleteRoot2.isSuccess)
    }

    @Test
    fun testCannotWriteToWorkspaceRootDirectly() = runBlocking {
        val writeRootResult = writeTool.execute(mapOf("path" to ".", "content" to "bad"))
        assertFalse(writeRootResult.isSuccess)
        assertTrue(writeRootResult.error?.contains("Cannot write content to workspace root directory") == true)
    }

    @Test
    fun testPathTraversalEscapesAreBlocked() = runBlocking {
        val traversalPayloads = listOf(
            "../outside.txt",
            "../../outside.txt",
            "../../../etc/passwd",
            "/etc/passwd",
            "nested/../../../../etc/shadow",
            "sub/../../../../secret.key",
            "..\\..\\outside.txt"
        )

        for (payload in traversalPayloads) {
            // Test resolvePath directly throws SecurityException
            var threwSecurityException = false
            try {
                val resolved = workspaceManager.resolvePath(payload)
                // If it didn't throw, it MUST be strictly inside the workspace root (e.g. leading slashes stripped)
                val root = workspaceManager.getWorkspaceDir()
                val isInside = resolved.canonicalPath == root.canonicalPath ||
                    resolved.canonicalPath.startsWith(root.canonicalPath + File.separator)
                assertTrue("Target must be inside workspace: ${resolved.canonicalPath}", isInside)
            } catch (_: SecurityException) {
                threwSecurityException = true
            }

            // Test through write tool - must never write outside workspace
            val writeRes = writeTool.execute(mapOf("path" to payload, "content" to "malicious"))
            if (!writeRes.isSuccess) {
                // If it failed, it must be either security error or failed
                assertNotNull(writeRes.error)
            } else {
                // If it succeeded, it must have been safely stripped inside the workspace
                val resolved = workspaceManager.resolvePath(payload)
                assertTrue(resolved.canonicalPath.startsWith(workspaceManager.getWorkspaceDir().canonicalPath))
            }

            // Test through read tool
            val readRes = readTool.execute(mapOf("path" to payload))
            if (!readRes.isSuccess) {
                assertNotNull(readRes.error)
            }

            // Test through delete tool
            val deleteRes = deleteTool.execute(mapOf("path" to payload))
            if (!deleteRes.isSuccess) {
                assertNotNull(deleteRes.error)
            }
        }
    }

    @Test
    fun testNullByteInjectionBlocked() = runBlocking {
        var threwSecurity = false
        try {
            workspaceManager.resolvePath("file.txt\u0000.evil")
        } catch (_: SecurityException) {
            threwSecurity = true
        }
        assertTrue("Null byte in path must throw SecurityException", threwSecurity)

        val writeRes = writeTool.execute(mapOf("path" to "file.txt\u0000.evil", "content" to "test"))
        assertFalse(writeRes.isSuccess)
        assertTrue(writeRes.error?.contains("null byte", ignoreCase = true) == true)
    }

    @Test
    fun testWorkspaceIsolationBetweenSessions() = runBlocking {
        // Session 1 writes its data
        workspaceManager.activeSessionId = "session_A"
        writeTool.execute(mapOf("path" to "data_a.txt", "content" to "Data for session A"))

        // Switch to Session 2
        workspaceManager.activeSessionId = "session_B"
        writeTool.execute(mapOf("path" to "data_b.txt", "content" to "Data for session B"))

        // Session 2 should not see Session A's file
        val readFromB = readTool.execute(mapOf("path" to "data_a.txt"))
        assertFalse(readFromB.isSuccess)
        assertTrue(readFromB.error?.contains("File not found") == true)

        // Session 2 can see its own file
        val readFromB2 = readTool.execute(mapOf("path" to "data_b.txt"))
        assertTrue(readFromB2.isSuccess)
        assertEquals("Data for session B", readFromB2.result)

        // Switch back to Session 1
        workspaceManager.activeSessionId = "session_A"

        // Session 1 should not see Session B's file
        val readFromA = readTool.execute(mapOf("path" to "data_b.txt"))
        assertFalse(readFromA.isSuccess)
        assertTrue(readFromA.error?.contains("File not found") == true)

        // Session 1 still retains its original file (persistent!)
        val readFromA2 = readTool.execute(mapOf("path" to "data_a.txt"))
        assertTrue(readFromA2.isSuccess)
        assertEquals("Data for session A", readFromA2.result)
    }

    @Test
    fun testToolRegistryIntegration() = runBlocking {
        // Ensure all 4 tools are registered in default registry
        assertNotNull(registry.getTool("file_list"))
        assertNotNull(registry.getTool("file_read"))
        assertNotNull(registry.getTool("file_write"))
        assertNotNull(registry.getTool("file_delete"))

        // Execute via ToolCall through ToolRegistry
        val writeCall = ToolCall(
            callId = "call-1",
            toolName = "file_write",
            arguments = mapOf("path" to "test_registry.txt", "content" to "Executed via registry!")
        )
        val writeResult = registry.execute(writeCall)
        assertTrue(writeResult.isSuccess)

        val readCall = ToolCall(
            callId = "call-2",
            toolName = "file_read",
            arguments = mapOf("path" to "test_registry.txt")
        )
        val readResult = registry.execute(readCall)
        assertTrue(readResult.isSuccess)
        assertEquals("Executed via registry!", readResult.result)

        // Definitions include parameters and descriptions
        val listDef = registry.getTool("file_list")!!.definition
        assertEquals("file_list", listDef.name)
        assertTrue(listDef.parameters.any { it.name == "path" })
        assertTrue(listDef.parameters.any { it.name == "recursive" })

        val promptStr = registry.formatToolsForPrompt()
        assertTrue(promptStr.contains("file_list"))
        assertTrue(promptStr.contains("file_read"))
        assertTrue(promptStr.contains("file_write"))
        assertTrue(promptStr.contains("file_delete"))
    }
}
