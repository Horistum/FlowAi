package org.flowlang.capabilities

import org.flowlang.standard.FlowStandardVersions

/**
 * Stable capability matrix for supported Flow targets.
 *
 * The matrix is descriptive, not executable. It makes target support explicit so
 * generators and planners do not need to invent target semantics by optimism.
 */
data class TargetCapabilityMatrixReport(
    val standardVersion: String = FlowStandardVersions.FLOW_STANDARD_VERSION,
    val matrixVersion: String = "1.0",
    val status: String,
    val targetCount: Int,
    val targets: List<String>,
    val capabilityNames: List<String>,
    val entries: List<TargetCapabilityMatrixEntry>,
    val unsupportedEntries: List<TargetCapabilityMatrixEntry>,
    val partialEntries: List<TargetCapabilityMatrixEntry>,
    val requiresRuntimeEntries: List<TargetCapabilityMatrixEntry>,
    val issues: List<String>
)

data class TargetCapabilityMatrixEntry(
    val target: String,
    val capability: String,
    val support: SupportLevel,
    val source: String
)

class TargetCapabilityMatrixAnalyzer(
    private val targets: Map<String, TargetCapability>,
    private val requiredTargets: Set<String> = setOf("jenkins", "github-actions", "tekton")
) {
    private val coreCapabilities: List<String> = listOf(
        "sequentialTasks",
        "parallel",
        "conditions",
        "dynamicLoops",
        "match",
        "retry",
        "approvals",
        "errorHandlers",
        "artifacts",
        "secrets",
        "nativeRuntime"
    )

    fun analyze(): TargetCapabilityMatrixReport {
        val issues = mutableListOf<String>()
        if (targets.isEmpty()) issues += "Target capability registry must not be empty."

        val missingTargets = requiredTargets.filterNot { it in targets.keys }.sorted()
        if (missingTargets.isNotEmpty()) issues += "Missing required targets: ${missingTargets.joinToString()}"

        targets.values.forEach { target ->
            if (target.target.isBlank()) issues += "Target name must not be blank."
            if (target.description.isBlank()) issues += "Target '${target.target}' must declare a description."
            if (target.conditions != SupportLevel.UNSUPPORTED && target.expressionSupport == null) {
                issues += "Target '${target.target}' declares condition support without expression-support evidence."
            }
            target.expressionSupport?.let { declaration ->
                TargetExpressionSupport.declarationValidationReason(declaration)?.let { reason ->
                    issues += "Target '${target.target}' has invalid expression-support evidence: $reason"
                }
            }
        }

        val entries = targets.values
            .sortedBy { it.target }
            .flatMap { target -> entriesFor(target) }

        val capabilityNames = entries.map { it.capability }.distinct().sorted()
        val unsupported = entries.filter { it.support == SupportLevel.UNSUPPORTED }
        val partial = entries.filter { it.support == SupportLevel.PARTIAL }
        val requiresRuntime = entries.filter { it.support == SupportLevel.REQUIRES_RUNTIME }

        return TargetCapabilityMatrixReport(
            status = if (issues.isEmpty()) "PASS" else "FAIL",
            targetCount = targets.size,
            targets = targets.keys.sorted(),
            capabilityNames = capabilityNames,
            entries = entries,
            unsupportedEntries = unsupported,
            partialEntries = partial,
            requiresRuntimeEntries = requiresRuntime,
            issues = issues.sorted()
        )
    }

    private fun entriesFor(target: TargetCapability): List<TargetCapabilityMatrixEntry> {
        val core = coreCapabilities.map { capability ->
            TargetCapabilityMatrixEntry(
                target = target.target,
                capability = capability,
                support = coreSupport(target, capability),
                source = "core"
            )
        }
        val featureEntries = target.features.entries
            .sortedBy { it.key }
            .map { (capability, support) ->
                TargetCapabilityMatrixEntry(
                    target = target.target,
                    capability = capability,
                    support = support,
                    source = "feature"
                )
            }
        return core + featureEntries
    }

    private fun coreSupport(target: TargetCapability, capability: String): SupportLevel = when (capability) {
        "sequentialTasks" -> target.sequentialTasks
        "parallel" -> target.parallel
        "conditions" -> target.conditions
        "dynamicLoops" -> target.dynamicLoops
        "match" -> target.match
        "retry" -> target.retry
        "approvals" -> target.approvals
        "errorHandlers" -> target.errorHandlers
        "artifacts" -> target.artifacts
        "secrets" -> target.secrets
        "nativeRuntime" -> target.nativeRuntime
        else -> error("Unknown core target capability '$capability'.")
    }
}
