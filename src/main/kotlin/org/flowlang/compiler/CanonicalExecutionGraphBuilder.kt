package org.flowlang.compiler

import org.flowlang.core.FlowMergeContract
import org.flowlang.core.FlowProducerIdentity
import org.flowlang.controls.ControlDecisionAuthority
import org.flowlang.planner.ApprovalNode
import org.flowlang.planner.ConditionNode
import org.flowlang.planner.ControlNode
import org.flowlang.planner.DataOpNode
import org.flowlang.planner.ExecutionPlan
import org.flowlang.planner.ExecutionProgramPlanningResult
import org.flowlang.planner.LoopNode
import org.flowlang.planner.MatchPlanNode
import org.flowlang.planner.ParallelGroupNode
import org.flowlang.planner.PlanDependencyRelation
import org.flowlang.planner.PlanInput
import org.flowlang.planner.PlanNode
import org.flowlang.planner.PlanOutput
import org.flowlang.planner.PlanSchedule
import org.flowlang.planner.PlanTrigger
import org.flowlang.planner.PlannedWorkflowFailurePolicy
import org.flowlang.planner.RetryGroupNode
import org.flowlang.planner.WorkflowFailureDisposition
import org.flowlang.planner.TaskNode
import org.flowlang.planner.TryPlanNode

object CanonicalExecutionGraphBuilder {
    private const val MERGE_EVIDENCE_PREFIX = "flow.merge:"

    fun build(plan: ExecutionPlan): CanonicalExecutionGraphBuild {
        require(plan.explicitMergeTargetNodeIds().isEmpty()) {
            "ExecutionPlan contains explicit merge evidence but no path-aware merge contracts. " +
                "Use the compiler authorization boundary."
        }
        return build(plan, emptyList(), emptyMap(), PlannedWorkflowFailurePolicy.none())
    }

    internal fun build(
        plan: ExecutionPlan,
        mergeContracts: List<FlowMergeContract>,
        producerNodeIds: Map<FlowProducerIdentity, String>,
        failurePolicy: PlannedWorkflowFailurePolicy
    ): CanonicalExecutionGraphBuild = buildWorkflow(
        plan = plan,
        mergeContracts = mergeContracts,
        producerNodeIds = producerNodeIds,
        failurePolicy = failurePolicy,
        workflowNameOverride = null,
        identityNamespace = null
    )

    internal fun build(program: ExecutionProgramPlanningResult): CanonicalExecutionGraphBuild {
        val ordered = program.workflows.sortedBy { it.workflowName }
        if (ordered.size == 1) {
            val only = ordered.single()
            val build = buildWorkflow(
                plan = only.planning.plan,
                mergeContracts = only.availability.merges,
                producerNodeIds = only.planning.producerNodeIds,
                failurePolicy = only.planning.failurePolicy,
                workflowNameOverride = only.workflowName,
                identityNamespace = null
            )
            require(build.graph.flowName == program.flowName) {
                "Single-workflow program name '${program.flowName}' differs from its graph '${build.graph.flowName}'."
            }
            require(build.graph.inputs == program.inputs.map { it.toCanonical() }) {
                "Single-workflow program inputs differ from its graph projection."
            }
            return build
        }

        val workflowBuilds = ordered.map { workflow ->
            buildWorkflow(
                plan = workflow.planning.plan,
                mergeContracts = workflow.availability.merges,
                producerNodeIds = workflow.planning.producerNodeIds,
                failurePolicy = workflow.planning.failurePolicy,
                workflowNameOverride = workflow.workflowName,
                identityNamespace = workflow.workflowName
            )
        }
        val workflows = workflowBuilds.map { it.graph.workflows.single() }
        val workflowIdsByName = workflows.associate { it.name to it.id }
        val requirements = mergeIdentical(
            workflowBuilds.flatMap { it.graph.controlRequirements },
            { it.id },
            "control requirement"
        )
        val evidence = mergeIdentical(
            workflowBuilds.flatMap { it.graph.controlEvidence },
            { it.requirementId },
            "control evidence"
        )
        val topology = mergeIdentical(
            workflowBuilds.flatMap { it.graph.topologyRequirements },
            { it.id },
            "topology requirement"
        )
        val controlDecision = ControlDecisionAuthority.evaluate(requirements, evidence)

        val graph = CanonicalExecutionGraph(
            flowName = program.flowName,
            workflows = workflows,
            inputs = program.inputs.map { it.toCanonical() },
            triggers = program.triggers.sortedBy { it.id }.map { it.toCanonical(workflowIdsByName) },
            outputs = workflowBuilds.flatMap { it.graph.outputs },
            requiredCapabilities = workflowBuilds.flatMap { it.graph.requiredCapabilities }
                .distinct()
                .sortedBy(CanonicalCapabilityId::value),
            controlRequirements = requirements,
            controlEvidence = evidence,
            topologyRequirements = topology,
            valueMerges = workflowBuilds.flatMap { it.graph.valueMerges }
                .sortedBy { it.id.value },
            nodes = workflowBuilds.flatMap { it.graph.nodes }
                .sortedBy { it.id.value },
            dependencyEdges = workflowBuilds.flatMap { it.graph.dependencyEdges }
                .sortedWith(compareBy({ it.targetNodeId.value }, { it.sourceNodeId?.value.orEmpty() }, { it.kind.name }))
        )
        val bindings = CanonicalExecutionBindingSet(
            tasks = workflowBuilds.flatMap { it.bindings.tasks }.sortedBy { it.nodeId.value },
            nodeMetadata = workflowBuilds.flatMap { it.bindings.nodeMetadata }.sortedBy { it.nodeId.value },
            workflowPlans = workflowBuilds.flatMap { it.bindings.workflowPlans }
                .sortedBy { it.workflowName },
            programMetadata = ExecutionProgramProjectionMetadata(
                sourceIntent = program.sourceIntent,
                loweringReport = program.loweringReport,
                controlDecision = controlDecision
            )
        )
        return CanonicalExecutionGraphBuild(graph, bindings)
    }

    private fun buildWorkflow(
    plan: ExecutionPlan,
    mergeContracts: List<FlowMergeContract>,
    producerNodeIds: Map<FlowProducerIdentity, String>,
    failurePolicy: PlannedWorkflowFailurePolicy,
    workflowNameOverride: String?,
        identityNamespace: String?
    ): CanonicalExecutionGraphBuild {
        require(plan.flowName.isNotBlank()) { "Cannot build canonical graph from a blank flow name." }
        val workflowName = workflowNameOverride ?: workflowName(plan)
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
        val failureProjection = requireFailureProjection(plan, failurePolicy)

        fun nodeIdentity(node: PlanNode, path: List<String>): CanonicalNodeId {
            val authored = when (node) {
                is TaskNode -> node.sourceId
                is ApprovalNode -> node.sourceId
                else -> null
            }
            val authoredIdentity = authored?.takeIf(String::isNotBlank)?.let { value ->
                identityNamespace?.let { namespace -> "$namespace::$value" } ?: value
            }
            val structuralIdentity = path.joinToString("/").let { value ->
                identityNamespace?.let { namespace -> "$namespace::$value" } ?: value
            }
            return CanonicalNodeId(
                authoredIdentity?.let { identity("source", it) }
                    ?: identity("structure", structuralIdentity)
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

        val rootNodeIds = failureProjection.rootNodes.mapIndexed { index, node ->
            visit(node, listOf("root", (index + 1).toString()))
        }
        val failureHandlerNodeIds = failureProjection.handlerNodes.mapIndexed { index, node ->
            visit(node, listOf("workflow-failure", "handler", (index + 1).toString()))
        }
        val canonicalFailurePolicy = CanonicalWorkflowFailurePolicy(
            disposition = when (failurePolicy.policy.disposition) {
                WorkflowFailureDisposition.PROPAGATE -> CanonicalWorkflowFailureDisposition.PROPAGATE
                WorkflowFailureDisposition.RECOVER -> CanonicalWorkflowFailureDisposition.RECOVER
            },
            handler = failurePolicy.policy.handler?.let { handler ->
                CanonicalWorkflowFailureHandlerRegion(
                    id = handler.id,
                    nodeIds = failureHandlerNodeIds,
                    entry = CanonicalWorkflowFailureHandlerEntry(
                        errorBinding = handler.entry.errorBinding,
                        priorSuccessfulValuesAvailable = handler.entry.priorSuccessfulValuesAvailable
                    )
                )
            }
        )

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
            workflows = listOf(
                CanonicalWorkflow(
                    id = workflowId,
                    name = workflowName,
                    rootNodeIds = rootNodeIds,
                    failurePolicy = canonicalFailurePolicy
                )
            ),
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
        val planMetadata = ExecutionPlanProjectionMetadata(
            planVersion = plan.planVersion,
            dependencies = plan.dependencies,
            requiredCapabilities = plan.requiredCapabilities,
            sourceIntent = plan.sourceIntent,
            loweringReport = plan.loweringReport,
            assumptions = plan.assumptions,
            targetHints = plan.targetHints,
            controlRequirements = plan.controlRequirements,
            controlEvidence = plan.controlEvidence,
            controlDecision = plan.controlDecision,
            topologyRequirements = plan.topologyRequirements
        )
        val bindings = CanonicalExecutionBindingSet(
            tasks = taskBindings,
            nodeMetadata = nodeMetadata,
            workflowPlans = listOf(
    WorkflowExecutionPlanProjectionMetadata(
        workflowId = workflowId,
        workflowName = workflowName,
        planMetadata = planMetadata,
        failureCompatibility = failureProjection.compatibilityBoundaryNodeId?.let {
            WorkflowFailureCompatibilityProjectionMetadata(it)
        }
    )
),
            programMetadata = ExecutionProgramProjectionMetadata(
                sourceIntent = plan.sourceIntent,
                loweringReport = plan.loweringReport,
                controlDecision = plan.controlDecision
            )
        )
        return CanonicalExecutionGraphBuild(graph, bindings)
    }


    private data class WorkflowFailureBuildProjection(
        val rootNodes: List<PlanNode>,
        val handlerNodes: List<PlanNode>,
        val compatibilityBoundaryNodeId: String?
    )

    private fun requireFailureProjection(
        plan: ExecutionPlan,
        failurePolicy: PlannedWorkflowFailurePolicy
    ): WorkflowFailureBuildProjection {
        val handler = failurePolicy.policy.handler
        if (handler == null) {
            // A raw compatibility plan has no authority to promote a terminal
            // empty-body Try into workflow-failure meaning. Preserve it as an
            // ordinary Try node; only the typed policy below may detach a handler
            // region from normal roots.
            return WorkflowFailureBuildProjection(plan.nodes, emptyList(), null)
        }

        val boundaryId = requireNotNull(failurePolicy.compatibilityBoundaryNodeId)
        val boundary = plan.nodes.singleOrNull { it.id == boundaryId } as? TryPlanNode
            ?: error("Workflow failure compatibility boundary '$boundaryId' is missing or not a TryPlanNode.")
        require(plan.nodes.lastOrNull() == boundary) {
            "Workflow failure compatibility boundary must be the final compatibility root."
        }
        require(boundary.body.isEmpty() && boundary.errorHandler == failurePolicy.handlerNodes) {
            "Workflow failure compatibility boundary differs from the typed handler region."
        }
        require(handler.nodeIds == failurePolicy.handlerNodes.map(PlanNode::id)) {
            "Workflow failure policy does not name its exact handler nodes."
        }
        return WorkflowFailureBuildProjection(
            rootNodes = plan.nodes.dropLast(1),
            handlerNodes = failurePolicy.handlerNodes,
            compatibilityBoundaryNodeId = boundaryId
        )
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

    private fun PlanTrigger.toCanonical(
        workflowIdsByName: Map<String, CanonicalWorkflowId>
    ): CanonicalGraphTrigger = CanonicalGraphTrigger(
        id = id,
        kind = CanonicalTriggerKind.fromWire(type),
        workflows = workflows.distinct().sorted().map { workflowName ->
            requireNotNull(workflowIdsByName[workflowName]) {
                "Trigger '$id' references unknown workflow '$workflowName'."
            }
        },
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

    private fun <T, K : Comparable<K>> mergeIdentical(
        values: List<T>,
        key: (T) -> K,
        label: String
    ): List<T> = values.groupBy(key).toSortedMap().map { (identity, grouped) ->
        require(grouped.distinct().size == 1) {
            "Multi-workflow $label '$identity' has contradictory definitions."
        }
        grouped.first()
    }

    private fun identity(kind: String, value: String): String = "$kind:${value.length}:$value"
}
