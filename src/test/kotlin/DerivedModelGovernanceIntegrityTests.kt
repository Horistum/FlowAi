import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.flowlang.adapters.yaml.IntentYamlLoader
import org.flowlang.adapters.yaml.TargetRegistryYamlLoader
import org.flowlang.architecture.ArchitectureGovernanceAnalyzer
import org.flowlang.architecture.ArchitectureGovernanceIntegrityAuthority
import org.flowlang.architecture.GovernedArchitectureAnalyzer
import org.flowlang.architecture.InvalidArchitectureGovernanceException
import org.flowlang.capabilities.CompatibilityAnalyzer
import org.flowlang.capabilities.DerivedModelIntegrityAuthority
import org.flowlang.capabilities.InvalidDerivedModelException
import org.flowlang.capabilities.TargetDecisionTraceAnalyzer
import org.flowlang.capabilities.TargetSelectionAnalyzer
import org.flowlang.generators.manifest.TargetCompatibilityReadinessAnalyzer
import org.flowlang.intent.IntentCapabilityValidator
import org.flowlang.intent.IntentToAstPlanner
import org.flowlang.modules.ModuleRegistry
import org.flowlang.planner.ExecutionPlan
import org.flowlang.planner.FlowPlanner
import org.flowlang.targets.builtin.BuiltInTargetProjections

class DerivedModelGovernanceIntegrityTests {
    private val registry = ModuleRegistry.fromDirectory(File("modules"), includeDefaults = true)
    private val targets = TargetRegistryYamlLoader.loadDirectory(File("targets"))

    private fun referencePlan(): ExecutionPlan =
        IntentYamlLoader.load(File("examples/intent/build-test-deploy.intent.yaml"))
            .also { IntentCapabilityValidator(registry).validate(it).assertValid() }
            .let { IntentToAstPlanner(registry).plan(it) }
            .let { FlowPlanner(registry).plan(it) }

    @Test
    fun preliminaryAndReconciledDerivedModelsPassOneAuthority() {
        val plan = referencePlan()
        val preliminaryNegotiation = CompatibilityAnalyzer(targets).negotiate(plan)
        val preliminarySelection = TargetSelectionAnalyzer(targets).analyze(plan)
        val pipeline = BuiltInTargetProjections.pipeline(targets)
        val manifests = listOf(
            pipeline.generate(testMaterializationRequest(plan, "jenkins", targets)),
            pipeline.generateDiagnosticEvidence(testDiagnosticMaterializationRequest(plan, "github-actions", targets)),
            pipeline.generateDiagnosticEvidence(testDiagnosticMaterializationRequest(plan, "tekton", targets))
        )

        DerivedModelIntegrityAuthority.requireNegotiation(preliminaryNegotiation)
        DerivedModelIntegrityAuthority.requireSelection(preliminarySelection)

        val negotiation = TargetCompatibilityReadinessAnalyzer.reconcile(preliminaryNegotiation, manifests)
        val selection = TargetCompatibilityReadinessAnalyzer.reconcile(preliminarySelection, manifests)

        DerivedModelIntegrityAuthority.requireNegotiation(negotiation)
        DerivedModelIntegrityAuthority.requireSelection(selection)
        val trace = TargetDecisionTraceAnalyzer(targets).analyze(
            plan = plan,
            requestedTarget = "jenkins",
            strict = false,
            negotiation = negotiation,
            selection = selection
        )

        assertEquals(selection.recommendedTarget, trace.recommendedTarget)
        assertEquals(selection.recommendedTarget.isNotBlank(), trace.generationAllowed)
    }

    @Test
    fun selectionSummaryCannotDivergeFromCandidates() {
        val selection = TargetSelectionAnalyzer(targets).analyze(referencePlan())
        val movedTarget = selection.degradedTargets.first()
        val corrupted = selection.copy(
            degradedTargets = selection.degradedTargets - movedTarget,
            blockedTargets = (selection.blockedTargets + movedTarget).sorted()
        )

        val failure = assertFailsWith<InvalidDerivedModelException> {
            DerivedModelIntegrityAuthority.requireSelection(corrupted)
        }

        assertTrue(failure.issues.any { it.code == "DERIVED_SELECTION_DEGRADED_TARGETS_MISMATCH" })
        assertTrue(failure.issues.any { it.code == "DERIVED_SELECTION_BLOCKED_TARGETS_MISMATCH" })
    }

    @Test
    fun capabilityOnlyNegotiationCannotForgeRecommendation() {
        val negotiation = CompatibilityAnalyzer(targets).negotiate(referencePlan())
        val candidate = negotiation.targets.first { it.status != org.flowlang.capabilities.SupportLevel.UNSUPPORTED }.target
        val corrupted = negotiation.copy(recommendedTargets = listOf(candidate))

        val failure = assertFailsWith<InvalidDerivedModelException> {
            DerivedModelIntegrityAuthority.requireNegotiation(corrupted)
        }

        assertTrue(failure.issues.any { it.code == "DERIVED_NEGOTIATION_RECOMMENDATION_WITHOUT_EVIDENCE" })
    }

    @Test
    fun decisionTraceRejectsArtifactsFromAnotherPlan() {
        val plan = referencePlan()
        val negotiation = CompatibilityAnalyzer(targets).negotiate(plan)
        val selection = TargetSelectionAnalyzer(targets).analyze(plan).copy(flowName = "other-flow")

        val failure = assertFailsWith<InvalidDerivedModelException> {
            TargetDecisionTraceAnalyzer(targets).analyze(
                plan = plan,
                requestedTarget = "jenkins",
                strict = false,
                negotiation = negotiation,
                selection = selection
            )
        }

        assertTrue(failure.issues.any { it.code == "DERIVED_TRACE_SELECTION_FLOW_MISMATCH" })
    }

    @Test
    fun duplicateManifestEvidenceCannotBeSilentlyOverwritten() {
        val plan = referencePlan()
        val selection = TargetSelectionAnalyzer(targets).analyze(plan)
        val manifest = BuiltInTargetProjections.pipeline(targets).generate(testMaterializationRequest(plan, "jenkins", targets))

        val failure = assertFailsWith<IllegalArgumentException> {
            TargetCompatibilityReadinessAnalyzer.reconcile(selection, listOf(manifest, manifest))
        }

        assertTrue(failure.message.orEmpty().contains("duplicate targets"))
    }

    @Test
    fun readinessAwareSelectionCannotBeUsedAsPreliminaryEvidenceAgain() {
        val plan = referencePlan()
        val pipeline = BuiltInTargetProjections.pipeline(targets)
        val manifests = listOf(pipeline.generate(testMaterializationRequest(plan, "jenkins", targets)))
        val reconciled = TargetCompatibilityReadinessAnalyzer.reconcile(
            TargetSelectionAnalyzer(targets).analyze(plan),
            manifests
        )

        val failure = assertFailsWith<IllegalArgumentException> {
            TargetCompatibilityReadinessAnalyzer.reconcile(reconciled, manifests)
        }

        assertTrue(failure.message.orEmpty().contains("already readiness-aware"))
    }

    @Test
    fun repositoryGovernanceCatalogAndDerivedReportAreConsistent() {
        val report = GovernedArchitectureAnalyzer(File(".")).analyze()

        assertEquals("PASS", report.status)
        assertEquals(report.driftScore.negativeSignals.sumOf { it.score }, report.driftScore.finalScore)
    }

    @Test
    fun governanceCatalogRejectsMissingSignalsPositiveWeightsAndWeakenedThreshold() {
        val root = Files.createTempDirectory("flow-governance-integrity").toFile()
        try {
            writeDriftCatalog(
                root = root,
                minimumScore = -100,
                runtimeScore = 3,
                omitSignal = "silent-semantic-fallback"
            )
            val report = ArchitectureGovernanceAnalyzer(root).analyze()
            val integrity = ArchitectureGovernanceIntegrityAuthority.analyze(root, report)

            assertEquals("FAIL", integrity.status)
            assertTrue(integrity.issues.any { it.code == "GOVERNANCE_DRIFT_MINIMUM_WEAKENED" })
            assertTrue(integrity.issues.any { it.code == "GOVERNANCE_NEGATIVE_SIGNAL_SCORE_NON_NEGATIVE" })
            assertTrue(integrity.issues.any { it.code == "GOVERNANCE_NEGATIVE_SIGNAL_MISSING" })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun freeTextAdrPhraseCannotWaiveUnrelatedDrift() {
        val root = Files.createTempDirectory("flow-governance-exception").toFile()
        try {
            writeDriftCatalog(root)
            val forbidden = File(root, "standard/architecture/forbidden-directions.yaml")
            forbidden.parentFile.mkdirs()
            forbidden.writeText(
                """
                version: 1.0
                forbiddenDirections:
                  - id: runtime-executor
                    forbiddenTerms:
                      - TaskExecutor
                """.trimIndent()
            )
            val source = File(root, "src/main/kotlin/org/flowlang/example/RuntimeEscape.kt")
            source.parentFile.mkdirs()
            source.writeText(
                """
                package org.flowlang.example
                class RuntimeEscape { val executor = TaskExecutor() }
                """.trimIndent()
            )
            val adr = File(root, "docs/adr/ADR-999-free-text.md")
            adr.parentFile.mkdirs()
            adr.writeText(
                """
                # Exception
                This text mentions a Drift Score exception and a conformance guardrail without declaring either structurally.
                """.trimIndent()
            )

            val report = ArchitectureGovernanceAnalyzer(root).analyze()
            assertTrue(report.driftScore.exceptionRecorded)
            val failure = assertFailsWith<InvalidArchitectureGovernanceException> {
                ArchitectureGovernanceIntegrityAuthority.requireValid(root, report)
            }

            assertTrue(failure.integrity.issues.any { it.code == "GOVERNANCE_DRIFT_EXCEPTION_UNSCOPED" })
        } finally {
            root.deleteRecursively()
        }
    }

    private fun writeDriftCatalog(
        root: File,
        minimumScore: Int = 0,
        runtimeScore: Int = -3,
        omitSignal: String? = null
    ) {
        val negative = listOf(
            "runtime-direction" to runtimeScore,
            "sdk-direction" to -3,
            "target-specific-standard" to -2,
            "report-without-validation-purpose" to -2,
            "silent-semantic-fallback" to -4
        ).filterNot { it.first == omitSignal }
        val file = File(root, "standard/architecture/drift-score.yaml")
        file.parentFile.mkdirs()
        file.writeText(buildString {
            appendLine("version: 1.3")
            appendLine("purpose: Test architecture drift policy.")
            appendLine("minimumScore: $minimumScore")
            appendLine("scoringMode: negative-signal-only")
            appendLine("baselineSignals:")
            listOf(
                "semantic-correctness",
                "safety-validation",
                "conformance-coverage",
                "artifact-stability",
                "portability-explanation"
            ).forEach { id ->
                appendLine("  - id: $id")
                appendLine("    description: Test baseline signal $id.")
            }
            appendLine("negativeSignals:")
            negative.forEach { (id, score) ->
                appendLine("  - id: $id")
                appendLine("    score: $score")
                appendLine("    description: Test negative signal $id.")
            }
            appendLine("rule: Negative evidence must lower the score.")
        })
    }
}
