package com.example.agent.python

import com.example.agent.tools.workspace.AgentWorkspaceManager
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
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
        } catch (e: Exception) {
            val duration = System.currentTimeMillis() - startTime
            appendStderr("RuntimeError: ${e.message ?: e.javaClass.simpleName}\n")
            PythonExecutionResult(
                stdout = stdoutBuffer.toString(),
                stderr = stderrBuffer.toString(),
                exitCode = 1,
                durationMs = duration,
                isTimedOut = false,
                isTruncated = isTruncated.get(),
                errorType = "RuntimeError"
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
    class Assign(val target: String, val value: PyExpr, line: Int) : PyStmt(line)
    class MultiAssign(val targets: List<String>, val value: PyExpr, line: Int) : PyStmt(line)
    class AugAssign(val target: String, val op: String, val value: PyExpr, line: Int) : PyStmt(line)
    class Expr(val expr: PyExpr, line: Int) : PyStmt(line)
    class Def(val name: String, val params: List<ParamDef>, val body: List<PyStmt>, line: Int) : PyStmt(line)
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
            "with", "break", "continue", "pass", "class", "True", "False",
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

            // Identifiers / Keywords / f-strings
            if (c == 'f' && pos + 1 < input.length && (input[pos + 1] == '"' || input[pos + 1] == '\'')) {
                val startCol = col
                pos++
                col++
                val strToken = readString(isFString = true, startCol = startCol)
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
                    if (matchNext('=')) tokens.add(Token(TokenType.MINUS_ASSIGN, "-=", line, startCol))
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

    private fun readString(isFString: Boolean, startCol: Int): Token {
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

            if (input[pos] == '\\' && pos + 1 < input.length) {
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
        val t = peek()
        if (t.type == TokenType.KEYWORD) {
            when (t.value) {
                "def" -> return parseDef()
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

    private fun parseDef(): PyStmt.Def {
        val defToken = consume(TokenType.KEYWORD, "Expected 'def'", "def")
        val nameToken = consume(TokenType.IDENTIFIER, "Expected function name after 'def'")
        consume(TokenType.LPAREN, "Expected '(' after function name")
        val params = mutableListOf<ParamDef>()
        if (!check(TokenType.RPAREN)) {
            do {
                val pName = consume(TokenType.IDENTIFIER, "Expected parameter name").value
                var defaultVal: PyExpr? = null
                if (match(TokenType.ASSIGN)) {
                    defaultVal = parseExpression()
                }
                params.add(ParamDef(pName, defaultVal))
            } while (match(TokenType.COMMA))
        }
        consume(TokenType.RPAREN, "Expected ')' after parameters")
        consume(TokenType.COLON, "Expected ':' after function signature")
        val body = parseBlock()
        return PyStmt.Def(nameToken.value, params, body, defToken.line)
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
        val module = consume(TokenType.IDENTIFIER, "Expected module name").value
        var alias: String? = null
        if (match(TokenType.KEYWORD, "as")) {
            alias = consume(TokenType.IDENTIFIER, "Expected alias after 'as'").value
        }
        consumeEndStatement()
        return PyStmt.Import(module, alias, impToken.line)
    }

    private fun parseFromImport(): PyStmt.Import {
        val fromToken = consume(TokenType.KEYWORD, "Expected 'from'", "from")
        val module = consume(TokenType.IDENTIFIER, "Expected module name").value
        consume(TokenType.KEYWORD, "Expected 'import'", "import")
        val item = consume(TokenType.IDENTIFIER, "Expected imported name").value
        var alias: String? = null
        if (match(TokenType.KEYWORD, "as")) {
            alias = consume(TokenType.IDENTIFIER, "Expected alias after 'as'").value
        }
        consumeEndStatement()
        return PyStmt.Import(module, alias ?: item, fromToken.line)
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
                scope[stmt.target] = value
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
                val func = UserDefinedFunction(stmt.name, stmt.params, stmt.body, filename, scope)
                scope[stmt.name] = func
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
                if (ctxObj !is SandboxedFile) {
                    throw PythonRuntimeException("TypeError", "'${ctxObj?.javaClass?.simpleName}' object does not support the context manager protocol", stmt.line)
                }
                if (stmt.asName != null) {
                    scope[stmt.asName] = ctxObj
                }
                try {
                    for (s in stmt.body) {
                        val res = executeStmt(s, scope)
                        if (res is ReturnSignal || res is BreakSignal || res is ContinueSignal) return res
                    }
                } finally {
                    ctxObj.close()
                }
            }
            is PyStmt.Import -> {
                handleImport(stmt.module, stmt.alias, scope, stmt.line)
            }
            is PyStmt.Break -> return BreakSignal
            is PyStmt.Continue -> return ContinueSignal
            is PyStmt.Pass -> {}
        }
        return null
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
        if (left is Number && right is Number) return left.toDouble() == right.toDouble()
        return left == right
    }

    private fun pyCompare(left: Any?, right: Any?, line: Int): Int {
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
        if (obj is PyModule) {
            return obj.getMember(name) ?: throw PythonRuntimeException("AttributeError", "module '${obj.name}' has no attribute '$name'", line)
        }
        if (obj is SandboxedFile) {
            return when (name) {
                "read" -> PyBuiltinFunc("read") { _, _ -> obj.read() }
                "readline" -> PyBuiltinFunc("readline") { _, _ -> obj.readline() }
                "readlines" -> PyBuiltinFunc("readlines") { _, _ -> obj.readlines() }
                "write" -> PyBuiltinFunc("write") { args, _ -> obj.write(pyStr(args.firstOrNull())) }
                "close" -> PyBuiltinFunc("close") { _, _ -> obj.close() }
                else -> throw PythonRuntimeException("AttributeError", "'file' object has no attribute '$name'", line)
            }
        }
        if (obj is String) {
            return when (name) {
                "upper" -> PyBuiltinFunc("upper") { _, _ -> obj.uppercase(Locale.US) }
                "lower" -> PyBuiltinFunc("lower") { _, _ -> obj.lowercase(Locale.US) }
                "strip" -> PyBuiltinFunc("strip") { _, _ -> obj.trim() }
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
            is UserDefinedFunction -> {
                checkDeadline()
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
            is List<*> -> "[${obj.joinToString(", ") { pyRepr(it) }}]"
            is Map<*, *> -> "{${obj.entries.joinToString(", ") { "'${it.key}': ${pyRepr(it.value)}" }}}"
            else -> obj.toString()
        }
    }

    private fun pyRepr(obj: Any?): String {
        return when (obj) {
            is String -> "'$obj'"
            else -> pyStr(obj)
        }
    }

    // -------------------------------------------------------------
    // Builtin Functions & Modules
    // -------------------------------------------------------------

    private fun initBuiltins() {
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
        val targetName = alias ?: module
        when (module) {
            "math" -> {
                val members = mapOf<String, Any?>(
                    "pi" to Math.PI,
                    "e" to Math.E,
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
                scope[targetName] = PyModule("math", members)
            }
            "random" -> {
                val rng = java.util.Random()
                val members = mapOf<String, Any?>(
                    "randint" to PyBuiltinFunc("randint") { args, _ ->
                        val a = (args.getOrNull(0) as? Number)?.toInt() ?: 0
                        val b = (args.getOrNull(1) as? Number)?.toInt() ?: 1
                        (a + rng.nextInt(b - a + 1)).toLong()
                    },
                    "random" to PyBuiltinFunc("random") { _, _ -> rng.nextDouble() },
                    "choice" to PyBuiltinFunc("choice") { args, _ ->
                        val list = toIterable(args.firstOrNull(), line)
                        if (list.isEmpty()) throw PythonRuntimeException("IndexError", "Cannot choose from an empty sequence", line)
                        list[rng.nextInt(list.size)]
                    },
                    "shuffle" to PyBuiltinFunc("shuffle") { args, _ ->
                        val list = args.firstOrNull() as? MutableList<Any?>
                            ?: throw PythonRuntimeException("TypeError", "shuffle argument must be a mutable list", line)
                        list.shuffle(rng)
                        null
                    }
                )
                scope[targetName] = PyModule("random", members)
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
                scope[targetName] = PyModule("sys", members)
            }
            "time" -> {
                val members = mapOf<String, Any?>(
                    "time" to PyBuiltinFunc("time") { _, _ -> System.currentTimeMillis() / 1000.0 },
                    "sleep" to PyBuiltinFunc("sleep") { args, _ ->
                        val secs = (args.firstOrNull() as? Number)?.toDouble() ?: 0.0
                        val ms = (secs * 1000).toLong()
                        Thread.sleep(ms.coerceAtMost(5000L))
                        checkDeadline()
                        null
                    }
                )
                scope[targetName] = PyModule("time", members)
            }
            "json" -> {
                val members = mapOf<String, Any?>(
                    "dumps" to PyBuiltinFunc("dumps") { args, _ -> pyJsonDumps(args.firstOrNull()) }
                )
                scope[targetName] = PyModule("json", members)
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
                scope[targetName] = PyModule("os", osMembers)
            }
            else -> {
                throw PythonRuntimeException("ModuleNotFoundError", "No module named '$module'", line)
            }
        }
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

class PyBuiltinFunc(
    val name: String,
    val invoke: (args: List<Any?>, kwargs: Map<String, Any?>) -> Any?
)

class PyModule(val name: String, private val members: Map<String, Any?>) {
    fun getMember(name: String): Any? = members[name]
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
