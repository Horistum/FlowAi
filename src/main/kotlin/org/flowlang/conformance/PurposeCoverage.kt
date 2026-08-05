package org.flowlang.conformance

import org.flowlang.artifacts.ReferenceIntentCorpusReport
import org.flowlang.artifacts.StandardSurface
import org.flowlang.standard.FlowStandardVersions
import org.flowlang.standard.GateKind
import org.flowlang.standard.StandardCheck
import org.flowlang.standard.StandardModel

/**
 * Purpose coverage retains ratios as observations, but pass/fail is structural:
 * required target-neutral capabilities, blocked-risk scenarios, semantic purpose
 * categories and evidence-backed categories. Adding an honest governance check
 * cannot make Flow less purposeful merely by changing a denominator.
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
    val requiredPurposeKinds: List<String>,
    val coveredPurposeKinds: List<String>,
    val missingPurposeKinds: List<String>,
    val evidenceBackedPurposeKinds: List<String>,
    val missingEvidenceBackedPurposeKinds: List<String>,
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
 * Purpose coverage is a behavioral quality signal, not a quota system for check
 * kinds. It fails when the public standard loses a required semantic category,
 * loses evidence for that category or loses target-neutral corpus coverage.
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
        val automationPurposeChecks = releaseChecks.filter { it.kind in requiredPurposeKinds }
        val governanceChecks = releaseChecks.filter { it.kind == GateKind.GOVERNANCE }
        val evidenceBackedPurposeChecks = automationPurposeChecks.filter { it.hasEvidence() }
        val coveredPurposeKinds = automationPurposeChecks.map { it.kind }.distinct().sortedBy { it.name }
        val evidenceBackedPurposeKinds = evidenceBackedPurposeChecks.map { it.kind }.distinct().sortedBy { it.name }
        val missingPurposeKinds = requiredPurposeKinds.filterNot(coveredPurposeKinds::contains)
        val missingEvidenceBackedPurposeKinds = requiredPurposeKinds.filterNot(evidenceBackedPurposeKinds::contains)
        val missingCapabilities = requiredPurposeCapabilities.filterNot(coveredCapabilities::contains)
        val missingBlockedRiskCapabilities = requiredBlockedRiskCapabilities.filterNot(blockedRiskCapabilities::contains)
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
                message = "Reference corpus does not cover all required target-neutral purpose capabilities.",
                subject = missingCapabilities.joinToString()
            )
        }
        if (missingBlockedRiskCapabilities.isNotEmpty()) {
            issues += PurposeCoverageIssue(
                code = "PURPOSE_COVERAGE_BLOCKED_RISK_MISSING",
                severity = "error",
                message = "Risk-sensitive target-neutral capabilities need at least one blocked reference scenario.",
                subject = missingBlockedRiskCapabilities.joinToString()
            )
        }
        if (missingPurposeKinds.isNotEmpty()) {
            issues += PurposeCoverageIssue(
                code = "PURPOSE_COVERAGE_KIND_MISSING",
                severity = "error",
                message = "The release profile is missing required automation-purpose categories.",
                subject = missingPurposeKinds.joinToString { it.category }
            )
        }
        if (missingEvidenceBackedPurposeKinds.isNotEmpty()) {
            issues += PurposeCoverageIssue(
                code = "PURPOSE_COVERAGE_KIND_EVIDENCE_MISSING",
                severity = "error",
                message = "Every required automation-purpose category needs at least one fixture or external evidence anchor.",
                subject = missingEvidenceBackedPurposeKinds.joinToString { it.category }
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
            requiredPurposeKinds = requiredPurposeKinds.map { it.category },
            coveredPurposeKinds = coveredPurposeKinds.map { it.category },
            missingPurposeKinds = missingPurposeKinds.map { it.category },
            evidenceBackedPurposeKinds = evidenceBackedPurposeKinds.map { it.category },
            missingEvidenceBackedPurposeKinds = missingEvidenceBackedPurposeKinds.map { it.category },
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

    private fun StandardCheck.hasEvidence(): Boolean =
        negativeFixture.isNotBlank() || externalAnchor.isNotBlank()

    private fun ratio(numerator: Int, denominator: Int): Double =
        if (denominator == 0) 0.0 else numerator.toDouble() / denominator.toDouble()

    companion object {
        val requiredPurposeKinds: List<GateKind> = listOf(
            GateKind.BEHAVIOR,
            GateKind.SAFETY,
            GateKind.NORMALIZATION,
            GateKind.EXECUTION_PLAN,
            GateKind.PORTABILITY
        )

        val automationPurposeKinds: Set<GateKind> = requiredPurposeKinds.toSet()

        val requiredPurposeCapabilities: List<String> = listOf(
            "APPROVE",
            "BACKUP",
            "BUILD",
            "BUILD_IMAGE",
            "CERTIFICATE_RENEW",
            "CHECKOUT",
            "CLEANUP",
            "CLUSTER_MAINTENANCE",
            "DATABASE_MIGRATE",
            "DEPLOY",
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
            "CLUSTER_MAINTENANCE",
            "DATABASE_MIGRATE",
            "DEPLOY",
            "SECRET_ROTATE"
        ).sorted()

        const val minimumReferenceScenarios: Int = 20
    }
}