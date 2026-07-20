package org.flowlang.planner

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
    val assumptions: List<PlanAssumption> = emptyList(),
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
            assumptions = plan.assumptions,
            nodes = plan.nodes.map { canonicalizeNode(it) },
            dependencyRelations = plan.dependencyRelations
        )

    private fun canonicalizeNode(node: PlanNode): CanonicalPlanNode = when (node) {
        is TaskNode -> CanonicalPlanNode(
            id = node.id,
            kind = taskKind(node),
            module = node.module,
            action = node.action,
            target = node.target,
            resultName = node.resultName,
            dependencies = node.dependencies,
            inputs = node.inputs.ifEmpty { node.params },
            outputs = node.outputs,
            effects = node.effects,
            safety = node.safety,
            destructive = node.destructive,
            requiredCapabilities = node.requiredCapabilities,
            targetHints = node.targetHints,
            assumptions = node.assumptions
        )
        is ApprovalNode -> CanonicalPlanNode(
            id = node.id,
            kind = "approval",
            resultName = node.resultName,
            dependencies = node.dependencies,
            inputs = mapOfNotNull("message" to node.message, "mode" to node.mode),
            safety = node.safety,
            requiredCapabilities = node.requiredCapabilities
        )
        is ConditionNode -> CanonicalPlanNode(
            id = node.id,
            kind = "condition",
            condition = node.condition,
            then = node.then.map { canonicalizeNode(it) },
            otherwise = node.otherwise.map { canonicalizeNode(it) },
            requiredCapabilities = listOf("condition.evaluate")
        )
        is LoopNode -> CanonicalPlanNode(
            id = node.id,
            kind = "loop",
            item = node.item,
            source = node.source,
            body = node.body.map { canonicalizeNode(it) },
            requiredCapabilities = listOf("loop.dynamic")
        )
        is ParallelGroupNode -> CanonicalPlanNode(
            id = node.id,
            kind = "parallel",
            failFast = node.failFast,
            branches = node.branches.map { CanonicalPlanBranch(it.name, it.steps.map { step -> canonicalizeNode(step) }) },
            requiredCapabilities = listOf("parallel.dag")
        )
        is MatchPlanNode -> CanonicalPlanNode(
            id = node.id,
            kind = "match",
            source = node.source,
            cases = node.cases.map { CanonicalMatchCase(it.condition, it.steps.map { step -> canonicalizeNode(step) }) },
            errorCase = node.errorCase.map { canonicalizeNode(it) },
            defaultSteps = node.defaultSteps.map { canonicalizeNode(it) },
            requiredCapabilities = listOf("match.basic")
        )
        is RetryGroupNode -> CanonicalPlanNode(
            id = node.id,
            kind = "retry",
            max = node.max,
            delay = node.delay,
            backoff = node.backoff,
            body = node.body.map { canonicalizeNode(it) },
            requiredCapabilities = listOf("retry.task")
        )
        is TryPlanNode -> CanonicalPlanNode(
            id = node.id,
            kind = "try",
            body = node.body.map { canonicalizeNode(it) },
            errorHandler = node.errorHandler.map { canonicalizeNode(it) },
            requiredCapabilities = listOf("errorHandlers.finally")
        )
        is DataOpNode -> CanonicalPlanNode(
            id = node.id,
            kind = node.kind.lowercase(),
            target = node.target,
            detail = node.detail
        )
        is ControlNode -> CanonicalPlanNode(
            id = node.id,
            kind = node.kind.lowercase(),
            detail = node.detail
        )
    }

    private fun taskKind(node: TaskNode): String = when {
        node.module == "standard" && node.action == "rollback" -> "rollback"
        node.module == "notify" -> "notification"
        node.requiredCapabilities.any { it.startsWith("secret.") } -> "secret"
        node.effects.any { it.contains("artifact", ignoreCase = true) } -> "artifact"
        else -> "task"
    }

    private fun mapOfNotNull(vararg pairs: Pair<String, String?>): Map<String, String> =
        pairs.mapNotNull { (key, value) -> value?.let { key to it } }.toMap()
}
