package org.flowlang.generators.manifest

import org.flowlang.capabilities.CompatibilityReadinessFinding
import org.flowlang.capabilities.CompatibilityReadinessReport
import org.flowlang.capabilities.MaterializationReadinessStatus
import org.flowlang.capabilities.ProjectionReadinessStatus
import org.flowlang.capabilities.SupportLevel
import org.flowlang.capabilities.TargetCapabilityNegotiationReport
import org.flowlang.capabilities.TargetNegotiationEntry

/**
 * Reconciles target capability declarations with concrete manifest evidence.
 *
 * This analyzer does not materialize work and does not add renderer payloads. It
 * only prevents capability optimism from being presented as executable readiness.
 */
object TargetCompatibilityReadinessAnalyzer {
    private val completedMaterialization = setOf(
        TargetMaterializationStatus.NATIVE,
        TargetMaterializationStatus.NOTES_PROJECTED
    )
    private val blockedMaterialization = setOf(
        TargetMaterializationStatus.BLOCKED,
        TargetMaterializationStatus.UNSUPPORTED
    )

    fun analyze(manifest: TargetManifest): CompatibilityReadinessReport {
        val projection = TargetRenderPolicy.evaluate(manifest)
        val leaves = manifest.jobs
            .flatMap { job -> job.steps.flatMap { it.flattenForCompatibilityReadiness() } }
            .filter { it.isCompatibilityReadinessLeaf() }

        val materialization = when {
            leaves.any { it.materialization.status in blockedMaterialization } -> MaterializationReadinessStatus.BLOCKED
            leaves.any { it.materialization.status !in completedMaterialization } -> MaterializationReadinessStatus.REVIEW_REQUIRED
            else -> MaterializationReadinessStatus.COMPLETE
        }
        val projectionStatus = when (projection.mode) {
            TargetRenderMode.EXECUTABLE -> ProjectionReadinessStatus.EXECUTABLE
            TargetRenderMode.REVIEW_ONLY -> ProjectionReadinessStatus.REVIEW_ONLY
            TargetRenderMode.FAIL_FAST -> ProjectionReadinessStatus.FAIL_FAST
        }
        val effectiveStatus = effectiveStatus(
            capabilityStatus = manifest.compatibility.status,
            materialization = materialization,
            projection = projectionStatus
        )
        val materializationFindings = leaves
            .filter { it.materialization.status !in completedMaterialization }
            .map {
                CompatibilityReadinessFinding(
                    nodeId = it.id,
                    status = it.materialization.status.name,
                    message = it.materialization.reason
                )
            }
        val projectionFindings = projection.findings.map {
            CompatibilityReadinessFinding(
                nodeId = it.nodeId,
                status = it.status,
                message = it.reason
            )
        }

        return CompatibilityReadinessReport(
            target = manifest.target,
            capabilityStatus = manifest.compatibility.status,
            effectiveStatus = effectiveStatus,
            materializationReadiness = materialization,
            projectionReadiness = projectionStatus,
            executable = projection.executable,
            evidenceAvailable = true,
            findings = (materializationFindings + projectionFindings).distinct()
        )
    }

    /**
     * Applies concrete manifest evidence to a capability negotiation report.
     *
     * The returned report keeps the original capability inventories but updates
     * target status and recommendations so unresolved or non-executable targets
     * cannot remain recommended.
     */
    fun reconcile(
        negotiation: TargetCapabilityNegotiationReport,
        manifests: Collection<TargetManifest>
    ): TargetCapabilityNegotiationReport {
        val manifestsByTarget = manifests.associateBy { it.target }
        val entries = negotiation.targets.map { entry ->
            val manifest = manifestsByTarget[entry.target]
            if (manifest == null) {
                entry.copy(
                    status = when (entry.status) {
                        SupportLevel.UNSUPPORTED -> SupportLevel.UNSUPPORTED
                        else -> SupportLevel.PARTIAL
                    },
                    notes = (entry.notes + "Materialization and projection readiness were not evaluated for this target.").distinct()
                )
            } else {
                val readiness = analyze(manifest)
                entry.copy(
                    status = readiness.effectiveStatus,
                    notes = (entry.notes + readinessNotes(readiness)).distinct()
                )
            }
        }
        val reports = manifestsByTarget.mapValues { (_, manifest) -> analyze(manifest) }
        val recommended = entries.mapNotNull { entry ->
            reports[entry.target]
                ?.takeIf { it.recommendationEligible }
                ?.let { entry.target }
        }.sorted()
        val blocked = entries.filter { entry ->
            entry.status == SupportLevel.UNSUPPORTED ||
                reports[entry.target]?.projectionReadiness == ProjectionReadinessStatus.FAIL_FAST ||
                reports[entry.target]?.materializationReadiness == MaterializationReadinessStatus.BLOCKED
        }.map { it.target }.distinct().sorted()

        return negotiation.copy(
            targets = entries,
            recommendedTargets = recommended,
            blockedTargets = blocked
        )
    }

    private fun effectiveStatus(
        capabilityStatus: SupportLevel,
        materialization: MaterializationReadinessStatus,
        projection: ProjectionReadinessStatus
    ): SupportLevel = when {
        capabilityStatus == SupportLevel.UNSUPPORTED -> SupportLevel.UNSUPPORTED
        materialization == MaterializationReadinessStatus.BLOCKED -> SupportLevel.UNSUPPORTED
        projection == ProjectionReadinessStatus.FAIL_FAST -> SupportLevel.UNSUPPORTED
        capabilityStatus == SupportLevel.REQUIRES_RUNTIME -> SupportLevel.REQUIRES_RUNTIME
        capabilityStatus == SupportLevel.PARTIAL -> SupportLevel.PARTIAL
        materialization != MaterializationReadinessStatus.COMPLETE -> SupportLevel.PARTIAL
        projection != ProjectionReadinessStatus.EXECUTABLE -> SupportLevel.PARTIAL
        else -> SupportLevel.SUPPORTED
    }

    private fun readinessNotes(report: CompatibilityReadinessReport): List<String> = listOf(
        "Materialization readiness: ${report.materializationReadiness}.",
        "Projection readiness: ${report.projectionReadiness}.",
        "Executable target artifact: ${report.executable}."
    )

    private fun TargetStep.isCompatibilityReadinessLeaf(): Boolean = children.isEmpty() && type !in setOf(
        "try-body",
        "error-handler",
        "parallel",
        "parallel-branch",
        "loop",
        "match",
        "retry",
        "condition"
    )

    private fun TargetStep.flattenForCompatibilityReadiness(): List<TargetStep> =
        listOf(this) + children.flatMap { it.flattenForCompatibilityReadiness() }
}
