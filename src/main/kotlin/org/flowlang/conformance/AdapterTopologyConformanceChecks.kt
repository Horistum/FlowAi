package org.flowlang.conformance

import java.io.File
import org.flowlang.adapters.portfolio.AdapterPortfolioLoader
import org.flowlang.adapters.portfolio.AdapterSupportClass
import org.flowlang.adapters.topology.AdapterTopologyClaimContract
import org.flowlang.adapters.topology.AdapterTopologyClaimStatus
import org.flowlang.adapters.topology.AdapterTopologyEvidenceAuthority
import org.flowlang.adapters.topology.AdapterTopologyEvidenceLoader
import org.flowlang.adapters.topology.AdapterTopologyRoadmapLifecycleAuthority
import org.flowlang.capabilities.TargetCapability
import org.flowlang.cli.Json
import org.flowlang.generators.manifest.TargetProjectionRegistry
import org.flowlang.targets.builtin.BuiltInTargetProjections
import org.flowlang.topology.ExecutionTopologyDecisionStatus
import org.flowlang.topology.ExecutionTopologyEvidenceStatus
import org.flowlang.topology.ExecutionTopologyKind
import org.flowlang.topology.ExecutionTopologyMatchingAuthority
import org.flowlang.topology.ExecutionTopologyRequirement
import org.flowlang.topology.ExecutionTopologyRequirementSource
import org.flowlang.topology.ExecutionTopologySupportStatus

class AdapterTopologyConformanceChecks(
    private val rootDir: File,
    private val targets: Map<String, TargetCapability>,
    private val projections: TargetProjectionRegistry
) {
    fun checks(): List<ConformanceCheck> {
        val lifecycleResult = runCatching { AdapterTopologyRoadmapLifecycleAuthority(rootDir).analyze() }
        val lifecycle = lifecycleResult.getOrNull()
        val topologyResult = runCatching { AdapterTopologyEvidenceAuthority(rootDir, targets, projections).analyze() }
        val topology = topologyResult.getOrNull()
        val runtimeResult = runCatching(::runtimeAuthorityErrors)
        val demotionResult = runCatching(::profileOnlyDemotionErrors)
        val executableResult = runCatching(::executableTopologyErrors)

        val lifecycleErrors = buildList {
            lifecycleResult.exceptionOrNull()?.let { add(it.message ?: it.javaClass.simpleName) }
            lifecycle?.failedChecks?.forEach { id ->
                val failed = lifecycle.checks.first { it.id == id }
                add("$id:${failed.evidence.joinToString()}:${failed.message}")
            }
        }
        val topologyErrors = buildList {
            topologyResult.exceptionOrNull()?.let { add(it.message ?: it.javaClass.simpleName) }
            topology?.findings?.forEach { add("${it.code}:${it.target}:${it.claim}:${it.message}") }
        }
        val runtimeErrors = resultErrors(runtimeResult)
        val demotionErrors = resultErrors(demotionResult)
        val executableErrors = resultErrors(executableResult)

        return listOf(
            ConformanceCheck(
                name = LIFECYCLE_CHECK,
                passed = lifecycle?.status == "PASS",
                message = lifecycleErrors.takeIf { it.isNotEmpty() }?.joinToString(" | ")
            ),
            ConformanceCheck(
                name = EVIDENCE_CHECK,
                passed = topology?.status == "PASS",
                message = topologyErrors.takeIf { it.isNotEmpty() }?.joinToString(" | ")
            ),
            ConformanceCheck(
                name = RUNTIME_AUTHORITY_CHECK,
                passed = runtimeErrors.isEmpty(),
                message = runtimeErrors.takeIf { it.isNotEmpty() }?.joinToString(" | ")
            ),
            ConformanceCheck(
                name = PROFILE_DEMOTION_CHECK,
                passed = demotionErrors.isEmpty(),
                message = demotionErrors.takeIf { it.isNotEmpty() }?.joinToString(" | ")
            ),
            ConformanceCheck(
                name = EXECUTABLE_TOPOLOGY_CHECK,
                passed = executableErrors.isEmpty(),
                message = executableErrors.takeIf { it.isNotEmpty() }?.joinToString(" | ")
            )
        )
    }

    private fun runtimeAuthorityErrors(): List<String> = buildList {
        for ((targetName, target) in targets.toSortedMap()) {
            val declarations = target.topologyProfile?.declarations.orEmpty()
            if (declarations.size != AdapterTopologyClaimContract.coreClaimKinds.size) {
                add("$targetName: runtime topology profile is incomplete (${declarations.size}/${AdapterTopologyClaimContract.coreClaimKinds.size}).")
            }
            for (declaration in declarations) {
                val key = declaration.kind.registryKey
                val expected = AdapterTopologyClaimContract.registryEvidenceReference(targetName, key)
                if (declaration.evidenceReference != expected) {
                    add("$targetName.$key: runtime evidence '${declaration.evidenceReference}' must be '$expected'.")
                }
                if (!declaration.detail.isNullOrBlank()) {
                    add("$targetName.$key: adapter-specific prose must remain in the adapter evidence authority, not the Core runtime profile.")
                }
            }
        }
    }

    private fun profileOnlyDemotionErrors(): List<String> = buildList {
        val portfolio = AdapterPortfolioLoader.load(rootDir)
        val profileOnlyTargets = portfolio.records.filter {
            it.supportClass == AdapterSupportClass.PROFILE_ONLY && it.target != "local"
        }
        for (record in profileOnlyTargets) {
            val profile = targets[record.target]?.topologyProfile
            if (profile == null) {
                add("${record.target}: runtime topology profile is missing.")
                continue
            }
            for (declaration in profile.declarations.filter { it.status != ExecutionTopologySupportStatus.UNKNOWN }) {
                add("${record.target}.${declaration.kind.registryKey}: profile-only target was promoted to ${declaration.status}.")
            }
            val probe = ExecutionTopologyMatchingAuthority.assess(
                listOf(
                    ExecutionTopologyRequirement(
                        id = "a0.2.profile-only.${record.target}",
                        kind = ExecutionTopologyKind.WORKFLOW_SCOPE,
                        subject = record.target,
                        source = ExecutionTopologyRequirementSource.PLAN_STRUCTURE,
                        evidenceReference = AdapterTopologyEvidenceLoader.PATH
                    )
                ),
                profile
            )
            if (probe.decision.status != ExecutionTopologyDecisionStatus.BLOCKED) {
                add("${record.target}: profile-only target must block topology-dependent execution.")
            }
        }
    }

    private fun executableTopologyErrors(): List<String> = buildList {
        val portfolio = AdapterPortfolioLoader.load(rootDir)
        val topologyDocument = AdapterTopologyEvidenceLoader.load(rootDir)
        val evidenceByTarget = topologyDocument.records.associateBy { it.target }
        val planGenerator = ReferenceSnapshotBundleGenerator(
            rootDir = rootDir,
            targets = targets,
            projections = projections
        )
        val executableRecords = portfolio.records.filter {
            it.supportClass == AdapterSupportClass.EXECUTABLE_REFERENCE
        }

        for (record in executableRecords) {
            val target = targets[record.target]
            if (target == null) {
                add("${record.target}: executable target is absent from runtime registry.")
                continue
            }
            val profile = target.topologyProfile
            if (profile == null) {
                add("${record.target}: executable target has no runtime topology profile.")
                continue
            }
            val topologyRecord = evidenceByTarget[record.target]
            if (topologyRecord == null) {
                add("${record.target}: executable target has no topology evidence record.")
                continue
            }

            for (reference in record.executableEvidence) {
                val snapshotFile = File(rootDir, reference.substringBefore('#'))
                val snapshotResult = runCatching {
                    Json.mapper.readValue(snapshotFile, ReferenceSnapshotSet::class.java)
                }
                val snapshot = snapshotResult.getOrNull()
                if (snapshot == null) {
                    add("${record.target}: snapshot cannot be parsed: ${snapshotResult.exceptionOrNull()?.message}")
                    continue
                }
                ReferenceSnapshotHonesty.validate(snapshot).forEach { issue ->
                    add("${record.target}: invalid snapshot '${snapshot.scenarioId}': $issue")
                }
                val targetState = snapshot.targets.singleOrNull { it.target == record.target }
                if (targetState == null || !targetState.executable) {
                    add("${record.target}: snapshot '${snapshot.scenarioId}' does not contain one executable target state.")
                    continue
                }
                val planArtifacts = snapshot.artifacts.filter { it.layer == ReferenceSnapshotLayer.SEMANTIC_PLAN }
                if (planArtifacts.size != 1) {
                    add("${record.target}: snapshot '${snapshot.scenarioId}' must contain exactly one semantic plan artifact.")
                    continue
                }
                val committedPlanFile = File(snapshotFile.parentFile, planArtifacts.single().file)
                if (!committedPlanFile.isFile) {
                    add("${record.target}: committed semantic plan artifact is missing: ${committedPlanFile.path}")
                    continue
                }
                val intentFile = File(rootDir, "examples/intent/${snapshot.scenarioId}.intent.yaml")
                if (!intentFile.isFile) {
                    add("${record.target}: reference intent is missing for scenario '${snapshot.scenarioId}': ${intentFile.path}")
                    continue
                }
                val planResult = runCatching { planGenerator.planFor(intentFile) }
                val plan = planResult.getOrNull()
                if (plan == null) {
                    add("${record.target}: semantic plan cannot be regenerated: ${planResult.exceptionOrNull()?.message}")
                    continue
                }
                val assessment = ExecutionTopologyMatchingAuthority.assess(plan.topologyRequirements, profile)
                if (assessment.decision.status != ExecutionTopologyDecisionStatus.MATCHED) {
                    val blockers = assessment.evidence.filter {
                        it.status != ExecutionTopologyEvidenceStatus.SATISFIED
                    }.joinToString { "${it.requirementId}=${it.status}" }
                    add("${record.target}: executable snapshot '${snapshot.scenarioId}' lacks matched topology evidence: $blockers")
                }
                for (evidence in assessment.evidence) {
                    val key = evidence.kind.registryKey
                    val claim = topologyRecord.claims.singleOrNull { it.key == key }
                    if (claim == null || claim.status != AdapterTopologyClaimStatus.SUPPORTED) {
                        add("${record.target}.$key: executable requirement is not backed by a SUPPORTED adapter claim.")
                    }
                    val expectedReference = AdapterTopologyClaimContract.registryEvidenceReference(record.target, key)
                    if (evidence.evidenceReference != expectedReference) {
                        add("${record.target}.$key: executable assessment did not consume adapter-owned evidence '$expectedReference'.")
                    }
                }
            }
        }
    }

    private fun resultErrors(result: Result<List<String>>): List<String> =
        result.getOrElse { listOf(it.message ?: it.javaClass.simpleName) }

    companion object {
        const val LIFECYCLE_CHECK = "adapters.a0.2.lifecycle-integrity"
        const val EVIDENCE_CHECK = "adapters.a0.2.topology-evidence-integrity"
        const val RUNTIME_AUTHORITY_CHECK = "adapters.a0.2.runtime-topology-authority"
        const val PROFILE_DEMOTION_CHECK = "adapters.a0.2.profile-only-demotion"
        const val EXECUTABLE_TOPOLOGY_CHECK = "adapters.a0.2.executable-topology-proof"
    }
}

/** Canonical complete post-Core adapter certification composition. */
class AdapterStreamConformanceRunner(
    private val rootDir: File,
    private val targets: Map<String, TargetCapability>,
    private val projections: TargetProjectionRegistry = BuiltInTargetProjections.registry
) {
    fun checks(): List<ConformanceCheck> {
        val produced = AdapterPortfolioConformanceChecks(rootDir, targets, projections).checks() +
            AdapterTopologyConformanceChecks(rootDir, targets, projections).checks() +
            AdapterBindingConformanceChecks(rootDir).checks()
        val inventoryResult = runCatching { AdapterConformanceInventory.load(rootDir) }
        val inventory = inventoryResult.getOrNull()
        val observed = produced.map { it.name }
        val exact = inventory != null && observed == inventory.checks
        val message = when {
            inventoryResult.isFailure -> inventoryResult.exceptionOrNull()?.message
            !exact -> "declared=${inventory?.checks?.joinToString()} observed=${observed.joinToString()}"
            else -> null
        }
        return listOf(
            ConformanceCheck(
                name = AdapterConformanceRunner.INVENTORY_CHECK,
                passed = exact,
                message = message
            )
        ) + produced
    }
}
