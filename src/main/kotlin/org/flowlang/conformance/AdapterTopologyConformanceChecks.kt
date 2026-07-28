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
import org.flowlang.planner.ExecutionPlan
import org.flowlang.targets.builtin.BuiltInTargetProjections
import org.flowlang.topology.ExecutionTopologyDecisionStatus
import org.flowlang.topology.ExecutionTopologyMatchingAuthority
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
        val runtimeErrors = runtimeAuthorityErrors()
        val demotionErrors = profileOnlyDemotionErrors()
        val executableErrors = executableTopologyErrors()

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
        targets.toSortedMap().forEach { (targetName, target) ->
            val declarations = target.topologyProfile?.declarations.orEmpty()
            if (declarations.size != AdapterTopologyClaimContract.coreClaimKinds.size) {
                add("$targetName: runtime topology profile is incomplete (${declarations.size}/${AdapterTopologyClaimContract.coreClaimKinds.size}).")
            }
            declarations.forEach { declaration ->
                val key = declaration.kind.registryKey
                val expected = AdapterTopologyClaimContract.registryEvidenceReference(targetName, key)
                if (declaration.evidenceReference != expected) {
                    add("$targetName.$key: runtime evidence '${declaration.evidenceReference}' must be '$expected'.")
                }
                if (declaration.detail.isNullOrBlank()) {
                    add("$targetName.$key: runtime topology evidence must retain mechanism detail.")
                }
            }
        }
    }

    private fun profileOnlyDemotionErrors(): List<String> = buildList {
        val portfolio = AdapterPortfolioLoader.load(rootDir)
        portfolio.records.filter { it.supportClass == AdapterSupportClass.PROFILE_ONLY && it.target != "local" }
            .forEach { record ->
                val profile = targets[record.target]?.topologyProfile
                if (profile == null) {
                    add("${record.target}: runtime topology profile is missing.")
                    return@forEach
                }
                val promoted = profile.declarations.filter { it.status != ExecutionTopologySupportStatus.UNKNOWN }
                promoted.forEach { declaration ->
                    add("${record.target}.${declaration.kind.registryKey}: profile-only target was promoted to ${declaration.status}.")
                }
                val probe = ExecutionTopologyMatchingAuthority.assess(
                    listOf(
                        org.flowlang.topology.ExecutionTopologyRequirement(
                            id = "a0.2.profile-only.${record.target}",
                            kind = org.flowlang.topology.ExecutionTopologyKind.WORKFLOW_SCOPE,
                            subject = record.target,
                            source = org.flowlang.topology.ExecutionTopologyRequirementSource.PLAN_STRUCTURE,
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
        portfolio.records.filter { it.supportClass == AdapterSupportClass.EXECUTABLE_REFERENCE }.forEach { record ->
            val target = targets[record.target]
            if (target == null) {
                add("${record.target}: executable target is absent from runtime registry.")
                return@forEach
            }
            val topologyRecord = evidenceByTarget[record.target]
            if (topologyRecord == null) {
                add("${record.target}: executable target has no topology evidence record.")
                return@forEach
            }
            record.executableEvidence.forEach { reference ->
                val snapshotFile = File(rootDir, reference.substringBefore('#'))
                val snapshotResult = runCatching { Json.mapper.readValue(snapshotFile, ReferenceSnapshotSet::class.java) }
                val snapshot = snapshotResult.getOrNull()
                if (snapshot == null) {
                    add("${record.target}: snapshot cannot be parsed: ${snapshotResult.exceptionOrNull()?.message}")
                    return@forEach
                }
                ReferenceSnapshotHonesty.validate(snapshot).forEach { issue ->
                    add("${record.target}: invalid snapshot '${snapshot.scenarioId}': $issue")
                }
                val planArtifacts = snapshot.artifacts.filter { it.layer == ReferenceSnapshotLayer.SEMANTIC_PLAN }
                if (planArtifacts.size != 1) {
                    add("${record.target}: snapshot '${snapshot.scenarioId}' must contain exactly one semantic plan artifact.")
                    return@forEach
                }
                val planFile = File(snapshotFile.parentFile, planArtifacts.single().file)
                val planResult = runCatching { Json.mapper.readValue(planFile, ExecutionPlan::class.java) }
                val plan = planResult.getOrNull()
                if (plan == null) {
                    add("${record.target}: execution plan cannot be parsed: ${planResult.exceptionOrNull()?.message}")
                    return@forEach
                }
                val assessment = ExecutionTopologyMatchingAuthority.assess(plan.topologyRequirements, target.topologyProfile)
                if (assessment.decision.status != ExecutionTopologyDecisionStatus.MATCHED) {
                    val blockers = assessment.evidence.filter {
                        it.status != org.flowlang.topology.ExecutionTopologyEvidenceStatus.SATISFIED
                    }.joinToString { "${it.requirementId}=${it.status}" }
                    add("${record.target}: executable snapshot '${snapshot.scenarioId}' lacks matched topology evidence: $blockers")
                }
                assessment.evidence.forEach { evidence ->
                    val claim = topologyRecord.claims.singleOrNull { it.key == evidence.kind.registryKey }
                    if (claim == null || claim.status != AdapterTopologyClaimStatus.SUPPORTED) {
                        add("${record.target}.${evidence.kind.registryKey}: executable requirement is not backed by a SUPPORTED adapter claim.")
                    }
                    if (evidence.evidenceReference != AdapterTopologyClaimContract.registryEvidenceReference(record.target, evidence.kind.registryKey)) {
                        add("${record.target}.${evidence.kind.registryKey}: executable assessment did not consume adapter-owned evidence.")
                    }
                }
            }
        }
    }

    companion object {
        const val LIFECYCLE_CHECK = "adapters.a0.2.lifecycle-integrity"
        const val EVIDENCE_CHECK = "adapters.a0.2.topology-evidence-integrity"
        const val RUNTIME_AUTHORITY_CHECK = "adapters.a0.2.runtime-topology-authority"
        const val PROFILE_DEMOTION_CHECK = "adapters.a0.2.profile-only-demotion"
        const val EXECUTABLE_TOPOLOGY_CHECK = "adapters.a0.2.executable-topology-proof"
    }
}

class AdapterStreamConformanceRunner(
    private val rootDir: File,
    private val targets: Map<String, TargetCapability>,
    private val projections: TargetProjectionRegistry = BuiltInTargetProjections.registry
) {
    fun checks(): List<ConformanceCheck> {
        val produced = AdapterPortfolioConformanceChecks(rootDir, targets, projections).checks() +
            AdapterTopologyConformanceChecks(rootDir, targets, projections).checks()
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
