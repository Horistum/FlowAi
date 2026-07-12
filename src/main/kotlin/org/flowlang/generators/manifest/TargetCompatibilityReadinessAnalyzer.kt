package org.flowlang.generators.manifest

import org.flowlang.capabilities.CompatibilityReadinessFinding
import org.flowlang.capabilities.CompatibilityReadinessReport
import org.flowlang.capabilities.ExecutionReadinessReport
import org.flowlang.capabilities.ExecutionReadinessStatus
import org.flowlang.capabilities.MaterializationReadinessStatus
import org.flowlang.capabilities.ProjectionReadinessStatus
import org.flowlang.capabilities.ReadinessFinding
import org.flowlang.capabilities.ReadinessSeverity
import org.flowlang.capabilities.SupportLevel
import org.flowlang.capabilities.TargetCapabilityNegotiationReport
import org.flowlang.capabilities.TargetSelectionCandidate
import org.flowlang.capabilities.TargetSelectionReport

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
        val capabilityStatus = manifest.metadata["capabilityCompatibility"]
            ?.let { value -> runCatching { SupportLevel.valueOf(value) }.getOrNull() }
            ?: manifest.compatibility.capabilityStatus

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
            capabilityStatus = capabilityStatus,
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
            capabilityStatus = capabilityStatus,
            effectiveStatus = effectiveStatus,
            materializationReadiness = materialization,
            projectionReadiness = projectionStatus,
            executable = projection.executable,
            evidenceAvailable = true,
            findings = (materializationFindings + projectionFindings).distinct()
        )
    }

    /** Applies concrete manifest evidence to a preliminary execution-readiness report. */
    fun reconcile(
        readiness: ExecutionReadinessReport,
        manifest: TargetManifest
    ): ExecutionReadinessReport {
        require(readiness.target == manifest.target) {
            "Execution readiness target '${readiness.target}' does not match manifest target '${manifest.target}'."
        }
        val concrete = analyze(manifest)
        val concreteFindings = concrete.findings.map { finding ->
            val blocker = concrete.effectiveStatus == SupportLevel.UNSUPPORTED ||
                concrete.materializationReadiness == MaterializationReadinessStatus.BLOCKED ||
                concrete.projectionReadiness == ProjectionReadinessStatus.FAIL_FAST
            ReadinessFinding(
                code = "TARGET_${finding.status}",
                severity = if (blocker) ReadinessSeverity.BLOCKER else ReadinessSeverity.WARNING,
                target = manifest.target,
                nodeId = finding.nodeId,
                message = finding.message
            )
        }
        val blockers = (readiness.blockers + concreteFindings.filter { it.severity == ReadinessSeverity.BLOCKER }).distinct()
        val warnings = (readiness.warnings + concreteFindings.filter { it.severity == ReadinessSeverity.WARNING }).distinct()
        val effectiveReadiness = when {
            concrete.effectiveStatus == SupportLevel.UNSUPPORTED -> ExecutionReadinessStatus.BLOCKED
            concrete.recommendationEligible && blockers.isEmpty() -> ExecutionReadinessStatus.READY
            else -> ExecutionReadinessStatus.DEGRADED
        }
        val generationAllowed = effectiveReadiness != ExecutionReadinessStatus.BLOCKED
        val productionReady = concrete.recommendationEligible && blockers.isEmpty()

        return readiness.copy(
            readiness = effectiveReadiness,
            generationAllowed = generationAllowed,
            productionReady = productionReady,
            decision = when (effectiveReadiness) {
                ExecutionReadinessStatus.READY -> "Concrete target manifest for '${manifest.target}' is materialized and projection-ready."
                ExecutionReadinessStatus.DEGRADED -> "Concrete target manifest for '${manifest.target}' is review-only or otherwise non-executable."
                ExecutionReadinessStatus.BLOCKED -> "Concrete target manifest for '${manifest.target}' is blocked and must not produce target syntax."
            },
            compatibilityStatus = concrete.effectiveStatus,
            blockers = blockers,
            warnings = warnings,
            requiredActions = buildList {
                if (blockers.isNotEmpty()) add("Resolve blocked materialization or unsupported projection evidence.")
                if (warnings.isNotEmpty()) add("Provide complete target renderer payload evidence before executable use.")
                if (productionReady) add("No target readiness action required.")
            }.distinct(),
            materializationReadiness = concrete.materializationReadiness,
            projectionReadiness = concrete.projectionReadiness,
            executable = concrete.executable,
            readinessEvidenceAvailable = true
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
                val concrete = analyze(manifest)
                entry.copy(
                    status = concrete.effectiveStatus,
                    notes = (entry.notes + readinessNotes(concrete)).distinct()
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
            blockedTargets = blocked,
            readinessEvidenceAvailable = true
        )
    }

    /** Applies concrete manifest evidence to preliminary target ranking and recommendation. */
    fun reconcile(
        selection: TargetSelectionReport,
        manifests: Collection<TargetManifest>
    ): TargetSelectionReport {
        val manifestsByTarget = manifests.associateBy { it.target }
        val candidates = selection.candidates.map { candidate ->
            val manifest = manifestsByTarget[candidate.target]
            if (manifest == null) {
                candidate.copy(
                    readiness = if (candidate.readiness == ExecutionReadinessStatus.BLOCKED) {
                        ExecutionReadinessStatus.BLOCKED
                    } else {
                        ExecutionReadinessStatus.DEGRADED
                    },
                    productionReady = false,
                    recommendation = "No concrete target manifest evidence is available; this target cannot be recommended."
                )
            } else {
                candidate.withConcreteReadiness(analyze(manifest))
            }
        }.sortedWith(
            compareBy<TargetSelectionCandidate> { selectionRank(it.readiness) }
                .thenByDescending { it.productionReady }
                .thenByDescending { it.targetPortabilityScore }
                .thenBy { it.target }
        ).mapIndexed { index, candidate -> candidate.copy(rank = index + 1) }

        val recommended = candidates.firstOrNull { it.productionReady && it.executable }?.target.orEmpty()
        return selection.copy(
            recommendedTarget = recommended,
            decision = if (recommended.isBlank()) {
                "No target has both supported effective compatibility and executable projection evidence."
            } else {
                "Recommended target is '$recommended' based on concrete materialization and projection evidence."
            },
            readyTargets = candidates.filter { it.readiness == ExecutionReadinessStatus.READY }.map { it.target },
            degradedTargets = candidates.filter { it.readiness == ExecutionReadinessStatus.DEGRADED }.map { it.target },
            blockedTargets = candidates.filter { it.readiness == ExecutionReadinessStatus.BLOCKED }.map { it.target },
            candidates = candidates
        )
    }

    private fun TargetSelectionCandidate.withConcreteReadiness(
        concrete: CompatibilityReadinessReport
    ): TargetSelectionCandidate {
        val readiness = when {
            concrete.effectiveStatus == SupportLevel.UNSUPPORTED -> ExecutionReadinessStatus.BLOCKED
            concrete.recommendationEligible -> ExecutionReadinessStatus.READY
            else -> ExecutionReadinessStatus.DEGRADED
        }
        val blockerDelta = if (readiness == ExecutionReadinessStatus.BLOCKED) concrete.findings.size.coerceAtLeast(1) else 0
        val warningDelta = if (readiness == ExecutionReadinessStatus.DEGRADED) concrete.findings.size.coerceAtLeast(1) else 0
        return copy(
            readiness = readiness,
            generationAllowed = readiness != ExecutionReadinessStatus.BLOCKED,
            productionReady = concrete.recommendationEligible,
            compatibilityStatus = concrete.effectiveStatus,
            blockerCount = blockerCount + blockerDelta,
            warningCount = warningCount + warningDelta,
            recommendation = when (readiness) {
                ExecutionReadinessStatus.READY -> "Recommended by concrete materialization and executable projection evidence."
                ExecutionReadinessStatus.DEGRADED -> "Review-only or incomplete target artifact; do not recommend for execution."
                ExecutionReadinessStatus.BLOCKED -> "Blocked target artifact; do not emit target syntax."
            },
            materializationReadiness = concrete.materializationReadiness,
            projectionReadiness = concrete.projectionReadiness,
            executable = concrete.executable,
            readinessEvidenceAvailable = true
        )
    }

    private fun selectionRank(readiness: ExecutionReadinessStatus): Int = when (readiness) {
        ExecutionReadinessStatus.READY -> 0
        ExecutionReadinessStatus.DEGRADED -> 1
        ExecutionReadinessStatus.BLOCKED -> 2
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
