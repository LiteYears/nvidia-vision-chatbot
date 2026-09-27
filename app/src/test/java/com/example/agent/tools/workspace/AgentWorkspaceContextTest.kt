package com.example.agent.tools.workspace

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Unit tests auditing and verifying agent workspace and environment awareness:
 * 1. Multi-file Android project task.
 * 2. Discovery of an unfamiliar project structure.
 * 3. File modification recognition and changed-state verification requirement.
 * 4. Failed command error tracking, diagnosis, and recovery.
 */
class AgentWorkspaceContextTest {

    private lateinit var tempDir: File
    private lateinit var workspaceManager: AgentWorkspaceManager
    private lateinit var context: AgentWorkspaceContext

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("workspace_context_test").toFile()
        workspaceManager = AgentWorkspaceManager(baseDir = tempDir, autoInitStandardFolders = false)
        context = AgentWorkspaceContext(
            sessionId = "test-session",
            workspaceManager = workspaceManager
        )
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun `test 1 - multi-file Android project task awareness`() {
        // Arrange: Populate an Android project hierarchy inside session workspace
        val wsRoot = context.workspaceRoot
        val appDir = File(wsRoot, "app").apply { mkdirs() }
        val srcMain = File(appDir, "src/main/java/com/example").apply { mkdirs() }
        File(wsRoot, "build.gradle.kts").writeText("// Top-level build file")
        File(wsRoot, "settings.gradle.kts").writeText("include(\":app\")")
        File(appDir, "build.gradle.kts").writeText("plugins { id(\"com.android.application\") }\nandroid { }")
        File(appDir, "src/main/AndroidManifest.xml").writeText("<manifest package=\"com.example\"></manifest>")
        File(srcMain, "MainActivity.kt").writeText("package com.example\nclass MainActivity")
        File(srcMain, "SettingsActivity.kt").writeText("package com.example\nclass SettingsActivity")
        File(srcMain, "PreferencesManager.kt").writeText("package com.example\nclass PreferencesManager")

        // Act: Discover workspace
        val detected = context.discoverWorkspace()

        // Assert: Correctly identifies Android multi-module project
        assertEquals(ProjectType.ANDROID_GRADLE, detected.type)
        assertTrue(detected.buildCommands.any { it.contains("gradlew assembleDebug") })
        assertTrue(detected.testCommands.any { it.contains("gradlew testDebugUnitTest") })

        // Check key components detected
        assertTrue(detected.keyComponents.any { it.contains("AndroidManifest.xml") })
        assertTrue(context.knownFiles.containsKey("app/src/main/AndroidManifest.xml"))
        assertTrue(context.knownFiles.containsKey("app/src/main/java/com/example/MainActivity.kt"))

        // Agent finds relevant files matching task keywords without blind full scans
        val relevant = context.findRelevantFiles(listOf("settings", "preferences"))
        assertEquals(2, relevant.size)
        assertTrue(relevant.contains("app/src/main/java/com/example/SettingsActivity.kt"))
        assertTrue(relevant.contains("app/src/main/java/com/example/PreferencesManager.kt"))

        // Assert prompt summary contains high-signal environment state
        val prompt = context.formatContextForPrompt()
        assertTrue(prompt.contains("Android (Gradle multi-module)"))
        assertTrue(prompt.contains("./gradlew testDebugUnitTest"))
    }

    @Test
    fun `test 2 - discovery of unfamiliar project structure`() {
        // Arrange: Simulate unfamiliar project (Node.js/Express service)
        val wsRoot = context.workspaceRoot
        val srcDir = File(wsRoot, "src/routes").apply { mkdirs() }
        val testDir = File(wsRoot, "tests").apply { mkdirs() }

        File(wsRoot, "package.json").writeText("{\"name\": \"unfamiliar-service\", \"scripts\": {\"test\": \"jest\"}}")
        File(wsRoot, "README.md").writeText("# Unfamiliar Service API")
        File(srcDir, "index.js").writeText("const express = require('express');")
        File(srcDir, "auth.js").writeText("module.exports = { login: () => {} };")
        File(testDir, "auth.test.js").writeText("test('auth', () => {});")

        // Act: Environment discovery
        val detected = context.discoverWorkspace()

        // Assert: Discovers unfamiliar layout as Node/NPM
        assertEquals(ProjectType.NODE_NPM, detected.type)
        assertTrue(detected.testCommands.contains("npm test"))
        assertTrue(detected.buildCommands.contains("npm run build"))

        // Inspect file and verify context remembers inspection
        context.recordFileInspection("src/routes/auth.js", 120L, "Exports authentication handlers")
        assertTrue(context.inspectedFiles.containsKey("src/routes/auth.js"))
        assertEquals(120L, context.inspectedFiles["src/routes/auth.js"]?.sizeBytes)

        val prompt = context.formatContextForPrompt()
        assertTrue(prompt.contains("Node.js / NPM"))
        assertTrue(prompt.contains("Already Inspected (avoid redundant re-reads): src/routes/auth.js"))
    }

    @Test
    fun `test 3 - file modification recognition and changed state verification requirement`() {
        // Arrange: Existing source file
        val wsRoot = context.workspaceRoot
        val calcFile = File(wsRoot, "Calculator.kt").apply {
            writeText("class Calculator { fun add(a: Int, b: Int) = a + b }")
        }
        context.discoverWorkspace()
        assertFalse("Initially no unverified modifications", context.hasUnverifiedModifications())

        // Act: Agent patches file during development
        context.recordFileModification(
            path = "Calculator.kt",
            changeType = FileChangeType.PATCHED,
            summary = "Added multiply and divide functions"
        )

        // Assert: Workspace state changed; verification mandated
        assertTrue("Modifications must require verification", context.hasUnverifiedModifications())
        val modInfo = context.modifiedFiles["Calculator.kt"]
        assertNotNull(modInfo)
        assertEquals(FileChangeType.PATCHED, modInfo!!.changeType)
        assertEquals(VerificationStatus.NEEDS_VERIFICATION, modInfo.verificationStatus)

        // Post action feedback gives explicit changed-state mandate
        val notice = context.formatPostActionNotice("file_patch", true)
        assertNotNull(notice)
        assertTrue(notice!!.contains("WORKSPACE STATE CHANGED: Calculator.kt modified on disk"))

        val prompt = context.formatContextForPrompt()
        assertTrue(prompt.contains("[MODIFIED - STATE CHANGED: NEEDS VERIFICATION]"))

        // Act: Agent runs test to verify the change
        context.recordCommandExecution(
            command = "./gradlew testDebugUnitTest",
            exitCode = 0,
            output = "BUILD SUCCESSFUL. 12 tests passed.",
            isSuccess = true
        )

        // Assert: State is now verified
        assertFalse("All modifications are now verified", context.hasUnverifiedModifications())
        assertEquals(VerificationStatus.VERIFIED, context.modifiedFiles["Calculator.kt"]?.verificationStatus)
    }

    @Test
    fun `test 4 - failed command error state diagnosis and recovery`() {
        val wsRoot = context.workspaceRoot
        File(wsRoot, "Engine.kt").writeText("class Engine")
        context.discoverWorkspace()

        // Act 1: Command fails with compiler error
        val compilerError = "CompilationError: Unresolved reference: TurboCompressor in Engine.kt:14"
        context.recordCommandExecution(
            command = "./gradlew assembleDebug",
            exitCode = 1,
            output = compilerError,
            isSuccess = false
        )

        // Assert 1: Context tracks error state and diagnostic hint
        val failure = context.lastFailure
        assertNotNull(failure)
        assertFalse(failure!!.isResolved)
        assertTrue(failure.errorMessage.contains("Unresolved reference"))
        assertTrue(failure.diagnosticHint!!.contains("Missing symbol or import"))

        val notice = context.formatPostActionNotice("run_command", false)
        assertTrue(notice!!.contains("WORKSPACE ERROR STATE"))

        val prompt = context.formatContextForPrompt()
        assertTrue(prompt.contains("Active Environment Error"))
        assertTrue(prompt.contains("Diagnostic Hint: Missing symbol or import"))

        // Act 2: Agent repairs the file
        context.recordFileModification(
            path = "Engine.kt",
            changeType = FileChangeType.PATCHED,
            summary = "Fixed TurboCompressor import"
        )
        // Failure is marked addressed by the fix
        assertTrue(context.lastFailure!!.isResolved)

        // Act 3: Agent re-executes build command successfully
        context.recordCommandExecution(
            command = "./gradlew assembleDebug",
            exitCode = 0,
            output = "BUILD SUCCESSFUL in 5s",
            isSuccess = true
        )

        // Assert 3: Error state cleared and modifications verified
        assertTrue(context.lastFailure!!.isResolved)
        assertFalse(context.hasUnverifiedModifications())
    }
}
