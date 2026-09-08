import org.flowlang.frontend.FrontendCompilerComposition
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.flowlang.architecture.ArchitectureGovernanceAnalyzer
import org.flowlang.ast.BooleanLiteralNode
import org.flowlang.ast.FlowDocument
import org.flowlang.ast.FlowNode
import org.flowlang.ast.IfNode
import org.flowlang.ast.StatementNode
import org.flowlang.conformance.SemanticEquivalenceConcretePair
import org.flowlang.conformance.SemanticEquivalenceTargetBacking
import org.flowlang.conformance.SemanticEquivalenceTargetBackingKind
import org.flowlang.parser.ExpressionParser
import org.flowlang.parser.FlowParser
import org.flowlang.parser.LexException
import org.flowlang.parser.Lexer
import org.flowlang.parser.ParseException
import org.flowlang.serialization.FlowYaml
import org.flowlang.serialization.FlowYamlException
import org.flowlang.validator.SafetyBoundaryValidator

class CritiqueFailClosedRegressionTests {
    @Test
    fun sharedMapYamlBoundaryRejectsDuplicateKeysAtEveryDepth() {
        listOf(
            "key: first\nkey: second",
            "root:\n  key: first\n  key: second"
        ).forEachIndexed { index, yaml ->
            val failure = assertFailsWith<FlowYamlException> {
                FlowYaml.readMap(yaml, "duplicate-$index.yaml")
            }
            assertTrue(failure.message.orEmpty().contains("duplicate-$index.yaml"))
        }
    }

    @Test
    fun lexerRejectsASecondDecimalPointWithSourceLocation() {
        listOf("1.2.3", "3.14.15.9").forEach { source ->
            val failure = assertFailsWith<LexException> { Lexer(source).tokenize() }
            assertTrue(failure.message.orEmpty().contains("more than one decimal point"), source)
            assertEquals(1, failure.line)
            assertTrue(failure.column > 1)
        }
    }

    @Test
    fun deeplyNestedStatementsFailWithParseDiagnosticInsteadOfStackOverflow() {
        val depth = org.flowlang.frontend.testing.FrontendTestFixtures.statementNestingDepth + 2
        val source = buildString {
            append("flow \"deep\" { steps { ")
            repeat(depth) { append("if true { ") }
            append("skip \"done\" ")
            repeat(depth) { append("} ") }
            append("} }")
        }

        val failure = assertFailsWith<ParseException> { FlowParser().parse(source, "deep.flow") }
        assertTrue(failure.message.orEmpty().contains("statement nesting exceeds maximum depth"))
        assertTrue(failure.line > 0)
        assertTrue(failure.column > 0)
    }

    @Test
    fun deeplyNestedExpressionsFailWithParseDiagnosticInsteadOfStackOverflow() {
        val depth = org.flowlang.frontend.testing.FrontendTestFixtures.expressionNestingDepth + 2
        val source = "(".repeat(depth) + "true" + ")".repeat(depth)

        val failure = assertFailsWith<ParseException> { ExpressionParser.parseSource(source) }
        assertTrue(failure.message.orEmpty().contains("expression nesting exceeds maximum depth"))
    }

    @Test
    fun safetyValidatorRejectsManuallyConstructedAstBeyondDepthLimit() {
        var statements: List<StatementNode> = emptyList()
        repeat(org.flowlang.compiler.testing.CompilerTestFixtures.safetyStatementNestingDepth + 2) {
            statements = listOf(IfNode(condition = BooleanLiteralNode(value = true), then = statements))
        }
        val document = FlowDocument(flow = FlowNode(name = "deep", steps = statements))

        val issues = FrontendCompilerComposition.safetyValidator().validate(document)

        assertTrue(issues.any { it.code == "SAFETY_NESTING_DEPTH_EXCEEDED" })
    }

    @Test
    fun driftScoreCannotPassWithoutAUsableNegativeSignalCatalog() {
        val roots = listOf(
            Files.createTempDirectory("flow-drift-missing").toFile(),
            Files.createTempDirectory("flow-drift-empty").toFile().also { root ->
                val file = File(root, "standard/architecture/drift-score.yaml")
                file.parentFile.mkdirs()
                file.writeText(
                    """
                    version: 1.3
                    minimumScore: 0
                    scoringMode: negative-signal-only
                    baselineSignals: []
                    negativeSignals: []
                    """.trimIndent()
                )
            }
        )
        try {
            roots.forEach { root ->
                val score = ArchitectureGovernanceAnalyzer(root).analyze().driftScore
                assertEquals("FAIL", score.status)
                assertTrue(score.negativeSignals.any { it.id == "drift-score-configuration-invalid" })
            }
        } finally {
            roots.forEach(File::deleteRecursively)
        }
    }

    @Test
    fun concretePairRequiresOneExplicitBackingPerTarget() {
        val failure = assertFailsWith<IllegalArgumentException> {
            SemanticEquivalenceConcretePair(
                id = "missing-backing",
                scenarioId = "scenario",
                intent = "intent.yaml",
                leftTarget = "target-a",
                leftSnapshot = "left.json",
                rightTarget = "target-b",
                rightSnapshot = "right.json",
                targetBackings = listOf(
                    SemanticEquivalenceTargetBacking(
                        "target-a",
                        SemanticEquivalenceTargetBackingKind.REFERENCE_SNAPSHOT
                    )
                )
            )
        }
        assertTrue(failure.message.orEmpty().contains("exactly one backing for each implementation target"))
    }

    @Test
    fun concretePairCannotUseTheSameTargetTwice() {
        val failure = assertFailsWith<IllegalArgumentException> {
            SemanticEquivalenceConcretePair(
                id = "same-target",
                scenarioId = "scenario",
                intent = "intent.yaml",
                leftTarget = "target-a",
                leftSnapshot = "left.json",
                rightTarget = "target-a",
                rightSnapshot = "right.json",
                targetBackings = listOf(
                    SemanticEquivalenceTargetBacking("target-a", SemanticEquivalenceTargetBackingKind.REFERENCE_SNAPSHOT)
                )
            )
        }
        assertTrue(failure.message.orEmpty().contains("two different implementation targets"))
    }
}
