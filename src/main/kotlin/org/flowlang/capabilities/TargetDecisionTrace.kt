package org.flowlang.capabilities

import org.flowlang.planner.ExecutionPlan
import org.flowlang.standard.FlowStandardVersions

enum class DecisionTraceStatus { PASSED, WARNING, BLOCKED }

enum class TargetDecisionKind { RECOMMENDED, READY_ALTERNATIVE, DEGRADED, BLOCKED }

data class TargetDecisionTraceStep(
    val id: String,
    val artifact: String,
    val status: DecisionTraceStatus,
    val summary: String
)

data class TargetDecisionExplanation(
    val target: String,
    val rank: Int,
    val readiness: ExecutionReadinessStatus,
    val decision: TargetDecisionKind,
    val reason: String
)

data class TargetDecisionTraceReport(
    val standardVersion: String = FlowStandardVersions.FLOW_STANDARD_VERSION,
    val traceModelVersion: String = "1.0",
    val planVersion: String,
    val flowName: String,
    val strict: Boolean,
    val requestedTarget: String,
    val recommendedTarget: String,
    val finalDecision: DecisionTraceStatus,
    val generationAllowed: Boolean,
    val decision: String,
    val trace: List<TargetDecisionTraceStep>,
    val targetExplanations: List<TargetDecisionExplanation>,
    val publicArtifacts: List<String>
)

/**
 * Explains how Flow reached the target recommendation.
 *
 * This is an audit/reporting layer over existing standard artifacts. It must
 * not introduce a second target-selection algorithm.
 */
class TargetDecisionTraceAnalyzer(private val targets: Map<String, TargetCapability>) {
    fun analyze(plan: ExecutionPlan, requestedTarget: String = "", strict: Boolean = false): TargetDecisionTraceReport {
        val negotiation = CompatibilityAnalyzer(targets).negotiate(plan, strict = strict)
        val selection = TargetSelectionAnalyzer(targets).analyze(plan, strict = strict)
        return analyze(plan, requestedTarget, strict, negotiation, selection)
    }

    /** Builds the trace from already reconciled negotiation and selection artifacts. */
    fun analyze(
        plan: ExecutionPlan,
        requestedTarget: String,
        strict: Boolean,
        negotiation: TargetCapabilityNegotiationReport,
        selection: TargetSelectionReport
    ): TargetDecisionTraceReport {
        require(negotiation.flowName == plan.flowName) {
            "Negotiation report flow '${negotiation.flowName}' does not match plan '${plan.flowName}'."
        }
        require(selection.flowName == plan.flowName) {
            "Selection report flow '${selection.flowName}' does not match plan '${plan.flowName}'."
        }
        val recommended = selection.recommendedTarget
        val recommendedCandidate = selection.candidates.firstOrNull { it.target == recommended }
        val finalDecision = finalDecisionFor(recommendedCandidate)
        val generationAllowed = recommendedCandidate?.let {
            it.generationAllowed && it.productionReady && it.executable
        } == true

        return TargetDecisionTraceReport(
            planVersion = plan.planVersion,
            flowName = plan.flowName,
            strict = strict,
            requestedTarget = requestedTarget,
            recommendedTarget = recommended,
            finalDecision = finalDecision,
            generationAllowed = generationAllowed,
            decision = decisionText(recommended, finalDecision),
            trace = traceSteps(plan, negotiation, selection, finalDecision),
            targetExplanations = selection.candidates.map { explainCandidate(it, recommended) },
            publicArtifacts = listOf(
                "execution-plan.json",
                "canonical-execution-plan.json",
                "capability-negotiation-report.json",
                "execution-readiness-report.json",
                "target-selection-report.json",
                "target-decision-trace-report.json"
            )
        )
    }

    private fun finalDecisionFor(candidate: TargetSelectionCandidate?): DecisionTraceStatus = when {
        candidate == null -> DecisionTraceStatus.BLOCKED
        candidate.readiness == ExecutionReadinessStatus.READY && candidate.productionReady && candidate.executable -> DecisionTraceStatus.PASSED
        candidate.generationAllowed -> DecisionTraceStatus.WARNING
        else -> DecisionTraceStatus.BLOCKED
    }

    private fun decisionText(recommendedTarget: String, finalDecision: DecisionTraceStatus): String = when (finalDecision) {
        DecisionTraceStatus.PASSED -> "Target recommendation is backed by executable manifest evidence: '$recommendedTarget'."
        DecisionTraceStatus.WARNING -> "Target selection has non-executable or incomplete evidence and is not ready for execution: '$recommendedTarget'."
        DecisionTraceStatus.BLOCKED -> "No target recommendation is backed by safe executable manifest evidence."
    }

    private fun traceSteps(
        plan: ExecutionPlan,
        negotiation: TargetCapabilityNegotiationReport,
        selection: TargetSelectionReport,
        finalDecision: DecisionTraceStatus
    ): List<TargetDecisionTraceStep> = listOf(
        TargetDecisionTraceStep(
            id = "execution-plan",
            artifact = "execution-plan.json",
            status = DecisionTraceStatus.PASSED,
            summary = "ExecutionPlan '${plan.flowName}' uses public plan version ${plan.planVersion}."
        ),
        TargetDecisionTraceStep(
            id = "capability-negotiation",
            artifact = "capability-negotiation-report.json",
            status = when {
                negotiation.recommendedTargets.isNotEmpty() -> DecisionTraceStatus.PASSED
                negotiation.blockedTargets.size == negotiation.targets.size -> DecisionTraceStatus.BLOCKED
                else -> DecisionTraceStatus.WARNING
            },
            summary = "Evaluated ${negotiation.targets.size} targets with readiness-aware recommendations; ${negotiation.recommendedTargets.size} targets are executable recommendations."
        ),
        TargetDecisionTraceStep(
            id = "execution-readiness",
            artifact = "execution-readiness-report.json",
            status = when {
                selection.readyTargets.isNotEmpty() -> DecisionTraceStatus.PASSED
                selection.blockedTargets.size == selection.candidates.size -> DecisionTraceStatus.BLOCKED
                else -> DecisionTraceStatus.WARNING
            },
            summary = "Readiness classified ${selection.readyTargets.size} ready, ${selection.degradedTargets.size} degraded and ${selection.blockedTargets.size} blocked targets."
        ),
        TargetDecisionTraceStep(
            id = "target-selection",
            artifact = "target-selection-report.json",
            status = finalDecision,
            summary = selection.decision
        )
    )

    private fun explainCandidate(candidate: TargetSelectionCandidate, recommendedTarget: String): TargetDecisionExplanation {
        val decision = when {
            candidate.target == recommendedTarget && candidate.productionReady && candidate.executable -> TargetDecisionKind.RECOMMENDED
            candidate.readiness == ExecutionReadinessStatus.READY -> TargetDecisionKind.READY_ALTERNATIVE
            candidate.readiness == ExecutionReadinessStatus.DEGRADED -> TargetDecisionKind.DEGRADED
            else -> TargetDecisionKind.BLOCKED
        }
        return TargetDecisionExplanation(
            target = candidate.target,
            rank = candidate.rank,
            readiness = candidate.readiness,
            decision = decision,
            reason = reasonFor(candidate, decision)
        )
    }

    private fun reasonFor(candidate: TargetSelectionCandidate, decision: TargetDecisionKind): String = when (decision) {
        TargetDecisionKind.RECOMMENDED -> "Highest-ranked target with complete materialization, executable projection evidence and portability score ${candidate.targetPortabilityScore}."
        TargetDecisionKind.READY_ALTERNATIVE -> "Executable target, but ranked below the recommended target by portability score or stable ordering."
        TargetDecisionKind.DEGRADED -> "Target is review-only or incomplete; materialization=${candidate.materializationReadiness}, projection=${candidate.projectionReadiness}."
        TargetDecisionKind.BLOCKED -> "Target has blocked compatibility or projection evidence; blocker count is ${candidate.blockerCount}."
    }
}
