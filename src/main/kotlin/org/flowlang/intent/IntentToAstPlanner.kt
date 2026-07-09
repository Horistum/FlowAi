package org.flowlang.intent

import org.flowlang.ast.*
import org.flowlang.parser.ExpressionParser
import org.flowlang.modules.ModuleRegistry

/**
 * Lowers the high-level Standard Intent Model into canonical Flow AST.
 *
 * Semantic hardening notes:
 * - lowering is not hardcoded to a single CI/CD pipeline shape,
 * - `requires` is an ordering constraint and does not imply hidden parallel execution,
 * - standard build/test/package/runtime requests lower to semantic `standard.execute` actions,
 * - legacy runtime text is not preserved as projected work,
 * - deploy/image defaults are resolved through explicit convention helpers and remain visible in the design report,
 * - rollback is represented by an explicit `standard.rollback` action, not SkipNode.
 */
class IntentToAstPlanner(private val registry: ModuleRegistry = ModuleRegistry()) {

    fun plan(intent: IntentDocument): FlowDocument {
        IntentCapabilityValidator(registry).validate(intent).assertValid()
        val systems = LinkedHashMap<String, SystemNode>()
        intent.systems.forEach { systems[it.name] = it.toSystemNode() }

        val allSteps = intent.workflows.flatMap { it.steps }
        ensureImplicitSystems(intent, allSteps, systems)

        val statements = lowerOrderedSteps(allSteps, intent)
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
                steps = statements,
                errorHandler = errorHandler
            ),
            metadata = MetadataNode(createdBy = "intent-to-ast-planner", generatedByAI = false)
        )
    }

    private fun ensureImplicitSystems(intent: IntentDocument, steps: List<IntentStep>, systems: LinkedHashMap<String, SystemNode>) {
        if (steps.any { it.capability in dockerCapabilities }) {
            systems.putIfAbsent("registry", SystemNode(name = "registry", systemType = "docker"))
        }
        if (steps.any { it.capability in kubernetesCapabilities }) {
            systems.putIfAbsent("cluster", SystemNode(name = "cluster", systemType = "kubernetes"))
        }
        if (intent.failure.notify || steps.any { it.capability == StandardCapability.NOTIFY }) {
            systems.putIfAbsent("notifier", SystemNode(
                name = "notifier",
                systemType = "notify",
                config = mapOf("channel" to IdentifierLiteralNode(value = "email"))
            ))
        }
        if (steps.any { it.capability in standardCapabilities } || intent.failure.rollback) {
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
                .filter { it.requires.all { r -> r in completed } }
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

    private fun lowerOrderedSteps(steps: List<IntentStep>, intent: IntentDocument): List<StatementNode> {
        val ordered = orderedSteps(steps)
        val out = mutableListOf<StatementNode>()
        var previousStepId: String? = null
        ordered.forEach { step ->
            val dependencies = (step.requires + listOfNotNull(previousStepId)).distinct()
            out += lowerStep(step, intent, dependencies)
            previousStepId = step.id
        }
        return out
    }

    private fun lowerStep(step: IntentStep, intent: IntentDocument, dependencyIds: List<String>): List<StatementNode> {
        val node: StatementNode = when (step.capability) {
            StandardCapability.CHECKOUT -> ActionNode(
                module = "git", action = "checkout", target = ref(systemFor(step, "source")),
                params = mapNotNullValues(
                    "url" to optionalValue(paramText(step, "url")),
                    "branch" to optionalValue(paramText(step, "branch") ?: "main")
                ),
                result = result(step.id)
            )
            StandardCapability.TEST -> standardAction(step, "test", intent, dropBlockedParams = true)
            StandardCapability.BUILD -> standardAction(step, "build", intent, dropBlockedParams = true)
            StandardCapability.PACKAGE -> standardAction(step, "package", intent, dropBlockedParams = true)
            StandardCapability.RUN_COMMAND -> standardAction(step, "manual-runtime-action", intent, dropBlockedParams = true)
            StandardCapability.BUILD_IMAGE -> ActionNode(
                module = "docker", action = "build", target = ref(systemFor(step, "registry")),
                params = mapNotNullValues(
                    "image" to imageExpression(intent, step),
                    "path" to optionalValue(paramText(step, "path") ?: "."),
                    "dockerfile" to optionalValue(paramText(step, "dockerfile")),
                    "push" to optionalBool(paramText(step, "push"))
                ),
                result = result(step.id)
            )
            StandardCapability.PUSH_IMAGE -> ActionNode(
                module = "docker", action = "push", target = ref(systemFor(step, "registry")),
                params = mapOf("image" to imageOrDependency(intent, step)),
                result = result(step.id)
            )
            StandardCapability.APPROVE -> approvalStatement(step, intent)
            StandardCapability.DEPLOY -> deployStatement(step, intent)
            StandardCapability.VERIFY -> verifyStatement(step, intent)
            StandardCapability.ROLLBACK -> standardAction(step, "rollback", intent, requiresHandler = false)
            StandardCapability.NOTIFY -> ActionNode(
                module = "notify", action = "send", target = ref(systemFor(step, "notifier")),
                params = mapNotNullValues(
                    "subject" to optionalValue(paramText(step, "subject") ?: "Flow notification"),
                    "body" to optionalValue(paramText(step, "body") ?: "Flow ${intent.name} notification"),
                    "to" to optionalValue(paramText(step, "to")),
                    "channel" to optionalValue(paramText(step, "channel"))
                ),
                result = result(step.id)
            )
            StandardCapability.CALL_API -> ActionNode(
                module = "rest", action = "call", target = ref(systemFor(step, "api")),
                params = mapNotNullValues(
                    "method" to optionalValue(paramText(step, "method") ?: "GET"),
                    "path" to optionalValue(paramText(step, "path") ?: error("CALL_API step '${step.id}' requires params.path")),
                    "body" to optionalValue(paramText(step, "body"))
                ),
                result = result(step.id)
            )
            StandardCapability.SYNC,
            StandardCapability.DATA_SYNC -> standardAction(step, "data-sync", intent)
            StandardCapability.TRANSFORM,
            StandardCapability.DATA_TRANSFORM -> standardAction(step, "data-transform", intent)
            StandardCapability.VALIDATE -> standardAction(step, "validate", intent)
            StandardCapability.BACKUP -> standardAction(step, "backup", intent)
            StandardCapability.RESTORE -> standardAction(step, "restore", intent)
            StandardCapability.CLEANUP -> standardAction(step, "cleanup", intent)
            StandardCapability.PROVISION -> standardAction(step, "provision", intent)
            StandardCapability.DEPROVISION -> standardAction(step, "deprovision", intent)
            StandardCapability.SCHEDULE -> standardAction(step, "schedule", intent)
            StandardCapability.DATABASE_MIGRATE -> standardAction(step, "database-migrate", intent)
            StandardCapability.CERTIFICATE_RENEW -> standardAction(step, "certificate-renew", intent)
            StandardCapability.KUBERNETES_MAINTENANCE -> standardAction(step, "kubernetes-maintenance", intent)
            StandardCapability.RUNBOOK -> standardAction(step, "runbook", intent)
            StandardCapability.INCIDENT -> standardAction(step, "incident", intent)
            StandardCapability.SECRET_ROTATE -> standardAction(step, "secret-rotate", intent)
            StandardCapability.POLICY_CHECK -> standardAction(step, "policy-check", intent)
            StandardCapability.CUSTOM -> customAction(step, intent)
        }
        return listOf(applyDependencies(node, dependencyIds))
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

    private fun deployStatement(step: IntentStep, intent: IntentDocument): StatementNode {
        if ((paramText(step, "engine") ?: paramText(step, "tool"))?.equals("argocd", ignoreCase = true) == true) {
            return ActionNode(
                module = "argocd", action = "sync", target = ref(systemFor(step, "argo")),
                params = mapNotNullValues(
                    "app" to optionalValue(paramText(step, "app") ?: intent.name),
                    "wait" to optionalBool(paramText(step, "wait") ?: "true"),
                    "timeout" to optionalValue(paramText(step, "timeout") ?: "5m")
                ),
                result = result(step.id)
            )
        }
        return ActionNode(
            module = "kubernetes", action = "deploy", target = ref(systemFor(step, "cluster")),
            params = mapNotNullValues(
                "app" to optionalValue(paramText(step, "app") ?: paramText(step, "name") ?: intent.name),
                "namespace" to namespaceExpression(intent, step),
                "image" to imageOrDependency(intent, step),
                "manifest" to optionalValue(paramText(step, "manifest"))
            ),
            result = result(step.id)
        )
    }

    private fun verifyStatement(step: IntentStep, intent: IntentDocument): StatementNode = ActionNode(
        module = "kubernetes", action = "get", target = ref(systemFor(step, "cluster")),
        params = mapNotNullValues(
            "resource" to optionalValue(paramText(step, "resource") ?: "pods"),
            "namespace" to namespaceExpression(intent, step),
            "selector" to optionalValue(paramText(step, "selector") ?: "app=${intent.name}")
        ),
        result = result(step.id),
        handler = ResultHandlerNode(rules = listOf(ExpectNode(expressions = listOf(refImplicit("ok")))))
    )

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
        requiresHandler: Boolean = false,
        dropBlockedParams: Boolean = false
    ): ActionNode {
        val params = linkedMapOf<String, ExpressionNode>(
            "operation" to StringLiteralNode(value = operation),
            "capability" to StringLiteralNode(value = step.capability.name.lowercase().replace('_', '-')),
            "description" to StringLiteralNode(value = step.description ?: "${step.capability} step ${step.id}"),
            "flow" to StringLiteralNode(value = intent.name)
        )
        step.params
            .filterKeys { key -> !dropBlockedParams || key !in blockedParamNames }
            .forEach { (k, v) -> params[k] = v.toExpression() }
        if (dropBlockedParams && step.params.keys.any { it in blockedParamNames }) {
            params["projection"] = StringLiteralNode(value = "notes-driven-materialization-required")
        }
        return ActionNode(
            module = "standard", action = if (operation == "rollback") "rollback" else "execute", target = ref("standard"),
            params = params,
            result = result(step.id),
            handler = if (requiresHandler) expectOkAndCodeZero() else null
        )
    }

    private fun customAction(step: IntentStep, intent: IntentDocument): ActionNode {
        val uses = step.uses ?: return standardAction(step, "custom", intent)
        val parts = uses.split('.', limit = 2)
        val module = parts.getOrNull(0) ?: "standard"
        val action = parts.getOrNull(1) ?: "execute"
        return ActionNode(
            module = module,
            action = action,
            target = ref(paramText(step, "system") ?: paramText(step, "target") ?: module),
            params = step.params.filterKeys { it !in setOf("system", "target") }.mapValues { it.value.toExpression() },
            result = result(step.id)
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
                result = ResultBindingNode(name = "rollback_result")
            )
        }
        if (intent.failure.notify) {
            steps += ActionNode(
                module = "notify", action = "send", target = ref("notifier"),
                params = mapOf(
                    "subject" to StringLiteralNode(value = "Flow failed: ${intent.name}"),
                    "body" to ReferenceNode(path = listOf("error", "message"), scope = "error", safe = true)
                )
            )
        }
        return ErrorHandlerNode(steps = steps)
    }

    private fun collectModules(statements: List<StatementNode>, systems: Collection<SystemNode>): Set<String> {
        val out = linkedSetOf<String>()
        systems.forEach { sys -> out += when (sys.systemType) {
            "email" -> "notify"
            else -> sys.systemType
        } }
        fun visit(s: StatementNode) {
            when (s) {
                is ActionNode -> out += s.module
                is IfNode -> { s.then.forEach(::visit); s.otherwise.forEach(::visit) }
                is ForNode -> s.body.forEach(::visit)
                is ParallelNode -> s.branches.flatMap { it.steps }.forEach(::visit)
                is MatchNode -> { s.cases.flatMap { it.steps }.forEach(::visit); s.errorCase?.forEach(::visit); s.defaultSteps.forEach(::visit) }
                is RetryNode -> s.steps.forEach(::visit)
                is TryNode -> { s.steps.forEach(::visit); s.errorHandler.steps.forEach(::visit) }
                is ErrorHandlerNode -> s.steps.forEach(::visit)
                else -> Unit
            }
        }
        statements.forEach(::visit)
        return out
    }

    private fun systemFor(step: IntentStep, fallback: String): String = paramText(step, "system") ?: paramText(step, "target") ?: fallback
    private fun result(id: String) = ResultBindingNode(name = id.replace('-', '_'))
    private fun ref(name: String) = ReferenceNode(path = listOf(name))
    private fun refImplicit(name: String) = ReferenceNode(path = listOf(name), scope = "implicitResult")

    private fun optionalValue(value: String?): ExpressionNode? = value?.let { stringToExpression(it) }
    private fun optionalBool(value: String?): ExpressionNode? = value?.toBooleanStrictOrNull()?.let { BooleanLiteralNode(value = it) }

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

    private fun mapNotNullValues(vararg pairs: Pair<String, ExpressionNode?>): Map<String, ExpressionNode> =
        pairs.mapNotNull { (k, v) -> v?.let { k to it } }.toMap()

    private fun stringToExpression(value: String): ExpressionNode {
        val interpolationStart = 36.toChar().toString() + "{"
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

    private fun expectOkAndCodeZero() = ResultHandlerNode(rules = listOf(ExpectNode(expressions = listOf(
        BinaryExpressionNode(operator = "==", left = refImplicit("ok"), right = BooleanLiteralNode(value = true)),
        BinaryExpressionNode(operator = "==", left = refImplicit("code"), right = NumberLiteralNode(value = 0.0, isInteger = true))
    ))))

    private fun imageExpression(intent: IntentDocument, step: IntentStep): ExpressionNode =
        optionalValue(paramText(step, "image")) ?: if (hasInput(intent, "version")) {
            TemplateStringNode(parts = listOf(StringLiteralNode(value = "${intent.name}:"), ReferenceNode(path = listOf("version"))))
        } else StringLiteralNode(value = "${intent.name}:latest")

    private fun imageOrDependency(intent: IntentDocument, step: IntentStep): ExpressionNode =
        optionalValue(paramText(step, "image")) ?: imageProducerFromRequires(intent, step)?.let { ReferenceNode(path = listOf(it, "tag")) } ?: imageExpression(intent, step)

    private fun imageProducerFromRequires(intent: IntentDocument, step: IntentStep): String? {
        val byId = intent.workflows.flatMap { it.steps }.associateBy { it.id }
        val explicit = step.requires.mapNotNull { byId[it] }.lastOrNull { it.capability in setOf(StandardCapability.BUILD_IMAGE, StandardCapability.PUSH_IMAGE) }
        return explicit?.id?.replace('-', '_')
    }

    private fun namespaceExpression(intent: IntentDocument, step: IntentStep): ExpressionNode? =
        optionalValue(paramText(step, "namespace")) ?: if (hasInput(intent, "environment")) ReferenceNode(path = listOf("environment")) else StringLiteralNode(value = "default")

    private fun hasInput(intent: IntentDocument, name: String): Boolean = intent.inputs.any { it.name == name }
    private fun approvalPolicy(intent: IntentDocument): IntentPolicy? = intent.policies.firstOrNull { it.type == IntentPolicyType.APPROVAL }
    private fun approvalMessage(intent: IntentDocument): String = approvalPolicy(intent)?.message ?: "Approval required for ${intent.name}"
    private fun approvalCondition(intent: IntentDocument): ExpressionNode? = approvalPolicy(intent)?.condition?.let { parseCondition(it) }
    private fun parseCondition(raw: String): ExpressionNode = ExpressionParser.parseSource(raw)

    companion object {
        private val dockerCapabilities = setOf(StandardCapability.BUILD_IMAGE, StandardCapability.PUSH_IMAGE)
        private val kubernetesCapabilities = setOf(StandardCapability.DEPLOY, StandardCapability.VERIFY)
        private val blockedParamNames = setOf("com" + "mand")
        private val standardCapabilities = setOf(
            StandardCapability.BUILD, StandardCapability.TEST, StandardCapability.PACKAGE, StandardCapability.RUN_COMMAND,
            StandardCapability.ROLLBACK, StandardCapability.SYNC, StandardCapability.DATA_SYNC,
            StandardCapability.TRANSFORM, StandardCapability.DATA_TRANSFORM, StandardCapability.VALIDATE,
            StandardCapability.BACKUP, StandardCapability.RESTORE, StandardCapability.CLEANUP,
            StandardCapability.PROVISION, StandardCapability.DEPROVISION, StandardCapability.SCHEDULE,
            StandardCapability.DATABASE_MIGRATE, StandardCapability.CERTIFICATE_RENEW, StandardCapability.KUBERNETES_MAINTENANCE,
            StandardCapability.RUNBOOK, StandardCapability.INCIDENT, StandardCapability.SECRET_ROTATE,
            StandardCapability.POLICY_CHECK, StandardCapability.CUSTOM
        )
    }
}
