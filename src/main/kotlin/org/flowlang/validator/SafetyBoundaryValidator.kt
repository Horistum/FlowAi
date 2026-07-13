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
import org.flowlang.ast.WhenNode
import org.flowlang.modules.Effects
import org.flowlang.modules.ModuleActionContract
import org.flowlang.modules.ModuleRegistry
import org.flowlang.safety.EnvironmentClassificationEvidence
import org.flowlang.safety.EnvironmentSafetyPolicy
import org.flowlang.safety.EnvironmentSensitivity

/**
 * Safety-boundary gate evaluated before target projection.
 *
 * Environment sensitivity is supplied by safety policy notes. This validator
 * does not own environment aliases, target environment names or runtime lookup.
 */
class SafetyBoundaryValidator(
    private val registry: ModuleRegistry = ModuleRegistry(),
    private val environmentPolicy: EnvironmentSafetyPolicy? = null
) {
    fun validate(document: FlowDocument): List<ValidationIssue> {
        val issues = mutableListOf<ValidationIssue>()
        document.flow.steps.forEach { validateStatement(it, issues, insideErrorHandler = false) }
        document.flow.errorHandler?.steps?.forEach { validateStatement(it, issues, insideErrorHandler = true) }
        return issues
    }

    private fun validateStatement(stmt: StatementNode, issues: MutableList<ValidationIssue>, insideErrorHandler: Boolean) {
        when (stmt) {
            is ActionNode -> {
                validateAction(stmt, issues, insideErrorHandler)
                stmt.handler?.rules?.forEach { rule ->
                    if (rule is WhenNode) {
                        rule.steps.forEach { validateStatement(it, issues, insideErrorHandler || rule.isError) }
                    }
                }
            }
            is IfNode -> (stmt.then + stmt.otherwise).forEach { validateStatement(it, issues, insideErrorHandler) }
            is ForNode -> stmt.body.forEach { validateStatement(it, issues, insideErrorHandler) }
            is ParallelNode -> stmt.branches.flatMap { it.steps }.forEach { validateStatement(it, issues, insideErrorHandler) }
            is MatchNode -> {
                stmt.cases.flatMap { it.steps }.forEach { validateStatement(it, issues, insideErrorHandler) }
                stmt.errorCase?.forEach { validateStatement(it, issues, insideErrorHandler = true) }
                stmt.defaultSteps.forEach { validateStatement(it, issues, insideErrorHandler) }
            }
            is RetryNode -> stmt.steps.forEach { validateStatement(it, issues, insideErrorHandler) }
            is TryNode -> {
                stmt.steps.forEach { validateStatement(it, issues, insideErrorHandler) }
                stmt.errorHandler.steps.forEach { validateStatement(it, issues, insideErrorHandler = true) }
            }
            is ErrorHandlerNode -> stmt.steps.forEach { validateStatement(it, issues, insideErrorHandler = true) }
            is TransformNode, is AggregateNode -> Unit
            else -> Unit
        }
    }

    private fun validateAction(action: ActionNode, issues: MutableList<ValidationIssue>, insideErrorHandler: Boolean) {
        val contract = registry.findAction(action.module, action.action) ?: return
        val hasApproval = action.safety?.rule == "requiresApproval"
        val rollbackSensitive = isRollbackSensitive(action)
        val environmentEvidence = environmentEvidence(action, contract)
        val environmentSensitive = environmentEvidence?.sensitivity == EnvironmentSensitivity.SENSITIVE
        val approvalSensitive = contract.safety.requiresApproval ||
            contract.safety.destructive ||
            environmentSensitive ||
            (rollbackSensitive && !insideErrorHandler)

        if (approvalSensitive && !hasApproval) {
            val code = when {
                rollbackSensitive -> "ROLLBACK_APPROVAL_REQUIRED"
                environmentSensitive -> "ENVIRONMENT_APPROVAL_REQUIRED"
                else -> "APPROVAL_REQUIRED"
            }
            val reason = when {
                rollbackSensitive -> "Rollback-sensitive action"
                environmentSensitive -> environmentReason(environmentEvidence!!)
                contract.safety.destructive -> "Destructive action"
                else -> "Action contract"
            }
            issues += ValidationIssue(
                "error",
                code,
                "$reason '${action.module}.${action.action}' requires safety: requiresApproval before target projection.",
                action.sourceLocation
            )
        }
    }

    private fun environmentEvidence(
        action: ActionNode,
        contract: ModuleActionContract
    ): EnvironmentClassificationEvidence? {
        val policy = environmentPolicy ?: return null
        if (!contract.effects.mutatesExternalState()) return null
        val scalarParams = action.params.mapNotNull { (name, expression) ->
            stringValue(expression)?.let { value -> name to value }
        }.toMap()
        return policy.classify(scalarParams)
    }

    private fun environmentReason(evidence: EnvironmentClassificationEvidence): String =
        "Sensitive environment evidence ${evidence.parameterName}=${evidence.parameterValue} " +
            "matched policy ${evidence.policyPackageId}@${evidence.policyPackageVersion}/${evidence.ruleId}."

    private fun isRollbackSensitive(action: ActionNode): Boolean {
        if (action.module == "standard" && action.action == "rollback") return true
        if (action.module == "standard" && action.action == "execute") {
            val operation = action.params["operation"] ?: return false
            return stringValue(operation)?.equals("rollback", ignoreCase = true) == true
        }
        return false
    }

    private fun Effects.mutatesExternalState(): Boolean =
        writes.isNotEmpty() || creates.isNotEmpty() || updates.isNotEmpty() || deletes.isNotEmpty()

    private fun stringValue(expr: ExpressionNode): String? = when (expr) {
        is StringLiteralNode -> expr.value
        is IdentifierLiteralNode -> expr.value
        is ReferenceNode -> expr.path.singleOrNull()
        else -> null
    }
}
