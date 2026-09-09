package org.flowlang.conformance

import org.flowlang.distribution.reference.ReferenceAdapterEvidence
import java.io.File
import org.flowlang.adapters.continuity.AdapterContinuityClaimStatus
import org.flowlang.adapters.continuity.AdapterContinuityDecision
import org.flowlang.adapters.continuity.AdapterContinuityEvidenceLoader
import org.flowlang.adapters.continuity.AdapterContinuityEvidenceStatus
import org.flowlang.adapters.continuity.AdapterContinuityFamily
import org.flowlang.adapters.continuity.AdapterContinuityRoadmapLifecycleAuthority
import org.flowlang.adapters.continuity.AdapterContinuitySatisfactionAuthority
import org.flowlang.adapters.portfolio.AdapterPortfolioLoader
import org.flowlang.adapters.portfolio.AdapterSupportClass
import org.flowlang.capabilities.TargetCapability
import org.flowlang.cli.Json
import org.flowlang.continuity.StateLifetime
import org.flowlang.generators.manifest.TargetProjectionRegistry
import org.flowlang.planner.ExecutionPlan
import org.flowlang.planner.PlanDependencyEvidence
import org.flowlang.planner.PlanDependencyKind
import org.flowlang.planner.PlanDependencyRelation
import org.flowlang.planner.PlanDependencyResolution

class AdapterContinuityConformanceChecks(
    private val rootDir: File,
    private val targets: Map<String, TargetCapability>,
    private val projections: TargetProjectionRegistry
) {
    fun checks(): List<ConformanceCheck> {
        val lifecycleResult = runCatching { AdapterContinuityRoadmapLifecycleAuthority(rootDir).analyze() }
        val lifecycle = lifecycleResult.getOrNull()
        val evidenceResult = runCatching { authority().analyze() }
        val evidence = evidenceResult.getOrNull()
        val runtimeResult = runCatching(::runtimeAuthorityErrors)
        val orderingResult = runCatching(::orderingSeparationErrors)
        val executableResult = runCatching(::executableContinuityErrors)
        val profileResult = runCatching(::profileOnlyDemotionErrors)

        val lifecycleErrors = buildList {
            lifecycleResult.exceptionOrNull()?.let { add(it.message ?: it.javaClass.simpleName) }
            lifecycle?.failedChecks?.forEach { id ->
                val failed = lifecycle.checks.first { it.id == id }
                add("$id:${failed.evidence.joinToString()}:${failed.message}")
            }
        }
        val evidenceErrors = buildList {
            evidenceResult.exceptionOrNull()?.let { add(it.message ?: it.javaClass.simpleName) }
            evidence?.findings?.forEach { add("${it.code}:${it.target}:${it.family}:${it.message}") }
        }
        val runtimeErrors = resultErrors(runtimeResult)
        val orderingErrors = resultErrors(orderingResult)
        val executableErrors = resultErrors(executableResult)
        val profileErrors = resultErrors(profileResult)

        return listOf(
            ConformanceCheck(
                name = LIFECYCLE_CHECK,
                passed = lifecycle?.status == "PASS",
                message = lifecycleErrors.takeIf { it.isNotEmpty() }?.joinToString(" | ")
            ),
            ConformanceCheck(
                name = EVIDENCE_CHECK,
                passed = evidence?.status == "PASS",
                message = evidenceErrors.takeIf { it.isNotEmpty() }?.joinToString(" | ")
            ),
            ConformanceCheck(
                name = RUNTIME_AUTHORITY_CHECK,
                passed = runtimeErrors.isEmpty(),
                message = runtimeErrors.takeIf { it.isNotEmpty() }?.joinToString(" | ")
            ),
            ConformanceCheck(
                name = ORDERING_SEPARATION_CHECK,
                passed = orderingErrors.isEmpty(),
                message = orderingErrors.takeIf { it.isNotEmpty() }?.joinToString(" | ")
            ),
            ConformanceCheck(
                name = EXECUTABLE_PROOF_CHECK,
                passed = executableErrors.isEmpty(),
                message = executableErrors.takeIf { it.isNotEmpty() }?.joinToString(" | ")
            ),
            ConformanceCheck(
                name = PROFILE_DEMOTION_CHECK,
                passed = profileErrors.isEmpty(),
                message = profileErrors.takeIf { it.isNotEmpty() }?.joinToString(" | ")
            )
        )
    }

    private fun runtimeAuthorityErrors(): List<String> = buildList {
        val authority = authority()
        val workspace = planWith(resolvedRelation(PlanDependencyKind.WORKSPACE, "source"))
        val jenkinsWorkspace = authority.assess(workspace, "jenkins")
        if (
            jenkinsWorkspace.decision != AdapterContinuityDecision.MATCHED ||
            jenkinsWorkspace.requirements.singleOrNull()?.family != AdapterContinuityFamily.ARTIFACT ||
            jenkinsWorkspace.evidence.singleOrNull()?.status != AdapterContinuityEvidenceStatus.SATISFIED
        ) {
            add("Jenkins shared-workspace continuity must be the one currently satisfied provider subset.")
        }

        val value = authority.assess(planWith(resolvedRelation(PlanDependencyKind.VALUE, "result")), "jenkins")
        if (
            value.decision != AdapterContinuityDecision.BLOCKED ||
            value.requirements.singleOrNull()?.family != AdapterContinuityFamily.DATA ||
            value.evidence.singleOrNull()?.status != AdapterContinuityEvidenceStatus.UNSUPPORTED
        ) {
            add("Jenkins generic task result metadata must not be promoted to DATA continuity.")
        }

        val workflowState = authority.assess(
            planWith(resolvedRelation(PlanDependencyKind.STATE, "session", StateLifetime.WORKFLOW)),
            "jenkins"
        )
        if (
            workflowState.decision != AdapterContinuityDecision.BLOCKED ||
            workflowState.requirements.map { it.family }.toSet() != setOf(AdapterContinuityFamily.MUTABLE_STATE) ||
            workflowState.evidence.any { it.status != AdapterContinuityEvidenceStatus.UNSUPPORTED }
        ) {
            add("Workflow-local STATE continuity must require mutable transfer without inventing durable persistence.")
        }

        val durableState = authority.assess(
            planWith(resolvedRelation(PlanDependencyKind.STATE, "session", StateLifetime.DURABLE)),
            "jenkins"
        )
        if (
            durableState.decision != AdapterContinuityDecision.BLOCKED ||
            durableState.requirements.map { it.family }.toSet() !=
            setOf(AdapterContinuityFamily.MUTABLE_STATE, AdapterContinuityFamily.DURABLE_STATE) ||
            durableState.evidence.any { it.status != AdapterContinuityEvidenceStatus.UNSUPPORTED }
        ) {
            add("Explicit durable STATE continuity must require both mutable transfer and durable persistence evidence.")
        }

        val unresolved = resolvedRelation(PlanDependencyKind.WORKSPACE, "source").copy(
            sourceNodeId = null,
            resolution = PlanDependencyResolution.UNRESOLVED,
            path = emptyList()
        )
        val unresolvedAssessment = authority.assess(planWith(unresolved), "jenkins")
        if (
            unresolvedAssessment.decision != AdapterContinuityDecision.BLOCKED ||
            unresolvedAssessment.evidence.singleOrNull()?.status != AdapterContinuityEvidenceStatus.UNKNOWN
        ) {
            add("Adapter evidence must not repair unresolved Core continuity.")
        }
    }

    private fun orderingSeparationErrors(): List<String> = buildList {
        val ordering = PlanDependencyRelation(
            sourceNodeId = "producer",
            targetNodeId = "consumer",
            kind = PlanDependencyKind.ORDERING,
            evidence = PlanDependencyEvidence.DECLARED_ORDERING,
            path = listOf("producer", "consumer")
        )
        val assessment = authority().assess(planWith(ordering), "jenkins")
        if (assessment.requirements.isNotEmpty() || assessment.decision != AdapterContinuityDecision.MATCHED) {
            add("ORDERING must create no adapter continuity requirement and must never be promoted to continuity support.")
        }
    }

    private fun executableContinuityErrors(): List<String> = buildList {
        val portfolio = AdapterPortfolioLoader.load(rootDir)
        val generator = ReferenceSnapshotBundleGenerator(
            rootDir = rootDir,
            targets = targets,
            projections = projections
        )
        val authority = authority()
        for (record in portfolio.records.filter { it.supportClass == AdapterSupportClass.EXECUTABLE_REFERENCE }) {
            for (reference in record.executableEvidence) {
                val snapshotFile = File(rootDir, reference.substringBefore('#'))
                val snapshotResult = runCatching { Json.mapper.readValue(snapshotFile, ReferenceSnapshotSet::class.java) }
                val snapshot = snapshotResult.getOrNull()
                if (snapshot == null) {
                    add("${record.target}: executable snapshot cannot be parsed: ${snapshotResult.exceptionOrNull()?.message}")
                    continue
                }
                val intentFile = File(rootDir, "examples/intent/${snapshot.scenarioId}.intent.yaml")
                if (!intentFile.isFile) {
                    add("${record.target}: continuity proof intent is missing for '${snapshot.scenarioId}'.")
                    continue
                }
                val planResult = runCatching { generator.planFor(intentFile) }
                val plan = planResult.getOrNull()
                if (plan == null) {
                    add("${record.target}: continuity proof plan cannot be regenerated: ${planResult.exceptionOrNull()?.message}")
                    continue
                }
                val assessment = authority.assess(plan, record.target)
                if (assessment.requirements.isEmpty()) {
                    add("${record.target}: executable snapshot '${snapshot.scenarioId}' exercises no continuity requirement.")
                    continue
                }
                if (assessment.decision != AdapterContinuityDecision.MATCHED) {
                    val blockers = assessment.evidence.filter { it.status != AdapterContinuityEvidenceStatus.SATISFIED }
                        .joinToString { "${it.requirementId}=${it.status}" }
                    add("${record.target}: executable snapshot '${snapshot.scenarioId}' lacks continuity proof: $blockers")
                }
                if (assessment.requirements.any { it.completeness.name != "RESOLVED" }) {
                    add("${record.target}: executable snapshot '${snapshot.scenarioId}' contains unresolved continuity requirements.")
                }
            }
        }
    }

    private fun profileOnlyDemotionErrors(): List<String> = buildList {
        val portfolio = AdapterPortfolioLoader.load(rootDir)
        val evidence = AdapterContinuityEvidenceLoader.load(rootDir).targets.associateBy { it.target }
        val profileOnly = portfolio.records.filter {
            it.supportClass == AdapterSupportClass.PROFILE_ONLY || it.target == "local"
        }
        val probe = planWith(resolvedRelation(PlanDependencyKind.WORKSPACE, "source"))
        for (record in profileOnly) {
            val claims = evidence[record.target]?.claims.orEmpty()
            claims.filter { it.status != AdapterContinuityClaimStatus.UNKNOWN }.forEach { claim ->
                add("${record.target}.${claim.family}: profile-only continuity was promoted to ${claim.status}.")
            }
            val assessment = authority().assess(probe, record.target)
            if (
                assessment.decision != AdapterContinuityDecision.BLOCKED ||
                assessment.evidence.none { it.status == AdapterContinuityEvidenceStatus.UNKNOWN }
            ) {
                add("${record.target}: profile-only target must remain an UNKNOWN continuity blocker.")
            }
        }
    }

    private fun authority() = ReferenceAdapterEvidence.continuity(rootDir, targets, projections)

    private fun planWith(vararg relations: PlanDependencyRelation) = ExecutionPlan(
        flowName = "a0.5-conformance",
        dependencyRelations = relations.toList()
    )

    private fun resolvedRelation(
        kind: PlanDependencyKind,
        channel: String,
        stateLifetime: StateLifetime? = null
    ) = PlanDependencyRelation(
        sourceNodeId = "producer",
        targetNodeId = "consumer",
        kind = kind,
        channel = channel,
        stateLifetime = stateLifetime,
        evidence = if (kind == PlanDependencyKind.VALUE) {
            PlanDependencyEvidence.DATA_REFERENCE
        } else {
            PlanDependencyEvidence.MODULE_CONTRACT
        },
        resolution = PlanDependencyResolution.RESOLVED,
        path = listOf("producer", "consumer"),
        evidenceReference = "conformance.$channel"
    )

    private fun resultErrors(result: Result<List<String>>): List<String> =
        result.getOrElse { listOf(it.message ?: it.javaClass.simpleName) }

    companion object {
        const val LIFECYCLE_CHECK = "adapters.a0.5.lifecycle-integrity"
        const val EVIDENCE_CHECK = "adapters.a0.5.continuity-evidence-integrity"
        const val RUNTIME_AUTHORITY_CHECK = "adapters.a0.5.runtime-continuity-authority"
        const val ORDERING_SEPARATION_CHECK = "adapters.a0.5.ordering-continuity-separation"
        const val EXECUTABLE_PROOF_CHECK = "adapters.a0.5.executable-continuity-proof"
        const val PROFILE_DEMOTION_CHECK = "adapters.a0.5.profile-only-demotion"
    }
}
