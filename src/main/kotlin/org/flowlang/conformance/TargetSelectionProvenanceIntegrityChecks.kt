package org.flowlang.conformance

import org.flowlang.materialization.CompatibilityMaterializationBoundary
import java.io.File
import org.flowlang.cli.honest.CliDiagnosticCode
import org.flowlang.cli.honest.CliExecutionDiagnostic
import org.flowlang.cli.honest.CliExecutionResult
import org.flowlang.cli.honest.CliPresentation
import org.flowlang.cli.honest.CliProcessExit
import org.flowlang.cli.honest.CliTargetEvidenceOutcome
import org.flowlang.cli.honest.executeCli
import org.flowlang.generators.manifest.TargetProjectionRegistry
import org.flowlang.materialization.TargetSelectionAuthority
import org.flowlang.materialization.TargetSelectionOrigin
import org.flowlang.materialization.UnsupportedExplicitConfigurationSourceException
import org.flowlang.modules.ModuleRegistry

/**
 * Closure-blocking proof that target provenance and CLI process status remain
 * derived from typed authorities rather than compatibility reports or free ints.
 */
internal class TargetSelectionProvenanceIntegrityChecks(
    rootDir: File,
    registry: ModuleRegistry,
    targets: Map<String, org.flowlang.capabilities.TargetCapability>,
    projections: TargetProjectionRegistry
) : ConformanceCheckSupport(rootDir, registry, targets, projections) {
    fun checks(): List<ConformanceCheck> = listOf(
        runCheck("governance.target-selection-provenance-cli-status-integrity") {
            checkSingleProjectionPipeline()
            checkClosedOriginVocabulary()
            checkTypedConfigurationSources()
            checkDerivedCliProcessStatus()
        }
    )

    private fun checkSingleProjectionPipeline() {
        val loader = Thread.currentThread().contextClassLoader
        require(loader.getResource("org/flowlang/cli/TargetManifestGenerationPipeline.class") == null) {
            "The removed CLI target-manifest composition facade remains on the compiled classpath."
        }
        require(loader.getResource("org/flowlang/generators/manifest/TargetManifestGenerationPipeline.class") != null) {
            "The canonical target-manifest generation pipeline is missing from the compiled classpath."
        }
    }

    private fun checkClosedOriginVocabulary() {
        require(
            TargetSelectionOrigin.entries.toSet() == setOf(
                TargetSelectionOrigin.CLI_OPTION,
                TargetSelectionOrigin.INTENT_DECLARATION,
                TargetSelectionOrigin.EXPLICIT_CONFIGURATION
            )
        ) {
            "Target-selection origin vocabulary changed without an explicit architecture decision: ${TargetSelectionOrigin.entries}."
        }
    }

    @Suppress("DEPRECATION")
    private fun checkTypedConfigurationSources() {
        val target = targets.keys.sorted().first()
        val reference = TargetSelectionAuthority.fromReferenceSnapshot(
            value = target,
            scenarioId = "provenance-integrity",
            targets = targets
        )
        require(reference.evidence.origin == TargetSelectionOrigin.EXPLICIT_CONFIGURATION) {
            "Reference snapshot selection did not retain EXPLICIT_CONFIGURATION origin."
        }
        require(reference.evidence.source == "reference-snapshot:provenance-integrity") {
            "Reference snapshot selection did not derive its source from the typed scenario id."
        }

        val rejected = runCatching {
            CompatibilityMaterializationBoundary.legacySelection(
                value = target,
                source = "cli:compatibility-report",
                targets = targets
            )
        }.exceptionOrNull()
        require(rejected is UnsupportedExplicitConfigurationSourceException) {
            "An arbitrary CLI-derived label was accepted as explicit configuration provenance."
        }
    }

    private fun checkDerivedCliProcessStatus() {
        val source = File(rootDir, "examples/intent/build-test-deploy.intent.yaml").path

        val reviewResult = executeCli(arrayOf("intent", source, "--target", "jenkins"))
        require(reviewResult is CliExecutionResult.Targeted) {
            "Reference CLI target evaluation did not return a targeted result."
        }
        require(reviewResult.evidence.outcome == CliTargetEvidenceOutcome.REVIEW_ONLY) {
            "Reference CLI target evidence is no longer REVIEW_ONLY; the process-status fixture must be reconsidered."
        }
        require(reviewResult.exitCode == CliProcessExit.SUCCESS.code) {
            "Review evidence without a gate request must remain observable without reporting process failure."
        }

        val reviewGate = executeCli(arrayOf("intent", source, "--target", "jenkins", "--render"))
        require(reviewGate is CliExecutionResult.Targeted) {
            "Render-gated reference CLI evaluation did not return a targeted result."
        }
        require(reviewGate.evidence.outcome == CliTargetEvidenceOutcome.REVIEW_ONLY) {
            "Render-gated reference evidence is no longer REVIEW_ONLY; the process-status fixture must be reconsidered."
        }
        require(reviewGate.exitCode == CliProcessExit.REVIEW_REQUIRED.code) {
            "A requested render with review-only evidence must return REVIEW_REQUIRED."
        }

        val blocked = reviewResult.copy(
            evidence = reviewResult.evidence.copy(outcome = CliTargetEvidenceOutcome.BLOCKED),
            strict = false,
            renderRequested = false
        )
        require(blocked.exitCode == CliProcessExit.BLOCKED.code) {
            "A typed BLOCKED target result returned a successful or review-only process status."
        }

        val completed = CliExecutionResult.Completed(CliPresentation())
        require(completed.exitCode == CliProcessExit.SUCCESS.code) {
            "Completed CLI result did not derive the success process status."
        }

        val integrityRejected = CliExecutionResult.Rejected(
            command = "integrity",
            diagnostic = CliExecutionDiagnostic(
                CliDiagnosticCode.INTEGRITY_BLOCKED,
                "Integrity authority blocked the command."
            ),
            presentation = CliPresentation()
        )
        require(integrityRejected.exitCode == CliProcessExit.BLOCKED.code) {
            "Integrity rejection did not derive the blocked process status."
        }

        val internalRejected = CliExecutionResult.Rejected(
            command = "internal",
            diagnostic = CliExecutionDiagnostic(
                CliDiagnosticCode.INTERNAL_ERROR,
                "Unexpected internal failure."
            ),
            presentation = CliPresentation()
        )
        require(internalRejected.exitCode == CliProcessExit.INTERNAL_ERROR.code) {
            "Internal rejection did not derive the internal-error process status."
        }
    }
}
