package org.flowlang.planner

import java.nio.charset.StandardCharsets
import java.util.Base64
import org.flowlang.ast.*
import org.flowlang.controls.PlanningControlAuthority
import org.flowlang.core.FlowAvailabilityAnalysis
import org.flowlang.core.FlowAvailabilityAnalyzer
import org.flowlang.core.FlowAvailabilityState
import org.flowlang.core.FlowMergeContract
import org.flowlang.core.FlowProducerIdentity
import org.flowlang.core.FlowStatementPath
import org.flowlang.core.FlowWorkflowIdentity
import org.flowlang.effects.CanonicalIntentEffectAuthority
import org.flowlang.effects.ModuleEffectCanonicalizer
import org.flowlang.effects.SemanticEffect
import org.flowlang.intent.StandardCapability
import org.flowlang.lowering.IntentLoweringAuthority
import org.flowlang.modules.ContinuityChannel
import org.flowlang.modules.ContinuityContract
import org.flowlang.modules.ContinuityKind
import org.flowlang.modules.ModuleActionContract
import org.flowlang.modules.ModuleCatalog
import org.flowlang.topology.PlanningTopologyAuthority

/**
 * Raised when the public planner boundary receives an AST action that has no
 * registered semantic and safety contract.
 *
 * Validation normally rejects this condition first. The planner still enforces
 * the invariant independently so callers cannot bypass validation and silently
 * turn unknown safety evidence into a non-destructive task.
 */
class MissingPlanningActionContractException(
    val moduleName: String,
    val actionName: String
) : IllegalStateException(
    "Cannot plan unregistered action '$moduleName.$actionName'. " +
        "Validate the Flow document and provide its authoritative module registry before planning."
)

internal data class FlowPlanningResult(
    val plan: ExecutionPlan,
    val producerNodeIds: Map<FlowProducerIdentity, String>,
    val failurePolicy: PlannedWorkflowFailurePolicy = PlannedWorkflowFailurePolicy.none()
)

/**
 * Converts validated Flow AST into a platform-neutral ExecutionPlan.
 *
 * Dependencies are semantic, not textual. AR-02A additionally requires every
 * value edge to resolve through the shared path-sensitive availability analysis.
 * Mutually exclusive branches therefore never overwrite a global binding map or
 * lend their producer identity to a sibling branch.
 */
class FlowPlanner(private val registry: ModuleCatalog) {

    fun plan(document: FlowDocument): ExecutionPlan =
        planWithProvenance(document, FlowAvailabilityAnalyzer().analyze(document)).plan

    internal fun plan(
        document: FlowDocument,
        availability: FlowAvailabilityAnalysis
    ): ExecutionPlan = planWithProvenance(document, availability).plan

    internal fun planWithProvenance(
        document: FlowDocument,
        availability: FlowAvailabilityAnalysis,
        workflowIdentity: FlowWorkflowIdentity = FlowWorkflowIdentity.Main,
        nodeIdNamespace: String? = null
    ): FlowPlanningResult {
        availability.requireDirectPlanningSafe()
        val ctx = Ctx(
            inputNames = document.flow.input.map { it.name }.toSet(),
            systems = document.flow.systems.associateBy { it.name },
            nodeIdNamespace = nodeIdNamespace
        )
        val nodes = planStatements(document.flow.steps, ctx, availability) { index ->
            FlowStatementPath.flowStep(index, workflowIdentity)
        }
        val failurePolicy = document.flow.errorHandler?.let { handler ->
            val handlerNodes = planStatements(handler.steps, ctx, availability) { index ->
                FlowStatementPath.globalErrorStep(index, workflowIdentity)
            }
            PlannedWorkflowFailurePolicy(
                policy = WorkflowFailurePolicy(
                    disposition = WorkflowFailureDisposition.PROPAGATE,
                    handler = WorkflowFailureHandlerRegion(
                        id = "workflow-failure:${workflowIdentity.value}",
                        nodeIds = handlerNodes.map(PlanNode::id),
                        entry = WorkflowFailureHandlerEntry(
                            errorBinding = "error",
                            priorSuccessfulValuesAvailable = false
                        )
                    )
                ),
                handlerNodes = handlerNodes,
                compatibilityBoundaryNodeId = ctx.id("onError")
            )
        } ?: PlannedWorkflowFailurePolicy.none()
        val allNodes = nodes + failurePolicy.compatibilityMirror()
        val dependencyRelations = ctx.dependencyRelations.distinctBy(PlanDependencyRelations::relationKey)
        val controlAssessment = PlanningControlAuthority.assess(
    canonicalRequirements = document.flow.controlRequirements,
    canonicalEvidence = document.flow.controlEvidence,
    nodes = nodes,
    modules = registry,
    workflowFailureHandlerNodes = failurePolicy.handlerNodes
)
        val basePlan = ExecutionPlan(
            flowName = document.flow.name,
            inputs = document.flow.input.map { it.toPlanInput() },
            triggers = document.flow.triggers.map { it.toPlanTrigger() },
            outputs = ctx.visibleOutputs(availability.normalExitState),
            dependencies = (collectDependencies(allNodes) + ctx.mergeDependencyNodeIds()).distinct(),
            requiredCapabilities = (
    collectRequiredCapabilities(allNodes) +
        document.flow.triggers.flatMap { it.requiredCapabilities() } +
        PlanningControlAuthority.requiredEnforcementCapabilities(controlAssessment)
).distinct(),
            sourceIntent = document.metadata.sourceIntent,
            loweringReport = null,
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
        val finalPlan = if (basePlan.sourceIntent == null) {
            basePlan
        } else {
            basePlan.copy(loweringReport = IntentLoweringAuthority.report(basePlan))
        }
        return FlowPlanningResult(finalPlan, ctx.producerBindings(), failurePolicy)
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

    private fun planStatements(
        statements: List<StatementNode>,
        ctx: Ctx,
        availability: FlowAvailabilityAnalysis,
        path: (Int) -> FlowStatementPath
    ): List<PlanNode> = statements.mapIndexed { index, statement ->
        planStatement(statement, ctx, availability, path(index))
    }

    private fun planStatement(
        statement: StatementNode,
        ctx: Ctx,
        availability: FlowAvailabilityAnalysis,
        path: FlowStatementPath
    ): PlanNode = when (statement) {
        is ActionNode -> planAction(statement, ctx, availability, path)
        is IfNode -> ConditionNode(
            id = ctx.id("if"),
            condition = ExpressionRenderer.render(statement.condition),
            then = planStatements(statement.then, ctx, availability) { index -> path.child("then", index) },
            otherwise = planStatements(statement.otherwise, ctx, availability) { index -> path.child("otherwise", index) }
        )
        is ForNode -> LoopNode(
            id = ctx.id("for"),
            item = statement.item,
            source = ExpressionRenderer.render(statement.source),
            body = planStatements(statement.body, ctx, availability) { index -> path.child("body", index) }
        )
        is ParallelNode -> ParallelGroupNode(
            id = ctx.id("parallel"),
            failFast = statement.failFast,
            branches = statement.branches.mapIndexed { branchIndex, branch ->
                PlanBranch(
                    branch.name,
                    planStatements(branch.steps, ctx, availability) { index ->
                        path.child("branches[$branchIndex].steps", index)
                    }
                )
            }
        )
        is MatchNode -> MatchPlanNode(
            id = ctx.id("match"),
            source = ExpressionRenderer.render(statement.source),
            cases = statement.cases.mapIndexed { caseIndex, matchCase ->
                MatchCase(
                    matchCase.condition?.let(ExpressionRenderer::render) ?: "_",
                    planStatements(matchCase.steps, ctx, availability) { index ->
                        path.child("cases[$caseIndex].steps", index)
                    }
                )
            },
            errorCase = statement.errorCase?.let { errorSteps ->
                planStatements(errorSteps, ctx, availability) { index -> path.child("errorCase", index) }
            } ?: emptyList(),
            defaultSteps = planStatements(statement.defaultSteps, ctx, availability) { index ->
                path.child("default", index)
            }
        )
        is RetryNode -> RetryGroupNode(
            id = ctx.id("retry"),
            max = statement.policy.max,
            delay = statement.policy.delay,
            backoff = statement.policy.backoff,
            body = planStatements(statement.steps, ctx, availability) { index -> path.child("body", index) }
        )
        is TryNode -> TryPlanNode(
            id = ctx.id("try"),
            body = planStatements(statement.steps, ctx, availability) { index -> path.child("body", index) },
            errorHandler = planStatements(statement.errorHandler.steps, ctx, availability) { index ->
                path.child("errorHandler", index)
            }
        )
        is ApproveNode -> planApproval(statement, ctx, availability, path)
        is TransformNode -> {
            val id = ctx.id("transform")
            ctx.registerProducer(availability.producerAt(path, statement.target), id)
            DataOpNode(
                id = id,
                kind = "Transform",
                target = statement.target,
                detail = ExpressionRenderer.render(statement.source),
                semanticCapability = StandardCapability.TRANSFORM.name,
                effectModel = CanonicalIntentEffectAuthority.effectsFor(StandardCapability.TRANSFORM)
            )
        }
        is AggregateNode -> {
            val id = ctx.id("aggregate")
            ctx.registerProducer(availability.producerAt(path, statement.target), id)
            DataOpNode(
                id = id,
                kind = "Aggregate",
                target = statement.target,
                detail = ExpressionRenderer.render(statement.source),
                semanticCapability = StandardCapability.DATA_TRANSFORM.name,
                effectModel = CanonicalIntentEffectAuthority.effectsFor(StandardCapability.DATA_TRANSFORM)
            )
        }
        is ValidateNode -> DataOpNode(
            id = ctx.id("validate"),
            kind = "Validate",
            detail = ExpressionRenderer.render(statement.target),
            semanticCapability = StandardCapability.VALIDATE.name,
            effectModel = CanonicalIntentEffectAuthority.effectsFor(StandardCapability.VALIDATE)
        )
        is SetNode -> availability.mergeAt(path)?.let { merge ->
            planMerge(statement, merge, ctx, path)
        } ?: run {
            val id = ctx.id("set")
            ctx.registerProducer(availability.producerAt(path, statement.name), id)
            ControlNode(id, "Set", "${statement.name} = ${ExpressionRenderer.render(statement.value)}")
        }
        is FailNode -> ControlNode(ctx.id("fail"), "Fail", ExpressionRenderer.render(statement.message))
        is SkipNode -> ControlNode(ctx.id("skip"), "Skip", ExpressionRenderer.render(statement.message))
        is ExpectNode -> ControlNode(
            ctx.id("expect"),
            "Expect",
            statement.expressions.joinToString("; ") { ExpressionRenderer.render(it) }
        )
        is ErrorHandlerNode -> TryPlanNode(
            id = ctx.id("onError"),
            body = emptyList(),
            errorHandler = planStatements(statement.steps, ctx, availability) { index -> path.child("steps", index) }
        )
    }

    private fun planMerge(
        statement: SetNode,
        merge: FlowMergeContract,
        ctx: Ctx,
        path: FlowStatementPath
    ): ControlNode {
        val incoming = merge.incoming.map { input ->
            input to ctx.resolveProducerIdentity(input.producer, input.binding, path)
        }
        val id = ctx.id("merge")
        ctx.registerProducer(merge.producer, id)
        ctx.registerMerge(id, incoming.map { it.second })
        incoming.forEach { (input, sourceNodeId) ->
            ctx.dependencyRelations += PlanDependencyRelation(
                sourceNodeId = sourceNodeId,
                targetNodeId = id,
                kind = PlanDependencyKind.ORDERING,
                evidence = PlanDependencyEvidence.DATA_REFERENCE,
                path = listOf(sourceNodeId, id),
                evidenceReference = "flow.merge:${merge.identity.resultBinding}:${input.binding}"
            )
            ctx.dependencyRelations += PlanDependencyRelation(
                sourceNodeId = sourceNodeId,
                targetNodeId = id,
                kind = PlanDependencyKind.VALUE,
                channel = input.binding,
                evidence = PlanDependencyEvidence.DATA_REFERENCE,
                path = listOf(sourceNodeId, id),
                evidenceReference = "flow.merge:${merge.identity.resultBinding}:${input.binding}"
            )
        }
        return ControlNode(id, "Set", "${statement.name} = ${ExpressionRenderer.render(statement.value)}")
    }

    private fun planApproval(
        approval: ApproveNode,
        ctx: Ctx,
        availability: FlowAvailabilityAnalysis,
        path: FlowStatementPath
    ): ApprovalNode {
        val explicitDependencies = approval.dependsOn.mapNotNull { binding ->
            ctx.resolveOrderingProducer(availability, path, binding)
        }.distinct()
        val id = ctx.id("approve")
        ctx.dependencyRelations += explicitDependencies.map { sourceNodeId ->
            PlanDependencyRelation(
                sourceNodeId = sourceNodeId,
                targetNodeId = id,
                kind = PlanDependencyKind.ORDERING,
                evidence = PlanDependencyEvidence.DECLARED_ORDERING,
                path = listOf(sourceNodeId, id)
            )
        }
        val outputNames = (listOfNotNull(approval.result?.name) + approval.declaredOutputs).distinct()
        outputNames.forEach { output ->
            val producer = availability.producerAt(path, output)
            ctx.registerProducer(producer, id)
            ctx.registerOutput(output, id, producer)
        }
        return ApprovalNode(
            id = id,
            mode = approval.mode,
            message = approval.params["message"]?.let(ExpressionRenderer::render)?.trim('"'),
            resultName = approval.result?.name,
            sourceId = approval.sourceId,
            sourceDescription = approval.sourceDescription,
            outputs = outputNames,
            dependsOn = explicitDependencies
        )
    }

    private fun planAction(
        action: ActionNode,
        ctx: Ctx,
        availability: FlowAvailabilityAnalysis,
        path: FlowStatementPath
    ): TaskNode {
        val id = ctx.id("${action.module}_${action.action}")
        val contract = registry.findAction(action.module, action.action)
            ?: throw MissingPlanningActionContractException(action.module, action.action)
        val effectModel = action.semanticEffects.ifEmpty {
            ModuleEffectCanonicalizer.canonicalize(contract.effects)
        }
        val effects = effectModel.map(SemanticEffect::resource).distinct()

        val referenced = linkedSetOf<String>()
        action.params.values.forEach { collectRoots(it, referenced) }
        action.target.path.firstOrNull()?.let { referenced += it }
        val dataDependencies = referenced.toList().sorted().mapNotNull { binding ->
            ctx.resolveProducer(availability, path, binding)?.let { sourceNodeId ->
                DataDependency(sourceNodeId, binding)
            }
        }.distinct()
        val explicitDependencies = action.dependsOn.mapNotNull { binding ->
            ctx.resolveOrderingProducer(availability, path, binding)
        }.distinct()
        val dependencies = (dataDependencies.map(DataDependency::sourceNodeId) + explicitDependencies)
            .distinct()
            .filter { it != id }

        val continuityRelations = contract.continuity.requires.map { requirement ->
            ctx.resolveContinuityRequirement(id, dependencies, requirement, action.module, action.action)
        }
        val declaredOrderingRelations = explicitDependencies
            .filter { it != id }
            .map { sourceNodeId ->
                PlanDependencyRelation(
                    sourceNodeId = sourceNodeId,
                    targetNodeId = id,
                    kind = PlanDependencyKind.ORDERING,
                    evidence = PlanDependencyEvidence.DECLARED_ORDERING,
                    path = listOf(sourceNodeId, id)
                )
            }
        val dataOrderingRelations = dataDependencies
            .map(DataDependency::sourceNodeId)
            .distinct()
            .filter { it != id }
            .map { sourceNodeId ->
                PlanDependencyRelation(
                    sourceNodeId = sourceNodeId,
                    targetNodeId = id,
                    kind = PlanDependencyKind.ORDERING,
                    evidence = PlanDependencyEvidence.DATA_REFERENCE,
                    path = listOf(sourceNodeId, id)
                )
            }
        val orderingRelations = (declaredOrderingRelations + dataOrderingRelations)
            .distinctBy(PlanDependencyRelations::relationKey)
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
        val continuityCapabilities = (valueRelations + continuityRelations).mapNotNull { it.kind.capability }
        val outputNames = (listOfNotNull(action.result?.name) + action.declaredOutputs).distinct()
        val task = TaskNode(
            id = id,
            module = action.module,
            action = action.action,
            target = action.target.path.joinToString("."),
            resultName = action.result?.name,
            dependsOn = dependencies,
            semanticCapability = action.semanticCapability,
            sourceId = action.sourceId,
            sourceDescription = action.sourceDescription,
            bindingMetadata = action.bindingMetadata.mapValues { (_, value) ->
                RuntimeParamRenderer.render(value, ctx.inputNames)
            },
            effectModel = effectModel,
            effects = effects,
            inputs = renderedParams,
            outputs = outputNames,
            destructive = contract.safety.destructive,
            safety = action.safety?.let { safety ->
                safety.rule + (safety.condition?.let { condition -> " " + ExpressionRenderer.render(condition) } ?: "")
            },
            params = renderedParams,
            requiredCapabilities = (inferRequiredCapabilities(action, contract) + continuityCapabilities).distinct()
        )
        ctx.registerTask(task, contract)
        outputNames.forEach { output ->
            val producer = availability.producerAt(path, output)
            ctx.registerProducer(producer, id)
            ctx.registerOutput(output, id, producer)
        }
        return task
    }

    private data class DataDependency(val sourceNodeId: String, val binding: String)

    /**
     * Renders action parameters and layers in every schema-declared value from the
     * targeted system configuration. Explicit action parameters always win.
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
        contract: ModuleActionContract
    ): List<String> = buildList {
        add("task.execute")
        addAll(contract.requiredCapabilities)
        if (action.module == "standard") {
            val operation = action.params["operation"]?.let(ExpressionRenderer::render)?.trim('"')
            if (!operation.isNullOrBlank()) add("standard.$operation")
        }
        if (contract.safety.destructive) add("safety.destructiveOperation")
    }.distinct()

    private fun collectDependencies(nodes: List<PlanNode>): List<String> = nodes.flatMap { node ->
        when (node) {
            is TaskNode -> node.dependsOn
            is ApprovalNode -> node.dependsOn
            is ConditionNode -> collectDependencies(node.then) + collectDependencies(node.otherwise)
            is LoopNode -> collectDependencies(node.body)
            is ParallelGroupNode -> node.branches.flatMap { collectDependencies(it.steps) }
            is MatchPlanNode -> node.cases.flatMap { collectDependencies(it.steps) } +
                collectDependencies(node.errorCase) + collectDependencies(node.defaultSteps)
            is RetryGroupNode -> collectDependencies(node.body)
            is TryPlanNode -> collectDependencies(node.body) + collectDependencies(node.errorHandler)
            else -> emptyList()
        }
    }.distinct()

    private fun collectRequiredCapabilities(nodes: List<PlanNode>): List<String> = nodes.flatMap { node ->
        when (node) {
            is TaskNode -> node.requiredCapabilities
            is ApprovalNode -> node.requiredCapabilities
            is ConditionNode -> listOf("condition.evaluate") +
                collectRequiredCapabilities(node.then) + collectRequiredCapabilities(node.otherwise)
            is LoopNode -> listOf("loop.dynamic") + collectRequiredCapabilities(node.body)
            is ParallelGroupNode -> listOf("parallel.dag") +
                node.branches.flatMap { collectRequiredCapabilities(it.steps) }
            is MatchPlanNode -> listOf("match.basic") +
                node.cases.flatMap { collectRequiredCapabilities(it.steps) } +
                collectRequiredCapabilities(node.errorCase) + collectRequiredCapabilities(node.defaultSteps)
            is RetryGroupNode -> listOf("retry.task") + collectRequiredCapabilities(node.body)
            is TryPlanNode -> listOf("errorHandlers.finally") +
                collectRequiredCapabilities(node.body) + collectRequiredCapabilities(node.errorHandler)
            else -> emptyList()
        }
    }.distinct()

    private fun collectRoots(expression: ExpressionNode, into: MutableSet<String>) {
        when (expression) {
            is ReferenceNode -> expression.path.firstOrNull()?.let { into += it }
            is BinaryExpressionNode -> {
                collectRoots(expression.left, into)
                collectRoots(expression.right, into)
            }
            is UnaryExpressionNode -> collectRoots(expression.operand, into)
            is UnaryPostfixExpressionNode -> collectRoots(expression.operand, into)
            is LogicalExpressionNode -> expression.operands.forEach { collectRoots(it, into) }
            is ListLiteralNode -> expression.items.forEach { collectRoots(it, into) }
            is MapLiteralNode -> expression.entries.values.forEach { collectRoots(it, into) }
            is TemplateStringNode -> expression.parts.forEach { collectRoots(it, into) }
            is CallExpressionNode -> expression.args.forEach { collectRoots(it, into) }
            is IndexExpressionNode -> {
                collectRoots(expression.target, into)
                collectRoots(expression.index, into)
            }
            is MemberExpressionNode -> collectRoots(expression.target, into)
            else -> Unit
        }
    }

    private class Ctx(
        val inputNames: Set<String>,
        val systems: Map<String, SystemNode> = emptyMap(),
        nodeIdNamespace: String? = null
    ) {
        private val encodedNodeIdNamespace = nodeIdNamespace?.let { value ->
            Base64.getUrlEncoder().withoutPadding()
                .encodeToString(value.toByteArray(StandardCharsets.UTF_8))
        }
        private val outputCandidates = mutableListOf<OutputCandidate>()
        val assumptions = mutableListOf<PlanAssumption>()
        val dependencyRelations = mutableListOf<PlanDependencyRelation>()
        private val producerNodeIds = mutableMapOf<FlowProducerIdentity, String>()
        private val taskDependencies = mutableMapOf<String, List<String>>()
        private val mergeDependencies = mutableMapOf<String, List<String>>()
        private val taskContinuity = mutableMapOf<String, ContinuityContract>()
        private val counters = mutableMapOf<String, Int>()

        fun id(prefix: String): String {
            val next = (counters[prefix] ?: 0) + 1
            counters[prefix] = next
            val localId = "${prefix}_$next"
            return encodedNodeIdNamespace?.let { namespace -> "wf_${namespace}__$localId" } ?: localId
        }

        fun registerProducer(producer: FlowProducerIdentity, nodeId: String) {
            val previous = producerNodeIds.put(producer, nodeId)
            check(previous == null || previous == nodeId) {
                "Flow producer '$producer' was mapped to both '$previous' and '$nodeId'."
            }
        }

        fun producerBindings(): Map<FlowProducerIdentity, String> = producerNodeIds.toMap()

        fun registerMerge(nodeId: String, dependencies: List<String>) {
            require(dependencies.size >= 2) { "Explicit merge '$nodeId' needs at least two producer nodes." }
            mergeDependencies[nodeId] = dependencies.distinct()
        }

        fun mergeDependencyNodeIds(): List<String> = mergeDependencies.values.flatten().distinct()

        fun registerOutput(name: String, nodeId: String, producer: FlowProducerIdentity) {
            outputCandidates += OutputCandidate(
                output = PlanOutput(name, sourceNodeId = nodeId),
                producer = producer
            )
        }

        fun visibleOutputs(exitState: FlowAvailabilityState): List<PlanOutput> =
            outputCandidates
                .filter { candidate ->
                    val state = exitState.binding(candidate.output.name)
                    state.safeToRead && !state.external && state.producers.singleOrNull() == candidate.producer
                }
                .distinctBy { candidate -> candidate.output.name.replace('-', '_') }
                .map(OutputCandidate::output)

        fun resolveProducer(
            availability: FlowAvailabilityAnalysis,
            path: FlowStatementPath,
            binding: String
        ): String? = resolveRegisteredProducer(
            producer = availability.producerBefore(path, binding),
            binding = binding,
            path = path
        )

        fun resolveOrderingProducer(
            availability: FlowAvailabilityAnalysis,
            path: FlowStatementPath,
            binding: String
        ): String? = resolveRegisteredProducer(
            producer = availability.orderingProducerBefore(path, binding),
            binding = binding,
            path = path
        )

        fun resolveProducerIdentity(
            producer: FlowProducerIdentity,
            binding: String,
            path: FlowStatementPath
        ): String = checkNotNull(resolveRegisteredProducer(producer, binding, path))

        private fun resolveRegisteredProducer(
            producer: FlowProducerIdentity?,
            binding: String,
            path: FlowStatementPath
        ): String? {
            producer ?: return null
            return checkNotNull(producerNodeIds[producer]) {
                "Flow producer '$producer' for '$binding' at '$path' has not been planned."
            }
        }

        fun registerTask(task: TaskNode, contract: ModuleActionContract) {
            taskDependencies[task.id] = task.dependsOn
            taskContinuity[task.id] = contract.continuity
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
                    stateLifetime = requirement.effectiveStateLifetime,
                    evidence = PlanDependencyEvidence.MODULE_CONTRACT,
                    resolution = PlanDependencyResolution.RESOLVED,
                    path = matches.single().path + targetNodeId,
                    evidenceReference = evidenceReference
                )
                0 -> PlanDependencyRelation(
                    targetNodeId = targetNodeId,
                    kind = requirement.kind.toPlanKind(),
                    channel = requirement.name,
                    stateLifetime = requirement.effectiveStateLifetime,
                    evidence = PlanDependencyEvidence.MODULE_CONTRACT,
                    resolution = PlanDependencyResolution.UNRESOLVED,
                    evidenceReference = evidenceReference
                )
                else -> PlanDependencyRelation(
                    targetNodeId = targetNodeId,
                    kind = requirement.kind.toPlanKind(),
                    channel = requirement.name,
                    stateLifetime = requirement.effectiveStateLifetime,
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
            mergeDependencies[nodeId]?.let { sources ->
                if (requirement.kind != ContinuityKind.VALUE) return emptyList()
                val bySource = sources.map { source ->
                    findProviders(source, requirement, LinkedHashSet(visited))
                }
                if (bySource.any(List<ContinuityPath>::isEmpty)) return emptyList()
                if (bySource.all { it.size == 1 }) {
                    return listOf(ContinuityPath(nodeId, listOf(nodeId)))
                }
                return bySource.flatten().distinctBy(ContinuityPath::providerNodeId)
            }
            val continuity = taskContinuity[nodeId] ?: return emptyList()
            if (continuity.provides.any { it.satisfies(requirement) }) {
                return listOf(ContinuityPath(nodeId, listOf(nodeId)))
            }
            if (continuity.preserves.none { it.satisfies(requirement) }) return emptyList()
            return taskDependencies[nodeId].orEmpty().flatMap { dependency ->
                findProviders(dependency, requirement, LinkedHashSet(visited)).map { providerPath ->
                    providerPath.copy(path = providerPath.path + nodeId)
                }
            }
        }

        private data class OutputCandidate(val output: PlanOutput, val producer: FlowProducerIdentity)

        private data class ContinuityPath(val providerNodeId: String, val path: List<String>)
    }
}

private fun ContinuityKind.toPlanKind(): PlanDependencyKind = when (this) {
    ContinuityKind.VALUE -> PlanDependencyKind.VALUE
    ContinuityKind.WORKSPACE -> PlanDependencyKind.WORKSPACE
    ContinuityKind.STATE -> PlanDependencyKind.STATE
}
