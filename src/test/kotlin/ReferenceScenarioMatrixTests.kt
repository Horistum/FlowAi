import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.flowlang.adapters.yaml.TargetRegistryYamlLoader
import org.flowlang.capabilities.CompatibilityAnalyzer
import org.flowlang.generators.manifest.GitHubActionsManifestGenerator
import org.flowlang.generators.manifest.JenkinsManifestGenerator
import org.flowlang.generators.manifest.TargetCapabilityDegradationAnalyzer
import org.flowlang.generators.manifest.TargetCapabilityDegradationStatus
import org.flowlang.generators.manifest.TargetCompatibilityReadinessAnalyzer
import org.flowlang.generators.manifest.TargetManifestGenerator
import org.flowlang.generators.manifest.TargetRenderMode
import org.flowlang.generators.manifest.TargetRenderPolicy
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
    private val targets = TargetRegistryYamlLoader.loadDirectory(File("targets"))
        .filterKeys { it in ReferenceAdapterProjectionMatrix.supportedTargets }
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
    fun flagshipBuildTestDeployUsesSemanticTestIntentInsteadOfShellExecution() {
        val scenario = ReferenceScenarioMatrix.positiveScenarios().single { it.id == "build-test-deploy" }

        assertTrue("software.test" in scenario.semanticExpectation.requiredCapabilities)
        assertFalse("command.run" in scenario.semanticExpectation.requiredCapabilities)
        assertFalse(scenario.source.contains("shell.run"))
        assertFalse(scenario.source.contains("use module \"shell\""))
        assertFalse(scenario.source.contains("type: shell"))
        assertTrue(scenario.source.contains("standard.execute standard"))
        assertTrue(scenario.source.contains("capability: \"software.test\""))
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
            assertEquals(
                expectation.outcome == ReferenceAdapterProjectionOutcome.EXECUTABLE,
                expectation.executable,
                "${expectation.scenarioId}/${expectation.target} executable flag must match its exact outcome"
            )
        }
    }

    @Test
    fun positiveAdapterExpectationsMatchExactReviewOnlyEvidence() {
        ReferenceScenarioMatrix.positiveScenarios().forEach { scenario ->
            val ast = parser.parse(scenario.source)
            val plan = planner.plan(ast)
            ReferenceAdapterProjectionMatrix.forScenario(scenario.id).forEach { expectation ->
                val generator = generators.getValue(expectation.target)
                val compatibility = CompatibilityAnalyzer(targets).analyze(plan, expectation.target)
                val manifest = generator.generate(plan, compatibility)
                val render = TargetRenderPolicy.evaluate(manifest)
                val readiness = TargetCompatibilityReadinessAnalyzer.analyze(manifest)
                val degradation = TargetCapabilityDegradationAnalyzer.analyze(manifest)

                assertEquals(expectation.target, manifest.target, "${scenario.id}/${expectation.target} manifest must retain target identity")
                assertTrue(manifest.jobs.isNotEmpty(), "${scenario.id}/${expectation.target} must generate at least one target job")
                assertTrue(readiness.evidenceAvailable, "${scenario.id}/${expectation.target} must expose concrete readiness evidence")

                when (expectation.outcome) {
                    ReferenceAdapterProjectionOutcome.EXECUTABLE -> {
                        assertEquals(TargetRenderMode.EXECUTABLE, render.mode)
                        assertTrue(render.executable)
                        assertEquals(TargetCapabilityDegradationStatus.SUPPORTED, degradation.status)
                    }
                    ReferenceAdapterProjectionOutcome.REVIEW_ONLY -> {
                        assertEquals(TargetRenderMode.REVIEW_ONLY, render.mode, "${scenario.id}/${expectation.target} must remain review-only")
                        assertFalse(render.executable, "${scenario.id}/${expectation.target} must be explicitly non-executable")
                        assertEquals(
                            TargetCapabilityDegradationStatus.DEGRADED,
                            degradation.status,
                            "${scenario.id}/${expectation.target} review-only evidence must be degraded, not blocked or supported"
                        )
                    }
                    ReferenceAdapterProjectionOutcome.BLOCKED -> error("Positive scenario ${scenario.id} must not declare BLOCKED adapter expectation.")
                }
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
                assertFalse(expectation.executable)
            }
        }
    }
}
