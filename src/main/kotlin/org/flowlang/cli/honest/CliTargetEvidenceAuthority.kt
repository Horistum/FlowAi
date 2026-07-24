package org.flowlang.cli.honest

import org.flowlang.adapters.contract.AdapterDiagnosticIssue
import org.flowlang.adapters.contract.AdapterDiagnosticSeverity
import org.flowlang.adapters.contract.AdapterDiagnosticsReport
import org.flowlang.adapters.contract.TargetAdapterContractAnalyzer
import org.flowlang.adapters.contract.TargetAdapterContractReport
import org.flowlang.capabilities.CompatibilityAnalyzer
import org.flowlang.capabilities.CompatibilityReport
import org.flowlang.capabilities.ExecutionReadinessAnalyzer
import org.flowlang.capabilities.ExecutionReadinessReport
import org.flowlang.capabilities.ExecutionReadinessStatus
import org.flowlang.capabilities.PlannerCapabilityConstraintViolation
import org.flowlang.capabilities.TargetCapability
import org.flowlang.capabilities.TargetCapabilityNegotiationReport
import org.flowlang.capabilities.TargetDecisionTraceAnalyzer
import org.flowlang.capabilities.TargetDecisionTraceReport
import org.flowlang.capabilities.TargetSelectionAnalyzer
import org.flowlang.capabilities.TargetSelectionReport
import org.flowlang.generators.manifest.TargetCompatibilityReadinessAnalyzer
import org.flowlang.generators.manifest.TargetManifest
import org.flowlang.generators.manifest.TargetManifestGenerationPipeline
import org.flowlang.generators.manifest.TargetProjectionRegistry
import org.flowlang.generators.manifest.TargetRenderMode
import org.flowlang.generators.manifest.TargetRenderPolicy
import org.flowlang.generators.manifest.TargetRenderReadiness
import org.flowlang.generators.manifest.UnresolvedExecutionTopologyException
import org.flowlang.generators.manifest.UnresolvedPlanningContinuityException
import org.flowlang.generators.manifest.UnresolvedPlanningControlException
import org.flowlang.planner.ExecutionPlan
import org.flowlang.targets.builtin.BuiltInTargetProjections

data class CliRenderedArtifact(
    val fileName: String,
    val content: String
)

enum class CliTargetEvidenceOutcome {
    EXECUTABLE,
    REVIEW_ONLY,
    BLOCKED
}

data class CliTargetDiagnostic(
    val code: String,
    val severity: String,
    val message: String,
    val causeType: String? = null
)

data class CliTargetEvidence(
    val outcome: CliTargetEvidenceOutcome,
    val diagnosticFallbackUsed: Boolean,
    val diagnostics: List<CliTargetDiagnostic>,
    val compatibility: CompatibilityReport,
    val negotiation: TargetCapabilityNegotiationReport,
    val readiness: ExecutionReadinessReport,
    val selection: TargetSelectionReport,
    val decisionTrace: TargetDecisionTraceReport,
    val adapterContract: TargetAdapterContractReport,
    val manifest: TargetManifest,
    val renderReadiness: TargetRenderReadiness,
    val renderedArtifact: CliRenderedArtifact?
)

/**
 * Produces one coherent CLI evidence set from one plan and one concrete manifest.
 *
 * Expected target incompatibility is represented as diagnostic manifest evidence,
 * not an exception. Structurally invalid or internally inconsistent plans still
 * fail, because diagnostic fallback must not become a bypass around materialization
 * integrity.
 */
class CliTargetEvidenceAuthority(
    private val targets: Map<String, TargetCapability>,
    private val projections: TargetProjectionRegistry = BuiltInTargetProjections.registry
) {
    private val pipeline = TargetManifestGenerationPipeline(targets, projections)

    fun evaluate(
        plan: ExecutionPlan,
        target: String,
        strict: Boolean,
        renderRequested: Boolean
    ): CliTargetEvidence {
        require(target in targets) {
            "Unknown target '$target'. Available targets: ${targets.keys.sorted().joinToString()}."
        }

        val compatibilityAnalyzer = CompatibilityAnalyzer(targets)
        val preliminaryNegotiation = compatibilityAnalyzer.negotiate(plan, strict = strict)
        val preliminaryReadiness = ExecutionReadinessAnalyzer(targets).analyze(plan, target, strict = strict)
        val preliminarySelection = TargetSelectionAnalyzer(targets).analyze(plan, strict = strict)

        val diagnostics = mutableListOf<CliTargetDiagnostic>()
        var fallbackUsed = false
        val manifest = try {
            pipeline.generate(plan, target, strict = strict)
        } catch (failure: RuntimeException) {
            if (!failure.isExpectedTargetBlocker()) throw failure
            fallbackUsed = true
            diagnostics += CliTargetDiagnostic(
                code = "CLI_TARGET_DIAGNOSTIC_FALLBACK",
                severity = "warning",
                message = failure.message ?: "Target materialization is not executable; diagnostic evidence was generated.",
                causeType = failure::class.simpleName
            )
            pipeline.generateDiagnosticEvidence(plan, target)
        }

        val manifests = listOf(manifest)
        val readiness = TargetCompatibilityReadinessAnalyzer.reconcile(preliminaryReadiness, manifest)
        val negotiation = TargetCompatibilityReadinessAnalyzer.reconcile(preliminaryNegotiation, manifests)
        val selection = TargetCompatibilityReadinessAnalyzer.reconcile(preliminarySelection, manifests)
        val decisionTrace = TargetDecisionTraceAnalyzer(targets).analyze(
            plan = plan,
            requestedTarget = target,
            strict = strict,
            negotiation = negotiation,
            selection = selection
        )
        val adapterContract = reconcileAdapterContract(
            TargetAdapterContractAnalyzer(targets).analyze(plan, target, strict = strict),
            readiness
        )
        val renderReadiness = TargetRenderPolicy.evaluate(manifest)
        val outcome = when (renderReadiness.mode) {
            TargetRenderMode.EXECUTABLE -> CliTargetEvidenceOutcome.EXECUTABLE
            TargetRenderMode.REVIEW_ONLY -> CliTargetEvidenceOutcome.REVIEW_ONLY
            TargetRenderMode.FAIL_FAST -> CliTargetEvidenceOutcome.BLOCKED
        }
        val rendered = if (renderRequested && outcome == CliTargetEvidenceOutcome.EXECUTABLE) {
            val provider = projections.requireProvider(target)
            CliRenderedArtifact(provider.artifactFileName, provider.render(manifest))
        } else {
            if (renderRequested) {
                diagnostics += CliTargetDiagnostic(
                    code = "CLI_RENDER_NOT_AUTHORIZED",
                    severity = "error",
                    message = "Target output was requested but manifest evidence is ${outcome.name.lowercase()}; review evidence remains available and no target syntax was emitted."
                )
            }
            null
        }

        return CliTargetEvidence(
            outcome = outcome,
            diagnosticFallbackUsed = fallbackUsed,
            diagnostics = diagnostics,
            compatibility = manifest.compatibility,
            negotiation = negotiation,
            readiness = readiness,
            selection = selection,
            decisionTrace = decisionTrace,
            adapterContract = adapterContract,
            manifest = manifest,
            renderReadiness = renderReadiness,
            renderedArtifact = rendered
        )
    }

    private fun RuntimeException.isExpectedTargetBlocker(): Boolean =
        this is PlannerCapabilityConstraintViolation ||
            this is UnresolvedExecutionTopologyException ||
            this is UnresolvedPlanningContinuityException ||
            this is UnresolvedPlanningControlException

    private fun reconcileAdapterContract(
        preliminary: TargetAdapterContractReport,
        readiness: ExecutionReadinessReport
    ): TargetAdapterContractReport {
        require(preliminary.flowName == readiness.flowName)
        require(preliminary.target == readiness.target)
        require(preliminary.planVersion == readiness.planVersion)
        require(preliminary.strict == readiness.strict)

        val diagnostics = when {
            readiness.readiness == ExecutionReadinessStatus.READY && readiness.productionReady && readiness.executable ->
                AdapterDiagnosticsReport(
                    flowName = readiness.flowName,
                    target = readiness.target,
                    status = readiness.readiness,
                    generationAllowed = readiness.generationAllowed,
                    issues = listOf(AdapterDiagnosticIssue(
                        code = "ADAPTER_CONTRACT_READY",
                        severity = AdapterDiagnosticSeverity.INFO,
                        artifact = "target-adapter-contract.json",
                        message = "Concrete manifest evidence permits executable target rendering."
                    ))
                )
            readiness.generationAllowed -> AdapterDiagnosticsReport(
                flowName = readiness.flowName,
                target = readiness.target,
                status = readiness.readiness,
                generationAllowed = true,
                issues = listOf(AdapterDiagnosticIssue(
                    code = "ADAPTER_CONTRACT_DEGRADED",
                    severity = AdapterDiagnosticSeverity.WARNING,
                    artifact = "execution-readiness-report.json",
                    message = "Concrete manifest evidence is review-only; target syntax must not be emitted."
                ))
            )
            else -> AdapterDiagnosticsReport(
                flowName = readiness.flowName,
                target = readiness.target,
                status = readiness.readiness,
                generationAllowed = false,
                issues = listOf(AdapterDiagnosticIssue(
                    code = "ADAPTER_CONTRACT_BLOCKED",
                    severity = AdapterDiagnosticSeverity.ERROR,
                    artifact = "execution-readiness-report.json",
                    message = "Concrete readiness blocks target manifest or rendered output use."
                ))
            )
        }

        return preliminary.copy(
            generationAllowed = readiness.generationAllowed,
            productionReady = readiness.productionReady && readiness.executable,
            expectedOutputArtifacts = preliminary.expectedOutputArtifacts.map { artifact ->
                if (artifact.name == "target-manifest.json") {
                    artifact.copy(required = readiness.generationAllowed)
                } else {
                    artifact
                }
            },
            diagnostics = diagnostics
        )
    }
}