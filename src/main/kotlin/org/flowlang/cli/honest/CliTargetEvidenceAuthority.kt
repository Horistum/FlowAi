package org.flowlang.cli.honest

import java.io.File
import org.flowlang.adapters.continuity.AdapterContinuitySatisfactionAuthority
import org.flowlang.adapters.continuity.UnresolvedAdapterContinuitySatisfactionException
import org.flowlang.adapters.control.AdapterControlMaterializationAuthority
import org.flowlang.adapters.control.UnresolvedAdapterControlMaterializationException
import org.flowlang.adapters.contract.AdapterDiagnosticIssue
import org.flowlang.adapters.contract.AdapterDiagnosticSeverity
import org.flowlang.adapters.contract.AdapterDiagnosticsReport
import org.flowlang.adapters.contract.TargetAdapterContractAnalyzer
import org.flowlang.adapters.contract.TargetAdapterContractReport
import org.flowlang.adapters.rendering.AdapterArtifactEvidenceReceipt
import org.flowlang.adapters.rendering.AdapterRenderedArtifactKind
import org.flowlang.adapters.trigger.AdapterTriggerAuthorizedRenderingAuthority
import org.flowlang.adapters.trigger.AdapterTriggerMaterializationAuthority
import org.flowlang.adapters.trigger.UnresolvedAdapterTriggerMaterializationException
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
import org.flowlang.compiler.CompilationUnit
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
import org.flowlang.materialization.ExplicitTargetSelection
import org.flowlang.materialization.TargetDiagnosticMaterializationRequest
import org.flowlang.materialization.TargetMaterializationRequest
import org.flowlang.materialization.TargetSelectionEvidenceReport
import org.flowlang.targets.builtin.BuiltInTargetProjections

data class CliRenderedArtifact(
    val fileName: String,
    val content: String,
    val kind: AdapterRenderedArtifactKind,
    val mediaType: String,
    val sha256: String,
    val evidenceFileName: String,
    val evidence: AdapterArtifactEvidenceReceipt
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
    val targetSelection: TargetSelectionEvidenceReport,
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
 * Produces one coherent CLI evidence set from one graph-authorized compilation
 * or one explicitly inventoried compatibility-plan fixture.
 *
 * Product callers must use the [CompilationUnit] overload so source-bound graph
 * authorization survives all the way to manifest projection. The plan overload
 * remains for conformance and mutation fixtures that have no authored source
 * envelope; the manifest pipeline still performs its graph validation, digest
 * and parity gate before a provider sees those plans.
 */
class CliTargetEvidenceAuthority(
    private val targets: Map<String, TargetCapability>,
    private val projections: TargetProjectionRegistry = BuiltInTargetProjections.registry,
    rootDir: File = File(".")
) {
    private val pipeline = TargetManifestGenerationPipeline(targets, projections)
    private val controlAuthority = AdapterControlMaterializationAuthority(rootDir, targets, projections)
    private val continuityAuthority = AdapterContinuitySatisfactionAuthority(rootDir, targets, projections)
    private val triggerAuthority = AdapterTriggerMaterializationAuthority(rootDir, targets, projections)
    private val renderingAuthority = AdapterTriggerAuthorizedRenderingAuthority(rootDir, projections)

    fun evaluate(
        compilation: CompilationUnit,
        explicitSelection: ExplicitTargetSelection,
        strict: Boolean,
        renderRequested: Boolean
    ): CliTargetEvidence {
        val materialization = TargetMaterializationRequest.fromCompilation(
            compilation,
            explicitSelection,
            strict
        )
        val diagnostic = TargetDiagnosticMaterializationRequest.fromCompilation(
            compilation,
            explicitSelection
        )
        return evaluate(
            plan = compilation.executionPlan,
            explicitSelection = explicitSelection,
            strict = strict,
            renderRequested = renderRequested,
            materializationRequest = { materialization },
            diagnosticRequest = { diagnostic }
        )
    }

    fun evaluate(
        plan: ExecutionPlan,
        explicitSelection: ExplicitTargetSelection,
        strict: Boolean,
        renderRequested: Boolean
    ): CliTargetEvidence = evaluate(
        plan = plan,
        explicitSelection = explicitSelection,
        strict = strict,
        renderRequested = renderRequested,
        materializationRequest = {
            TargetMaterializationRequest.fromCompatibilityPlan(
                plan = plan,
                selection = explicitSelection,
                strict = strict,
                evidenceId = explicitSelection.evidence.source
            )
        },
        diagnosticRequest = {
            TargetDiagnosticMaterializationRequest.fromCompatibilityPlan(
                plan = plan,
                selection = explicitSelection,
                evidenceId = explicitSelection.evidence.source
            )
        }
    )

    private fun evaluate(
        plan: ExecutionPlan,
        explicitSelection: ExplicitTargetSelection,
        strict: Boolean,
        renderRequested: Boolean,
        materializationRequest: () -> TargetMaterializationRequest,
        diagnosticRequest: () -> TargetDiagnosticMaterializationRequest
    ): CliTargetEvidence {
        val target = explicitSelection.target
        require(target in targets) {
            "Selected target '$target' is no longer present in the active target registry."
        }
        val materialization = lazy(LazyThreadSafetyMode.NONE) { materializationRequest() }

        val compatibilityAnalyzer = CompatibilityAnalyzer(targets)
        val preliminaryNegotiation = compatibilityAnalyzer.negotiate(plan, strict = strict)
        val preliminaryReadiness = ExecutionReadinessAnalyzer(targets).analyze(plan, target, strict = strict)
        val preliminarySelection = TargetSelectionAnalyzer(targets).analyze(plan, strict = strict)

        val diagnostics = mutableListOf<CliTargetDiagnostic>()
        var fallbackUsed = false
        val manifest = try {
            val controlAssessment = controlAuthority.requireMatched(
                materialization.value.authorization,
                target
            )
            val continuityAssessment = continuityAuthority.requireMatched(plan, target)
            val triggerAssessment = triggerAuthority.requireMatched(plan, target)
            val generated = pipeline.generate(materialization.value)
            val withControls = controlAuthority.reconcileDiagnostic(generated, controlAssessment)
            val withContinuity = continuityAuthority.reconcileDiagnostic(withControls, continuityAssessment)
            triggerAuthority.reconcileDiagnostic(withContinuity, triggerAssessment)
        } catch (failure: RuntimeException) {
            if (!failure.isExpectedTargetBlocker()) throw failure
            fallbackUsed = true
            diagnostics += CliTargetDiagnostic(
                code = "CLI_TARGET_DIAGNOSTIC_FALLBACK",
                severity = "warning",
                message = failure.message ?: "Target materialization is not executable; diagnostic evidence was generated.",
                causeType = failure::class.simpleName
            )
            val diagnostic = pipeline.generateDiagnosticEvidence(diagnosticRequest())
            val controlAssessment = when (failure) {
                is UnresolvedAdapterControlMaterializationException -> failure.assessment
                    else -> controlAuthority.assess(materialization.value.authorization, target)
                }
            val continuityAssessment = when (failure) {
                is UnresolvedAdapterContinuitySatisfactionException -> failure.assessment
                else -> continuityAuthority.assess(plan, target)
            }
            val triggerAssessment = when (failure) {
                is UnresolvedAdapterTriggerMaterializationException -> failure.assessment
                else -> triggerAuthority.assess(plan, target)
            }
            val withControls = controlAuthority.reconcileDiagnostic(diagnostic, controlAssessment)
            val withContinuity = continuityAuthority.reconcileDiagnostic(withControls, continuityAssessment)
            triggerAuthority.reconcileDiagnostic(withContinuity, triggerAssessment)
        }

        val manifests = listOf(manifest)
        val readiness = TargetCompatibilityReadinessAnalyzer.reconcile(preliminaryReadiness, manifest)
        val negotiation = TargetCompatibilityReadinessAnalyzer.reconcile(preliminaryNegotiation, manifests)
        val selectionReport = TargetCompatibilityReadinessAnalyzer.reconcile(preliminarySelection, manifests)
        val decisionTrace = TargetDecisionTraceAnalyzer(targets).analyze(
            plan = plan,
            requestedTarget = target,
            strict = strict,
            negotiation = negotiation,
            selection = selectionReport
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
        val rendered = when {
            !renderRequested -> null
            outcome == CliTargetEvidenceOutcome.BLOCKED -> {
                diagnostics += CliTargetDiagnostic(
                    code = "CLI_RENDER_NOT_AUTHORIZED",
                    severity = "error",
                    message = "Target output was requested but manifest evidence is blocked; no artifact was emitted."
                )
                null
            }
            else -> {
                if (outcome == CliTargetEvidenceOutcome.REVIEW_ONLY) {
                    diagnostics += CliTargetDiagnostic(
                        code = "CLI_RENDER_NOT_AUTHORIZED",
                        severity = "error",
                        message = "Executable target syntax is not authorized; a dedicated non-executable review artifact was emitted instead."
                    )
                }
                renderingAuthority.render(manifest).let { bundle ->
                    CliRenderedArtifact(
                        fileName = bundle.artifact.fileName,
                        content = bundle.artifact.content,
                        kind = bundle.artifact.kind,
                        mediaType = bundle.artifact.mediaType,
                        sha256 = bundle.artifact.sha256,
                        evidenceFileName = bundle.evidenceFileName,
                        evidence = bundle.receipt
                    )
                }
            }
        }

        return CliTargetEvidence(
            outcome = outcome,
            targetSelection = TargetSelectionEvidenceReport.from(plan, explicitSelection),
            diagnosticFallbackUsed = fallbackUsed,
            diagnostics = diagnostics,
            compatibility = manifest.compatibility,
            negotiation = negotiation,
            readiness = readiness,
            selection = selectionReport,
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
            this is UnresolvedPlanningControlException ||
            this is UnresolvedAdapterControlMaterializationException ||
            this is UnresolvedAdapterContinuitySatisfactionException ||
            this is UnresolvedAdapterTriggerMaterializationException

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
