
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
