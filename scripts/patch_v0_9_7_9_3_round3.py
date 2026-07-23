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
 * Approval evidence is reachability-scoped. A directly preceding ApproveNode or
 * an unconditional action safety rule may authorize downstream sensitive work.
 * A conditional approval whose predicate is proven to select a sensitive
 * environment may authorize only downstream environment-sensitive work.
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
        validateStatements(document.flow.steps, issues, insideErrorHandler = false, ApprovalState())
        document.flow.errorHandler?.steps?.let { handlerSteps ->
            validateStatements(handlerSteps, issues, insideErrorHandler = true, ApprovalState())
        }
        return issues
    }

    private fun validateStatements(
        statements: List<StatementNode>,
        issues: MutableList<ValidationIssue>,
        insideErrorHandler: Boolean,
        inheritedApproval: ApprovalState
    ): ApprovalState {
        var approval = inheritedApproval
        statements.forEach { statement ->
            when (statement) {
                is ApproveNode -> approval = approval.copy(unconditional = true)
                is ActionNode -> {
                    validateAction(statement, issues, insideErrorHandler, approval)
                    statement.handler?.rules?.forEach { rule ->
                        if (rule is WhenNode) {
                            validateStatements(
                                rule.steps,
                                issues,
                                insideErrorHandler || rule.isError,
                                approval
                            )
                        }
                    }
                }
                is IfNode -> {
                    validateStatements(statement.then, issues, insideErrorHandler, approval)
                    validateStatements(statement.otherwise, issues, insideErrorHandler, approval)
                    if (isSensitiveEnvironmentApprovalGuard(statement)) {
                        approval = approval.copy(sensitiveEnvironmentGuard = true)
                    }
                }
                is ForNode -> validateStatements(statement.body, issues, insideErrorHandler, approval)
                is ParallelNode -> statement.branches.forEach { branch ->
                    validateStatements(branch.steps, issues, insideErrorHandler, approval)
                }
                is MatchNode -> {
                    statement.cases.forEach { case ->
                        validateStatements(case.steps, issues, insideErrorHandler, approval)
                    }
                    statement.errorCase?.let { errorSteps ->
                        validateStatements(errorSteps, issues, insideErrorHandler = true, approval)
                    }
                    validateStatements(statement.defaultSteps, issues, insideErrorHandler, approval)
                }
                is RetryNode -> validateStatements(statement.steps, issues, insideErrorHandler, approval)
                is TryNode -> {
                    validateStatements(statement.steps, issues, insideErrorHandler, approval)
                    validateStatements(statement.errorHandler.steps, issues, insideErrorHandler = true, approval)
                }
                is ErrorHandlerNode -> validateStatements(
                    statement.steps,
                    issues,
                    insideErrorHandler = true,
                    inheritedApproval = approval
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
        approval: ApprovalState
    ) {
        val contract = registry.findAction(action.module, action.action) ?: return
        val actionApproval = action.safety?.let { safety ->
            safety.rule.trim() == "requiresApproval" && safety.condition == null
        } == true
        val hasUnconditionalApproval = approval.unconditional || actionApproval
        val hasEnvironmentApproval = hasUnconditionalApproval || approval.sensitiveEnvironmentGuard
        val rollbackSensitive = isRollbackSensitive(action)
        val environmentEvidence = environmentEvidence(action, contract)

        if (environmentEvidence?.sensitivity == EnvironmentSensitivity.UNKNOWN && !hasEnvironmentApproval) {
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
        // The raw Flow rollback heuristic remains only for directly authored actions.
        val rawFlowRollbackRequiresApproval = rollbackSensitive &&
            !insideErrorHandler &&
            action.semanticCapability.isNullOrBlank()
        val genericApprovalSensitive = contract.safety.requiresApproval ||
            contract.safety.destructive ||
            rawFlowRollbackRequiresApproval

        if (genericApprovalSensitive && !hasUnconditionalApproval) {
            if (contract.safety.destructive && action.safety == null) {
                issues += ValidationIssue(
                    "error",
                    "SAFETY_REQUIRED",
                    "Destructive action '${action.module}.${action.action}' requires an explicit safety rule or reachable approval.",
                    action.sourceLocation
                )
            }
            val code = if (rawFlowRollbackRequiresApproval) "ROLLBACK_APPROVAL_REQUIRED" else "APPROVAL_REQUIRED"
            val reason = when {
                rawFlowRollbackRequiresApproval -> "Rollback-sensitive action"
                contract.safety.destructive -> "Destructive action"
                else -> "Action contract"
            }
            issues += ValidationIssue(
                "error",
                code,
                "$reason '${action.module}.${action.action}' requires unconditional approval before planning and target projection.",
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
            "${evidence.policyPackageId}@${evidence.policyPackageVersion}. ${evidence.reason} Add reachable unconditional or sensitive-environment conditional approval, or resolve environment sensitivity before lowering."
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
    "src/test/kotlin/SafetyBoundaryHardeningTests.kt",
    '              namespace: "customer-a"',
    '              environment: "customer-a"'
)

replace_exact(
    "src/test/kotlin/EnvironmentSafetyProductionIntegrationTests.kt",
    "    @Test\n    fun conditionalApprovalTextDoesNotCountAsUnconditionalApproval() {",
    r'''    @Test
    fun sensitiveEnvironmentConditionalApprovalGuardsDynamicEnvironment() {
        val report = FlowValidator().validate(parse(
            """
            version "1.0"
            use module "kubernetes" version "1.0"
            flow "conditional environment approval" {
              input { environment: text required }
              systems { system "k8s" { type: kubernetes } }
              steps {
                if environment == "prod" {
                  approve manual { message: "Production approval" }
                }
                kubernetes.deploy k8s {
                  app: "demo"
                  namespace: environment
                  image: "demo:1"
                }
              }
            }
            """
        ))
        assertTrue(report.issues.none { it.code.startsWith("ENVIRONMENT_") }, report.issues.toString())
    }

    @Test
    fun unrelatedConditionalApprovalDoesNotAuthorizeDynamicEnvironment() {
        val report = FlowValidator().validate(parse(
            """
            version "1.0"
            use module "kubernetes" version "1.0"
            flow "unrelated approval" {
              input {
                environment: text required
                allow: boolean required
              }
              systems { system "k8s" { type: kubernetes } }
              steps {
                if allow == true {
                  approve manual { message: "Unrelated approval" }
                }
                kubernetes.deploy k8s {
                  app: "demo"
                  namespace: environment
                  image: "demo:1"
                }
              }
            }
            """
        ))
        assertTrue(report.issues.any { it.code == "ENVIRONMENT_CLASSIFICATION_UNKNOWN" }, report.issues.toString())
    }

    @Test
    fun conditionalApprovalTextDoesNotCountAsUnconditionalApproval() {'''
)

replace_exact(
    "docs/V0_9_7_9_3_ENVIRONMENT_SAFETY_PRODUCTION_INTEGRATION.md",
    "Unknown evidence cannot proceed without a reachable unconditional approval. Approval reachability is sequential and branch-scoped; an approval in an optional branch does not authorize work outside that branch.",
    "Unknown evidence cannot proceed without reachable approval. Unconditional approval is sequential and branch-scoped. A conditional approval may guard later environment-sensitive work only when its predicate is structurally proven to select a policy-declared sensitive environment, such as `environment == \"prod\"`. An unrelated optional approval does not authorize the environment boundary."
)
replace_exact(
    "docs/V0_9_7_9_3_ENVIRONMENT_SAFETY_PRODUCTION_INTEGRATION.md",
    "Only unconditional `safety: requiresApproval` satisfies the AST safety boundary. Conditional safety text is not treated as confirmed approval evidence.",
    "An action-local `safety: requiresApproval` must be unconditional. A standalone `ApproveNode` is reachability-scoped. A structurally verified sensitive-environment conditional approval may authorize only the later environment boundary; it does not become generic approval evidence for destructive actions. Conditional safety text such as `safety: onlyIf ...` is not approval evidence."
)

replace_exact(
    ".flow-agent/work-packages/v0.9.7.9.3-environment-safety-production-integration.yaml",
    '  - "Unknown literal, reference and dynamic expression evidence blocks planning."',
    '  - "Unknown literal, reference and dynamic expression evidence blocks planning unless reachable approval conservatively guards the environment boundary."'
)
replace_exact(
    ".flow-agent/reports/v0.9.7.9.3-environment-safety-production-integration.md",
    "Dynamic evidence remains unknown and requires reachable unconditional approval. Approval reachability is sequential and does not leak out of optional control-flow branches.",
    "Dynamic evidence remains unknown and requires reachable approval. Unconditional approval is sequential and does not leak out of optional control-flow branches. A conditional approval is accepted only as an environment guard when its predicate is structurally proven to select a policy-declared sensitive value; it never authorizes unrelated destructive work. Canonical Intent rollback actions remain governed by typed intent control evidence, while directly authored raw Flow rollback actions retain the local approval requirement."
)

print("v0.9.7.9.3 conditional environment guard refinement applied")
