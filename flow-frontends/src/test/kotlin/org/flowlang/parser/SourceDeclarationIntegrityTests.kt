package org.flowlang.parser

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.flowlang.ast.*
import org.flowlang.frontend.FrontendCompilerComposition
import org.flowlang.frontend.intent.FlowIntentExpressionParser
import org.flowlang.frontend.source.FlowSourceFrontend
import org.flowlang.modules.FlowModule
import org.flowlang.modules.ModuleCatalog

class SourceDeclarationIntegrityTests {
    @Test fun sourceVersionIsNotLastWinsEvenWhenBothValuesAreEqual() {
        for (value in listOf("1.0", "2.0")) {
            rejected("«version» \"1.0\"\n«version» \"$value\"\nflow \"x\" { steps {} }", "version")
        }
    }

    @Test fun inputModifiersRejectRepeatedDefaultsAndRequiredMarkers() {
        rejected("flow \"x\" { input { x: text «default» null «default» \"next\" } steps {} }", "flow.input[0].default")
        rejected("flow \"x\" { input { x: text «required» «required» } steps {} }", "flow.input[0].required")
    }

    @Test fun systemTypeAndEveryConfigurationFieldHaveOneOccurrence() {
        rejected("flow \"x\" { systems { system \"s\" { «type»: one «type»: two } } steps {} }", "flow.systems[0].type")
        for (separator in listOf(" ", ", ", "\n", " # comment\n", " // comment\n")) {
            rejected("flow \"x\" { systems { system \"s\" { type: one «url»: null${separator}«url»: null } } steps {} }", "flow.systems[0].config.url")
        }
    }

    @Test fun actionsAndApprovalsCannotReplaceParameters() {
        rejected(flow("m.a s { «x»: 1 «x»: 2 }"), "flow.steps[0].params.x")
        rejected(flow("approve manual { «message»: null «message»: \"next\" }"), "flow.steps[0].params.message")
    }

    @Test fun actionSafetyCannotBeOverwrittenOrReasserted() {
        for (value in listOf("onlyIf true", "requiresApproval")) {
            rejected(flow("m.a s { «safety»: requiresApproval «safety»: $value }"), "flow.steps[0].safety")
        }
    }

    @Test fun transformFilterIsNotLastWins() {
        rejected(flow("transform xs -> ys { «where» false «where» true }"), "flow.steps[0].where")
    }

    @Test fun selectKeyOccurrencesSpanAllSelectBlocksOfTheSameTransform() {
        rejected(flow("transform xs -> ys { select { «x»: 1 «x»: 2 } }"), "flow.steps[0].select.x")
        rejected(flow("transform xs -> ys { select { «x»: 1 } select {} select { «x»: 1 } }"), "flow.steps[0].select.x")
    }

    @Test fun aggregateFieldsCannotBeOverwritten() {
        rejected(flow("aggregate xs -> ys { «x»: null «x»: 2 }"), "flow.steps[0].fields.x")
    }

    @Test fun emptyMatchDefaultStillCountsAsADeclaration() {
        rejected(flow("match value { «default» {} «default» { skip \"replacement\" } }"), "flow.steps[0].defaultSteps")
        rejected(flow("match value { «default» {} «default» {} }"), "flow.steps[0].defaultSteps")
    }

    @Test fun emptyMatchErrorCaseStillCountsAsADeclaration() {
        rejected(flow("match value { when «error» {} when «error» { fail \"replacement\" } }"), "flow.steps[0].errorCase")
    }

    @Test fun globalHandlerCannotBeReplacedEvenWhenInitiallyEmpty() {
        rejected("flow \"x\" { «on» error {} steps {} «on» error { fail \"replacement\" } }", "flow.errorHandler")
    }

    @Test fun mapKeysUseDecodedIdentityRatherThanTokenSpelling() {
        for (keys in listOf("«key»: 1 «\"key\"»: 2", "«'a.b'»: 1 «\"a.b\"»: 2", "«'a\\tb'»: 1 «\"a\tb\"»: 2")) {
            val path = when {
                "a.b" in keys -> "flow.steps[0].params.payload[\"a.b\"]"
                "key" in keys -> "flow.steps[0].params.payload.key"
                else -> "flow.steps[0].params.payload[\"a\\u0009b\"]"
            }
            rejected(flow("m.a s { payload: {$keys} }"), path)
        }
    }

    @Test fun nestedMapsAndListsKeepTheirOwningPath() {
        rejected(flow("m.a s { payload: [{ inner: { «key»: 1 «key»: 2 } }] }"), "flow.steps[0].params.payload[0].inner.key")
        rejected("flow \"x\" { input { x: text default { «key»: 1 «key»: 2 } } steps {} }", "flow.input[0].default.key")
        rejected("flow \"x\" { vars { x: { «key»: 1 «key»: 2 } } steps {} }", "flow.vars[0].value.key")
    }

    @Test fun separateMapsAndStatementScopesDoNotShareOccurrenceState() {
        val document = FlowParser().parse(flow("""
            m.a s { payload: [{key: 1}, {key: 2}] safety: requiresApproval }
            m.a s { payload: {key: 3} safety: onlyIf true }
            if true { m.a s { key: 4 } } else { m.a s { key: 5 } }
        """))
        assertEquals(3, document.flow.steps.size)
        val payload = assertIs<ListLiteralNode>(assertIs<ActionNode>(document.flow.steps[0]).params["payload"])
        assertEquals(2, payload.items.size)
    }

    @Test fun additiveBlocksAndOrderedRuleListsRemainLegal() {
        val document = FlowParser().parse("""
            flow "x" {
                input { a: text } input { b: text }
                vars { a: 1 } vars { b: 2 }
                systems { system "a" { type: one } } systems { system "b" { type: two } }
                steps { transform xs -> ys { select { a: 1 } select {} select { b: 2 } } }
                steps { match x { when true {} when true {} default {} when error {} } }
                steps { m.a s {} -> r { when error {} when error {} } }
            }
        """)
        assertEquals(2, document.flow.input.size)
        assertEquals(2, document.flow.vars.size)
        assertEquals(2, document.flow.systems.size)
        assertEquals(setOf("a", "b"), assertIs<TransformNode>(document.flow.steps[0]).select.keys)
        assertEquals(2, assertIs<MatchNode>(document.flow.steps[1]).cases.size)
        assertEquals(2, assertIs<ActionNode>(document.flow.steps[2]).handler!!.rules.size)
    }

    @Test fun pathsRetainAbsoluteIndicesAcrossAdditiveBlocks() {
        rejected("flow \"x\" { input { a: text } input { b: text «default» null «default» null } steps {} }", "flow.input[1].default")
        rejected("flow \"x\" { steps { skip \"first\" } steps { m.a s { «key»: 1 «key»: 2 } } }", "flow.steps[1].params.key")
        rejected("flow \"x\" { systems { system \"a\" { type: one } } systems { system \"b\" { «type»: one «type»: two } } steps {} }", "flow.systems[1].type")
    }

    @Test fun nestedControlPathsRemainDistinct() {
        val cases = listOf(
            "if true { %s }" to "flow.steps[0].then[0].params.key",
            "if true {} else if false { %s }" to "flow.steps[0].otherwise[0].then[0].params.key",
            "for item in xs { %s }" to "flow.steps[0].body[0].params.key",
            "parallel { branch \"a\" { %s } }" to "flow.steps[0].branches[0].steps[0].params.key",
            "parallel { %s }" to "flow.steps[0].branches[0].steps[0].params.key",
            "match value { default { %s } }" to "flow.steps[0].defaultSteps[0].params.key",
            "match value { when error { %s } }" to "flow.steps[0].errorCase[0].params.key",
            "match value { when true { %s } }" to "flow.steps[0].cases[0].steps[0].params.key",
            "retry { max: 2 } { %s }" to "flow.steps[0].steps[0].params.key",
            "try {} on error { %s }" to "flow.steps[0].errorHandler.steps[0].params.key",
            "m.a s {} -> r { when error { %s } }" to "flow.steps[0].handler.rules[0].steps[0].params.key"
        )
        cases.forEach { (body, path) -> rejected(flow(body.replace("%s", "m.a s { «key»: 1 «key»: 2 }")), path) }
    }

    @Test fun invalidReplacementIsRejectedBeforeItsValueIsParsed() {
        rejected(flow("m.a s { «key»: 1 «key»: }"), "flow.steps[0].params.key")
        rejected(flow("m.a s { «safety»: requiresApproval «safety»: }"), "flow.steps[0].safety")
    }

    @Test fun interpolationReportsOriginalFileCoordinatesWithoutRepositioningAst() {
        rejected("\n\n" + flow("m.a s { payload: \"prefix ${'$'}{{ «key»: 1 «key»: 2 }}\" }"), "flow.steps[0].params.payload.parts[1].key")
        val expression = assertIs<TemplateStringNode>(ExpressionParser.parseSource("\"prefix ${'$'}{value}\""))
        assertEquals(SourceLocation(1, 1), assertIs<ReferenceNode>(expression.parts[1]).location)
    }

    @Test fun decodedEscapesBeforeInterpolationDoNotShiftItsDiagnostic() {
        for (prefix in listOf("\\t", "\\n", "\\\\x", "\\\"", "\\${'$'}{literal}")) {
            rejected(flow("m.a s { payload: \"$prefix${'$'}{{ «key»: 1 «key»: 2 }}\" }"), "flow.steps[0].params.payload.parts[1].key")
        }
        rejected(flow("m.a s { payload: \"${'$'}{{ «key»: 1\\n «key»: 2 }}\" }"), "flow.steps[0].params.payload.parts[0].key")
    }

    @Test fun escapedInterpolationRemainsLiteralEvenWhenItContainsDuplicateLookingText() {
        val value = assertIs<StringLiteralNode>(ExpressionParser.parseSource("\"\\${'$'}{{key: 1 key: 2}}\""))
        assertEquals("${'$'}{{key: 1 key: 2}}", value.value)
    }

    @Test fun callersUsingThePublicLexerAndTokenStreamRetainDiagnosticProvenance() {
        val text = "\"prefix ${'$'}{{key: 1 key: 2}}\""
        val tokens = Lexer(text).tokenize()
        assertEquals(tokens.toList(), tokens)
        val error = assertFailsWith<DuplicateDeclarationException> { ExpressionParser(TokenStream(tokens)).parse() }
        assertEquals(SourceLocation(1, text.indexOf("key") + 1), error.firstOccurrence)
        assertEquals(SourceLocation(1, text.lastIndexOf("key") + 1), error.secondOccurrence)
    }

    @Test fun nestedInterpolationRemapsDiagnosticsThroughBothStringBoundaries() {
        rejected(flow("m.a s { payload: \"${'$'}{'inner ${'$'}{{ «key»: 1 «key»: 2 }}'}\" }"), "flow.steps[0].params.payload.parts[0].parts[1].key")
    }

    @Test fun standaloneExpressionsRejectDuplicateKeysAndKeepSeparateMapsLegal() {
        val error = assertFailsWith<DuplicateDeclarationException> { ExpressionParser.parseSource("{key: 1, 'key': 2}") }
        assertEquals("expression.key", error.path)
        assertEquals(SourceLocation(1, 2), error.firstOccurrence)
        assertEquals(SourceLocation(1, 10), error.secondOccurrence)
        assertIs<ListLiteralNode>(ExpressionParser.parseSource("[{key: 1}, {key: 2}]"))
    }

    @Test fun diagnosticsAreDeterministicAndParserCanBeReusedAfterFailure() {
        val parser = FlowParser()
        val invalid = flow("if true { m.a s { key: 1 key: 2 } }")
        val first = assertFailsWith<DuplicateDeclarationException> { parser.parse(invalid) }
        assertEquals(1, parser.parse(flow("skip \"valid\"")).flow.steps.size)
        val second = assertFailsWith<DuplicateDeclarationException> { parser.parse(invalid) }
        assertEquals(first.message, second.message)
        assertEquals(first.firstOccurrence, second.firstOccurrence)
        assertEquals(first.secondOccurrence, second.secondOccurrence)
    }

    @Test fun flowSourceFrontendRejectsBeforeModuleLookupOrPlanning() {
        var lookups = 0
        val catalog = object : ModuleCatalog {
            override fun findModule(name: String): FlowModule? { lookups++; return null }
            override fun allModules(): Collection<FlowModule> { lookups++; return emptyList() }
        }
        val frontend = FlowSourceFrontend(FrontendCompilerComposition.compiler(catalog))
        val directory = createTempDirectory("source-integrity-").toFile()
        try {
            val file = File(directory, "source.flow").apply { writeText(flow("m.a s { safety: requiresApproval safety: onlyIf true }")) }
            val error = assertFailsWith<DuplicateDeclarationException> { frontend.compile(file) }
            assertEquals("flow.steps[0].safety", error.path)
            assertEquals(0, lookups)
            assertEquals(listOf("source.flow"), directory.list()!!.toList())
        } finally { check(directory.deleteRecursively()) }
    }

    @Test fun syntaxFailuresAreInvalidInputsNotUnexpectedImplementationFailures() {
        assertIs<IllegalArgumentException>(assertFailsWith<ParseException> { FlowParser().parse("flow") })
    }

    @Test fun injectedIntentExpressionSyntaxCannotBypassMapKeyIntegrity() {
        val error = assertFailsWith<DuplicateDeclarationException> { FlowIntentExpressionParser.parse("{key: 1, key: 2}") }
        assertEquals("expression.key", error.path)
    }

    @Test fun mapKeysInsideIndexExpressionsHaveTheIndexOwningPath() {
        rejected(flow("m.a s { payload: xs[{ «key»: 1 «key»: 2 }] }"), "flow.steps[0].params.payload.index.key")
    }

    @Test fun escapedQuotedKeysAndUnicodeKeepOriginalSourceColumns() {
        rejected(flow("m.a s { payload: \"😀 ${'$'}{{ «\\\"key\\\"»: 1 «'key'»: 2 }}\" }"), "flow.steps[0].params.payload.parts[1].key")
    }

    @Test fun standaloneExpressionsCannotHideDuplicateTextAfterAnAcceptedPrefix() {
        assertFailsWith<ParseException> { ExpressionParser.parseSource("null {key: 1 key: 2}") }
        assertFailsWith<ParseException> { FlowIntentExpressionParser.parse("null {key: 1 key: 2}") }
        assertIs<NullLiteralNode>(ExpressionParser.parseSource("null # trailing comment\n"))
    }

    @Test fun interpolationCannotDiscardTrailingAuthoredTokens() {
        assertFailsWith<ParseException> {
            FlowParser().parse(flow("m.a s { payload: \"${'$'}{null {key: 1 key: 2}}\" }"))
        }
    }

    @Test fun longLiteralProvenanceUsesSparseRunsWithoutChangingLogicalPositions() {
        val source = "\"" + "x".repeat(100_000) + "\""
        val tokens = Lexer(source).tokenize() as SourceTokenList
        val locations = assertIs<DecodedStringLocations>(tokens.stringLocations.values.single())
        assertEquals(100_000, locations.size)
        assertEquals(1, locations.runCount)
        assertEquals(SourceLocation(1, 2), locations[0])
        assertEquals(SourceLocation(1, 100_001), locations[99_999])
    }

    private fun flow(steps: String): String = "flow \"x\" { steps { $steps } }"

    private fun rejected(marked: String, path: String): DuplicateDeclarationException {
        val source = StringBuilder()
        val locations = mutableListOf<SourceLocation>()
        for (character in marked) {
            when (character) {
                '«' -> locations += SourceLocation(source.count { it == '\n' } + 1, source.length - source.lastIndexOf("\n"))
                '»' -> Unit
                else -> source.append(character)
            }
        }
        assertEquals(2, locations.size, "Each negative fixture marks both declarations.")
        val error = assertFailsWith<DuplicateDeclarationException>(source.toString()) { FlowParser().parse(source.toString(), "input.flow") }
        assertEquals(DuplicateDeclarationException.CODE, error.code)
        assertEquals(path, error.path)
        assertEquals(locations[0], error.firstOccurrence, source.toString())
        assertEquals(locations[1], error.secondOccurrence, source.toString())
        assertEquals(error.secondOccurrence.line, error.line)
        assertEquals(error.secondOccurrence.column, error.column)
        assertTrue(error.message!!.contains("first declared at ${locations[0].line}:${locations[0].column}"))
        return error
    }
}
