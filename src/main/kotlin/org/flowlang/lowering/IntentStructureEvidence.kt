package org.flowlang.lowering

import org.flowlang.ast.CallExpressionNode
import org.flowlang.identity.SemanticId
import org.flowlang.intent.*
import org.flowlang.planner.*
import org.flowlang.planner.ExpressionRenderer

/** Compare authored structure with actual projected nodes, not with copied source metadata. */
internal object IntentStructureEvidence {
    private fun record(vararg values: String): String = values.joinToString("") { "${it.length}:$it" }
    fun source(body: List<IntentStatement>, expressions: IntentExpressionParser): String = record(*body.map { statement ->
        fun render(value: IntentValue) = ExpressionRenderer.render(IntentValueExpressionLowering.lower(value, expressions))
        fun symbol() = IntentStepIdentity.resultSymbol(SemanticId.of(statement.id))
        when (statement) {
            is IntentOperation -> record("operation", statement.id)
            is IntentBranch -> record("branch", render(statement.condition), source(statement.then, expressions), source(statement.otherwise, expressions))
            is IntentLocalValue -> record("set", "${symbol()} = ${render(statement.value)}")
            is IntentMerge -> record("set", "${symbol()} = " + ExpressionRenderer.render(CallExpressionNode(function = "merge",
                args = statement.sources.map { IntentValueExpressionLowering.lower(it, expressions) })))
        }
    }.toTypedArray())
    fun plan(nodes: List<PlanNode>): String = record(*nodes.map { node ->
        when (node) {
            is TaskNode -> record("operation", requireNotNull(node.sourceId))
            is ApprovalNode -> record("operation", requireNotNull(node.sourceId))
            is ConditionNode -> record("branch", node.condition, plan(node.then), plan(node.otherwise))
            is ControlNode -> { require(node.kind == "Set"); record("set", requireNotNull(node.detail)) }
            else -> error("Unsupported structured lowering node: ${node.javaClass.simpleName}")
        }
    }.toTypedArray())
}
