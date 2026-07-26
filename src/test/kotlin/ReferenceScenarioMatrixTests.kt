import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.flowlang.adapters.yaml.IntentYamlLoader
import org.flowlang.adapters.yaml.TargetRegistryYamlLoader
import org.flowlang.capabilities.CompatibilityAnalyzer
import org.flowlang.controls.ControlDecisionStatus
import org.flowlang.targets.builtin.BuiltInTargetProjections
import org.flowlang.intent.IntentCapabilityValidator
import org.flowlang.intent.IntentToAstPlanner
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

    @Test
    fun matrixCoversRequiredScenarioKindsAndDeclaresTargetNeutralSemanticMetadata() {
        val scenarios = ReferenceScenarioMatrix.all()
        val kinds = scenarios.map { it.kind }.toSet()

        assertEquals(ReferenceScenarioKind.values().toSet(), kinds)
        assertTrue(scenarios.any { it.negativeCoverage })
        scenarios.forEach { scenario ->
            assertTrue(scenario.id.matches(Regex("[a-z0-9-]+")))
            assertTrue(scenario.semanticExpectation.requiredCapabilities.isNotEmpty())
            assertTrue(scenario.semanticExpectation.notes.isNotEmpty())
            assertTrue(scenario.risks.isNotEmpty())
            assertTrue(scenario.safetyRequirements.isNotEmpty())
            assertFalse(scenario.source.contains("shell.run"), "${scenario.id} must not use shell.run as active reference behavior")
            scenario.semanticExpectation.requiredCapabilities.forEach { capability ->
                assertFalse(capability.startsWith("git."), "${scenario.id} uses implementation-specific capability '$capability'")
                assertFalse(capability.startsWith("shell."), "${scenario.id} uses implementation-specific capability '$capability'")
                assertFalse(capability.startsWith("kubernetes."), "${scenario.id} uses implementation-specific capability '$capability'")
                assertFalse(capability.startsWith("database."), "${scenario.id} uses implementation-specific capability '$capability'")
                assertFalse(capability.startsWith("notify."), "${scenario.id} uses implementation-specific capability '$capability'")
                assertFalse(capability.startsWith("rest."), "${scenario.id} uses implementation-specific capability '$capability'")
                assertFalse(capability.startsWith("standard."), "${scenario.id} uses implementation-specific capability '$capability'")
                assertFalse(capability == "command.run", "${scenario.id} uses command execution as universal meaning")
            }
        }
        assertTrue(scenarios.any { it.semanticExpectation.portabilityClass == ReferencePortabilityClass.ADAPTER_REQUIRED })
    }

    @Test
    fun flagshipBuildTestDeployUsesSemanticTestIntentInsteadOfShellExecution() {
        val scenario = ReferenceScenarioMatrix.positiveScenarios().single { it.id == "build-test-deploy" }
        assertTrue("software.test" in scenario.semanticExpectation.requiredCapabilities)
        assertFalse("command.run" in scenario.semanticExpectation.requiredCapabilities)
        assertFalse(scenario.source.contains("shell.run"))
        assertTrue(scenario.source.contains("standard.execute standard"))
        assertTrue(scenario.source.contains("capability: \"software.test\""))
    }

    @Test
    fun positiveScenariosPassTargetNeutralCorePipelineBeforeAdapterProjection() {
        ReferenceScenarioMatrix.positiveScenarios().forEach { scenario ->
            val ast = parser.parse(scenario.source)
            val validation = validator.validate(ast)
            assertTrue(validation.valid, "${scenario.id}: ${validation.issues}")
            val safetyIssues = safety.validate(ast)
            assertTrue(safetyIssues.none { it.level == "error" }, "${scenario.id}: $safetyIssues")
            assertTrue(planner.plan(ast).nodes.isNotEmpty())
        }
    }

    @Test
    fun targetOutcomesAreDerivedFromConcreteEvidenceWithoutBlockedManifestGeneration() {
        ReferenceScenarioMatrix.positiveScenarios().forEach { scenario ->
            val plan = planner.plan(parser.parse(scenario.source))
            val coreBlocked = plan.controlDecision.status != ControlDecisionStatus.ALLOWED
            ReferenceAdapterProjectionMatrix.supportedTargets.sorted().forEach { target ->
                val compatibility = CompatibilityAnalyzer(targets).analyze(plan, target)
                val expectation = when {
                    coreBlocked -> ReferenceAdapterProjectionMatrix.evaluate(
                        scenario = scenario,
                        target = target,
                        coreBlocked = true,
                        compatibility = compatibility,
                        manifest = null
                    )
                    compatibility.hasErrors -> ReferenceAdapterProjectionMatrix.evaluate(
                        scenario = scenario,
                        target = target,
                        coreBlocked = false,
                        compatibility = compatibility,
                        manifest = null
                    )
                    else -> {
                        val manifest = BuiltInTargetProjections.pipeline(targets).generate(testMaterializationRequest(plan, target, targets))
                        ReferenceAdapterProjectionMatrix.evaluate(
                            scenario = scenario,
                            target = target,
                            coreBlocked = false,
                            compatibility = compatibility,
                            manifest = manifest
                        )
                    }
                }

                assertTrue(expectation.rationale.isNotBlank())
                assertEquals(expectation.outcome == ReferenceAdapterProjectionOutcome.EXECUTABLE, expectation.executable)
                if (coreBlocked || compatibility.hasErrors) {
                    assertEquals(ReferenceAdapterProjectionOutcome.FAIL_FAST, expectation.outcome)
                }
            }
        }
    }

    @Test
    fun realisticBuildTestDeployEvidenceIsReviewOnlyForJenkinsAndFailFastWithoutWorkspaceContinuity() {
        val scenario = ReferenceScenarioMatrix.positiveScenarios().single { it.id == "build-test-deploy" }
        val intent = IntentYamlLoader.load(File("examples/intent/build-test-deploy.intent.yaml"))
        IntentCapabilityValidator(registry).validate(intent).assertValid()
        val ast = IntentToAstPlanner(registry).plan(intent)
        val validation = validator.validate(ast)
        assertTrue(validation.valid, validation.issues.toString())
        val safetyIssues = safety.validate(ast)
        assertTrue(safetyIssues.none { it.level == "error" }, safetyIssues.toString())
        val plan = planner.plan(ast)
        assertEquals(ControlDecisionStatus.ALLOWED, plan.controlDecision.status)
        val outcomes = ReferenceAdapterProjectionMatrix.supportedTargets.associateWith { target ->
            val compatibility = CompatibilityAnalyzer(targets).analyze(plan, target)
            if (compatibility.hasErrors) {
                ReferenceAdapterProjectionMatrix.evaluate(scenario, target, coreBlocked = false, compatibility = compatibility)
            } else {
                val manifest = BuiltInTargetProjections.pipeline(targets).generate(testMaterializationRequest(plan, target, targets))
                ReferenceAdapterProjectionMatrix.evaluate(scenario, target, coreBlocked = false, compatibility = compatibility, manifest = manifest)
            }
        }

        assertEquals(ReferenceAdapterProjectionOutcome.REVIEW_ONLY, outcomes.getValue("jenkins").outcome)
        assertEquals(ReferenceAdapterProjectionOutcome.FAIL_FAST, outcomes.getValue("github-actions").outcome)
        assertEquals(ReferenceAdapterProjectionOutcome.FAIL_FAST, outcomes.getValue("tekton").outcome)
        assertTrue(outcomes.values.none { it.executable })
    }

    @Test
    fun negativeCoverageFailsBeforeTargetProjection() {
        ReferenceScenarioMatrix.negativeScenarios().forEach { scenario ->
            val ast = parser.parse(scenario.source)
            val issues = validator.validate(ast).issues + safety.validate(ast)
            val errorCodes = issues.filter { it.level == "error" }.map { it.code }.toSet()
            assertFalse(errorCodes.isEmpty())
            assertTrue(scenario.expectedDiagnosticCodes.any { it in errorCodes })
            ReferenceAdapterProjectionMatrix.supportedTargets.forEach { target ->
                val expectation = ReferenceAdapterProjectionMatrix.evaluate(
                    scenario = scenario,
                    target = target,
                    coreBlocked = true
                )
                assertEquals(ReferenceAdapterProjectionOutcome.FAIL_FAST, expectation.outcome)
                assertFalse(expectation.executable)
            }
        }
    }
}
