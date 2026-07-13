package org.flowlang.safety

import org.flowlang.notes.NotesPackageContract
import org.flowlang.notes.NotesPackageKind
import org.flowlang.notes.StandardNotesPackageContracts

/**
 * Evidence used to classify an explicitly declared environment-like parameter.
 *
 * The policy describes safety meaning only. It is not a target adapter, runtime
 * environment resolver or deployment framework.
 */
enum class EnvironmentSensitivity {
    SENSITIVE,
    NON_SENSITIVE,
    UNKNOWN
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
    val reason: String
) {
    val evidenceAvailable: Boolean = ruleId != null && parameterName != null && parameterValue != null
}

class EnvironmentSafetyPolicy(private val notes: EnvironmentSafetyPolicyNotes) {
    init {
        require(notes.packageId.isNotBlank()) { "Environment safety policy package id is required." }
        require(notes.packageVersion.isNotBlank()) { "Environment safety policy package version is required." }
        require(notes.rules.isNotEmpty()) { "Environment safety policy must declare at least one rule." }
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

    fun classify(parameters: Map<String, String>): EnvironmentClassificationEvidence {
        val normalized = parameters.mapKeys { (name, _) -> name.trim().lowercase() }
            .mapValues { (_, value) -> value.trim().lowercase() }

        val matches = notes.rules.flatMap { rule ->
            normalized.mapNotNull { (name, value) ->
                if (name in rule.parameterNames.normalized() && value in rule.values.normalized()) {
                    RuleMatch(rule, name, value)
                } else null
            }
        }

        val selected = matches.sortedWith(
            compareByDescending<RuleMatch> { it.rule.sensitivity == EnvironmentSensitivity.SENSITIVE }
                .thenBy { it.rule.ruleId }
                .thenBy { it.parameterName }
        ).firstOrNull()

        if (selected == null) {
            return EnvironmentClassificationEvidence(
                sensitivity = EnvironmentSensitivity.UNKNOWN,
                policyPackageId = notes.packageId,
                policyPackageVersion = notes.packageVersion,
                ruleId = null,
                parameterName = null,
                parameterValue = null,
                approvalEnvironment = null,
                reason = notes.unknownReason
            )
        }

        return EnvironmentClassificationEvidence(
            sensitivity = selected.rule.sensitivity,
            policyPackageId = notes.packageId,
            policyPackageVersion = notes.packageVersion,
            ruleId = selected.rule.ruleId,
            parameterName = selected.parameterName,
            parameterValue = selected.parameterValue,
            approvalEnvironment = selected.rule.approvalEnvironment,
            reason = selected.rule.reason
        )
    }

    private data class RuleMatch(
        val rule: EnvironmentPolicyRule,
        val parameterName: String,
        val parameterValue: String
    )

    private fun Set<String>.normalized(): Set<String> = map { it.trim().lowercase() }.toSet()
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
            unknownReason = "No declared environment safety rule matched the available parameters; sensitivity remains unknown."
        )
    }

    fun policy(): EnvironmentSafetyPolicy = EnvironmentSafetyPolicy(baseline())

    private fun List<NotesPackageContract>.singleSafetyContract(): NotesPackageContract =
        single { contract ->
            contract.kind == NotesPackageKind.SAFETY && contract.packageId == "flow.safety.core"
        }
}
