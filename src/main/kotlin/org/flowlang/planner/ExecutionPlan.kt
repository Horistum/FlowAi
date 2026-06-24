package org.flowlang.planner

/**
 * Execution plan (docs/07). The plan preserves control-flow structure (conditions,
 * loops, parallel groups, match, retry, try, approvals, data ops) instead of
 * flattening everything to a linear task list. A flattened [tasks] view is exposed
 * for the runtime and the draft generators.
 */
data class ExecutionPlan(
    val flowName: String,
    val planVersion: String = org.flowlang.standard.FlowStandardVersions.EXECUTION_PLAN_VERSION,
    /** Runtime inputs carried from Flow AST to target manifests/renderers. */
    val inputs: List<PlanInput> = emptyList(),
    val outputs: List<PlanOutput> = emptyList(),
    val dependencies: List<String> = emptyList(),
    val requiredCapabilities: List<String> = emptyList(),
    val targetHints: Map<String, String> = emptyMap(),
    val assumptions: List<PlanAssumption> = emptyList(),
    val nodes: List<PlanNode> = emptyList()
) {
    /** Depth-first flattening of all concrete action tasks. */
    val tasks: List<TaskNode> get() = collectTasks(nodes)

    private fun collectTasks(ns: List<PlanNode>): List<TaskNode> = ns.flatMap { n ->
        when (n) {
            is TaskNode -> listOf(n)
            is ConditionNode -> collectTasks(n.then) + collectTasks(n.otherwise)
            is LoopNode -> collectTasks(n.body)
            is ParallelGroupNode -> n.branches.flatMap { collectTasks(it.steps) }
            is MatchPlanNode -> n.cases.flatMap { collectTasks(it.steps) } + collectTasks(n.errorCase) + collectTasks(n.defaultSteps)
            is RetryGroupNode -> collectTasks(n.body)
            is TryPlanNode -> collectTasks(n.body) + collectTasks(n.errorHandler)
            else -> emptyList()
        }
    }
}

data class PlanInput(
    val name: String,
    val type: String = "text",
    val required: Boolean = false,
    val defaultValue: String? = null,
    val choices: List<String> = emptyList()
)

data class PlanOutput(
    val name: String,
    val type: String = "any",
    val sourceNodeId: String? = null
)

data class PlanAssumption(
    val id: String,
    val message: String,
    val confidence: Double = 0.5
)

sealed interface PlanNode { val id: String; val kind: String }

data class TaskNode(
    override val id: String,
    override val kind: String = "Task",
    val module: String,
    val action: String,
    val target: String,
    val resultName: String? = null,
    val dependsOn: List<String> = emptyList(),
    val dependencies: List<String> = dependsOn,
    val effects: List<String> = emptyList(),
    val inputs: Map<String, String> = emptyMap(),
    val outputs: List<String> = emptyList(),
    val destructive: Boolean = false,
    val safety: String? = null,
    val params: Map<String, String> = emptyMap(),
    val requiredCapabilities: List<String> = emptyList(),
    val targetHints: Map<String, String> = emptyMap(),
    val assumptions: List<String> = emptyList()
) : PlanNode

data class ConditionNode(
    override val id: String,
    override val kind: String = "Condition",
    val condition: String,
    val then: List<PlanNode> = emptyList(),
    val otherwise: List<PlanNode> = emptyList()
) : PlanNode

data class LoopNode(
    override val id: String,
    override val kind: String = "Loop",
    val item: String,
    val source: String,
    val body: List<PlanNode> = emptyList()
) : PlanNode

data class ParallelGroupNode(
    override val id: String,
    override val kind: String = "ParallelGroup",
    val failFast: Boolean = true,
    val branches: List<PlanBranch> = emptyList()
) : PlanNode

data class PlanBranch(val name: String?, val steps: List<PlanNode> = emptyList())

data class MatchPlanNode(
    override val id: String,
    override val kind: String = "Match",
    val source: String,
    val cases: List<MatchCase> = emptyList(),
    val errorCase: List<PlanNode> = emptyList(),
    val defaultSteps: List<PlanNode> = emptyList()
) : PlanNode

data class MatchCase(val condition: String, val steps: List<PlanNode> = emptyList())

data class RetryGroupNode(
    override val id: String,
    override val kind: String = "Retry",
    val max: Int,
    val delay: String,
    val backoff: String,
    val body: List<PlanNode> = emptyList()
) : PlanNode

data class TryPlanNode(
    override val id: String,
    override val kind: String = "Try",
    val body: List<PlanNode> = emptyList(),
    val errorHandler: List<PlanNode> = emptyList()
) : PlanNode

data class ApprovalNode(
    override val id: String,
    override val kind: String = "Approval",
    val mode: String = "manual",
    val message: String? = null,
    val resultName: String? = null,
    val dependsOn: List<String> = emptyList(),
    val dependencies: List<String> = dependsOn,
    val requiredCapabilities: List<String> = listOf("approval.manual"),
    val safety: String? = "requiresApproval"
) : PlanNode

data class DataOpNode(
    override val id: String,
    override val kind: String,         // "Transform" | "Validate" | "Aggregate"
    val target: String? = null,
    val detail: String? = null
) : PlanNode

data class ControlNode(
    override val id: String,
    override val kind: String,         // "Fail" | "Skip" | "Set"
    val detail: String? = null
) : PlanNode
