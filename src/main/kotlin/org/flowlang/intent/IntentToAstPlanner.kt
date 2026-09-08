package org.flowlang.intent

import org.flowlang.effects.CanonicalIntentEffectAuthority
import org.flowlang.effects.SemanticEffect
import org.flowlang.controls.ControlAssessment
import org.flowlang.controls.ControlDecisionAuthority
import org.flowlang.controls.ControlRequirementScopeKind
import org.flowlang.topology.ExecutionTopologyRequirement
import org.flowlang.topology.ExecutionTopologyRequirementSource
import org.flowlang.ast.*
import org.flowlang.modules.ModuleCatalog
import org.flowlang.lowering.IntentExpressionParser
import org.flowlang.lowering.IntentLoweringAuthority
import org.flowlang.lowering.IntentValueExpressionLowering

/**
 * Lowers the high-level Standard Intent Model into canonical Flow AST.
 *
 * Semantic hardening notes:
 * - lowering is not hardcoded to a single CI/CD pipeline shape,
 * - `requires` is an ordering constraint and does not imply hidden parallel execution,
 * - standard build/test/package/runtime requests lower to semantic `standard.execute` actions,
 * - legacy runtime text is not preserved as projected work,
 * - target-neutral capabilities never invent Kubernetes, namespace or selector semantics,
 * - rollback is represented by an explicit `standard.rollback` action, not SkipNode.
 */
internal data class ValidatedIntentEvaluation(
    val report: IntentValidationReport,
    val accepted: ValidatedIntent?
)

/**
 * Unforgeable module-internal proof that one immutable IntentDocument passed the
 * exact validation report used by lowering. The private constructor prevents a
 * caller from pairing an intent with stale evidence from another source.
 */
internal class ValidatedIntent private constructor(
    val document: IntentDocument,
    val report: IntentValidationReport
) {
    companion object {
        fun evaluate(registry: ModuleCatalog, document: IntentDocument): ValidatedIntentEvaluation {
            val report = IntentCapabilityValidator(registry).validate(document)
            return ValidatedIntentEvaluation(
                report = report,
                accepted = ValidatedIntent(document, report).takeIf { report.valid }
            )
        }
    }
}

class IntentToAstPlanner(
    private val registry: ModuleCatalog,
    private val expressions: IntentExpressionParser
) {

    fun plan(intent: IntentDocument): FlowDocument {
        val evaluation = ValidatedIntent.evaluate(registry, intent)
        evaluation.report.assertValid()
        return plan(requireNotNull(evaluation.accepted))
    }

    internal fun plan(validated: ValidatedIntent): FlowDocument =
        planProgram(validated).requireSingleDocument()

    internal fun planProgram(validated: ValidatedIntent): LoweredIntentProgram {
        val intent = validated.document
        val validation = validated.report
        validation.assertValid()
        val bindings = validation.bindings.associateBy { it.stepId }
        val declaredWorkflows = intent.workflows.ifEmpty {
            listOf(IntentWorkflow(name = "main", kind = IntentWorkflowKind.CUSTOM))
        }
        val multiple = declaredWorkflows.size > 1
        val systems = LinkedHashMap<String, SystemNode>()
        intent.systems.forEach { systems[it.name] = it.toSystemNode() }

        val allSteps = declaredWorkflows.flatMap { it.steps }
        ensureImplicitSystems(intent, allSteps, bindings, systems)
        val errorHandler = buildErrorHandler(intent)
        val sourceMetadata = IntentLoweringAuthority.sourceMetadata(intent, expressions)
        val inputNodes = intent.inputs.map { it.toInputNode() }
        val triggerNodes = intent.triggers
            .map { trigger ->
                trigger.toTriggerNode().let { node ->
                    node.copy(workflows = node.workflows.sorted())
                }
            }
            .sortedBy { it.id }

        val lowered = declaredWorkflows.map { workflow ->
            val statements = lowerOrderedSteps(workflow.steps, intent, bindings)
            val imports = collectModules(statements, systems.values).sorted()
                .map { ModuleImportNode(name = it, version = "1.0") }
            val control = if (multiple) {
                workflowControlAssessment(validation.controlAssessment, workflow.name)
            } else {
                validation.controlAssessment
            }
            val topology = if (multiple) {
                workflowTopology(validation.meaning.topologyRequirements, workflow)
            } else {
                validation.meaning.topologyRequirements
            }
            val scopedTriggers = if (multiple) {
                triggerNodes.filter { workflow.name in it.workflows }
                    .map { it.copy(workflows = listOf(workflow.name)) }
            } else {
                triggerNodes
            }
            LoweredIntentWorkflow(
                name = workflow.name,
                document = FlowDocument(
                    sourceVersion = intent.intentVersion,
                    imports = imports,
                    flow = FlowNode(
                        name = if (multiple) workflow.name else intent.name,
                        input = inputNodes,
                        vars = emptyList(),
                        systems = systems.values.toList(),
                        triggers = scopedTriggers,
                        controlRequirements = control.requirements,
                        controlEvidence = control.evidence,
                        controlDecision = control.decision,
                        topologyRequirements = topology,
                        steps = statements,
                        errorHandler = errorHandler
                    ),
                    metadata = MetadataNode(
                        createdBy = "intent-to-ast-planner",
                        generatedByAI = false,
                        sourceIntent = sourceMetadata.takeUnless { multiple },
                        loweringReport = null
                    )
                )
            )
        }

        return LoweredIntentProgram(
            name = intent.name,
            inputs = inputNodes,
            triggers = triggerNodes,
            sourceIntent = sourceMetadata,
            workflows = lowered
        )
    }

    private fun workflowControlAssessment(
        assessment: ControlAssessment,
        workflowName: String
    ): ControlAssessment {
        val requirements = assessment.requirements.filter { requirement ->
            when (requirement.scope.kind) {
                ControlRequirementScopeKind.INTENT -> true
                ControlRequirementScopeKind.OPERATION -> requirement.scope.workflow == workflowName
                ControlRequirementScopeKind.PLAN_NODE -> false
            }
        }
        val ids = requirements.map { it.id }.toSet()
        val evidence = assessment.evidence.filter { it.requirementId in ids }
        return ControlDecisionAuthority.assessment(requirements, evidence)
    }

    private fun workflowTopology(
        requirements: List<ExecutionTopologyRequirement>,
        workflow: IntentWorkflow
    ): List<ExecutionTopologyRequirement> {
        val stepIds = workflow.steps.map { it.id }.toSet()
        return requirements.filter { requirement ->
            when (requirement.source) {
                ExecutionTopologyRequirementSource.CANONICAL_WORKFLOW ->
                    requirement.subject == workflow.name || requirement.evidenceReference == "intent.failure"
                ExecutionTopologyRequirementSource.CANONICAL_CAPABILITY ->
                    stepIds.any { stepId -> requirement.evidenceReference?.endsWith(".steps.$stepId") == true }
                else -> true
            }
        }
    }

    private fun ensureImplicitSystems(
        intent: IntentDocument,
        steps: List<IntentStep>,
        bindings: Map<String, IntentBindingEvidence>,
        systems: LinkedHashMap<String, SystemNode>
    ) {
        val hasUnboundSemanticWork = steps.any { step ->
            step.capability != StandardCapability.APPROVE && bindings.getValue(step.id).status == IntentBindingStatus.UNBOUND
        }
        if (hasUnboundSemanticWork || intent.failure.notify || intent.failure.rollback) {
            systems.putIfAbsent("standard", SystemNode(name = "standard", systemType = "standard"))
        }
    }

    private fun IntentSystem.toSystemNode(): SystemNode = SystemNode(
        name = name,
        systemType = IntentSystemTypeAuthority.bindingType(type),
        purpose = purpose,
        config = config.mapValues { (_, value) -> value.toExpression() }
    )

    private fun IntentTrigger.toTriggerNode(): TriggerNode = TriggerNode(
        id = id,
        triggerType = type.name,
        workflows = workflows,
        schedule = schedule?.let { ScheduleNode(kind = it.kind.name, expression = it.expression, timezone = it.timezone) },
        event = event,
        params = params.mapValues { (_, value) -> value.toExpression() }
    )

    private fun IntentInput.toInputNode(): InputNode {
        val vt = when {
            type.startsWith("option[") && type.endsWith("]") -> {
                val values = type.substringAfter("option[").substringBeforeLast("]")
                    .split(',').map { it.trim().trim('"', '\'') }.filter { it.isNotEmpty() }
                    .map { StringLiteralNode(value = it) }
                ValueTypeNode(kind = "option", values = values)
            }
            else -> ValueTypeNode(kind = type)
        }
        return InputNode(
            name = name,
            valueType = vt,
            required = required,
            default = default?.toExpression()
        )
    }

    private fun orderedSteps(steps: List<IntentStep>): List<IntentStep> {
        if (steps.none { it.requires.isNotEmpty() }) return steps
        val orderIndex = steps.mapIndexed { index, step -> step.id to index }.toMap()
        val remaining = steps.associateBy { it.id }.toMutableMap()
        val completed = linkedSetOf<String>()
        val ordered = mutableListOf<IntentStep>()
        while (remaining.isNotEmpty()) {
            val ready = remaining.values
                .filter { it.requires.all { requirement -> requirement in completed } }
                .minByOrNull { orderIndex[it.id] ?: Int.MAX_VALUE }
                ?: error(
                    "Internal planner invariant: unresolved or cyclic step dependencies must be rejected by " +
                        "IntentCapabilityValidator before planning (near '${remaining.keys.first()}')."
                )
            ordered += ready
            remaining.remove(ready.id)
            completed += ready.id
        }
        return ordered
    }

    private fun lowerOrderedSteps(
        steps: List<IntentStep>,
        intent: IntentDocument,
        bindings: Map<String, IntentBindingEvidence>
    ): List<StatementNode> {
        return orderedSteps(steps).flatMap { step ->
            lowerStep(step, intent, step.requires.distinct(), bindings.getValue(step.id))
        }
    }

    private fun lowerStep(
        step: IntentStep,
        intent: IntentDocument,
        dependencyIds: List<String>,
        binding: IntentBindingEvidence
    ): List<StatementNode> {
        val semanticEffects = CanonicalIntentEffectAuthority.effectsFor(
            step.capability,
            CanonicalIntentMeaningAuthority.semanticParameters(step)
        )
        val node: StatementNode = when (binding.status) {
            IntentBindingStatus.RESOLVED -> boundAction(step, binding, semanticEffects)
            IntentBindingStatus.UNBOUND -> semanticStatement(step, intent, semanticEffects)
            IntentBindingStatus.INVALID -> error(
                "Internal planner invariant: invalid binding '${binding.requestedAction}' for step '${step.id}' passed validation."
            )
        }
        return listOf(applyDependencies(node, dependencyIds))
    }

    private fun semanticStatement(
        step: IntentStep,
        intent: IntentDocument,
        semanticEffects: List<SemanticEffect>
    ): StatementNode =
        if (step.capability == StandardCapability.APPROVE) {
            approvalStatement(step, intent)
        } else {
            standardAction(
                step = step,
                operation = semanticOperation(step.capability),
                intent = intent,
                semanticEffects = semanticEffects
            )
        }

    private fun semanticOperation(capability: StandardCapability): String = when (capability) {
        StandardCapability.RUN_COMMAND -> "manual-runtime-action"
        StandardCapability.DATA_SYNC, StandardCapability.SYNC -> "data-sync"
        StandardCapability.DATA_TRANSFORM, StandardCapability.TRANSFORM -> "data-transform"
        else -> capability.name.lowercase().replace('_', '-')
    }

    private fun boundAction(
        step: IntentStep,
        binding: IntentBindingEvidence,
        semanticEffects: List<SemanticEffect>
    ): ActionNode {
        val moduleName = requireNotNull(binding.module)
        val actionName = requireNotNull(binding.action)
        val systemName = requireNotNull(binding.system)
        requireNotNull(binding.bindingContractId) {
            "Internal planner invariant: resolved binding '${binding.requestedAction}' has no binding contract identity."
        }
        require(binding.effectPolicy == IntentBindingEffectPolicy.PRESERVE_CANONICAL) {
            "Internal planner invariant: resolved binding '${binding.requestedAction}' does not preserve canonical effects."
        }
        return ActionNode(
            module = moduleName,
            action = actionName,
            target = ref(systemName),
            params = binding.resolvedParameters.mapValues { (_, value) -> value.toExpression() },
            result = result(step.id),
            semanticCapability = step.capability.name,
            semanticEffects = semanticEffects,
            sourceId = step.id,
            sourceDescription = step.description,
            bindingMetadata = step.params.filterKeys { it in CanonicalIntentMeaningAuthority.BINDING_METADATA_PARAMS }
                .mapValues { (_, value) -> value.toExpression() },
            declaredOutputs = step.produces
        )
    }

    private fun applyDependencies(node: StatementNode, dependencyIds: List<String>): StatementNode {
        val deps = dependencyIds.map { it.replace('-', '_') }.distinct()
        if (deps.isEmpty()) return node
        return when (node) {
            is ActionNode -> node.copy(dependsOn = deps)
            is ApproveNode -> node.copy(dependsOn = deps)
            is IfNode -> node.copy(
                then = node.then.map { applyDependencies(it, dependencyIds) },
                otherwise = node.otherwise.map { applyDependencies(it, dependencyIds) }
            )
            else -> node
        }
    }

    private fun approvalStatement(step: IntentStep, intent: IntentDocument): StatementNode {
        val approval = ApproveNode(
            mode = "manual",
            params = mapOf("message" to StringLiteralNode(value = paramText(step, "message") ?: approvalMessage(intent))),
            result = result(step.id),
            sourceId = step.id,
            sourceDescription = step.description,
            declaredOutputs = step.produces
        )
        val condition = approvalCondition(intent)
        return if (condition != null) IfNode(condition = condition, then = listOf(approval)) else approval
    }

    private fun standardAction(
        step: IntentStep,
        operation: String,
        intent: IntentDocument,
        semanticEffects: List<SemanticEffect> = CanonicalIntentEffectAuthority.effectsFor(step.capability)
    ): ActionNode {
        val params = linkedMapOf<String, ExpressionNode>(
            "operation" to StringLiteralNode(value = operation),
            "capability" to StringLiteralNode(value = step.capability.name.lowercase().replace('_', '-')),
            "description" to StringLiteralNode(value = step.description ?: "${step.capability} step ${step.id}"),
            "flow" to StringLiteralNode(value = intent.name)
        )
        CanonicalIntentMeaningAuthority.semanticParameters(step)
            .forEach { (key, value) -> params[key] = value.toExpression() }
        return ActionNode(
            module = "standard", action = if (operation == "rollback") "rollback" else "execute", target = ref("standard"),
            params = params,
            result = result(step.id),
            semanticCapability = step.capability.name,
            semanticEffects = semanticEffects,
            sourceId = step.id,
            sourceDescription = step.description,
            bindingMetadata = step.params.filterKeys { it in CanonicalIntentMeaningAuthority.BINDING_METADATA_PARAMS }
                .mapValues { (_, value) -> value.toExpression() },
            declaredOutputs = step.produces
        )
    }

    private fun buildErrorHandler(intent: IntentDocument): ErrorHandlerNode? {
        if (!intent.failure.notify && !intent.failure.rollback) return null
        val steps = mutableListOf<StatementNode>()
        if (intent.failure.rollback) {
            steps += ActionNode(
                module = "standard", action = "rollback", target = ref("standard"),
                params = mapOf(
                    "operation" to StringLiteralNode(value = "rollback"),
                    "reason" to ReferenceNode(path = listOf("error", "message"), scope = "error", safe = true),
                    "flow" to StringLiteralNode(value = intent.name)
                ),
                result = ResultBindingNode(name = "rollback_result"),
                semanticCapability = StandardCapability.ROLLBACK.name,
                semanticEffects = CanonicalIntentEffectAuthority.effectsFor(StandardCapability.ROLLBACK)
            )
        }
        if (intent.failure.notify) {
            steps += ActionNode(
                module = "standard", action = "execute", target = ref("standard"),
                params = mapOf(
                    "operation" to StringLiteralNode(value = "notify-failure"),
                    "capability" to StringLiteralNode(value = "notify"),
                    "description" to StringLiteralNode(value = "Notify about failure of ${intent.name}"),
                    "flow" to StringLiteralNode(value = intent.name),
                    "subject" to StringLiteralNode(value = "Flow failed: ${intent.name}"),
                    "body" to ReferenceNode(path = listOf("error", "message"), scope = "error", safe = true)
                ),
                semanticCapability = StandardCapability.NOTIFY.name,
                semanticEffects = CanonicalIntentEffectAuthority.effectsFor(StandardCapability.NOTIFY)
            )
        }
        return ErrorHandlerNode(steps = steps)
    }

    private fun collectModules(statements: List<StatementNode>, systems: Collection<SystemNode>): Set<String> {
        val out = linkedSetOf<String>()
        systems.forEach { system ->
            out += when (system.systemType) {
                "email" -> "notify"
                else -> system.systemType
            }
        }
        fun visit(statement: StatementNode) {
            when (statement) {
                is ActionNode -> out += statement.module
                is IfNode -> { statement.then.forEach(::visit); statement.otherwise.forEach(::visit) }
                is ForNode -> statement.body.forEach(::visit)
                is ParallelNode -> statement.branches.flatMap { it.steps }.forEach(::visit)
                is MatchNode -> {
                    statement.cases.flatMap { it.steps }.forEach(::visit)
                    statement.errorCase?.forEach(::visit)
                    statement.defaultSteps.forEach(::visit)
                }
                is RetryNode -> statement.steps.forEach(::visit)
                is TryNode -> { statement.steps.forEach(::visit); statement.errorHandler.steps.forEach(::visit) }
                is ErrorHandlerNode -> statement.steps.forEach(::visit)
                else -> Unit
            }
        }
        statements.forEach(::visit)
        return out
    }

    private fun result(id: String) = ResultBindingNode(name = id.replace('-', '_'))
    private fun ref(name: String) = ReferenceNode(path = listOf(name))
    private fun paramText(step: IntentStep, key: String): String? = step.params[key].asTextOrNull()

    private fun IntentValue.toExpression(): ExpressionNode = IntentValueExpressionLowering.lower(this, expressions)

    private fun approvalPolicy(intent: IntentDocument): IntentPolicy? = intent.policies.firstOrNull { it.type == IntentPolicyType.APPROVAL }
    private fun approvalMessage(intent: IntentDocument): String = approvalPolicy(intent)?.message ?: "Approval required for ${intent.name}"
    private fun approvalCondition(intent: IntentDocument): ExpressionNode? = approvalPolicy(intent)?.condition?.let { parseCondition(it) }
    private fun parseCondition(raw: String): ExpressionNode = expressions.parse(raw)
}
