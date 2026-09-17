package org.flowlang.validator

import org.flowlang.ast.ActionNode
import org.flowlang.ast.AggregateNode
import org.flowlang.ast.ApproveNode
import org.flowlang.ast.BinaryExpressionNode
import org.flowlang.ast.ErrorHandlerNode
import org.flowlang.ast.ExpressionNode
import org.flowlang.ast.ForNode
import org.flowlang.ast.IdentifierLiteralNode
import org.flowlang.ast.IfNode
import org.flowlang.ast.LogicalExpressionNode
import org.flowlang.ast.MatchNode
import org.flowlang.ast.ParallelNode
import org.flowlang.ast.ReferenceNode
import org.flowlang.ast.RetryNode
import org.flowlang.ast.StatementNode
import org.flowlang.ast.StringLiteralNode
import org.flowlang.ast.TransformNode
import org.flowlang.ast.TryNode
import org.flowlang.ast.WhenNode
import org.flowlang.ast.FlowDocument
import org.flowlang.modules.Effects
import org.flowlang.modules.ModuleActionContract
import org.flowlang.modules.ModuleCatalog
import org.flowlang.modules.ModuleCatalogIndex
import org.flowlang.safety.EnvironmentClassificationEvidence
import org.flowlang.safety.EnvironmentParameterEvidence
import org.flowlang.safety.EnvironmentSafetyPolicy
import org.flowlang.safety.EnvironmentSensitivity
import org.flowlang.safety.EnvironmentValueKind

/**
 * Production safety gate evaluated before planning and target projection.
 *
 * Approval evidence is reachability-scoped. A conditional approval whose
 * predicate selects a policy-declared sensitive environment authorizes only
 * the downstream environment boundary. A finite `onlyIf` domain may replace
 * approval only when every executable value is policy-classified non-sensitive.
 */
class SafetyBoundaryValidator(
    registry: ModuleCatalog,
    private val environmentPolicy: EnvironmentSafetyPolicy
) {
    private val registry = ModuleCatalogIndex.capture(registry)

    private data class ApprovalState(
        val unconditional: Boolean = false,
        val sensitiveEnvironmentGuard: Boolean = false
    )

    fun validate(document: FlowDocument): List<ValidationIssue> {
        val issues = mutableListOf<ValidationIssue>()
        val environmentDomains = document.flow.input.mapNotNull { input ->
            if (!environmentPolicy.recognizesParameter(input.name)) return@mapNotNull null
            val values = input.valueType.values.mapNotNull { value ->
                when (value) {
                    is StringLiteralNode -> value.value
                    is IdentifierLiteralNode -> value.value
                    else -> null
                }
            }.toSet()
            if (values.isEmpty()) null else input.name to values
        }.toMap()

        validateStatements(
            document.flow.steps,
            issues,
            insideErrorHandler = false,
            inheritedApproval = ApprovalState(),
            environmentDomains = environmentDomains
        )
        document.flow.errorHandler?.steps?.let { handlerSteps ->
            validateStatements(
                handlerSteps,
                issues,
                insideErrorHandler = true,
                inheritedApproval = ApprovalState(),
                environmentDomains = environmentDomains
            )
        }
        return issues
    }

    private fun validateStatements(
        statements: List<StatementNode>,
        issues: MutableList<ValidationIssue>,
        insideErrorHandler: Boolean,
        inheritedApproval: ApprovalState,
        environmentDomains: Map<String, Set<String>>,
        depth: Int = 0
    ): ApprovalState {
        if (depth >= MAX_STATEMENT_NESTING_DEPTH) {
            issues += ValidationIssue(
                "error",
                "SAFETY_NESTING_DEPTH_EXCEEDED",
                "Statement nesting exceeds maximum safety validation depth $MAX_STATEMENT_NESTING_DEPTH.",
                null
            )
            return inheritedApproval
        }

        var approval = inheritedApproval
        statements.forEach { statement ->
            when (statement) {
                is ApproveNode -> approval = approval.copy(unconditional = true)
                is ActionNode -> {
                    validateAction(statement, issues, insideErrorHandler, approval, environmentDomains)
                    statement.handler?.rules?.forEach { rule ->
                        if (rule is WhenNode) {
                            validateStatements(
                                rule.steps,
                                issues,
                                insideErrorHandler || rule.isError,
                                approval,
                                environmentDomains,
                                depth + 1
                            )
                        }
                    }
                }
                is IfNode -> {
                    validateStatements(statement.then, issues, insideErrorHandler, approval, environmentDomains, depth + 1)
                    validateStatements(statement.otherwise, issues, insideErrorHandler, approval, environmentDomains, depth + 1)
                    if (isSensitiveEnvironmentApprovalGuard(statement)) {
                        approval = approval.copy(sensitiveEnvironmentGuard = true)
                    }
                }
                is ForNode -> validateStatements(statement.body, issues, insideErrorHandler, approval, environmentDomains, depth + 1)
                is ParallelNode -> statement.branches.forEach { branch ->
                    validateStatements(branch.steps, issues, insideErrorHandler, approval, environmentDomains, depth + 1)
                }
                is MatchNode -> {
                    statement.cases.forEach { case ->
                        validateStatements(case.steps, issues, insideErrorHandler, approval, environmentDomains, depth + 1)
                    }
                    statement.errorCase?.let { errorSteps ->
                        validateStatements(
                            errorSteps,
                            issues,
                            insideErrorHandler = true,
                            inheritedApproval = approval,
                            environmentDomains = environmentDomains,
                            depth = depth + 1
                        )
                    }
                    validateStatements(statement.defaultSteps, issues, insideErrorHandler, approval, environmentDomains, depth + 1)
                }
                is RetryNode -> validateStatements(statement.steps, issues, insideErrorHandler, approval, environmentDomains, depth + 1)
                is TryNode -> {
                    validateStatements(statement.steps, issues, insideErrorHandler, approval, environmentDomains, depth + 1)
                    validateStatements(
                        statement.errorHandler.steps,
                        issues,
                        insideErrorHandler = true,
                        inheritedApproval = approval,
                        environmentDomains = environmentDomains,
                        depth = depth + 1
                    )
                }
                is ErrorHandlerNode -> validateStatements(
                    statement.steps,
                    issues,
                    insideErrorHandler = true,
                    inheritedApproval = approval,
                    environmentDomains = environmentDomains,
                    depth = depth + 1
                )
                is TransformNode, is AggregateNode -> Unit
                else -> Unit
            }
        }
        return approval
    }

    private fun validateAction(
        action: ActionNode,
        issues: MutableList<ValidationIssue>,
        insideErrorHandler: Boolean,
        approval: ApprovalState,
        environmentDomains: Map<String, Set<String>>
    ) {
        val contract = registry.findAction(action.module, action.action)
        if (contract == null) {
            issues += ValidationIssue(
                level = "error",
                code = "SAFETY_ACTION_CONTRACT_UNKNOWN",
                message = "Safety evaluation cannot authorize unknown action '${action.module}.${action.action}'. Register and validate the action contract before planning or target projection.",
                location = action.sourceLocation
            )
            return
        }
        val actionApproval = action.safety?.let { safety ->
            safety.rule.trim() == "requiresApproval" && safety.condition == null
        } == true
        val nonSensitiveEnvironmentGuard = action.safety?.let { safety ->
            safety.rule.trim() == "onlyIf" &&
                safety.condition?.let { condition ->
                    conditionProvesFiniteNonSensitiveEnvironment(condition, environmentDomains)
                } == true
        } == true
        val hasUnconditionalApproval = approval.unconditional || actionApproval
        val hasEnvironmentApproval = hasUnconditionalApproval || approval.sensitiveEnvironmentGuard
        val hasEnvironmentMitigation = hasEnvironmentApproval || nonSensitiveEnvironmentGuard
        val rollbackSensitive = isRollbackSensitive(action)
        val environmentEvidence = environmentEvidence(action, contract)

        if (environmentEvidence?.sensitivity == EnvironmentSensitivity.UNKNOWN && !hasEnvironmentMitigation) {
            issues += ValidationIssue(
                level = "error",
                code = "ENVIRONMENT_CLASSIFICATION_UNKNOWN",
                message = unknownEnvironmentReason(action, environmentEvidence),
                location = action.sourceLocation
            )
        }

        val environmentSensitive = environmentEvidence?.sensitivity == EnvironmentSensitivity.SENSITIVE
        if (environmentSensitive && !hasEnvironmentApproval) {
            issues += ValidationIssue(
                "error",
                "ENVIRONMENT_APPROVAL_REQUIRED",
                "${environmentReason(requireNotNull(environmentEvidence))} '${action.module}.${action.action}' requires reachable environment approval before planning and target projection.",
                action.sourceLocation
            )
        }

        // Canonical Intent actions already carry typed control requirements/evidence.
        val rawFlowRollbackRequiresApproval = rollbackSensitive &&
            !insideErrorHandler &&
            action.semanticCapability.isNullOrBlank()
        val hardApprovalRequired = contract.safety.requiresApproval || rawFlowRollbackRequiresApproval
        if (hardApprovalRequired && !hasUnconditionalApproval) {
            val code = if (rawFlowRollbackRequiresApproval) "ROLLBACK_APPROVAL_REQUIRED" else "APPROVAL_REQUIRED"
            val reason = if (rawFlowRollbackRequiresApproval) "Rollback-sensitive action" else "Action contract"
            issues += ValidationIssue(
                "error",
                code,
                "$reason '${action.module}.${action.action}' requires unconditional approval before planning and target projection.",
                action.sourceLocation
            )
        }

        if (contract.safety.destructive && !hasUnconditionalApproval && !nonSensitiveEnvironmentGuard) {
            if (action.safety == null) {
                issues += ValidationIssue(
                    "error",
                    "SAFETY_REQUIRED",
                    "Destructive action '${action.module}.${action.action}' requires an explicit safety rule or reachable approval.",
                    action.sourceLocation
                )
            }
            issues += ValidationIssue(
                "error",
                "APPROVAL_REQUIRED",
                "Destructive action '${action.module}.${action.action}' requires unconditional approval or a finite policy-proven non-sensitive environment guard.",
                action.sourceLocation
            )
        }
    }

    private fun isSensitiveEnvironmentApprovalGuard(statement: IfNode): Boolean {
        if (statement.otherwise.isNotEmpty()) return false
        if (statement.then.none { it is ApproveNode }) return false
        return conditionSelectsSensitiveEnvironment(statement.condition)
    }

    private fun conditionSelectsSensitiveEnvironment(expression: ExpressionNode): Boolean = when (expression) {
        is BinaryExpressionNode -> sensitiveEnvironmentEquality(expression)
        is LogicalExpressionNode ->
            expression.operator == "or" &&
                expression.operands.isNotEmpty() &&
                expression.operands.all(::conditionSelectsSensitiveEnvironment)
        else -> false
    }

    private fun sensitiveEnvironmentEquality(expression: BinaryExpressionNode): Boolean {
        if (expression.operator !in setOf("==", "equals")) return false
        val direct = referenceAndLiteral(expression.left, expression.right)
        val reversed = referenceAndLiteral(expression.right, expression.left)
        val (parameter, value) = direct ?: reversed ?: return false
        if (!environmentPolicy.recognizesParameter(parameter)) return false
        return environmentPolicy.classify(mapOf(parameter to value)).sensitivity == EnvironmentSensitivity.SENSITIVE
    }

    private fun conditionProvesFiniteNonSensitiveEnvironment(
        expression: ExpressionNode,
        environmentDomains: Map<String, Set<String>>
    ): Boolean = environmentDomains.any { (parameter, domain) ->
        val evaluations = domain.map { value -> value to evaluateForParameter(expression, parameter, value) }
        if (evaluations.any { (_, result) -> result == null }) return@any false
        val executableValues = evaluations.filter { (_, result) -> result == true }.map(Pair<String, Boolean?>::first)
        executableValues.isNotEmpty() && executableValues.all { value ->
            environmentPolicy.classify(mapOf(parameter to value)).sensitivity == EnvironmentSensitivity.NON_SENSITIVE
        }
    }

    private fun evaluateForParameter(
        expression: ExpressionNode,
        parameter: String,
        value: String
    ): Boolean? = when (expression) {
        is BinaryExpressionNode -> evaluateBinaryForParameter(expression, parameter, value)
        is LogicalExpressionNode -> {
            val results = expression.operands.map { operand -> evaluateForParameter(operand, parameter, value) }
            if (results.any { it == null }) null else when (expression.operator) {
                "and" -> results.all { it == true }
                "or" -> results.any { it == true }
                else -> null
            }
        }
        else -> null
    }

    private fun evaluateBinaryForParameter(
        expression: BinaryExpressionNode,
        parameter: String,
        value: String
    ): Boolean? {
        val direct = referenceAndLiteral(expression.left, expression.right)
        val reversed = referenceAndLiteral(expression.right, expression.left)
        val pair = direct ?: reversed ?: return null
        if (pair.first != parameter) return null
        return when (expression.operator) {
            "==", "equals" -> value == pair.second
            "!=" -> value != pair.second
            else -> null
        }
    }

    private fun referenceAndLiteral(
        possibleReference: ExpressionNode,
        possibleLiteral: ExpressionNode
    ): Pair<String, String>? {
        val reference = possibleReference as? ReferenceNode ?: return null
        if (reference.path.size != 1) return null
        val literal = when (possibleLiteral) {
            is StringLiteralNode -> possibleLiteral.value
            is IdentifierLiteralNode -> possibleLiteral.value
            else -> return null
        }
        return reference.path.single() to literal
    }

    private fun environmentEvidence(
        action: ActionNode,
        contract: ModuleActionContract
    ): EnvironmentClassificationEvidence? {
        if (!contract.effects.mutatesExternalState()) return null
        val candidates = action.params
            .filterKeys(environmentPolicy::recognizesParameter)
            .map { (name, expression) -> expression.toEnvironmentEvidence(name) }
        if (candidates.isEmpty()) return null
        return environmentPolicy.classify(candidates).takeIf(EnvironmentClassificationEvidence::evidenceAvailable)
    }

    private fun ExpressionNode.toEnvironmentEvidence(name: String): EnvironmentParameterEvidence = when (this) {
        is StringLiteralNode -> EnvironmentParameterEvidence(name, EnvironmentValueKind.LITERAL, literalValue = value)
        is IdentifierLiteralNode -> EnvironmentParameterEvidence(name, EnvironmentValueKind.LITERAL, literalValue = value)
        is ReferenceNode -> EnvironmentParameterEvidence(name, EnvironmentValueKind.REFERENCE, referencePath = path)
        else -> EnvironmentParameterEvidence(name, EnvironmentValueKind.DYNAMIC_EXPRESSION)
    }

    private fun unknownEnvironmentReason(
        action: ActionNode,
        evidence: EnvironmentClassificationEvidence
    ): String {
        val observed = when (evidence.valueKind) {
            EnvironmentValueKind.LITERAL -> "literal '${evidence.parameterValue}'"
            EnvironmentValueKind.REFERENCE -> "runtime reference '${evidence.referencePath.joinToString(".")}'"
            EnvironmentValueKind.DYNAMIC_EXPRESSION -> "dynamic expression"
            null -> "missing environment evidence"
        }
        return "Environment parameter '${evidence.parameterName}' for '${action.module}.${action.action}' is $observed and cannot be classified safely by " +
            "${evidence.policyPackageId}@${evidence.policyPackageVersion}. ${evidence.reason} Add reachable approval, prove a finite non-sensitive environment guard, or resolve environment sensitivity before lowering."
    }

    private fun environmentReason(evidence: EnvironmentClassificationEvidence): String =
        "Sensitive environment evidence ${evidence.parameterName}=${evidence.parameterValue} " +
            "matched policy ${evidence.policyPackageId}@${evidence.policyPackageVersion}/${evidence.ruleId}."

    private fun isRollbackSensitive(action: ActionNode): Boolean {
        if (action.module == "standard" && action.action == "rollback") return true
        if (action.module == "standard" && action.action == "execute") {
            val operation = action.params["operation"] ?: return false
            return when (operation) {
                is StringLiteralNode -> operation.value.equals("rollback", ignoreCase = true)
                is IdentifierLiteralNode -> operation.value.equals("rollback", ignoreCase = true)
                else -> false
            }
        }
        return false
    }

    private fun Effects.mutatesExternalState(): Boolean =
        writes.isNotEmpty() || creates.isNotEmpty() || updates.isNotEmpty() || deletes.isNotEmpty()

    companion object {
        internal const val MAX_STATEMENT_NESTING_DEPTH = 128
    }
}
