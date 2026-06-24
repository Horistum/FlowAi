package org.flowlang.standard

import org.flowlang.artifacts.ReferenceIntentCorpusReport
import org.flowlang.artifacts.StandardSurface

/**
 * Measures whether the public standard still grows around Flow's purpose:
 * portable automation intent, safety boundaries, normalization, planning and
 * target portability. The report is intentionally derived from the public
 * StandardModel and reference intent corpus, not from implementation internals.
 */
data class PurposeCoverageReport(
    val standardVersion: String = FlowStandardVersions.FLOW_STANDARD_VERSION,
    val status: String,
    val referenceScenarioCount: Int,
    val acceptedScenarioCount: Int,
    val blockedScenarioCount: Int,
    val requiredPurposeCapabilities: List<String>,
    val coveredCapabilities: List<String>,
    val missingCapabilities: List<String>,
    val blockedRiskCapabilities: List<String>,
    val missingBlockedRiskCapabilities: List<String>,
    val automationPurposeCheckCount: Int,
    val governanceCheckCount: Int,
    val releaseCheckCount: Int,
    val automationPurposeRatio: Double,
    val governanceRatio: Double,
    val evidenceBackedPurposeCheckCount: Int,
    val evidenceBackedPurposeRatio: Double,
    val issues: List<PurposeCoverageIssue>
)

data class PurposeCoverageIssue(
    val code: String,
    val severity: String,
    val message: String,
    val subject: String = ""
)

/**
 * Purpose coverage is a behavioral quality signal, not another registry parity
 * check. It fails when the standard surface grows away from executable intent
 * evidence, or when the reference corpus stops covering the core automation
 * scenarios Flow promises to standardize.
 */
class PurposeCoverageAnalyzer(
    private val corpus: ReferenceIntentCorpusReport = StandardSurface.referenceIntentCorpus(),
    private val checks: List<StandardCheck> = StandardModel.checks
) {
    fun analyze(): PurposeCoverageReport {
        val coveredCapabilities = corpus.scenarios
            .flatMap { it.expectedCapabilities }
            .distinct()
            .sorted()
        val blockedRiskCapabilities = corpus.scenarios
            .filter { it.expectedStatus == "BLOCKED" }
            .flatMap { it.expectedCapabilities }
            .distinct()
            .sorted()
        val releaseChecks = checks.filter { it.inReleaseProfile }
        val automationPurposeChecks = releaseChecks.filter { it.kind in automationPurposeKinds }
        val governanceChecks = releaseChecks.filter { it.kind == GateKind.GOVERNANCE }
        val evidenceBackedPurposeChecks = automationPurposeChecks.filter { it.hasEvidence() }
        val missingCapabilities = requiredPurposeCapabilities.filterNot { it in coveredCapabilities }
        val missingBlockedRiskCapabilities = requiredBlockedRiskCapabilities.filterNot { it in blockedRiskCapabilities }
        val automationPurposeRatio = ratio(automationPurposeChecks.size, releaseChecks.size)
        val governanceRatio = ratio(governanceChecks.size, releaseChecks.size)
        val evidenceBackedPurposeRatio = ratio(evidenceBackedPurposeChecks.size, automationPurposeChecks.size)

        val issues = mutableListOf<PurposeCoverageIssue>()
        if (corpus.scenarios.size < minimumReferenceScenarios) {
            issues += PurposeCoverageIssue(
                code = "PURPOSE_COVERAGE_REFERENCE_CORPUS_TOO_SMALL",
                severity = "error",
                message = "Reference corpus must keep at least $minimumReferenceScenarios scenarios after v0.7.5.",
                subject = corpus.scenarios.size.toString()
            )
        }
        if (missingCapabilities.isNotEmpty()) {
            issues += PurposeCoverageIssue(
                code = "PURPOSE_COVERAGE_CAPABILITY_MISSING",
                severity = "error",
                message = "Reference corpus does not cover all required purpose capabilities.",
                subject = missingCapabilities.joinToString()
            )
        }
        if (missingBlockedRiskCapabilities.isNotEmpty()) {
            issues += PurposeCoverageIssue(
                code = "PURPOSE_COVERAGE_BLOCKED_RISK_MISSING",
                severity = "error",
                message = "Risk-sensitive capabilities need at least one blocked reference scenario.",
                subject = missingBlockedRiskCapabilities.joinToString()
            )
        }
        if (automationPurposeRatio < minimumAutomationPurposeRatio) {
            issues += PurposeCoverageIssue(
                code = "PURPOSE_COVERAGE_AUTOMATION_RATIO_LOW",
                severity = "error",
                message = "Automation-purpose checks must stay at or above ${minimumAutomationPurposeRatio.formatRatio()} of release checks.",
                subject = "automationPurposeRatio=${automationPurposeRatio.formatRatio()}"
            )
        }
        if (governanceRatio > maximumGovernanceRatio) {
            issues += PurposeCoverageIssue(
                code = "PURPOSE_COVERAGE_GOVERNANCE_RATIO_HIGH",
                severity = "error",
                message = "Governance checks must not dominate the release profile.",
                subject = "governanceRatio=${governanceRatio.formatRatio()}"
            )
        }
        if (evidenceBackedPurposeRatio < minimumEvidenceBackedPurposeRatio) {
            issues += PurposeCoverageIssue(
                code = "PURPOSE_COVERAGE_EVIDENCE_RATIO_LOW",
                severity = "error",
                message = "Automation-purpose checks must be backed by fixtures or external anchors.",
                subject = "evidenceBackedPurposeRatio=${evidenceBackedPurposeRatio.formatRatio()}"
            )
        }
        if (StandardModel.registryConsistencyCheckIds().isNotEmpty()) {
            issues += PurposeCoverageIssue(
                code = "PURPOSE_COVERAGE_REGISTRY_GATE_PRESENT",
                severity = "error",
                message = "Registry-consistency gates are bookkeeping, not purpose coverage.",
                subject = StandardModel.registryConsistencyCheckIds().joinToString()
            )
        }

        return PurposeCoverageReport(
            status = if (issues.none { it.severity == "error" }) "PASS" else "FAIL",
            referenceScenarioCount = corpus.scenarios.size,
            acceptedScenarioCount = corpus.scenarios.count { it.expectedStatus == "ACCEPTED" },
            blockedScenarioCount = corpus.scenarios.count { it.expectedStatus == "BLOCKED" },
            requiredPurposeCapabilities = requiredPurposeCapabilities,
            coveredCapabilities = coveredCapabilities,
            missingCapabilities = missingCapabilities,
            blockedRiskCapabilities = blockedRiskCapabilities,
            missingBlockedRiskCapabilities = missingBlockedRiskCapabilities,
            automationPurposeCheckCount = automationPurposeChecks.size,
            governanceCheckCount = governanceChecks.size,
            releaseCheckCount = releaseChecks.size,
            automationPurposeRatio = automationPurposeRatio,
            governanceRatio = governanceRatio,
            evidenceBackedPurposeCheckCount = evidenceBackedPurposeChecks.size,
            evidenceBackedPurposeRatio = evidenceBackedPurposeRatio,
            issues = issues
        )
    }

    private fun StandardCheck.hasEvidence(): Boolean = negativeFixture.isNotBlank() || externalAnchor.isNotBlank()

    private fun ratio(numerator: Int, denominator: Int): Double =
        if (denominator == 0) 0.0 else numerator.toDouble() / denominator.toDouble()

    private fun Double.formatRatio(): String = "%.2f".format(this)

    companion object {
        val automationPurposeKinds: Set<GateKind> = setOf(
            GateKind.BEHAVIOR,
            GateKind.SAFETY,
            GateKind.NORMALIZATION,
            GateKind.EXECUTION_PLAN,
            GateKind.PORTABILITY
        )

        val requiredPurposeCapabilities: List<String> = listOf(
            "APPROVE",
            "BACKUP",
            "BUILD",
            "BUILD_IMAGE",
            "CERTIFICATE_RENEW",
            "CHECKOUT",
            "CLEANUP",
            "DATABASE_MIGRATE",
            "DEPLOY",
            "KUBERNETES_MAINTENANCE",
            "NOTIFY",
            "ROLLBACK",
            "SECRET_ROTATE",
            "TEST",
            "VERIFY"
        ).sorted()

        val requiredBlockedRiskCapabilities: List<String> = listOf(
            "APPROVE",
            "CERTIFICATE_RENEW",
            "CLEANUP",
            "DATABASE_MIGRATE",
            "DEPLOY",
            "KUBERNETES_MAINTENANCE",
            "SECRET_ROTATE"
        ).sorted()

        const val minimumReferenceScenarios: Int = 20
        const val minimumAutomationPurposeRatio: Double = 0.50
        const val maximumGovernanceRatio: Double = 0.15
        const val minimumEvidenceBackedPurposeRatio: Double = 0.60
    }
}

