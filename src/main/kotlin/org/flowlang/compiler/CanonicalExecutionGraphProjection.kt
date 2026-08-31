package org.flowlang.compiler

import org.flowlang.planner.ApprovalNode
import org.flowlang.planner.ConditionNode
import org.flowlang.planner.ControlNode
import org.flowlang.planner.DataOpNode
import org.flowlang.planner.CanonicalExecutionPlan
import org.flowlang.planner.CanonicalExecutionPlanSemanticsAuthority
import org.flowlang.planner.CanonicalMatchCase as CanonicalPlanMatchCase
import org.flowlang.planner.CanonicalPlanBranch
import org.flowlang.planner.CanonicalPlanNode
import org.flowlang.planner.CanonicalPlanNodeKind
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


    fun toCanonicalExecutionPlan(build: CanonicalExecutionGraphBuild): CanonicalExecutionPlan =
        toCanonicalExecutionPlan(build.graph, build.bindings)

    /**
     * Projects the stable public canonical view directly from the authoritative graph.
     *
     * ExecutionPlan remains a compatibility view, but it is not an input to this projection.
     * Both public plan representations therefore share one semantic authority and one binding
     * envelope instead of forming a second plan-to-plan semantic transformation chain.
     */
    fun toCanonicalExecutionPlan(
        graph: CanonicalExecutionGraph,
        bindings: CanonicalExecutionBindingSet
    ): CanonicalExecutionPlan {
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

        fun project(id: CanonicalNodeId): CanonicalPlanNode {
            val node = requireNotNull(nodeById[id]) { "Canonical graph references missing node '$id'." }
            val projection = metadata(id)
            val projectionId = projection.planNodeId
            return when (node) {
                is CanonicalTaskNode -> {
                    val binding = requireNotNull(taskBindingById[id]) {
                        "Canonical task '$id' has no implementation binding."
                    }
                    CanonicalPlanNode(
                        id = projectionId,
                        kind = CanonicalExecutionPlanSemanticsAuthority
                            .kindForSemanticCapability(node.semantics.capability?.value)
                            .wireValue,
                        module = binding.module,
                        action = binding.action,
                        target = binding.target,
                        resultName = node.resultName,
                        dependencies = dependenciesFor(id),
                        inputs = when (binding.parameterProjectionMode) {
                            TaskParameterProjectionMode.EMPTY -> emptyMap()
                            TaskParameterProjectionMode.INPUTS_ONLY,
                            TaskParameterProjectionMode.PARAMS_ONLY,
                            TaskParameterProjectionMode.BOTH -> node.semantics.parameters
                        },
                        outputs = node.semantics.outputs,
                        semanticCapability = node.semantics.capability?.value,
                        sourceId = projection.sourceId,
                        sourceDescription = projection.sourceDescription,
                        bindingMetadata = binding.bindingMetadata,
                        effectModel = node.semantics.effects,
                        effects = projection.legacyEffects,
                        safety = node.semantics.safety.rule,
                        destructive = node.semantics.safety.destructive,
                        requiredCapabilities = node.semantics.requiredCapabilities.map(CanonicalCapabilityId::value),
                        targetHints = binding.targetHints,
                        assumptions = binding.assumptions
                    )
                }
                is CanonicalApprovalNode -> CanonicalPlanNode(
                    id = projectionId,
                    kind = CanonicalPlanNodeKind.APPROVAL.wireValue,
                    resultName = node.resultName,
                    dependencies = dependenciesFor(id),
                    inputs = mapOfNotNull("message" to node.message, "mode" to node.mode),
                    outputs = node.semantics.outputs,
                    sourceId = projection.sourceId,
                    sourceDescription = projection.sourceDescription,
                    safety = node.semantics.safety.rule,
                    requiredCapabilities = node.semantics.requiredCapabilities.map(CanonicalCapabilityId::value)
                )
                is CanonicalConditionNode -> CanonicalPlanNode(
                    id = projectionId,
                    kind = CanonicalPlanNodeKind.CONDITION.wireValue,
                    condition = node.condition,
                    then = node.thenNodeIds.map(::project),
                    otherwise = node.otherwiseNodeIds.map(::project),
                    requiredCapabilities = node.semantics.requiredCapabilities.map(CanonicalCapabilityId::value)
                )
                is CanonicalLoopNode -> CanonicalPlanNode(
                    id = projectionId,
                    kind = CanonicalPlanNodeKind.LOOP.wireValue,
                    item = node.item,
                    source = node.source,
                    body = node.bodyNodeIds.map(::project),
                    requiredCapabilities = node.semantics.requiredCapabilities.map(CanonicalCapabilityId::value)
                )
                is CanonicalParallelNode -> CanonicalPlanNode(
                    id = projectionId,
                    kind = CanonicalPlanNodeKind.PARALLEL.wireValue,
                    failFast = node.failFast,
                    branches = node.branches.map { branch ->
                        CanonicalPlanBranch(branch.name, branch.nodeIds.map(::project))
                    },
                    requiredCapabilities = node.semantics.requiredCapabilities.map(CanonicalCapabilityId::value)
                )
                is CanonicalMatchNode -> CanonicalPlanNode(
                    id = projectionId,
                    kind = CanonicalPlanNodeKind.MATCH.wireValue,
                    source = node.source,
                    cases = node.cases.map { case ->
                        CanonicalPlanMatchCase(case.condition, case.nodeIds.map(::project))
                    },
                    errorCase = node.errorNodeIds.map(::project),
                    defaultSteps = node.defaultNodeIds.map(::project),
                    requiredCapabilities = node.semantics.requiredCapabilities.map(CanonicalCapabilityId::value)
                )
                is CanonicalRetryNode -> CanonicalPlanNode(
                    id = projectionId,
                    kind = CanonicalPlanNodeKind.RETRY.wireValue,
                    max = node.max,
                    delay = node.delay,
                    backoff = node.backoff,
                    body = node.bodyNodeIds.map(::project),
                    requiredCapabilities = node.semantics.requiredCapabilities.map(CanonicalCapabilityId::value)
                )
                is CanonicalTryNode -> CanonicalPlanNode(
                    id = projectionId,
                    kind = CanonicalPlanNodeKind.TRY.wireValue,
                    body = node.bodyNodeIds.map(::project),
                    errorHandler = node.errorHandlerNodeIds.map(::project),
                    requiredCapabilities = node.semantics.requiredCapabilities.map(CanonicalCapabilityId::value)
                )
                is CanonicalDataOperationNode -> CanonicalPlanNode(
                    id = projectionId,
                    kind = when (node.operation) {
                        CanonicalDataOperationKind.TRANSFORM -> CanonicalPlanNodeKind.TRANSFORM
                        CanonicalDataOperationKind.VALIDATE -> CanonicalPlanNodeKind.VALIDATE
                        CanonicalDataOperationKind.AGGREGATE -> CanonicalPlanNodeKind.AGGREGATE
                    }.wireValue,
                    target = node.target,
                    detail = node.detail,
                    semanticCapability = node.semantics.capability?.value,
                    effectModel = node.semantics.effects,
                    effects = projection.legacyEffects
                )
                is CanonicalControlOperationNode -> CanonicalPlanNode(
                    id = projectionId,
                    kind = when (node.operation) {
                        CanonicalControlOperationKind.FAIL -> CanonicalPlanNodeKind.FAIL
                        CanonicalControlOperationKind.SKIP -> CanonicalPlanNodeKind.SKIP
                        CanonicalControlOperationKind.SET -> CanonicalPlanNodeKind.SET
                        CanonicalControlOperationKind.EXPECT -> CanonicalPlanNodeKind.EXPECT
                    }.wireValue,
                    detail = node.detail
                )
            }
        }

        val workflow = graph.workflows.single()
        return CanonicalExecutionPlan(
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
            topologyRequirements = graph.topologyRequirements,
            nodes = workflow.rootNodeIds.map(::project),
            dependencyRelations = graph.dependencyEdges.map { edge -> edge.toPlan(::planId) }
        )
    }

    private fun mapOfNotNull(vararg pairs: Pair<String, String?>): Map<String, String> =
        pairs.mapNotNull { (key, value) -> value?.let { key to it } }.toMap()

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
