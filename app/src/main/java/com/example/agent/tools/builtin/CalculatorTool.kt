package com.example.agent.tools.builtin

import com.example.agent.tools.AgentTool
import com.example.agent.tools.ToolDefinition
import com.example.agent.tools.ToolParameter
import com.example.agent.tools.ToolResult
import java.util.UUID

/**
 * Built-in Calculator Tool for Agent Mode.
 *
 * Safely evaluates mathematical expressions and returns structured results.
 * Supports arithmetic operators (+, -, *, /, %, ^), parentheses, constants (pi, e),
 * and standard mathematical functions (sqrt, abs, round, ceil, floor, sin, cos, tan, log).
 */
class CalculatorTool : AgentTool {

    override val definition: ToolDefinition = ToolDefinition(
        name = "calculator",
        description = "Evaluates mathematical expressions safely. Input parameter 'expression' is a mathematical formula (e.g. '24 * 7 + 15' or 'sqrt(144) + 12').",
        parameters = listOf(
            ToolParameter(
                name = "expression",
                type = "string",
                description = "The mathematical expression to evaluate (e.g., '128 * 16', '(45 + 55) / 4', 'sqrt(256) * 3')",
                required = true
            )
        )
    )

    override suspend fun execute(arguments: Map<String, Any?>): ToolResult {
        val callId = UUID.randomUUID().toString()

        val rawExpression = arguments["expression"]?.toString()
            ?: arguments["input"]?.toString()
            ?: arguments["expr"]?.toString()

        if (rawExpression.isNullOrBlank()) {
            return ToolResult.failure(
                callId = callId,
                toolName = definition.name,
                error = "Missing required parameter 'expression'. Please provide a valid mathematical expression string."
            )
        }

        return try {
            val evaluator = MathEvaluator(rawExpression.trim())
            val value = evaluator.parse()

            if (value.isNaN()) {
                ToolResult.failure(
                    callId = callId,
                    toolName = definition.name,
                    error = "Calculation resulted in NaN (Not a Number) for expression: '$rawExpression'"
                )
            } else if (value.isInfinite()) {
                ToolResult.failure(
                    callId = callId,
                    toolName = definition.name,
                    error = "Calculation resulted in infinity (overflow or division by zero) for expression: '$rawExpression'"
                )
            } else {
                val formatted = if (value == value.toLong().toDouble() && !value.toString().contains("e", ignoreCase = true)) {
                    value.toLong().toString()
                } else {
                    // Truncate trailing zeros if any
                    val s = String.format(java.util.Locale.US, "%.6f", value).trimEnd('0').trimEnd('.')
                    if (s.isEmpty()) "0" else s
                }

                ToolResult.success(
                    callId = callId,
                    toolName = definition.name,
                    result = formatted
                )
            }
        } catch (e: ArithmeticException) {
            ToolResult.failure(
                callId = callId,
                toolName = definition.name,
                error = "Arithmetic error: ${e.message}"
            )
        } catch (e: IllegalArgumentException) {
            ToolResult.failure(
                callId = callId,
                toolName = definition.name,
                error = "Expression syntax error: ${e.message}"
            )
        } catch (e: Exception) {
            ToolResult.failure(
                callId = callId,
                toolName = definition.name,
                error = "Evaluation failed: ${e.message ?: "Invalid mathematical expression"}"
            )
        }
    }

    /**
     * Pure Kotlin Recursive-Descent Expression Parser.
     * Evaluates formulas without external dependencies, reflection, or shell access.
     */
    private class MathEvaluator(private val expression: String) {
        private var pos = -1
        private var ch = '\u0000'

        private fun nextChar() {
            pos++
            ch = if (pos < expression.length) expression[pos] else '\u0000'
        }

        private fun eat(charToEat: Char): Boolean {
            while (ch == ' ' || ch == '\t') nextChar()
            if (ch == charToEat) {
                nextChar()
                return true
            }
            return false
        }

        fun parse(): Double {
            nextChar()
            val x = parseExpression()
            while (ch == ' ' || ch == '\t') nextChar()
            if (pos < expression.length) {
                throw IllegalArgumentException("Unexpected character '$ch' at position $pos in '$expression'")
            }
            return x
        }

        private fun parseExpression(): Double {
            var x = parseTerm()
            while (true) {
                when {
                    eat('+') -> x += parseTerm()
                    eat('-') -> x -= parseTerm()
                    else -> return x
                }
            }
        }

        private fun parseTerm(): Double {
            var x = parseFactor()
            while (true) {
                when {
                    eat('*') -> x *= parseFactor()
                    eat('/') -> {
                        val denom = parseFactor()
                        if (denom == 0.0) throw ArithmeticException("Division by zero")
                        x /= denom
                    }
                    eat('%') -> {
                        val denom = parseFactor()
                        if (denom == 0.0) throw ArithmeticException("Modulo by zero")
                        x %= denom
                    }
                    else -> return x
                }
            }
        }

        private fun parseFactor(): Double {
            var x = parsePrimary()
            if (eat('^')) {
                val exp = parseFactor() // right-associative
                x = Math.pow(x, exp)
            }
            return x
        }

        private fun parsePrimary(): Double {
            while (ch == ' ' || ch == '\t') nextChar()
            if (eat('+')) return parsePrimary()
            if (eat('-')) return -parsePrimary()

            var x: Double
            val startPos = pos

            if (eat('(')) {
                x = parseExpression()
                if (!eat(')')) throw IllegalArgumentException("Missing closing parenthesis ')' in '$expression'")
            } else if ((ch in '0'..'9') || ch == '.') {
                while ((ch in '0'..'9') || ch == '.') nextChar()
                val numStr = expression.substring(startPos, pos)
                x = numStr.toDoubleOrNull() ?: throw IllegalArgumentException("Invalid number: '$numStr'")
            } else if (ch in 'a'..'z' || ch in 'A'..'Z') {
                while (ch in 'a'..'z' || ch in 'A'..'Z') nextChar()
                val func = expression.substring(startPos, pos).lowercase()
                when (func) {
                    "pi" -> x = Math.PI
                    "e" -> x = Math.E
                    "sqrt" -> {
                        if (!eat('(')) throw IllegalArgumentException("Expected '(' after '$func'")
                        val arg = parseExpression()
                        if (!eat(')')) throw IllegalArgumentException("Missing ')' after '$func' argument")
                        if (arg < 0) throw ArithmeticException("Square root of negative number: $arg")
                        x = Math.sqrt(arg)
                    }
                    "abs" -> {
                        if (!eat('(')) throw IllegalArgumentException("Expected '(' after '$func'")
                        val arg = parseExpression()
                        if (!eat(')')) throw IllegalArgumentException("Missing ')' after '$func' argument")
                        x = Math.abs(arg)
                    }
                    "round" -> {
                        if (!eat('(')) throw IllegalArgumentException("Expected '(' after '$func'")
                        val arg = parseExpression()
                        if (!eat(')')) throw IllegalArgumentException("Missing ')' after '$func' argument")
                        x = Math.round(arg).toDouble()
                    }
                    "ceil" -> {
                        if (!eat('(')) throw IllegalArgumentException("Expected '(' after '$func'")
                        val arg = parseExpression()
                        if (!eat(')')) throw IllegalArgumentException("Missing ')' after '$func' argument")
                        x = Math.ceil(arg)
                    }
                    "floor" -> {
                        if (!eat('(')) throw IllegalArgumentException("Expected '(' after '$func'")
                        val arg = parseExpression()
                        if (!eat(')')) throw IllegalArgumentException("Missing ')' after '$func' argument")
                        x = Math.floor(arg)
                    }
                    "sin" -> {
                        if (!eat('(')) throw IllegalArgumentException("Expected '(' after '$func'")
                        val arg = parseExpression()
                        if (!eat(')')) throw IllegalArgumentException("Missing ')' after '$func' argument")
                        x = Math.sin(Math.toRadians(arg))
                    }
                    "cos" -> {
                        if (!eat('(')) throw IllegalArgumentException("Expected '(' after '$func'")
                        val arg = parseExpression()
                        if (!eat(')')) throw IllegalArgumentException("Missing ')' after '$func' argument")
                        x = Math.cos(Math.toRadians(arg))
                    }
                    "tan" -> {
                        if (!eat('(')) throw IllegalArgumentException("Expected '(' after '$func'")
                        val arg = parseExpression()
                        if (!eat(')')) throw IllegalArgumentException("Missing ')' after '$func' argument")
                        x = Math.tan(Math.toRadians(arg))
                    }
                    "log" -> {
                        if (!eat('(')) throw IllegalArgumentException("Expected '(' after '$func'")
                        val arg = parseExpression()
                        if (!eat(')')) throw IllegalArgumentException("Missing ')' after '$func' argument")
                        if (arg <= 0) throw ArithmeticException("Logarithm of non-positive number: $arg")
                        x = Math.log10(arg)
                    }
                    else -> throw IllegalArgumentException("Unknown function or constant: '$func'")
                }
            } else {
                throw IllegalArgumentException("Unexpected character '$ch' at position $pos in '$expression'")
            }
            return x
        }
    }
}
