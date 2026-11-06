package org.flowlang.cli.honest

import java.io.File
import org.flowlang.adapters.rendering.AdapterRenderedArtifactKind
import org.flowlang.adapters.yaml.TargetRegistryYamlLoader
import org.flowlang.ai.normalization.ClarificationSeverity
import org.flowlang.ai.normalization.RiskSeverity
import org.flowlang.compiler.CompilationResult
import org.flowlang.compiler.CompilationSource
import org.flowlang.distribution.reference.ReferenceTargetProjections
import org.flowlang.frontend.FrontendCompilerComposition
import org.flowlang.frontend.ai.ExternalAiProposalReader
import org.flowlang.frontend.ai.ProposalInputException
import org.flowlang.frontend.ai.ReviewedAiProposalFrontend
import org.flowlang.materialization.TargetSelectionAuthority
import org.flowlang.materialization.TargetSelectionDecision
import org.flowlang.modules.ModuleRegistry
import org.flowlang.safety.StandardEnvironmentSafetyPolicyNotes
import org.flowlang.standard.StandardDiagnosticCatalog

data class ProposalDiagnostic(val code: String, val severity: String, val field: String, val message: String)
data class ProposalReviewItem(val field: String, val severity: String)

/** Versioned host feedback. Authorship and fidelity are deliberately separate from compilation. */
data class ProposalReviewReport(
    val formatVersion: Int = 1,
    val outcome: CliProcessExit,
    val stage: String,
    val semanticAcceptance: String,
    val inputSource: CompilationSource?,
    val compilationSource: CompilationSource? = null,
    val graphDigest: String? = null,
    val providerId: String? = null,
    val providerAttribution: String = "caller-declared",
    val requestFidelity: String = "unassessed",
    val reviewItems: List<ProposalReviewItem> = emptyList(),
    val diagnostics: List<ProposalDiagnostic> = emptyList(),
    val target: String? = null,
    val targetDisposition: String = "not-requested",
    val renderRequested: Boolean = false,
    val renderAuthorized: Boolean = false,
    val artifacts: List<String> = emptyList()
) {
    val exitCode: Int get() = outcome.code
}

class ProposalCliResult internal constructor(
    val review: ProposalReviewReport,
    override val presentation: CliPresentation,
    override val artifacts: List<CliArtifact>
) : CliExecutionResult {
    override val exitCode: Int get() = review.exitCode
    override val diagnostics: List<CliExecutionDiagnostic> = when (review.outcome) {
        CliProcessExit.INVALID_INPUT -> listOf(CliExecutionDiagnostic(CliDiagnosticCode.INVALID_INPUT, "Inspect proposal-review.json for input diagnostics."))
        CliProcessExit.BLOCKED -> listOf(CliExecutionDiagnostic(CliDiagnosticCode.INTEGRITY_BLOCKED, "Inspect proposal-review.json for blocking diagnostics."))
        CliProcessExit.INTERNAL_ERROR -> listOf(CliExecutionDiagnostic(CliDiagnosticCode.INTERNAL_ERROR, "Proposal processing failed internally."))
        else -> emptyList()
    }
}

internal object ProposalCommand {
    fun execute(args: CliArguments, output: CliOutputCollector, contractRoot: File): CliExecutionResult {
        val directory = args.value(CliValueOption.OUT)?.let(::File)
        val render = args.has(CliFlagOption.RENDER)
        val input = try {
            ExternalAiProposalReader.read(File(args.positionals.single()))
        } catch (failure: ProposalInputException) {
            return publish(ProposalReviewReport(
                outcome = CliProcessExit.INVALID_INPUT, stage = "INPUT", semanticAcceptance = "not-assessed",
                inputSource = failure.inputSource, renderRequested = render,
                diagnostics = listOf(diagnostic(failure.code, failure.field))
            ), linkedMapOf(), directory, output)
        }
        val proposal = input.proposal
        val providerReport = proposal.response.report
        val items = providerReport.openQuestions.mapIndexed { i, q ->
            ProposalReviewItem("$.response.report.openQuestions[$i]", q.severity.name)
        } + providerReport.risks.mapIndexed { i, risk ->
            ProposalReviewItem("$.response.report.risks[$i]", if (risk.mitigated) "MITIGATED" else risk.severity.name)
        } + providerReport.missingDecisions.mapIndexed { i, _ ->
            ProposalReviewItem("$.response.report.missingDecisions[$i]", "REQUIRED")
        }
        val reviewRequired = providerReport.openQuestions.any { it.severity == ClarificationSeverity.REQUIRED } ||
            providerReport.risks.any { it.severity == RiskSeverity.HIGH && !it.mitigated } || providerReport.missingDecisions.isNotEmpty()
        val requestedTarget = proposal.request.context.target
        val cliTarget = args.value(CliValueOption.TARGET)
        val target = cliTarget ?: requestedTarget
        val base = ProposalReviewReport(
            outcome = CliProcessExit.SUCCESS, stage = "INPUT", semanticAcceptance = "not-assessed",
            inputSource = input.inputSource, providerId = proposal.providerId, reviewItems = items,
            target = target, renderRequested = render
        )
        fun invalid(code: String, field: String) = publish(base.copy(
            outcome = CliProcessExit.INVALID_INPUT, diagnostics = listOf(diagnostic(code, field)),
            targetDisposition = "invalid-selection"
        ), linkedMapOf(), directory, output)
        if (requestedTarget != null && cliTarget != null && requestedTarget != cliTarget)
            return invalid("PROPOSAL_TARGET_CONFLICT", "$.request.context.target")
        if (render && target == null) return invalid("CLI_TARGET_REQUIRED_FOR_RENDER", "$.request.context.target")
        val targets = TargetRegistryYamlLoader.loadDirectory(File(contractRoot, "targets")) { file ->
            "contract:${file.relativeTo(contractRoot).invariantSeparatorsPath}"
        }
        if (target != null && target !in targets) return invalid("PROPOSAL_TARGET_UNKNOWN", "$.request.context.target")
        val selection = if (cliTarget != null) TargetSelectionAuthority.fromCliOption(cliTarget, targets)
            else if (requestedTarget != null) TargetSelectionDecision.Selected(
                TargetSelectionAuthority.fromIntentDeclaration(requestedTarget, input.inputSource.identity + "#request.context.target", targets)
            ) else TargetSelectionDecision.NotSelected
        val compiler = FrontendCompilerComposition.compiler(
            ModuleRegistry.fromDirectory(File(contractRoot, "modules")), StandardEnvironmentSafetyPolicyNotes.policy(contractRoot)
        )
        val compiled = ReviewedAiProposalFrontend(compiler).compile(proposal)
        if (compiled is CompilationResult.Rejected) {
            val rejected = compiled.rejection
            return publish(base.copy(
                outcome = CliProcessExit.BLOCKED, stage = rejected.stage.name, semanticAcceptance = "rejected",
                compilationSource = rejected.source, targetDisposition = "withheld",
                diagnostics = rejected.diagnostics.map { diagnostic(it.code, "$.response.normalizedIntent", it.severity.name) }
            ), linkedMapOf(), directory, output)
        }
        val unit = (compiled as CompilationResult.Accepted).unit
        val values = linkedMapOf<String, Any>(
            "workflow-execution-plan-set.json" to unit.workflowPlanSet
        )
        if (!unit.workflowPlanSet.multiWorkflow) {
            values["execution-plan.json"] = unit.executionPlan
            values["canonical-execution-plan.json"] = unit.canonicalPlan
        }
        val accepted = base.copy(
            stage = "CANONICALIZATION", semanticAcceptance = "accepted", compilationSource = unit.source,
            graphDigest = unit.graphDigest.value,
            diagnostics = unit.requireReviewedAiProposalEvidence().validation.issues.map {
                diagnostic(it.code, "$.response.normalizedIntent", it.level.uppercase())
            }
        )
        if (reviewRequired) return publish(accepted.copy(
            outcome = CliProcessExit.REVIEW_REQUIRED, targetDisposition = "withheld-for-review"
        ), values, directory, output)
        if (selection == TargetSelectionDecision.NotSelected) return publish(accepted, values, directory, output)
        if (unit.workflowPlanSet.multiWorkflow) return publish(accepted.copy(
            outcome = CliProcessExit.BLOCKED, stage = "MATERIALIZATION", targetDisposition = "blocked",
            diagnostics = accepted.diagnostics + diagnostic("PROPOSAL_MULTI_WORKFLOW_TARGET_UNSUPPORTED", "$.response.normalizedIntent.workflows")
        ), values, directory, output)
        val strict = args.has(CliFlagOption.STRICT) || args.has(CliFlagOption.FAIL_ON_UNSUPPORTED)
        val explicit = TargetSelectionAuthority.requireSelected(selection, "Proposal target materialization")
        val evidence = CliTargetEvidenceAuthority(targets, ReferenceTargetProjections.fromContracts(contractRoot), contractRoot)
            .evaluate(unit, explicit, strict, render)
        values["target-manifest.json"] = evidence.manifest
        values["target-selection-evidence.json"] = evidence.targetSelection
        values["target-render-readiness.json"] = evidence.renderReadiness
        val roles = mutableMapOf("target-manifest.json" to CliArtifactRole.TARGET_MANIFEST)
        evidence.renderedArtifact?.let {
            values[it.fileName] = it.content
            values[it.evidenceFileName] = it.evidence
            roles[it.fileName] = if (it.kind == AdapterRenderedArtifactKind.EXECUTABLE_TARGET) CliArtifactRole.RENDERED_TARGET else CliArtifactRole.REVIEW_DOCUMENT
        }
        // Reuse the existing typed target outcome, including rendering and receipt invariants.
        val targetResult = CliExecutionResult.Targeted(explicit, evidence, strict, render, output.snapshot(),
            artifacts(values.keys, roles, directory != null))
        return publish(accepted.copy(
            outcome = CliProcessExit.entries.single { it.code == targetResult.exitCode }, stage = "MATERIALIZATION",
            targetDisposition = evidence.outcome.name.lowercase(),
            renderAuthorized = evidence.renderedArtifact?.kind == AdapterRenderedArtifactKind.EXECUTABLE_TARGET,
            diagnostics = accepted.diagnostics + evidence.diagnostics.map { diagnostic(it.code, "$.request.context.target", it.severity.uppercase()) }
        ), values, directory, output, roles)
    }

    private fun diagnostic(code: String, field: String, severity: String = "ERROR") = ProposalDiagnostic(
        code, severity, field, StandardDiagnosticCatalog.codes.find { it.code == code }?.description
            ?: "The compiler or input boundary rejected this proposal. Use the diagnostic code and field to revise the original input."
    )

    private fun artifacts(names: Set<String>, roles: Map<String, CliArtifactRole>, persisted: Boolean) = names.map {
        CliArtifact(it, roles[it] ?: if (it in setOf("workflow-execution-plan-set.json", "execution-plan.json", "canonical-execution-plan.json"))
            CliArtifactRole.TARGET_NEUTRAL_PLANNING else CliArtifactRole.DIAGNOSTIC_EVIDENCE, persisted)
    }

    private fun publish(
        review: ProposalReviewReport, values: LinkedHashMap<String, Any>, directory: File?,
        output: CliOutputCollector, roles: Map<String, CliArtifactRole> = emptyMap()
    ): CliExecutionResult {
        val report = review.copy(artifacts = values.keys.toList() + "proposal-review.json")
        values["proposal-review.json"] = report
        output.section("AI PROPOSAL REVIEW", report)
        values["workflow-execution-plan-set.json"]?.let { output.section("WORKFLOW EXECUTION PLAN SET", it) }
        values["target-manifest.json"]?.let { output.section("TARGET MANIFEST", it) }
        values["target-selection-evidence.json"]?.let { output.section("TARGET SELECTION EVIDENCE", it) }
        values["target-render-readiness.json"]?.let { output.section("TARGET RENDER READINESS", it) }
        roles.forEach { (name, role) ->
            val title = when (role) {
                CliArtifactRole.RENDERED_TARGET -> "RENDERED EXECUTABLE TARGET OUTPUT"
                CliArtifactRole.REVIEW_DOCUMENT -> "RENDERED NON-EXECUTABLE REVIEW EVIDENCE"
                else -> return@forEach
            }
            output.text("===== $title: $name =====")
            output.text(values.getValue(name) as String)
        }
        values["target-artifact-evidence.json"]?.let { output.section("TARGET ARTIFACT EVIDENCE RECEIPT", it) }
        directory?.let { CliArtifactOutput.write(it, values) }
        return ProposalCliResult(report, output.snapshot(), artifacts(values.keys, roles, directory != null))
    }
}
