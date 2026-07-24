package org.flowlang.capabilities

import org.flowlang.planner.ExecutionPlan
import org.flowlang.standard.FlowStandardVersions

enum class ExecutionReadinessStatus { READY, DEGRADED, BLOCKED }

enum class ReadinessSeverity { INFO, WARNING, BLOCKER }

data class ReadinessFinding(
    val code: String,
    val severity: ReadinessSeverity,
    val target: String,
    val capability: String = "",
    val nodeId: String = "",
    val message: String
)

data class ExecutionReadinessReport(
    val standardVersion: String = FlowStandardVersions.FLOW_STANDARD_VERSION,
    val readinessModelVersion: String = "1.0",
    val planVersion: String,
    val flowName: String,
    val target: String,
    val strict: Boolean,
    val readiness: ExecutionReadinessStatus,
    val generationAllowed: Boolean,
    val productionReady: Boolean,
    val decision: String,
    val compatibilityStatus: SupportLevel,
    val targetPortabilityScore: Double,
    val planPortabilityScore: Double,
    val blockers: List<ReadinessFinding> = emptyList(),
    val warnings: List<ReadinessFinding> = emptyList(),
    val requiredActions: List<String> = emptyList(),
    val materializationReadiness: MaterializationReadinessStatus = MaterializationReadinessStatus.NOT_EVALUATED,
    val projectionReadiness: ProjectionReadinessStatus = ProjectionReadinessStatus.NOT_EVALUATED,
    val executable: Boolean = false,
    val readinessEvidenceAvailable: Boolean = false
)

/**
 * Converts compatibility and portability facts into a preliminary target decision.
 * Concrete materialization and projection evidence is reconciled after a target
 * manifest exists; until then this report must not be treated as executable proof.
 */
class ExecutionReadinessAnalyzer(private val targets: Map<String, TargetCapability>) {
    fun analyze(plan: ExecutionPlan, target: String, strict: Boolean = false): ExecutionReadinessReport {
        val compatibilityAnalyzer = CompatibilityAnalyzer(targets)
        val compatibility = compatibilityAnalyzer.analyze(plan, target, strict = strict)
        val negotiation = compatibilityAnalyzer.negotiate(plan, strict = strict)
        val targetEntry = negotiation.targets.firstOrNull { it.target == target }

        val blockers = mutableListOf<ReadinessFinding>()
        val warnings = mutableListOf<ReadinessFinding>()

        compatibility.issues.forEach { issue ->
            when (issue.level) {
                CompatibilityLevel.ERROR -> blockers += ReadinessFinding(
                    code = "TARGET_UNSUPPORTED_FEATURE",
                    severity = ReadinessSeverity.BLOCKER,
                    target = target,
                    capability = issue.feature,
                    nodeId = issue.nodeId,
                    message = issue.message
                )
                CompatibilityLevel.WARNING -> warnings += ReadinessFinding(
                    code = if (strict) "TARGET_STRICT_PARTIAL_FEATURE" else "TARGET_PARTIAL_FEATURE",
                    severity = if (strict) ReadinessSeverity.BLOCKER else ReadinessSeverity.WARNING,
                    target = target,
                    capability = issue.feature,
                    nodeId = issue.nodeId,
                    message = issue.message
                )
                CompatibilityLevel.INFO -> Unit
            }
        }

        targetEntry?.unsupported.orEmpty().forEach { capability ->
            blockers += ReadinessFinding(
                code = "TARGET_UNSUPPORTED_CAPABILITY",
                severity = ReadinessSeverity.BLOCKER,
                target = target,
                capability = capability,
                message = "Target '$target' does not support required capability '$capability'."
            )
        }
        targetEntry?.requiresRuntime.orEmpty().forEach { capability ->
            warnings += ReadinessFinding(
                code = "TARGET_REQUIRES_RUNTIME",
                severity = ReadinessSeverity.WARNING,
                target = target,
                capability = capability,
                message = "Target '$target' requires Flow runtime support for capability '$capability'."
            )
        }
        targetEntry?.partial.orEmpty().forEach { capability ->
            warnings += ReadinessFinding(
                code = "TARGET_PARTIAL_CAPABILITY",
                severity = ReadinessSeverity.WARNING,
                target = target,
                capability = capability,
                message = "Target '$target' only partially supports required capability '$capability'."
            )
        }

        if (targetEntry == null) {
            blockers += ReadinessFinding(
                code = "UNKNOWN_TARGET",
                severity = ReadinessSeverity.BLOCKER,
                target = target,
                message = "Unknown target '$target'."
            )
        }

        val effectiveBlockers = (blockers + warnings.filter { it.severity == ReadinessSeverity.BLOCKER }).distinct()
        val effectiveWarnings = warnings.filter { it.severity == ReadinessSeverity.WARNING }.distinct()
        val readiness = when {
            effectiveBlockers.isNotEmpty() -> ExecutionReadinessStatus.BLOCKED
            effectiveWarnings.isNotEmpty() || compatibility.status == SupportLevel.PARTIAL -> ExecutionReadinessStatus.DEGRADED
            else -> ExecutionReadinessStatus.READY
        }

        val report = ExecutionReadinessReport(
            planVersion = plan.planVersion,
            flowName = plan.flowName,
            target = target,
            strict = strict,
            readiness = readiness,
            generationAllowed = readiness != ExecutionReadinessStatus.BLOCKED,
            productionReady = false,
            decision = preliminaryDecisionText(readiness, target),
            compatibilityStatus = compatibility.status,
            targetPortabilityScore = targetEntry?.portabilityScore ?: 0.0,
            planPortabilityScore = negotiation.portabilityScore,
            blockers = effectiveBlockers,
            warnings = effectiveWarnings,
            requiredActions = preliminaryRequiredActions(readiness, effectiveBlockers, effectiveWarnings)
        )
        return ExecutionReadinessIntegrityAuthority.requireValid(report)
    }

    private fun preliminaryDecisionText(readiness: ExecutionReadinessStatus, target: String): String = when (readiness) {
        ExecutionReadinessStatus.READY -> "Capability checks passed for target '$target'; concrete materialization and projection readiness are not evaluated yet."
        ExecutionReadinessStatus.DEGRADED -> "Capability checks for target '$target' require documented target-specific limitations; concrete artifact readiness is not evaluated yet."
        ExecutionReadinessStatus.BLOCKED -> "ExecutionPlan must not be generated for target '$target' until blocking compatibility issues are resolved."
    }

    private fun preliminaryRequiredActions(
        readiness: ExecutionReadinessStatus,
        blockers: List<ReadinessFinding>,
        warnings: List<ReadinessFinding>
    ): List<String> = buildList {
        if (blockers.isNotEmpty()) add("Resolve blocking target incompatibilities before target manifest generation.")
        if (warnings.isNotEmpty()) add("Document target-specific workaround or choose a target with stronger native support.")
        if (readiness != ExecutionReadinessStatus.BLOCKED) add("Generate and evaluate a concrete target manifest before claiming executable readiness.")
    }.distinct()
}
