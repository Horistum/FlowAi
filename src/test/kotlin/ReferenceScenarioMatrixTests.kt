import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.flowlang.capabilities.CompatibilityAnalyzer
import org.flowlang.capabilities.TargetCapability
import org.flowlang.generators.manifest.GitHubActionsManifestGenerator
import org.flowlang.generators.manifest.JenkinsManifestGenerator
import org.flowlang.generators.manifest.TargetCapabilityDegradationAnalyzer
import org.flowlang.generators.manifest.TargetCapabilityDegradationStatus
import org.flowlang.generators.manifest.TargetManifestContractValidator
import org.flowlang.generators.manifest.TargetManifestGenerator
import org.flowlang.generators.manifest.TektonManifestGenerator
import org.flowlang.modules.ModuleRegistry
import org.flowlang.parser.FlowParser
import org.flowlang.planner.FlowPlanner
import org.flowlang.scenarios.ReferenceScenarioKind
import org.flowlang.scenarios.ReferenceScenarioMatrix
import org.flowlang.scenarios.ReferenceTargetOutcome
import org.flowlang.validator.FlowValidator
import org.flowlang.validator.SafetyBoundaryValidator

class ReferenceScenarioMatrixTests {
    private val registry = ModuleRegistry.fromDirectory(File("modules"), includeDefaults = true)
    private val parser = FlowParser()
    private val planner = FlowPlanner(registry)
    private val validator = FlowValidator(registry)
    private val safety = SafetyBoundaryValidator(enforceProductionBoundary = true)
    private val targets = mapOf(
        "jenkins" to TargetCapability(target = "jenkins", description = "test"),
        "github-actions" to TargetCapability(target = "github-actions", description = "test"),
        "tekton" to TargetCapability(target = "tekton", description = "test")
    )
    private val generators: Map<String, TargetManifestGenerator> = mapOf(
        "jenkins" to JenkinsManifestGenerator(),
        "github-actions" to GitHubActionsManifestGenerator(),
        "tekton" to TektonManifestGenerator()
    )

    @Test
    fun matrixCoversRequiredScenarioKindsAndDeclaresReviewMetadata() {
        val scenarios = ReferenceScenarioMatrix.all()
        val kinds = scenarios.map { it.kind }.toSet()

        assertEquals(ReferenceScenarioKind.values().toSet(), kinds, "matrix must cover every required reference scenario kind")
        assertTrue(scenarios.any { it.negativeCoverage }, "matrix must include explicit negative coverage")
        scenarios.forEach { scenario ->
            assertTrue(scenario.id.matches(Regex("[a-z0-9-]+")), "scenario ids must be stable slugs: ${scenario.id}")
            assertTrue(scenario.expectedCapabilities.isNotEmpty(), "${scenario.id} must declare expected capabilities")
            assertTrue(scenario.risks.isNotEmpty(), "${scenario.id} must declare risks")
            assertTrue(scenario.safetyRequirements.isNotEmpty(), "${scenario.id} must declare safety requirements")
            assertEquals(generators.keys, scenario.targetExpectations.keys, "${scenario.id} must declare every main target outcome")
            scenario.targetExpectations.forEach { (target, expectation) ->
                assertTrue(expectation.rationale.isNotBlank(), "${scenario.id}/$target must explain the target expectation")
            }
        }
    }

    @Test
    fun positiveScenariosPassParserValidatorPlannerCompatibilityAndManifestGeneration() {
        ReferenceScenarioMatrix.positiveScenarios().forEach { scenario ->
            val ast = parser.parse(scenario.source)
            val validation = validator.validate(ast)
            assertTrue(validation.valid, "${scenario.id} must pass Flow validation: ${validation.issues}")
            val safetyIssues = safety.validate(ast)
            assertTrue(safetyIssues.none { it.level == "error" }, "${scenario.id} must pass safety validation: $safetyIssues")

            val plan = planner.plan(ast)
            assertTrue(plan.nodes.isNotEmpty(), "${scenario.id} must produce execution plan nodes")

            generators.forEach { (target, generator) ->
                val compatibility = CompatibilityAnalyzer(targets).analyze(plan, target)
                val manifest = generator.generate(plan, compatibility)
                val manifestReport = TargetManifestContractValidator.validate(manifest)
                assertTrue(manifestReport.valid, "${scenario.id}/$target manifest must satisfy projection contract: ${manifestReport.issues}")
                assertTrue(manifest.jobs.isNotEmpty(), "${scenario.id}/$target must generate at least one target job")

                val degradation = TargetCapabilityDegradationAnalyzer.analyze(manifest)
                val expected = assertNotNull(scenario.targetExpectations[target], "${scenario.id} must declare $target")
                assertTrue(
                    expected.outcome.accepts(degradation.status),
                    "${scenario.id}/$target expected ${expected.outcome} but got ${degradation.status}: ${degradation.entries}"
                )
                if (expected.outcome == ReferenceTargetOutcome.REVIEW_REQUIRED) {
                    assertTrue(
                        degradation.entries.isNotEmpty() || degradation.status == TargetCapabilityDegradationStatus.SUPPORTED,
                        "${scenario.id}/$target review-required projection must produce an explicit report state"
                    )
                }
            }
        }
    }

    @Test
    fun negativeCoverageIsExplicitAndRejectedBySafetyBoundary() {
        ReferenceScenarioMatrix.negativeScenarios().forEach { scenario ->
            val ast = parser.parse(scenario.source)
            val validation = validator.validate(ast)
            assertTrue(validation.valid, "negative scenario ${scenario.id} must still be syntactically and structurally valid Flow: ${validation.issues}")

            val issues = safety.validate(ast)
            assertFalse(issues.none { it.level == "error" }, "negative scenario ${scenario.id} must be rejected by safety validation")
            val codes = issues.map { it.code }.toSet()
            scenario.expectedDiagnosticCodes.forEach { expectedCode ->
                assertTrue(expectedCode in codes, "negative scenario ${scenario.id} must produce $expectedCode, got $codes")
            }
            scenario.targetExpectations.values.forEach { expectation ->
                assertEquals(ReferenceTargetOutcome.BLOCKED, expectation.outcome, "negative scenario ${scenario.id} must declare blocked target outcomes")
            }
        }
    }

    private fun ReferenceTargetOutcome.accepts(actual: TargetCapabilityDegradationStatus): Boolean = when (this) {
        ReferenceTargetOutcome.SUPPORTED -> actual == TargetCapabilityDegradationStatus.SUPPORTED
        ReferenceTargetOutcome.REVIEW_REQUIRED -> actual in setOf(
            TargetCapabilityDegradationStatus.SUPPORTED,
            TargetCapabilityDegradationStatus.DEGRADED,
            TargetCapabilityDegradationStatus.BLOCKED
        )
        ReferenceTargetOutcome.BLOCKED -> actual == TargetCapabilityDegradationStatus.BLOCKED
    }
}
