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
    val recommendation: String
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
 * Ranks registered targets for an already validated ExecutionPlan.
 *
 * This is intentionally a report layer, not a scheduler and not a renderer.
 * Flow should explain target choice before any target-specific manifest is
 * trusted.
 */
class TargetSelectionAnalyzer(private val targets: Map<String, TargetCapability>) {
    fun analyze(plan: ExecutionPlan, strict: Boolean = false): TargetSelectionReport {
        val readinessAnalyzer = ExecutionReadinessAnalyzer(targets)
        val readinessReports = targets.keys.sorted().map { target ->
            readinessAnalyzer.analyze(plan, target, strict = strict)
        }
        val rankedReports = readinessReports.sortedWith(
            compareBy<ExecutionReadinessReport> { readinessRank(it.readiness) }
                .thenByDescending { it.productionReady }
                .thenByDescending { it.targetPortabilityScore }
                .thenBy { it.target }
        )
        val candidates = rankedReports.mapIndexed { index, report ->
            TargetSelectionCandidate(
                rank = index + 1,
                target = report.target,
                readiness = report.readiness,
                generationAllowed = report.generationAllowed,
                productionReady = report.productionReady,
                compatibilityStatus = report.compatibilityStatus,
                targetPortabilityScore = report.targetPortabilityScore,
                blockerCount = report.blockers.size,
                warningCount = report.warnings.size,
                recommendation = recommendationFor(report)
            )
        }
        val recommended = candidates.firstOrNull { it.generationAllowed }?.target.orEmpty()
        return TargetSelectionReport(
            planVersion = plan.planVersion,
            flowName = plan.flowName,
            strict = strict,
            recommendedTarget = recommended,
            decision = decisionText(recommended, candidates),
            readyTargets = candidates.filter { it.readiness == ExecutionReadinessStatus.READY }.map { it.target },
            degradedTargets = candidates.filter { it.readiness == ExecutionReadinessStatus.DEGRADED }.map { it.target },
            blockedTargets = candidates.filter { it.readiness == ExecutionReadinessStatus.BLOCKED }.map { it.target },
            candidates = candidates
        )
    }

    private fun readinessRank(readiness: ExecutionReadinessStatus): Int = when (readiness) {
        ExecutionReadinessStatus.READY -> 0
        ExecutionReadinessStatus.DEGRADED -> 1
        ExecutionReadinessStatus.BLOCKED -> 2
    }

    private fun recommendationFor(report: ExecutionReadinessReport): String = when (report.readiness) {
        ExecutionReadinessStatus.READY -> "Recommended for direct target manifest generation."
        ExecutionReadinessStatus.DEGRADED -> "Usable only when target-specific limitations are accepted and documented."
        ExecutionReadinessStatus.BLOCKED -> "Do not generate for this target until blockers are resolved."
    }

    private fun decisionText(recommendedTarget: String, candidates: List<TargetSelectionCandidate>): String =
        if (recommendedTarget.isNotBlank()) {
            "Recommended target is '$recommendedTarget'."
        } else {
            "No registered target is currently eligible for manifest generation. Blockers: " +
                candidates.filter { it.blockerCount > 0 }.joinToString { it.target }
        }
}
