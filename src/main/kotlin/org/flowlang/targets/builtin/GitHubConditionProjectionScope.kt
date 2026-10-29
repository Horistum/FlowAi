package org.flowlang.targets.builtin

import org.flowlang.capabilities.SupportLevel
import org.flowlang.capabilities.TargetCapability
import org.flowlang.compiler.CompilationAuthorization
import org.flowlang.generators.manifest.TargetNativeProjectionCatalog
import org.flowlang.generators.manifest.TargetProjectionCapabilityResolver
import org.flowlang.generators.manifest.TargetStructuralProjectionKind
import org.flowlang.generators.manifest.TargetStructuralProjectionSupportScope
import org.flowlang.planner.ConditionNode
import org.flowlang.planner.TaskNode
import org.flowlang.planner.ApprovalNode
import org.flowlang.planner.PlanNode
import org.flowlang.ast.*
import org.flowlang.parser.ExpressionParser

/** Condition eligibility is independent of leaf implementation, workflow size and branch depth. */
class GitHubConditionProjectionScope(private val catalog: TargetNativeProjectionCatalog?) : TargetProjectionCapabilityResolver {
    override fun resolve(authorization: CompilationAuthorization, target: String, declared: TargetCapability): TargetCapability {
        authorization.requireIntegrity()
        if (target != "github-actions" || declared.target != target || catalog == null || catalog.target != target ||
            catalog.structuralDefinitions.none { it.structure == TargetStructuralProjectionKind.CONDITION &&
                it.kind == "github-job-condition" && it.reference == "if" &&
                it.supportScope == TargetStructuralProjectionSupportScope.PLAN_SCOPED }) return declared
        val plan = authorization.executionPlan
        if (authorization.graph.workflows.size != 1 || authorization.graph.workflows.single().failurePolicy.handler != null) return declared
        // Event/schedule contexts do not supply workflow_dispatch inputs. Do not silently
        // turn a missing input into GitHub's empty string or an invented Boolean default.
        val booleanInputs = if (plan.triggers.any { it.type != "MANUAL" }) emptySet() else
            plan.inputs.filter { it.type == "boolean" && (it.required || it.defaultValue in setOf("true", "false")) }
                .map { it.name }.toSet()
        var hasCondition = false
        fun supported(nodes: List<PlanNode>): Boolean = nodes.all { node -> when (node) {
            is ConditionNode -> {
                hasCondition = true
                GitHubBooleanCondition.supports(node.condition, booleanInputs) && supported(node.then) && supported(node.otherwise)
            }
            // Native leaf, value transport and control availability retain their independent gates.
            is TaskNode, is ApprovalNode -> true
            else -> false
        } }
        if (!supported(plan.nodes) || !hasCondition) return declared
        return declared.copy(features = declared.features + mapOf("condition.evaluate" to SupportLevel.SUPPORTED,
            "conditions.inline" to SupportLevel.SUPPORTED),
            notes = declared.notes + "Boolean conditions compose across native leaves, nested branches and ordering successors; output-dependent predicates and cross-job value transport are not supplied by this contract.")
    }
}

/** Avoid GitHub's implicit value coercions and ambient contexts at the job guard boundary. */
internal object GitHubBooleanCondition {
    /** Recheck the compiler's enclosing guards, without inferring arbitrary logical implication. */
    fun retainsGuard(consumer: String?, producer: String?): Boolean {
        if (producer == null) return true
        if (consumer == null) return false
        fun conjuncts(node: ExpressionNode): Set<ExpressionNode> =
            if (node is LogicalExpressionNode && node.operator == "and") node.operands.flatMap { conjuncts(it) }.toSet()
            else setOf(guardShape(node))
        return runCatching {
            conjuncts(ExpressionParser.parseSource(consumer)).containsAll(conjuncts(ExpressionParser.parseSource(producer)))
        }.getOrDefault(false)
    }

    // Compare expression structure, including parentheses/precedence, without source locations.
    private fun guardShape(node: ExpressionNode): ExpressionNode = when (node) {
        is BooleanLiteralNode -> node
        is ReferenceNode -> node.copy(location = null)
        is UnaryExpressionNode -> node.copy(operand = guardShape(node.operand))
        is BinaryExpressionNode -> node.copy(left = guardShape(node.left), right = guardShape(node.right), location = null)
        is LogicalExpressionNode -> node.copy(operands = node.operands.map(::guardShape), location = null)
        else -> error("Unsupported Boolean guard node.")
    }

    fun supports(source: String, inputs: Set<String>): Boolean = runCatching {
        supports(ExpressionParser.parseSource(source), inputs)
    }.getOrDefault(false)

    private fun supports(node: ExpressionNode, inputs: Set<String>): Boolean = when (node) {
        is BooleanLiteralNode -> true
        is ReferenceNode -> !node.safe && node.scope == "auto" && node.path.size == 1 && node.path.single() in inputs
        is UnaryExpressionNode -> node.operator == "not" && supports(node.operand, inputs)
        is LogicalExpressionNode -> node.operator in setOf("and", "or") && node.operands.isNotEmpty() &&
            node.operands.all { supports(it, inputs) }
        is BinaryExpressionNode -> node.operator in setOf("==", "!=") && supports(node.left, inputs) && supports(node.right, inputs)
        else -> false
    }
}
