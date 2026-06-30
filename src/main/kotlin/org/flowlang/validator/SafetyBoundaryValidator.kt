package org.flowlang.validator

import org.flowlang.ast.ActionNode
import org.flowlang.ast.AggregateNode
import org.flowlang.ast.ErrorHandlerNode
import org.flowlang.ast.ExpressionNode
import org.flowlang.ast.FlowDocument
import org.flowlang.ast.ForNode
import org.flowlang.ast.IdentifierLiteralNode
import org.flowlang.ast.IfNode
import org.flowlang.ast.MatchNode
import org.flowlang.ast.ParallelNode
import org.flowlang.ast.ReferenceNode
import org.flowlang.ast.RetryNode
import org.flowlang.ast.StatementNode
import org.flowlang.ast.StringLiteralNode
import org.flowlang.ast.TransformNode
import org.flowlang.ast.TryNode
import org.flowlang.modules.Effects
import org.flowlang.modules.ModuleActionContract
import org.flowlang.modules.ModuleRegistry

/**
 * v0.8.5 safety-boundary gate.
 *
 * This gate strengthens safety decisions before target projection. It does not
 * execute anything and it does not invent target-specific syntax. It only blocks
 * high-risk Flow semantics that are already visible in the AST/module contracts.
 */
internal class SafetyBoundaryValidator(private val registry: ModuleRegistry = ModuleRegistry()) {
    fun validate(document: FlowDocument): List<ValidationIssue> {
        val issues = mutableListOf<ValidationIssue>()
        document.flow.steps.forEach { validateStatement(it, issues) }
        document.flow.errorHandler?.steps?.forEach { validateStatement(it, issues) }
        return issues
    }

    private fun validateStatement(stmt: StatementNode, issues: MutableList<ValidationIssue>) {
        when (stmt) {
            is ActionNode -> validateAction(stmt, issues)
            is IfNode -> (stmt.then + stmt.otherwise).forEach { validateStatement(it, issues) }
            is ForNode -> stmt.body.forEach { validateStatement(it, issues) }
            is ParallelNode -> stmt.branches.flatMap { it.steps }.forEach { validateStatement(it, issues) }
            is MatchNode -> {
                stmt.cases.flatMap { it.steps }.forEach { validateStatement(it, issues) }
                stmt.errorCase?.forEach { validateStatement(it, issues) }
                stmt.defaultSteps.forEach { validateStatement(it, issues) }
            }
            is RetryNode -> stmt.steps.forEach { validateStatement(it, issues) }
            is TryNode -> {
                stmt.steps.forEach { validateStatement(it, issues) }
                stmt.errorHandler.steps.forEach { validateStatement(it, issues) }
            }
            is ErrorHandlerNode -> stmt.steps.forEach { validateStatement(it, issues) }
            is TransformNode, is AggregateNode -> Unit
            else -> Unit
        }
    }

    private fun validateAction(action: ActionNode, issues: MutableList<ValidationIssue>) {
        val contract = registry.findAction(action.module, action.action) ?: return
        val hasApproval = action.safety?.rule == "requiresApproval"
        val rollbackSensitive = isRollbackSensitive(action)
        val productionSensitive = isProductionSensitiveMutation(action, contract)
        val approvalSensitive = contract.safety.requiresApproval || contract.safety.destructive || rollbackSensitive || productionSensitive

        if (approvalSensitive && !hasApproval) {
            val code = when {
                rollbackSensitive -> "ROLLBACK_APPROVAL_REQUIRED"
                productionSensitive -> "PRODUCTION_APPROVAL_REQUIRED"
                else -> "APPROVAL_REQUIRED"
            }
            val reason = when {
                rollbackSensitive -> "Rollback-sensitive action"
                productionSensitive -> "Production-sensitive mutating action"
                contract.safety.destructive -> "Destructive action"
                else -> "Action contract"
            }
            issues += ValidationIssue(
                level = "error",
                code = code,
                message = "$reason '${action.module}.${action.action}' requires safety: requiresApproval before target projection.",
                location = action.sourceLocation
            )
        }
    }

    private fun isRollbackSensitive(action: ActionNode): Boolean {
        if (action.module == "standard" && action.action == "rollback") return true
        if (action.module == "standard" && action.action == "execute") {
            val operation = action.params["operation"] ?: return false
            return stringValue(operation)?.equals("rollback", ignoreCase = true) == true
        }
        return false
    }

    private fun isProductionSensitiveMutation(action: ActionNode, contract: ModuleActionContract): Boolean {
        if (!contract.effects.mutatesExternalState()) return false
        return action.params.any { (name, value) ->
            name in productionBoundaryParamNames && isProductionLiteral(value)
        }
    }

    private fun Effects.mutatesExternalState(): Boolean =
        writes.isNotEmpty() || creates.isNotEmpty() || updates.isNotEmpty() || deletes.isNotEmpty()

    private fun isProductionLiteral(expr: ExpressionNode): Boolean =
        stringValue(expr)?.lowercase() in productionValues

    private fun stringValue(expr: ExpressionNode): String? = when (expr) {
        is StringLiteralNode -> expr.value
        is IdentifierLiteralNode -> expr.value
        is ReferenceNode -> expr.path.singleOrNull()
        else -> null
    }

    private companion object {
        val productionBoundaryParamNames = setOf("environment", "env", "namespace", "cluster", "stage")
        val productionValues = setOf("prod", "production", "live")
    }
}
