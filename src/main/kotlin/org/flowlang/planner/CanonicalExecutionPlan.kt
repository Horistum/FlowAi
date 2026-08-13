package org.flowlang.planner

import org.flowlang.effects.SemanticEffect
import org.flowlang.controls.ControlDecision
import org.flowlang.controls.ControlDecisionStatus
import org.flowlang.controls.ControlEvidence
import org.flowlang.controls.ControlRequirement
import org.flowlang.topology.ExecutionTopologyRequirement
import org.flowlang.lowering.IntentLoweringReport
import org.flowlang.lowering.IntentSourceMetadata

/**
 * Canonical public Execution Plan view.
 *
 * The internal planner keeps historical node class names for compatibility with
 * existing generators. This canonical view is the stable adapter/runtime
 * contract: node kinds are lowercase and target-neutral.
 */
data class CanonicalExecutionPlan(
    val flowName: String,
    val planVersion: String,
    val inputs: List<PlanInput> = emptyList(),
    val triggers: List<PlanTrigger> = emptyList(),
    val outputs: List<PlanOutput> = emptyList(),
    val dependencies: List<String> = emptyList(),
    val requiredCapabilities: List<String> = emptyList(),
    val targetHints: Map<String, String> = emptyMap(),
    val sourceIntent: IntentSourceMetadata? = null,
    val loweringReport: IntentLoweringReport? = null,
    val assumptions: List<PlanAssumption> = emptyList(),
    val controlRequirements: List<ControlRequirement> = emptyList(),
    val controlEvidence: List<ControlEvidence> = emptyList(),
    val controlDecision: ControlDecision = ControlDecision(ControlDecisionStatus.ALLOWED),
    val topologyRequirements: List<ExecutionTopologyRequirement> = emptyList(),
    val nodes: List<CanonicalPlanNode> = emptyList(),
    val dependencyRelations: List<PlanDependencyRelation> = emptyList()
)

data class CanonicalPlanNode(
    val id: String,
    val kind: String,
    val module: String? = null,
    val action: String? = null,
    val target: String? = null,
    val resultName: String? = null,
    val dependencies: List<String> = emptyList(),
    val inputs: Map<String, String> = emptyMap(),
    val outputs: List<String> = emptyList(),
    val semanticCapability: String? = null,
    val sourceId: String? = null,
    val sourceDescription: String? = null,
    val bindingMetadata: Map<String, String> = emptyMap(),
    val effectModel: List<SemanticEffect> = emptyList(),
    val effects: List<String> = emptyList(),
    val safety: String? = null,
    val destructive: Boolean = false,
    val requiredCapabilities: List<String> = emptyList(),
    val targetHints: Map<String, String> = emptyMap(),
    val assumptions: List<String> = emptyList(),
    val condition: String? = null,
    val then: List<CanonicalPlanNode> = emptyList(),
    val otherwise: List<CanonicalPlanNode> = emptyList(),
    val item: String? = null,
    val source: String? = null,
    val failFast: Boolean? = null,
    val branches: List<CanonicalPlanBranch> = emptyList(),
    val cases: List<CanonicalMatchCase> = emptyList(),
    val errorCase: List<CanonicalPlanNode> = emptyList(),
    val defaultSteps: List<CanonicalPlanNode> = emptyList(),
    val max: Int? = null,
    val delay: String? = null,
    val backoff: String? = null,
    val body: List<CanonicalPlanNode> = emptyList(),
    val errorHandler: List<CanonicalPlanNode> = emptyList(),
    val detail: String? = null
)

data class CanonicalPlanBranch(
    val name: String? = null,
    val steps: List<CanonicalPlanNode> = emptyList()
)

data class CanonicalMatchCase(
    val condition: String,
    val steps: List<CanonicalPlanNode> = emptyList()
)

object ExecutionPlanCanonicalizer {
    fun canonicalize(plan: ExecutionPlan): CanonicalExecutionPlan =
        CanonicalExecutionPlan(
            flowName = plan.flowName,
            planVersion = plan.planVersion,
            inputs = plan.inputs,
            triggers = plan.triggers,
            outputs = plan.outputs,
            dependencies = plan.dependencies,
            requiredCapabilities = plan.requiredCapabilities,
            targetHints = plan.targetHints,
            sourceIntent = plan.sourceIntent,
            loweringReport = plan.loweringReport,
            assumptions = plan.assumptions,
            controlRequirements = plan.controlRequirements,
            controlEvidence = plan.controlEvidence,
            controlDecision = plan.controlDecision,
            topologyRequirements = plan.topologyRequirements,
            nodes = plan.nodes.map { canonicalizeNode(it) },
            dependencyRelations = plan.dependencyRelations
        )

    private fun canonicalizeNode(node: PlanNode): CanonicalPlanNode = when (node) {
        is TaskNode -> CanonicalPlanNode(
            id = node.id,
            kind = CanonicalExecutionPlanSemanticsAuthority.kindFor(node).wireValue,
            module = node.module,
            action = node.action,
            target = node.target,
            resultName = node.resultName,
            dependencies = node.dependencies,
            inputs = node.inputs.ifEmpty { node.params },
            outputs = node.outputs,
            semanticCapability = node.semanticCapability,
            sourceId = node.sourceId,
            sourceDescription = node.sourceDescription,
            bindingMetadata = node.bindingMetadata,
            effectModel = node.effectModel,
            effects = node.effects,
            safety = node.safety,
            destructive = node.destructive,
            requiredCapabilities = node.requiredCapabilities,
            targetHints = node.targetHints,
            assumptions = node.assumptions
        )
        is ApprovalNode -> CanonicalPlanNode(
            id = node.id,
            kind = CanonicalPlanNodeKind.APPROVAL.wireValue,
            resultName = node.resultName,
            sourceId = node.sourceId,
            sourceDescription = node.sourceDescription,
            dependencies = node.dependencies,
            outputs = node.outputs,
            inputs = mapOfNotNull("message" to node.message, "mode" to node.mode),
            safety = node.safety,
            requiredCapabilities = node.requiredCapabilities
        )
        is ConditionNode -> CanonicalPlanNode(
            id = node.id,
            kind = CanonicalPlanNodeKind.CONDITION.wireValue,
            condition = node.condition,
            then = node.then.map { canonicalizeNode(it) },
            otherwise = node.otherwise.map { canonicalizeNode(it) },
            requiredCapabilities = listOf("condition.evaluate")
        )
        is LoopNode -> CanonicalPlanNode(
            id = node.id,
            kind = CanonicalPlanNodeKind.LOOP.wireValue,
            item = node.item,
            source = node.source,
            body = node.body.map { canonicalizeNode(it) },
            requiredCapabilities = listOf("loop.dynamic")
        )
        is ParallelGroupNode -> CanonicalPlanNode(
            id = node.id,
            kind = CanonicalPlanNodeKind.PARALLEL.wireValue,
            failFast = node.failFast,
            branches = node.branches.map { CanonicalPlanBranch(it.name, it.steps.map { step -> canonicalizeNode(step) }) },
            requiredCapabilities = listOf("parallel.dag")
        )
        is MatchPlanNode -> CanonicalPlanNode(
            id = node.id,
            kind = CanonicalPlanNodeKind.MATCH.wireValue,
            source = node.source,
            cases = node.cases.map { CanonicalMatchCase(it.condition, it.steps.map { step -> canonicalizeNode(step) }) },
            errorCase = node.errorCase.map { canonicalizeNode(it) },
            defaultSteps = node.defaultSteps.map { canonicalizeNode(it) },
            requiredCapabilities = listOf("match.basic")
        )
        is RetryGroupNode -> CanonicalPlanNode(
            id = node.id,
            kind = CanonicalPlanNodeKind.RETRY.wireValue,
            max = node.max,
            delay = node.delay,
            backoff = node.backoff,
            body = node.body.map { canonicalizeNode(it) },
            requiredCapabilities = listOf("retry.task")
        )
        is TryPlanNode -> CanonicalPlanNode(
            id = node.id,
            kind = CanonicalPlanNodeKind.TRY.wireValue,
            body = node.body.map { canonicalizeNode(it) },
            errorHandler = node.errorHandler.map { canonicalizeNode(it) },
            requiredCapabilities = listOf("errorHandlers.finally")
        )
        is DataOpNode -> CanonicalPlanNode(
            id = node.id,
            kind = dataOperationKind(node),
            target = node.target,
            detail = node.detail,
            semanticCapability = node.semanticCapability,
            effectModel = node.effectModel,
            effects = node.effects
        )
        is ControlNode -> CanonicalPlanNode(
            id = node.id,
            kind = controlKind(node),
            detail = node.detail
        )
    }

    private fun dataOperationKind(node: DataOpNode): String = when (node.kind) {
        "Transform" -> CanonicalPlanNodeKind.TRANSFORM.wireValue
        "Validate" -> CanonicalPlanNodeKind.VALIDATE.wireValue
        "Aggregate" -> CanonicalPlanNodeKind.AGGREGATE.wireValue
        else -> error("Unsupported canonical data-operation node kind '${node.kind}'.")
    }

    private fun controlKind(node: ControlNode): String = when (node.kind) {
        "Fail" -> CanonicalPlanNodeKind.FAIL.wireValue
        "Skip" -> CanonicalPlanNodeKind.SKIP.wireValue
        "Set" -> CanonicalPlanNodeKind.SET.wireValue
        "Expect" -> CanonicalPlanNodeKind.EXPECT.wireValue
        else -> error("Unsupported canonical control node kind '${node.kind}'.")
    }

    private fun mapOfNotNull(vararg pairs: Pair<String, String?>): Map<String, String> =
        pairs.mapNotNull { (key, value) -> value?.let { key to it } }.toMap()
}
