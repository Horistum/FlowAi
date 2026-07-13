import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.flowlang.capabilities.CompatibilityAnalyzer
import org.flowlang.capabilities.TargetCapability
import org.flowlang.generators.manifest.GitHubActionsManifestGenerator
import org.flowlang.generators.manifest.JenkinsManifestGenerator
import org.flowlang.generators.manifest.TargetCapabilityDegradationAnalyzer
import org.flowlang.generators.manifest.TargetCapabilityDegradationStatus
import org.flowlang.generators.manifest.TargetManifestGenerator
import org.flowlang.generators.manifest.TektonManifestGenerator
import org.flowlang.modules.ModuleRegistry
import org.flowlang.parser.FlowParser
import org.flowlang.planner.FlowPlanner
import org.flowlang.safety.StandardEnvironmentSafetyPolicyNotes
import org.flowlang.scenarios.ReferenceAdapterProjectionMatrix
import org.flowlang.scenarios.ReferenceAdapterProjectionOutcome
import org.flowlang.scenarios.ReferencePortabilityClass
import org.flowlang.scenarios.ReferenceScenarioKind
import org.flowlang.scenarios.ReferenceScenarioMatrix
import org.flowlang.validator.FlowValidator
import org.flowlang.validator.SafetyBoundaryValidator

class ReferenceScenarioMatrixTests {
    private val registry = ModuleRegistry.fromDirectory(File("modules"), includeDefaults = true)
    private val parser = FlowParser()
    private val planner = FlowPlanner(registry)
    private val validator = FlowValidator(registry)
    private val safety = SafetyBoundaryValidator(
        registry = registry,
        environmentPolicy = StandardEnvironmentSafetyPolicyNotes.policy()
    )
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
    fun matrixCoversRequiredScenarioKindsAndDeclaresTargetNeutralSemanticMetadata() {
        val scenarios = ReferenceScenarioMatrix.all()
        val kinds = scenarios.map { it.kind }.toSet()

        assertEquals(ReferenceScenarioKind.values().toSet(), kinds, "matrix must cover every required reference scenario kind")
        assertTrue(scenarios.any { it.negativeCoverage }, "matrix must include explicit negative coverage")
        scenarios.forEach { scenario ->
            assertTrue(scenario.id.matches(Regex("[a-z0-9-]+")), "scenario ids must be stable slugs: ${scenario.id}")
            assertTrue(scenario.semanticExpectation.requiredCapabilities.isNotEmpty(), "${scenario.id} must declare semantic capabilities")
            assertTrue(scenario.semanticExpectation.notes.isNotEmpty(), "${scenario.id} must explain its semantic expectation")
            assertTrue(scenario.risks.isNotEmpty(), "${scenario.id} must declare risks")
            assertTrue(scenario.safetyRequirements.isNotEmpty(), "${scenario.id} must declare safety requirements")
            scenario.semanticExpectation.requiredCapabilities.forEach { capability ->
                assertFalse(capability.startsWith("git."), "${scenario.id} must not use implementation-specific capability '$capability'")
                assertFalse(capability.startsWith("shell."), "${scenario.id} must not use implementation-specific capability '$capability'")
                assertFalse(capability.startsWith("kubernetes."), "${scenario.id} must not use implementation-specific capability '$capability'")
                assertFalse(capability.startsWith("database."), "${scenario.id} must not use implementation-specific capability '$capability'")
                assertFalse(capability.startsWith("notify."), "${scenario.id} must not use implementation-specific capability '$capability'")
                assertFalse(capability.startsWith("rest."), "${scenario.id} must not use implementation-specific capability '$capability'")
                assertFalse(capability.startsWith("standard."), "${scenario.id} must not use implementation-specific capability '$capability'")
            }
        }
        assertTrue(
            scenarios.any { it.semanticExpectation.portabilityClass == ReferencePortabilityClass.ADAPTER_REQUIRED },
            "matrix must explicitly represent adapter-required universal semantics"
        )
    }

    @Test
    fun positiveScenariosPassTargetNeutralCorePipelineBeforeAdapterProjection() {
        ReferenceScenarioMatrix.positiveScenarios().forEach { scenario ->
            val ast = parser.parse(scenario.source)
            val validation = validator.validate(ast)
            assertTrue(validation.valid, "${scenario.id} must pass Flow validation: ${validation.issues}")
            val safetyIssues = safety.validate(ast)
            assertTrue(safetyIssues.none { it.level == "error" }, "${scenario.id} must pass safety validation: $safetyIssues")

            val plan = planner.plan(ast)
            assertTrue(plan.nodes.isNotEmpty(), "${scenario.id} must produce execution plan nodes")
        }
    }

    @Test
    fun adapterProjectionMatrixIsSeparateFromTargetNeutralScenarioSemantics() {
        val scenarios = ReferenceScenarioMatrix.all()
        val expectations = ReferenceAdapterProjectionMatrix.all()
        val scenarioIds = scenarios.map { it.id }.toSet()

        assertEquals(scenarioIds, expectations.map { it.scenarioId }.toSet(), "adapter matrix must cover every reference scenario")
        scenarioIds.forEach { scenarioId ->
            val actualTargets = ReferenceAdapterProjectionMatrix.forScenario(scenarioId).map { it.target }.toSet()
            assertEquals(ReferenceAdapterProjectionMatrix.supportedTargets, actualTargets, "$scenarioId must declare every supported adapter target")
        }
        expectations.forEach { expectation ->
            assertTrue(expectation.rationale.isNotBlank(), "${expectation.scenarioId}/${expectation.target} must explain adapter expectation")
        }
    }

    @Test
    fun positiveAdapterExpectationsRemainProjectableWithoutBecomingCoreSemantics() {
        ReferenceScenarioMatrix.positiveScenarios().forEach { scenario ->
            val ast = parser.parse(scenario.source)
            val plan = planner.plan(ast)
            ReferenceAdapterProjectionMatrix.forScenario(scenario.id).forEach { expectation ->
                val generator = generators.getValue(expectation.target)
                val compatibility = CompatibilityAnalyzer(targets).analyze(plan, expectation.target)
                val manifest = generator.generate(plan, compatibility)
                assertEquals(expectation.target, manifest.target, "${scenario.id}/${expectation.target} manifest must retain target identity")
                assertTrue(manifest.jobs.isNotEmpty(), "${scenario.id}/${expectation.target} must generate at least one target job")
                val degradation = TargetCapabilityDegradationAnalyzer.analyze(manifest)
                assertTrue(
                    expectation.outcome.accepts(degradation.status),
                    "${scenario.id}/${expectation.target} expected ${expectation.outcome} but got ${degradation.status}: ${degradation.entries}"
                )
            }
        }
    }

    @Test
    fun negativeCoverageIsExplicitAndRejectedByCoreValidationGates() {
        ReferenceScenarioMatrix.negativeScenarios().forEach { scenario ->
            val ast = parser.parse(scenario.source)
            val validation = validator.validate(ast)
            val safetyIssues = safety.validate(ast)
            val allIssues = validation.issues + safetyIssues
            val errorCodes = allIssues.filter { it.level == "error" }.map { it.code }.toSet()

            assertFalse(errorCodes.isEmpty(), "negative scenario ${scenario.id} must be rejected by at least one core validation gate")
            assertTrue(
                scenario.expectedDiagnosticCodes.any { it in errorCodes },
                "negative scenario ${scenario.id} must produce at least one expected diagnostic from ${scenario.expectedDiagnosticCodes}, got $errorCodes"
            )
            ReferenceAdapterProjectionMatrix.forScenario(scenario.id).forEach { expectation ->
                assertEquals(ReferenceAdapterProjectionOutcome.BLOCKED, expectation.outcome, "negative scenario ${scenario.id} must declare blocked adapter outcomes")
            }
        }
    }

    private fun ReferenceAdapterProjectionOutcome.accepts(status: TargetCapabilityDegradationStatus): Boolean = when (this) {
        ReferenceAdapterProjectionOutcome.SUPPORTED -> status == TargetCapabilityDegradationStatus.SUPPORTED
        ReferenceAdapterProjectionOutcome.REVIEW_REQUIRED -> status in setOf(
            TargetCapabilityDegradationStatus.SUPPORTED,
            TargetCapabilityDegradationStatus.DEGRADED,
            TargetCapabilityDegradationStatus.BLOCKED
        )
        ReferenceAdapterProjectionOutcome.BLOCKED -> status == TargetCapabilityDegradationStatus.BLOCKED
    }
}
