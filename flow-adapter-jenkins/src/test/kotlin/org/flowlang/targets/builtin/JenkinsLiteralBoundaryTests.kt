package org.flowlang.targets.builtin

import groovy.lang.Binding
import groovy.lang.GroovyShell
import kotlin.test.*
import org.flowlang.ast.*
import org.flowlang.generators.GroovyExpr
import org.flowlang.parser.ExpressionParser

class JenkinsLiteralBoundaryTests {
    private fun evaluate(source: String, variables: Map<String, Any> = emptyMap()): Any? =
        GroovyShell(Binding(variables)).evaluate(source)

    @Test fun singleQuotedLiteralsRoundTripWithoutInterpolation() {
        val values = listOf(
            "", "normal", "'", "\\", "\\'", "trailing\\", "line\r\nbreak", "\t\b\u000C",
            "\${marker}", "\${marker = 'changed'}", "$" + "marker", "\\u000a", "\\u0027",
            "Příliš žluťoučký", "\u2028\u2029", "x'); marker = 'changed'; //"
        ) + (0..31).map { "before" + it.toChar() + "after" }
        values.forEach { value ->
            val bindings = linkedMapOf<String, Any>("marker" to "unchanged")
            val binding = Binding(bindings)
            val shell = GroovyShell(binding)
            assertEquals(value, shell.evaluate(groovyString(value)), "literal value: ${value.toCharArray().map { it.code }}")
            assertEquals(value, shell.evaluate("'${groovyEscape(value)}'"))
            assertEquals("unchanged", binding.getVariable("marker"))
        }
    }

    @Test fun regexEvaluationNeverExecutesLiteralInterpolation() {
        val marker = mutableListOf(false)
        val expression = GroovyExpr(emptySet()).render(BinaryExpressionNode(
            operator = "matches", left = StringLiteralNode(value = ""),
            right = StringLiteralNode(value = "(?x)#\${mark()}")
        ))
        assertEquals(true, evaluate("def mark() { marker[0] = true; return '' }\n$expression", mapOf("marker" to marker)))
        assertEquals(listOf(false), marker)
    }

    @Test fun injectionFixtureDetectsTheFormerSlashyEncoding() {
        val marker = mutableListOf(false)
        val formerEncoding = "('' ==~ /(?x)#\${mark()}/)"
        assertEquals(true, evaluate("def mark() { marker[0] = true; return '' }\n$formerEncoding", mapOf("marker" to marker)))
        assertEquals(listOf(true), marker, "The negative control must actually detect interpolation.")
    }

    @Test fun escapedFlowInterpolationRemainsLiteralThroughTheParser() {
        val parsed = ExpressionParser.parseSource("\"\" matches \"(?x)#\\\${mark()}\"", scope = "auto")
        val marker = mutableListOf(false)
        val rendered = GroovyExpr(emptySet()).render(parsed)
        assertEquals(true, evaluate("def mark() { marker[0] = true; return '' }\n$rendered", mapOf("marker" to marker)))
        assertEquals(listOf(false), marker)
    }

    @Test fun regexMeaningSurvivesQuotingIncludingEmptyPatternsAndAnchors() {
        val cases = listOf(
            Triple("", "", true), Triple("a", "", false),
            Triple("prod", "^prod$", true), Triple("production", "^prod$", false),
            Triple("a/b", "a/b", true), Triple("123", "\\d+", true),
            Triple("a'b", "a'b", true), Triple("\\", "\\\\", true),
            Triple("$" + "name", "\\$" + "name", true)
        )
        cases.forEach { (value, pattern, expected) ->
            val expression = BinaryExpressionNode(operator = "matches",
                left = StringLiteralNode(value = value), right = StringLiteralNode(value = pattern))
            assertEquals(expected, evaluate(GroovyExpr(emptySet()).render(expression)), "pattern=$pattern value=$value")
        }
        val semver = BinaryExpressionNode(operator = "matches", left = StringLiteralNode(value = "1.2.3"),
            right = IdentifierLiteralNode(value = "semver"))
        assertEquals(true, evaluate(GroovyExpr(emptySet()).render(semver)))
    }

    @Test fun inputPropertiesPreserveNamesAndCannotIntroduceStatements() {
        listOf("environment", "build-type", "x'\\\r\n", "x'; marker = 'changed'; //").forEach { name ->
            val vars = mapOf<String, Any>("params" to mapOf(name to "expected"), "marker" to "unchanged")
            val expression = GroovyExpr(setOf(name)).render(ReferenceNode(path = listOf(name)))
            assertEquals("expected", evaluate(expression, vars))
            assertEquals("expected", evaluate(JenkinsProjectionSyntax.renderInput(name), vars))
            assertEquals("expected", evaluate("\"${JenkinsProjectionSyntax.renderInputInterpolation(name)}\"", vars).toString())
        }
    }

    @Test fun credentialCallsPreserveOpaqueValues() {
        listOf("normal", "\\'", "line\r\nbreak", "x'); marker = 'changed'; //", "\${marker}").forEach { name ->
            assertEquals(name, evaluate("def credentials(String value) { value }\n${JenkinsProjectionSyntax.bindingValue(name)}"))
            val mapping = evaluate("def string(Map value) { value }\n${JenkinsProjectionSyntax.mappingSpec(name)}") as Map<*, *>
            assertEquals(name, mapping["credentialsId"])
            assertEquals(safeEnvName(name), mapping["variable"])
        }
    }

    @Test fun unknownOperatorsAreRejectedInsteadOfDroppingAuthoredMeaning() {
        val renderer = GroovyExpr(emptySet())
        val operand = BooleanLiteralNode(value = true)
        assertFailsWith<IllegalStateException> { renderer.render(UnaryExpressionNode(operator = "bogus", operand = operand)) }
        assertFailsWith<IllegalStateException> { renderer.render(UnaryPostfixExpressionNode(operator = "bogus", operand = operand)) }
        assertFailsWith<IllegalStateException> { renderer.render(BinaryExpressionNode(operator = "bogus", left = operand, right = operand)) }
        assertFailsWith<IllegalStateException> { renderer.render(LogicalExpressionNode(operator = "bogus", operands = listOf(operand))) }
    }
}
