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
        raise RuntimeError(f"{path}: expected {expected} occurrences, found {count}: {old[:120]!r}")
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
    val unknownReason: String
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
        .map(String::normalized)
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

        return relevant
            .map(::classifyCandidate)
            .sortedWith(
                compareBy<EnvironmentClassificationEvidence> { priority(it.sensitivity) }
                    .thenBy { it.parameterName.orEmpty() }
                    .thenBy { it.parameterValue.orEmpty() }
            )
            .first()
    }

    private fun classifyCandidate(candidate: EnvironmentParameterEvidence): EnvironmentClassificationEvidence {
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
            name in rule.parameterNames.map(String::normalized) &&
                value in rule.values.map(String::normalized)
        }
        if (matches.isEmpty()) {
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
            unknownReason = "No declared environment safety rule resolves the available evidence; sensitivity remains unknown."
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
 * Only policy-recognized environment parameters are classified. Literal values
 * may resolve to sensitive or non-sensitive evidence. References and dynamic
 * expressions remain UNKNOWN and block lowering instead of being reinterpreted
 * as convenient literal names.
 */
class SafetyBoundaryValidator(
    private val registry: ModuleRegistry = ModuleRegistry(),
    private val environmentPolicy: EnvironmentSafetyPolicy = StandardEnvironmentSafetyPolicyNotes.policy()
) {
    fun validate(document: FlowDocument): List<ValidationIssue> {
        val issues = mutableListOf<ValidationIssue>()
        document.flow.steps.forEach { validateStatement(it, issues, insideErrorHandler = false) }
        document.flow.errorHandler?.steps?.forEach { validateStatement(it, issues, insideErrorHandler = true) }
        return issues
    }

    private fun validateStatement(
        stmt: StatementNode,
        issues: MutableList<ValidationIssue>,
        insideErrorHandler: Boolean
    ) {
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

    private fun validateAction(
        action: ActionNode,
        issues: MutableList<ValidationIssue>,
        insideErrorHandler: Boolean
    ) {
        val contract = registry.findAction(action.module, action.action) ?: return
        val hasUnconditionalApproval = action.safety?.let { safety ->
            safety.rule.trim() == "requiresApproval" && safety.condition == null
        } == true
        val rollbackSensitive = isRollbackSensitive(action)
        val environmentEvidence = environmentEvidence(action, contract)

        if (environmentEvidence?.sensitivity == EnvironmentSensitivity.UNKNOWN) {
            issues += ValidationIssue(
                level = "error",
                code = "ENVIRONMENT_CLASSIFICATION_UNKNOWN",
                message = unknownEnvironmentReason(action, environmentEvidence),
                location = action.sourceLocation
            )
        }

        val environmentSensitive = environmentEvidence?.sensitivity == EnvironmentSensitivity.SENSITIVE
        val approvalSensitive = contract.safety.requiresApproval ||
            contract.safety.destructive ||
            environmentSensitive ||
            (rollbackSensitive && !insideErrorHandler)

        if (approvalSensitive && !hasUnconditionalApproval) {
            val code = when {
                rollbackSensitive -> "ROLLBACK_APPROVAL_REQUIRED"
                environmentSensitive -> "ENVIRONMENT_APPROVAL_REQUIRED"
                else -> "APPROVAL_REQUIRED"
            }
            val reason = when {
                rollbackSensitive -> "Rollback-sensitive action"
                environmentSensitive -> environmentReason(requireNotNull(environmentEvidence))
                contract.safety.destructive -> "Destructive action"
                else -> "Action contract"
            }
            issues += ValidationIssue(
                "error",
                code,
                "$reason '${action.module}.${action.action}' requires unconditional safety: requiresApproval before planning and target projection.",
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
        return environmentPolicy.classify(candidates)
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
            "${evidence.policyPackageId}@${evidence.policyPackageVersion}. ${evidence.reason} Resolve environment sensitivity before lowering."
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
    "src/main/kotlin/org/flowlang/validator/FlowValidator.kt",
    "import org.flowlang.modules.ModuleRegistry\n",
    "import org.flowlang.modules.ModuleRegistry\nimport org.flowlang.safety.EnvironmentSafetyPolicy\nimport org.flowlang.safety.StandardEnvironmentSafetyPolicyNotes\n"
)
replace_exact(
    "src/main/kotlin/org/flowlang/validator/FlowValidator.kt",
    "class FlowValidator(private val registry: ModuleRegistry = ModuleRegistry()) {",
    "class FlowValidator(\n    private val registry: ModuleRegistry = ModuleRegistry(),\n    private val environmentPolicy: EnvironmentSafetyPolicy = StandardEnvironmentSafetyPolicyNotes.policy()\n) {"
)
replace_exact(
    "src/main/kotlin/org/flowlang/validator/FlowValidator.kt",
    "            if (contract.safety.destructive && action.safety == null)\n                issues += err(\"SAFETY_REQUIRED\", \"Destructive action '${action.module}.${action.action}' requires a safety rule\", action.sourceLocation)\n",
    ""
)
replace_exact(
    "src/main/kotlin/org/flowlang/validator/FlowValidator.kt",
    "        return ValidationReport(valid = issues.none { it.level == \"error\" }, issues = issues)\n",
    "        issues += SafetyBoundaryValidator(registry, environmentPolicy).validate(document)\n        return ValidationReport(valid = issues.none { it.level == \"error\" }, issues = issues)\n"
)

replace_exact(
    "src/main/kotlin/org/flowlang/cli/FlowCli.kt",
    "    val report = validator.validate(ast)\n    val plan = FlowPlanner(registry).plan(ast)\n",
    "    val report = validator.validate(ast)\n    require(report.valid) { \"Flow validation failed before planning: \" + report.issues.joinToString { it.code + \": \" + it.message } }\n    val plan = FlowPlanner(registry).plan(ast)\n"
)
replace_exact(
    "src/main/kotlin/org/flowlang/cli/FlowCli.kt",
    "        val validation = FlowValidator(registry).validate(ast)\n        val plan = FlowPlanner(registry).plan(ast)\n",
    "        val validation = FlowValidator(registry).validate(ast)\n        require(validation.valid) { \"Flow validation failed before planning: \" + validation.issues.joinToString { it.code + \": \" + it.message } }\n        val plan = FlowPlanner(registry).plan(ast)\n",
    expected=1
)
replace_exact(
    "src/main/kotlin/org/flowlang/cli/FlowCli.kt",
    "    val validation = FlowValidator(registry).validate(ast)\n    val plan = FlowPlanner(registry).plan(ast)\n",
    "    val validation = FlowValidator(registry).validate(ast)\n    require(validation.valid) { \"Flow validation failed before planning: \" + validation.issues.joinToString { it.code + \": \" + it.message } }\n    val plan = FlowPlanner(registry).plan(ast)\n",
    expected=1
)

replace_exact(
    "src/main/kotlin/org/flowlang/standard/StandardDiagnosticCatalog.kt",
    "        code(\"SAFETY_REQUIRED\", \"flow-validation\", \"error\", \"validation-report.json\", \"Destructive Flow AST action lacks a safety rule.\"),\n",
    "        code(\"SAFETY_REQUIRED\", \"flow-validation\", \"error\", \"validation-report.json\", \"Destructive Flow AST action lacks a safety rule.\"),\n" +
    "        code(\"APPROVAL_REQUIRED\", \"flow-validation\", \"error\", \"validation-report.json\", \"An action contract requires unconditional approval before planning.\"),\n" +
    "        code(\"ROLLBACK_APPROVAL_REQUIRED\", \"flow-validation\", \"error\", \"validation-report.json\", \"Rollback-sensitive work outside an error handler requires unconditional approval.\"),\n" +
    "        code(\"ENVIRONMENT_APPROVAL_REQUIRED\", \"flow-validation\", \"error\", \"validation-report.json\", \"A policy-classified sensitive environment requires unconditional approval.\"),\n" +
    "        code(\"ENVIRONMENT_CLASSIFICATION_UNKNOWN\", \"flow-validation\", \"error\", \"validation-report.json\", \"Environment evidence is dynamic or unclassified and cannot proceed to planning.\"),\n"
)

replace_exact(
    "src/test/kotlin/SafetyBoundaryHardeningTests.kt",
    "    fun unknownEnvironmentDoesNotBecomeSensitiveByNameGuessing() {\n        val issues = strictValidator().validate(parse(customEnvironmentDeployFlow()))\n\n        assertTrue(issues.none { it.code == \"ENVIRONMENT_APPROVAL_REQUIRED\" }, issues.toString())\n    }",
    "    fun unknownEnvironmentFailsClosedWithoutBeingGuessedAsSensitive() {\n        val issues = strictValidator().validate(parse(customEnvironmentDeployFlow()))\n\n        assertTrue(issues.any { it.code == \"ENVIRONMENT_CLASSIFICATION_UNKNOWN\" }, issues.toString())\n        assertTrue(issues.none { it.code == \"ENVIRONMENT_APPROVAL_REQUIRED\" }, issues.toString())\n    }"
)

write("src/test/kotlin/EnvironmentSafetyProductionIntegrationTests.kt", r'''
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.flowlang.cli.main as flowMain
import org.flowlang.parser.FlowParser
import org.flowlang.safety.EnvironmentParameterEvidence
import org.flowlang.safety.EnvironmentSensitivity
import org.flowlang.safety.EnvironmentValueKind
import org.flowlang.safety.StandardEnvironmentSafetyPolicyNotes
import org.flowlang.validator.FlowValidator

class EnvironmentSafetyProductionIntegrationTests {
    @Test
    fun standardPolicyPrioritizesSensitiveThenUnknownThenNonSensitive() {
        val policy = StandardEnvironmentSafetyPolicyNotes.policy()
        val evidence = policy.classify(
            listOf(
                EnvironmentParameterEvidence("namespace", EnvironmentValueKind.LITERAL, literalValue = "dev"),
                EnvironmentParameterEvidence("cluster", EnvironmentValueKind.REFERENCE, referencePath = listOf("targetCluster")),
                EnvironmentParameterEvidence("environment", EnvironmentValueKind.LITERAL, literalValue = "prod")
            )
        )
        assertEquals(EnvironmentSensitivity.SENSITIVE, evidence.sensitivity)
        assertEquals("environment.sensitive.production-like", evidence.ruleId)
    }

    @Test
    fun flowValidatorIncludesSensitiveEnvironmentSafetyByDefault() {
        val report = FlowValidator().validate(parse(deploy(namespace = "\"prod\"")))
        assertFalse(report.valid)
        assertTrue(report.issues.any { it.code == "ENVIRONMENT_APPROVAL_REQUIRED" }, report.issues.toString())
    }

    @Test
    fun approvedSensitiveLiteralPassesEnvironmentBoundary() {
        val report = FlowValidator().validate(parse(deploy(namespace = "\"production\"", approval = true)))
        assertTrue(report.issues.none { it.code.startsWith("ENVIRONMENT_") }, report.issues.toString())
    }

    @Test
    fun knownEngineeringLiteralPassesEnvironmentBoundary() {
        val report = FlowValidator().validate(parse(deploy(namespace = "\"qa\"")))
        assertTrue(report.issues.none { it.code.startsWith("ENVIRONMENT_") }, report.issues.toString())
    }

    @Test
    fun unclassifiedLiteralFailsClosedButIsNotInventedAsProduction() {
        val report = FlowValidator().validate(parse(deploy(namespace = "\"customer-a\"")))
        assertFalse(report.valid)
        assertTrue(report.issues.any { it.code == "ENVIRONMENT_CLASSIFICATION_UNKNOWN" }, report.issues.toString())
        assertTrue(report.issues.none { it.code == "ENVIRONMENT_APPROVAL_REQUIRED" }, report.issues.toString())
    }

    @Test
    fun referenceRemainsReferenceAndFailsClosed() {
        val report = FlowValidator().validate(parse(deploy(namespace = "environment", input = true)))
        val issue = report.issues.single { it.code == "ENVIRONMENT_CLASSIFICATION_UNKNOWN" }
        assertTrue(issue.message.contains("runtime reference 'environment'"), issue.message)
        assertFalse(issue.message.contains("namespace=environment"), issue.message)
    }

    @Test
    fun unrelatedProductionTextIsNotEnvironmentEvidence() {
        val report = FlowValidator().validate(parse(deploy(namespace = null, app = "\"production\"")))
        assertTrue(report.issues.none { it.code.startsWith("ENVIRONMENT_") }, report.issues.toString())
    }

    @Test
    fun conditionalApprovalTextDoesNotCountAsUnconditionalApproval() {
        val source = deploy(namespace = "\"prod\"")
            .replace("image: \"demo:1\"", "image: \"demo:1\"\n          safety: onlyIf allow == true")
            .replace("flow \"environment safety\" {", "flow \"environment safety\" {\n      input { allow: boolean required }")
        val report = FlowValidator().validate(parse(source))
        assertTrue(report.issues.any { it.code == "ENVIRONMENT_APPROVAL_REQUIRED" }, report.issues.toString())
    }

    @Test
    fun cliBlocksUnsafeFlowBeforeExecutionPlanOutput() {
        val file = Files.createTempFile("flow-unsafe-environment", ".flow").toFile()
        file.writeText(deploy(namespace = "environment", input = true))
        val originalOut = System.out
        val output = ByteArrayOutputStream()
        try {
            System.setOut(PrintStream(output))
            val error = assertFailsWith<IllegalArgumentException> {
                flowMain(arrayOf(file.absolutePath))
            }
            assertTrue(error.message.orEmpty().contains("ENVIRONMENT_CLASSIFICATION_UNKNOWN"), error.message)
        } finally {
            System.setOut(originalOut)
            file.delete()
        }
        assertFalse(output.toString().contains("EXECUTION PLAN JSON"), output.toString())
    }

    private fun parse(source: String) = FlowParser().parse(source.trimIndent())

    private fun deploy(
        namespace: String?,
        app: String = "\"demo\"",
        approval: Boolean = false,
        input: Boolean = false
    ): String {
        val inputBlock = if (input) "input { environment: text required }" else ""
        val namespaceLine = namespace?.let { "namespace: $it" }.orEmpty()
        val approvalLine = if (approval) "safety: requiresApproval" else ""
        return """
            version "1.0"
            use module "kubernetes" version "1.0"
            flow "environment safety" {
              $inputBlock
              systems {
                system "k8s" { type: kubernetes }
              }
              steps {
                kubernetes.deploy k8s {
                  app: $app
                  $namespaceLine
                  image: "demo:1"
                  $approvalLine
                }
              }
            }
        """
    }
}
''')

replace_exact(
    "src/main/kotlin/org/flowlang/conformance/StandardArchitectureNormalizationChecks.kt",
    "import org.flowlang.planner.FlowPlanner\n",
    "import org.flowlang.planner.FlowPlanner\nimport org.flowlang.parser.FlowParser\n"
)
replace_exact(
    "src/main/kotlin/org/flowlang/conformance/StandardArchitectureNormalizationChecks.kt",
    "        checkIntentLoweringDiagnosticHonesty(),\n        checkIntentDesignReport(),",
    "        checkIntentLoweringDiagnosticHonesty(),\n        checkEnvironmentSafetyProductionIntegration(),\n        checkIntentDesignReport(),"
)
replace_exact(
    "src/main/kotlin/org/flowlang/conformance/StandardArchitectureNormalizationChecks.kt",
    "    private fun checkIntentDesignReport(): ConformanceCheck = runCheck(\"intent.design-report\") {",
    r'''    private fun checkEnvironmentSafetyProductionIntegration(): ConformanceCheck =
        runCheck("flow.environment-safety.production-integration") {
            fun flow(namespace: String, approval: Boolean = false, input: Boolean = false): org.flowlang.ast.FlowDocument {
                val inputBlock = if (input) "input { environment: text required }" else ""
                val approvalLine = if (approval) "safety: requiresApproval" else ""
                return FlowParser().parse(
                    """
                    version "1.0"
                    use module "kubernetes" version "1.0"
                    flow "environment safety" {
                      ${'$'}inputBlock
                      systems { system "k8s" { type: kubernetes } }
                      steps {
                        kubernetes.deploy k8s {
                          app: "demo"
                          namespace: ${'$'}namespace
                          image: "demo:1"
                          ${'$'}approvalLine
                        }
                      }
                    }
                    """.trimIndent()
                )
            }

            val dynamic = FlowValidator(registry).validate(flow("environment", input = true))
            require(!dynamic.valid && dynamic.issues.any { it.code == "ENVIRONMENT_CLASSIFICATION_UNKNOWN" }) {
                "Runtime environment reference was not blocked by the production Flow validator."
            }
            require(dynamic.issues.single { it.code == "ENVIRONMENT_CLASSIFICATION_UNKNOWN" }
                .message.contains("runtime reference 'environment'")) {
                "Reference environment evidence was reinterpreted as a literal."
            }

            val sensitive = FlowValidator(registry).validate(flow("\"prod\""))
            require(!sensitive.valid && sensitive.issues.any { it.code == "ENVIRONMENT_APPROVAL_REQUIRED" }) {
                "Sensitive environment mutation was not approval-gated."
            }

            val approved = FlowValidator(registry).validate(flow("\"prod\"", approval = true))
            require(approved.issues.none { it.code.startsWith("ENVIRONMENT_") }) {
                "Unconditional approval did not satisfy sensitive environment policy: ${'$'}{approved.issues}."
            }

            val engineering = FlowValidator(registry).validate(flow("\"dev\""))
            require(engineering.issues.none { it.code.startsWith("ENVIRONMENT_") }) {
                "Known non-sensitive environment was blocked: ${'$'}{engineering.issues}."
            }
        }

    private fun checkIntentDesignReport(): ConformanceCheck = runCheck("intent.design-report") {'''
)

write("docs/V0_9_7_9_3_ENVIRONMENT_SAFETY_PRODUCTION_INTEGRATION.md", r'''
# v0.9.7.9.3 Environment Safety Production Integration

## Purpose

This work item turns environment safety from an optional side validator into a mandatory part of production Flow validation and CLI lowering.

## Classification contract

Environment evidence has three compile-time forms:

- `LITERAL`: a concrete value that may match a policy rule.
- `REFERENCE`: a runtime reference whose value is not known at compile time.
- `DYNAMIC_EXPRESSION`: any other expression that cannot be reduced safely.

Only parameter names declared by the environment safety policy participate in classification. Text in unrelated fields such as application names, descriptions or image tags is never environment evidence.

Classification is fail closed:

1. Any sensitive match yields `SENSITIVE`.
2. Otherwise any unresolved, dynamic or unclassified candidate yields `UNKNOWN`.
3. `NON_SENSITIVE` is returned only when all relevant evidence is explicitly classified as non-sensitive.

## Production integration

`FlowValidator` invokes `SafetyBoundaryValidator` using the standard notes-backed environment policy. The CLI requires a valid Flow report before `FlowPlanner` is invoked in direct Flow, Intent and normalization-lowering paths. Reference snapshot generation already requires the same validator report.

A `ReferenceNode` remains a reference. Its path is retained as diagnostic evidence and is never converted to a literal environment value.

## Approval evidence

Only unconditional `safety: requiresApproval` satisfies the AST safety boundary. Conditional safety text is not treated as confirmed approval evidence.

## Diagnostics

- `ENVIRONMENT_CLASSIFICATION_UNKNOWN`
- `ENVIRONMENT_APPROVAL_REQUIRED`
- `APPROVAL_REQUIRED`
- `ROLLBACK_APPROVAL_REQUIRED`

All are registered in the standard diagnostic catalog.

## Boundary

This change does not add a runtime environment resolver, target-specific environment aliases or a shell/runtime escape hatch. Dynamic environment classification remains blocked until a later explicit enforcement capability can prove it.
''')

write(".flow-agent/work-packages/v0.9.7.9.3-environment-safety-production-integration.yaml", r'''
version: "0.9.7.9.3"
name: "Environment Safety Production Integration"
stream: core
status: validation-pending
objective: "Make notes-backed environment safety a fail-closed production validation gate before planning and projection."
scopeBoundary:
  - "Keep environment classification target-neutral and notes-backed."
  - "Do not add runtime environment lookup or infer target aliases."
  - "Treat references as dynamic evidence, never as literal environment values."
  - "Require the same safety gate in direct Flow, Intent lowering, normalization lowering and reference generation."
universalInvariants:
  - "Only policy-recognized parameter names are environment evidence."
  - "Sensitive evidence requires unconditional approval."
  - "Unknown literal, reference and dynamic expression evidence blocks planning."
  - "Non-sensitive status requires explicit policy evidence."
  - "CLI lowering cannot invoke FlowPlanner after an invalid Flow validation report."
implementedSlices:
  - id: typed-environment-evidence
  - id: fail-closed-unknown-classification
  - id: reference-preservation
  - id: flow-validator-production-wiring
  - id: cli-pre-planning-gate
  - id: diagnostic-catalog-registration
  - id: conformance-and-adversarial-tests
validation:
  required:
    - "Flow agent tooling tests"
    - "Repository structure validation"
    - "Clean Gradle compilation and complete test suite"
    - "Standalone Flow conformance"
followUpWorkItem:
  version: "0.9.7.9.4"
  name: "Scenario Negation and Token Boundary Honesty"
''')

write(".flow-agent/reports/v0.9.7.9.3-environment-safety-production-integration.md", r'''
# v0.9.7.9.3 Environment Safety Production Integration

## Status

Implementation prepared. Exact-head CI validation is pending.

## Corrected failures

- `SafetyBoundaryValidator` was optional and absent from `FlowValidator`.
- direct Flow, Intent and normalization CLI paths could call `FlowPlanner` after an invalid Flow report.
- `ReferenceNode.path.singleOrNull()` was reinterpreted as an environment literal.
- `UNKNOWN` environment evidence behaved like non-sensitive evidence.
- arbitrary scalar parameters were offered to the environment policy.
- conditional safety text could be confused with unconditional approval evidence.

## Implemented design

Environment parameter evidence is typed as literal, reference or dynamic expression. The standard policy evaluates only names declared by its rules and applies fail-closed precedence: sensitive, then unknown, then non-sensitive.

`FlowValidator` owns production composition of structural and safety validation. CLI lowering requires a valid Flow report before planning. Reference snapshot generation already enforces the composed report.

## Verification coverage

Tests and conformance cover sensitive, non-sensitive, unclassified literal, runtime reference, unrelated text, unconditional approval and conditional safety cases. A CLI regression test proves an unsafe direct Flow fails before execution-plan output.

## Architecture boundary

No target environment aliases, runtime resolver, command transport or shell projection is introduced.
''')

print("v0.9.7.9.3 migration applied")
