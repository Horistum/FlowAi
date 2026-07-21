package org.flowlang.intent

import org.flowlang.effects.CanonicalIntentEffectAuthority
import org.flowlang.effects.SemanticEffect
import org.flowlang.ast.*
import org.flowlang.modules.ModuleRegistry
import org.flowlang.parser.ExpressionParser

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
class IntentToAstPlanner(private val registry: ModuleRegistry = ModuleRegistry()) {

    fun plan(intent: IntentDocument): FlowDocument {
        val validation = IntentCapabilityValidator(registry).validate(intent)
        validation.assertValid()
        val bindings = validation.bindings.associateBy { it.stepId }
        val systems = LinkedHashMap<String, SystemNode>()
        intent.systems.forEach { systems[it.name] = it.toSystemNode() }

        val allSteps = intent.workflows.flatMap { it.steps }
        ensureImplicitSystems(intent, allSteps, bindings, systems)

        val statements = lowerOrderedSteps(allSteps, intent, bindings)
        val errorHandler = buildErrorHandler(intent)
        val imports = collectModules(statements, systems.values).sorted().map { ModuleImportNode(name = it, version = "1.0") }

        return FlowDocument(
            sourceVersion = intent.intentVersion,
            imports = imports,
            flow = FlowNode(
                name = intent.name,
                input = intent.inputs.map { it.toInputNode() },
                vars = emptyList(),
                systems = systems.values.toList(),
                triggers = intent.triggers.map { it.toTriggerNode() },
                controlRequirements = validation.controlAssessment.requirements,
                controlEvidence = validation.controlAssessment.evidence,
                controlDecision = validation.controlAssessment.decision,
                steps = statements,
                errorHandler = errorHandler
            ),
            metadata = MetadataNode(createdBy = "intent-to-ast-planner", generatedByAI = false)
        )
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

    private fun IntentSystem.toSystemNode(): SystemNode {
        val normalizedType = when (type) {
            "dockerRegistry", "containerRegistry" -> "docker"
            "notification", "email" -> "notify"
            else -> type
        }
        return SystemNode(
            name = name,
            systemType = normalizedType,
            config = config.mapValues { (_, value) -> value.toExpression() }
        )
    }


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

    /**
     * Returns a deterministic topological order without introducing implicit parallelism.
     *
     * `requires` is an ordering constraint, not permission to parallelize every other
     * independent step. Flow has an explicit ParallelNode for true parallel intent;
     * lowering from the Standard Intent Model must therefore keep authored automation
     * safe and ordered unless parallelism is expressed in the source model itself.
     */
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
        val ordered = orderedSteps(steps)
        val out = mutableListOf<StatementNode>()
        var previousStepId: String? = null
        ordered.forEach { step ->
            val dependencies = (step.requires + listOfNotNull(previousStepId)).distinct()
            out += lowerStep(step, intent, dependencies, bindings.getValue(step.id))
            previousStepId = step.id
        }
        return out
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
                dropBlockedParams = step.capability in blockedRuntimeCapabilities,
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
        val contract = requireNotNull(registry.findAction(moduleName, actionName)) {
            "Internal planner invariant: resolved binding '${binding.requestedAction}' has no module action contract."
        }
        val supplied = step.params.filterKeys { it !in CanonicalIntentMeaningAuthority.BINDING_METADATA_PARAMS }
        val params = linkedMapOf<String, ExpressionNode>()
        contract.input.forEach { (name, field) ->
            val value = supplied[name]
            when {
                value != null -> params[name] = value.toExpression()
                field.defaultValue != null -> params[name] = field.defaultValue.toExpressionNode()
            }
        }
        if (contract.additionalParams) {
            supplied.filterKeys { it !in params }.forEach { (name, value) -> params[name] = value.toExpression() }
        }
        return ActionNode(
            module = moduleName,
            action = actionName,
            target = ref(systemName),
            params = params,
            result = result(step.id),
            semanticCapability = step.capability.name,
            semanticEffects = semanticEffects
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
            result = result(step.id)
        )
        val condition = approvalCondition(intent)
        return if (condition != null) IfNode(condition = condition, then = listOf(approval)) else approval
    }

    private fun standardAction(
        step: IntentStep,
        operation: String,
        intent: IntentDocument,
        dropBlockedParams: Boolean = false,
        semanticEffects: List<SemanticEffect> = CanonicalIntentEffectAuthority.effectsFor(step.capability)
    ): ActionNode {
        val params = linkedMapOf<String, ExpressionNode>(
            "operation" to StringLiteralNode(value = operation),
            "capability" to StringLiteralNode(value = step.capability.name.lowercase().replace('_', '-')),
            "description" to StringLiteralNode(value = step.description ?: "${step.capability} step ${step.id}"),
            "flow" to StringLiteralNode(value = intent.name)
        )
        CanonicalIntentMeaningAuthority.semanticParameters(step)
            .filterKeys { key -> !dropBlockedParams || key !in blockedParamNames }
            .forEach { (key, value) -> params[key] = value.toExpression() }
        if (dropBlockedParams && step.params.keys.any { it in blockedParamNames }) {
            params["projection"] = StringLiteralNode(value = "notes-driven-materialization-required")
        }
        return ActionNode(
            module = "standard", action = if (operation == "rollback") "rollback" else "execute", target = ref("standard"),
            params = params,
            result = result(step.id),
            semanticCapability = step.capability.name,
            semanticEffects = semanticEffects
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

    private fun IntentValue.toExpression(): ExpressionNode = when (this) {
        is IntentString -> stringToExpression(value)
        is IntentNumber -> NumberLiteralNode(value = value, isInteger = isInteger)
        is IntentBoolean -> BooleanLiteralNode(value = value)
        is IntentNull -> NullLiteralNode()
        is IntentSecretRef -> SecretRefNode(name = name)
        is IntentRef -> ReferenceNode(path = path)
        is IntentExpression -> parseCondition(source)
        is IntentList -> ListLiteralNode(items = items.map { it.toExpression() })
        is IntentObject -> MapLiteralNode(entries = fields.mapValues { it.value.toExpression() })
    }

    private fun Any.toExpressionNode(): ExpressionNode = when (this) {
        is String -> StringLiteralNode(value = this)
        is Boolean -> BooleanLiteralNode(value = this)
        is Int -> NumberLiteralNode(value = toDouble(), isInteger = true)
        is Long -> NumberLiteralNode(value = toDouble(), isInteger = true)
        is Float -> NumberLiteralNode(value = toDouble(), isInteger = false)
        is Double -> NumberLiteralNode(value = this, isInteger = this % 1.0 == 0.0)
        is Number -> NumberLiteralNode(value = toDouble(), isInteger = false)
        else -> StringLiteralNode(value = toString())
    }


    private fun stringToExpression(value: String): ExpressionNode {
        val interpolationStart = "\${"
        if (!value.contains(interpolationStart)) return StringLiteralNode(value = value)
        val parts = mutableListOf<ExpressionNode>()
        var pos = 0
        val regex = Regex(Regex.escape(interpolationStart) + "([^}]+)}")
        regex.findAll(value).forEach { match ->
            if (match.range.first > pos) parts += StringLiteralNode(value = value.substring(pos, match.range.first))
            val exprSource = match.groupValues[1].trim()
            parts += parseCondition(exprSource)
            pos = match.range.last + 1
        }
        if (pos < value.length) parts += StringLiteralNode(value = value.substring(pos))
        return if (parts.size == 1) parts.single() else TemplateStringNode(parts = parts)
    }

    private fun approvalPolicy(intent: IntentDocument): IntentPolicy? = intent.policies.firstOrNull { it.type == IntentPolicyType.APPROVAL }
    private fun approvalMessage(intent: IntentDocument): String = approvalPolicy(intent)?.message ?: "Approval required for ${intent.name}"
    private fun approvalCondition(intent: IntentDocument): ExpressionNode? = approvalPolicy(intent)?.condition?.let { parseCondition(it) }
    private fun parseCondition(raw: String): ExpressionNode = ExpressionParser.parseSource(raw)

    companion object {
        private val blockedParamNames = setOf("command")
        private val blockedRuntimeCapabilities = setOf(
            StandardCapability.BUILD,
            StandardCapability.TEST,
            StandardCapability.PACKAGE,
            StandardCapability.RUN_COMMAND
        )
    }
}
