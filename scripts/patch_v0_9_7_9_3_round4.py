from pathlib import Path


def write(path: str, content: str) -> None:
    target = Path(path)
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(content.rstrip() + "\n")


def replace_exact(path: str, old: str, new: str, expected: int = 1) -> None:
    target = Path(path)
    text = target.read_text()
    count = text.count(old)
    if count != expected:
        raise RuntimeError(f"{path}: expected {expected} occurrences, found {count}: {old[:160]!r}")
    target.write_text(text.replace(old, new))


write("src/main/kotlin/org/flowlang/validator/SafetyBoundaryValidator.kt", r'''
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
import org.flowlang.modules.ModuleRegistry
import org.flowlang.safety.EnvironmentClassificationEvidence
import org.flowlang.safety.EnvironmentParameterEvidence
import org.flowlang.safety.EnvironmentSafetyPolicy
import org.flowlang.safety.EnvironmentSensitivity
import org.flowlang.safety.EnvironmentValueKind
import org.flowlang.safety.StandardEnvironmentSafetyPolicyNotes

/**
 * Production safety gate evaluated before planning and target projection.
 *
 * Approval evidence is reachability-scoped. A conditional approval whose
 * predicate selects a policy-declared sensitive environment authorizes only
 * the downstream environment boundary. A finite `onlyIf` domain may replace
 * approval only when every executable value is policy-classified non-sensitive.
 */
class SafetyBoundaryValidator(
    private val registry: ModuleRegistry = ModuleRegistry(),
    private val environmentPolicy: EnvironmentSafetyPolicy = StandardEnvironmentSafetyPolicyNotes.policy()
) {
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
        environmentDomains: Map<String, Set<String>>
    ): ApprovalState {
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
                                environmentDomains
                            )
                        }
                    }
                }
                is IfNode -> {
                    validateStatements(statement.then, issues, insideErrorHandler, approval, environmentDomains)
                    validateStatements(statement.otherwise, issues, insideErrorHandler, approval, environmentDomains)
                    if (isSensitiveEnvironmentApprovalGuard(statement)) {
                        approval = approval.copy(sensitiveEnvironmentGuard = true)
                    }
                }
                is ForNode -> validateStatements(statement.body, issues, insideErrorHandler, approval, environmentDomains)
                is ParallelNode -> statement.branches.forEach { branch ->
                    validateStatements(branch.steps, issues, insideErrorHandler, approval, environmentDomains)
                }
                is MatchNode -> {
                    statement.cases.forEach { case ->
                        validateStatements(case.steps, issues, insideErrorHandler, approval, environmentDomains)
                    }
                    statement.errorCase?.let { errorSteps ->
                        validateStatements(errorSteps, issues, insideErrorHandler = true, approval, environmentDomains)
                    }
                    validateStatements(statement.defaultSteps, issues, insideErrorHandler, approval, environmentDomains)
                }
                is RetryNode -> validateStatements(statement.steps, issues, insideErrorHandler, approval, environmentDomains)
                is TryNode -> {
                    validateStatements(statement.steps, issues, insideErrorHandler, approval, environmentDomains)
                    validateStatements(statement.errorHandler.steps, issues, insideErrorHandler = true, approval, environmentDomains)
                }
                is ErrorHandlerNode -> validateStatements(
                    statement.steps,
                    issues,
                    insideErrorHandler = true,
                    inheritedApproval = approval,
                    environmentDomains = environmentDomains
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
        val contract = registry.findAction(action.module, action.action) ?: return
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
}
''')

replace_exact(
    "src/test/kotlin/EnvironmentSafetyProductionIntegrationTests.kt",
    "    @Test\n    fun conditionalApprovalTextDoesNotCountAsUnconditionalApproval() {",
    r'''    @Test
    fun finiteOnlyIfGuardProvesDestructiveWorkIsNonSensitive() {
        val report = FlowValidator().validate(parse(
            """
            version "1.0"
            use module "kubernetes" version "1.0"
            flow "finite non-sensitive cleanup" {
              input { environment: option ["dev", "test", "prod"] required }
              systems { system "k8s" { type: kubernetes } }
              steps {
                kubernetes.delete k8s {
                  resource: "namespace"
                  name: "old"
                  safety: onlyIf environment != "prod"
                }
              }
            }
            """
        ))
        assertTrue(report.issues.none { it.code in setOf("SAFETY_REQUIRED", "APPROVAL_REQUIRED") }, report.issues.toString())
    }

    @Test
    fun onlyIfGuardDoesNotPassWhenFiniteDomainStillContainsSensitiveValues() {
        val report = FlowValidator().validate(parse(
            """
            version "1.0"
            use module "kubernetes" version "1.0"
            flow "unsafe finite cleanup" {
              input { environment: option ["dev", "test", "prod"] required }
              systems { system "k8s" { type: kubernetes } }
              steps {
                kubernetes.delete k8s {
                  resource: "namespace"
                  name: "old"
                  safety: onlyIf environment != "dev"
                }
              }
            }
            """
        ))
        assertTrue(report.issues.any { it.code == "APPROVAL_REQUIRED" }, report.issues.toString())
    }

    @Test
    fun conditionalApprovalTextDoesNotCountAsUnconditionalApproval() {'''
)

replace_exact(
    "docs/V0_9_7_9_3_ENVIRONMENT_SAFETY_PRODUCTION_INTEGRATION.md",
    "An action-local `safety: requiresApproval` must be unconditional. A standalone `ApproveNode` is reachability-scoped. A structurally verified sensitive-environment conditional approval may authorize only the later environment boundary; it does not become generic approval evidence for destructive actions. Conditional safety text such as `safety: onlyIf ...` is not approval evidence.",
    "An action-local `safety: requiresApproval` must be unconditional. A standalone `ApproveNode` is reachability-scoped. A structurally verified sensitive-environment conditional approval may authorize only the later environment boundary; it does not become generic approval evidence for destructive actions. An `onlyIf` safety rule may replace destructive approval only when the referenced input has a finite option domain and exhaustive evaluation proves that every executable value is policy-classified `NON_SENSITIVE`. Arbitrary boolean conditions remain insufficient."
)
replace_exact(
    ".flow-agent/work-packages/v0.9.7.9.3-environment-safety-production-integration.yaml",
    '  - "Sensitive evidence requires unconditional approval."',
    '  - "Sensitive evidence requires reachable approval; destructive non-sensitive work may instead use an exhaustively proven finite environment guard."'
)
replace_exact(
    ".flow-agent/reports/v0.9.7.9.3-environment-safety-production-integration.md",
    "Canonical Intent rollback actions remain governed by typed intent control evidence, while directly authored raw Flow rollback actions retain the local approval requirement.",
    "Canonical Intent rollback actions remain governed by typed intent control evidence, while directly authored raw Flow rollback actions retain the local approval requirement. Destructive `onlyIf` safety is accepted only when exhaustive evaluation over a finite option domain proves every executable environment value non-sensitive; arbitrary boolean guards remain blocking."
)

print("v0.9.7.9.3 finite environment guard proof applied")
