package org.flowlang.capabilities

import org.flowlang.planner.ExecutionPlan
import org.flowlang.standard.FlowStandardVersions

data class TargetSelectionCandidate(
    val rank: Int,
    val target: String,
    val readiness: ExecutionReadinessStatus,
    val generationAllowed: Boolean,
    val productionReady: Boolean,
    val compatibilityStatus: SupportLevel,
    val targetPortabilityScore: Double,
    val blockerCount: Int,
    val warningCount: Int,
    val recommendation: String,
    val materializationReadiness: MaterializationReadinessStatus = MaterializationReadinessStatus.NOT_EVALUATED,
    val projectionReadiness: ProjectionReadinessStatus = ProjectionReadinessStatus.NOT_EVALUATED,
    val executable: Boolean = false,
    val readinessEvidenceAvailable: Boolean = false
)

data class TargetSelectionReport(
    val standardVersion: String = FlowStandardVersions.FLOW_STANDARD_VERSION,
    val selectionModelVersion: String = "1.0",
    val planVersion: String,
    val flowName: String,
    val strict: Boolean,
    val recommendedTarget: String,
    val decision: String,
    val readyTargets: List<String>,
    val degradedTargets: List<String>,
    val blockedTargets: List<String>,
    val candidates: List<TargetSelectionCandidate>
)

/**
 * Produces preliminary target ranking from capability facts.
 *
 * This is intentionally a report layer, not a scheduler and not a renderer.
 * A concrete recommendation is added only after manifest materialization and
 * projection evidence is reconciled.
 */
class TargetSelectionAnalyzer(private val targets: Map<String, TargetCapability>) {
    fun analyze(plan: ExecutionPlan, strict: Boolean = false): TargetSelectionReport {
        val readinessAnalyzer = ExecutionReadinessAnalyzer(targets)
        val readinessReports = targets.keys.sorted().map { target ->
            readinessAnalyzer.analyze(plan, target, strict = strict)
        }
        val rankedReports = readinessReports.sortedWith(
            compareBy<ExecutionReadinessReport> { readinessRank(it.readiness) }
                .thenByDescending { it.targetPortabilityScore }
                .thenBy { it.target }
        )
        val candidates = rankedReports.mapIndexed { index, report ->
            TargetSelectionCandidate(
                rank = index + 1,
                target = report.target,
                readiness = report.readiness,
                generationAllowed = report.generationAllowed,
                productionReady = false,
                compatibilityStatus = report.compatibilityStatus,
                targetPortabilityScore = report.targetPortabilityScore,
                blockerCount = report.blockers.size,
                warningCount = report.warnings.size,
                recommendation = preliminaryRecommendationFor(report)
            )
        }
        return TargetSelectionReport(
            planVersion = plan.planVersion,
            flowName = plan.flowName,
            strict = strict,
            recommendedTarget = "",
            decision = "Capability ranking is preliminary. Concrete manifest readiness evidence is required before recommending a target.",
            readyTargets = emptyList(),
            degradedTargets = candidates.filter { it.readiness != ExecutionReadinessStatus.BLOCKED }.map { it.target },
            blockedTargets = candidates.filter { it.readiness == ExecutionReadinessStatus.BLOCKED }.map { it.target },
            candidates = candidates
        )
    }

    private fun readinessRank(readiness: ExecutionReadinessStatus): Int = when (readiness) {
        ExecutionReadinessStatus.READY -> 0
        ExecutionReadinessStatus.DEGRADED -> 1
        ExecutionReadinessStatus.BLOCKED -> 2
    }

    private fun preliminaryRecommendationFor(report: ExecutionReadinessReport): String = when (report.readiness) {
        ExecutionReadinessStatus.READY -> "Capability-compatible candidate; generate and evaluate a concrete target manifest before recommendation."
        ExecutionReadinessStatus.DEGRADED -> "Capability-degraded candidate; concrete artifact evidence and documented limitations are required."
        ExecutionReadinessStatus.BLOCKED -> "Do not generate for this target until capability blockers are resolved."
    }
}
