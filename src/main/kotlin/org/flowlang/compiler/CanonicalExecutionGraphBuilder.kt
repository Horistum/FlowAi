package org.flowlang.compiler

import org.flowlang.core.FlowMergeContract
import org.flowlang.core.FlowProducerIdentity
import org.flowlang.planner.ApprovalNode
import org.flowlang.planner.ConditionNode
import org.flowlang.planner.ControlNode
import org.flowlang.planner.DataOpNode
import org.flowlang.planner.ExecutionPlan
import org.flowlang.planner.LoopNode
import org.flowlang.planner.MatchPlanNode
import org.flowlang.planner.ParallelGroupNode
import org.flowlang.planner.PlanDependencyRelation
import org.flowlang.planner.PlanInput
import org.flowlang.planner.PlanNode
import org.flowlang.planner.PlanOutput
import org.flowlang.planner.PlanSchedule
import org.flowlang.planner.PlanTrigger
import org.flowlang.planner.RetryGroupNode
import org.flowlang.planner.TaskNode
import org.flowlang.planner.TryPlanNode

object CanonicalExecutionGraphBuilder {
    private const val MERGE_EVIDENCE_PREFIX = "flow.merge:"

    fun build(plan: ExecutionPlan): CanonicalExecutionGraphBuild {
        require(plan.explicitMergeTargetNodeIds().isEmpty()) {
            "ExecutionPlan contains explicit merge evidence but no path-aware merge contracts. " +
                "Use the compiler authorization boundary."
        }
        return build(plan, emptyList(), emptyMap())
    }

    internal fun build(
        plan: ExecutionPlan,
        mergeContracts: List<FlowMergeContract>,
        producerNodeIds: Map<FlowProducerIdentity, String>
    ): CanonicalExecutionGraphBuild {
        require(plan.flowName.isNotBlank()) { "Cannot build canonical graph from a blank flow name." }
        val workflowName = workflowName(plan)
        val workflowId = CanonicalWorkflowId(identity("workflow", workflowName))
        val nodes = mutableListOf<CanonicalExecutionNode>()
        val taskBindings = mutableListOf<CanonicalTaskBinding>()
        val nodeMetadata = mutableListOf<CanonicalNodeProjectionMetadata>()
        val canonicalByPlanId = linkedMapOf<String, CanonicalNodeId>()
        val mergeByPlanNodeId = mergeContracts.associateBy { merge ->
            requireNotNull(producerNodeIds[merge.producer]) {
                "Merge producer '${merge.producer}' has no planner node binding."
            }
        }
        val mergeEvidenceTargets = plan.explicitMergeTargetNodeIds()
        require(mergeByPlanNodeId.keys == mergeEvidenceTargets) {
            "ExecutionPlan merge evidence and typed merge contracts differ: " +
                "evidence=${mergeEvidenceTargets.sorted()} contracts=${mergeByPlanNodeId.keys.sorted()}."
        }

        fun nodeIdentity(node: PlanNode, path: List<String>): CanonicalNodeId {
            val authored = when (node) {
                is TaskNode -> node.sourceId
                is ApprovalNode -> node.sourceId
                else -> null
            }
            return CanonicalNodeId(
                authored?.takeIf(String::isNotBlank)?.let { identity("source", it) }
                    ?: identity("structure", path.joinToString("/"))
            )
        }

        fun visit(node: PlanNode, path: List<String>): CanonicalNodeId {
            val id = nodeIdentity(node, path)
            require(canonicalByPlanId.putIfAbsent(node.id, id) == null) {
                "ExecutionPlan contains duplicate plan node id '${node.id}'."
            }
            val merge = mergeByPlanNodeId[node.id]
            nodeMetadata += CanonicalNodeProjectionMetadata(
                nodeId = id,
                planNodeId = node.id,
                planNodeKind = node.kind,
                sourceId = (node as? TaskNode)?.sourceId ?: (node as? ApprovalNode)?.sourceId,
                sourceDescription = (node as? TaskNode)?.sourceDescription ?: (node as? ApprovalNode)?.sourceDescription,
                legacyEffects = when (node) {
                    is TaskNode -> node.effects
                    is DataOpNode -> node.effects
                    else -> emptyList()
                },
                projectionDetail = if (merge != null) (node as? ControlNode)?.detail else null
            )

            fun children(values: List<PlanNode>, role: String): List<CanonicalNodeId> =
                values.mapIndexed { index, child -> visit(child, path + role + (index + 1).toString()) }

            val canonical: CanonicalExecutionNode = when (node) {
                is TaskNode -> {
                    require(node.dependsOn == node.dependencies) {
                        "Task '${node.id}' has contradictory dependsOn and dependencies projections."
                    }
                    require(node.inputs.isEmpty() || node.params.isEmpty() || node.inputs == node.params) {
                        "Task '${node.id}' has contradictory inputs and params projections."
                    }
                    taskBindings += CanonicalTaskBinding(
                        nodeId = id,
                        module = node.module,
                        action = node.action,
                        target = node.target,
                        parameterProjectionMode = parameterProjectionMode(node),
                        bindingMetadata = node.bindingMetadata,
                        targetHints = node.targetHints,
                        assumptions = node.assumptions
                    )
                    CanonicalTaskNode(
                        id = id,
                        workflow = workflowId,
                        semantics = CanonicalNodeSemantics(
                            capability = node.semanticCapability?.let(::CanonicalCapabilityId),
                            effects = node.effectModel,
                            parameters = node.inputs.ifEmpty { node.params },
                            outputs = node.outputs,
                            requiredCapabilities = node.requiredCapabilities.map(::CanonicalCapabilityId),
                            safety = CanonicalSafetyFacet(node.destructive, node.safety)
                        ),
                        resultName = node.resultName
                    )
                }
                is ApprovalNode -> {
                    require(node.dependsOn == node.dependencies) {
                        "Approval '${node.id}' has contradictory dependsOn and dependencies projections."
                    }
                    CanonicalApprovalNode(
                        id = id,
                        workflow = workflowId,
                        semantics = CanonicalNodeSemantics(
                            outputs = node.outputs,
                            requiredCapabilities = node.requiredCapabilities.map(::CanonicalCapabilityId),
                            safety = CanonicalSafetyFacet(destructive = false, rule = node.safety)
                        ),
                        mode = node.mode,
                        message = node.message,
                        resultName = node.resultName
                    )
                }
                is ConditionNode -> CanonicalConditionNode(
                    id = id,
                    workflow = workflowId,
                    condition = node.condition,
                    thenNodeIds = children(node.then, "then"),
                    otherwiseNodeIds = children(node.otherwise, "otherwise")
                )
                is LoopNode -> CanonicalLoopNode(
                    id = id,
                    workflow = workflowId,
                    item = node.item,
                    source = node.source,
                    bodyNodeIds = children(node.body, "body")
                )
                is ParallelGroupNode -> CanonicalParallelNode(
                    id = id,
                    workflow = workflowId,
                    failFast = node.failFast,
                    branches = node.branches.mapIndexed { branchIndex, branch ->
                        CanonicalParallelBranch(
                            branch.name,
                            branch.steps.mapIndexed { childIndex, child ->
                                visit(
                                    child,
                                    path + "branch" + (branchIndex + 1).toString() + "node" + (childIndex + 1).toString()
                                )
                            }
                        )
                    }
                )
                is MatchPlanNode -> CanonicalMatchNode(
                    id = id,
                    workflow = workflowId,
                    source = node.source,
                    cases = node.cases.mapIndexed { caseIndex, case ->
                        CanonicalMatchCase(
                            case.condition,
                            case.steps.mapIndexed { childIndex, child ->
                                visit(
                                    child,
                                    path + "case" + (caseIndex + 1).toString() + "node" + (childIndex + 1).toString()
                                )
                            }
                        )
                    },
                    errorNodeIds = children(node.errorCase, "error"),
                    defaultNodeIds = children(node.defaultSteps, "default")
                )
                is RetryGroupNode -> CanonicalRetryNode(
                    id = id,
                    workflow = workflowId,
                    max = node.max,
                    delay = node.delay,
                    backoff = node.backoff,
                    bodyNodeIds = children(node.body, "body")
                )
                is TryPlanNode -> CanonicalTryNode(
                    id = id,
                    workflow = workflowId,
                    bodyNodeIds = children(node.body, "body"),
                    errorHandlerNodeIds = children(node.errorHandler, "error")
                )
                is DataOpNode -> CanonicalDataOperationNode(
                    id = id,
                    workflow = workflowId,
                    operation = node.operationKind(),
                    target = node.target,
                    detail = node.detail,
                    semantics = CanonicalNodeSemantics(
                        capability = node.semanticCapability?.let(::CanonicalCapabilityId),
                        effects = node.effectModel
                    )
                )
                is ControlNode -> CanonicalControlOperationNode(
                    id = id,
                    workflow = workflowId,
                    operation = node.controlOperationKind(),
                    detail = merge?.canonicalDetail() ?: node.detail
                )
            }
            nodes += canonical
            return id
        }

        val rootNodeIds = plan.nodes.mapIndexed { index, node ->
            visit(node, listOf("root", (index + 1).toString()))
        }

        fun canonicalReference(planNodeId: String): CanonicalNodeId =
            canonicalByPlanId[planNodeId] ?: CanonicalNodeId(identity("missing-plan-node", planNodeId))

        val canonicalMerges = mergeContracts
            .sortedWith(compareBy({ it.identity.joinPath.workflow.value }, { it.identity.joinPath.value }, { it.identity.resultBinding }))
            .map { merge ->
                val targetPlanNodeId = requireNotNull(producerNodeIds[merge.producer])
                CanonicalValueMerge(
                    id = CanonicalMergeId(identity("merge", "${merge.identity.joinPath}:${merge.identity.resultBinding}")),
                    workflow = workflowId,
                    targetNodeId = canonicalReference(targetPlanNodeId),
                    joinPath = merge.identity.joinPath.toString(),
                    resultBinding = merge.identity.resultBinding,
                    paths = merge.paths.map { it.value }.sorted(),
                    inputs = merge.incoming.map { input ->
                        val sourcePlanNodeId = requireNotNull(producerNodeIds[input.producer]) {
                            "Merge input producer '${input.producer}' has no planner node binding."
                        }
                        CanonicalValueMergeInput(
                            binding = input.binding,
                            producerNodeId = canonicalReference(sourcePlanNodeId),
                            paths = input.paths.map { it.value }.sorted(),
                            valueType = input.valueType?.value?.let(::CanonicalValueTypeId)
                        )
                    },
                    valueType = merge.valueType?.value?.let(::CanonicalValueTypeId)
                )
            }

        val graph = CanonicalExecutionGraph(
            flowName = plan.flowName,
            workflows = listOf(CanonicalWorkflow(workflowId, workflowName, rootNodeIds)),
            inputs = plan.inputs.map { input -> input.toCanonical() },
            triggers = plan.triggers.map { trigger -> trigger.toCanonical(workflowId) },
            outputs = plan.outputs.map { output -> output.toCanonical(::canonicalReference) },
            requiredCapabilities = plan.requiredCapabilities.map(::CanonicalCapabilityId),
            controlRequirements = plan.controlRequirements,
            controlEvidence = plan.controlEvidence,
            topologyRequirements = plan.topologyRequirements,
            valueMerges = canonicalMerges,
            nodes = nodes,
            dependencyEdges = plan.dependencyRelations.map { relation -> relation.toCanonical(::canonicalReference) }
        )
        val bindings = CanonicalExecutionBindingSet(
            tasks = taskBindings,
            nodeMetadata = nodeMetadata,
            planMetadata = ExecutionPlanProjectionMetadata(
                planVersion = plan.planVersion,
                dependencies = plan.dependencies,
                sourceIntent = plan.sourceIntent,
                loweringReport = plan.loweringReport,
                assumptions = plan.assumptions,
                targetHints = plan.targetHints,
                controlDecision = plan.controlDecision
            )
        )
        return CanonicalExecutionGraphBuild(graph, bindings)
    }

    private fun ExecutionPlan.explicitMergeTargetNodeIds(): Set<String> =
        dependencyRelations.asSequence()
            .filter { relation -> relation.evidenceReference?.startsWith(MERGE_EVIDENCE_PREFIX) == true }
            .map { relation -> relation.targetNodeId }
            .toSet()

    private fun FlowMergeContract.canonicalDetail(): String =
        "${identity.resultBinding} = merge(${incoming.map { it.binding }.sorted().joinToString(", ")})"

    private fun workflowName(plan: ExecutionPlan): String {
        val authored = plan.sourceIntent?.workflows.orEmpty().map { it.name }.filter(String::isNotBlank).distinct()
        val triggered = plan.triggers.flatMap(PlanTrigger::workflows)
            .filter(String::isNotBlank)
            .filterNot { it == "main" && authored.isNotEmpty() }
            .distinct()
        val referenced = (authored + triggered).distinct()
        require(referenced.size <= 1) {
            "ExecutionPlan does not preserve membership for multiple workflows: ${referenced.joinToString()}."
        }
        return referenced.singleOrNull() ?: "main"
    }

    private fun parameterProjectionMode(node: TaskNode): TaskParameterProjectionMode = when {
        node.inputs.isEmpty() && node.params.isEmpty() -> TaskParameterProjectionMode.EMPTY
        node.inputs.isNotEmpty() && node.params.isEmpty() -> TaskParameterProjectionMode.INPUTS_ONLY
        node.inputs.isEmpty() && node.params.isNotEmpty() -> TaskParameterProjectionMode.PARAMS_ONLY
        else -> TaskParameterProjectionMode.BOTH
    }

    private fun PlanInput.toCanonical(): CanonicalGraphInput = CanonicalGraphInput(
        name = name,
        type = CanonicalValueTypeId(type),
        required = required,
        defaultValue = defaultValue,
        defaultExpression = defaultExpression,
        choices = choices
    )

    private fun PlanTrigger.toCanonical(workflowId: CanonicalWorkflowId): CanonicalGraphTrigger =
        CanonicalGraphTrigger(
            id = id,
            kind = CanonicalTriggerKind.fromWire(type),
            workflows = listOf(workflowId),
            schedule = schedule?.toCanonical(),
            event = event,
            params = params,
            requiredCapabilities = requiredCapabilities.map(::CanonicalCapabilityId)
        )

    private fun PlanSchedule.toCanonical(): CanonicalGraphSchedule = CanonicalGraphSchedule(
        kind = CanonicalScheduleKind.fromWire(kind),
        expression = expression,
        timezone = timezone
    )

    private fun PlanOutput.toCanonical(resolve: (String) -> CanonicalNodeId): CanonicalGraphOutput =
        CanonicalGraphOutput(
            name = name,
            type = CanonicalValueTypeId(type),
            sourceNodeId = sourceNodeId?.let(resolve)
        )

    private fun PlanDependencyRelation.toCanonical(
        resolve: (String) -> CanonicalNodeId
    ): CanonicalDependencyEdge = CanonicalDependencyEdge(
        sourceNodeId = sourceNodeId?.let(resolve),
        targetNodeId = resolve(targetNodeId),
        kind = kind.toCanonical(),
        channel = channel,
        stateLifetime = stateLifetime,
        evidence = evidence.toCanonical(),
        resolution = resolution.toCanonical(),
        path = path.map(resolve),
        candidates = candidates.map(resolve),
        evidenceReference = evidenceReference
    )

    private fun DataOpNode.operationKind(): CanonicalDataOperationKind = when (kind) {
        "Transform" -> CanonicalDataOperationKind.TRANSFORM
        "Validate" -> CanonicalDataOperationKind.VALIDATE
        "Aggregate" -> CanonicalDataOperationKind.AGGREGATE
        else -> error("Unsupported data operation kind '$kind'.")
    }

    private fun ControlNode.controlOperationKind(): CanonicalControlOperationKind = when (kind) {
        "Fail" -> CanonicalControlOperationKind.FAIL
        "Skip" -> CanonicalControlOperationKind.SKIP
        "Set" -> CanonicalControlOperationKind.SET
        "Expect" -> CanonicalControlOperationKind.EXPECT
        else -> error("Unsupported control operation kind '$kind'.")
    }

    private fun identity(kind: String, value: String): String = "$kind:${value.length}:$value"
}
