package org.flowlang.targets.builtin

import org.flowlang.capabilities.SupportLevel
import org.flowlang.capabilities.TargetCapability
import org.flowlang.compiler.CanonicalParallelNode
import org.flowlang.compiler.CompilationAuthorization
import org.flowlang.generators.manifest.TargetNativeProjectionCatalog
import org.flowlang.generators.manifest.TargetProjectionCapabilityResolver
import org.flowlang.generators.manifest.TargetStructuralProjectionKind
import org.flowlang.generators.manifest.TargetStructuralProjectionSupportScope
import org.flowlang.planner.ApprovalNode
import org.flowlang.planner.ParallelGroupNode
import org.flowlang.planner.PlanDependencyRelations
import org.flowlang.topology.ExecutionTopologyKind
import org.flowlang.topology.ExecutionTopologySupportStatus

/** A single top-level fork/join of two gates; branch work has no workspace or output effects. */
class JenkinsParallelProjectionScope(private val catalog: TargetNativeProjectionCatalog?) : TargetProjectionCapabilityResolver {
    override fun resolve(authorization: CompilationAuthorization, target: String, declared: TargetCapability): TargetCapability {
        authorization.requireIntegrity()
        if (target != "jenkins" || declared.target != target || catalog == null || catalog.target != target ||
            catalog.structuralDefinitions.none { it.structure == TargetStructuralProjectionKind.PARALLEL &&
                it.kind == JenkinsProjectionPayloadKinds.JENKINS_STRUCTURE && it.reference == "approval-parallel" &&
                it.supportScope == TargetStructuralProjectionSupportScope.PLAN_SCOPED }) return declared
        val groups = authorization.graph.nodes.filterIsInstance<CanonicalParallelNode>()
        val bound = PlanDependencyRelations.flatten(authorization.executionPlan.nodes).filterIsInstance<ParallelGroupNode>()
        if (groups.size != 1 || bound.size != 1) return declared
        val group = bound.single()
        if (group !in authorization.executionPlan.nodes || group.branches.size != 2) return declared
        val approvals = group.branches.map { it.steps.singleOrNull() as? ApprovalNode ?: return declared }
        if (approvals.any { it.mode != "manual" || it.message.isNullOrBlank() || it.resultName != null ||
                it.outputs.isNotEmpty() || it.dependsOn.isNotEmpty() || it.dependencies.isNotEmpty() } ||
            approvals.map { it.message }.distinct().size != 2) return declared
        val topology = declared.topologyProfile ?: return declared
        if (topology.target != target || topology.declarations.count { it.kind == ExecutionTopologyKind.BRANCH_ISOLATION } != 1)
            return declared
        val scopedTopology = topology.copy(declarations = topology.declarations.map { declaration ->
            if (declaration.kind != ExecutionTopologyKind.BRANCH_ISOLATION) declaration else declaration.copy(
                status = ExecutionTopologySupportStatus.SUPPORTED,
                evidenceReference = if (group.failFast)
                    "src/test/kotlin/JenkinsParallelCancellationRuntimeTests.kt#cancellationAndSuccessfulJoinRequireAuthenticatedNativeEvidence"
                else "src/test/kotlin/JenkinsParallelRuntimeCertificationTests.kt#parallelGatesPreserveConcurrentPendingJoinAndRejection",
                detail = "Two distinct native input executions remain independently pending in parallel branches; no branch workspace mutation, result bindings or state-isolation claim.")
        })
        return declared.copy(features = declared.features + ("parallel.dag" to SupportLevel.SUPPORTED),
            topologyProfile = scopedTopology,
            notes = declared.notes + "Parallel supports one top-level pair of manual approval gates with distinct messages and either failFast policy; no branch workspace or output-continuity claim.")
    }
}
