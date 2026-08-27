package org.flowlang.compiler

import org.flowlang.planner.ApprovalNode
import org.flowlang.planner.ConditionNode
import org.flowlang.planner.ControlNode
import org.flowlang.planner.DataOpNode
import org.flowlang.planner.ExecutionPlan
import org.flowlang.planner.LoopNode
import org.flowlang.planner.MatchCase
import org.flowlang.planner.MatchPlanNode
import org.flowlang.planner.ParallelGroupNode
import org.flowlang.planner.PlanBranch
import org.flowlang.planner.PlanDependencyRelation
import org.flowlang.planner.PlanInput
import org.flowlang.planner.PlanNode
import org.flowlang.planner.PlanOutput
import org.flowlang.planner.PlanSchedule
import org.flowlang.planner.PlanTrigger
import org.flowlang.planner.RetryGroupNode
import org.flowlang.planner.TaskNode
import org.flowlang.planner.TryPlanNode

object CanonicalExecutionGraphProjection {
    fun toExecutionPlan(build: CanonicalExecutionGraphBuild): ExecutionPlan =
        toExecutionPlan(build.graph, build.bindings)

    fun toExecutionPlan(
        graph: CanonicalExecutionGraph,
        bindings: CanonicalExecutionBindingSet
    ): ExecutionPlan {
        val nodeById = graph.nodes.associateBy(CanonicalExecutionNode::id)
        val taskBindingById = bindings.tasks.associateBy(CanonicalTaskBinding::nodeId)
        val metadataById = bindings.nodeMetadata.associateBy(CanonicalNodeProjectionMetadata::nodeId)

        fun metadata(id: CanonicalNodeId): CanonicalNodeProjectionMetadata = requireNotNull(metadataById[id]) {
            "Canonical graph node '$id' has no compatibility identity."
        }

        fun planId(id: CanonicalNodeId): String = metadata(id).planNodeId

        fun dependenciesFor(id: CanonicalNodeId): List<String> = graph.dependencyEdges
            .asSequence()
            .filter { edge ->
                edge.targetNodeId == id &&
                    edge.kind == CanonicalDependencyKind.ORDERING &&
                    edge.resolution == CanonicalDependencyResolution.RESOLVED
            }
            .mapNotNull(CanonicalDependencyEdge::sourceNodeId)
            .map(::planId)
            .distinct()
            .toList()

        fun project(id: CanonicalNodeId): PlanNode {
            val node = requireNotNull(nodeById[id]) { "Canonical graph references missing node '$id'." }
            val projection = metadata(id)
            val projectionId = projection.planNodeId
            return when (node) {
                is CanonicalTaskNode -> {
                    val binding = requireNotNull(taskBindingById[id]) {
                        "Canonical task '$id' has no implementation binding."
                    }
                    val dependencies = dependenciesFor(id)
                    TaskNode(
                        id = projectionId,
                        kind = projection.planNodeKind,
                        module = binding.module,
                        action = binding.action,
                        target = binding.target,
                        resultName = node.resultName,
                        dependsOn = dependencies,
                        dependencies = dependencies,
                        semanticCapability = node.semantics.capability?.value,
                        sourceId = projection.sourceId,
                        sourceDescription = projection.sourceDescription,
                        bindingMetadata = binding.bindingMetadata,
                        effectModel = node.semantics.effects,
                        effects = projection.legacyEffects,
                        inputs = when (binding.parameterProjectionMode) {
                            TaskParameterProjectionMode.EMPTY,
                            TaskParameterProjectionMode.PARAMS_ONLY -> emptyMap()
                            TaskParameterProjectionMode.INPUTS_ONLY,
                            TaskParameterProjectionMode.BOTH -> node.semantics.parameters
                        },
                        outputs = node.semantics.outputs,
                        destructive = node.semantics.safety.destructive,
                        safety = node.semantics.safety.rule,
                        params = when (binding.parameterProjectionMode) {
                            TaskParameterProjectionMode.EMPTY,
                            TaskParameterProjectionMode.INPUTS_ONLY -> emptyMap()
                            TaskParameterProjectionMode.PARAMS_ONLY,
                            TaskParameterProjectionMode.BOTH -> node.semantics.parameters
                        },
                        requiredCapabilities = node.semantics.requiredCapabilities.map(CanonicalCapabilityId::value),
                        targetHints = binding.targetHints,
                        assumptions = binding.assumptions
                    )
                }
                is CanonicalApprovalNode -> {
                    val dependencies = dependenciesFor(id)
                    ApprovalNode(
                        id = projectionId,
                        kind = projection.planNodeKind,
                        mode = node.mode,
                        message = node.message,
                        resultName = node.resultName,
                        sourceId = projection.sourceId,
                        sourceDescription = projection.sourceDescription,
                        outputs = node.semantics.outputs,
                        dependsOn = dependencies,
                        dependencies = dependencies,
                        requiredCapabilities = node.semantics.requiredCapabilities.map(CanonicalCapabilityId::value),
                        safety = node.semantics.safety.rule
                    )
                }
                is CanonicalConditionNode -> ConditionNode(
                    id = projectionId,
                    kind = projection.planNodeKind,
                    condition = node.condition,
                    then = node.thenNodeIds.map(::project),
                    otherwise = node.otherwiseNodeIds.map(::project)
                )
                is CanonicalLoopNode -> LoopNode(
                    id = projectionId,
                    kind = projection.planNodeKind,
                    item = node.item,
                    source = node.source,
                    body = node.bodyNodeIds.map(::project)
                )
                is CanonicalParallelNode -> ParallelGroupNode(
                    id = projectionId,
                    kind = projection.planNodeKind,
                    failFast = node.failFast,
                    branches = node.branches.map { branch ->
                        PlanBranch(branch.name, branch.nodeIds.map(::project))
                    }
                )
                is CanonicalMatchNode -> MatchPlanNode(
                    id = projectionId,
                    kind = projection.planNodeKind,
                    source = node.source,
                    cases = node.cases.map { case -> MatchCase(case.condition, case.nodeIds.map(::project)) },
                    errorCase = node.errorNodeIds.map(::project),
                    defaultSteps = node.defaultNodeIds.map(::project)
                )
                is CanonicalRetryNode -> RetryGroupNode(
                    id = projectionId,
                    kind = projection.planNodeKind,
                    max = node.max,
                    delay = node.delay,
                    backoff = node.backoff,
                    body = node.bodyNodeIds.map(::project)
                )
                is CanonicalTryNode -> TryPlanNode(
                    id = projectionId,
                    kind = projection.planNodeKind,
                    body = node.bodyNodeIds.map(::project),
                    errorHandler = node.errorHandlerNodeIds.map(::project)
                )
                is CanonicalDataOperationNode -> DataOpNode(
                    id = projectionId,
                    kind = projection.planNodeKind,
                    target = node.target,
                    detail = node.detail,
                    semanticCapability = node.semantics.capability?.value,
                    effectModel = node.semantics.effects,
                    effects = projection.legacyEffects
                )
                is CanonicalControlOperationNode -> ControlNode(
                    id = projectionId,
                    kind = projection.planNodeKind,
                    detail = node.detail
                )
            }
        }

        val workflow = graph.workflows.single()
        val nodes = workflow.rootNodeIds.map(::project)
        return ExecutionPlan(
            flowName = graph.flowName,
            planVersion = bindings.planMetadata.planVersion,
            inputs = graph.inputs.map { input -> input.toPlan() },
            triggers = graph.triggers.map { trigger -> trigger.toPlan(workflow.name) },
            outputs = graph.outputs.map { output -> output.toPlan(::planId) },
            dependencies = bindings.planMetadata.dependencies,
            requiredCapabilities = graph.requiredCapabilities.map(CanonicalCapabilityId::value),
            targetHints = bindings.planMetadata.targetHints,
            sourceIntent = bindings.planMetadata.sourceIntent,
            loweringReport = bindings.planMetadata.loweringReport,
            assumptions = bindings.planMetadata.assumptions,
            controlRequirements = graph.controlRequirements,
            controlEvidence = graph.controlEvidence,
            controlDecision = bindings.planMetadata.controlDecision,
            nodes = nodes,
            dependencyRelations = graph.dependencyEdges.map { edge -> edge.toPlan(::planId) },
            topologyRequirements = graph.topologyRequirements
        )
    }

    private fun CanonicalGraphInput.toPlan(): PlanInput = PlanInput(
        name = name,
        type = type.value,
        required = required,
        defaultValue = defaultValue,
        defaultExpression = defaultExpression,
        choices = choices
    )

    private fun CanonicalGraphTrigger.toPlan(workflowName: String): PlanTrigger = PlanTrigger(
        id = id,
        type = kind.name,
        workflows = listOf(workflowName),
        schedule = schedule?.let { PlanSchedule(it.kind.name, it.expression, it.timezone) },
        event = event,
        params = params,
        requiredCapabilities = requiredCapabilities.map(CanonicalCapabilityId::value)
    )

    private fun CanonicalGraphOutput.toPlan(resolve: (CanonicalNodeId) -> String): PlanOutput = PlanOutput(
        name = name,
        type = type.value,
        sourceNodeId = sourceNodeId?.let(resolve)
    )

    private fun CanonicalDependencyEdge.toPlan(
        resolve: (CanonicalNodeId) -> String
    ): PlanDependencyRelation = PlanDependencyRelation(
        sourceNodeId = sourceNodeId?.let(resolve),
        targetNodeId = resolve(targetNodeId),
        kind = kind.toPlan(),
        channel = channel,
        stateLifetime = stateLifetime,
        evidence = evidence.toPlan(),
        resolution = resolution.toPlan(),
        path = path.map(resolve),
        candidates = candidates.map(resolve),
        evidenceReference = evidenceReference
    )
}
