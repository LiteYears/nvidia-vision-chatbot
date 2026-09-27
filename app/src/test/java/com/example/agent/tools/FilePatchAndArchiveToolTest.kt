package com.example.agent.tools

import com.example.agent.tools.workspace.AgentWorkspaceManager
import com.example.agent.tools.workspace.ArchiveExtractTool
import com.example.agent.tools.workspace.FilePatchTool
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
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class FilePatchAndArchiveToolTest {

    private lateinit var tempBaseDir: File
    private lateinit var workspaceManager: AgentWorkspaceManager
    private lateinit var patchTool: FilePatchTool
    private lateinit var archiveTool: ArchiveExtractTool
    private lateinit var writeTool: FileWriteTool
    private lateinit var readTool: FileReadTool
    private lateinit var registry: ToolRegistry

    @Before
    fun setUp() {
        tempBaseDir = Files.createTempDirectory("agent_patch_archive_test_").toFile()
        workspaceManager = AgentWorkspaceManager(tempBaseDir, autoInitStandardFolders = false)
        workspaceManager.activeSessionId = "session_patch_1"

        patchTool = FilePatchTool(workspaceManager)
        archiveTool = ArchiveExtractTool(workspaceManager)
        writeTool = FileWriteTool(workspaceManager)
        readTool = FileReadTool(workspaceManager)

        registry = ToolRegistry.defaultRegistry(workspaceManager)
    }

    @After
    fun tearDown() {
        tempBaseDir.deleteRecursively()
    }

    @Test
    fun testFilePatchExactReplacement() = runBlocking {
        // 1. Create a source file
        val initialCode = """
            def calculate_total(price, tax):
                return price + tax
        """.trimIndent()
        writeTool.execute(mapOf("path" to "src/calculator.py", "content" to initialCode))

        // 2. Patch a specific function line
        val patchResult = patchTool.execute(
            mapOf(
                "path" to "src/calculator.py",
                "target_content" to "return price + tax",
                "replacement_content" to "return round(price + (price * tax), 2)"
            )
        )

        assertTrue(patchResult.isSuccess)
        assertTrue(patchResult.result?.contains("Successfully patched") == true)

        // 3. Read back and verify
        val readResult = readTool.execute(mapOf("path" to "src/calculator.py"))
        assertTrue(readResult.isSuccess)
        val expected = """
            def calculate_total(price, tax):
                return round(price + (price * tax), 2)
        """.trimIndent()
        assertEquals(expected, readResult.result)
    }

    @Test
    fun testFilePatchSearchReplaceHunks() = runBlocking {
        // 1. Create source file
        val initialSource = """
            package main
            
            import "fmt"
            
            func main() {
                fmt.Println("Old message")
            }
        """.trimIndent()
        writeTool.execute(mapOf("path" to "main.go", "content" to initialSource))

        // 2. Patch with SEARCH / REPLACE block
        val hunkDiff = """
            <<<<<<< SEARCH
                fmt.Println("Old message")
            =======
                fmt.Println("New improved message")
            >>>>>>> REPLACE
        """.trimIndent()

        val patchResult = patchTool.execute(
            mapOf(
                "path" to "main.go",
                "diff" to hunkDiff
            )
        )

        assertTrue(patchResult.isSuccess)
        val readResult = readTool.execute(mapOf("path" to "main.go"))
        assertTrue(readResult.result?.contains("New improved message") == true)
        assertFalse(readResult.result?.contains("Old message") == true)
    }

    @Test
    fun testFilePatchUnifiedDiffHunks() = runBlocking {
        val initialFile = "line1\nline2\nline3\n"
        writeTool.execute(mapOf("path" to "lines.txt", "content" to initialFile))

        val unifiedDiff = """
            --- lines.txt
            +++ lines.txt
            @@ -1,3 +1,3 @@
             line1
            -line2
            +line2_updated
             line3
        """.trimIndent()

        val patchResult = patchTool.execute(
            mapOf(
                "path" to "lines.txt",
                "diff" to unifiedDiff
            )
        )

        assertTrue(patchResult.isSuccess)
        val readResult = readTool.execute(mapOf("path" to "lines.txt"))
        assertEquals("line1\nline2_updated\nline3\n", readResult.result)
    }

    @Test
    fun testFilePatchFailsGracefullyWhenTargetNotFound() = runBlocking {
        writeTool.execute(mapOf("path" to "test.py", "content" to "print('hello')"))

        val patchResult = patchTool.execute(
            mapOf(
                "path" to "test.py",
                "target_content" to "print('nonexistent')",
                "replacement_content" to "print('new')"
            )
        )

        assertFalse(patchResult.isSuccess)
        assertNotNull(patchResult.error)
        assertTrue(patchResult.error?.contains("Could not find target_content") == true)
    }

    @Test
    fun testZipExtractStream() = runBlocking {
        // Create an in-memory zip
        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zos ->
            zos.putNextEntry(ZipEntry("src/index.js"))
            zos.write("console.log('from zip');\n".toByteArray())
            zos.closeEntry()

            zos.putNextEntry(ZipEntry("package.json"))
            zos.write("{\"name\": \"test-app\"}\n".toByteArray())
            zos.closeEntry()

            // Noise files that should be ignored
            zos.putNextEntry(ZipEntry("__MACOSX/._index.js"))
            zos.write("metadata".toByteArray())
            zos.closeEntry()
        }

        val extractResult = workspaceManager.extractZipStream(
            inputStream = ByteArrayInputStream(baos.toByteArray()),
            targetSubdir = "project",
            overwrite = true
        )

        assertTrue(extractResult.isSuccess)
        assertEquals(2, extractResult.totalFiles) // __MACOSX ignored
        assertTrue(extractResult.extractedPaths.contains("project/src/index.js"))
        assertTrue(extractResult.extractedPaths.contains("project/package.json"))

        // Read extracted file
        val readJs = readTool.execute(mapOf("path" to "project/src/index.js"))
        assertTrue(readJs.isSuccess)
        assertEquals("console.log('from zip');\n", readJs.result)
    }

    @Test
    fun testArchiveExtractTool() = runBlocking {
        // 1. Create a zip archive on disk inside workspace
        val zipBaos = ByteArrayOutputStream()
        ZipOutputStream(zipBaos).use { zos ->
            zos.putNextEntry(ZipEntry("app.py"))
            zos.write("print('Hello from app.py')".toByteArray())
            zos.closeEntry()
        }

        // Write as binary base64
        val base64 = java.util.Base64.getEncoder().encodeToString(zipBaos.toByteArray())
        writeTool.execute(
            mapOf(
                "path" to "repo.zip",
                "content" to base64,
                "is_base64" to "true"
            )
        )

        // 2. Use ArchiveExtractTool to extract it
        val extractToolResult = archiveTool.execute(
            mapOf(
                "path" to "repo.zip",
                "destination" to "unpacked"
            )
        )

        assertTrue(extractToolResult.isSuccess)
        assertTrue(extractToolResult.result?.contains("Successfully extracted") == true)

        // 3. Verify extracted file
        val readApp = readTool.execute(mapOf("path" to "unpacked/app.py"))
        assertTrue(readApp.isSuccess)
        assertEquals("print('Hello from app.py')", readApp.result)
    }

    @Test
    fun testZipSlipPathTraversalBlocked() = runBlocking {
        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zos ->
            zos.putNextEntry(ZipEntry("../../evil.sh"))
            zos.write("rm -rf /".toByteArray())
            zos.closeEntry()
        }

        val extractResult = workspaceManager.extractZipStream(
            inputStream = ByteArrayInputStream(baos.toByteArray()),
            targetSubdir = ".",
            overwrite = true
        )

        assertFalse(extractResult.isSuccess)
        assertTrue(extractResult.error?.contains("Zip-Slip") == true || extractResult.error?.contains("path traversal") == true)
    }

    @Test
    fun testToolRegistryContainsPatchAndArchive() {
        val patch = registry.getTool("file_patch")
        assertNotNull(patch)
        assertEquals("file_patch", patch?.name)

        val archive = registry.getTool("archive_extract")
        assertNotNull(archive)
        assertEquals("archive_extract", archive?.name)

        val prompt = registry.formatToolsForPrompt()
        assertTrue(prompt.contains("file_patch"))
        assertTrue(prompt.contains("archive_extract"))
    }
}
