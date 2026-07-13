package org.flowlang.generators.manifest

import org.flowlang.capabilities.CompatibilityIssue
import org.flowlang.capabilities.CompatibilityLevel
import org.flowlang.capabilities.SupportLevel

/**
 * Applies materialization and projection evidence to a canonical target manifest.
 * The capability-only status is retained for audit, while the public compatibility
 * status becomes the effective status of the concrete artifact.
 */
fun TargetManifest.reconcileCompatibilityReadiness(): TargetManifest {
    val readiness = TargetCompatibilityReadinessAnalyzer.analyze(this)
    val readinessIssues = readiness.findings.map { finding ->
        CompatibilityIssue(
            level = when (readiness.effectiveStatus) {
                SupportLevel.UNSUPPORTED -> CompatibilityLevel.ERROR
                SupportLevel.PARTIAL, SupportLevel.REQUIRES_RUNTIME -> CompatibilityLevel.WARNING
                SupportLevel.SUPPORTED -> CompatibilityLevel.INFO
            },
            target = target,
            nodeId = finding.nodeId,
            feature = "readiness.${finding.status.lowercase()}",
            message = finding.message
        )
    }
    return copy(
        compatibility = compatibility.copy(
            status = readiness.effectiveStatus,
            issues = (compatibility.issues + readinessIssues).distinct(),
            capabilityStatus = readiness.capabilityStatus,
            materializationReadiness = readiness.materializationReadiness,
            projectionReadiness = readiness.projectionReadiness,
            executable = readiness.executable,
            readinessEvidenceAvailable = true
        ),
        metadata = metadata + mapOf(
            "capabilityCompatibility" to readiness.capabilityStatus.name,
            "effectiveCompatibility" to readiness.effectiveStatus.name,
            "materializationReadiness" to readiness.materializationReadiness.name,
            "projectionReadiness" to readiness.projectionReadiness.name,
            "executable" to readiness.executable.toString()
        )
    )
}
