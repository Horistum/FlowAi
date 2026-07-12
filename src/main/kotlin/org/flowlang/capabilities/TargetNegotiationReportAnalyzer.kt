package org.flowlang.capabilities

import org.flowlang.standard.FlowStandardVersions

/**
 * Human-readable target negotiation report built on top of the lower-level
 * compatibility negotiation model.
 *
 * This report explains why a target is usable, degraded, or blocked. Capability
 * compatibility remains preliminary until concrete manifest evidence is present.
 */
data class TargetNegotiationExplanationReport(
    val standardVersion: String = FlowStandardVersions.FLOW_STANDARD_VERSION,
    val reportVersion: String = "1.0",
    val status: String,
    val flowName: String,
    val requiredCapabilities: List<String>,
    val recommendedTargets: List<String>,
    val blockedTargets: List<String>,
    val targets: List<TargetNegotiationExplanation>,
    val rejectionReasons: List<TargetNegotiationRejectionReason>,
    val warnings: List<String>,
    val readinessEvidenceAvailable: Boolean = false
)

data class TargetNegotiationExplanation(
    val target: String,
    val outcome: TargetNegotiationOutcome,
    val portabilityScore: Double,
    val supportedCapabilities: List<String>,
    val degradedCapabilities: List<String>,
    val unsupportedCapabilities: List<String>,
    val runtimeRequiredCapabilities: List<String>,
    val rejectionReasons: List<TargetNegotiationRejectionReason>,
    val workaroundRecommendations: List<String>,
    val notes: List<String>
)

data class TargetNegotiationRejectionReason(
    val target: String,
    val capability: String,
    val level: SupportLevel,
    val message: String
)

enum class TargetNegotiationOutcome {
    SUPPORTED,
    DEGRADED,
    BLOCKED
}

object TargetNegotiationReportAnalyzer {
    fun explain(report: TargetCapabilityNegotiationReport): TargetNegotiationExplanationReport {
        val targetExplanations = report.targets.map { entry ->
            val reasons = rejectionReasonsFor(entry)
            TargetNegotiationExplanation(
                target = entry.target,
                outcome = outcomeFor(entry, report.readinessEvidenceAvailable),
                portabilityScore = entry.portabilityScore,
                supportedCapabilities = entry.supported.sorted(),
                degradedCapabilities = entry.partial.sorted(),
                unsupportedCapabilities = entry.unsupported.sorted(),
                runtimeRequiredCapabilities = entry.requiresRuntime.sorted(),
                rejectionReasons = reasons,
                workaroundRecommendations = workaroundRecommendationsFor(entry, report.requiredWorkarounds),
                notes = entry.notes.sorted()
            )
        }.sortedBy { it.target }

        val rejectionReasons = targetExplanations.flatMap { it.rejectionReasons }.sortedWith(
            compareBy<TargetNegotiationRejectionReason> { it.target }
                .thenBy { it.capability }
                .thenBy { it.message }
        )
        val warnings = buildList {
            targetExplanations
                .filter { it.outcome == TargetNegotiationOutcome.DEGRADED }
                .forEach {
                    add("Target '${it.target}' is degraded and requires explicit workarounds for: ${it.degradedCapabilities.joinToString()}${runtimeSuffix(it.runtimeRequiredCapabilities)}")
                }
            if (!report.readinessEvidenceAvailable) {
                add("Target recommendation is unavailable until materialization and projection readiness are evaluated on concrete manifests.")
            }
        }.distinct().sorted()
        val status = when {
            report.readinessEvidenceAvailable && targetExplanations.any { it.outcome == TargetNegotiationOutcome.SUPPORTED } -> "PASS"
            targetExplanations.any { it.outcome == TargetNegotiationOutcome.DEGRADED } -> "DEGRADED"
            else -> "BLOCKED"
        }

        return TargetNegotiationExplanationReport(
            status = status,
            flowName = report.flowName,
            requiredCapabilities = report.requiredCapabilities.sorted(),
            recommendedTargets = report.recommendedTargets.sorted(),
            blockedTargets = report.blockedTargets.sorted(),
            targets = targetExplanations,
            rejectionReasons = rejectionReasons,
            warnings = warnings,
            readinessEvidenceAvailable = report.readinessEvidenceAvailable
        )
    }

    private fun outcomeFor(
        entry: TargetNegotiationEntry,
        readinessEvidenceAvailable: Boolean
    ): TargetNegotiationOutcome = when {
        entry.unsupported.isNotEmpty() || entry.status == SupportLevel.UNSUPPORTED -> TargetNegotiationOutcome.BLOCKED
        !readinessEvidenceAvailable -> TargetNegotiationOutcome.DEGRADED
        entry.partial.isNotEmpty() || entry.requiresRuntime.isNotEmpty() || entry.status == SupportLevel.PARTIAL || entry.status == SupportLevel.REQUIRES_RUNTIME -> TargetNegotiationOutcome.DEGRADED
        else -> TargetNegotiationOutcome.SUPPORTED
    }

    private fun rejectionReasonsFor(entry: TargetNegotiationEntry): List<TargetNegotiationRejectionReason> {
        val unsupported = entry.unsupported.map { capability ->
            TargetNegotiationRejectionReason(
                target = entry.target,
                capability = capability,
                level = SupportLevel.UNSUPPORTED,
                message = "Capability '$capability' is not supported by target '${entry.target}'."
            )
        }
        val runtime = entry.requiresRuntime.map { capability ->
            TargetNegotiationRejectionReason(
                target = entry.target,
                capability = capability,
                level = SupportLevel.REQUIRES_RUNTIME,
                message = "Capability '$capability' requires runtime support on target '${entry.target}'."
            )
        }
        return (unsupported + runtime).sortedBy { it.capability }
    }

    private fun workaroundRecommendationsFor(
        entry: TargetNegotiationEntry,
        workarounds: List<TargetWorkaround>
    ): List<String> = workarounds
        .filter { it.target == entry.target }
        .sortedWith(compareBy<TargetWorkaround> { it.capability }.thenBy { it.support.name })
        .map { "${it.capability}: ${it.recommendation}" }

    private fun runtimeSuffix(capabilities: List<String>): String =
        if (capabilities.isEmpty()) "" else "; runtime required for: ${capabilities.sorted().joinToString()}"
}
