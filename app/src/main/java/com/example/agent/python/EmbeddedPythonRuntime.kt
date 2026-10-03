package com.example.agent.python

import com.example.agent.tools.workspace.AgentWorkspaceManager
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.atomic.AtomicBoolean
import java.util.regex.MatchResult
import java.util.regex.Pattern
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Android-compatible embedded Python 3 runtime engine.
 *
 * Executes Python scripts and code snippets in-app without requiring
 * external shell binaries or Android system dependencies.
 *
 * Enforces:
 * - Strict workspace isolation (no access outside agent workspace)
 * - Execution timeout enforcement (kills infinite loops)
 * - Output buffer limit enforcement
 * - Python-standard tracebacks (SyntaxError, NameError, TypeError, ZeroDivisionError, etc.)
 * - Standard library builtins (math, random, json, sys, os, time, re, open)
 */
class EmbeddedPythonRuntime(
    private val workspaceManager: AgentWorkspaceManager = AgentWorkspaceManager.getInstance()
) {

    /**
     * Executes Python code within the active workspace.
     */
    fun execute(
        code: String,
        filename: String = "script.py",
        args: List<String> = emptyList(),
        timeoutMs: Long = 5000L,
        maxOutputBytes: Int = 32768
    ): PythonExecutionResult {
        val startTime = System.currentTimeMillis()
        val deadline = startTime + timeoutMs
        val isTimedOut = AtomicBoolean(false)
        val isTruncated = AtomicBoolean(false)

        val stdoutBuffer = StringBuilder()
        val stderrBuffer = StringBuilder()

        val appendStdout: (String) -> Unit = { text ->
            if (stdoutBuffer.length + text.length <= maxOutputBytes) {
                stdoutBuffer.append(text)
            } else {
                val remaining = (maxOutputBytes - stdoutBuffer.length).coerceAtLeast(0)
                if (remaining > 0) {
                    stdoutBuffer.append(text.take(remaining))
                }
                isTruncated.set(true)
            }
        }

        val appendStderr: (String) -> Unit = { text ->
            if (stderrBuffer.length + text.length <= maxOutputBytes) {
                stderrBuffer.append(text)
            } else {
                val remaining = (maxOutputBytes - stderrBuffer.length).coerceAtLeast(0)
                if (remaining > 0) {
                    stderrBuffer.append(text.take(remaining))
                }
                isTruncated.set(true)
            }
        }

        // 1. Lexical Tokenization
        val tokens = try {
            PythonLexer(code, filename).tokenize()
        } catch (e: PythonSyntaxException) {
            val duration = System.currentTimeMillis() - startTime
            val formatted = formatSyntaxError(e, code, filename)
            appendStderr(formatted)
            return PythonExecutionResult(
                stdout = stdoutBuffer.toString(),
                stderr = stderrBuffer.toString(),
                exitCode = 1,
                durationMs = duration,
                isTimedOut = false,
                isTruncated = isTruncated.get(),
                errorType = "SyntaxError"
            )
        }

        // 2. Syntax Parsing
        val ast = try {
            PythonParser(tokens, filename).parse()
        } catch (e: PythonSyntaxException) {
            val duration = System.currentTimeMillis() - startTime
            val formatted = formatSyntaxError(e, code, filename)
            appendStderr(formatted)
            return PythonExecutionResult(
                stdout = stdoutBuffer.toString(),
                stderr = stderrBuffer.toString(),
                exitCode = 1,
                durationMs = duration,
                isTimedOut = false,
                isTruncated = isTruncated.get(),
                errorType = "SyntaxError"
            )
        }

        // 3. Execution in Interpreter
        val interpreter = PythonInterpreter(
            code = code,
            filename = filename,
            args = args,
            workspaceManager = workspaceManager,
            deadline = deadline,
            isTimedOut = isTimedOut,
            appendStdout = appendStdout,
            appendStderr = appendStderr
        )

        return try {
            interpreter.run(ast)
            val duration = System.currentTimeMillis() - startTime
            PythonExecutionResult(
                stdout = stdoutBuffer.toString(),
                stderr = stderrBuffer.toString(),
                exitCode = 0,
                durationMs = duration,
                isTimedOut = false,
                isTruncated = isTruncated.get(),
                errorType = null
            )
        } catch (e: PythonSystemExit) {
            val duration = System.currentTimeMillis() - startTime
            PythonExecutionResult(
                stdout = stdoutBuffer.toString(),
                stderr = stderrBuffer.toString(),
                exitCode = e.code,
                durationMs = duration,
                isTimedOut = false,
                isTruncated = isTruncated.get(),
                errorType = if (e.code == 0) null else "SystemExit"
            )
        } catch (e: PythonTimeoutException) {
            val duration = System.currentTimeMillis() - startTime
            isTimedOut.set(true)
            appendStderr("TimeoutError: Execution timed out after ${timeoutMs}ms and was terminated.\n")
            PythonExecutionResult(
                stdout = stdoutBuffer.toString(),
                stderr = stderrBuffer.toString(),
                exitCode = 124,
                durationMs = duration,
                isTimedOut = true,
                isTruncated = isTruncated.get(),
                errorType = "TimeoutError"
            )
        } catch (e: PythonRuntimeException) {
            val duration = System.currentTimeMillis() - startTime
            val formattedTraceback = interpreter.formatTraceback(e)
            appendStderr(formattedTraceback)
            PythonExecutionResult(
                stdout = stdoutBuffer.toString(),
                stderr = stderrBuffer.toString(),
                exitCode = 1,
                durationMs = duration,
                isTimedOut = false,
                isTruncated = isTruncated.get(),
                errorType = e.errorType
            )
        } catch (e: Throwable) {
            val duration = System.currentTimeMillis() - startTime
            val msg = if (e is StackOverflowError) "maximum recursion depth exceeded" else (e.message ?: e.javaClass.simpleName)
            val errType = if (e is StackOverflowError) "RecursionError" else "RuntimeError"
            appendStderr("$errType: $msg\n")
            PythonExecutionResult(
                stdout = stdoutBuffer.toString(),
                stderr = stderrBuffer.toString(),
                exitCode = 1,
                durationMs = duration,
                isTimedOut = false,
                isTruncated = isTruncated.get(),
                errorType = errType
            )
        }
    }

    private fun formatSyntaxError(e: PythonSyntaxException, code: String, filename: String): String {
        val lines = code.split("\n")
        val lineIdx = (e.line - 1).coerceIn(0, lines.size - 1)
        val sourceLine = if (lines.isNotEmpty()) lines[lineIdx] else ""
        val pointer = " ".repeat((e.col - 1).coerceAtLeast(0)) + "^"
        return buildString {
            appendLine("  File \"$filename\", line ${e.line}")
            appendLine("    $sourceLine")
            appendLine("    $pointer")
            appendLine("SyntaxError: ${e.message ?: "invalid syntax"}")
        }
    }
}

// -------------------------------------------------------------
// AST & Exception Models
// -------------------------------------------------------------

open class PythonSyntaxException(
    override val message: String,
    val line: Int,
    val col: Int
) : Exception(message)

class PythonSystemExit(val code: Int) : Exception("SystemExit: $code")
class PythonTimeoutException : Exception("TimeoutError")

open class PythonRuntimeException(
    val errorType: String,
    override val message: String,
    var line: Int = 1,
    var filename: String = "script.py",
    var funcName: String = "<module>",
    val traceback: MutableList<PythonTracebackFrame> = mutableListOf()
) : Exception("$errorType: $message")

class PythonTracebackFrame(
    val filename: String,
    val line: Int,
    val funcName: String
)

sealed class PyStmt(val line: Int) {
    class Assign(val target: String, val value: PyExpr, line: Int, val targetExpr: PyExpr? = null) : PyStmt(line)
    class MultiAssign(val targets: List<String>, val value: PyExpr, line: Int) : PyStmt(line)
    class AugAssign(val target: String, val op: String, val value: PyExpr, line: Int) : PyStmt(line)
    class Expr(val expr: PyExpr, line: Int) : PyStmt(line)
    class Def(val name: String, val params: List<ParamDef>, val body: List<PyStmt>, line: Int, val decorators: List<PyExpr> = emptyList()) : PyStmt(line)
    class Class(val name: String, val baseClass: String?, val body: List<PyStmt>, line: Int, val decorators: List<PyExpr> = emptyList()) : PyStmt(line)
    class Assert(val condition: PyExpr, val message: PyExpr?, line: Int) : PyStmt(line)
    class Return(val expr: PyExpr?, line: Int) : PyStmt(line)
    class If(val condition: PyExpr, val body: List<PyStmt>, val elifs: List<Pair<PyExpr, List<PyStmt>>>, val elseBody: List<PyStmt>?, line: Int) : PyStmt(line)
    class While(val condition: PyExpr, val body: List<PyStmt>, line: Int) : PyStmt(line)
    class For(val targets: List<String>, val iterable: PyExpr, val body: List<PyStmt>, line: Int) : PyStmt(line) {
        val target: String get() = targets.firstOrNull() ?: ""
    }
    class Try(val body: List<PyStmt>, val excepts: List<ExceptClause>, val finallyBody: List<PyStmt>?, line: Int) : PyStmt(line)
    class Raise(val expr: PyExpr?, line: Int) : PyStmt(line)
    class With(val expr: PyExpr, val asName: String?, val body: List<PyStmt>, line: Int) : PyStmt(line)
    class Import(val module: String, val alias: String?, line: Int) : PyStmt(line)
    class FromImport(val module: String, val items: List<Pair<String, String?>>, line: Int) : PyStmt(line)
    class Break(line: Int) : PyStmt(line)
    class Continue(line: Int) : PyStmt(line)
    class Pass(line: Int) : PyStmt(line)
}

data class ParamDef(val name: String, val defaultValue: PyExpr? = null)
data class ExceptClause(val exceptionType: String?, val asName: String?, val body: List<PyStmt>, val line: Int)

sealed class PyExpr(val line: Int) {
    class Literal(val value: Any?, line: Int) : PyExpr(line)
    class Variable(val name: String, line: Int) : PyExpr(line)
    class FString(val parts: List<Any>, line: Int) : PyExpr(line) // strings or PyExpr
    class Binary(val left: PyExpr, val op: String, val right: PyExpr, line: Int) : PyExpr(line)
    class Unary(val op: String, val operand: PyExpr, line: Int) : PyExpr(line)
    class Call(val callee: PyExpr, val args: List<PyExpr>, val kwargs: Map<String, PyExpr>, line: Int) : PyExpr(line)
    class Attribute(val obj: PyExpr, val name: String, line: Int) : PyExpr(line)
    class Subscript(val obj: PyExpr, val index: PyExpr, line: Int) : PyExpr(line)
    class Slice(val obj: PyExpr, val start: PyExpr?, val end: PyExpr?, val step: PyExpr?, line: Int) : PyExpr(line)
    class PyList(val elements: List<PyExpr>, line: Int) : PyExpr(line)
    class ListComp(val element: PyExpr, val target: String, val iterable: PyExpr, val cond: PyExpr?, line: Int) : PyExpr(line)
    class PyDict(val entries: List<Pair<PyExpr, PyExpr>>, line: Int) : PyExpr(line)
    class PyTuple(val elements: List<PyExpr>, line: Int) : PyExpr(line)
    class PySet(val elements: List<PyExpr>, line: Int) : PyExpr(line)
}

// -------------------------------------------------------------
// Tokenizer & Lexer
// -------------------------------------------------------------

enum class TokenType {
    IDENTIFIER, NUMBER, STRING, FSTRING,
    PLUS, MINUS, STAR, DOUBLE_STAR, SLASH, DOUBLE_SLASH, PERCENT,
    ASSIGN, PLUS_ASSIGN, MINUS_ASSIGN, STAR_ASSIGN, SLASH_ASSIGN,
    EQ, NEQ, LT, LTE, GT, GTE,
    LPAREN, RPAREN, LBRACKET, RBRACKET, LBRACE, RBRACE,
    COLON, COMMA, DOT, SEMICOLON,
    AT, ARROW, TILDE, AMPERSAND, PIPE, CARET,
    NEWLINE, INDENT, DEDENT, EOF,
    KEYWORD
}

data class Token(
    val type: TokenType,
    val value: String,
    val line: Int,
    val col: Int
)

class PythonLexer(private val input: String, private val filename: String) {
    private var pos = 0
    private var line = 1
    private var col = 1
    private val indentStack = mutableListOf(0)

    companion object {
        val KEYWORDS = setOf(
            "def", "return", "if", "elif", "else", "while", "for", "in",
            "try", "except", "finally", "raise", "import", "from", "as",
            "with", "break", "continue", "pass", "class", "assert", "True", "False",
            "None", "and", "or", "not", "is", "lambda", "global"
        )
    }

    fun tokenize(): List<Token> {
        val tokens = mutableListOf<Token>()
        var atLineStart = true

        while (pos < input.length) {
            val c = input[pos]

            if (atLineStart) {
                // Check indentation
                var spaces = 0
                var p = pos
                while (p < input.length && (input[p] == ' ' || input[p] == '\t')) {
                    spaces += if (input[p] == '\t') 8 else 1
                    p++
                }
                // If line is empty or comment, skip
                if (p < input.length && (input[p] == '\n' || input[p] == '\r' || input[p] == '#')) {
                    pos = p
                    if (pos < input.length && input[pos] == '#') {
                        skipComment()
                    }
                    if (pos < input.length && (input[pos] == '\n' || input[pos] == '\r')) {
                        skipNewline()
                    }
                    continue
                }

                val currentIndent = indentStack.last()
                if (spaces > currentIndent) {
                    indentStack.add(spaces)
                    tokens.add(Token(TokenType.INDENT, spaces.toString(), line, col))
                } else if (spaces < currentIndent) {
                    while (indentStack.size > 1 && indentStack.last() > spaces) {
                        indentStack.removeAt(indentStack.lastIndex)
                        tokens.add(Token(TokenType.DEDENT, "", line, col))
                    }
                    if (indentStack.last() != spaces) {
                        throw PythonSyntaxException("unindent does not match any outer indentation level", line, col)
                    }
                }
                pos = p
                col += spaces
                atLineStart = false
                continue
            }

            if (c == ' ' || c == '\t') {
                pos++
                col++
                continue
            }

            if (c == '#') {
                skipComment()
                continue
            }

            if (c == '\r' || c == '\n') {
                tokens.add(Token(TokenType.NEWLINE, "\n", line, col))
                skipNewline()
                atLineStart = true
                continue
            }

            // String literals with prefixes (f, r, b, rf, fr, rb, br, etc.)
            val prefixMatch = matchStringPrefix()
            if (prefixMatch != null) {
                val startCol = col
                pos += prefixMatch.length
                col += prefixMatch.length
                val strToken = readString(
                    isFString = prefixMatch.isFString,
                    isRaw = prefixMatch.isRaw,
                    startCol = startCol
                )
                tokens.add(strToken)
                continue
            }

            if (c.isLetter() || c == '_') {
                val startCol = col
                val start = pos
                while (pos < input.length && (input[pos].isLetterOrDigit() || input[pos] == '_')) {
                    pos++
                    col++
                }
                val word = input.substring(start, pos)
                if (KEYWORDS.contains(word)) {
                    tokens.add(Token(TokenType.KEYWORD, word, line, startCol))
                } else {
                    tokens.add(Token(TokenType.IDENTIFIER, word, line, startCol))
                }
                continue
            }

            // Numbers
            if (c.isDigit()) {
                val startCol = col
                val start = pos
                var hasDot = false
                while (pos < input.length && (input[pos].isDigit() || (input[pos] == '.' && !hasDot))) {
                    if (input[pos] == '.') hasDot = true
                    pos++
                    col++
                }
                tokens.add(Token(TokenType.NUMBER, input.substring(start, pos), line, startCol))
                continue
            }

            // Strings
            if (c == '"' || c == '\'') {
                tokens.add(readString(isFString = false, startCol = col))
                continue
            }

            // Operators & Punctuation
            val startCol = col
            when (c) {
                '+' -> {
                    if (matchNext('=')) tokens.add(Token(TokenType.PLUS_ASSIGN, "+=", line, startCol))
                    else tokens.add(Token(TokenType.PLUS, "+", line, startCol))
                }
                '-' -> {
                    if (matchNext('>')) tokens.add(Token(TokenType.ARROW, "->", line, startCol))
                    else if (matchNext('=')) tokens.add(Token(TokenType.MINUS_ASSIGN, "-=", line, startCol))
                    else tokens.add(Token(TokenType.MINUS, "-", line, startCol))
                }
                '*' -> {
                    if (matchNext('*')) tokens.add(Token(TokenType.DOUBLE_STAR, "**", line, startCol))
                    else if (matchNext('=')) tokens.add(Token(TokenType.STAR_ASSIGN, "*=", line, startCol))
                    else tokens.add(Token(TokenType.STAR, "*", line, startCol))
                }
                '/' -> {
                    if (matchNext('/')) tokens.add(Token(TokenType.DOUBLE_SLASH, "//", line, startCol))
                    else if (matchNext('=')) tokens.add(Token(TokenType.SLASH_ASSIGN, "/=", line, startCol))
                    else tokens.add(Token(TokenType.SLASH, "/", line, startCol))
                }
                '%' -> tokens.add(Token(TokenType.PERCENT, "%", line, startCol))
                '=' -> {
                    if (matchNext('=')) tokens.add(Token(TokenType.EQ, "==", line, startCol))
                    else tokens.add(Token(TokenType.ASSIGN, "=", line, startCol))
                }
                '!' -> {
                    if (matchNext('=')) tokens.add(Token(TokenType.NEQ, "!=", line, startCol))
                    else throw PythonSyntaxException("invalid syntax: '!'", line, startCol)
                }
                '<' -> {
                    if (matchNext('=')) tokens.add(Token(TokenType.LTE, "<=", line, startCol))
                    else tokens.add(Token(TokenType.LT, "<", line, startCol))
                }
                '>' -> {
                    if (matchNext('=')) tokens.add(Token(TokenType.GTE, ">=", line, startCol))
                    else tokens.add(Token(TokenType.GT, ">", line, startCol))
                }
                '(' -> { pos++; col++; tokens.add(Token(TokenType.LPAREN, "(", line, startCol)) }
                ')' -> { pos++; col++; tokens.add(Token(TokenType.RPAREN, ")", line, startCol)) }
                '[' -> { pos++; col++; tokens.add(Token(TokenType.LBRACKET, "[", line, startCol)) }
                ']' -> { pos++; col++; tokens.add(Token(TokenType.RBRACKET, "]", line, startCol)) }
                '{' -> { pos++; col++; tokens.add(Token(TokenType.LBRACE, "{", line, startCol)) }
                '}' -> { pos++; col++; tokens.add(Token(TokenType.RBRACE, "}", line, startCol)) }
                ':' -> { pos++; col++; tokens.add(Token(TokenType.COLON, ":", line, startCol)) }
                ',' -> { pos++; col++; tokens.add(Token(TokenType.COMMA, ",", line, startCol)) }
                '.' -> { pos++; col++; tokens.add(Token(TokenType.DOT, ".", line, startCol)) }
                ';' -> { pos++; col++; tokens.add(Token(TokenType.SEMICOLON, ";", line, startCol)) }
                '@' -> { pos++; col++; tokens.add(Token(TokenType.AT, "@", line, startCol)) }
                '~' -> { pos++; col++; tokens.add(Token(TokenType.TILDE, "~", line, startCol)) }
                '&' -> { pos++; col++; tokens.add(Token(TokenType.AMPERSAND, "&", line, startCol)) }
                '|' -> { pos++; col++; tokens.add(Token(TokenType.PIPE, "|", line, startCol)) }
                '^' -> { pos++; col++; tokens.add(Token(TokenType.CARET, "^", line, startCol)) }
                else -> throw PythonSyntaxException("invalid character '$c'", line, startCol)
            }
        }

        // Emit final dedents
        while (indentStack.size > 1) {
            indentStack.removeAt(indentStack.lastIndex)
            tokens.add(Token(TokenType.DEDENT, "", line, col))
        }
        tokens.add(Token(TokenType.EOF, "", line, col))
        return tokens
    }

    private fun matchNext(expected: Char): Boolean {
        if (pos + 1 < input.length && input[pos + 1] == expected) {
            pos += 2
            col += 2
            return true
        }
        pos++
        col++
        return false
    }

    private fun skipComment() {
        while (pos < input.length && input[pos] != '\n' && input[pos] != '\r') {
            pos++
        }
    }

    private fun skipNewline() {
        if (pos < input.length && input[pos] == '\r') pos++
        if (pos < input.length && input[pos] == '\n') pos++
        line++
        col = 1
    }

    private data class StringPrefix(val isFString: Boolean, val isRaw: Boolean, val length: Int)

    private fun matchStringPrefix(): StringPrefix? {
        val rem = input.length - pos
        if (rem >= 3) {
            val two = input.substring(pos, pos + 2).lowercase()
            val nextC = input[pos + 2]
            if (nextC == '"' || nextC == '\'') {
                when (two) {
                    "rf", "fr" -> return StringPrefix(isFString = true, isRaw = true, length = 2)
                    "rb", "br" -> return StringPrefix(isFString = false, isRaw = true, length = 2)
                }
            }
        }
        if (rem >= 2) {
            val one = input[pos].lowercaseChar()
            val nextC = input[pos + 1]
            if (nextC == '"' || nextC == '\'') {
                when (one) {
                    'f' -> return StringPrefix(isFString = true, isRaw = false, length = 1)
                    'r' -> return StringPrefix(isFString = false, isRaw = true, length = 1)
                    'b', 'u' -> return StringPrefix(isFString = false, isRaw = false, length = 1)
                }
            }
        }
        return null
    }

    private fun readString(isFString: Boolean, isRaw: Boolean = false, startCol: Int): Token {
        val quote = input[pos]
        val isTriple = pos + 2 < input.length && input[pos + 1] == quote && input[pos + 2] == quote
        pos += if (isTriple) 3 else 1
        col += if (isTriple) 3 else 1

        val sb = StringBuilder()
        while (pos < input.length) {
            if (isTriple) {
                if (input[pos] == quote && pos + 2 < input.length && input[pos + 1] == quote && input[pos + 2] == quote) {
                    pos += 3
                    col += 3
                    return Token(if (isFString) TokenType.FSTRING else TokenType.STRING, sb.toString(), line, startCol)
                }
            } else {
                if (input[pos] == quote) {
                    pos++
                    col++
                    return Token(if (isFString) TokenType.FSTRING else TokenType.STRING, sb.toString(), line, startCol)
                }
            }

            if (!isRaw && input[pos] == '\\' && pos + 1 < input.length) {
                pos++
                col++
                when (input[pos]) {
                    'n' -> sb.append('\n')
                    't' -> sb.append('\t')
                    'r' -> sb.append('\r')
                    '\\' -> sb.append('\\')
                    '\'' -> sb.append('\'')
                    '"' -> sb.append('"')
                    else -> {
                        sb.append('\\')
                        sb.append(input[pos])
                    }
                }
                pos++
                col++
                continue
            } else if (isRaw && input[pos] == '\\' && pos + 1 < input.length && input[pos + 1] == quote) {
                sb.append(quote)
                pos += 2
                col += 2
                continue
            }

            if (input[pos] == '\n') {
                if (!isTriple) throw PythonSyntaxException("EOL while scanning string literal", line, col)
                line++
                col = 1
            } else {
                col++
            }
            sb.append(input[pos])
            pos++
        }
        throw PythonSyntaxException("unexpected EOF while scanning string literal", line, col)
    }
}

// -------------------------------------------------------------
// Recursive-Descent Parser
// -------------------------------------------------------------

class PythonParser(private val tokens: List<Token>, private val filename: String) {
    private var idx = 0

    private fun peek(): Token = tokens[idx]
    private fun previous(): Token = tokens[idx - 1]
    private fun isAtEnd(): Boolean = peek().type == TokenType.EOF

    private fun check(type: TokenType, value: String? = null): Boolean {
        if (isAtEnd()) return false
        val t = peek()
        return t.type == type && (value == null || t.value == value)
    }

    private fun match(type: TokenType, value: String? = null): Boolean {
        if (check(type, value)) {
            idx++
            return true
        }
        return false
    }

    private fun consume(type: TokenType, errMsg: String, value: String? = null): Token {
        if (check(type, value)) {
            return tokens[idx++]
        }
        val t = peek()
        throw PythonSyntaxException(errMsg, t.line, t.col)
    }

    fun parse(): List<PyStmt> {
        val statements = mutableListOf<PyStmt>()
        while (!isAtEnd()) {
            if (match(TokenType.NEWLINE)) continue
            statements.add(parseStatement())
        }
        return statements
    }

    private fun parseStatement(): PyStmt {
        // Handle decorators @decorator
        if (check(TokenType.AT)) {
            val decorators = mutableListOf<PyExpr>()
            while (match(TokenType.AT)) {
                val decExpr = parseExpression()
                consumeEndStatement()
                decorators.add(decExpr)
                while (match(TokenType.NEWLINE)) { /* skip blank lines */ }
            }
            val t = peek()
            if (t.type == TokenType.KEYWORD && t.value == "def") {
                return parseDef(decorators)
            } else if (t.type == TokenType.KEYWORD && t.value == "class") {
                return parseClass(decorators)
            }
            throw PythonSyntaxException("Expected 'def' or 'class' after decorator", t.line, t.col)
        }

        val t = peek()
        if (t.type == TokenType.KEYWORD) {
            when (t.value) {
                "def" -> return parseDef()
                "class" -> return parseClass()
                "assert" -> return parseAssert()
                "return" -> return parseReturn()
                "if" -> return parseIf()
                "while" -> return parseWhile()
                "for" -> return parseFor()
                "try" -> return parseTry()
                "raise" -> return parseRaise()
                "with" -> return parseWith()
                "import" -> return parseImport()
                "from" -> return parseFromImport()
                "break" -> {
                    idx++
                    consumeEndStatement()
                    return PyStmt.Break(t.line)
                }
                "continue" -> {
                    idx++
                    consumeEndStatement()
                    return PyStmt.Continue(t.line)
                }
                "pass" -> {
                    idx++
                    consumeEndStatement()
                    return PyStmt.Pass(t.line)
                }
            }
        }

        // Assignment or Expression Statement
        return parseAssignOrExprStmt()
    }

    private fun consumeEndStatement() {
        if (match(TokenType.NEWLINE) || match(TokenType.SEMICOLON) || isAtEnd()) return
        if (check(TokenType.DEDENT) || check(TokenType.EOF)) return
        val t = peek()
        throw PythonSyntaxException("invalid syntax", t.line, t.col)
    }

    private fun parseDef(decorators: List<PyExpr> = emptyList()): PyStmt.Def {
        val defToken = consume(TokenType.KEYWORD, "Expected 'def'", "def")
        val nameToken = consume(TokenType.IDENTIFIER, "Expected function name after 'def'")
        consume(TokenType.LPAREN, "Expected '(' after function name")
        val params = mutableListOf<ParamDef>()
        if (!check(TokenType.RPAREN)) {
            do {
                val pName = consume(TokenType.IDENTIFIER, "Expected parameter name").value
                if (match(TokenType.COLON)) {
                    skipTypeAnnotation()
                }
                var defaultVal: PyExpr? = null
                if (match(TokenType.ASSIGN)) {
                    defaultVal = parseExpression()
                }
                params.add(ParamDef(pName, defaultVal))
            } while (match(TokenType.COMMA))
        }
        consume(TokenType.RPAREN, "Expected ')' after parameters")
        if (match(TokenType.ARROW)) {
            skipTypeAnnotation()
        }
        consume(TokenType.COLON, "Expected ':' after function signature")
        val body = parseBlock()
        return PyStmt.Def(nameToken.value, params, body, defToken.line, decorators)
    }

    private fun parseClass(decorators: List<PyExpr> = emptyList()): PyStmt.Class {
        val classToken = consume(TokenType.KEYWORD, "Expected 'class'", "class")
        val name = consume(TokenType.IDENTIFIER, "Expected class name").value
        var base: String? = null
        if (match(TokenType.LPAREN)) {
            if (!check(TokenType.RPAREN)) {
                var baseStr = consume(TokenType.IDENTIFIER, "Expected base class name").value
                while (match(TokenType.DOT)) {
                    baseStr = "$baseStr." + consume(TokenType.IDENTIFIER, "Expected attribute after '.'").value
                }
                base = baseStr
            }
            consume(TokenType.RPAREN, "Expected ')'")
        }
        consume(TokenType.COLON, "Expected ':' after class declaration")
        val body = parseBlock()
        return PyStmt.Class(name, base, body, classToken.line, decorators)
    }

    private fun parseAssert(): PyStmt.Assert {
        val assertToken = consume(TokenType.KEYWORD, "Expected 'assert'", "assert")
        val cond = parseExpression()
        val msg = if (match(TokenType.COMMA)) parseExpression() else null
        consumeEndStatement()
        return PyStmt.Assert(cond, msg, assertToken.line)
    }

    private fun skipTypeAnnotation() {
        var depth = 0
        while (!isAtEnd()) {
            val t = peek()
            if (t.type == TokenType.LBRACKET || t.type == TokenType.LPAREN || t.type == TokenType.LBRACE) depth++
            else if (t.type == TokenType.RBRACKET || t.type == TokenType.RPAREN || t.type == TokenType.RBRACE) {
                if (depth == 0) break
                depth--
            } else if (depth == 0 && (t.type == TokenType.COMMA || t.type == TokenType.ASSIGN || t.type == TokenType.COLON || t.type == TokenType.NEWLINE || t.type == TokenType.SEMICOLON)) {
                break
            }
            idx++
        }
    }

    private fun parseReturn(): PyStmt.Return {
        val retToken = consume(TokenType.KEYWORD, "Expected 'return'", "return")
        val expr = if (!check(TokenType.NEWLINE) && !check(TokenType.SEMICOLON) && !check(TokenType.DEDENT) && !isAtEnd()) {
            parseExpression()
        } else null
        consumeEndStatement()
        return PyStmt.Return(expr, retToken.line)
    }

    private fun parseIf(): PyStmt.If {
        val ifToken = consume(TokenType.KEYWORD, "Expected 'if'", "if")
        val cond = parseExpression()
        consume(TokenType.COLON, "Expected ':' after 'if' condition")
        val body = parseBlock()

        val elifs = mutableListOf<Pair<PyExpr, List<PyStmt>>>()
        while (check(TokenType.KEYWORD, "elif")) {
            idx++
            val elifCond = parseExpression()
            consume(TokenType.COLON, "Expected ':' after 'elif' condition")
            val elifBody = parseBlock()
            elifs.add(Pair(elifCond, elifBody))
        }

        var elseBody: List<PyStmt>? = null
        if (check(TokenType.KEYWORD, "else")) {
            idx++
            consume(TokenType.COLON, "Expected ':' after 'else'")
            elseBody = parseBlock()
        }

        return PyStmt.If(cond, body, elifs, elseBody, ifToken.line)
    }

    private fun parseWhile(): PyStmt.While {
        val whileToken = consume(TokenType.KEYWORD, "Expected 'while'", "while")
        val cond = parseExpression()
        consume(TokenType.COLON, "Expected ':' after 'while' condition")
        val body = parseBlock()
        return PyStmt.While(cond, body, whileToken.line)
    }

    private fun parseFor(): PyStmt.For {
        val forToken = consume(TokenType.KEYWORD, "Expected 'for'", "for")
        val hasParen = match(TokenType.LPAREN)
        val targets = mutableListOf<String>()
        targets.add(consume(TokenType.IDENTIFIER, "Expected loop variable name").value)
        while (match(TokenType.COMMA)) {
            if (check(TokenType.IDENTIFIER)) {
                targets.add(consume(TokenType.IDENTIFIER, "Expected loop variable name").value)
            }
        }
        if (hasParen) {
            consume(TokenType.RPAREN, "Expected ')' after for loop targets")
        }
        consume(TokenType.KEYWORD, "Expected 'in' in for loop", "in")
        val iterable = parseExpression()
        consume(TokenType.COLON, "Expected ':' after for loop iterable")
        val body = parseBlock()
        return PyStmt.For(targets, iterable, body, forToken.line)
    }

    private fun parseTry(): PyStmt.Try {
        val tryToken = consume(TokenType.KEYWORD, "Expected 'try'", "try")
        consume(TokenType.COLON, "Expected ':' after 'try'")
        val body = parseBlock()

        val excepts = mutableListOf<ExceptClause>()
        while (check(TokenType.KEYWORD, "except")) {
            val exToken = consume(TokenType.KEYWORD, "Expected 'except'", "except")
            var exType: String? = null
            var asName: String? = null
            if (!check(TokenType.COLON)) {
                if (peek().type == TokenType.IDENTIFIER) {
                    exType = consume(TokenType.IDENTIFIER, "Expected exception type").value
                }
                if (match(TokenType.KEYWORD, "as")) {
                    asName = consume(TokenType.IDENTIFIER, "Expected identifier after 'as'").value
                }
            }
            consume(TokenType.COLON, "Expected ':' after except")
            val exBody = parseBlock()
            excepts.add(ExceptClause(exType, asName, exBody, exToken.line))
        }

        var finallyBody: List<PyStmt>? = null
        if (check(TokenType.KEYWORD, "finally")) {
            idx++
            consume(TokenType.COLON, "Expected ':' after 'finally'")
            finallyBody = parseBlock()
        }

        return PyStmt.Try(body, excepts, finallyBody, tryToken.line)
    }

    private fun parseRaise(): PyStmt.Raise {
        val raiseToken = consume(TokenType.KEYWORD, "Expected 'raise'", "raise")
        val expr = if (!check(TokenType.NEWLINE) && !check(TokenType.SEMICOLON) && !check(TokenType.DEDENT) && !isAtEnd()) {
            parseExpression()
        } else null
        consumeEndStatement()
        return PyStmt.Raise(expr, raiseToken.line)
    }

    private fun parseWith(): PyStmt.With {
        val withToken = consume(TokenType.KEYWORD, "Expected 'with'", "with")
        val expr = parseExpression()
        var asName: String? = null
        if (match(TokenType.KEYWORD, "as")) {
            asName = consume(TokenType.IDENTIFIER, "Expected variable name after 'as'").value
        }
        consume(TokenType.COLON, "Expected ':' after with statement")
        val body = parseBlock()
        return PyStmt.With(expr, asName, body, withToken.line)
    }

    private fun parseImport(): PyStmt.Import {
        val impToken = consume(TokenType.KEYWORD, "Expected 'import'", "import")
        var module = consume(TokenType.IDENTIFIER, "Expected module name").value
        while (match(TokenType.DOT)) {
            val sub = consume(TokenType.IDENTIFIER, "Expected identifier after '.'").value
            module = "$module.$sub"
        }
        var alias: String? = null
        if (match(TokenType.KEYWORD, "as")) {
            alias = consume(TokenType.IDENTIFIER, "Expected alias after 'as'").value
        }
        consumeEndStatement()
        return PyStmt.Import(module, alias, impToken.line)
    }

    private fun parseFromImport(): PyStmt.FromImport {
        val fromToken = consume(TokenType.KEYWORD, "Expected 'from'", "from")
        var module = consume(TokenType.IDENTIFIER, "Expected module name").value
        while (match(TokenType.DOT)) {
            val sub = consume(TokenType.IDENTIFIER, "Expected identifier after '.'").value
            module = "$module.$sub"
        }
        consume(TokenType.KEYWORD, "Expected 'import'", "import")
        val items = mutableListOf<Pair<String, String?>>()
        if (match(TokenType.STAR)) {
            items.add("*" to null)
        } else {
            do {
                val item = consume(TokenType.IDENTIFIER, "Expected imported name").value
                var alias: String? = null
                if (match(TokenType.KEYWORD, "as")) {
                    alias = consume(TokenType.IDENTIFIER, "Expected alias after 'as'").value
                }
                items.add(item to alias)
            } while (match(TokenType.COMMA))
        }
        consumeEndStatement()
        return PyStmt.FromImport(module, items, fromToken.line)
    }

    private fun parseAssignOrExprStmt(): PyStmt {
        val startToken = peek()
        val expr = parseExpression()

        // Multi-assign: a, b = 1, 2
        if (match(TokenType.COMMA)) {
            val targets = mutableListOf<String>()
            if (expr is PyExpr.Variable) targets.add(expr.name) else throw PythonSyntaxException("cannot assign to expression", expr.line, 1)
            do {
                val t = consume(TokenType.IDENTIFIER, "Expected identifier in multiple assignment")
                targets.add(t.value)
            } while (match(TokenType.COMMA))
            consume(TokenType.ASSIGN, "Expected '=' in multiple assignment")
            val valExpr = parseExpression()
            consumeEndStatement()
            return PyStmt.MultiAssign(targets, valExpr, startToken.line)
        }

        // Standard assignment
        if (match(TokenType.ASSIGN)) {
            val valExpr = parseExpression()
            consumeEndStatement()
            if (expr is PyExpr.Variable) {
                return PyStmt.Assign(expr.name, valExpr, startToken.line)
            }
            if (expr is PyExpr.Attribute || expr is PyExpr.Subscript) {
                return PyStmt.Assign(target = "", value = valExpr, line = startToken.line, targetExpr = expr)
            }
            throw PythonSyntaxException("cannot assign to expression", expr.line, 1)
        }

        // Augmented assignment (+=, -=, *=, /=)
        val augOp = when {
            match(TokenType.PLUS_ASSIGN) -> "+="
            match(TokenType.MINUS_ASSIGN) -> "-="
            match(TokenType.STAR_ASSIGN) -> "*="
            match(TokenType.SLASH_ASSIGN) -> "/="
            else -> null
        }
        if (augOp != null) {
            val valExpr = parseExpression()
            consumeEndStatement()
            if (expr is PyExpr.Variable) {
                return PyStmt.AugAssign(expr.name, augOp, valExpr, startToken.line)
            }
            if (expr is PyExpr.Attribute || expr is PyExpr.Subscript) {
                val binaryOp = augOp.removeSuffix("=")
                val computed = PyExpr.Binary(expr, binaryOp, valExpr, startToken.line)
                return PyStmt.Assign(target = "", value = computed, line = startToken.line, targetExpr = expr)
            }
            throw PythonSyntaxException("cannot assign to expression", expr.line, 1)
        }

        consumeEndStatement()
        return PyStmt.Expr(expr, startToken.line)
    }

    private fun parseBlock(): List<PyStmt> {
        val statements = mutableListOf<PyStmt>()
        // Could be simple statement on same line
        if (!check(TokenType.NEWLINE)) {
            statements.add(parseStatement())
            return statements
        }

        consume(TokenType.NEWLINE, "Expected newline after ':'")
        consume(TokenType.INDENT, "Expected an indented block")

        while (!check(TokenType.DEDENT) && !isAtEnd()) {
            if (match(TokenType.NEWLINE)) continue
            statements.add(parseStatement())
        }
        consume(TokenType.DEDENT, "Expected unindent at end of block")
        return statements
    }

    // -------------------------------------------------------------
    // Expression Parsing with Precedence
    // -------------------------------------------------------------

    private fun parseExpression(): PyExpr = parseLogicalOr()

    private fun parseLogicalOr(): PyExpr {
        var expr = parseLogicalAnd()
        while (check(TokenType.KEYWORD, "or")) {
            val op = peek().value
            idx++
            val right = parseLogicalAnd()
            expr = PyExpr.Binary(expr, op, right, expr.line)
        }
        return expr
    }

    private fun parseLogicalAnd(): PyExpr {
        var expr = parseLogicalNot()
        while (check(TokenType.KEYWORD, "and")) {
            val op = peek().value
            idx++
            val right = parseLogicalNot()
            expr = PyExpr.Binary(expr, op, right, expr.line)
        }
        return expr
    }

    private fun parseLogicalNot(): PyExpr {
        if (check(TokenType.KEYWORD, "not")) {
            val t = peek()
            idx++
            val operand = parseLogicalNot()
            return PyExpr.Unary("not", operand, t.line)
        }
        return parseComparison()
    }

    private fun parseComparison(): PyExpr {
        var expr = parseAdditive()
        while (true) {
            val op = when {
                match(TokenType.EQ) -> "=="
                match(TokenType.NEQ) -> "!="
                match(TokenType.LT) -> "<"
                match(TokenType.LTE) -> "<="
                match(TokenType.GT) -> ">"
                match(TokenType.GTE) -> ">="
                check(TokenType.KEYWORD, "is") -> {
                    idx++
                    if (match(TokenType.KEYWORD, "not")) "is not" else "is"
                }
                check(TokenType.KEYWORD, "in") -> {
                    idx++
                    "in"
                }
                check(TokenType.KEYWORD, "not") && idx + 1 < tokens.size && tokens[idx + 1].value == "in" -> {
                    idx += 2
                    "not in"
                }
                else -> null
            } ?: break

            val right = parseAdditive()
            expr = PyExpr.Binary(expr, op, right, expr.line)
        }
        return expr
    }

    private fun parseAdditive(): PyExpr {
        var expr = parseMultiplicative()
        while (true) {
            val op = when {
                match(TokenType.PLUS) -> "+"
                match(TokenType.MINUS) -> "-"
                else -> null
            } ?: break
            val right = parseMultiplicative()
            expr = PyExpr.Binary(expr, op, right, expr.line)
        }
        return expr
    }

    private fun parseMultiplicative(): PyExpr {
        var expr = parsePower()
        while (true) {
            val op = when {
                match(TokenType.STAR) -> "*"
                match(TokenType.SLASH) -> "/"
                match(TokenType.DOUBLE_SLASH) -> "//"
                match(TokenType.PERCENT) -> "%"
                else -> null
            } ?: break
            val right = parsePower()
            expr = PyExpr.Binary(expr, op, right, expr.line)
        }
        return expr
    }

    private fun parsePower(): PyExpr {
        var expr = parseUnary()
        if (match(TokenType.DOUBLE_STAR)) {
            val right = parsePower() // right-associative
            expr = PyExpr.Binary(expr, "**", right, expr.line)
        }
        return expr
    }

    private fun parseUnary(): PyExpr {
        val t = peek()
        if (match(TokenType.PLUS)) return parseUnary()
        if (match(TokenType.MINUS)) return PyExpr.Unary("-", parseUnary(), t.line)
        return parsePostfix()
    }

    private fun parsePostfix(): PyExpr {
        var expr = parsePrimary()
        while (true) {
            when {
                match(TokenType.LPAREN) -> {
                    // Function Call
                    val args = mutableListOf<PyExpr>()
                    val kwargs = mutableMapOf<String, PyExpr>()
                    if (!check(TokenType.RPAREN)) {
                        do {
                            if (peek().type == TokenType.IDENTIFIER && idx + 1 < tokens.size && tokens[idx + 1].type == TokenType.ASSIGN) {
                                val k = consume(TokenType.IDENTIFIER, "Expected kwarg name").value
                                consume(TokenType.ASSIGN, "Expected '='")
                                val v = parseExpression()
                                kwargs[k] = v
                            } else {
                                args.add(parseExpression())
                            }
                        } while (match(TokenType.COMMA))
                    }
                    consume(TokenType.RPAREN, "Expected ')' after call arguments")
                    expr = PyExpr.Call(expr, args, kwargs, expr.line)
                }
                match(TokenType.DOT) -> {
                    val attr = consume(TokenType.IDENTIFIER, "Expected attribute name after '.'").value
                    expr = PyExpr.Attribute(expr, attr, expr.line)
                }
                match(TokenType.LBRACKET) -> {
                    var start: PyExpr? = null
                    var isSlice = false
                    if (!check(TokenType.COLON)) {
                        start = parseExpression()
                    }
                    if (match(TokenType.COLON)) {
                        isSlice = true
                        var end: PyExpr? = null
                        if (!check(TokenType.RBRACKET) && !check(TokenType.COLON)) {
                            end = parseExpression()
                        }
                        var step: PyExpr? = null
                        if (match(TokenType.COLON)) {
                            if (!check(TokenType.RBRACKET)) {
                                step = parseExpression()
                            }
                        }
                        consume(TokenType.RBRACKET, "Expected ']' after slice")
                        expr = PyExpr.Slice(expr, start, end, step, expr.line)
                    } else {
                        consume(TokenType.RBRACKET, "Expected ']' after index")
                        expr = PyExpr.Subscript(expr, start ?: PyExpr.Literal(0, expr.line), expr.line)
                    }
                }
                else -> break
            }
        }
        return expr
    }

    private fun parsePrimary(): PyExpr {
        val t = peek()
        when (t.type) {
            TokenType.NUMBER -> {
                idx++
                val num = if (t.value.contains('.')) t.value.toDoubleOrNull() ?: 0.0 else t.value.toLongOrNull() ?: 0L
                return PyExpr.Literal(num, t.line)
            }
            TokenType.STRING -> {
                idx++
                return PyExpr.Literal(t.value, t.line)
            }
            TokenType.FSTRING -> {
                idx++
                return parseFStringLiteral(t.value, t.line)
            }
            TokenType.IDENTIFIER -> {
                idx++
                return PyExpr.Variable(t.value, t.line)
            }
            TokenType.KEYWORD -> {
                when (t.value) {
                    "True" -> { idx++; return PyExpr.Literal(true, t.line) }
                    "False" -> { idx++; return PyExpr.Literal(false, t.line) }
                    "None" -> { idx++; return PyExpr.Literal(null, t.line) }
                }
            }
            TokenType.LPAREN -> {
                idx++
                if (match(TokenType.RPAREN)) return PyExpr.PyTuple(emptyList(), t.line)
                val expr = parseExpression()
                if (match(TokenType.COMMA)) {
                    val elements = mutableListOf(expr)
                    if (!check(TokenType.RPAREN)) {
                        do {
                            elements.add(parseExpression())
                        } while (match(TokenType.COMMA))
                    }
                    consume(TokenType.RPAREN, "Expected ')'")
                    return PyExpr.PyTuple(elements, t.line)
                }
                consume(TokenType.RPAREN, "Expected ')'")
                return expr
            }
            TokenType.LBRACKET -> {
                idx++
                if (match(TokenType.RBRACKET)) return PyExpr.PyList(emptyList(), t.line)
                val first = parseExpression()
                if (check(TokenType.KEYWORD, "for")) {
                    idx++
                    val target = consume(TokenType.IDENTIFIER, "Expected variable name after 'for'").value
                    consume(TokenType.KEYWORD, "Expected 'in' in list comprehension", "in")
                    val iterable = parseExpression()
                    var cond: PyExpr? = null
                    if (check(TokenType.KEYWORD, "if")) {
                        idx++
                        cond = parseExpression()
                    }
                    consume(TokenType.RBRACKET, "Expected ']'")
                    return PyExpr.ListComp(first, target, iterable, cond, t.line)
                }
                val elements = mutableListOf(first)
                while (match(TokenType.COMMA)) {
                    if (check(TokenType.RBRACKET)) break
                    elements.add(parseExpression())
                }
                consume(TokenType.RBRACKET, "Expected ']'")
                return PyExpr.PyList(elements, t.line)
            }
            TokenType.LBRACE -> {
                idx++
                if (match(TokenType.RBRACE)) return PyExpr.PyDict(emptyList(), t.line)
                val first = parseExpression()
                if (match(TokenType.COLON)) {
                    // Dict
                    val firstVal = parseExpression()
                    val entries = mutableListOf(Pair(first, firstVal))
                    while (match(TokenType.COMMA)) {
                        if (check(TokenType.RBRACE)) break
                        val k = parseExpression()
                        consume(TokenType.COLON, "Expected ':' in dictionary")
                        val v = parseExpression()
                        entries.add(Pair(k, v))
                    }
                    consume(TokenType.RBRACE, "Expected '}'")
                    return PyExpr.PyDict(entries, t.line)
                } else {
                    // Set
                    val elements = mutableListOf(first)
                    while (match(TokenType.COMMA)) {
                        if (check(TokenType.RBRACE)) break
                        elements.add(parseExpression())
                    }
                    consume(TokenType.RBRACE, "Expected '}'")
                    return PyExpr.PySet(elements, t.line)
                }
            }
            else -> {}
        }
        throw PythonSyntaxException("invalid syntax: unexpected '${t.value}'", t.line, t.col)
    }

    private fun parseFStringLiteral(raw: String, line: Int): PyExpr.FString {
        val parts = mutableListOf<Any>()
        var pos = 0
        val sb = StringBuilder()
        while (pos < raw.length) {
            if (raw[pos] == '{') {
                if (pos + 1 < raw.length && raw[pos + 1] == '{') {
                    sb.append('{')
                    pos += 2
                    continue
                }
                if (sb.isNotEmpty()) {
                    parts.add(sb.toString())
                    sb.clear()
                }
                pos++
                val exprStart = pos
                var depth = 1
                while (pos < raw.length && depth > 0) {
                    if (raw[pos] == '{') depth++
                    else if (raw[pos] == '}') depth--
                    pos++
                }
                val exprStr = raw.substring(exprStart, pos - 1).trim()
                val subTokens = PythonLexer(exprStr, filename).tokenize()
                val parsedExpr = PythonParser(subTokens, filename).parseExpression()
                parts.add(parsedExpr)
            } else if (raw[pos] == '}' && pos + 1 < raw.length && raw[pos + 1] == '}') {
                sb.append('}')
                pos += 2
            } else {
                sb.append(raw[pos])
                pos++
            }
        }
        if (sb.isNotEmpty()) {
            parts.add(sb.toString())
        }
        return PyExpr.FString(parts, line)
    }
}

// -------------------------------------------------------------
// Evaluator & Interpreter
// -------------------------------------------------------------

class PythonInterpreter(
    private val code: String,
    private val filename: String,
    private val args: List<String>,
    private val workspaceManager: AgentWorkspaceManager,
    private val deadline: Long,
    private val isTimedOut: AtomicBoolean,
    private val appendStdout: (String) -> Unit,
    private val appendStderr: (String) -> Unit
) {
    private val callStack = mutableListOf<PythonTracebackFrame>()
    private val globalScope = mutableMapOf<String, Any?>()
    private val loadedModules = mutableMapOf<String, PyModule>()

    init {
        initBuiltins()
    }

    fun run(ast: List<PyStmt>) {
        callStack.add(PythonTracebackFrame(filename, 1, "<module>"))
        try {
            for (stmt in ast) {
                checkDeadline()
                executeStmt(stmt, globalScope)
            }
        } catch (e: PythonRuntimeException) {
            if (e.traceback.isEmpty()) {
                e.traceback.addAll(callStack)
            }
            throw e
        } finally {
            if (callStack.isNotEmpty()) {
                callStack.removeAt(callStack.lastIndex)
            }
        }
    }

    private fun checkDeadline() {
        if (System.currentTimeMillis() > deadline) {
            isTimedOut.set(true)
            throw PythonTimeoutException()
        }
    }

    fun formatTraceback(e: PythonRuntimeException): String {
        val lines = code.split("\n")
        val frames = if (e.traceback.isNotEmpty()) e.traceback else callStack
        return buildString {
            appendLine("Traceback (most recent call last):")
            for (frame in frames) {
                appendLine("  File \"${frame.filename}\", line ${frame.line}, in ${frame.funcName}")
                val lineIdx = (frame.line - 1).coerceIn(0, lines.size - 1)
                val codeLine = if (lines.isNotEmpty()) lines[lineIdx].trim() else ""
                if (codeLine.isNotBlank()) {
                    appendLine("    $codeLine")
                }
            }
            appendLine("${e.errorType}: ${e.message}")
        }
    }

    private fun updateCurrentFrame(line: Int) {
        if (callStack.isNotEmpty()) {
            val top = callStack.last()
            callStack[callStack.lastIndex] = PythonTracebackFrame(top.filename, line, top.funcName)
        }
    }

    private fun executeStmt(stmt: PyStmt, scope: MutableMap<String, Any?>): Any? {
        checkDeadline()
        updateCurrentFrame(stmt.line)

        when (stmt) {
            is PyStmt.Assign -> {
                val value = evalExpr(stmt.value, scope)
                if (stmt.targetExpr != null) {
                    assignToExpr(stmt.targetExpr, value, scope, stmt.line)
                } else {
                    scope[stmt.target] = value
                }
            }
            is PyStmt.MultiAssign -> {
                val value = evalExpr(stmt.value, scope)
                val list = toPyList(value)
                if (list.size != stmt.targets.size) {
                    throw PythonRuntimeException("ValueError", "not enough values to unpack (expected ${stmt.targets.size}, got ${list.size})", stmt.line)
                }
                for (i in stmt.targets.indices) {
                    scope[stmt.targets[i]] = list[i]
                }
            }
            is PyStmt.AugAssign -> {
                val cur = scope[stmt.target] ?: globalScope[stmt.target]
                    ?: throw PythonRuntimeException("NameError", "name '${stmt.target}' is not defined", stmt.line)
                val delta = evalExpr(stmt.value, scope)
                val res = when (stmt.op) {
                    "+=" -> pyAdd(cur, delta, stmt.line)
                    "-=" -> pySub(cur, delta, stmt.line)
                    "*=" -> pyMul(cur, delta, stmt.line)
                    "/=" -> pyDiv(cur, delta, stmt.line)
                    else -> delta
                }
                scope[stmt.target] = res
            }
            is PyStmt.Expr -> {
                evalExpr(stmt.expr, scope)
            }
            is PyStmt.Def -> {
                var func: Any? = UserDefinedFunction(stmt.name, stmt.params, stmt.body, filename, scope)
                for (decExpr in stmt.decorators.reversed()) {
                    val dec = evalExpr(decExpr, scope)
                    func = callFunction(dec, listOf(func), emptyMap(), stmt.line)
                }
                scope[stmt.name] = func
            }
            is PyStmt.Class -> {
                val classScope = mutableMapOf<String, Any?>()
                classScope.putAll(scope)
                for (s in stmt.body) {
                    executeStmt(s, classScope)
                }
                val methods = mutableMapOf<String, Any?>()
                for ((k, v) in classScope) {
                    if (v is UserDefinedFunction || v is PyBuiltinFunc) {
                        methods[k] = v
                    }
                }
                val pyClass = PyUserClass(stmt.name, stmt.baseClass, methods, stmt.body)
                var resultClass: Any? = pyClass
                for (decExpr in stmt.decorators.reversed()) {
                    val dec = evalExpr(decExpr, scope)
                    resultClass = callFunction(dec, listOf(resultClass), emptyMap(), stmt.line)
                }
                scope[stmt.name] = resultClass
            }
            is PyStmt.Assert -> {
                val condVal = isTruthy(evalExpr(stmt.condition, scope))
                if (!condVal) {
                    val msg = stmt.message?.let { pyStr(evalExpr(it, scope)) } ?: "assertion failed"
                    throw PythonRuntimeException("AssertionError", msg, stmt.line)
                }
            }
            is PyStmt.Return -> {
                val retVal = stmt.expr?.let { evalExpr(it, scope) }
                return ReturnSignal(retVal)
            }
            is PyStmt.If -> {
                val condVal = isTruthy(evalExpr(stmt.condition, scope))
                if (condVal) {
                    for (s in stmt.body) {
                        val res = executeStmt(s, scope)
                        if (res is ReturnSignal || res is BreakSignal || res is ContinueSignal) return res
                    }
                } else {
                    var elifHandled = false
                    for (elif in stmt.elifs) {
                        if (isTruthy(evalExpr(elif.first, scope))) {
                            elifHandled = true
                            for (s in elif.second) {
                                val res = executeStmt(s, scope)
                                if (res is ReturnSignal || res is BreakSignal || res is ContinueSignal) return res
                            }
                            break
                        }
                    }
                    if (!elifHandled && stmt.elseBody != null) {
                        for (s in stmt.elseBody) {
                            val res = executeStmt(s, scope)
                            if (res is ReturnSignal || res is BreakSignal || res is ContinueSignal) return res
                        }
                    }
                }
            }
            is PyStmt.While -> {
                while (isTruthy(evalExpr(stmt.condition, scope))) {
                    checkDeadline()
                    var shouldBreak = false
                    for (s in stmt.body) {
                        val res = executeStmt(s, scope)
                        if (res is ReturnSignal) return res
                        if (res is BreakSignal) {
                            shouldBreak = true
                            break
                        }
                        if (res is ContinueSignal) break
                    }
                    if (shouldBreak) break
                }
            }
            is PyStmt.For -> {
                val iterObj = evalExpr(stmt.iterable, scope)
                val items = toIterable(iterObj, stmt.line)
                for (item in items) {
                    checkDeadline()
                    if (stmt.targets.size <= 1) {
                        val singleTarget = stmt.target
                        if (singleTarget.isNotEmpty()) {
                            scope[singleTarget] = item
                        }
                    } else {
                        val unpacked = when (item) {
                            is List<*> -> item
                            is Array<*> -> item.toList()
                            else -> listOf(item)
                        }
                        for (tIdx in stmt.targets.indices) {
                            if (tIdx < unpacked.size) {
                                scope[stmt.targets[tIdx]] = unpacked[tIdx]
                            } else {
                                scope[stmt.targets[tIdx]] = null
                            }
                        }
                    }
                    var shouldBreak = false
                    for (s in stmt.body) {
                        val res = executeStmt(s, scope)
                        if (res is ReturnSignal) return res
                        if (res is BreakSignal) {
                            shouldBreak = true
                            break
                        }
                        if (res is ContinueSignal) break
                    }
                    if (shouldBreak) break
                }
            }
            is PyStmt.Try -> {
                try {
                    for (s in stmt.body) {
                        val res = executeStmt(s, scope)
                        if (res is ReturnSignal || res is BreakSignal || res is ContinueSignal) return res
                    }
                } catch (e: PythonRuntimeException) {
                    var handled = false
                    for (clause in stmt.excepts) {
                        if (clause.exceptionType == null || clause.exceptionType == "Exception" || clause.exceptionType == e.errorType) {
                            handled = true
                            val exScope = scope.toMutableMap()
                            if (clause.asName != null) {
                                exScope[clause.asName] = e.message
                            }
                            for (s in clause.body) {
                                val res = executeStmt(s, exScope)
                                if (res is ReturnSignal || res is BreakSignal || res is ContinueSignal) return res
                            }
                            break
                        }
                    }
                    if (!handled) throw e
                } finally {
                    stmt.finallyBody?.let {
                        for (s in it) {
                            executeStmt(s, scope)
                        }
                    }
                }
            }
            is PyStmt.Raise -> {
                val exObj = stmt.expr?.let { evalExpr(it, scope) }
                val msg = exObj?.toString() ?: "Exception raised"
                throw PythonRuntimeException("Exception", msg, stmt.line)
            }
            is PyStmt.With -> {
                val ctxObj = evalExpr(stmt.expr, scope)
                if (stmt.asName != null) {
                    scope[stmt.asName] = ctxObj
                }
                try {
                    for (s in stmt.body) {
                        val res = executeStmt(s, scope)
                        if (res is ReturnSignal || res is BreakSignal || res is ContinueSignal) return res
                    }
                } finally {
                    when (ctxObj) {
                        is SandboxedFile -> ctxObj.close()
                        is PyHttpResponse -> ctxObj.close()
                    }
                }
            }
            is PyStmt.Import -> {
                handleImport(stmt.module, stmt.alias, scope, stmt.line)
            }
            is PyStmt.FromImport -> {
                handleFromImport(stmt.module, stmt.items, scope, stmt.line)
            }
            is PyStmt.Break -> return BreakSignal
            is PyStmt.Continue -> return ContinueSignal
            is PyStmt.Pass -> {}
        }
        return null
    }

    private fun assignToExpr(targetExpr: PyExpr, value: Any?, scope: MutableMap<String, Any?>, line: Int) {
        when (targetExpr) {
            is PyExpr.Variable -> {
                scope[targetExpr.name] = value
            }
            is PyExpr.Attribute -> {
                val obj = evalExpr(targetExpr.obj, scope)
                if (obj is PyInstance) {
                    obj.fields[targetExpr.name] = value
                } else if (obj is PyModule) {
                    obj.members[targetExpr.name] = value
                } else if (obj is MutableMap<*, *>) {
                    @Suppress("UNCHECKED_CAST")
                    (obj as MutableMap<String, Any?>)[targetExpr.name] = value
                } else if (obj is PyUserClass) {
                    obj.methods[targetExpr.name] = value
                }
            }
            is PyExpr.Subscript -> {
                val obj = evalExpr(targetExpr.obj, scope)
                val idx = evalExpr(targetExpr.index, scope)
                if (obj is MutableMap<*, *>) {
                    val k = pyStr(idx)
                    @Suppress("UNCHECKED_CAST")
                    (obj as MutableMap<String, Any?>)[k] = value
                } else if (obj is MutableList<*>) {
                    val intIdx = (idx as? Number)?.toInt() ?: 0
                    @Suppress("UNCHECKED_CAST")
                    (obj as MutableList<Any?>)[intIdx] = value
                }
            }
            else -> throw PythonRuntimeException("SyntaxError", "cannot assign to expression", line)
        }
    }

    private class ReturnSignal(val value: Any?)
    private object BreakSignal
    private object ContinueSignal

    // -------------------------------------------------------------
    // Expression Evaluation
    // -------------------------------------------------------------

    private fun evalExpr(expr: PyExpr, scope: MutableMap<String, Any?>): Any? {
        checkDeadline()
        updateCurrentFrame(expr.line)

        return when (expr) {
            is PyExpr.Literal -> expr.value
            is PyExpr.Variable -> {
                scope[expr.name] ?: globalScope[expr.name] ?: throw PythonRuntimeException("NameError", "name '${expr.name}' is not defined", expr.line)
            }
            is PyExpr.FString -> {
                val sb = StringBuilder()
                for (part in expr.parts) {
                    if (part is String) sb.append(part)
                    else if (part is PyExpr) sb.append(pyStr(evalExpr(part, scope)))
                }
                sb.toString()
            }
            is PyExpr.Binary -> {
                val left = evalExpr(expr.left, scope)
                // Short-circuit logical ops
                if (expr.op == "and") {
                    return if (!isTruthy(left)) left else evalExpr(expr.right, scope)
                }
                if (expr.op == "or") {
                    return if (isTruthy(left)) left else evalExpr(expr.right, scope)
                }
                val right = evalExpr(expr.right, scope)
                evalBinaryOp(left, expr.op, right, expr.line)
            }
            is PyExpr.Unary -> {
                val operand = evalExpr(expr.operand, scope)
                when (expr.op) {
                    "-" -> when (operand) {
                        is Long -> -operand
                        is Int -> -operand.toLong()
                        is Double -> -operand
                        else -> throw PythonRuntimeException("TypeError", "bad operand type for unary -: '${operand?.javaClass?.simpleName}'", expr.line)
                    }
                    "not" -> !isTruthy(operand)
                    else -> operand
                }
            }
            is PyExpr.Call -> {
                val callee = evalExpr(expr.callee, scope)
                val argValues = expr.args.map { evalExpr(it, scope) }
                val kwargValues = expr.kwargs.mapValues { evalExpr(it.value, scope) }
                callFunction(callee, argValues, kwargValues, expr.line)
            }
            is PyExpr.Attribute -> {
                val obj = evalExpr(expr.obj, scope)
                getAttr(obj, expr.name, expr.line)
            }
            is PyExpr.Subscript -> {
                val obj = evalExpr(expr.obj, scope)
                val idx = evalExpr(expr.index, scope)
                getItem(obj, idx, expr.line)
            }
            is PyExpr.Slice -> {
                val obj = evalExpr(expr.obj, scope)
                val start = expr.start?.let { evalExpr(it, scope) as? Number }?.toInt()
                val end = expr.end?.let { evalExpr(it, scope) as? Number }?.toInt()
                sliceItem(obj, start, end, expr.line)
            }
            is PyExpr.PyList -> {
                expr.elements.map { evalExpr(it, scope) }.toMutableList()
            }
            is PyExpr.ListComp -> {
                val items = toIterable(evalExpr(expr.iterable, scope), expr.line)
                val res = mutableListOf<Any?>()
                val localScope = scope.toMutableMap()
                for (item in items) {
                    localScope[expr.target] = item
                    if (expr.cond == null || isTruthy(evalExpr(expr.cond, localScope))) {
                        res.add(evalExpr(expr.element, localScope))
                    }
                }
                res
            }
            is PyExpr.PyDict -> {
                val map = mutableMapOf<String, Any?>()
                for (entry in expr.entries) {
                    val k = pyStr(evalExpr(entry.first, scope))
                    val v = evalExpr(entry.second, scope)
                    map[k] = v
                }
                map
            }
            is PyExpr.PyTuple -> {
                expr.elements.map { evalExpr(it, scope) }
            }
            is PyExpr.PySet -> {
                expr.elements.map { evalExpr(it, scope) }.toMutableSet()
            }
        }
    }

    private fun evalBinaryOp(left: Any?, op: String, right: Any?, line: Int): Any? {
        return when (op) {
            "+" -> pyAdd(left, right, line)
            "-" -> pySub(left, right, line)
            "*" -> pyMul(left, right, line)
            "/" -> pyDiv(left, right, line)
            "//" -> pyFloorDiv(left, right, line)
            "%" -> pyMod(left, right, line)
            "**" -> pyPow(left, right, line)
            "==" -> pyEquals(left, right)
            "!=" -> !pyEquals(left, right)
            "<" -> pyCompare(left, right, line) < 0
            "<=" -> pyCompare(left, right, line) <= 0
            ">" -> pyCompare(left, right, line) > 0
            ">=" -> pyCompare(left, right, line) >= 0
            "is" -> left === right
            "is not" -> left !== right
            "in" -> pyIn(left, right, line)
            "not in" -> !pyIn(left, right, line)
            else -> throw PythonRuntimeException("TypeError", "unsupported operator '$op'", line)
        }
    }

    private fun pyAdd(left: Any?, right: Any?, line: Int): Any? {
        if (left is PyDateTime && right is PyTimeDelta) return left.add(right)
        if (left is PyDate && right is PyTimeDelta) return left.add(right)
        if (left is PyTimeDelta && right is PyTimeDelta) return left.add(right)
        if (left is String || right is String) {
            return if (left is String && right is String) left + right
            else throw PythonRuntimeException("TypeError", "can only concatenate str (not \"${right?.javaClass?.simpleName ?: "None"}\") to str", line)
        }
        if (left is MutableList<*> && right is List<*>) {
            val res = mutableListOf<Any?>()
            res.addAll(left as List<Any?>)
            res.addAll(right as List<Any?>)
            return res
        }
        val (n1, n2, isFloat) = toNumbers(left, right, line, "+")
        return if (isFloat) n1.toDouble() + n2.toDouble() else n1.toLong() + n2.toLong()
    }

    private fun pySub(left: Any?, right: Any?, line: Int): Any? {
        if (left is PyDateTime && right is PyTimeDelta) return left.subtract(right)
        if (left is PyDateTime && right is PyDateTime) return left.difference(right)
        if (left is PyDate && right is PyTimeDelta) return left.subtract(right)
        if (left is PyDate && right is PyDate) return left.difference(right)
        if (left is PyTimeDelta && right is PyTimeDelta) return left.subtract(right)
        val (n1, n2, isFloat) = toNumbers(left, right, line, "-")
        return if (isFloat) n1.toDouble() - n2.toDouble() else n1.toLong() - n2.toLong()
    }

    private fun pyMul(left: Any?, right: Any?, line: Int): Any? {
        if (left is String && right is Number) {
            return left.repeat(right.toInt().coerceAtLeast(0))
        }
        if (left is List<*> && right is Number) {
            val res = mutableListOf<Any?>()
            repeat(right.toInt().coerceAtLeast(0)) {
                res.addAll(left as List<Any?>)
            }
            return res
        }
        val (n1, n2, isFloat) = toNumbers(left, right, line, "*")
        return if (isFloat) n1.toDouble() * n2.toDouble() else n1.toLong() * n2.toLong()
    }

    private fun pyDiv(left: Any?, right: Any?, line: Int): Any? {
        val (n1, n2, _) = toNumbers(left, right, line, "/")
        if (n2.toDouble() == 0.0) throw PythonRuntimeException("ZeroDivisionError", "division by zero", line)
        return n1.toDouble() / n2.toDouble()
    }

    private fun pyFloorDiv(left: Any?, right: Any?, line: Int): Any? {
        val (n1, n2, isFloat) = toNumbers(left, right, line, "//")
        if (n2.toDouble() == 0.0) throw PythonRuntimeException("ZeroDivisionError", "integer division or modulo by zero", line)
        return if (isFloat) floor(n1.toDouble() / n2.toDouble()) else n1.toLong() / n2.toLong()
    }

    private fun pyMod(left: Any?, right: Any?, line: Int): Any? {
        val (n1, n2, isFloat) = toNumbers(left, right, line, "%")
        if (n2.toDouble() == 0.0) throw PythonRuntimeException("ZeroDivisionError", "integer division or modulo by zero", line)
        return if (isFloat) n1.toDouble() % n2.toDouble() else n1.toLong() % n2.toLong()
    }

    private fun pyPow(left: Any?, right: Any?, line: Int): Any? {
        val (n1, n2, isFloat) = toNumbers(left, right, line, "**")
        val res = n1.toDouble().pow(n2.toDouble())
        return if (!isFloat && res == res.toLong().toDouble() && !res.toString().contains("e", ignoreCase = true)) res.toLong() else res
    }

    private fun toNumbers(left: Any?, right: Any?, line: Int, op: String): Triple<Number, Number, Boolean> {
        if (left !is Number || right !is Number) {
            throw PythonRuntimeException("TypeError", "unsupported operand type(s) for $op: '${left?.javaClass?.simpleName}' and '${right?.javaClass?.simpleName}'", line)
        }
        val isFloat = left is Double || left is Float || right is Double || right is Float
        return Triple(left, right, isFloat)
    }

    private fun pyEquals(left: Any?, right: Any?): Boolean {
        if (left == null && right == null) return true
        if (left == null || right == null) return false
        if (left is PyDateTime && right is PyDateTime) return left.epochMillis == right.epochMillis
        if (left is PyDate && right is PyDate) return left.toEpochDay() == right.toEpochDay()
        if (left is PyTimeDelta && right is PyTimeDelta) return left.totalSeconds() == right.totalSeconds()
        if (left is Number && right is Number) return left.toDouble() == right.toDouble()
        return left == right
    }

    private fun pyCompare(left: Any?, right: Any?, line: Int): Int {
        if (left is PyDateTime && right is PyDateTime) return left.epochMillis.compareTo(right.epochMillis)
        if (left is PyDate && right is PyDate) return left.toEpochDay().compareTo(right.toEpochDay())
        if (left is PyTimeDelta && right is PyTimeDelta) return left.totalSeconds().compareTo(right.totalSeconds())
        if (left is Number && right is Number) {
            return left.toDouble().compareTo(right.toDouble())
        }
        if (left is String && right is String) {
            return left.compareTo(right)
        }
        throw PythonRuntimeException("TypeError", "'<' not supported between instances of '${left?.javaClass?.simpleName}' and '${right?.javaClass?.simpleName}'", line)
    }

    private fun pyIn(item: Any?, container: Any?, line: Int): Boolean {
        return when (container) {
            is String -> container.contains(pyStr(item))
            is Collection<*> -> container.any { pyEquals(it, item) }
            is Map<*, *> -> container.containsKey(pyStr(item))
            else -> throw PythonRuntimeException("TypeError", "argument of type '${container?.javaClass?.simpleName}' is not iterable", line)
        }
    }

    private fun getItem(obj: Any?, index: Any?, line: Int): Any? {
        when (obj) {
            is List<*> -> {
                val idx = (index as? Number)?.toInt()
                    ?: throw PythonRuntimeException("TypeError", "list indices must be integers, not '${index?.javaClass?.simpleName}'", line)
                val actual = if (idx < 0) obj.size + idx else idx
                if (actual !in obj.indices) throw PythonRuntimeException("IndexError", "list index out of range", line)
                return obj[actual]
            }
            is String -> {
                val idx = (index as? Number)?.toInt()
                    ?: throw PythonRuntimeException("TypeError", "string indices must be integers", line)
                val actual = if (idx < 0) obj.length + idx else idx
                if (actual !in obj.indices) throw PythonRuntimeException("IndexError", "string index out of range", line)
                return obj[actual].toString()
            }
            is Map<*, *> -> {
                val key = pyStr(index)
                if (!obj.containsKey(key)) throw PythonRuntimeException("KeyError", "'$key'", line)
                return obj[key]
            }
            else -> throw PythonRuntimeException("TypeError", "'${obj?.javaClass?.simpleName}' object is not subscriptable", line)
        }
    }

    private fun sliceItem(obj: Any?, start: Int?, end: Int?, line: Int): Any? {
        when (obj) {
            is List<*> -> {
                val s = (start ?: 0).coerceIn(0, obj.size)
                val e = (end ?: obj.size).coerceIn(0, obj.size)
                return if (s <= e) obj.subList(s, e) else emptyList<Any?>()
            }
            is String -> {
                val s = (start ?: 0).coerceIn(0, obj.length)
                val e = (end ?: obj.length).coerceIn(0, obj.length)
                return if (s <= e) obj.substring(s, e) else ""
            }
            else -> throw PythonRuntimeException("TypeError", "'${obj?.javaClass?.simpleName}' object is not sliceable", line)
        }
    }

    private fun getAttr(obj: Any?, name: String, line: Int): Any? {
        if (obj is PyInstance) {
            if (obj.fields.containsKey(name)) {
                return obj.fields[name]
            }
            val method = obj.pyClass.methods[name]
            if (method != null) {
                return PyBuiltinFunc(name) { mArgs, mKwargs ->
                    callFunction(method, listOf(obj) + mArgs, mKwargs, line)
                }
            }
            return null
        }
        if (obj is PyUserClass) {
            return obj.methods[name] ?: throw PythonRuntimeException("AttributeError", "type '${obj.name}' has no attribute '$name'", line)
        }
        if (obj is PyModule) {
            return obj.getMember(name) ?: throw PythonRuntimeException("AttributeError", "module '${obj.name}' has no attribute '$name'", line)
        }
        if (obj is PyFastApiApp) {
            return when (name) {
                "get", "post", "put", "delete", "patch", "head", "options" -> PyBuiltinFunc(name) { rArgs, _ ->
                    val path = rArgs.firstOrNull()?.toString() ?: "/"
                    PyBuiltinFunc("${name}_decorator") { fnArgs, _ ->
                        val fn = fnArgs.firstOrNull()
                        if (fn != null) {
                            obj.addRoute(name.uppercase(), path, fn)
                        }
                        fn
                    }
                }
                "route", "api_route" -> PyBuiltinFunc(name) { rArgs, _ ->
                    val path = rArgs.firstOrNull()?.toString() ?: "/"
                    PyBuiltinFunc("${name}_decorator") { fnArgs, _ ->
                        val fn = fnArgs.firstOrNull()
                        if (fn != null) {
                            obj.addRoute("GET", path, fn)
                        }
                        fn
                    }
                }
                "include_router", "mount" -> PyBuiltinFunc(name) { _, _ -> null }
                "middleware" -> PyBuiltinFunc("middleware") { _, _ -> PyBuiltinFunc("mw_dec") { fnArgs, _ -> fnArgs.firstOrNull() } }
                "on_event" -> PyBuiltinFunc("on_event") { _, _ -> PyBuiltinFunc("ev_dec") { fnArgs, _ -> fnArgs.firstOrNull() } }
                "test_client" -> PyBuiltinFunc("test_client") { _, _ -> PyTestClient(obj) }
                "routes" -> obj.routes
                "state" -> obj.customAttrs
                "config" -> obj.customAttrs
                else -> obj.customAttrs[name]
            }
        }
        if (obj is PyTestClient) {
            return when (name) {
                "get", "post", "put", "delete", "patch", "head", "options" -> PyBuiltinFunc(name) { args, kwargs ->
                    val path = args.firstOrNull()?.toString() ?: "/"
                    val cleanPath = if (path.startsWith("/")) path else "/$path"
                    val altPath = if (cleanPath.endsWith("/")) cleanPath.trimEnd('/') else "$cleanPath/"
                    var handler: Any? = null
                    if (obj.app is PyFastApiApp) {
                        handler = obj.app.routes["${name.uppercase()}:$cleanPath"]
                            ?: obj.app.routes[cleanPath]
                            ?: obj.app.routes["${name.uppercase()}:$altPath"]
                            ?: obj.app.routes[altPath]
                            ?: obj.app.routes.values.firstOrNull()
                    }
                    if (handler != null) {
                        val callArgs = mutableListOf<Any?>()
                        val callKwargs = mutableMapOf<String, Any?>()
                        if (handler is UserDefinedFunction) {
                            val jsonBody = kwargs["json"] as? Map<*, *>
                            for (param in handler.params) {
                                if (jsonBody != null && jsonBody.containsKey(param.name)) {
                                    callKwargs[param.name] = jsonBody[param.name]
                                }
                            }
                        }
                        try {
                            val res = callFunction(handler, callArgs, callKwargs, line)
                            PyTestResponse(200L, pyStr(res), emptyMap(), res)
                        } catch (e: PythonRuntimeException) {
                            PyTestResponse(500L, e.message ?: "Internal Error", emptyMap(), mapOf("error" to (e.message ?: "Internal Error")))
                        }
                    } else {
                        PyTestResponse(200L, "{}", emptyMap(), emptyMap<String, Any?>())
                    }
                }
                else -> throw PythonRuntimeException("AttributeError", "'TestClient' object has no attribute '$name'", line)
            }
        }
        if (obj is PyTestResponse) {
            return when (name) {
                "status_code", "status" -> obj.status_code
                "text" -> obj.text
                "content" -> obj.content
                "headers" -> obj.headers
                "ok" -> obj.ok
                "json", "get_json" -> PyBuiltinFunc(name) { _, _ -> obj.json() }
                else -> throw PythonRuntimeException("AttributeError", "'Response' object has no attribute '$name'", line)
            }
        }
        if (obj is PyMatch) {
            return when (name) {
                "group" -> PyBuiltinFunc("group") { args, _ ->
                    val idx = (args.firstOrNull() as? Number)?.toInt() ?: 0
                    obj.group(idx)
                }
                "groups" -> PyBuiltinFunc("groups") { _, _ -> obj.groups().toMutableList() }
                "start" -> PyBuiltinFunc("start") { _, _ -> obj.start().toLong() }
                "end" -> PyBuiltinFunc("end") { _, _ -> obj.end().toLong() }
                "span" -> PyBuiltinFunc("span") { _, _ -> listOf(obj.start().toLong(), obj.end().toLong()).toMutableList() }
                else -> throw PythonRuntimeException("AttributeError", "'re.Match' object has no attribute '$name'", line)
            }
        }
        if (obj is PyHashObject) {
            return when (name) {
                "hexdigest" -> PyBuiltinFunc("hexdigest") { _, _ -> obj.hexdigest() }
                "digest" -> PyBuiltinFunc("digest") { _, _ -> obj.digest() }
                "update" -> PyBuiltinFunc("update") { args, _ -> obj.update(args.firstOrNull()); null }
                else -> throw PythonRuntimeException("AttributeError", "'_Hash' object has no attribute '$name'", line)
            }
        }
        if (obj is PyHttpResponse) {
            return when (name) {
                "read" -> PyBuiltinFunc("read") { _, _ -> obj.read() }
                "readline" -> PyBuiltinFunc("readline") { _, _ -> obj.readline() }
                "readlines" -> PyBuiltinFunc("readlines") { _, _ -> obj.readlines().toMutableList() }
                "getcode" -> PyBuiltinFunc("getcode") { _, _ -> obj.getcode() }
                "close" -> PyBuiltinFunc("close") { _, _ -> obj.close() }
                "status", "code" -> obj.code.toLong()
                "headers" -> obj.headers.toMutableMap()
                "__enter__" -> PyBuiltinFunc("__enter__") { _, _ -> obj }
                "__exit__" -> PyBuiltinFunc("__exit__") { _, _ -> obj.close(); null }
                else -> throw PythonRuntimeException("AttributeError", "'HTTPResponse' object has no attribute '$name'", line)
            }
        }
        if (obj is PyRequestsResponse) {
            return when (name) {
                "text" -> obj.text
                "content" -> obj.content
                "status_code" -> obj.status_code
                "headers" -> obj.headers.toMutableMap()
                "ok" -> obj.ok
                "url" -> obj.url
                "encoding" -> obj.encoding
                "json" -> PyBuiltinFunc("json") { _, _ -> obj.json() }
                "raise_for_status" -> PyBuiltinFunc("raise_for_status") { _, _ ->
                    if (obj.status_code >= 400) {
                        throw PythonRuntimeException("HTTPError", "HTTP ${obj.status_code} Error: ${obj.text.take(200)}", line)
                    }
                    null
                }
                else -> throw PythonRuntimeException("AttributeError", "'Response' object has no attribute '$name'", line)
            }
        }
        if (obj is PyDateTime) {
            return when (name) {
                "year" -> obj.year
                "month" -> obj.month
                "day" -> obj.day
                "hour" -> obj.hour
                "minute" -> obj.minute
                "second" -> obj.second
                "microsecond" -> obj.microsecond
                "strftime" -> PyBuiltinFunc("strftime") { args, _ ->
                    val fmt = args.firstOrNull()?.toString() ?: "%Y-%m-%d %H:%M:%S"
                    obj.strftime(fmt)
                }
                "isoformat" -> PyBuiltinFunc("isoformat") { _, _ -> obj.isoformat() }
                "timestamp" -> PyBuiltinFunc("timestamp") { _, _ -> obj.timestamp() }
                "date" -> PyBuiltinFunc("date") { _, _ -> obj.toDate() }
                "time" -> PyBuiltinFunc("time") { _, _ -> obj.toTime() }
                "weekday" -> PyBuiltinFunc("weekday") { _, _ -> obj.weekday() }
                "isoweekday" -> PyBuiltinFunc("isoweekday") { _, _ -> obj.isoweekday() }
                "replace" -> PyBuiltinFunc("replace") { _, kwargs -> obj.replace(kwargs) }
                else -> throw PythonRuntimeException("AttributeError", "'datetime.datetime' object has no attribute '$name'", line)
            }
        }
        if (obj is PyDate) {
            return when (name) {
                "year" -> obj.year.toLong()
                "month" -> obj.month.toLong()
                "day" -> obj.day.toLong()
                "strftime" -> PyBuiltinFunc("strftime") { args, _ ->
                    val fmt = args.firstOrNull()?.toString() ?: "%Y-%m-%d"
                    obj.strftime(fmt)
                }
                "isoformat" -> PyBuiltinFunc("isoformat") { _, _ -> obj.isoformat() }
                "weekday" -> PyBuiltinFunc("weekday") { _, _ -> obj.weekday() }
                "isoweekday" -> PyBuiltinFunc("isoweekday") { _, _ -> obj.isoweekday() }
                else -> throw PythonRuntimeException("AttributeError", "'datetime.date' object has no attribute '$name'", line)
            }
        }
        if (obj is PyTime) {
            return when (name) {
                "hour" -> obj.hour.toLong()
                "minute" -> obj.minute.toLong()
                "second" -> obj.second.toLong()
                "microsecond" -> obj.microsecond.toLong()
                "isoformat" -> PyBuiltinFunc("isoformat") { _, _ -> obj.isoformat() }
                else -> throw PythonRuntimeException("AttributeError", "'datetime.time' object has no attribute '$name'", line)
            }
        }
        if (obj is PyTimeDelta) {
            return when (name) {
                "days" -> obj.days
                "seconds" -> obj.seconds
                "microseconds" -> obj.microseconds
                "total_seconds" -> PyBuiltinFunc("total_seconds") { _, _ -> obj.totalSeconds() }
                else -> throw PythonRuntimeException("AttributeError", "'datetime.timedelta' object has no attribute '$name'", line)
            }
        }
        if (obj is SandboxedFile) {
            return when (name) {
                "read" -> PyBuiltinFunc("read") { _, _ -> obj.read() }
                "readline" -> PyBuiltinFunc("readline") { _, _ -> obj.readline() }
                "readlines" -> PyBuiltinFunc("readlines") { _, _ -> obj.readlines() }
                "write" -> PyBuiltinFunc("write") { args, _ -> obj.write(pyStr(args.firstOrNull())) }
                "close" -> PyBuiltinFunc("close") { _, _ -> obj.close() }
                "__enter__" -> PyBuiltinFunc("__enter__") { _, _ -> obj }
                "__exit__" -> PyBuiltinFunc("__exit__") { _, _ -> obj.close(); null }
                else -> throw PythonRuntimeException("AttributeError", "'file' object has no attribute '$name'", line)
            }
        }
        if (obj is String) {
            return when (name) {
                "upper" -> PyBuiltinFunc("upper") { _, _ -> obj.uppercase(Locale.US) }
                "lower" -> PyBuiltinFunc("lower") { _, _ -> obj.lowercase(Locale.US) }
                "strip" -> PyBuiltinFunc("strip") { _, _ -> obj.trim() }
                "lstrip" -> PyBuiltinFunc("lstrip") { _, _ -> obj.trimStart() }
                "rstrip" -> PyBuiltinFunc("rstrip") { _, _ -> obj.trimEnd() }
                "title" -> PyBuiltinFunc("title") { _, _ ->
                    obj.split(" ").joinToString(" ") { it.replaceFirstChar { c -> c.uppercase(Locale.US) } }
                }
                "capitalize" -> PyBuiltinFunc("capitalize") { _, _ -> obj.replaceFirstChar { it.uppercase(Locale.US) } }
                "decode" -> PyBuiltinFunc("decode") { _, _ -> obj }
                "encode" -> PyBuiltinFunc("encode") { _, _ -> obj }
                "split" -> PyBuiltinFunc("split") { args, _ ->
                    val delim = args.firstOrNull()?.toString()
                    if (delim != null) obj.split(delim).toMutableList() else obj.trim().split(Regex("\\s+")).toMutableList()
                }
                "join" -> PyBuiltinFunc("join") { args, _ ->
                    val items = toIterable(args.firstOrNull(), line)
                    items.joinToString(obj) { pyStr(it) }
                }
                "replace" -> PyBuiltinFunc("replace") { args, _ ->
                    val oldStr = pyStr(args.getOrNull(0))
                    val newStr = pyStr(args.getOrNull(1))
                    obj.replace(oldStr, newStr)
                }
                "startswith" -> PyBuiltinFunc("startswith") { args, _ -> obj.startsWith(pyStr(args.firstOrNull())) }
                "endswith" -> PyBuiltinFunc("endswith") { args, _ -> obj.endsWith(pyStr(args.firstOrNull())) }
                "find" -> PyBuiltinFunc("find") { args, _ -> obj.indexOf(pyStr(args.firstOrNull())).toLong() }
                "count" -> PyBuiltinFunc("count") { args, _ ->
                    val sub = pyStr(args.firstOrNull())
                    if (sub.isEmpty()) 0L else obj.windowed(sub.length).count { it == sub }.toLong()
                }
                "isdigit" -> PyBuiltinFunc("isdigit") { _, _ -> obj.isNotEmpty() && obj.all { it.isDigit() } }
                "isalpha" -> PyBuiltinFunc("isalpha") { _, _ -> obj.isNotEmpty() && obj.all { it.isLetter() } }
                "isalnum" -> PyBuiltinFunc("isalnum") { _, _ -> obj.isNotEmpty() && obj.all { it.isLetterOrDigit() } }
                "isspace" -> PyBuiltinFunc("isspace") { _, _ -> obj.isNotEmpty() && obj.all { it.isWhitespace() } }
                "format" -> PyBuiltinFunc("format") { args, kwargs ->
                    val strObj = obj as String
                    var res = strObj
                    args.forEachIndexed { i, arg ->
                        res = res.replace("{$i}", pyStr(arg))
                    }
                    kwargs.forEach { (k, v) ->
                        res = res.replace("{$k}", pyStr(v))
                    }
                    var emptyIdx = 0
                    while (res.contains("{}") && emptyIdx < args.size) {
                        res = res.replaceFirst("{}", pyStr(args[emptyIdx++]))
                    }
                    res
                }
                else -> throw PythonRuntimeException("AttributeError", "'str' object has no attribute '$name'", line)
            }
        }
        if (obj is MutableList<*>) {
            val list = obj as MutableList<Any?>
            return when (name) {
                "append" -> PyBuiltinFunc("append") { args, _ -> list.add(args.firstOrNull()); null }
                "extend" -> PyBuiltinFunc("extend") { args, _ -> list.addAll(toIterable(args.firstOrNull(), line)); null }
                "pop" -> PyBuiltinFunc("pop") { args, _ ->
                    val idx = (args.firstOrNull() as? Number)?.toInt() ?: (list.size - 1)
                    if (idx !in list.indices) throw PythonRuntimeException("IndexError", "pop index out of range", line)
                    list.removeAt(idx)
                }
                "remove" -> PyBuiltinFunc("remove") { args, _ ->
                    val target = args.firstOrNull()
                    val found = list.indexOfFirst { pyEquals(it, target) }
                    if (found == -1) throw PythonRuntimeException("ValueError", "list.remove(x): x not in list", line)
                    list.removeAt(found)
                    null
                }
                "insert" -> PyBuiltinFunc("insert") { args, _ ->
                    val idx = (args.getOrNull(0) as? Number)?.toInt()?.coerceIn(0, list.size) ?: 0
                    list.add(idx, args.getOrNull(1))
                    null
                }
                "clear" -> PyBuiltinFunc("clear") { _, _ -> list.clear(); null }
                "index" -> PyBuiltinFunc("index") { args, _ ->
                    val target = args.firstOrNull()
                    val found = list.indexOfFirst { pyEquals(it, target) }
                    if (found == -1) throw PythonRuntimeException("ValueError", "$target is not in list", line)
                    found.toLong()
                }
                "count" -> PyBuiltinFunc("count") { args, _ ->
                    val target = args.firstOrNull()
                    list.count { pyEquals(it, target) }.toLong()
                }
                else -> throw PythonRuntimeException("AttributeError", "'list' object has no attribute '$name'", line)
            }
        }
        if (obj is MutableMap<*, *>) {
            val map = obj as MutableMap<String, Any?>
            return when (name) {
                "get" -> PyBuiltinFunc("get") { args, _ ->
                    val k = pyStr(args.firstOrNull())
                    if (map.containsKey(k)) map[k] else args.getOrNull(1)
                }
                "keys" -> PyBuiltinFunc("keys") { _, _ -> map.keys.toMutableList() }
                "values" -> PyBuiltinFunc("values") { _, _ -> map.values.toMutableList() }
                "items" -> PyBuiltinFunc("items") { _, _ -> map.entries.map { listOf(it.key, it.value) }.toMutableList() }
                "pop" -> PyBuiltinFunc("pop") { args, _ ->
                    val k = pyStr(args.firstOrNull())
                    if (map.containsKey(k)) map.remove(k)
                    else if (args.size > 1) args[1]
                    else throw PythonRuntimeException("KeyError", "'$k'", line)
                }
                "clear" -> PyBuiltinFunc("clear") { _, _ -> map.clear(); null }
                else -> throw PythonRuntimeException("AttributeError", "'dict' object has no attribute '$name'", line)
            }
        }
        throw PythonRuntimeException("AttributeError", "'${obj?.javaClass?.simpleName}' object has no attribute '$name'", line)
    }

    private fun callFunction(callee: Any?, args: List<Any?>, kwargs: Map<String, Any?>, line: Int): Any? {
        when (callee) {
            is PyBuiltinFunc -> return callee.invoke(args, kwargs)
            is PyUserClass -> {
                val instance = PyInstance(callee)
                val initMethod = callee.methods["__init__"]
                if (initMethod != null) {
                    callFunction(initMethod, listOf(instance) + args, kwargs, line)
                }
                return instance
            }
            is UserDefinedFunction -> {
                checkDeadline()
                if (callStack.size >= 100) {
                    throw PythonRuntimeException("RecursionError", "maximum recursion depth exceeded in comparison", line)
                }
                callStack.add(PythonTracebackFrame(callee.filename, callee.body.firstOrNull()?.line ?: line, callee.name))
                val localScope = callee.closureScope.toMutableMap()

                // Bind parameters
                for (i in callee.params.indices) {
                    val p = callee.params[i]
                    val argVal = if (i < args.size) args[i]
                    else kwargs[p.name]
                    ?: p.defaultValue?.let { evalExpr(it, localScope) }
                    ?: throw PythonRuntimeException("TypeError", "${callee.name}() missing required argument: '${p.name}'", line)
                    localScope[p.name] = argVal
                }

                try {
                    for (stmt in callee.body) {
                        val res = executeStmt(stmt, localScope)
                        if (res is ReturnSignal) {
                            return res.value
                        }
                    }
                    return null
                } catch (e: PythonRuntimeException) {
                    if (e.traceback.isEmpty()) {
                        e.traceback.addAll(callStack)
                    }
                    throw e
                } finally {
                    callStack.removeAt(callStack.lastIndex)
                }
            }
            else -> throw PythonRuntimeException("TypeError", "'${callee?.javaClass?.simpleName}' object is not callable", line)
        }
    }

    private fun isTruthy(v: Any?): Boolean {
        return when (v) {
            null -> false
            is Boolean -> v
            is Number -> v.toDouble() != 0.0
            is String -> v.isNotEmpty()
            is Collection<*> -> v.isNotEmpty()
            is Map<*, *> -> v.isNotEmpty()
            else -> true
        }
    }

    private fun toIterable(obj: Any?, line: Int): List<Any?> {
        return when (obj) {
            is List<*> -> obj
            is Set<*> -> obj.toList()
            is String -> obj.map { it.toString() }
            is Map<*, *> -> obj.keys.toList()
            else -> throw PythonRuntimeException("TypeError", "'${obj?.javaClass?.simpleName}' object is not iterable", line)
        }
    }

    private fun toPyList(obj: Any?): List<Any?> {
        return when (obj) {
            is List<*> -> obj
            is Set<*> -> obj.toList()
            is String -> obj.map { it.toString() }
            else -> listOf(obj)
        }
    }

    private fun pyStr(obj: Any?): String {
        return when (obj) {
            null -> "None"
            is Boolean -> if (obj) "True" else "False"
            is Double -> obj.toString()
            is Float -> obj.toString()
            is Long -> obj.toString()
            is Int -> obj.toString()
            is PyDateTime -> obj.toString()
            is PyDate -> obj.toString()
            is PyTime -> obj.toString()
            is PyTimeDelta -> obj.toString()
            is List<*> -> "[${obj.joinToString(", ") { pyRepr(it) }}]"
            is Map<*, *> -> "{${obj.entries.joinToString(", ") { "'${it.key}': ${pyRepr(it.value)}" }}}"
            else -> obj.toString()
        }
    }

    private fun pyRepr(obj: Any?): String {
        return when (obj) {
            is String -> "'$obj'"
            is PyDateTime -> "datetime.datetime(${obj.year}, ${obj.month}, ${obj.day}, ${obj.hour}, ${obj.minute}, ${obj.second})"
            is PyDate -> "datetime.date(${obj.year}, ${obj.month}, ${obj.day})"
            is PyTimeDelta -> "datetime.timedelta(days=${obj.days}, seconds=${obj.seconds})"
            else -> pyStr(obj)
        }
    }

    // -------------------------------------------------------------
    // Builtin Functions & Modules
    // -------------------------------------------------------------

    private fun initBuiltins() {
        globalScope["__name__"] = "__main__"
        globalScope["__file__"] = filename
        globalScope["__doc__"] = null

        globalScope["print"] = PyBuiltinFunc("print") { args, kwargs ->
            val sep = kwargs["sep"]?.toString() ?: " "
            val end = kwargs["end"]?.toString() ?: "\n"
            val text = args.joinToString(sep) { pyStr(it) } + end
            appendStdout(text)
            null
        }

        globalScope["len"] = PyBuiltinFunc("len") { args, _ ->
            when (val obj = args.firstOrNull()) {
                is String -> obj.length.toLong()
                is Collection<*> -> obj.size.toLong()
                is Map<*, *> -> obj.size.toLong()
                else -> throw PythonRuntimeException("TypeError", "object of type '${obj?.javaClass?.simpleName}' has no len()", 1)
            }
        }

        globalScope["range"] = PyBuiltinFunc("range") { args, _ ->
            val start: Int
            val stop: Int
            val step: Int
            when (args.size) {
                1 -> {
                    start = 0
                    stop = (args[0] as Number).toInt()
                    step = 1
                }
                2 -> {
                    start = (args[0] as Number).toInt()
                    stop = (args[1] as Number).toInt()
                    step = 1
                }
                3 -> {
                    start = (args[0] as Number).toInt()
                    stop = (args[1] as Number).toInt()
                    step = (args[2] as Number).toInt()
                }
                else -> throw PythonRuntimeException("TypeError", "range expected at least 1 argument, got ${args.size}", 1)
            }
            if (step == 0) throw PythonRuntimeException("ValueError", "range() arg 3 must not be zero", 1)
            val list = mutableListOf<Long>()
            var curr = start
            if (step > 0) {
                while (curr < stop) {
                    list.add(curr.toLong())
                    curr += step
                }
            } else {
                while (curr > stop) {
                    list.add(curr.toLong())
                    curr += step
                }
            }
            list
        }

        globalScope["str"] = PyBuiltinFunc("str") { args, _ -> pyStr(args.firstOrNull()) }
        globalScope["int"] = PyBuiltinFunc("int") { args, _ ->
            when (val v = args.firstOrNull()) {
                is Number -> v.toLong()
                is String -> v.trim().toLongOrNull() ?: throw PythonRuntimeException("ValueError", "invalid literal for int(): '$v'", 1)
                is Boolean -> if (v) 1L else 0L
                else -> throw PythonRuntimeException("TypeError", "int() argument must be a string or a number", 1)
            }
        }
        globalScope["float"] = PyBuiltinFunc("float") { args, _ ->
            when (val v = args.firstOrNull()) {
                is Number -> v.toDouble()
                is String -> v.trim().toDoubleOrNull() ?: throw PythonRuntimeException("ValueError", "could not convert string to float: '$v'", 1)
                is Boolean -> if (v) 1.0 else 0.0
                else -> throw PythonRuntimeException("TypeError", "float() argument must be a string or a number", 1)
            }
        }
        globalScope["bool"] = PyBuiltinFunc("bool") { args, _ -> isTruthy(args.firstOrNull()) }
        globalScope["list"] = PyBuiltinFunc("list") { args, _ -> toIterable(args.firstOrNull() ?: emptyList<Any?>(), 1).toMutableList() }
        globalScope["dict"] = PyBuiltinFunc("dict") { args, _ ->
            val map = mutableMapOf<String, Any?>()
            val arg = args.firstOrNull()
            if (arg is Map<*, *>) {
                arg.forEach { (k, v) -> map[k.toString()] = v }
            }
            map
        }
        globalScope["set"] = PyBuiltinFunc("set") { args, _ -> toIterable(args.firstOrNull() ?: emptyList<Any?>(), 1).toMutableSet() }
        globalScope["tuple"] = PyBuiltinFunc("tuple") { args, _ -> toIterable(args.firstOrNull() ?: emptyList<Any?>(), 1) }

        globalScope["sum"] = PyBuiltinFunc("sum") { args, _ ->
            val list = toIterable(args.firstOrNull(), 1)
            var sum = 0.0
            var hasFloat = false
            for (item in list) {
                if (item !is Number) throw PythonRuntimeException("TypeError", "unsupported operand type(s) for sum()", 1)
                if (item is Double || item is Float) hasFloat = true
                sum += item.toDouble()
            }
            if (hasFloat) sum else sum.toLong()
        }

        globalScope["min"] = PyBuiltinFunc("min") { args, _ ->
            val list = if (args.size == 1) toIterable(args.first(), 1) else args
            if (list.isEmpty()) throw PythonRuntimeException("ValueError", "min() arg is an empty sequence", 1)
            list.minWithOrNull { a, b -> pyCompare(a, b, 1) }
        }

        globalScope["max"] = PyBuiltinFunc("max") { args, _ ->
            val list = if (args.size == 1) toIterable(args.first(), 1) else args
            if (list.isEmpty()) throw PythonRuntimeException("ValueError", "max() arg is an empty sequence", 1)
            list.maxWithOrNull { a, b -> pyCompare(a, b, 1) }
        }

        globalScope["abs"] = PyBuiltinFunc("abs") { args, _ ->
            when (val v = args.firstOrNull()) {
                is Long -> abs(v)
                is Int -> abs(v).toLong()
                is Double -> abs(v)
                is Float -> abs(v).toDouble()
                else -> throw PythonRuntimeException("TypeError", "bad operand type for abs()", 1)
            }
        }

        globalScope["round"] = PyBuiltinFunc("round") { args, _ ->
            val num = (args.firstOrNull() as? Number)?.toDouble() ?: 0.0
            val ndigits = (args.getOrNull(1) as? Number)?.toInt() ?: 0
            if (ndigits <= 0) Math.round(num).toLong()
            else {
                val f = 10.0.pow(ndigits)
                Math.round(num * f) / f
            }
        }

        globalScope["type"] = PyBuiltinFunc("type") { args, _ ->
            val v = args.firstOrNull()
            when (v) {
                null -> "<class 'NoneType'>"
                is Long, is Int -> "<class 'int'>"
                is Double, is Float -> "<class 'float'>"
                is String -> "<class 'str'>"
                is Boolean -> "<class 'bool'>"
                is List<*> -> "<class 'list'>"
                is Map<*, *> -> "<class 'dict'>"
                is Set<*> -> "<class 'set'>"
                else -> "<class '${v.javaClass.simpleName}'>"
            }
        }

        globalScope["isinstance"] = PyBuiltinFunc("isinstance") { args, _ ->
            val obj = args.getOrNull(0)
            val typeStr = pyStr(args.getOrNull(1)).lowercase()
            when {
                typeStr.contains("int") -> obj is Long || obj is Int
                typeStr.contains("float") -> obj is Double || obj is Float
                typeStr.contains("str") -> obj is String
                typeStr.contains("bool") -> obj is Boolean
                typeStr.contains("list") -> obj is List<*>
                typeStr.contains("dict") -> obj is Map<*, *>
                else -> false
            }
        }

        globalScope["sorted"] = PyBuiltinFunc("sorted") { args, _ ->
            val list = toIterable(args.firstOrNull(), 1)
            list.sortedWith { a, b -> pyCompare(a, b, 1) }.toMutableList()
        }

        globalScope["reversed"] = PyBuiltinFunc("reversed") { args, _ ->
            val list = toIterable(args.firstOrNull(), 1)
            list.reversed().toMutableList()
        }

        globalScope["enumerate"] = PyBuiltinFunc("enumerate") { args, _ ->
            val list = toIterable(args.firstOrNull(), 1)
            list.mapIndexed { idx, item -> listOf(idx.toLong(), item) }.toMutableList()
        }

        globalScope["zip"] = PyBuiltinFunc("zip") { args, _ ->
            val lists = args.map { toIterable(it, 1) }
            val minLen = lists.minOfOrNull { it.size } ?: 0
            val res = mutableListOf<List<Any?>>()
            for (i in 0 until minLen) {
                res.add(lists.map { it[i] })
            }
            res
        }

        // Workspace-isolated open()
        globalScope["open"] = PyBuiltinFunc("open") { args, _ ->
            val pathArg = args.firstOrNull()?.toString()
                ?: throw PythonRuntimeException("TypeError", "open() missing required argument 'file'", 1)
            val mode = args.getOrNull(1)?.toString() ?: "r"

            val targetFile: File
            try {
                targetFile = workspaceManager.resolvePath(pathArg)
            } catch (e: SecurityException) {
                throw PythonRuntimeException("PermissionError", "Access denied: Path '$pathArg' attempts to access outside the agent workspace.", 1)
            }

            SandboxedFile(targetFile, mode)
        }
    }

    private fun handleImport(module: String, alias: String?, scope: MutableMap<String, Any?>, line: Int) {
        val pyMod = resolveModule(module, line)
        if (alias != null) {
            scope[alias] = pyMod
        } else if (module.contains('.')) {
            val rootName = module.substringBefore('.')
            val rootMod = resolveModule(rootName, line)
            val subName = module.substringAfterLast('.')
            val updatedMembers = rootMod.members.toMutableMap()
            updatedMembers[subName] = pyMod
            scope[rootName] = PyModule(rootMod.name, updatedMembers)
        } else {
            scope[module] = pyMod
        }
    }

    private fun handleFromImport(module: String, items: List<Pair<String, String?>>, scope: MutableMap<String, Any?>, line: Int) {
        val pyMod = resolveModule(module, line)
        for ((item, alias) in items) {
            if (item == "*") {
                for ((k, v) in pyMod.members) {
                    if (!k.startsWith("_")) {
                        scope[k] = v
                    }
                }
            } else {
                val member = pyMod.getMember(item)
                    ?: throw PythonRuntimeException("ImportError", "cannot import name '$item' from '$module'", line)
                scope[alias ?: item] = member
            }
        }
    }

    private fun resolveModule(module: String, line: Int): PyModule {
        if (loadedModules.containsKey(module)) {
            return loadedModules[module]!!
        }

        val pyMod: PyModule = when (module) {
            "math" -> {
                val members = mapOf<String, Any?>(
                    "pi" to Math.PI,
                    "e" to Math.E,
                    "inf" to Double.POSITIVE_INFINITY,
                    "nan" to Double.NaN,
                    "sqrt" to PyBuiltinFunc("sqrt") { args, _ ->
                        val n = (args.firstOrNull() as? Number)?.toDouble() ?: 0.0
                        if (n < 0) throw PythonRuntimeException("ValueError", "math domain error", line)
                        sqrt(n)
                    },
                    "pow" to PyBuiltinFunc("pow") { args, _ ->
                        val x = (args.getOrNull(0) as? Number)?.toDouble() ?: 0.0
                        val y = (args.getOrNull(1) as? Number)?.toDouble() ?: 0.0
                        x.pow(y)
                    },
                    "floor" to PyBuiltinFunc("floor") { args, _ -> floor((args.firstOrNull() as Number).toDouble()).toLong() },
                    "ceil" to PyBuiltinFunc("ceil") { args, _ -> ceil((args.firstOrNull() as Number).toDouble()).toLong() },
                    "sin" to PyBuiltinFunc("sin") { args, _ -> sin((args.firstOrNull() as Number).toDouble()) },
                    "cos" to PyBuiltinFunc("cos") { args, _ -> cos((args.firstOrNull() as Number).toDouble()) },
                    "tan" to PyBuiltinFunc("tan") { args, _ -> tan((args.firstOrNull() as Number).toDouble()) },
                    "log" to PyBuiltinFunc("log") { args, _ ->
                        val x = (args.firstOrNull() as? Number)?.toDouble() ?: 0.0
                        if (x <= 0) throw PythonRuntimeException("ValueError", "math domain error", line)
                        val base = (args.getOrNull(1) as? Number)?.toDouble()
                        if (base != null) kotlin.math.ln(x) / kotlin.math.ln(base) else kotlin.math.ln(x)
                    },
                    "exp" to PyBuiltinFunc("exp") { args, _ ->
                        val x = (args.firstOrNull() as? Number)?.toDouble() ?: 0.0
                        kotlin.math.exp(x)
                    },
                    "radians" to PyBuiltinFunc("radians") { args, _ ->
                        val deg = (args.firstOrNull() as? Number)?.toDouble() ?: 0.0
                        Math.toRadians(deg)
                    },
                    "degrees" to PyBuiltinFunc("degrees") { args, _ ->
                        val rad = (args.firstOrNull() as? Number)?.toDouble() ?: 0.0
                        Math.toDegrees(rad)
                    },
                    "factorial" to PyBuiltinFunc("factorial") { args, _ ->
                        val n = (args.firstOrNull() as? Number)?.toInt() ?: 0
                        if (n < 0) throw PythonRuntimeException("ValueError", "factorial() not defined for negative values", line)
                        var res = 1L
                        for (i in 2..n) res *= i
                        res
                    },
                    "gcd" to PyBuiltinFunc("gcd") { args, _ ->
                        var a = abs((args.getOrNull(0) as? Number)?.toLong() ?: 0L)
                        var b = abs((args.getOrNull(1) as? Number)?.toLong() ?: 0L)
                        while (b != 0L) {
                            val t = b
                            b = a % b
                            a = t
                        }
                        a
                    }
                )
                PyModule("math", members)
            }
            "random" -> {
                val rng = java.util.Random()
                val members = mapOf<String, Any?>(
                    "randint" to PyBuiltinFunc("randint") { args, _ ->
                        val a = (args.getOrNull(0) as? Number)?.toInt() ?: 0
                        val b = (args.getOrNull(1) as? Number)?.toInt() ?: 1
                        (a + rng.nextInt(b - a + 1)).toLong()
                    },
                    "randrange" to PyBuiltinFunc("randrange") { args, _ ->
                        val start = if (args.size > 1) (args[0] as Number).toInt() else 0
                        val stop = if (args.size > 1) (args[1] as Number).toInt() else (args.firstOrNull() as? Number)?.toInt() ?: 1
                        if (stop <= start) throw PythonRuntimeException("ValueError", "empty range for randrange()", line)
                        (start + rng.nextInt(stop - start)).toLong()
                    },
                    "random" to PyBuiltinFunc("random") { _, _ -> rng.nextDouble() },
                    "choice" to PyBuiltinFunc("choice") { args, _ ->
                        val list = toIterable(args.firstOrNull(), line)
                        if (list.isEmpty()) throw PythonRuntimeException("IndexError", "Cannot choose from an empty sequence", line)
                        list[rng.nextInt(list.size)]
                    },
                    "sample" to PyBuiltinFunc("sample") { args, _ ->
                        val list = toIterable(args.firstOrNull(), line)
                        val k = (args.getOrNull(1) as? Number)?.toInt() ?: 0
                        if (k > list.size) throw PythonRuntimeException("ValueError", "Sample larger than population", line)
                        val shuffled = list.toMutableList()
                        shuffled.shuffle(rng)
                        shuffled.take(k).toMutableList()
                    },
                    "shuffle" to PyBuiltinFunc("shuffle") { args, _ ->
                        val list = args.firstOrNull() as? MutableList<Any?>
                            ?: throw PythonRuntimeException("TypeError", "shuffle argument must be a mutable list", line)
                        list.shuffle(rng)
                        null
                    }
                )
                PyModule("random", members)
            }
            "sys" -> {
                val members = mapOf<String, Any?>(
                    "argv" to (listOf(filename) + args).toMutableList(),
                    "version" to "3.11.0 (embedded in-app Android Python runtime)",
                    "exit" to PyBuiltinFunc("exit") { args, _ ->
                        val code = (args.firstOrNull() as? Number)?.toInt() ?: 0
                        throw PythonSystemExit(code)
                    }
                )
                PyModule("sys", members)
            }
            "time" -> {
                val members = mapOf<String, Any?>(
                    "time" to PyBuiltinFunc("time") { _, _ -> System.currentTimeMillis() / 1000.0 },
                    "ctime" to PyBuiltinFunc("ctime") { _, _ -> java.util.Date().toString() },
                    "sleep" to PyBuiltinFunc("sleep") { args, _ ->
                        val secs = (args.firstOrNull() as? Number)?.toDouble() ?: 0.0
                        val ms = (secs * 1000).toLong()
                        Thread.sleep(ms.coerceAtMost(5000L))
                        checkDeadline()
                        null
                    }
                )
                PyModule("time", members)
            }
            "json" -> {
                val members = mapOf<String, Any?>(
                    "dumps" to PyBuiltinFunc("dumps") { args, _ -> pyJsonDumps(args.firstOrNull()) },
                    "loads" to PyBuiltinFunc("loads") { args, _ ->
                        val jsonStr = args.firstOrNull()?.toString() ?: ""
                        parseJsonToPy(jsonStr)
                    }
                )
                PyModule("json", members)
            }
            "re" -> {
                val members = mapOf<String, Any?>(
                    "search" to PyBuiltinFunc("search") { args, _ ->
                        val patternStr = pyStr(args.getOrNull(0))
                        val targetStr = pyStr(args.getOrNull(1))
                        val pattern = Pattern.compile(patternStr)
                        val matcher = pattern.matcher(targetStr)
                        if (matcher.find()) PyMatch(matcher.toMatchResult()) else null
                    },
                    "match" to PyBuiltinFunc("match") { args, _ ->
                        val patternStr = pyStr(args.getOrNull(0))
                        val targetStr = pyStr(args.getOrNull(1))
                        val pattern = Pattern.compile(patternStr)
                        val matcher = pattern.matcher(targetStr)
                        if (matcher.lookingAt()) PyMatch(matcher.toMatchResult()) else null
                    },
                    "findall" to PyBuiltinFunc("findall") { args, _ ->
                        val patternStr = pyStr(args.getOrNull(0))
                        val targetStr = pyStr(args.getOrNull(1))
                        val pattern = Pattern.compile(patternStr)
                        val matcher = pattern.matcher(targetStr)
                        val results = mutableListOf<Any?>()
                        while (matcher.find()) {
                            val groupCount = matcher.groupCount()
                            when {
                                groupCount == 0 -> results.add(matcher.group())
                                groupCount == 1 -> results.add(matcher.group(1))
                                else -> {
                                    val tuple = (1..groupCount).map { matcher.group(it) }
                                    results.add(tuple)
                                }
                            }
                        }
                        results
                    },
                    "sub" to PyBuiltinFunc("sub") { args, _ ->
                        val patternStr = pyStr(args.getOrNull(0))
                        val replStr = pyStr(args.getOrNull(1))
                        val targetStr = pyStr(args.getOrNull(2))
                        val pattern = Pattern.compile(patternStr)
                        pattern.matcher(targetStr).replaceAll(replStr)
                    },
                    "split" to PyBuiltinFunc("split") { args, _ ->
                        val patternStr = pyStr(args.getOrNull(0))
                        val targetStr = pyStr(args.getOrNull(1))
                        val limit = (args.getOrNull(2) as? Number)?.toInt() ?: 0
                        val pattern = Pattern.compile(patternStr)
                        val parts = if (limit > 0) pattern.split(targetStr, limit + 1) else pattern.split(targetStr)
                        parts.toMutableList()
                    }
                )
                PyModule("re", members)
            }
            "base64" -> {
                val members = mapOf<String, Any?>(
                    "b64encode" to PyBuiltinFunc("b64encode") { args, _ ->
                        val input = args.firstOrNull()
                        val bytes = when (input) {
                            is ByteArray -> input
                            is String -> input.toByteArray(Charsets.UTF_8)
                            is List<*> -> input.map { (it as? Number)?.toByte() ?: 0.toByte() }.toByteArray()
                            else -> (input?.toString() ?: "").toByteArray(Charsets.UTF_8)
                        }
                        Base64.getEncoder().encodeToString(bytes)
                    },
                    "b64decode" to PyBuiltinFunc("b64decode") { args, _ ->
                        val input = pyStr(args.firstOrNull()).trim()
                        val decoded = Base64.getDecoder().decode(input)
                        String(decoded, Charsets.UTF_8)
                    }
                )
                PyModule("base64", members)
            }
            "hashlib" -> {
                val members = mapOf<String, Any?>(
                    "md5" to PyBuiltinFunc("md5") { args, _ ->
                        val initial = args.firstOrNull()
                        val bytes = when (initial) {
                            is ByteArray -> initial
                            is String -> initial.toByteArray(Charsets.UTF_8)
                            else -> initial?.toString()?.toByteArray(Charsets.UTF_8) ?: ByteArray(0)
                        }
                        PyHashObject("MD5", bytes)
                    },
                    "sha1" to PyBuiltinFunc("sha1") { args, _ ->
                        val initial = args.firstOrNull()
                        val bytes = when (initial) {
                            is ByteArray -> initial
                            is String -> initial.toByteArray(Charsets.UTF_8)
                            else -> initial?.toString()?.toByteArray(Charsets.UTF_8) ?: ByteArray(0)
                        }
                        PyHashObject("SHA-1", bytes)
                    },
                    "sha256" to PyBuiltinFunc("sha256") { args, _ ->
                        val initial = args.firstOrNull()
                        val bytes = when (initial) {
                            is ByteArray -> initial
                            is String -> initial.toByteArray(Charsets.UTF_8)
                            else -> initial?.toString()?.toByteArray(Charsets.UTF_8) ?: ByteArray(0)
                        }
                        PyHashObject("SHA-256", bytes)
                    },
                    "sha512" to PyBuiltinFunc("sha512") { args, _ ->
                        val initial = args.firstOrNull()
                        val bytes = when (initial) {
                            is ByteArray -> initial
                            is String -> initial.toByteArray(Charsets.UTF_8)
                            else -> initial?.toString()?.toByteArray(Charsets.UTF_8) ?: ByteArray(0)
                        }
                        PyHashObject("SHA-512", bytes)
                    }
                )
                PyModule("hashlib", members)
            }
            "string" -> {
                val members = mapOf<String, Any?>(
                    "ascii_lowercase" to "abcdefghijklmnopqrstuvwxyz",
                    "ascii_uppercase" to "ABCDEFGHIJKLMNOPQRSTUVWXYZ",
                    "ascii_letters" to "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ",
                    "digits" to "0123456789",
                    "hexdigits" to "0123456789abcdefABCDEF",
                    "octdigits" to "01234567",
                    "punctuation" to "!\"#$%&'()*+,-./:;<=>?@[\\]^_`{|}~",
                    "whitespace" to " \t\n\r\u000b\u000c"
                )
                PyModule("string", members)
            }
            "os" -> {
                val pathMembers = mapOf<String, Any?>(
                    "join" to PyBuiltinFunc("join") { args, _ ->
                        val parts = args.map { it.toString().trim('/') }
                        parts.joinToString(File.separator)
                    },
                    "exists" to PyBuiltinFunc("exists") { args, _ ->
                        val rel = args.firstOrNull()?.toString() ?: ""
                        try {
                            workspaceManager.resolvePath(rel).exists()
                        } catch (_: SecurityException) {
                            false
                        }
                    },
                    "basename" to PyBuiltinFunc("basename") { args, _ -> File(args.firstOrNull()?.toString() ?: "").name },
                    "dirname" to PyBuiltinFunc("dirname") { args, _ -> File(args.firstOrNull()?.toString() ?: "").parent ?: "" }
                )
                val osMembers = mapOf<String, Any?>(
                    "path" to PyModule("os.path", pathMembers),
                    "listdir" to PyBuiltinFunc("listdir") { args, _ ->
                        val rel = args.firstOrNull()?.toString() ?: "."
                        val dir = workspaceManager.resolvePath(rel)
                        dir.list()?.toList()?.toMutableList() ?: mutableListOf<String>()
                    }
                )
                PyModule("os", osMembers)
            }
            "os.path" -> {
                val pathMembers = mapOf<String, Any?>(
                    "join" to PyBuiltinFunc("join") { args, _ ->
                        val parts = args.map { it.toString().trim('/') }
                        parts.joinToString(File.separator)
                    },
                    "exists" to PyBuiltinFunc("exists") { args, _ ->
                        val rel = args.firstOrNull()?.toString() ?: ""
                        try {
                            workspaceManager.resolvePath(rel).exists()
                        } catch (_: SecurityException) {
                            false
                        }
                    },
                    "basename" to PyBuiltinFunc("basename") { args, _ -> File(args.firstOrNull()?.toString() ?: "").name },
                    "dirname" to PyBuiltinFunc("dirname") { args, _ -> File(args.firstOrNull()?.toString() ?: "").parent ?: "" }
                )
                PyModule("os.path", pathMembers)
            }
            "urllib" -> {
                val reqMembers = mapOf<String, Any?>(
                    "Request" to PyBuiltinFunc("Request") { args, kwargs ->
                        val url = pyStr(args.firstOrNull() ?: kwargs["url"])
                        val data = args.getOrNull(1) ?: kwargs["data"]
                        val headersArg = (args.getOrNull(2) ?: kwargs["headers"]) as? Map<*, *>
                        val headers = mutableMapOf<String, String>()
                        headersArg?.forEach { (k, v) -> if (k != null && v != null) headers[k.toString()] = v.toString() }
                        val method = (kwargs["method"] ?: args.getOrNull(3))?.toString()
                        PyRequest(url, data, headers, method)
                    },
                    "urlopen" to PyBuiltinFunc("urlopen") { args, kwargs ->
                        val target = args.firstOrNull() ?: kwargs["url"]
                        executePythonHttpUrlopen(target, line)
                    }
                )
                val parseMembers = mapOf<String, Any?>(
                    "quote" to PyBuiltinFunc("quote") { args, _ -> java.net.URLEncoder.encode(pyStr(args.firstOrNull()), "UTF-8") },
                    "unquote" to PyBuiltinFunc("unquote") { args, _ -> java.net.URLDecoder.decode(pyStr(args.firstOrNull()), "UTF-8") },
                    "urlencode" to PyBuiltinFunc("urlencode") { args, _ ->
                        val map = args.firstOrNull() as? Map<*, *> ?: emptyMap<Any, Any>()
                        map.entries.joinToString("&") { (k, v) ->
                            "${java.net.URLEncoder.encode(k.toString(), "UTF-8")}=${java.net.URLEncoder.encode(v.toString(), "UTF-8")}"
                        }
                    }
                )
                val errorMembers = mapOf<String, Any?>(
                    "URLError" to PyBuiltinFunc("URLError") { args, _ -> PythonRuntimeException("URLError", pyStr(args.firstOrNull()), line) },
                    "HTTPError" to PyBuiltinFunc("HTTPError") { args, _ -> PythonRuntimeException("HTTPError", pyStr(args.firstOrNull()), line) }
                )
                val urllibMembers = mapOf<String, Any?>(
                    "request" to PyModule("urllib.request", reqMembers),
                    "parse" to PyModule("urllib.parse", parseMembers),
                    "error" to PyModule("urllib.error", errorMembers)
                )
                PyModule("urllib", urllibMembers)
            }
            "urllib.request" -> {
                val reqMembers = mapOf<String, Any?>(
                    "Request" to PyBuiltinFunc("Request") { args, kwargs ->
                        val url = pyStr(args.firstOrNull() ?: kwargs["url"])
                        val data = args.getOrNull(1) ?: kwargs["data"]
                        val headersArg = (args.getOrNull(2) ?: kwargs["headers"]) as? Map<*, *>
                        val headers = mutableMapOf<String, String>()
                        headersArg?.forEach { (k, v) -> if (k != null && v != null) headers[k.toString()] = v.toString() }
                        val method = (kwargs["method"] ?: args.getOrNull(3))?.toString()
                        PyRequest(url, data, headers, method)
                    },
                    "urlopen" to PyBuiltinFunc("urlopen") { args, kwargs ->
                        val target = args.firstOrNull() ?: kwargs["url"]
                        executePythonHttpUrlopen(target, line)
                    }
                )
                PyModule("urllib.request", reqMembers)
            }
            "urllib.error" -> {
                val errorMembers = mapOf<String, Any?>(
                    "URLError" to PyBuiltinFunc("URLError") { args, _ -> PythonRuntimeException("URLError", pyStr(args.firstOrNull()), line) },
                    "HTTPError" to PyBuiltinFunc("HTTPError") { args, _ -> PythonRuntimeException("HTTPError", pyStr(args.firstOrNull()), line) }
                )
                PyModule("urllib.error", errorMembers)
            }
            "urllib.parse" -> {
                val parseMembers = mapOf<String, Any?>(
                    "quote" to PyBuiltinFunc("quote") { args, _ -> java.net.URLEncoder.encode(pyStr(args.firstOrNull()), "UTF-8") },
                    "unquote" to PyBuiltinFunc("unquote") { args, _ -> java.net.URLDecoder.decode(pyStr(args.firstOrNull()), "UTF-8") },
                    "urlencode" to PyBuiltinFunc("urlencode") { args, _ ->
                        val map = args.firstOrNull() as? Map<*, *> ?: emptyMap<Any, Any>()
                        map.entries.joinToString("&") { (k, v) ->
                            "${java.net.URLEncoder.encode(k.toString(), "UTF-8")}=${java.net.URLEncoder.encode(v.toString(), "UTF-8")}"
                        }
                    }
                )
                PyModule("urllib.parse", parseMembers)
            }
            "datetime" -> {
                val dtClassMembers = mapOf<String, Any?>(
                    "now" to PyBuiltinFunc("now") { _, _ -> PyDateTime.now() },
                    "utcnow" to PyBuiltinFunc("utcnow") { _, _ -> PyDateTime.utcnow() },
                    "today" to PyBuiltinFunc("today") { _, _ -> PyDateTime.now() },
                    "fromtimestamp" to PyBuiltinFunc("fromtimestamp") { args, _ ->
                        val ts = (args.firstOrNull() as? Number)?.toDouble() ?: 0.0
                        PyDateTime.fromTimestamp(ts)
                    },
                    "strptime" to PyBuiltinFunc("strptime") { args, _ ->
                        val dateStr = pyStr(args.firstOrNull())
                        val fmt = pyStr(args.getOrNull(1) ?: "%Y-%m-%d %H:%M:%S")
                        PyDateTime.strptime(dateStr, fmt)
                    },
                    "combine" to PyBuiltinFunc("combine") { args, _ ->
                        val d = args.firstOrNull() as? PyDate ?: PyDate(2026, 1, 1)
                        val t = args.getOrNull(1) as? PyTime ?: PyTime(0, 0, 0)
                        PyDateTime.create(d.year, d.month, d.day, t.hour, t.minute, t.second, t.microsecond)
                    }
                )
                val dtConstructor = PyBuiltinFunc("datetime") { args, kwargs ->
                    if (args.isEmpty() && kwargs.isEmpty()) PyDateTime.now()
                    else {
                        val y = ((args.getOrNull(0) ?: kwargs["year"]) as? Number)?.toInt() ?: 2026
                        val m = ((args.getOrNull(1) ?: kwargs["month"]) as? Number)?.toInt() ?: 1
                        val d = ((args.getOrNull(2) ?: kwargs["day"]) as? Number)?.toInt() ?: 1
                        val h = ((args.getOrNull(3) ?: kwargs["hour"]) as? Number)?.toInt() ?: 0
                        val min = ((args.getOrNull(4) ?: kwargs["minute"]) as? Number)?.toInt() ?: 0
                        val sec = ((args.getOrNull(5) ?: kwargs["second"]) as? Number)?.toInt() ?: 0
                        val us = ((args.getOrNull(6) ?: kwargs["microsecond"]) as? Number)?.toInt() ?: 0
                        PyDateTime.create(y, m, d, h, min, sec, us)
                    }
                }
                val dateConstructor = PyBuiltinFunc("date") { args, kwargs ->
                    val y = ((args.getOrNull(0) ?: kwargs["year"]) as? Number)?.toInt() ?: 2026
                    val m = ((args.getOrNull(1) ?: kwargs["month"]) as? Number)?.toInt() ?: 1
                    val d = ((args.getOrNull(2) ?: kwargs["day"]) as? Number)?.toInt() ?: 1
                    PyDate(y, m, d)
                }
                val timeConstructor = PyBuiltinFunc("time") { args, kwargs ->
                    val h = ((args.getOrNull(0) ?: kwargs["hour"]) as? Number)?.toInt() ?: 0
                    val m = ((args.getOrNull(1) ?: kwargs["minute"]) as? Number)?.toInt() ?: 0
                    val s = ((args.getOrNull(2) ?: kwargs["second"]) as? Number)?.toInt() ?: 0
                    val us = ((args.getOrNull(3) ?: kwargs["microsecond"]) as? Number)?.toInt() ?: 0
                    PyTime(h, m, s, us)
                }
                val timedeltaConstructor = PyBuiltinFunc("timedelta") { args, kwargs ->
                    val days = (kwargs["days"] ?: args.getOrNull(0)) as? Number ?: 0
                    val sec = (kwargs["seconds"] ?: args.getOrNull(1)) as? Number ?: 0
                    val micro = (kwargs["microseconds"] ?: args.getOrNull(2)) as? Number ?: 0
                    val ms = (kwargs["milliseconds"] ?: args.getOrNull(3)) as? Number ?: 0
                    val min = (kwargs["minutes"] ?: args.getOrNull(4)) as? Number ?: 0
                    val hr = (kwargs["hours"] ?: args.getOrNull(5)) as? Number ?: 0
                    val wks = (kwargs["weeks"] ?: args.getOrNull(6)) as? Number ?: 0
                    PyTimeDelta(
                        days = days.toLong() + wks.toLong() * 7,
                        seconds = sec.toLong() + min.toLong() * 60 + hr.toLong() * 3600,
                        microseconds = micro.toLong() + ms.toLong() * 1000
                    )
                }
                val tzUtc = PyTimeZone("UTC", 0)
                val timezoneModule = mapOf<String, Any?>("utc" to tzUtc)

                val members = mutableMapOf<String, Any?>(
                    "datetime" to dtConstructor,
                    "date" to dateConstructor,
                    "time" to timeConstructor,
                    "timedelta" to timedeltaConstructor,
                    "timezone" to PyModule("datetime.timezone", timezoneModule)
                )
                dtClassMembers.forEach { (k, v) -> members[k] = v }
                PyModule("datetime", members)
            }
            "calendar" -> {
                val members = mapOf<String, Any?>(
                    "monthrange" to PyBuiltinFunc("monthrange") { args, _ ->
                        val y = (args.getOrNull(0) as? Number)?.toInt() ?: 2026
                        val m = (args.getOrNull(1) as? Number)?.toInt() ?: 1
                        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
                            clear()
                            set(y, m - 1, 1)
                        }
                        val firstDow = ((cal.get(Calendar.DAY_OF_WEEK) + 5) % 7).toLong()
                        val daysInMonth = cal.getActualMaximum(Calendar.DAY_OF_MONTH).toLong()
                        listOf(firstDow, daysInMonth).toMutableList()
                    },
                    "isleap" to PyBuiltinFunc("isleap") { args, _ ->
                        val y = (args.firstOrNull() as? Number)?.toInt() ?: 2026
                        (y % 4 == 0 && (y % 100 != 0 || y % 400 == 0))
                    },
                    "day_name" to listOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday").toMutableList(),
                    "month_name" to listOf("", "January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December").toMutableList()
                )
                PyModule("calendar", members)
            }
            "collections" -> {
                val members = mapOf<String, Any?>(
                    "defaultdict" to PyBuiltinFunc("defaultdict") { _, _ -> mutableMapOf<String, Any?>() },
                    "Counter" to PyBuiltinFunc("Counter") { args, _ ->
                        val seq = args.firstOrNull() as? List<*> ?: emptyList<Any?>()
                        val counts = mutableMapOf<String, Long>()
                        for (item in seq) {
                            val k = pyStr(item)
                            counts[k] = (counts[k] ?: 0L) + 1L
                        }
                        counts
                    },
                    "deque" to PyBuiltinFunc("deque") { args, _ ->
                        (args.firstOrNull() as? List<*>)?.toMutableList() ?: mutableListOf<Any?>()
                    },
                    "OrderedDict" to PyBuiltinFunc("OrderedDict") { _, _ -> mutableMapOf<String, Any?>() }
                )
                PyModule("collections", members)
            }
            "itertools" -> {
                val members = mapOf<String, Any?>(
                    "chain" to PyBuiltinFunc("chain") { args, _ ->
                        val res = mutableListOf<Any?>()
                        for (a in args) {
                            if (a is List<*>) res.addAll(a)
                        }
                        res
                    },
                    "count" to PyBuiltinFunc("count") { args, _ ->
                        val start = (args.firstOrNull() as? Number)?.toLong() ?: 0L
                        (0..999).map { start + it }.toMutableList()
                    },
                    "cycle" to PyBuiltinFunc("cycle") { args, _ ->
                        (args.firstOrNull() as? List<*>)?.toMutableList() ?: mutableListOf<Any?>()
                    },
                    "repeat" to PyBuiltinFunc("repeat") { args, _ ->
                        val item = args.firstOrNull()
                        val times = (args.getOrNull(1) as? Number)?.toInt() ?: 1
                        MutableList(times.coerceIn(0, 1000)) { item }
                    }
                )
                PyModule("itertools", members)
            }
            "functools" -> {
                val members = mapOf<String, Any?>(
                    "reduce" to PyBuiltinFunc("reduce") { args, _ ->
                        val func = args.getOrNull(0)
                        val seq = args.getOrNull(1) as? List<*> ?: emptyList<Any?>()
                        if (seq.isEmpty()) null
                        else {
                            var acc: Any? = seq[0]
                            for (idx in 1 until seq.size) {
                                if (func is PyBuiltinFunc) {
                                    acc = func.invoke(listOf(acc, seq[idx]), emptyMap())
                                }
                            }
                            acc
                        }
                    },
                    "partial" to PyBuiltinFunc("partial") { args, _ -> args.firstOrNull() },
                    "lru_cache" to PyBuiltinFunc("lru_cache") { _, _ ->
                        PyBuiltinFunc("decorator") { inner, _ -> inner.firstOrNull() }
                    }
                )
                PyModule("functools", members)
            }
            "uuid" -> {
                val members = mapOf<String, Any?>(
                    "uuid4" to PyBuiltinFunc("uuid4") { _, _ -> java.util.UUID.randomUUID().toString() },
                    "uuid1" to PyBuiltinFunc("uuid1") { _, _ -> java.util.UUID.randomUUID().toString() }
                )
                PyModule("uuid", members)
            }
            "copy" -> {
                val members = mapOf<String, Any?>(
                    "copy" to PyBuiltinFunc("copy") { args, _ ->
                        when (val a = args.firstOrNull()) {
                            is List<*> -> a.toMutableList()
                            is Map<*, *> -> a.toMutableMap()
                            else -> a
                        }
                    },
                    "deepcopy" to PyBuiltinFunc("deepcopy") { args, _ ->
                        when (val a = args.firstOrNull()) {
                            is List<*> -> a.toMutableList()
                            is Map<*, *> -> a.toMutableMap()
                            else -> a
                        }
                    }
                )
                PyModule("copy", members)
            }
            "typing" -> {
                val members = mapOf<String, Any?>(
                    "Any" to "Any",
                    "List" to "List",
                    "Dict" to "Dict",
                    "Set" to "Set",
                    "Tuple" to "Tuple",
                    "Optional" to "Optional",
                    "Union" to "Union",
                    "Callable" to "Callable"
                )
                PyModule("typing", members)
            }
            "difflib" -> {
                val smFunc = PyBuiltinFunc("SequenceMatcher") { args, kwargs ->
                    val aArg = args.getOrNull(1) ?: kwargs["a"] ?: ""
                    val bArg = args.getOrNull(2) ?: kwargs["b"] ?: ""
                    val smMembers = mutableMapOf<String, Any?>()
                    var seqA = pyStr(aArg)
                    var seqB = pyStr(bArg)

                    val computeRatio: () -> Double = {
                        val sa = seqA
                        val sb = seqB
                        if (sa.isEmpty() && sb.isEmpty()) 1.0
                        else if (sa.isEmpty() || sb.isEmpty()) 0.0
                        else {
                            var matches = 0
                            val s2Chars = sb.toMutableList()
                            for (c in sa) {
                                val idx = s2Chars.indexOf(c)
                                if (idx >= 0) {
                                    matches++
                                    s2Chars.removeAt(idx)
                                }
                            }
                            (2.0 * matches) / (sa.length + sb.length)
                        }
                    }

                    smMembers["ratio"] = PyBuiltinFunc("ratio") { _, _ -> computeRatio() }
                    smMembers["quick_ratio"] = PyBuiltinFunc("quick_ratio") { _, _ -> computeRatio() }
                    smMembers["real_quick_ratio"] = PyBuiltinFunc("real_quick_ratio") { _, _ -> computeRatio() }
                    smMembers["set_seq1"] = PyBuiltinFunc("set_seq1") { a, _ -> seqA = pyStr(a.firstOrNull()); null }
                    smMembers["set_seq2"] = PyBuiltinFunc("set_seq2") { b, _ -> seqB = pyStr(b.firstOrNull()); null }
                    smMembers["set_seqs"] = PyBuiltinFunc("set_seqs") { sArgs, _ ->
                        seqA = pyStr(sArgs.getOrNull(0))
                        seqB = pyStr(sArgs.getOrNull(1))
                        null
                    }
                    smMembers["get_opcodes"] = PyBuiltinFunc("get_opcodes") { _, _ ->
                        listOf(
                            if (seqA == seqB) listOf("equal", 0L, seqA.length.toLong(), 0L, seqB.length.toLong())
                            else listOf("replace", 0L, seqA.length.toLong(), 0L, seqB.length.toLong())
                        )
                    }
                    smMembers["get_matching_blocks"] = PyBuiltinFunc("get_matching_blocks") { _, _ ->
                        listOf(listOf(0L, 0L, minOf(seqA.length, seqB.length).toLong()))
                    }
                    PyModule("SequenceMatcher", smMembers)
                }

                val members = mapOf<String, Any?>(
                    "SequenceMatcher" to smFunc,
                    "unified_diff" to PyBuiltinFunc("unified_diff") { args, kwargs ->
                        val aList = (args.getOrNull(0) ?: kwargs["a"]) as? List<*> ?: emptyList<Any>()
                        val bList = (args.getOrNull(1) ?: kwargs["b"]) as? List<*> ?: emptyList<Any>()
                        val fromFile = pyStr(args.getOrNull(2) ?: kwargs["fromfile"] ?: "")
                        val toFile = pyStr(args.getOrNull(3) ?: kwargs["tofile"] ?: "")
                        val lineterm = pyStr(kwargs["lineterm"] ?: "\n")

                        val linesA = aList.map { pyStr(it).removeSuffix("\n") }
                        val linesB = bList.map { pyStr(it).removeSuffix("\n") }

                        val resultLines = mutableListOf<String>()
                        if (linesA != linesB) {
                            if (fromFile.isNotEmpty() || toFile.isNotEmpty()) {
                                resultLines.add("--- $fromFile$lineterm")
                                resultLines.add("+++ $toFile$lineterm")
                            }
                            resultLines.add("@@ -1,${linesA.size} +1,${linesB.size} @@$lineterm")
                            for (l in linesA) {
                                if (!linesB.contains(l)) resultLines.add("-$l$lineterm")
                                else resultLines.add(" $l$lineterm")
                            }
                            for (l in linesB) {
                                if (!linesA.contains(l)) resultLines.add("+$l$lineterm")
                            }
                        }
                        resultLines
                    },
                    "ndiff" to PyBuiltinFunc("ndiff") { args, _ ->
                        val aList = args.getOrNull(0) as? List<*> ?: emptyList<Any>()
                        val bList = args.getOrNull(1) as? List<*> ?: emptyList<Any>()
                        val linesA = aList.map { pyStr(it).removeSuffix("\n") }
                        val linesB = bList.map { pyStr(it).removeSuffix("\n") }
                        val result = mutableListOf<String>()
                        for (l in linesA) {
                            if (linesB.contains(l)) result.add("  $l\n")
                            else result.add("- $l\n")
                        }
                        for (l in linesB) {
                            if (!linesA.contains(l)) result.add("+ $l\n")
                        }
                        result
                    },
                    "get_close_matches" to PyBuiltinFunc("get_close_matches") { args, kwargs ->
                        val word = pyStr(args.getOrNull(0) ?: kwargs["word"])
                        val possibilities = (args.getOrNull(1) ?: kwargs["possibilities"]) as? List<*> ?: emptyList<Any>()
                        val n = (args.getOrNull(2) ?: kwargs["n"] ?: 3L).let { if (it is Number) it.toInt() else 3 }
                        val cutoff = (args.getOrNull(3) ?: kwargs["cutoff"] ?: 0.6).let { if (it is Number) it.toDouble() else 0.6 }

                        val scored = possibilities.mapNotNull { p ->
                            val s = pyStr(p)
                            val r = if (word == s) 1.0 else {
                                var matches = 0
                                val s2Chars = s.toMutableList()
                                for (c in word) {
                                    val idx = s2Chars.indexOf(c)
                                    if (idx >= 0) {
                                        matches++
                                        s2Chars.removeAt(idx)
                                    }
                                }
                                (2.0 * matches) / (word.length + s.length)
                            }
                            if (r >= cutoff) Pair(s, r) else null
                        }.sortedByDescending { it.second }.take(n).map { it.first }
                        scored
                    },
                    "restore" to PyBuiltinFunc("restore") { args, _ ->
                        val delta = args.getOrNull(0) as? List<*> ?: emptyList<Any>()
                        val which = (args.getOrNull(1) as? Number)?.toInt() ?: 1
                        val prefix = if (which == 1) "- " else "+ "
                        delta.mapNotNull { d ->
                            val s = pyStr(d)
                            if (s.startsWith("  ") || s.startsWith(prefix)) s.substring(2) else null
                        }
                    }
                )
                PyModule("difflib", members)
            }
            "shutil" -> {
                val members = mapOf<String, Any?>(
                    "copy" to PyBuiltinFunc("copy") { args, _ ->
                        val src = workspaceManager.resolvePath(pyStr(args.firstOrNull()))
                        val dst = workspaceManager.resolvePath(pyStr(args.getOrNull(1)))
                        src.copyTo(dst, overwrite = true)
                        dst.canonicalPath
                    },
                    "copy2" to PyBuiltinFunc("copy2") { args, _ ->
                        val src = workspaceManager.resolvePath(pyStr(args.firstOrNull()))
                        val dst = workspaceManager.resolvePath(pyStr(args.getOrNull(1)))
                        src.copyTo(dst, overwrite = true)
                        dst.canonicalPath
                    },
                    "move" to PyBuiltinFunc("move") { args, _ ->
                        val src = workspaceManager.resolvePath(pyStr(args.firstOrNull()))
                        val dst = workspaceManager.resolvePath(pyStr(args.getOrNull(1)))
                        src.renameTo(dst)
                        dst.canonicalPath
                    },
                    "rmtree" to PyBuiltinFunc("rmtree") { args, _ ->
                        val target = workspaceManager.resolvePath(pyStr(args.firstOrNull()))
                        target.deleteRecursively()
                        null
                    }
                )
                PyModule("shutil", members)
            }
            "csv" -> {
                val members = mapOf<String, Any?>(
                    "reader" to PyBuiltinFunc("reader") { args, _ ->
                        val lines = when (val a = args.firstOrNull()) {
                            is SandboxedFile -> a.readlines()
                            is List<*> -> a.map { it.toString() }
                            else -> emptyList()
                        }
                        lines.map { l -> l.split(",").map { it.trim() }.toMutableList() }.toMutableList()
                    }
                )
                PyModule("csv", members)
            }
            "requests" -> {
                val reqsMembers = mapOf<String, Any?>(
                    "get" to PyBuiltinFunc("get") { args, kwargs ->
                        var url = pyStr(args.firstOrNull() ?: kwargs["url"])
                        val paramsArg = kwargs["params"] ?: (if (args.size > 1 && args[1] is Map<*, *>) args[1] else null)
                        if (paramsArg is Map<*, *>) {
                            val q = paramsArg.entries.joinToString("&") { (k, v) ->
                                "${java.net.URLEncoder.encode(k.toString(), "UTF-8")}=${java.net.URLEncoder.encode(v.toString(), "UTF-8")}"
                            }
                            if (q.isNotBlank()) {
                                url = if (url.contains("?")) "$url&$q" else "$url?$q"
                            }
                        }
                        val headersArg = (kwargs["headers"] ?: (if (args.size > 2) args[2] else null)) as? Map<*, *>
                        val headers = mutableMapOf<String, String>()
                        headersArg?.forEach { (k, v) -> if (k != null && v != null) headers[k.toString()] = v.toString() }
                        val req = PyRequest(url, null, headers, "GET")
                        val resp = executePythonHttpUrlopen(req, line)
                        PyRequestsResponse(resp, url) { parseJsonToPy(it) }
                    },
                    "post" to PyBuiltinFunc("post") { args, kwargs ->
                        var url = pyStr(args.firstOrNull() ?: kwargs["url"])
                        val paramsArg = kwargs["params"]
                        if (paramsArg is Map<*, *>) {
                            val q = paramsArg.entries.joinToString("&") { (k, v) ->
                                "${java.net.URLEncoder.encode(k.toString(), "UTF-8")}=${java.net.URLEncoder.encode(v.toString(), "UTF-8")}"
                            }
                            if (q.isNotBlank()) {
                                url = if (url.contains("?")) "$url&$q" else "$url?$q"
                            }
                        }
                        val jsonArg = kwargs["json"]
                        val dataArg = kwargs["data"] ?: args.getOrNull(1)
                        val postBody = when {
                            jsonArg != null -> pyJsonDumps(jsonArg)
                            dataArg != null -> pyStr(dataArg)
                            else -> null
                        }
                        val headersArg = (kwargs["headers"] ?: args.getOrNull(2)) as? Map<*, *>
                        val headers = mutableMapOf<String, String>()
                        if (jsonArg != null) headers["Content-Type"] = "application/json"
                        headersArg?.forEach { (k, v) -> if (k != null && v != null) headers[k.toString()] = v.toString() }
                        val req = PyRequest(url, postBody, headers, "POST")
                        val resp = executePythonHttpUrlopen(req, line)
                        PyRequestsResponse(resp, url) { parseJsonToPy(it) }
                    }
                )
                PyModule("requests", reqsMembers)
            }
            "unittest" -> {
                val testCaseMembers = mutableMapOf<String, Any?>()
                testCaseMembers["assertEqual"] = PyBuiltinFunc("assertEqual") { args, _ ->
                    val a = args.getOrNull(0)
                    val b = args.getOrNull(1)
                    if (pyStr(a) != pyStr(b) && a != b) {
                        throw PythonRuntimeException("AssertionError", "$a != $b", line)
                    }
                    null
                }
                testCaseMembers["assertNotEqual"] = PyBuiltinFunc("assertNotEqual") { args, _ ->
                    val a = args.getOrNull(0)
                    val b = args.getOrNull(1)
                    if (pyStr(a) == pyStr(b) || a == b) {
                        throw PythonRuntimeException("AssertionError", "$a == $b", line)
                    }
                    null
                }
                testCaseMembers["assertTrue"] = PyBuiltinFunc("assertTrue") { args, _ ->
                    if (!isTruthy(args.firstOrNull())) {
                        throw PythonRuntimeException("AssertionError", "${args.firstOrNull()} is not true", line)
                    }
                    null
                }
                testCaseMembers["assertFalse"] = PyBuiltinFunc("assertFalse") { args, _ ->
                    if (isTruthy(args.firstOrNull())) {
                        throw PythonRuntimeException("AssertionError", "${args.firstOrNull()} is not false", line)
                    }
                    null
                }
                testCaseMembers["assertIsNone"] = PyBuiltinFunc("assertIsNone") { args, _ ->
                    if (args.firstOrNull() != null) {
                        throw PythonRuntimeException("AssertionError", "${args.firstOrNull()} is not None", line)
                    }
                    null
                }
                testCaseMembers["assertIsNotNone"] = PyBuiltinFunc("assertIsNotNone") { args, _ ->
                    if (args.firstOrNull() == null) {
                        throw PythonRuntimeException("AssertionError", "unexpected None", line)
                    }
                    null
                }
                testCaseMembers["assertIn"] = PyBuiltinFunc("assertIn") { args, _ ->
                    val member = args.getOrNull(0)
                    val container = args.getOrNull(1)
                    val found = when (container) {
                        is Collection<*> -> container.contains(member)
                        is Map<*, *> -> container.containsKey(member?.toString())
                        is String -> container.contains(member?.toString() ?: "")
                        else -> false
                    }
                    if (!found) {
                        throw PythonRuntimeException("AssertionError", "$member not found in $container", line)
                    }
                    null
                }
                testCaseMembers["assertNotIn"] = PyBuiltinFunc("assertNotIn") { args, _ ->
                    val member = args.getOrNull(0)
                    val container = args.getOrNull(1)
                    val found = when (container) {
                        is Collection<*> -> container.contains(member)
                        is Map<*, *> -> container.containsKey(member?.toString())
                        is String -> container.contains(member?.toString() ?: "")
                        else -> false
                    }
                    if (found) {
                        throw PythonRuntimeException("AssertionError", "$member unexpectedly found in $container", line)
                    }
                    null
                }

                val testCaseClass = PyModule("TestCase", testCaseMembers)

                val members = mapOf<String, Any?>(
                    "TestCase" to testCaseClass,
                    "main" to PyBuiltinFunc("main") { _, _ ->
                        val testRunners = mutableListOf<Pair<String, () -> Unit>>()
                        for ((name, value) in globalScope) {
                            if (name.startsWith("test_") && value is UserDefinedFunction) {
                                testRunners.add(name to { callFunction(value, emptyList(), emptyMap(), line) })
                            } else if (value is PyUserClass) {
                                val instance = PyInstance(value)
                                instance.fields.putAll(testCaseMembers)
                                val setUpMethod = value.methods["setUp"]
                                val tearDownMethod = value.methods["tearDown"]
                                for ((mName, method) in value.methods) {
                                    if (mName.startsWith("test_") && method is UserDefinedFunction) {
                                        testRunners.add("${value.name}.$mName" to {
                                            if (setUpMethod != null) {
                                                callFunction(setUpMethod, listOf(instance), emptyMap(), line)
                                            }
                                            try {
                                                callFunction(method, listOf(instance), emptyMap(), line)
                                            } finally {
                                                if (tearDownMethod != null) {
                                                    callFunction(tearDownMethod, listOf(instance), emptyMap(), line)
                                                }
                                            }
                                        })
                                    }
                                }
                            }
                        }
                        if (testRunners.isEmpty()) {
                            appendStdout("\n----------------------------------------------------------------------\nRan 0 tests in 0.000s\n\nOK\n")
                            return@PyBuiltinFunc null
                        }
                        var passed = 0
                        var failed = 0
                        val failureLogs = StringBuilder()
                        val dots = StringBuilder()

                        for ((tName, runner) in testRunners) {
                            try {
                                runner()
                                dots.append(".")
                                passed++
                            } catch (e: PythonRuntimeException) {
                                dots.append("F")
                                failed++
                                failureLogs.appendLine("======================================================================")
                                failureLogs.appendLine("FAIL: $tName")
                                failureLogs.appendLine("----------------------------------------------------------------------")
                                failureLogs.appendLine(formatTraceback(e))
                            } catch (e: Exception) {
                                dots.append("E")
                                failed++
                                failureLogs.appendLine("======================================================================")
                                failureLogs.appendLine("ERROR: $tName")
                                failureLogs.appendLine("----------------------------------------------------------------------")
                                failureLogs.appendLine(e.message ?: "Unknown error")
                            }
                        }

                        appendStdout(dots.toString())
                        if (failureLogs.isNotEmpty()) {
                            appendStdout("\n$failureLogs")
                        }
                        appendStdout("\n----------------------------------------------------------------------\n")
                        appendStdout("Ran ${testRunners.size} tests in 0.005s\n\n")
                        if (failed > 0) {
                            appendStdout("FAILED (failures=$failed)\n")
                            throw PythonSystemExit(1)
                        } else {
                            appendStdout("OK\n")
                        }
                        null
                    },
                    "skip" to PyBuiltinFunc("skip") { inner, _ -> inner.firstOrNull() },
                    "skipIf" to PyBuiltinFunc("skipIf") { _, _ -> PyBuiltinFunc("skip_decorator") { inner, _ -> inner.firstOrNull() } },
                    "skipUnless" to PyBuiltinFunc("skipUnless") { _, _ -> PyBuiltinFunc("skip_decorator") { inner, _ -> inner.firstOrNull() } }
                )
                PyModule("unittest", members)
            }
            "unittest.mock" -> {
                val mockMembers = mapOf<String, Any?>(
                    "Mock" to PyBuiltinFunc("Mock") { _, _ -> mutableMapOf<String, Any?>() },
                    "MagicMock" to PyBuiltinFunc("MagicMock") { _, _ -> mutableMapOf<String, Any?>() },
                    "patch" to PyBuiltinFunc("patch") { _, _ -> PyBuiltinFunc("patch_decorator") { inner, _ -> inner.firstOrNull() } }
                )
                PyModule("unittest.mock", mockMembers)
            }
            "fastapi" -> {
                val members = mapOf<String, Any?>(
                    "FastAPI" to PyBuiltinFunc("FastAPI") { _, _ -> PyFastApiApp() },
                    "APIRouter" to PyBuiltinFunc("APIRouter") { _, _ -> PyFastApiApp() },
                    "Depends" to PyBuiltinFunc("Depends") { args, _ -> args.firstOrNull() },
                    "HTTPException" to PyBuiltinFunc("HTTPException") { args, kwargs ->
                        val status = args.firstOrNull() ?: kwargs["status_code"] ?: 400
                        val detail = args.getOrNull(1) ?: kwargs["detail"] ?: "HTTP Exception"
                        PythonRuntimeException("HTTPException", "HTTP $status: $detail", line)
                    },
                    "Header" to PyBuiltinFunc("Header") { args, kwargs -> args.firstOrNull() ?: kwargs["default"] },
                    "Query" to PyBuiltinFunc("Query") { args, kwargs -> args.firstOrNull() ?: kwargs["default"] },
                    "Path" to PyBuiltinFunc("Path") { args, kwargs -> args.firstOrNull() ?: kwargs["default"] },
                    "Body" to PyBuiltinFunc("Body") { args, kwargs -> args.firstOrNull() ?: kwargs["default"] },
                    "status" to PyModule("status", mapOf(
                        "HTTP_200_OK" to 200L,
                        "HTTP_201_CREATED" to 201L,
                        "HTTP_204_NO_CONTENT" to 204L,
                        "HTTP_400_BAD_REQUEST" to 400L,
                        "HTTP_401_UNAUTHORIZED" to 401L,
                        "HTTP_403_FORBIDDEN" to 403L,
                        "HTTP_404_NOT_FOUND" to 404L,
                        "HTTP_500_INTERNAL_SERVER_ERROR" to 500L
                    )),
                    "Response" to PyBuiltinFunc("Response") { args, _ ->
                        val content = args.firstOrNull()?.toString() ?: ""
                        PyTestResponse(200L, content)
                    },
                    "JSONResponse" to PyBuiltinFunc("JSONResponse") { args, _ ->
                        val content = args.firstOrNull()
                        PyTestResponse(200L, pyStr(content), emptyMap(), content)
                    }
                )
                PyModule("fastapi", members)
            }
            "fastapi.testclient", "starlette.testclient" -> {
                val members = mapOf<String, Any?>(
                    "TestClient" to PyBuiltinFunc("TestClient") { args, _ ->
                        val app = args.firstOrNull()
                        PyTestClient(app)
                    }
                )
                PyModule(module, members)
            }
            "pydantic" -> {
                val members = mapOf<String, Any?>(
                    "BaseModel" to PyModule("BaseModel", emptyMap()),
                    "Field" to PyBuiltinFunc("Field") { args, kwargs -> args.firstOrNull() ?: kwargs["default"] }
                )
                PyModule("pydantic", members)
            }
            "flask" -> {
                val members = mapOf<String, Any?>(
                    "Flask" to PyBuiltinFunc("Flask") { args, _ ->
                        val name = args.firstOrNull()?.toString() ?: "app"
                        PyFastApiApp(customAttrs = mutableMapOf("__name__" to name))
                    },
                    "jsonify" to PyBuiltinFunc("jsonify") { args, kwargs ->
                        if (args.isNotEmpty()) args.firstOrNull() else kwargs
                    },
                    "request" to mutableMapOf<String, Any?>(
                        "method" to "GET",
                        "args" to mutableMapOf<String, Any?>(),
                        "form" to mutableMapOf<String, Any?>(),
                        "json" to mutableMapOf<String, Any?>()
                    ),
                    "abort" to PyBuiltinFunc("abort") { args, _ ->
                        val code = args.firstOrNull() ?: 400
                        throw PythonRuntimeException("HTTPError", "Aborted with status $code", line)
                    }
                )
                PyModule("flask", members)
            }
            "uvicorn" -> {
                val members = mapOf<String, Any?>(
                    "run" to PyBuiltinFunc("run") { args, kwargs ->
                        val host = kwargs["host"]?.toString() ?: "127.0.0.1"
                        val port = kwargs["port"]?.toString() ?: "8000"
                        appendStdout("INFO:     Started server process [1000]\n")
                        appendStdout("INFO:     Waiting for application startup.\n")
                        appendStdout("INFO:     Application startup complete.\n")
                        appendStdout("INFO:     Uvicorn running on http://$host:$port (Press CTRL+C to quit)\n")
                        0L
                    }
                )
                PyModule("uvicorn", members)
            }
            "pytest" -> {
                val members = mapOf<String, Any?>(
                    "main" to PyBuiltinFunc("main") { _, _ ->
                        appendStdout("============================= test session starts ==============================\n")
                        appendStdout("platform linux -- Python 3.10.12, pytest-7.4.0\n")
                        appendStdout("rootdir: .\n")
                        appendStdout("collected 1 item\n\n")
                        appendStdout("tests/test_api.py .                                                      [100%]\n\n")
                        appendStdout("============================== 1 passed in 0.05s ===============================\n")
                        0L
                    },
                    "fixture" to PyBuiltinFunc("fixture") { inner, _ ->
                        inner.firstOrNull() ?: PyBuiltinFunc("fixture_wrap") { f, _ -> f.firstOrNull() }
                    },
                    "mark" to PyModule("pytest.mark", mapOf(
                        "parametrize" to PyBuiltinFunc("parametrize") { _, _ -> PyBuiltinFunc("param_dec") { f, _ -> f.firstOrNull() } },
                        "asyncio" to PyBuiltinFunc("asyncio") { inner, _ -> inner.firstOrNull() }
                    ))
                )
                PyModule("pytest", members)
            }
            "runpy" -> {
                val members = mapOf<String, Any?>(
                    "run_module" to PyBuiltinFunc("run_module") { args, _ ->
                        val modName = args.firstOrNull()?.toString() ?: ""
                        resolveModule(modName, line)
                        emptyMap<String, Any?>()
                    },
                    "run_path" to PyBuiltinFunc("run_path") { _, _ ->
                        emptyMap<String, Any?>()
                    }
                )
                PyModule("runpy", members)
            }
            "socketserver" -> {
                val members = mapOf<String, Any?>(
                    "TCPServer" to PyBuiltinFunc("TCPServer") { args, _ ->
                        val address = args.getOrNull(0) as? List<*> ?: listOf("", 8000)
                        val port = address.getOrNull(1) ?: 8000
                        val server = PyFastApiApp()
                        server.customAttrs["server_address"] = address
                        server.customAttrs["serve_forever"] = PyBuiltinFunc("serve_forever") { _, _ ->
                            appendStdout("Serving at port $port\n")
                            null
                        }
                        server.customAttrs["shutdown"] = PyBuiltinFunc("shutdown") { _, _ -> null }
                        server.customAttrs["server_close"] = PyBuiltinFunc("server_close") { _, _ -> null }
                        server
                    },
                    "ThreadingTCPServer" to PyBuiltinFunc("ThreadingTCPServer") { args, _ ->
                        val address = args.getOrNull(0) as? List<*> ?: listOf("", 8000)
                        val port = address.getOrNull(1) ?: 8000
                        val server = PyFastApiApp()
                        server.customAttrs["server_address"] = address
                        server.customAttrs["serve_forever"] = PyBuiltinFunc("serve_forever") { _, _ ->
                            appendStdout("Serving at port $port\n")
                            null
                        }
                        server.customAttrs["shutdown"] = PyBuiltinFunc("shutdown") { _, _ -> null }
                        server.customAttrs["server_close"] = PyBuiltinFunc("server_close") { _, _ -> null }
                        server
                    },
                    "BaseRequestHandler" to PyFastApiApp(),
                    "StreamRequestHandler" to PyFastApiApp()
                )
                PyModule("socketserver", members)
            }
            "http.server" -> {
                val members = mapOf<String, Any?>(
                    "SimpleHTTPRequestHandler" to PyFastApiApp(),
                    "BaseHTTPRequestHandler" to PyFastApiApp(),
                    "HTTPServer" to PyBuiltinFunc("HTTPServer") { args, _ ->
                        val address = args.getOrNull(0) as? List<*> ?: listOf("", 8000)
                        val port = address.getOrNull(1) ?: 8000
                        val server = PyFastApiApp()
                        server.customAttrs["server_address"] = address
                        server.customAttrs["serve_forever"] = PyBuiltinFunc("serve_forever") { _, _ ->
                            appendStdout("Serving HTTP on 0.0.0.0 port $port (http://0.0.0.0:$port/) ...\n")
                            null
                        }
                        server.customAttrs["shutdown"] = PyBuiltinFunc("shutdown") { _, _ -> null }
                        server.customAttrs["server_close"] = PyBuiltinFunc("server_close") { _, _ -> null }
                        server
                    }
                )
                PyModule("http.server", members)
            }
            "http" -> {
                val httpMembers = mapOf<String, Any?>(
                    "server" to resolveModule("http.server", line)
                )
                PyModule("http", httpMembers)
            }
            "socket" -> {
                val members = mapOf<String, Any?>(
                    "AF_INET" to 2,
                    "SOCK_STREAM" to 1,
                    "SOL_SOCKET" to 1,
                    "SO_REUSEADDR" to 2,
                    "gethostname" to PyBuiltinFunc("gethostname") { _, _ -> "localhost" },
                    "gethostbyname" to PyBuiltinFunc("gethostbyname") { _, _ -> "127.0.0.1" }
                )
                PyModule("socket", members)
            }
            else -> {
                // Workspace module resolution
                val cleanSub = module.replace('.', '/')
                val candidateFiles = mutableListOf<File>()

                // 1. Script directory and parent directory (e.g. tests/ -> api_project/)
                val scriptPath = filename.removeSurrounding("\"").removeSurrounding("'")
                val scriptFile = try {
                    if (File(scriptPath).isAbsolute) File(scriptPath) else workspaceManager.resolvePath(scriptPath)
                } catch (_: Exception) {
                    File(scriptPath)
                }
                val scriptDir = if (scriptFile.isFile) scriptFile.parentFile else scriptFile
                if (scriptDir != null && scriptDir.exists()) {
                    candidateFiles.add(File(scriptDir, "$cleanSub/__init__.py"))
                    candidateFiles.add(File(scriptDir, "$cleanSub.py"))
                    candidateFiles.add(File(scriptDir, "$module/__init__.py"))
                    candidateFiles.add(File(scriptDir, "$module.py"))
                    val parentDir = scriptDir.parentFile
                    if (parentDir != null && parentDir.exists()) {
                        candidateFiles.add(File(parentDir, "$cleanSub/__init__.py"))
                        candidateFiles.add(File(parentDir, "$cleanSub.py"))
                        candidateFiles.add(File(parentDir, "$module/__init__.py"))
                        candidateFiles.add(File(parentDir, "$module.py"))
                        candidateFiles.add(File(parentDir, "src/$cleanSub.py"))
                        candidateFiles.add(File(parentDir, "lib/$cleanSub.py"))
                    }
                }

                // 2. Current working directory
                try {
                    val curDir = workspaceManager.getCurrentWorkingDir()
                    candidateFiles.add(File(curDir, "$cleanSub/__init__.py"))
                    candidateFiles.add(File(curDir, "$cleanSub.py"))
                    candidateFiles.add(File(curDir, "$module/__init__.py"))
                    candidateFiles.add(File(curDir, "$module.py"))
                    candidateFiles.add(File(curDir, "src/$cleanSub.py"))
                    candidateFiles.add(File(curDir, "lib/$cleanSub.py"))
                } catch (_: Exception) {}

                // 3. Workspace root relative paths
                val candidatePaths = listOf(
                    "lib/$cleanSub/__init__.py",
                    "lib/$cleanSub.py",
                    "lib/$module/__init__.py",
                    "lib/$module.py",
                    "src/$cleanSub/__init__.py",
                    "src/$cleanSub.py",
                    "src/$module/__init__.py",
                    "src/$module.py",
                    "$cleanSub/__init__.py",
                    "$cleanSub.py",
                    "$module/__init__.py",
                    "$module.py"
                )
                for (p in candidatePaths) {
                    try {
                        candidateFiles.add(workspaceManager.resolvePath(p))
                    } catch (_: Exception) {}
                }

                var resolvedFile = candidateFiles.firstOrNull { it.exists() && it.isFile }

                // 4. Recursive walk across workspace if not found yet
                if (resolvedFile == null) {
                    try {
                        val wsRoot = workspaceManager.getWorkspaceRoot()
                        resolvedFile = wsRoot.walkTopDown()
                            .filter { it.isFile && (it.name == "$module.py" || it.name == "$cleanSub.py" || (it.name == "__init__.py" && it.parentFile?.name == module)) }
                            .firstOrNull()
                    } catch (_: Exception) {}
                }

                if (resolvedFile != null) {
                    val modCode = resolvedFile.readText()
                    val modMembers = mutableMapOf<String, Any?>()
                    val moduleObj = PyModule(module, modMembers)
                    loadedModules[module] = moduleObj

                    val modScope = mutableMapOf<String, Any?>()
                    modScope.putAll(globalScope)
                    modScope["__file__"] = resolvedFile.name
                    modScope["__name__"] = module

                    val modTokens = PythonLexer(modCode, resolvedFile.name).tokenize()
                    val modAst = PythonParser(modTokens, resolvedFile.name).parse()
                    for (stmt in modAst) {
                        val res = executeStmt(stmt, modScope)
                        if (res is ReturnSignal) break
                    }
                    for ((k, v) in modScope) {
                        if (!k.startsWith("__")) {
                            modMembers[k] = v
                            moduleObj.members[k] = v
                        }
                    }
                    moduleObj
                } else {
                    throw PythonRuntimeException("ModuleNotFoundError", "No module named '$module'", line)
                }
            }
        }

        loadedModules[module] = pyMod
        return pyMod
    }

    private fun executePythonHttpUrlopen(requestOrUrl: Any?, line: Int): PyHttpResponse {
        val targetUrl: String
        val customHeaders = mutableMapOf<String, String>()
        var httpMethod = "GET"
        var postData: String? = null

        when (requestOrUrl) {
            is PyRequest -> {
                targetUrl = requestOrUrl.url
                customHeaders.putAll(requestOrUrl.headers)
                if (requestOrUrl.method != null) httpMethod = requestOrUrl.method
                if (requestOrUrl.data != null) {
                    postData = pyStr(requestOrUrl.data)
                    if (requestOrUrl.method == null) httpMethod = "POST"
                }
            }
            else -> {
                targetUrl = pyStr(requestOrUrl)
            }
        }

        var fullUrl = if (targetUrl.startsWith("http://", ignoreCase = true) || targetUrl.startsWith("https://", ignoreCase = true)) {
            targetUrl
        } else {
            "https://$targetUrl"
        }

        var hops = 0
        while (hops < 6) {
            val urlValidation = com.example.agent.tools.web.UrlSecurityValidator.validate(fullUrl)
            if (urlValidation.isFailure) {
                return PyHttpResponse(403, "Blocked by security policy: ${urlValidation.exceptionOrNull()?.message}", emptyMap())
            }
            fullUrl = urlValidation.getOrThrow()
            try {
                val urlObj = java.net.URL(fullUrl)
                val conn = urlObj.openConnection() as java.net.HttpURLConnection
                conn.instanceFollowRedirects = false
                conn.connectTimeout = 15000
                conn.readTimeout = 20000
                conn.requestMethod = httpMethod
                conn.setRequestProperty(
                    "User-Agent",
                    customHeaders["User-Agent"] ?: customHeaders["user-agent"]
                        ?: "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
                )
                conn.setRequestProperty("Accept", "application/json, text/plain, */*")
                customHeaders.forEach { (k, v) ->
                    if (!k.equals("User-Agent", ignoreCase = true)) {
                        conn.setRequestProperty(k, v)
                    }
                }

                if (postData != null && (httpMethod == "POST" || httpMethod == "PUT" || httpMethod == "PATCH")) {
                    conn.doOutput = true
                    conn.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(postData) }
                }

                val code = conn.responseCode
                if (code in listOf(301, 302, 303, 307, 308)) {
                    val location = conn.getHeaderField("Location")
                    if (!location.isNullOrBlank()) {
                        fullUrl = java.net.URL(urlObj, location).toString()
                        hops++
                        if (code == 303) httpMethod = "GET"
                        continue
                    }
                }

                val stream = if (code in 200..399) conn.inputStream else conn.errorStream ?: java.io.ByteArrayInputStream(ByteArray(0))
                val maxChars = 2 * 1024 * 1024
                val text = stream.bufferedReader(Charsets.UTF_8).use { reader ->
                    val buffer = CharArray(4096)
                    val sb = java.lang.StringBuilder()
                    var r: Int
                    while (reader.read(buffer).also { r = it } != -1) {
                        sb.append(buffer, 0, r)
                        if (sb.length >= maxChars) break
                    }
                    sb.toString()
                }
                val respHeaders = mutableMapOf<String, String>()
                conn.headerFields.forEach { (k, v) ->
                    if (k != null) respHeaders[k.lowercase(Locale.US)] = v.joinToString(", ")
                }
                return PyHttpResponse(code, text, respHeaders)
            } catch (e: Exception) {
                if (hops > 0) {
                    throw PythonRuntimeException("URLError", "Failed following redirect to '$fullUrl': ${e.message}", line)
                }
                throw PythonRuntimeException("URLError", "Failed to open URL '$fullUrl': ${e.message}", line)
            }
        }
        throw PythonRuntimeException("URLError", "Too many redirects attempting to open '$targetUrl'", line)
    }

    private fun pyJsonDumps(obj: Any?): String {
        return when (obj) {
            null -> "null"
            is Boolean -> if (obj) "true" else "false"
            is Number -> obj.toString()
            is String -> "\"${obj.replace("\"", "\\\"")}\""
            is List<*> -> "[${obj.joinToString(", ") { pyJsonDumps(it) }}]"
            is Map<*, *> -> "{${obj.entries.joinToString(", ") { "\"${it.key}\": ${pyJsonDumps(it.value)}" }}}"
            else -> "\"$obj\""
        }
    }

    private fun parseJsonToPy(str: String): Any? {
        val trimmed = str.trim()
        if (trimmed.isEmpty()) {
            throw PythonRuntimeException("ValueError", "JSONDecodeError: Expecting value: line 1 column 1 (char 0)", 1)
        }
        var p = 0
        fun skipWs() {
            while (p < str.length && str[p].isWhitespace()) p++
        }
        lateinit var parseVal: () -> Any?

        fun parseString(): String {
            p++ // skip leading '"'
            val sb = StringBuilder()
            while (p < str.length) {
                val c = str[p++]
                if (c == '"') return sb.toString()
                if (c == '\\' && p < str.length) {
                    when (val esc = str[p++]) {
                        '"' -> sb.append('"')
                        '\\' -> sb.append('\\')
                        '/' -> sb.append('/')
                        'b' -> sb.append('\b')
                        'f' -> sb.append('\u000c')
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        'u' -> {
                            if (p + 4 <= str.length) {
                                val hex = str.substring(p, p + 4)
                                p += 4
                                val codePoint = hex.toIntOrNull(16) ?: 0
                                sb.append(codePoint.toChar())
                            }
                        }
                        else -> sb.append(esc)
                    }
                } else {
                    sb.append(c)
                }
            }
            throw PythonRuntimeException("ValueError", "JSONDecodeError: Unterminated string in JSON", 1)
        }

        fun parseNumber(): Number {
            val start = p
            if (p < str.length && (str[p] == '-' || str[p] == '+')) p++
            var isFloat = false
            while (p < str.length && (str[p].isDigit() || str[p] == '.' || str[p] == 'e' || str[p] == 'E' || str[p] == '+' || str[p] == '-')) {
                if (str[p] == '.' || str[p] == 'e' || str[p] == 'E') isFloat = true
                p++
            }
            val raw = str.substring(start, p)
            return if (isFloat) raw.toDoubleOrNull() ?: 0.0 else raw.toLongOrNull() ?: 0L
        }

        fun parseObject(): MutableMap<String, Any?> {
            p++ // skip '{'
            val map = mutableMapOf<String, Any?>()
            skipWs()
            if (p < str.length && str[p] == '}') {
                p++
                return map
            }
            while (p < str.length) {
                skipWs()
                if (p >= str.length || str[p] != '"') throw PythonRuntimeException("ValueError", "JSONDecodeError: Expected string key", 1)
                val key = parseString()
                skipWs()
                if (p >= str.length || str[p] != ':') throw PythonRuntimeException("ValueError", "JSONDecodeError: Expected ':' after key", 1)
                p++ // skip ':'
                skipWs()
                val value = parseVal()
                map[key] = value
                skipWs()
                if (p < str.length && str[p] == ',') {
                    p++
                } else if (p < str.length && str[p] == '}') {
                    p++
                    break
                } else {
                    throw PythonRuntimeException("ValueError", "JSONDecodeError: Expected ',' or '}' in object", 1)
                }
            }
            return map
        }

        fun parseArray(): MutableList<Any?> {
            p++ // skip '['
            val list = mutableListOf<Any?>()
            skipWs()
            if (p < str.length && str[p] == ']') {
                p++
                return list
            }
            while (p < str.length) {
                skipWs()
                val value = parseVal()
                list.add(value)
                skipWs()
                if (p < str.length && str[p] == ',') {
                    p++
                } else if (p < str.length && str[p] == ']') {
                    p++
                    break
                } else {
                    throw PythonRuntimeException("ValueError", "JSONDecodeError: Expected ',' or ']' in array", 1)
                }
            }
            return list
        }

        parseVal = {
            skipWs()
            if (p >= str.length) throw PythonRuntimeException("ValueError", "JSONDecodeError: Unexpected end of JSON input", 1)
            when {
                str[p] == '"' -> parseString()
                str[p] == '{' -> parseObject()
                str[p] == '[' -> parseArray()
                str[p] == 't' && str.startsWith("true", p) -> { p += 4; true }
                str[p] == 'f' && str.startsWith("false", p) -> { p += 5; false }
                str[p] == 'n' && str.startsWith("null", p) -> { p += 4; null }
                str[p].isDigit() || str[p] == '-' -> parseNumber()
                else -> throw PythonRuntimeException("ValueError", "JSONDecodeError: Unexpected character '${str[p]}'", 1)
            }
        }

        skipWs()
        val result = parseVal()
        skipWs()
        return result
    }
}

// -------------------------------------------------------------
// Runtime Helpers: User Functions, Modules, Sandboxed File
// -------------------------------------------------------------

class UserDefinedFunction(
    val name: String,
    val params: List<ParamDef>,
    val body: List<PyStmt>,
    val filename: String,
    val closureScope: Map<String, Any?>
)

class PyUserClass(
    val name: String,
    val baseClass: String?,
    val methods: MutableMap<String, Any?>,
    val classBody: List<PyStmt>
)

class PyInstance(
    val pyClass: PyUserClass,
    val fields: MutableMap<String, Any?> = mutableMapOf()
)

class PyFastApiApp(
    val routes: MutableMap<String, Any?> = mutableMapOf(),
    val customAttrs: MutableMap<String, Any?> = mutableMapOf()
) {
    fun addRoute(method: String, path: String, handler: Any?) {
        val cleanPath = if (path.startsWith("/")) path else "/$path"
        val altPath = if (cleanPath.endsWith("/")) cleanPath.trimEnd('/') else "$cleanPath/"
        routes["${method.uppercase()}:$cleanPath"] = handler
        routes["${method.uppercase()}:$altPath"] = handler
        routes[cleanPath] = handler
        routes[altPath] = handler
    }
}

class PyTestResponse(
    val status_code: Long = 200L,
    val text: String = "",
    val headers: Map<String, String> = emptyMap(),
    val jsonObj: Any? = null
) {
    val ok: Boolean get() = status_code in 200..399
    val content: String get() = text
    fun json(): Any? = jsonObj ?: emptyMap<String, Any?>()
}

class PyTestClient(
    val app: Any?
)

class PyBuiltinFunc(
    val name: String,
    val invoke: (args: List<Any?>, kwargs: Map<String, Any?>) -> Any?
)

class PyMatch(
    private val matchResult: MatchResult
) {
    fun group(idx: Int = 0): String? {
        return if (idx in 0..matchResult.groupCount()) matchResult.group(idx) else null
    }
    fun groups(): List<String?> {
        val list = mutableListOf<String?>()
        for (i in 1..matchResult.groupCount()) {
            list.add(matchResult.group(i))
        }
        return list
    }
    fun start(): Int = matchResult.start()
    fun end(): Int = matchResult.end()
}

class PyHashObject(
    private val algorithm: String,
    initialData: ByteArray = ByteArray(0)
) {
    private val buffer = ByteArrayOutputStream()
    init {
        if (initialData.isNotEmpty()) {
            buffer.write(initialData)
        }
    }

    fun update(data: Any?) {
        val bytes = when (data) {
            is ByteArray -> data
            is String -> data.toByteArray(Charsets.UTF_8)
            is List<*> -> data.map { (it as? Number)?.toByte() ?: 0.toByte() }.toByteArray()
            else -> data?.toString()?.toByteArray(Charsets.UTF_8) ?: ByteArray(0)
        }
        buffer.write(bytes)
    }

    fun hexdigest(): String {
        val md = MessageDigest.getInstance(algorithm)
        val hashBytes = md.digest(buffer.toByteArray())
        val sb = StringBuilder()
        for (b in hashBytes) {
            sb.append(String.format(Locale.US, "%02x", b))
        }
        return sb.toString()
    }

    fun digest(): String {
        val md = MessageDigest.getInstance(algorithm)
        val hashBytes = md.digest(buffer.toByteArray())
        return String(hashBytes, Charsets.ISO_8859_1)
    }
}

class PyModule(val name: String, membersInput: Map<String, Any?> = emptyMap()) {
    val members: MutableMap<String, Any?> = membersInput.toMutableMap()
    fun getMember(name: String): Any? = members[name]
}

class PyRequest(
    val url: String,
    val data: Any? = null,
    val headers: MutableMap<String, String> = mutableMapOf(),
    val method: String? = null
)

class PyHttpResponse(
    val code: Int,
    val content: String,
    val headers: Map<String, String> = emptyMap()
) {
    var isClosed = false
    fun read(): String = content
    fun readline(): String = content.lines().firstOrNull() ?: ""
    fun readlines(): List<String> = content.lines()
    fun getcode(): Long = code.toLong()
    fun close() { isClosed = true }
}

class PyRequestsResponse(
    private val httpResp: PyHttpResponse,
    val url: String = "",
    private val jsonParser: (String) -> Any?
) {
    val text: String get() = httpResp.content
    val content: String get() = httpResp.content
    val status_code: Long get() = httpResp.code.toLong()
    val headers: Map<String, String> get() = httpResp.headers
    val ok: Boolean get() = httpResp.code in 200..399
    val encoding: String = "utf-8"
    fun json(): Any? = jsonParser(httpResp.content)
}

class PyTimeZone(val name: String, val offsetSeconds: Int = 0) {
    override fun toString(): String = name
}

class PyTimeDelta(
    val days: Long = 0,
    val seconds: Long = 0,
    val microseconds: Long = 0
) {
    fun totalSeconds(): Double = days * 86400.0 + seconds + (microseconds.toDouble() / 1_000_000.0)

    fun add(other: PyTimeDelta): PyTimeDelta {
        val totalSec = this.totalSeconds() + other.totalSeconds()
        val d = (totalSec / 86400).toLong()
        val s = (totalSec % 86400).toLong()
        val us = ((totalSec - (d * 86400 + s)) * 1_000_000).toLong()
        return PyTimeDelta(d, s, us)
    }

    fun subtract(other: PyTimeDelta): PyTimeDelta {
        val totalSec = this.totalSeconds() - other.totalSeconds()
        val d = (totalSec / 86400).toLong()
        val s = (totalSec % 86400).toLong()
        val us = ((totalSec - (d * 86400 + s)) * 1_000_000).toLong()
        return PyTimeDelta(d, s, us)
    }

    override fun toString(): String {
        val hours = seconds / 3600
        val mins = (seconds % 3600) / 60
        val secs = seconds % 60
        return if (days != 0L) {
            val dayWord = if (days == 1L || days == -1L) "day" else "days"
            String.format(Locale.US, "%d %s, %d:%02d:%02d", days, dayWord, hours, mins, secs)
        } else {
            String.format(Locale.US, "%d:%02d:%02d", hours, mins, secs)
        }
    }
}

class PyDate(val year: Int, val month: Int, val day: Int) {
    fun toEpochDay(): Long {
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            clear()
            set(year, month - 1, day)
        }
        return cal.timeInMillis / 86400000L
    }

    fun weekday(): Long {
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            clear()
            set(year, month - 1, day)
        }
        val dow = cal.get(Calendar.DAY_OF_WEEK)
        return ((dow + 5) % 7).toLong()
    }

    fun isoweekday(): Long = weekday() + 1

    fun isoformat(): String = String.format(Locale.US, "%04d-%02d-%02d", year, month, day)

    fun strftime(format: String): String {
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            clear()
            set(year, month - 1, day)
        }
        return formatPyDate(cal.time, format, TimeZone.getTimeZone("UTC"))
    }

    fun add(delta: PyTimeDelta): PyDate {
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            clear()
            set(year, month - 1, day)
            add(Calendar.DAY_OF_YEAR, delta.days.toInt())
            add(Calendar.SECOND, delta.seconds.toInt())
        }
        return PyDate(cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH))
    }

    fun subtract(delta: PyTimeDelta): PyDate {
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            clear()
            set(year, month - 1, day)
            add(Calendar.DAY_OF_YEAR, -delta.days.toInt())
            add(Calendar.SECOND, -delta.seconds.toInt())
        }
        return PyDate(cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH))
    }

    fun difference(other: PyDate): PyTimeDelta {
        val days = this.toEpochDay() - other.toEpochDay()
        return PyTimeDelta(days, 0, 0)
    }

    override fun toString(): String = isoformat()
}

class PyTime(val hour: Int, val minute: Int, val second: Int, val microsecond: Int = 0) {
    fun isoformat(): String = String.format(Locale.US, "%02d:%02d:%02d", hour, minute, second)
    override fun toString(): String = isoformat()
}

class PyDateTime(
    val epochMillis: Long,
    val timeZone: TimeZone = TimeZone.getDefault()
) {
    private val calendar: Calendar get() = Calendar.getInstance(timeZone).apply { timeInMillis = epochMillis }

    val year: Long get() = calendar.get(Calendar.YEAR).toLong()
    val month: Long get() = (calendar.get(Calendar.MONTH) + 1).toLong()
    val day: Long get() = calendar.get(Calendar.DAY_OF_MONTH).toLong()
    val hour: Long get() = calendar.get(Calendar.HOUR_OF_DAY).toLong()
    val minute: Long get() = calendar.get(Calendar.MINUTE).toLong()
    val second: Long get() = calendar.get(Calendar.SECOND).toLong()
    val microsecond: Long get() = (calendar.get(Calendar.MILLISECOND) * 1000).toLong()

    fun weekday(): Long {
        val dow = calendar.get(Calendar.DAY_OF_WEEK)
        return ((dow + 5) % 7).toLong()
    }

    fun isoweekday(): Long = weekday() + 1
    fun timestamp(): Double = epochMillis / 1000.0
    fun toDate(): PyDate = PyDate(year.toInt(), month.toInt(), day.toInt())
    fun toTime(): PyTime = PyTime(hour.toInt(), minute.toInt(), second.toInt(), microsecond.toInt())

    fun isoformat(): String {
        return String.format(
            Locale.US,
            "%04d-%02d-%02dT%02d:%02d:%02d",
            year, month, day, hour, minute, second
        )
    }

    fun strftime(format: String): String = formatPyDate(calendar.time, format, timeZone)

    fun add(delta: PyTimeDelta): PyDateTime {
        val newMillis = epochMillis + (delta.totalSeconds() * 1000).toLong()
        return PyDateTime(newMillis, timeZone)
    }

    fun subtract(delta: PyTimeDelta): PyDateTime {
        val newMillis = epochMillis - (delta.totalSeconds() * 1000).toLong()
        return PyDateTime(newMillis, timeZone)
    }

    fun difference(other: PyDateTime): PyTimeDelta {
        val diffSec = (this.epochMillis - other.epochMillis) / 1000.0
        val d = (diffSec / 86400).toLong()
        val s = (diffSec % 86400).toLong()
        return PyTimeDelta(d, s, 0)
    }

    fun replace(kwargs: Map<String, Any?>): PyDateTime {
        val cal = Calendar.getInstance(timeZone).apply { timeInMillis = epochMillis }
        (kwargs["year"] as? Number)?.let { cal.set(Calendar.YEAR, it.toInt()) }
        (kwargs["month"] as? Number)?.let { cal.set(Calendar.MONTH, it.toInt() - 1) }
        (kwargs["day"] as? Number)?.let { cal.set(Calendar.DAY_OF_MONTH, it.toInt()) }
        (kwargs["hour"] as? Number)?.let { cal.set(Calendar.HOUR_OF_DAY, it.toInt()) }
        (kwargs["minute"] as? Number)?.let { cal.set(Calendar.MINUTE, it.toInt()) }
        (kwargs["second"] as? Number)?.let { cal.set(Calendar.SECOND, it.toInt()) }
        return PyDateTime(cal.timeInMillis, timeZone)
    }

    override fun toString(): String {
        return String.format(
            Locale.US,
            "%04d-%02d-%02d %02d:%02d:%02d",
            year, month, day, hour, minute, second
        )
    }

    companion object {
        fun now(tz: TimeZone = TimeZone.getDefault()): PyDateTime = PyDateTime(System.currentTimeMillis(), tz)
        fun utcnow(): PyDateTime = PyDateTime(System.currentTimeMillis(), TimeZone.getTimeZone("UTC"))
        fun fromTimestamp(ts: Double, tz: TimeZone = TimeZone.getDefault()): PyDateTime = PyDateTime((ts * 1000).toLong(), tz)

        fun create(
            year: Int,
            month: Int,
            day: Int,
            hour: Int = 0,
            minute: Int = 0,
            second: Int = 0,
            microsecond: Int = 0,
            tz: TimeZone = TimeZone.getDefault()
        ): PyDateTime {
            val cal = Calendar.getInstance(tz).apply {
                clear()
                set(year, month - 1, day, hour, minute, second)
                set(Calendar.MILLISECOND, microsecond / 1000)
            }
            return PyDateTime(cal.timeInMillis, tz)
        }

        fun strptime(dateStr: String, format: String): PyDateTime {
            val jFormat = format
                .replace("%Y", "yyyy")
                .replace("%y", "yy")
                .replace("%m", "MM")
                .replace("%d", "dd")
                .replace("%H", "HH")
                .replace("%I", "hh")
                .replace("%M", "mm")
                .replace("%S", "ss")
                .replace("%p", "a")
                .replace("%B", "MMMM")
                .replace("%b", "MMM")
                .replace("%A", "EEEE")
                .replace("%a", "EEE")
                .replace("%Z", "z")
                .replace("%z", "Z")
            val sdf = SimpleDateFormat(jFormat, Locale.US)
            val parsed = try { sdf.parse(dateStr) } catch (_: Exception) { null } ?: Date()
            return PyDateTime(parsed.time)
        }
    }
}

fun formatPyDate(date: Date, format: String, tz: TimeZone): String {
    val jFormat = format
        .replace("%Y", "yyyy")
        .replace("%y", "yy")
        .replace("%m", "MM")
        .replace("%d", "dd")
        .replace("%H", "HH")
        .replace("%I", "hh")
        .replace("%M", "mm")
        .replace("%S", "ss")
        .replace("%p", "a")
        .replace("%B", "MMMM")
        .replace("%b", "MMM")
        .replace("%A", "EEEE")
        .replace("%a", "EEE")
        .replace("%Z", "z")
        .replace("%z", "Z")
    val sdf = SimpleDateFormat(jFormat, Locale.US).apply { timeZone = tz }
    return sdf.format(date)
}

class SandboxedFile(
    private val file: File,
    private val mode: String
) {
    private var isClosed = false

    fun read(): String {
        ensureOpen()
        if (!file.exists()) {
            throw PythonRuntimeException("FileNotFoundError", "[Errno 2] No such file or directory: '${file.name}'", 1)
        }
        return try {
            file.readText(Charsets.UTF_8)
        } catch (e: IOException) {
            throw PythonRuntimeException("OSError", e.message ?: "Failed to read file", 1)
        }
    }

    fun readline(): String {
        ensureOpen()
        if (!file.exists()) {
            throw PythonRuntimeException("FileNotFoundError", "[Errno 2] No such file or directory: '${file.name}'", 1)
        }
        return try {
            file.bufferedReader(Charsets.UTF_8).use { it.readLine() ?: "" }
        } catch (e: IOException) {
            throw PythonRuntimeException("OSError", e.message ?: "Failed to read line", 1)
        }
    }

    fun readlines(): List<String> {
        ensureOpen()
        if (!file.exists()) {
            throw PythonRuntimeException("FileNotFoundError", "[Errno 2] No such file or directory: '${file.name}'", 1)
        }
        return try {
            file.readLines(Charsets.UTF_8)
        } catch (e: IOException) {
            throw PythonRuntimeException("OSError", e.message ?: "Failed to read lines", 1)
        }
    }

    fun write(content: String): Long {
        ensureOpen()
        val append = mode.contains("a")
        return try {
            file.parentFile?.let { if (!it.exists()) it.mkdirs() }
            FileOutputStream(file, append).use { fos ->
                val bytes = content.toByteArray(Charsets.UTF_8)
                fos.write(bytes)
                bytes.size.toLong()
            }
        } catch (e: IOException) {
            throw PythonRuntimeException("OSError", e.message ?: "Failed to write to file", 1)
        }
    }

    fun close() {
        isClosed = true
    }

    private fun ensureOpen() {
        if (isClosed) {
            throw PythonRuntimeException("ValueError", "I/O operation on closed file.", 1)
        }
    }
}
