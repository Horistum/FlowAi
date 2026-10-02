package org.flowlang.safety

import java.io.File

import org.flowlang.notes.NotesPackageContract
import org.flowlang.notes.NotesPackageKind
import org.flowlang.notes.StandardNotesPackageContracts

object StandardEnvironmentSafetyPolicyNotes {
    fun baseline(): EnvironmentSafetyPolicyNotes = baseline(File("."))

    fun baseline(rootDir: File): EnvironmentSafetyPolicyNotes {
        val contract = StandardNotesPackageContracts.baseline(rootDir).singleSafetyContract()
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

    fun policy(rootDir: File): EnvironmentSafetyPolicy = EnvironmentSafetyPolicy(baseline(rootDir))

    private fun List<NotesPackageContract>.singleSafetyContract(): NotesPackageContract =
        single { contract ->
            contract.kind == NotesPackageKind.SAFETY && contract.packageId == "flow.safety.core"
        }
}
