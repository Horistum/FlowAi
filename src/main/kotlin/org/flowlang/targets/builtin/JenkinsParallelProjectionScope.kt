package org.flowlang.targets.builtin

import org.flowlang.ast.StringLiteralNode
import org.flowlang.parser.ExpressionParser
import org.flowlang.projection.ProjectionBindingKind
import org.flowlang.capabilities.SupportLevel
import org.flowlang.capabilities.TargetCapability
import org.flowlang.compiler.CanonicalParallelNode
import org.flowlang.compiler.CompilationAuthorization
import org.flowlang.generators.manifest.*
import org.flowlang.planner.*
import org.flowlang.topology.ExecutionTopologyKind
import org.flowlang.topology.ExecutionTopologySupportStatus

/** Fork/join applicability follows branch ownership and native isolation, not fixture cardinality. */
class JenkinsParallelProjectionScope(private val catalog: TargetNativeProjectionCatalog?) : TargetProjectionCapabilityResolver {
    override fun resolve(authorization: CompilationAuthorization, target: String, declared: TargetCapability): TargetCapability {
        authorization.requireIntegrity()
        if (target != "jenkins" || declared.target != target || catalog == null || catalog.target != target ||
            catalog.structuralDefinitions.none { it.structure == TargetStructuralProjectionKind.PARALLEL &&
                it.kind == JenkinsProjectionPayloadKinds.JENKINS_STRUCTURE && it.reference == "approval-parallel" &&
                it.supportScope == TargetStructuralProjectionSupportScope.PLAN_SCOPED }) return declared
        val ownedCatalog = requireNotNull(catalog)
        val groups = PlanDependencyRelations.flatten(authorization.executionPlan.nodes).filterIsInstance<ParallelGroupNode>()
        if (groups.isEmpty() || groups.size != authorization.graph.nodes.filterIsInstance<CanonicalParallelNode>().size ||
            groups.any { it !in authorization.executionPlan.nodes || it.branches.isEmpty() }) return declared
        val branchIds = mutableSetOf<String>()
        for (group in groups) for (branch in group.branches) {
            val prior = mutableSetOf<String>()
            for (node in branch.steps) {
                val dependencies = when (node) {
                    is ApprovalNode -> {
                        if (node.mode != "manual" || node.resultName != null || node.outputs.isNotEmpty() ||
                            runCatching { ownedCatalog.compileApproval(node)?.let(::controlLocal) }.getOrNull() != true) return declared
                        node.dependsOn + node.dependencies
                    }
                    is TaskNode -> {
                        val rule = declared.projectionRules.singleOrNull { it.module == node.module && it.action == node.action }
                            ?: return declared
                        if (!runCatching { workspaceLocal(ownedCatalog.compile(rule, node), node.params) }.getOrDefault(false)) return declared
                        node.dependsOn + node.dependencies
                    }
                    else -> return declared // Other controls need their own interaction evidence.
                }
                if (dependencies.any { it !in prior }) return declared
                prior += node.id
                branchIds += node.id
            }
        }
        // Directory placement establishes no value/state transfer, even between sequential native leaves.
        if (authorization.executionPlan.dependencyRelations.any { it.kind != PlanDependencyKind.ORDERING &&
                (it.sourceNodeId in branchIds || it.targetNodeId in branchIds) }) return declared
        val topology = declared.topologyProfile ?: return declared
        if (topology.target != target || topology.declarations.count { it.kind == ExecutionTopologyKind.BRANCH_ISOLATION } != 1)
            return declared
        return declared.copy(features = declared.features + ("parallel.dag" to SupportLevel.SUPPORTED),
            topologyProfile = topology.copy(declarations = topology.declarations.map { declaration ->
                if (declaration.kind != ExecutionTopologyKind.BRANCH_ISOLATION) declaration else declaration.copy(
                    status = ExecutionTopologySupportStatus.SUPPORTED,
                    evidenceReference = "src/test/kotlin/JenkinsParallelCompositionTests.kt; tools/parallel_composition_runtime.py; " +
                        "src/test/kotlin/JenkinsParallelWorkspaceRuntimeTests.kt; src/test/kotlin/JenkinsWorkspaceCancellationRuntimeTests.kt",
                    detail = "Independent native control executions and workspace-local leaves compose in identity-derived branch directories; authored failFast and fork/join are preserved. No branch output export, fresh workspace, shared-service or security isolation claim.")
            }),
            notes = declared.notes + "Top-level fork/join groups compose sequential branch leaves with owned control-local or workspace-local mappings; branch cardinality, labels, messages and leaf positions do not determine applicability.")
    }

    companion object {
        /** Native contracts, deliberately separate from module/action identity and support claims. */
        private fun controlLocal(payload: TargetRendererPayload): Boolean =
            payload.target == "jenkins" && payload.kind == JenkinsProjectionPayloadKinds.JENKINS_STEP &&
                payload.reference == "input" && payload.bindings["mode"]?.value == "manual" &&
                !payload.bindings["message"]?.value.isNullOrBlank()

        private fun workspaceLocal(payload: TargetRendererPayload, parameters: Map<String, String>): Boolean = runCatching {
            if (payload.target != "jenkins" || payload.kind != JenkinsProjectionPayloadKinds.JENKINS_STEP ||
                payload.reference != "git") return@runCatching false
            for (name in listOf("url", "branch")) {
                val binding = payload.bindings[name] ?: return@runCatching false
                val literal = when (binding.kind) {
                    ProjectionBindingKind.LITERAL -> binding.value
                    ProjectionBindingKind.TASK_PARAMETER -> if (binding.name !in parameters && binding.value == binding.defaultValue)
                        binding.defaultValue else (ExpressionParser.parseSource(binding.value ?: return@runCatching false) as? StringLiteralNode)?.value
                    else -> null
                }
                if (literal.isNullOrBlank() || literal.contains('$')) return@runCatching false
            }
            val context = "Jenkins branch workspace mapping"
            CheckoutProjectionValues.gitUrl(payload, "url", context)
            val branch = CheckoutProjectionValues.branch(payload, "branch", context)
            val depth = payload.bindings["depth"]?.let { CheckoutProjectionValues.depth(payload, "depth", context) } ?: 0
            depth == 0 && !Regex("[0-9a-fA-F]{40}").matches(branch)
        }.getOrDefault(false)

        internal fun usesWorkspace(step: TargetStep): Boolean = step.type == "action" &&
            step.rendererPayload?.let { workspaceLocal(it, step.params) } == true

        internal fun requireBranch(branch: TargetStep) {
            require(branch.type == "parallel-branch" && branch.id.length <= 128 && Regex("[a-z0-9_-]+").matches(branch.id)) {
                "Jenkins parallel branch needs a safe, preserved directory identity."
            }
            val prior = mutableSetOf<String>()
            branch.children.forEach { step ->
                require(step.children.isEmpty() && step.dependsOn.all { it in prior } &&
                    step.materialization.status == TargetMaterializationStatus.NATIVE &&
                    (usesWorkspace(step) || step.type == "approval" && step.params["mode"] == "manual" &&
                        step.metadata["resultName"] == null && step.rendererPayload?.let(::controlLocal) == true)) {
                    "Jenkins branch '${branch.id}' requires independent sequential leaves with owned native isolation mappings."
                }
                prior += step.id
            }
        }
    }
}
