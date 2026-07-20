package org.flowlang.planner

import org.flowlang.ast.*
import org.flowlang.modules.ContinuityChannel
import org.flowlang.modules.ContinuityContract
import org.flowlang.modules.ContinuityKind
import org.flowlang.modules.ModuleActionContract
import org.flowlang.modules.ModuleRegistry

/**
 * Converts validated Flow AST into a platform-neutral ExecutionPlan.
 *
 * RC4 rule: dependencies are semantic, not textual. The planner no longer adds
 * false sequential dependencies between independent tasks. Edges come from:
 *  - explicit Action/Approval dependsOn names produced by intent lowering,
 *  - data-flow references to previous result bindings.
 *
 * This keeps Flow portable: generators may exploit DAG parallelism instead of
 * serializing work merely because two statements appeared on adjacent lines.
 */
class FlowPlanner(private val registry: ModuleRegistry = ModuleRegistry()) {

    fun plan(document: FlowDocument): ExecutionPlan {
        val ctx = Ctx(document.flow.input.map { it.name }.toSet(), document.flow.systems.associateBy { it.name })
        val nodes = planStatements(document.flow.steps, ctx)
        val tail = document.flow.errorHandler?.let {
            listOf(TryPlanNode(id = ctx.id("onError"), body = emptyList(), errorHandler = planStatements(it.steps, ctx)))
        } ?: emptyList()
        val allNodes = nodes + tail
        return ExecutionPlan(
            flowName = document.flow.name,
            inputs = document.flow.input.map { it.toPlanInput() },
            triggers = document.flow.triggers.map { it.toPlanTrigger() },
            outputs = ctx.outputs.toList(),
            dependencies = collectDependencies(allNodes),
            requiredCapabilities = (collectRequiredCapabilities(allNodes) + document.flow.triggers.flatMap { it.requiredCapabilities() }).distinct(),
            assumptions = ctx.assumptions.toList(),
            nodes = allNodes,
            dependencyRelations = ctx.dependencyRelations.distinctBy(PlanDependencyRelations::relationKey)
        )
    }


    private fun TriggerNode.toPlanTrigger(): PlanTrigger = PlanTrigger(
        id = id,
        type = triggerType,
        workflows = workflows,
        schedule = schedule?.let { PlanSchedule(it.kind, it.expression, it.timezone) },
        event = event,
        params = params.mapValues { (_, value) -> RuntimeParamRenderer.render(value, emptySet()) },
        requiredCapabilities = requiredCapabilities()
    )

    private fun TriggerNode.requiredCapabilities(): List<String> = when (triggerType) {
        "SCHEDULE" -> listOf("trigger.schedule.${schedule?.kind?.lowercase() ?: "unknown"}")
        "EVENT" -> listOf("trigger.event")
        "WEBHOOK" -> listOf("trigger.webhook")
        "MANUAL" -> listOf("trigger.manual")
        else -> listOf("trigger.unknown")
    }

    private fun InputNode.toPlanInput(): PlanInput {
        val choices = valueType.values.filterIsInstance<StringLiteralNode>().map { it.value }
        val defaultValue = default?.let { ExpressionRenderer.render(it).trim('"') }
        return PlanInput(name = name, type = valueType.kind, required = required, defaultValue = defaultValue, choices = choices)
    }

    private fun planStatements(stmts: List<StatementNode>, ctx: Ctx): List<PlanNode> = stmts.map { planStatement(it, ctx) }

    private fun planStatement(stmt: StatementNode, ctx: Ctx): PlanNode = when (stmt) {
        is ActionNode -> planAction(stmt, ctx)
        is IfNode -> ConditionNode(
            id = ctx.id("if"),
            condition = ExpressionRenderer.render(stmt.condition),
            then = planStatements(stmt.then, ctx),
            otherwise = planStatements(stmt.otherwise, ctx)
        )
        is ForNode -> LoopNode(
            id = ctx.id("for"), item = stmt.item,
            source = ExpressionRenderer.render(stmt.source),
            body = planStatements(stmt.body, ctx)
        )
        is ParallelNode -> ParallelGroupNode(
            id = ctx.id("parallel"), failFast = stmt.failFast,
            branches = stmt.branches.map { PlanBranch(it.name, planStatements(it.steps, ctx)) }
        )
        is MatchNode -> MatchPlanNode(
            id = ctx.id("match"), source = ExpressionRenderer.render(stmt.source),
            cases = stmt.cases.map { MatchCase(it.condition?.let(ExpressionRenderer::render) ?: "_", planStatements(it.steps, ctx)) },
            errorCase = stmt.errorCase?.let { planStatements(it, ctx) } ?: emptyList(),
            defaultSteps = planStatements(stmt.defaultSteps, ctx)
        )
        is RetryNode -> RetryGroupNode(
            id = ctx.id("retry"), max = stmt.policy.max, delay = stmt.policy.delay, backoff = stmt.policy.backoff,
            body = planStatements(stmt.steps, ctx)
        )
        is TryNode -> TryPlanNode(
            id = ctx.id("try"), body = planStatements(stmt.steps, ctx),
            errorHandler = planStatements(stmt.errorHandler.steps, ctx)
        )
        is ApproveNode -> {
            val explicitDeps = stmt.dependsOn.mapNotNull { ctx.results[it.replace('-', '_')] ?: ctx.results[it] }.distinct()
            val id = ctx.id("approve")
            stmt.result?.let { ctx.results[it.name] = id }
            ApprovalNode(id = id, mode = stmt.mode,
                message = (stmt.params["message"])?.let(ExpressionRenderer::render)?.trim('"'),
                resultName = stmt.result?.name,
                dependsOn = explicitDeps)
        }
        is TransformNode -> { val id = ctx.id("transform"); ctx.results[stmt.target] = id; DataOpNode(id, "Transform", stmt.target, ExpressionRenderer.render(stmt.source)) }
        is AggregateNode -> { val id = ctx.id("aggregate"); ctx.results[stmt.target] = id; DataOpNode(id, "Aggregate", stmt.target, ExpressionRenderer.render(stmt.source)) }
        is ValidateNode -> DataOpNode(ctx.id("validate"), "Validate", null, ExpressionRenderer.render(stmt.target))
        is SetNode -> { val id = ctx.id("set"); ctx.results[stmt.name] = id; ControlNode(id, "Set", "${stmt.name} = ${ExpressionRenderer.render(stmt.value)}") }
        is FailNode -> ControlNode(ctx.id("fail"), "Fail", ExpressionRenderer.render(stmt.message))
        is SkipNode -> ControlNode(ctx.id("skip"), "Skip", ExpressionRenderer.render(stmt.message))
        is ExpectNode -> ControlNode(ctx.id("expect"), "Expect", stmt.expressions.joinToString("; ") { ExpressionRenderer.render(it) })
        is ErrorHandlerNode -> TryPlanNode(id = ctx.id("onError"), body = emptyList(), errorHandler = planStatements(stmt.steps, ctx))
    }

    private fun planAction(action: ActionNode, ctx: Ctx): TaskNode {
        val id = ctx.id("${action.module}_${action.action}")
        val contract = registry.findAction(action.module, action.action)
        val effects = contract?.effects?.let { e ->
            (e.reads + e.writes + e.creates + e.updates + e.deletes + e.executes + e.network + e.filesystem)
        } ?: emptyList()

        val referenced = mutableSetOf<String>()
        action.params.values.forEach { collectRoots(it, referenced) }
        action.target.path.firstOrNull()?.let { referenced += it }
        val dataDependencies = referenced.mapNotNull { binding ->
            ctx.results[binding]?.let { sourceNodeId -> DataDependency(sourceNodeId, binding) }
        }.distinct()
        val explicitDependencies = action.dependsOn.mapNotNull { raw ->
            val normalized = raw.replace('-', '_')
            ctx.results[normalized] ?: ctx.results[raw]
        }.distinct()
        val deps = (dataDependencies.map { it.sourceNodeId } + explicitDependencies)
            .distinct()
            .filter { it != id }

        val continuityRelations = contract?.continuity?.requires.orEmpty().map { requirement ->
            ctx.resolveContinuityRequirement(id, deps, requirement, action.module, action.action)
        }
        val orderingRelations = deps.map { sourceNodeId ->
            val evidence = if (dataDependencies.any { it.sourceNodeId == sourceNodeId }) {
                PlanDependencyEvidence.DATA_REFERENCE
            } else {
                PlanDependencyEvidence.DECLARED_ORDERING
            }
            PlanDependencyRelation(
                sourceNodeId = sourceNodeId,
                targetNodeId = id,
                kind = PlanDependencyKind.ORDERING,
                evidence = evidence,
                path = listOf(sourceNodeId, id)
            )
        }
        val valueRelations = dataDependencies.map { dependency ->
            PlanDependencyRelation(
                sourceNodeId = dependency.sourceNodeId,
                targetNodeId = id,
                kind = PlanDependencyKind.VALUE,
                channel = dependency.binding,
                evidence = PlanDependencyEvidence.DATA_REFERENCE,
                path = listOf(dependency.sourceNodeId, id),
                evidenceReference = "${action.module}.${action.action}.params.${dependency.binding}"
            )
        }
        ctx.dependencyRelations += orderingRelations + valueRelations + continuityRelations

        // System configuration (git url/branch, REST baseUrl, k8s/helm namespace) is no longer
        // dropped at the planning boundary. Non-secret values configured on the target system act as
        // defaults for the action's params; explicit action params still win. Secret-valued endpoints
        // remain symbolic for target-side secret resolution.
        val renderedParams = mergeSystemConfig(action, ctx)
        val continuityCapabilities = (valueRelations + continuityRelations)
            .mapNotNull { it.kind.capability }

        val task = TaskNode(
            id = id, module = action.module, action = action.action,
            target = action.target.path.joinToString("."),
            resultName = action.result?.name, dependsOn = deps, effects = effects,
            inputs = renderedParams,
            outputs = action.result?.let { listOf(it.name) } ?: emptyList(),
            destructive = contract?.safety?.destructive ?: false,
            safety = action.safety?.let { it.rule + (it.condition?.let { c -> " " + ExpressionRenderer.render(c) } ?: "") },
            params = renderedParams,
            requiredCapabilities = (
                inferRequiredCapabilities(action, contract?.safety?.destructive ?: false) + continuityCapabilities
            ).distinct()
        )
        ctx.registerTask(task, contract)
        action.result?.let {
            ctx.results[it.name] = id
            ctx.outputs += PlanOutput(it.name, sourceNodeId = id)
        }
        return task
    }

    private data class DataDependency(val sourceNodeId: String, val binding: String)

    /**
     * Renders the action's params and layers in defaults from the targeted system's configuration
     * for the keys each module consumes. Values are merged only when the action does not already
     * provide the key, so explicit params always win. Secret-valued config (`secret("NAME")`) is
     * kept and rendered as-is; the manifest layer materialises it through the target's secret
     * mechanism (Jenkins credentials, GitHub secrets, Tekton secretKeyRef) rather than dropping it.
     */
    private fun mergeSystemConfig(action: ActionNode, ctx: Ctx): Map<String, String> {
        val rendered = action.params.mapValues { (_, expr) -> RuntimeParamRenderer.render(expr, ctx.inputNames) }.toMutableMap()
        val system = action.target.path.firstOrNull()?.let { ctx.systems[it] } ?: return rendered
        fun pull(key: String) {
            if (key in rendered) return
            val expr = system.config[key] ?: return
            rendered[key] = RuntimeParamRenderer.render(expr, ctx.inputNames)
        }
        when (action.module) {
            "git" -> { pull("url"); pull("branch") }
            "rest" -> pull("baseUrl")
            "database" -> pull("url")
            "kubernetes", "helm" -> pull("namespace")
        }
        return rendered
    }

    private fun inferRequiredCapabilities(action: ActionNode, destructive: Boolean): List<String> = buildList {
        add("task.execute")
        if (action.module == "standard") {
            val operation = action.params["operation"]?.let(ExpressionRenderer::render)?.trim('"')
            if (!operation.isNullOrBlank()) add("standard.$operation")
        }
        if (action.module == "git" && action.action == "checkout") add("git.checkout")
        if (action.module == "docker" && action.action == "build") add("docker.build")
        if (action.module == "kubernetes") add("kubernetes.api")
        if (action.module == "docker") add("container.image")
        if (action.module == "notify") add("notification.send")
        if (destructive) add("safety.destructiveOperation")
    }.distinct()

    private fun collectDependencies(nodes: List<PlanNode>): List<String> = nodes.flatMap { node ->
        when (node) {
            is TaskNode -> node.dependsOn
            is ApprovalNode -> node.dependsOn
            is ConditionNode -> collectDependencies(node.then) + collectDependencies(node.otherwise)
            is LoopNode -> collectDependencies(node.body)
            is ParallelGroupNode -> node.branches.flatMap { collectDependencies(it.steps) }
            is MatchPlanNode -> node.cases.flatMap { collectDependencies(it.steps) } + collectDependencies(node.errorCase) + collectDependencies(node.defaultSteps)
            is RetryGroupNode -> collectDependencies(node.body)
            is TryPlanNode -> collectDependencies(node.body) + collectDependencies(node.errorHandler)
            else -> emptyList()
        }
    }.distinct()

    private fun collectRequiredCapabilities(nodes: List<PlanNode>): List<String> = nodes.flatMap { node ->
        when (node) {
            is TaskNode -> node.requiredCapabilities
            is ApprovalNode -> node.requiredCapabilities
            is ConditionNode -> listOf("condition.evaluate") + collectRequiredCapabilities(node.then) + collectRequiredCapabilities(node.otherwise)
            is LoopNode -> listOf("loop.dynamic") + collectRequiredCapabilities(node.body)
            is ParallelGroupNode -> listOf("parallel.dag") + node.branches.flatMap { collectRequiredCapabilities(it.steps) }
            is MatchPlanNode -> listOf("match.basic") + node.cases.flatMap { collectRequiredCapabilities(it.steps) } + collectRequiredCapabilities(node.errorCase) + collectRequiredCapabilities(node.defaultSteps)
            is RetryGroupNode -> listOf("retry.task") + collectRequiredCapabilities(node.body)
            is TryPlanNode -> listOf("errorHandlers.finally") + collectRequiredCapabilities(node.body) + collectRequiredCapabilities(node.errorHandler)
            else -> emptyList()
        }
    }.distinct()

    private fun collectRoots(e: ExpressionNode, into: MutableSet<String>) {
        when (e) {
            is ReferenceNode -> e.path.firstOrNull()?.let { into += it }
            is BinaryExpressionNode -> { collectRoots(e.left, into); collectRoots(e.right, into) }
            is UnaryExpressionNode -> collectRoots(e.operand, into)
            is UnaryPostfixExpressionNode -> collectRoots(e.operand, into)
            is LogicalExpressionNode -> e.operands.forEach { collectRoots(it, into) }
            is ListLiteralNode -> e.items.forEach { collectRoots(it, into) }
            is MapLiteralNode -> e.entries.values.forEach { collectRoots(it, into) }
            is TemplateStringNode -> e.parts.forEach { collectRoots(it, into) }
            is CallExpressionNode -> e.args.forEach { collectRoots(it, into) }
            is IndexExpressionNode -> { collectRoots(e.target, into); collectRoots(e.index, into) }
            is MemberExpressionNode -> collectRoots(e.target, into)
            else -> Unit
        }
    }

    private class Ctx(val inputNames: Set<String>, val systems: Map<String, SystemNode> = emptyMap()) {
        val results = mutableMapOf<String, String>()   // result/binding name -> producing task id
        val outputs = mutableListOf<PlanOutput>()
        val assumptions = mutableListOf<PlanAssumption>()
        val dependencyRelations = mutableListOf<PlanDependencyRelation>()
        private val taskDependencies = mutableMapOf<String, List<String>>()
        private val taskContinuity = mutableMapOf<String, ContinuityContract>()
        private val counters = mutableMapOf<String, Int>()

        fun id(prefix: String): String {
            val n = (counters[prefix] ?: 0) + 1
            counters[prefix] = n
            return "${prefix}_$n"
        }

        fun registerTask(task: TaskNode, contract: ModuleActionContract?) {
            taskDependencies[task.id] = task.dependsOn
            taskContinuity[task.id] = contract?.continuity ?: ContinuityContract()
        }

        fun resolveContinuityRequirement(
            targetNodeId: String,
            dependencies: List<String>,
            requirement: ContinuityChannel,
            module: String,
            action: String
        ): PlanDependencyRelation {
            val matches = dependencies.flatMap { dependency ->
                findProviders(dependency, requirement, linkedSetOf())
            }.distinctBy { it.providerNodeId }
            val evidenceReference = "$module.$action.continuity.requires.${requirement.kind.name.lowercase()}.${requirement.name}"
            return when (matches.size) {
                1 -> PlanDependencyRelation(
                    sourceNodeId = matches.single().providerNodeId,
                    targetNodeId = targetNodeId,
                    kind = requirement.kind.toPlanKind(),
                    channel = requirement.name,
                    evidence = PlanDependencyEvidence.MODULE_CONTRACT,
                    resolution = PlanDependencyResolution.RESOLVED,
                    path = matches.single().path + targetNodeId,
                    evidenceReference = evidenceReference
                )
                0 -> PlanDependencyRelation(
                    targetNodeId = targetNodeId,
                    kind = requirement.kind.toPlanKind(),
                    channel = requirement.name,
                    evidence = PlanDependencyEvidence.MODULE_CONTRACT,
                    resolution = PlanDependencyResolution.UNRESOLVED,
                    evidenceReference = evidenceReference
                )
                else -> PlanDependencyRelation(
                    targetNodeId = targetNodeId,
                    kind = requirement.kind.toPlanKind(),
                    channel = requirement.name,
                    evidence = PlanDependencyEvidence.MODULE_CONTRACT,
                    resolution = PlanDependencyResolution.AMBIGUOUS,
                    candidates = matches.map { it.providerNodeId }.sorted(),
                    evidenceReference = evidenceReference
                )
            }
        }

        private fun findProviders(
            nodeId: String,
            requirement: ContinuityChannel,
            visited: LinkedHashSet<String>
        ): List<ContinuityPath> {
            if (!visited.add(nodeId)) return emptyList()
            val continuity = taskContinuity[nodeId] ?: return emptyList()
            if (requirement in continuity.provides) return listOf(ContinuityPath(nodeId, listOf(nodeId)))
            if (requirement !in continuity.preserves) return emptyList()
            return taskDependencies[nodeId].orEmpty().flatMap { dependency ->
                findProviders(dependency, requirement, LinkedHashSet(visited)).map { path ->
                    path.copy(path = path.path + nodeId)
                }
            }
        }

        private data class ContinuityPath(val providerNodeId: String, val path: List<String>)
    }

}

private fun ContinuityKind.toPlanKind(): PlanDependencyKind = when (this) {
    ContinuityKind.VALUE -> PlanDependencyKind.VALUE
    ContinuityKind.WORKSPACE -> PlanDependencyKind.WORKSPACE
    ContinuityKind.STATE -> PlanDependencyKind.STATE
}
