package com.example.agent.tools

import com.example.agent.capability.RuntimeCapabilityDetector
import com.example.agent.python.EmbeddedPythonRuntime
import com.example.agent.tools.command.RunCommandTool
import com.example.agent.tools.python.PythonExecuteTool
import com.example.agent.tools.workspace.AgentWorkspaceManager
import com.example.agent.tools.workspace.FileListTool
import com.example.agent.tools.workspace.FileReadTool
import com.example.agent.tools.workspace.FileSearchTool
import com.example.agent.tools.workspace.FileTreeTool
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

/**
 * Comprehensive test suite verifying:
 * - Python execution (direct and from workspace scripts)
 * - Python syntax error diagnostics
 * - Python runtime error tracebacks
 * - Autonomous debugging workflow: successful retry after fixing a script
 * - Missing runtime detection & structured CAPABILITY_UNAVAILABLE handling
 * - Workspace isolation enforcement in file and Python execution tools
 * - Execution timeout enforcement
 * - Workspace-root file listing with empty, slash, and dot paths
 */
class PythonExecutionAndCapabilityTest {

    private lateinit var tempBaseDir: File
    private lateinit var workspaceManager: AgentWorkspaceManager
    private lateinit var pythonTool: PythonExecuteTool
    private lateinit var runCommandTool: RunCommandTool
    private lateinit var writeTool: FileWriteTool
    private lateinit var readTool: FileReadTool
    private lateinit var listTool: FileListTool
    private lateinit var capabilityDetector: RuntimeCapabilityDetector
    private lateinit var registry: ToolRegistry

    @Before
    fun setUp() {
        tempBaseDir = Files.createTempDirectory("agent_py_test_").toFile()
        workspaceManager = AgentWorkspaceManager(tempBaseDir)
        workspaceManager.activeSessionId = "session_python_cuj"

        pythonTool = PythonExecuteTool(workspaceManager)
        runCommandTool = RunCommandTool(workspaceManager)
        writeTool = FileWriteTool(workspaceManager)
        readTool = FileReadTool(workspaceManager)
        listTool = FileListTool(workspaceManager)
        capabilityDetector = RuntimeCapabilityDetector()
        registry = ToolRegistry.defaultRegistry(workspaceManager)
    }

    @After
    fun tearDown() {
        tempBaseDir.deleteRecursively()
    }

    @Test
    fun testPythonExecutionDirectCode() = runBlocking {
        val code = """
            import math
            def calculate_hypotenuse(a, b):
                return math.sqrt(a * a + b * b)
            
            result = calculate_hypotenuse(3, 4)
            print(f"Hypotenuse: {result}")
        """.trimIndent()

        val toolResult = pythonTool.execute(mapOf("code" to code))
        assertTrue("Python execution should succeed: ${toolResult.error}", toolResult.isSuccess)
        assertNotNull(toolResult.result)
        assertTrue("Output should contain calculated hypotenuse", toolResult.result!!.contains("Hypotenuse: 5.0"))
        assertTrue("Output should indicate Exit Code: 0", toolResult.result!!.contains("Exit Code: 0"))
    }

    @Test
    fun testPythonExecutionFromWorkspaceScript() = runBlocking {
        val scriptContent = """
            items = ["apple", "banana", "cherry"]
            upper_items = [item.upper() for item in items]
            for i, val in enumerate(upper_items):
                print(f"{i + 1}: {val}")
        """.trimIndent()

        // 1. Create file in workspace
        val writeRes = writeTool.execute(mapOf("path" to "process_fruits.py", "content" to scriptContent))
        assertTrue("Writing script should succeed", writeRes.isSuccess)

        // 2. Execute file via python_execute
        val execRes = pythonTool.execute(mapOf("script_path" to "process_fruits.py"))
        assertTrue("Script execution should succeed: ${execRes.error}", execRes.isSuccess)
        assertTrue(execRes.result!!.contains("1: APPLE"))
        assertTrue(execRes.result!!.contains("2: BANANA"))
        assertTrue(execRes.result!!.contains("3: CHERRY"))
        assertTrue(execRes.result!!.contains("Script: process_fruits.py"))
    }

    @Test
    fun testPythonSyntaxErrors() = runBlocking {
        // Missing colon in def statement
        val buggySyntaxCode = """
            def invalid_function(x)
                return x * 2
            print(invalid_function(10))
        """.trimIndent()

        val result = pythonTool.execute(mapOf("code" to buggySyntaxCode))
        assertFalse("Code with syntax error must fail", result.isSuccess)
        assertNotNull(result.error)
        assertTrue("Error should be classified as SyntaxError: ${result.error}", result.error!!.contains("SyntaxError"))
        assertTrue("Error should point to line 1 or line 2", result.error!!.contains("line 1") || result.error!!.contains("line 2"))
        assertTrue("Error should report Exit Code: 1", result.error!!.contains("Exit Code: 1"))
    }

    @Test
    fun testPythonRuntimeErrorsAndTraceback() = runBlocking {
        // ZeroDivisionError in function call
        val divisionByZeroCode = """
            def compute_ratio(a, b):
                return a / b

            def run():
                compute_ratio(100, 0)

            run()
        """.trimIndent()

        val result = pythonTool.execute(mapOf("code" to divisionByZeroCode))
        assertFalse("Runtime error should result in failure", result.isSuccess)
        assertNotNull(result.error)
        assertTrue("Should produce standard Python traceback", result.error!!.contains("Traceback (most recent call last):"))
        assertTrue("Should report ZeroDivisionError", result.error!!.contains("ZeroDivisionError: division by zero"))
        assertTrue("Should include compute_ratio frame", result.error!!.contains("compute_ratio"))
        assertTrue("Exit code must be non-zero", result.error!!.contains("Exit Code: 1"))
    }

    @Test
    fun testAutonomousDebuggingWorkflowSuccessfulRetry() = runBlocking {
        // Step 1: Agent writes a Python script that has an error
        val initialBuggyCode = """
            def calculate_average(numbers):
                total = sum(numbers)
                return total / len(numbers)

            # Bug: passing an empty list causes ZeroDivisionError
            data = []
            avg = calculate_average(data)
            print(f"Average: {avg}")
        """.trimIndent()

        val writeRes1 = writeTool.execute(mapOf("path" to "calc.py", "content" to initialBuggyCode))
        assertTrue(writeRes1.isSuccess)

        // Step 2: Agent executes the script and observes the error
        val run1 = pythonTool.execute(mapOf("script_path" to "calc.py"))
        assertFalse("Initial run should fail with error", run1.isSuccess)
        assertTrue(run1.error!!.contains("ZeroDivisionError"))

        // Step 3: Agent diagnoses the error, modifies/fixes the code, and overwrites calc.py
        val fixedCode = """
            def calculate_average(numbers):
                if not numbers:
                    return 0.0
                return sum(numbers) / len(numbers)

            data = [10, 20, 30, 40]
            avg = calculate_average(data)
            print(f"Average: {avg}")
        """.trimIndent()

        val writeRes2 = writeTool.execute(mapOf("path" to "calc.py", "content" to fixedCode))
        assertTrue(writeRes2.isSuccess)

        // Step 4: Agent re-runs with python_execute and verifies the result
        val run2 = pythonTool.execute(mapOf("script_path" to "calc.py"))
        assertTrue("Fixed script must succeed: ${run2.error}", run2.isSuccess)
        assertNotNull(run2.result)
        assertTrue("Output should contain calculated average", run2.result!!.contains("Average: 25.0"))
        assertTrue("Exit Code should be 0", run2.result!!.contains("Exit Code: 0"))
    }

    @Test
    fun testMissingRuntimeDetectionAndCapabilityReport() {
        val caps = capabilityDetector.detectCapabilities()
        val embeddedPy = caps.firstOrNull { it.id == "embedded_python" }
        assertNotNull("Embedded Python capability must be present", embeddedPy)
        assertTrue("Embedded Python must be available", embeddedPy!!.isAvailable)
        assertEquals("python_execute", embeddedPy.primaryTool)

        val promptBlock = capabilityDetector.formatCapabilitiesForPrompt()
        assertTrue(promptBlock.contains("Embedded Python 3"))
        assertTrue(promptBlock.contains("python_execute"))

        // Alternative resolution
        val altPy = capabilityDetector.findAlternative("python")
        assertNotNull(altPy)
        assertTrue("Alternative for python must suggest python_execute", altPy!!.contains("python_execute"))

        val structuredError = capabilityDetector.buildCapabilityUnavailableError("python", "python script.py")
        assertTrue(structuredError.contains("[CAPABILITY_UNAVAILABLE]"))
        assertTrue(structuredError.contains("python_execute"))
    }

    @Test
    fun testRunCommandReturnsStructuredCapabilityUnavailableForPython() = runBlocking {
        // When agent or user runs "python script.py" in run_command
        val result = runCommandTool.execute(mapOf("command" to "python script.py"))
        assertFalse("Running python in run_command must fail", result.isSuccess)
        assertNotNull(result.error)
        assertTrue("Error must be structured CAPABILITY_UNAVAILABLE: ${result.error}", result.error!!.contains("[CAPABILITY_UNAVAILABLE]"))
        assertTrue("Error must direct to python_execute: ${result.error}", result.error!!.contains("python_execute"))
        assertFalse("Must not pretend it succeeded", result.isSuccess)
    }

    @Test
    fun testPythonWorkspaceIsolation() = runBlocking {
        // Attempting to read outside workspace from Python
        val maliciousCode = """
            try:
                with open("/etc/passwd", "r") as f:
                    content = f.read()
                print("LEAKED: " + content)
            except PermissionError as e:
                print("BLOCKED: " + str(e))
        """.trimIndent()

        val result = pythonTool.execute(mapOf("code" to maliciousCode))
        assertTrue(result.isSuccess)
        assertNotNull(result.result)
        assertTrue("Access to /etc/passwd must be blocked: ${result.result}", result.result!!.contains("BLOCKED"))
        assertFalse("Must never leak /etc/passwd", result.result!!.contains("LEAKED"))

        // Path traversal in Python open()
        val traversalCode = """
            with open("../../secret.txt", "w") as f:
                f.write("bad")
        """.trimIndent()

        val traversalResult = pythonTool.execute(mapOf("code" to traversalCode))
        assertFalse("Traversal open() must fail", traversalResult.isSuccess)
        assertTrue(
            traversalResult.error!!.contains("PermissionError") ||
            traversalResult.error!!.contains("Security") ||
            traversalResult.error!!.contains("Access denied")
        )
    }

    @Test
    fun testPythonExecutionTimeout() = runBlocking {
        // Infinite while loop
        val infiniteLoopCode = """
            count = 0
            while True:
                count += 1
        """.trimIndent()

        val result = pythonTool.execute(
            mapOf(
                "code" to infiniteLoopCode,
                "timeout_ms" to 200
            )
        )

        assertFalse("Infinite loop must time out", result.isSuccess)
        assertNotNull(result.error)
        assertTrue("Error should indicate TimeoutError: ${result.error}", result.error!!.contains("TimeoutError"))
        assertTrue("Exit code must be 124: ${result.error}", result.error!!.contains("Exit Code: 124"))
    }

    @Test
    fun testWorkspaceRootFileListingWithVariousInputs() = runBlocking {
        // 1. Listing empty workspace with path: ""
        val listEmptyStr = listTool.execute(mapOf("path" to ""))
        assertTrue(listEmptyStr.isSuccess)
        assertTrue(
            "Empty path must report workspace root is empty: ${listEmptyStr.result}",
            listEmptyStr.result!!.contains("Directory workspace root is empty.")
        )
        assertFalse("Must NOT claim '/' is empty", listEmptyStr.result!!.contains("Directory '/' is empty."))

        // 2. Listing empty workspace with path: "/"
        val listSlash = listTool.execute(mapOf("path" to "/"))
        assertTrue(listSlash.isSuccess)
        assertTrue(
            "Slash path must report workspace root is empty: ${listSlash.result}",
            listSlash.result!!.contains("Directory workspace root is empty.")
        )
        assertFalse("Must NOT claim '/' is empty", listSlash.result!!.contains("Directory '/' is empty."))

        // 3. Listing empty workspace with path: "."
        val listDot = listTool.execute(mapOf("path" to "."))
        assertTrue(listDot.isSuccess)
        assertTrue(listDot.result!!.contains("Directory workspace root is empty."))

        // 4. Create files in workspace
        writeTool.execute(mapOf("path" to "main.py", "content" to "print('hello')"))
        writeTool.execute(mapOf("path" to "data/config.json", "content" to "{}"))

        // 5. Listing with path: "" now lists workspace items
        val listFilledEmpty = listTool.execute(mapOf("path" to ""))
        assertTrue(listFilledEmpty.isSuccess)
        assertTrue(listFilledEmpty.result!!.contains("main.py"))
        assertTrue(listFilledEmpty.result!!.contains("data"))

        // 6. Listing with path: "/" now lists workspace items
        val listFilledSlash = listTool.execute(mapOf("path" to "/"))
        assertTrue(listFilledSlash.isSuccess)
        assertTrue(listFilledSlash.result!!.contains("main.py"))
        assertTrue(listFilledSlash.result!!.contains("data"))
    }

    @Test
    fun testPythonWorkspaceFileReadAndWrite() = runBlocking {
        // Python code writes to a file in workspace, then reads it back
        val fileIoCode = """
            # Write to workspace file
            with open("output.txt", "w") as f:
                f.write("Processed line 1\nProcessed line 2\n")

            # Read back from workspace file
            with open("output.txt", "r") as f:
                lines = f.readlines()

            print(f"Read {len(lines)} lines from output.txt")
        """.trimIndent()

        val execRes = pythonTool.execute(mapOf("code" to fileIoCode))
        assertTrue("Python file I/O must succeed: ${execRes.error}", execRes.isSuccess)
        assertTrue(execRes.result!!.contains("Read 2 lines from output.txt"))

        // Verify the file was physically created in workspace and can be read by FileReadTool
        val readRes = readTool.execute(mapOf("path" to "output.txt"))
        assertTrue(readRes.isSuccess)
        assertTrue(readRes.result!!.contains("Processed line 1"))
        assertTrue(readRes.result!!.contains("Processed line 2"))
    }

    @Test
    fun testLocalWorkspaceModuleImportAndFromImport() = runBlocking {
        val calcModule = """
            def add(a, b):
                return a + b

            def multiply(a, b):
                return a * b

            VERSION = "2.0"
        """.trimIndent()

        // 1. Create calculator.py module in workspace
        val writeRes = writeTool.execute(mapOf("path" to "calculator.py", "content" to calcModule))
        assertTrue("Writing calculator.py must succeed", writeRes.isSuccess)

        // 2. Execute script that imports calculator and uses 'from calculator import ...'
        val mainCode = """
            import calculator
            from calculator import multiply, VERSION

            print(f"add: {calculator.add(15, 27)}")
            print(f"multiply: {multiply(6, 7)}")
            print(f"version: {VERSION}")
        """.trimIndent()

        val execRes = pythonTool.execute(mapOf("code" to mainCode))
        assertTrue("Module import script execution must succeed: ${execRes.error}", execRes.isSuccess)
        assertTrue("Output should contain add result", execRes.result!!.contains("add: 42"))
        assertTrue("Output should contain multiply result", execRes.result!!.contains("multiply: 42"))
        assertTrue("Output should contain imported VERSION", execRes.result!!.contains("version: 2.0"))
    }

    @Test
    fun testFromMathImportSpecificSymbols() = runBlocking {
        val code = """
            from math import sqrt, pi, floor, ceil, gcd
            print(f"sqrt: {sqrt(144)}")
            print(f"floor: {floor(3.9)}")
            print(f"ceil: {ceil(3.1)}")
            print(f"gcd: {gcd(48, 18)}")
            print(f"pi_valid: {pi > 3.14}")
        """.trimIndent()

        val execRes = pythonTool.execute(mapOf("code" to code))
        assertTrue("from math import ... must succeed: ${execRes.error}", execRes.isSuccess)
        assertTrue(execRes.result!!.contains("sqrt: 12.0"))
        assertTrue(execRes.result!!.contains("floor: 3"))
        assertTrue(execRes.result!!.contains("ceil: 4"))
        assertTrue(execRes.result!!.contains("gcd: 6"))
        assertTrue(execRes.result!!.contains("pi_valid: True"))
    }

    @Test
    fun testJsonLoadsAndDumps() = runBlocking {
        val code = """
            import json
            raw = '{"name": "Agent", "status": "active", "scores": [95, 88, 100], "debug": true}'
            data = json.loads(raw)
            print(f"name: {data['name']}")
            print(f"scores_len: {len(data['scores'])}")
            print(f"first_score: {data['scores'][0]}")
            print(f"debug: {data['debug']}")

            data['scores'].append(99)
            dumped = json.dumps(data)
            print(f"dumped_valid: {'Agent' in dumped and '99' in dumped}")
        """.trimIndent()

        val execRes = pythonTool.execute(mapOf("code" to code))
        assertTrue("json.loads and json.dumps must succeed: ${execRes.error}", execRes.isSuccess)
        assertTrue(execRes.result!!.contains("name: Agent"))
        assertTrue(execRes.result!!.contains("scores_len: 3"))
        assertTrue(execRes.result!!.contains("first_score: 95"))
        assertTrue(execRes.result!!.contains("debug: True"))
        assertTrue(execRes.result!!.contains("dumped_valid: True"))
    }

    @Test
    fun testRegexModule() = runBlocking {
        val code = """
            import re
            m = re.search(r"(\w+)\s*=\s*(\d+)", "max_retry = 42")
            print(f"matched: {m is not None}")
            print(f"key: {m.group(1)}")
            print(f"val: {m.group(2)}")

            subbed = re.sub(r"\d+", "99", "item 10 and 20")
            print(f"subbed: {subbed}")

            parts = re.split(r",\s*", "alpha, beta, gamma")
            print(f"parts_len: {len(parts)}")
            print(f"first_part: {parts[0]}")
        """.trimIndent()

        val execRes = pythonTool.execute(mapOf("code" to code))
        assertTrue("re module must succeed: ${execRes.error}", execRes.isSuccess)
        assertTrue(execRes.result!!.contains("matched: True"))
        assertTrue(execRes.result!!.contains("key: max_retry"))
        assertTrue(execRes.result!!.contains("val: 42"))
        assertTrue(execRes.result!!.contains("subbed: item 99 and 99"))
        assertTrue(execRes.result!!.contains("parts_len: 3"))
        assertTrue(execRes.result!!.contains("first_part: alpha"))
    }

    @Test
    fun testBase64AndHashlibAndStringModules() = runBlocking {
        val code = """
            import base64
            import hashlib
            import string

            # Base64
            encoded = base64.b64encode("Hello Antigravity")
            decoded = base64.b64decode(encoded)
            print(f"decoded: {decoded}")

            # Hashlib
            sha = hashlib.sha256(b"hello world")
            print(f"sha_len: {len(sha.hexdigest())}")
            md5_hash = hashlib.md5(b"hello world")
            print(f"md5_len: {len(md5_hash.hexdigest())}")

            # String
            print(f"digits_count: {len(string.digits)}")
            print(f"letters_has_a: {'a' in string.ascii_lowercase}")
        """.trimIndent()

        val execRes = pythonTool.execute(mapOf("code" to code))
        assertTrue("base64, hashlib, string must succeed: ${execRes.error}", execRes.isSuccess)
        assertTrue(execRes.result!!.contains("decoded: Hello Antigravity"))
        assertTrue(execRes.result!!.contains("sha_len: 64"))
        assertTrue(execRes.result!!.contains("md5_len: 32"))
        assertTrue(execRes.result!!.contains("digits_count: 10"))
        assertTrue(execRes.result!!.contains("letters_has_a: True"))
    }

    @Test
    fun testFileSearchAndFileTreeTools() = runBlocking {
        val treeTool = FileTreeTool(workspaceManager)
        val searchTool = FileSearchTool(workspaceManager)

        // 1. Create multi-level files in workspace
        writeTool.execute(mapOf("path" to "src/main.py", "content" to "def main():\n    print('TargetFunction running')\n"))
        writeTool.execute(mapOf("path" to "src/utils.py", "content" to "def helper():\n    return 'TargetFunction helper'\n"))
        writeTool.execute(mapOf("path" to "docs/readme.txt", "content" to "Project Documentation\nNo target here.\n"))

        // 2. Test FileTreeTool
        val treeRes = treeTool.execute(emptyMap())
        assertTrue("FileTreeTool execution must succeed: ${treeRes.error}", treeRes.isSuccess)
        val treeOut = treeRes.result!!
        assertTrue("Tree output must contain src folder", treeOut.contains("src/"))
        assertTrue("Tree output must contain main.py", treeOut.contains("main.py"))
        assertTrue("Tree output must contain utils.py", treeOut.contains("utils.py"))
        assertTrue("Tree output must contain docs folder", treeOut.contains("docs/"))

        // 3. Test FileSearchTool
        val searchRes = searchTool.execute(mapOf("query" to "TargetFunction"))
        assertTrue("FileSearchTool execution must succeed: ${searchRes.error}", searchRes.isSuccess)
        val searchOut = searchRes.result!!
        assertTrue("Search should match src/main.py", searchOut.contains("src/main.py"))
        assertTrue("Search should match src/utils.py", searchOut.contains("src/utils.py"))
        assertFalse("Search should not match docs/readme.txt", searchOut.contains("docs/readme.txt"))

        // 4. Test FileSearchTool with extension filter
        val pySearchRes = searchTool.execute(mapOf("query" to "TargetFunction", "extension" to "py"))
        assertTrue(pySearchRes.isSuccess)
        assertTrue(pySearchRes.result!!.contains("src/main.py"))
    }

    @Test
    fun testUrllibParseAndModuleImport() = runBlocking {
        val code = """
            import urllib.parse
            encoded = urllib.parse.quote("hello world & test=1")
            decoded = urllib.parse.unquote(encoded)
            print(f"encoded: {encoded}")
            print(f"decoded: {decoded}")
        """.trimIndent()

        val execRes = pythonTool.execute(mapOf("code" to code))
        assertTrue("urllib.parse must succeed: ${execRes.error}", execRes.isSuccess)
        assertTrue(execRes.result!!.contains("encoded: hello+world+%26+test%3D1") || execRes.result!!.contains("encoded: hello%20world%20%26%20test%3D1"))
        assertTrue(execRes.result!!.contains("decoded: hello world & test=1"))
    }
}
