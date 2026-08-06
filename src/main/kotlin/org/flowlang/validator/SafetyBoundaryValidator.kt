package org.flowlang.validator

import org.flowlang.ast.*

/**
 * Conservative safety validator. It does not attempt to divine intention from
 * free-form prose; explicit destructive operations must carry explicit safety.
 */
class SafetyBoundaryValidator {
    fun validate(document: FlowDocument): List<ValidationIssue> {
        val issues = mutableListOf<ValidationIssue>()
        validateStatements(document.flow.steps, "flow.steps", issues)
        validateStatements(document.flow.onSuccess, "flow.onSuccess", issues)
        validateStatements(document.flow.finallySteps, "flow.finally", issues)
        document.flow.onFailure?.let { validateStatements(it.steps, "flow.onFailure", issues) }
        return issues
    }

    private fun validateStatements(
        statements: List<StatementNode>,
        path: String,
        issues: MutableList<ValidationIssue>,
        depth: Int = 0
    ) {
        if (depth > MAX_STATEMENT_NESTING_DEPTH) {
            issues += ValidationIssue(
                code = "SAFETY_NESTING_DEPTH_EXCEEDED",
                message = "Statement nesting exceeds maximum safety validation depth $MAX_STATEMENT_NESTING_DEPTH",
                path = path,
                severity = ValidationSeverity.ERROR
            )
            return
        }
        statements.forEachIndexed { index, statement ->
            val statementPath = "$path[$index]"
            when (statement) {
                is TaskNode -> validateTask(statement, statementPath, issues)
                is IfNode -> {
                    validateStatements(statement.then, "$statementPath.then", issues, depth + 1)
                    validateStatements(statement.otherwise, "$statementPath.else", issues, depth + 1)
                }
                is ForNode -> validateStatements(statement.body, "$statementPath.body", issues, depth + 1)
                is ParallelNode -> statement.branches.forEachIndexed { branchIndex, branch ->
                    validateStatements(branch.steps, "$statementPath.branches[$branchIndex]", issues, depth + 1)
                }
                is MatchNode -> {
                    statement.cases.forEachIndexed { caseIndex, case ->
                        validateStatements(case.steps, "$statementPath.cases[$caseIndex]", issues, depth + 1)
                    }
                    validateStatements(statement.errorCase, "$statementPath.error", issues, depth + 1)
                    validateStatements(statement.default, "$statementPath.default", issues, depth + 1)
                }
                is RetryNode -> validateStatements(statement.body, "$statementPath.body", issues, depth + 1)
                is TryNode -> {
                    validateStatements(statement.body, "$statementPath.body", issues, depth + 1)
                    validateStatements(statement.catch, "$statementPath.catch", issues, depth + 1)
                }
                is SetNode, is EmitNode, is SkipNode, is FailNode, is WaitNode, is ApprovalNode -> Unit
            }
        }
    }

    private fun validateTask(task: TaskNode, path: String, issues: MutableList<ValidationIssue>) {
        if (!task.destructive) return
        if (task.safety.isNullOrBlank()) {
            issues += ValidationIssue(
                code = "DESTRUCTIVE_TASK_WITHOUT_SAFETY",
                message = "Destructive task '${task.module}.${task.action}' requires an explicit safety boundary",
                path = path,
                severity = ValidationSeverity.ERROR
            )
        }
    }

    companion object {
        internal const val MAX_STATEMENT_NESTING_DEPTH = 128
    }
}
