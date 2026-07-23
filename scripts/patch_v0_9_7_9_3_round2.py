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


write("src/main/kotlin/org/flowlang/safety/EnvironmentSafetyPolicy.kt", r'''
package org.flowlang.safety

import org.flowlang.notes.NotesPackageContract
import org.flowlang.notes.NotesPackageKind
import org.flowlang.notes.StandardNotesPackageContracts

/**
 * Environment safety classification is compile-time evidence, not name guessing.
 * References and other dynamic expressions remain dynamic and therefore UNKNOWN.
 */
enum class EnvironmentSensitivity {
    SENSITIVE,
    NON_SENSITIVE,
    UNKNOWN
}

enum class EnvironmentValueKind {
    LITERAL,
    REFERENCE,
    DYNAMIC_EXPRESSION
}

enum class UnmatchedEnvironmentValueDisposition {
    UNKNOWN,
    NOT_ENVIRONMENT_EVIDENCE
}

data class EnvironmentParameterEvidence(
    val parameterName: String,
    val valueKind: EnvironmentValueKind,
    val literalValue: String? = null,
    val referencePath: List<String> = emptyList()
) {
    init {
        require(parameterName.isNotBlank()) { "Environment parameter name is required." }
        when (valueKind) {
            EnvironmentValueKind.LITERAL -> require(literalValue != null) {
                "Literal environment evidence requires a literal value."
            }
            EnvironmentValueKind.REFERENCE -> require(referencePath.isNotEmpty()) {
                "Reference environment evidence requires a reference path."
            }
            EnvironmentValueKind.DYNAMIC_EXPRESSION -> Unit
        }
    }
}

data class EnvironmentPolicyRule(
    val ruleId: String,
    val parameterNames: Set<String>,
    val values: Set<String>,
    val sensitivity: EnvironmentSensitivity,
    val approvalEnvironment: String? = null,
    val reason: String
)

data class EnvironmentSafetyPolicyNotes(
    val packageId: String,
    val packageVersion: String,
    val rules: List<EnvironmentPolicyRule>,
    val unknownReason: String,
    val unmatchedValueDispositions: Map<String, UnmatchedEnvironmentValueDisposition> = emptyMap()
)

data class EnvironmentClassificationEvidence(
    val sensitivity: EnvironmentSensitivity,
    val policyPackageId: String,
    val policyPackageVersion: String,
    val ruleId: String?,
    val parameterName: String?,
    val parameterValue: String?,
    val approvalEnvironment: String?,
    val reason: String,
    val valueKind: EnvironmentValueKind? = null,
    val referencePath: List<String> = emptyList()
) {
    val evidenceAvailable: Boolean = parameterName != null && valueKind != null
    val classificationResolved: Boolean = sensitivity != EnvironmentSensitivity.UNKNOWN && ruleId != null
}

class EnvironmentSafetyPolicy(private val notes: EnvironmentSafetyPolicyNotes) {
    val recognizedParameterNames: Set<String> = notes.rules
        .flatMap(EnvironmentPolicyRule::parameterNames)
        .map { it.normalized() }
        .toSet()

    init {
        require(notes.packageId.isNotBlank()) { "Environment safety policy package id is required." }
        require(notes.packageVersion.isNotBlank()) { "Environment safety policy package version is required." }
        require(notes.rules.isNotEmpty()) { "Environment safety policy must declare at least one rule." }
        require(notes.unknownReason.isNotBlank()) { "Environment safety policy must explain unknown classifications." }
        require(notes.rules.map { it.ruleId }.distinct().size == notes.rules.size) {
            "Environment safety policy rule ids must be unique."
        }
        notes.rules.forEach { rule ->
            require(rule.ruleId.isNotBlank()) { "Environment policy rule id is required." }
            require(rule.parameterNames.isNotEmpty()) { "Environment policy rule '${rule.ruleId}' must declare parameter names." }
            require(rule.values.isNotEmpty()) { "Environment policy rule '${rule.ruleId}' must declare values." }
            require(rule.reason.isNotBlank()) { "Environment policy rule '${rule.ruleId}' must explain its classification." }
            require(rule.approvalEnvironment == null || rule.sensitivity == EnvironmentSensitivity.SENSITIVE) {
                "Only sensitive environment rules may declare an approval environment."
            }
        }
        val unknownParameters = notes.unmatchedValueDispositions.keys.map { it.normalized() }.toSet() - recognizedParameterNames
        require(unknownParameters.isEmpty()) {
            "Unmatched-value dispositions reference undeclared environment parameters: ${unknownParameters.sorted().joinToString()}."
        }
    }

    fun recognizesParameter(name: String): Boolean = name.normalized() in recognizedParameterNames

    fun classify(parameters: Map<String, String>): EnvironmentClassificationEvidence = classify(
        parameters.map { (name, value) ->
            EnvironmentParameterEvidence(
                parameterName = name,
                valueKind = EnvironmentValueKind.LITERAL,
                literalValue = value
            )
        }
    )

    fun classify(parameters: List<EnvironmentParameterEvidence>): EnvironmentClassificationEvidence {
        val relevant = parameters
            .filter { recognizesParameter(it.parameterName) }
            .sortedWith(compareBy({ it.parameterName.normalized() }, { it.valueKind.name }))
        if (relevant.isEmpty()) return unknown(null, notes.unknownReason)

        val classified = relevant.mapNotNull(::classifyCandidate)
        if (classified.isEmpty()) return unknown(null, notes.unknownReason)
        return classified.sortedWith(
            compareBy<EnvironmentClassificationEvidence> { priority(it.sensitivity) }
                .thenBy { it.parameterName.orEmpty() }
                .thenBy { it.parameterValue.orEmpty() }
        ).first()
    }

    private fun classifyCandidate(candidate: EnvironmentParameterEvidence): EnvironmentClassificationEvidence? {
        val name = candidate.parameterName.normalized()
        if (candidate.valueKind != EnvironmentValueKind.LITERAL) {
            val detail = when (candidate.valueKind) {
                EnvironmentValueKind.REFERENCE ->
                    "Environment parameter '$name' is a runtime reference '${candidate.referencePath.joinToString(".")}'."
                EnvironmentValueKind.DYNAMIC_EXPRESSION ->
                    "Environment parameter '$name' is a dynamic expression."
                EnvironmentValueKind.LITERAL -> error("Literal handled below")
            }
            return unknown(candidate, "$detail ${notes.unknownReason}")
        }

        val value = requireNotNull(candidate.literalValue).normalized()
        val matches = notes.rules.filter { rule ->
            name in rule.parameterNames.map { it.normalized() } &&
                value in rule.values.map { it.normalized() }
        }
        if (matches.isEmpty()) {
            val unmatchedDisposition = notes.unmatchedValueDispositions.entries
                .firstOrNull { it.key.normalized() == name }
                ?.value
                ?: UnmatchedEnvironmentValueDisposition.UNKNOWN
            if (unmatchedDisposition == UnmatchedEnvironmentValueDisposition.NOT_ENVIRONMENT_EVIDENCE) return null
            return unknown(
                candidate,
                "Environment parameter '$name' has unclassified literal '$value'. ${notes.unknownReason}"
            )
        }

        val selected = matches.sortedWith(
            compareBy<EnvironmentPolicyRule> { priority(it.sensitivity) }
                .thenBy(EnvironmentPolicyRule::ruleId)
        ).first()
        return EnvironmentClassificationEvidence(
            sensitivity = selected.sensitivity,
            policyPackageId = notes.packageId,
            policyPackageVersion = notes.packageVersion,
            ruleId = selected.ruleId,
            parameterName = name,
            parameterValue = value,
            approvalEnvironment = selected.approvalEnvironment,
            reason = selected.reason,
            valueKind = EnvironmentValueKind.LITERAL
        )
    }

    private fun unknown(
        candidate: EnvironmentParameterEvidence?,
        reason: String
    ): EnvironmentClassificationEvidence = EnvironmentClassificationEvidence(
        sensitivity = EnvironmentSensitivity.UNKNOWN,
        policyPackageId = notes.packageId,
        policyPackageVersion = notes.packageVersion,
        ruleId = null,
        parameterName = candidate?.parameterName?.normalized(),
        parameterValue = candidate?.literalValue?.normalized(),
        approvalEnvironment = null,
        reason = reason,
        valueKind = candidate?.valueKind,
        referencePath = candidate?.referencePath.orEmpty()
    )

    private fun priority(sensitivity: EnvironmentSensitivity): Int = when (sensitivity) {
        EnvironmentSensitivity.SENSITIVE -> 0
        EnvironmentSensitivity.UNKNOWN -> 1
        EnvironmentSensitivity.NON_SENSITIVE -> 2
    }

    private fun String.normalized(): String = trim().lowercase()
}

object StandardEnvironmentSafetyPolicyNotes {
    fun baseline(): EnvironmentSafetyPolicyNotes {
        val contract = StandardNotesPackageContracts.baseline().singleSafetyContract()
        return EnvironmentSafetyPolicyNotes(
            packageId = contract.packageId,
            packageVersion = contract.packageVersion,
            rules = listOf(
                EnvironmentPolicyRule(
                    ruleId = "environment.sensitive.production-like",
                    parameterNames = setOf("environment", "env", "namespace", "cluster", "stage"),
                    values = setOf("prod", "production", "live"),
                    sensitivity = EnvironmentSensitivity.SENSITIVE,
                    approvalEnvironment = "production",
                    reason = "Explicit environment evidence matches the production-sensitive rule declared by safety notes."
                ),
                EnvironmentPolicyRule(
                    ruleId = "environment.non-sensitive.engineering",
                    parameterNames = setOf("environment", "env", "namespace", "cluster", "stage"),
                    values = setOf("dev", "development", "test", "testing", "qa", "sandbox"),
                    sensitivity = EnvironmentSensitivity.NON_SENSITIVE,
                    reason = "Explicit environment evidence matches a non-sensitive engineering rule declared by safety notes."
                )
            ),
            unknownReason = "No declared environment safety rule resolves the available evidence; sensitivity remains unknown.",
            unmatchedValueDispositions = mapOf(
                "environment" to UnmatchedEnvironmentValueDisposition.UNKNOWN,
                "env" to UnmatchedEnvironmentValueDisposition.UNKNOWN,
                "stage" to UnmatchedEnvironmentValueDisposition.UNKNOWN,
                "namespace" to UnmatchedEnvironmentValueDisposition.NOT_ENVIRONMENT_EVIDENCE,
                "cluster" to UnmatchedEnvironmentValueDisposition.NOT_ENVIRONMENT_EVIDENCE
            )
        )
    }

    fun policy(): EnvironmentSafetyPolicy = EnvironmentSafetyPolicy(baseline())

    private fun List<NotesPackageContract>.singleSafetyContract(): NotesPackageContract =
        single { contract ->
            contract.kind == NotesPackageKind.SAFETY && contract.packageId == "flow.safety.core"
        }
}
''')

write("src/main/kotlin/org/flowlang/validator/SafetyBoundaryValidator.kt", r'''
package org.flowlang.validator

import org.flowlang.ast.ActionNode
import org.flowlang.ast.AggregateNode
import org.flowlang.ast.ApproveNode
import org.flowlang.ast.ErrorHandlerNode
import org.flowlang.ast.ExpressionNode
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
 * Approvals inside optional control-flow branches never leak into the outer path.
 */
class SafetyBoundaryValidator(
    private val registry: ModuleRegistry = ModuleRegistry(),
    private val environmentPolicy: EnvironmentSafetyPolicy = StandardEnvironmentSafetyPolicyNotes.policy()
) {
    fun validate(document: FlowDocument): List<ValidationIssue> {
        val issues = mutableListOf<ValidationIssue>()
        validateStatements(document.flow.steps, issues, insideErrorHandler = false, approvalAvailable = false)
        document.flow.errorHandler?.steps?.let { handlerSteps ->
            validateStatements(handlerSteps, issues, insideErrorHandler = true, approvalAvailable = false)
        }
        return issues
    }

    private fun validateStatements(
        statements: List<StatementNode>,
        issues: MutableList<ValidationIssue>,
        insideErrorHandler: Boolean,
        approvalAvailable: Boolean
    ): Boolean {
        var reachableApproval = approvalAvailable
        statements.forEach { statement ->
            when (statement) {
                is ApproveNode -> reachableApproval = true
                is ActionNode -> {
                    validateAction(statement, issues, insideErrorHandler, reachableApproval)
                    statement.handler?.rules?.forEach { rule ->
                        if (rule is WhenNode) {
                            validateStatements(
                                rule.steps,
                                issues,
                                insideErrorHandler || rule.isError,
                                reachableApproval
                            )
                        }
                    }
                }
                is IfNode -> {
                    validateStatements(statement.then, issues, insideErrorHandler, reachableApproval)
                    validateStatements(statement.otherwise, issues, insideErrorHandler, reachableApproval)
                }
                is ForNode -> validateStatements(statement.body, issues, insideErrorHandler, reachableApproval)
                is ParallelNode -> statement.branches.forEach { branch ->
                    validateStatements(branch.steps, issues, insideErrorHandler, reachableApproval)
                }
                is MatchNode -> {
                    statement.cases.forEach { case ->
                        validateStatements(case.steps, issues, insideErrorHandler, reachableApproval)
                    }
                    statement.errorCase?.let { errorSteps ->
                        validateStatements(errorSteps, issues, insideErrorHandler = true, reachableApproval)
                    }
                    validateStatements(statement.defaultSteps, issues, insideErrorHandler, reachableApproval)
                }
                is RetryNode -> validateStatements(statement.steps, issues, insideErrorHandler, reachableApproval)
                is TryNode -> {
                    validateStatements(statement.steps, issues, insideErrorHandler, reachableApproval)
                    validateStatements(statement.errorHandler.steps, issues, insideErrorHandler = true, reachableApproval)
                }
                is ErrorHandlerNode -> validateStatements(
                    statement.steps,
                    issues,
                    insideErrorHandler = true,
                    approvalAvailable = reachableApproval
                )
                is TransformNode, is AggregateNode -> Unit
                else -> Unit
            }
        }
        return reachableApproval
    }

    private fun validateAction(
        action: ActionNode,
        issues: MutableList<ValidationIssue>,
        insideErrorHandler: Boolean,
        approvalAvailable: Boolean
    ) {
        val contract = registry.findAction(action.module, action.action) ?: return
        val actionApproval = action.safety?.let { safety ->
            safety.rule.trim() == "requiresApproval" && safety.condition == null
        } == true
        val hasUnconditionalApproval = approvalAvailable || actionApproval
        val rollbackSensitive = isRollbackSensitive(action)
        val environmentEvidence = environmentEvidence(action, contract)

        if (environmentEvidence?.sensitivity == EnvironmentSensitivity.UNKNOWN && !hasUnconditionalApproval) {
            issues += ValidationIssue(
                level = "error",
                code = "ENVIRONMENT_CLASSIFICATION_UNKNOWN",
                message = unknownEnvironmentReason(action, environmentEvidence),
                location = action.sourceLocation
            )
        }

        val environmentSensitive = environmentEvidence?.sensitivity == EnvironmentSensitivity.SENSITIVE
        val rollbackRequiresApproval = rollbackSensitive && !insideErrorHandler
        val approvalSensitive = contract.safety.requiresApproval ||
            contract.safety.destructive ||
            environmentSensitive ||
            rollbackRequiresApproval

        if (approvalSensitive && !hasUnconditionalApproval) {
            if (contract.safety.destructive && action.safety == null) {
                issues += ValidationIssue(
                    "error",
                    "SAFETY_REQUIRED",
                    "Destructive action '${action.module}.${action.action}' requires an explicit safety rule or reachable approval.",
                    action.sourceLocation
                )
            }
            val code = when {
                rollbackRequiresApproval -> "ROLLBACK_APPROVAL_REQUIRED"
                environmentSensitive -> "ENVIRONMENT_APPROVAL_REQUIRED"
                else -> "APPROVAL_REQUIRED"
            }
            val reason = when {
                rollbackRequiresApproval -> "Rollback-sensitive action"
                environmentSensitive -> environmentReason(requireNotNull(environmentEvidence))
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
            "${evidence.policyPackageId}@${evidence.policyPackageVersion}. ${evidence.reason} Add reachable unconditional approval or resolve environment sensitivity before lowering."
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
    "        val report = FlowValidator().validate(parse(deploy(namespace = \"\\\"customer-a\\\"\")))",
    "        val report = FlowValidator().validate(parse(deploy(namespace = null, environment = \"\\\"customer-a\\\"\")))"
)
replace_exact(
    "src/test/kotlin/EnvironmentSafetyProductionIntegrationTests.kt",
    "        input: Boolean = false\n    ): String {",
    "        input: Boolean = false,\n        environment: String? = null\n    ): String {"
)
replace_exact(
    "src/test/kotlin/EnvironmentSafetyProductionIntegrationTests.kt",
    "        val namespaceLine = namespace?.let { \"namespace: $it\" }.orEmpty()\n        val approvalLine = if (approval) \"safety: requiresApproval\" else \"\"",
    "        val namespaceLine = namespace?.let { \"namespace: $it\" }.orEmpty()\n        val environmentLine = environment?.let { \"environment: $it\" }.orEmpty()\n        val approvalLine = if (approval) \"safety: requiresApproval\" else \"\""
)
replace_exact(
    "src/test/kotlin/EnvironmentSafetyProductionIntegrationTests.kt",
    "                  $namespaceLine\n                  image: \"demo:1\"",
    "                  $namespaceLine\n                  $environmentLine\n                  image: \"demo:1\""
)

replace_exact(
    "src/main/kotlin/org/flowlang/conformance/StandardArchitectureNormalizationChecks.kt",
    "${'$'}inputBlock",
    "$inputBlock"
)
replace_exact(
    "src/main/kotlin/org/flowlang/conformance/StandardArchitectureNormalizationChecks.kt",
    "${'$'}namespace",
    "$namespace"
)
replace_exact(
    "src/main/kotlin/org/flowlang/conformance/StandardArchitectureNormalizationChecks.kt",
    "${'$'}approvalLine",
    "$approvalLine"
)
replace_exact(
    "src/main/kotlin/org/flowlang/conformance/StandardArchitectureNormalizationChecks.kt",
    "${'$'}{approved.issues}",
    "${approved.issues}"
)
replace_exact(
    "src/main/kotlin/org/flowlang/conformance/StandardArchitectureNormalizationChecks.kt",
    "${'$'}{engineering.issues}",
    "${engineering.issues}"
)

replace_exact(
    "docs/V0_9_7_9_3_ENVIRONMENT_SAFETY_PRODUCTION_INTEGRATION.md",
    "Only parameter names declared by the environment safety policy participate in classification. Text in unrelated fields such as application names, descriptions or image tags is never environment evidence.",
    "Only parameter names declared by the environment safety policy participate in classification. Explicit environment fields fail closed on unmatched literals. Contextual resource identifiers such as namespaces and clusters become environment evidence only when they match a declared policy value or remain dynamic. Text in unrelated fields such as application names, descriptions or image tags is never environment evidence."
)
replace_exact(
    "docs/V0_9_7_9_3_ENVIRONMENT_SAFETY_PRODUCTION_INTEGRATION.md",
    "Classification is fail closed:\n\n1. Any sensitive match yields `SENSITIVE`.\n2. Otherwise any unresolved, dynamic or unclassified candidate yields `UNKNOWN`.\n3. `NON_SENSITIVE` is returned only when all relevant evidence is explicitly classified as non-sensitive.",
    "Classification is fail closed:\n\n1. Any sensitive match yields `SENSITIVE`.\n2. Otherwise any unresolved or dynamic candidate yields `UNKNOWN`. Explicit environment fields also yield `UNKNOWN` for unclassified literals.\n3. Contextual namespace or cluster literals that match no policy rule are not silently labelled safe; they remain resource identifiers rather than environment claims.\n4. `NON_SENSITIVE` is returned only from an explicit policy match.\n\nUnknown evidence cannot proceed without a reachable unconditional approval. Approval reachability is sequential and branch-scoped; an approval in an optional branch does not authorize work outside that branch."
)

replace_exact(
    ".flow-agent/reports/v0.9.7.9.3-environment-safety-production-integration.md",
    "Environment parameter evidence is typed as literal, reference or dynamic expression. The standard policy evaluates only names declared by its rules and applies fail-closed precedence: sensitive, then unknown, then non-sensitive.",
    "Environment parameter evidence is typed as literal, reference or dynamic expression. The standard policy evaluates only names declared by its rules. Explicit environment fields fail closed on unmatched literals, while contextual namespace or cluster identifiers require an actual policy match before they are treated as environment claims. Dynamic evidence remains unknown and requires reachable unconditional approval. Approval reachability is sequential and does not leak out of optional control-flow branches."
)

print("v0.9.7.9.3 round-two refinement applied")
