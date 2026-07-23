package org.flowlang.planner

import org.flowlang.ast.*
import org.flowlang.effects.CanonicalIntentEffectAuthority
import org.flowlang.effects.ModuleEffectCanonicalizer
import org.flowlang.effects.SemanticEffect
import org.flowlang.controls.PlanningControlAuthority
import org.flowlang.intent.StandardCapability
import org.flowlang.modules.ContinuityChannel
import org.flowlang.modules.ContinuityContract
import org.flowlang.modules.ContinuityKind
import org.flowlang.modules.ModuleActionContract
import org.flowlang.modules.ModuleRegistry
import org.flowlang.topology.PlanningTopologyAuthority

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
        val dependencyRelations = ctx.dependencyRelations.distinctBy(PlanDependencyRelations::relationKey)
        val controlAssessment = PlanningControlAuthority.assess(
            canonicalRequirements = document.flow.controlRequirements,
            canonicalEvidence = document.flow.controlEvidence,
            nodes = allNodes,
            modules = registry
        )
        return ExecutionPlan(
            flowName = document.flow.name,
            inputs = document.flow.input.map { it.toPlanInput() },
            triggers = document.flow.triggers.map { it.toPlanTrigger() },
            outputs = ctx.outputs.toList(),
            dependencies = collectDependencies(allNodes),
            requiredCapabilities = (
                collectRequiredCapabilities(allNodes) +
                    document.flow.triggers.flatMap { it.requiredCapabilities() } +
                    PlanningControlAuthority.requiredEnforcementCapabilities(controlAssessment)
                ).distinct(),
            sourceIntent = document.metadata.sourceIntent,
            loweringReport = document.metadata.loweringReport,
            assumptions = ctx.assumptions.toList(),
            controlRequirements = controlAssessment.requirements,
            controlEvidence = controlAssessment.evidence,
            controlDecision = controlAssessment.decision,
            topologyRequirements = PlanningTopologyAuthority.requirementsFor(
                flowName = document.flow.name,
                canonicalRequirements = document.flow.topologyRequirements,
                nodes = allNodes,
                dependencyRelations = dependencyRelations
            ),
            nodes = allNodes,
            dependencyRelations = dependencyRelations
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
        val defaultValue = (default as? StringLiteralNode)?.value
        val defaultExpression = default?.let(ExpressionRenderer::render)
        return PlanInput(
            name = name,
            type = valueType.kind,
            required = required,
            defaultValue = defaultValue,
            defaultExpression = defaultExpression,
            choices = choices
        )
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
            ctx.dependencyRelations += explicitDeps.map { sourceNodeId ->
                PlanDependencyRelation(
                    sourceNodeId = sourceNodeId,
                    targetNodeId = id,
                    kind = PlanDependencyKind.ORDERING,
                    evidence = PlanDependencyEvidence.DECLARED_ORDERING,
                    path = listOf(sourceNodeId, id)
                )
            }
            val outputNames = (listOfNotNull(stmt.result?.name) + stmt.declaredOutputs).distinct()
            outputNames.forEach { output ->
                ctx.results[output] = id
                ctx.results[output.replace('-', '_')] = id
                ctx.outputs += PlanOutput(output, sourceNodeId = id)
            }
            ApprovalNode(
                id = id,
                mode = stmt.mode,
                message = (stmt.params["message"])?.let(ExpressionRenderer::render)?.trim('"'),
                resultName = stmt.result?.name,
                sourceId = stmt.sourceId,
                sourceDescription = stmt.sourceDescription,
                outputs = outputNames,
                dependsOn = explicitDeps
            )
        }
        is TransformNode -> {
            val id = ctx.id("transform")
            ctx.results[stmt.target] = id
            DataOpNode(
                id = id,
                kind = "Transform",
                target = stmt.target,
                detail = ExpressionRenderer.render(stmt.source),
                semanticCapability = StandardCapability.TRANSFORM.name,
                effectModel = CanonicalIntentEffectAuthority.effectsFor(StandardCapability.TRANSFORM)
            )
        }
        is AggregateNode -> {
            val id = ctx.id("aggregate")
            ctx.results[stmt.target] = id
            DataOpNode(
                id = id,
                kind = "Aggregate",
                target = stmt.target,
                detail = ExpressionRenderer.render(stmt.source),
                semanticCapability = StandardCapability.DATA_TRANSFORM.name,
                effectModel = CanonicalIntentEffectAuthority.effectsFor(StandardCapability.DATA_TRANSFORM)
            )
        }
        is ValidateNode -> DataOpNode(
            id = ctx.id("validate"),
            kind = "Validate",
            detail = ExpressionRenderer.render(stmt.target),
            semanticCapability = StandardCapability.VALIDATE.name,
            effectModel = CanonicalIntentEffectAuthority.effectsFor(StandardCapability.VALIDATE)
        )
        is SetNode -> { val id = ctx.id("set"); ctx.results[stmt.name] = id; ControlNode(id, "Set", "${stmt.name} = ${ExpressionRenderer.render(stmt.value)}") }
        is FailNode -> ControlNode(ctx.id("fail"), "Fail", ExpressionRenderer.render(stmt.message))
        is SkipNode -> ControlNode(ctx.id("skip"), "Skip", ExpressionRenderer.render(stmt.message))
        is ExpectNode -> ControlNode(ctx.id("expect"), "Expect", stmt.expressions.joinToString("; ") { ExpressionRenderer.render(it) })
        is ErrorHandlerNode -> TryPlanNode(id = ctx.id("onError"), body = emptyList(), errorHandler = planStatements(stmt.steps, ctx))
    }

    private fun planAction(action: ActionNode, ctx: Ctx): TaskNode {
        val id = ctx.id("${action.module}_${action.action}")
        val contract = registry.findAction(action.module, action.action)
        val effectModel = action.semanticEffects.ifEmpty {
            contract?.effects?.let(ModuleEffectCanonicalizer::canonicalize).orEmpty()
        }
        val effects = effectModel.map(SemanticEffect::resource).distinct()

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

        val renderedParams = mergeSystemConfig(action, ctx)
        val continuityCapabilities = (valueRelations + continuityRelations)
            .mapNotNull { it.kind.capability }

        val outputNames = (listOfNotNull(action.result?.name) + action.declaredOutputs).distinct()
        val task = TaskNode(
            id = id, module = action.module, action = action.action,
            target = action.target.path.joinToString("."),
            resultName = action.result?.name,
            dependsOn = deps,
            semanticCapability = action.semanticCapability,
            sourceId = action.sourceId,
            sourceDescription = action.sourceDescription,
            bindingMetadata = action.bindingMetadata.mapValues { (_, value) -> RuntimeParamRenderer.render(value, ctx.inputNames) },
            effectModel = effectModel,
            effects = effects,
            inputs = renderedParams,
            outputs = outputNames,
            destructive = contract?.safety?.destructive ?: false,
            safety = action.safety?.let { it.rule + (it.condition?.let { condition -> " " + ExpressionRenderer.render(condition) } ?: "") },
            params = renderedParams,
            requiredCapabilities = (
                inferRequiredCapabilities(action, contract) + continuityCapabilities
            ).distinct()
        )
        ctx.registerTask(task, contract)
        outputNames.forEach { output ->
            ctx.results[output] = id
            ctx.results[output.replace('-', '_')] = id
            ctx.outputs += PlanOutput(output, sourceNodeId = id)
        }
        return task
    }

    private data class DataDependency(val sourceNodeId: String, val binding: String)

    /**
     * Renders action parameters and layers in every schema-declared value from the
     * targeted system configuration. Explicit action parameters always win.
     *
     * The system-type descriptor is the authority for which configuration belongs
     * to the system. This removes module-name switches and preserves integrations
     * such as Argo CD url/token without teaching Core about that product.
     */
    private fun mergeSystemConfig(action: ActionNode, ctx: Ctx): Map<String, String> {
        val rendered = action.params.mapValues { (_, expression) ->
            RuntimeParamRenderer.render(expression, ctx.inputNames)
        }.toMutableMap()
        val system = action.target.path.firstOrNull()?.let { ctx.systems[it] } ?: return rendered
        val systemContract = registry.findSystemType(system.systemType)?.second ?: return rendered
        systemContract.input.keys.sorted().forEach { key ->
            if (key !in rendered) {
                system.config[key]?.let { expression ->
                    rendered[key] = RuntimeParamRenderer.render(expression, ctx.inputNames)
                }
            }
        }
        return rendered
    }

    private fun inferRequiredCapabilities(
        action: ActionNode,
        contract: ModuleActionContract?
    ): List<String> = buildList {
        add("task.execute")
        addAll(contract?.requiredCapabilities.orEmpty())
        if (action.module == "standard") {
            val operation = action.params["operation"]?.let(ExpressionRenderer::render)?.trim('"')
            if (!operation.isNullOrBlank()) add("standard.$operation")
        }
        if (contract?.safety?.destructive == true) add("safety.destructiveOperation")
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
        val results = mutableMapOf<String, String>()
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
