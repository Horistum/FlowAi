package org.flowlang.targets.builtin

import org.flowlang.ast.StringLiteralNode
import org.flowlang.parser.ExpressionParser
import org.flowlang.planner.TaskNode
import org.flowlang.planner.PlanDependencyKind
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

/** One bounded fork/join; optional checkout bodies have branch-local directories, never exported outputs. */
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
        val workspaceBodies = group.branches.all { it.steps.size == 2 }
        if (!workspaceBodies && group.branches.any { it.steps.size != 1 }) return declared
        val approvals = group.branches.map { it.steps.firstOrNull() as? ApprovalNode ?: return declared }
        if (workspaceBodies) {
            val tasks = group.branches.mapIndexed { index, branch ->
                val task = branch.steps[1] as? TaskNode ?: return declared
                if (!supportsCheckout(task, approvals[index].id)) return declared
                task
            }
            val taskIds = tasks.map { it.id }.toSet()
            if (authorization.executionPlan.dependencyRelations.any { it.kind != PlanDependencyKind.ORDERING &&
                (it.sourceNodeId in taskIds || it.targetNodeId in taskIds) }) return declared
        }
        if (approvals.any { it.mode != "manual" || it.message.isNullOrBlank() || it.resultName != null ||
                it.outputs.isNotEmpty() || it.dependsOn.isNotEmpty() || it.dependencies.isNotEmpty() } ||
            approvals.map { it.message }.distinct().size != 2) return declared
        val topology = declared.topologyProfile ?: return declared
        if (topology.target != target || topology.declarations.count { it.kind == ExecutionTopologyKind.BRANCH_ISOLATION } != 1)
            return declared
        val scopedTopology = topology.copy(declarations = topology.declarations.map { declaration ->
            if (declaration.kind != ExecutionTopologyKind.BRANCH_ISOLATION) declaration else declaration.copy(
                status = ExecutionTopologySupportStatus.SUPPORTED,
                evidenceReference = if (workspaceBodies && group.failFast)
                    "src/test/kotlin/JenkinsWorkspaceCancellationRuntimeTests.kt#workspacePoliciesPreserveJoinCancellationAndOriginalFailure"
                else if (workspaceBodies)
                    "src/test/kotlin/JenkinsParallelWorkspaceRuntimeTests.kt#branchDirectoriesPreserveApprovalWorkJoinAndRejection"
                else if (group.failFast)
                    "src/test/kotlin/JenkinsParallelCancellationRuntimeTests.kt#cancellationAndSuccessfulJoinRequireAuthenticatedNativeEvidence"
                else "src/test/kotlin/JenkinsParallelRuntimeCertificationTests.kt#parallelGatesPreserveConcurrentPendingJoinAndRejection",
                detail = if (workspaceBodies) "Two native approval branches write checkout state only in distinct identity-derived directories; no output export, fresh-workspace, security or state-transfer claim."
                    else "Two distinct native input executions remain independently pending in parallel branches; no branch workspace mutation, result bindings or state-isolation claim.")
        })
        return declared.copy(features = declared.features + ("parallel.dag" to SupportLevel.SUPPORTED),
            topologyProfile = scopedTopology,
            notes = declared.notes + "Parallel supports one top-level pair of manual gates with distinct messages and either failFast policy, or approval then isolated checkout with either policy; no output continuity, security isolation or fresh-workspace claim.")
    }

    private fun supportsCheckout(task: TaskNode, gateId: String): Boolean {
        if (task.module != "git" || task.action != "checkout" || task.resultName != null || task.outputs.isNotEmpty() ||
            (task.dependsOn + task.dependencies).any { it != gateId } ||
            task.params.keys.any { it !in setOf("url", "branch", "depth") } ||
            task.params["depth"]?.trim()?.let { it != "0" } == true) return false
        fun literal(name: String): String? = runCatching {
            (ExpressionParser.parseSource(task.params[name] ?: return null) as? StringLiteralNode)?.value
                ?.takeIf { it.isNotBlank() && !it.contains('$') }
        }.getOrNull()
        val branch = literal("branch") ?: return false
        return literal("url") != null && !Regex("[0-9a-fA-F]{40}").matches(branch)
    }

}
