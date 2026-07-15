package org.flowlang.capabilities

data class CompatibilityIssue(
    val level: CompatibilityLevel,
    val target: String,
    val nodeId: String,
    val feature: String,
    val message: String
)

enum class CompatibilityLevel { INFO, WARNING, ERROR }

/**
 * Compatibility status for a target decision.
 *
 * [capabilityStatus] is the platform declaration. [status] is the effective
 * status of the report. Before a concrete manifest is evaluated, readiness
 * fields remain NOT_EVALUATED and executable remains false.
 */
data class CompatibilityReport(
    val target: String,
    val status: SupportLevel,
    val issues: List<CompatibilityIssue> = emptyList(),
    val capabilityStatus: SupportLevel = status,
    val materializationReadiness: MaterializationReadinessStatus = MaterializationReadinessStatus.NOT_EVALUATED,
    val projectionReadiness: ProjectionReadinessStatus = ProjectionReadinessStatus.NOT_EVALUATED,
    val executable: Boolean = false,
    val readinessEvidenceAvailable: Boolean = false,
    val expressionSupport: TargetExpressionSupportDeclaration? = null,
    val projectionRules: List<TargetProjectionRule> = emptyList()
) {
    val hasErrors: Boolean get() = issues.any { it.level == CompatibilityLevel.ERROR }
    val hasWarnings: Boolean get() = issues.any { it.level == CompatibilityLevel.WARNING }
    fun assertAllowed(strict: Boolean = false) {
        if (hasErrors) error("Target '$target' has unsupported Flow features: " + issues.filter { it.level == CompatibilityLevel.ERROR }.joinToString { it.feature + " at " + it.nodeId })
        if (strict && hasWarnings) error("Target '$target' has only partially supported Flow features in strict mode: " + issues.filter { it.level == CompatibilityLevel.WARNING }.joinToString { it.feature + " at " + it.nodeId })
    }
}

data class TargetCapabilityNegotiationReport(
    val planVersion: String,
    val flowName: String,
    val requiredCapabilities: List<String>,
    val portabilityScore: Double,
    val portableCapabilities: List<String>,
    val targetSpecificCapabilities: List<String>,
    val blockingPortabilityIssues: List<PortabilityIssue>,
    val requiredWorkarounds: List<TargetWorkaround>,
    val targets: List<TargetNegotiationEntry>,
    val recommendedTargets: List<String>,
    val blockedTargets: List<String>,
    val readinessEvidenceAvailable: Boolean = false
)

data class TargetNegotiationEntry(
    val target: String,
    val status: SupportLevel,
    val portabilityScore: Double,
    val supported: List<String> = emptyList(),
    val partial: List<String> = emptyList(),
    val unsupported: List<String> = emptyList(),
    val requiresRuntime: List<String> = emptyList(),
    val issues: List<CompatibilityIssue> = emptyList(),
    val notes: List<String> = emptyList()
)

data class PortabilityIssue(
    val target: String,
    val capability: String,
    val level: SupportLevel,
    val message: String
)

data class TargetWorkaround(
    val target: String,
    val capability: String,
    val support: SupportLevel,
    val recommendation: String
)
