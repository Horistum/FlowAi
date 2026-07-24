package org.flowlang.artifacts

import org.flowlang.conformance.ConformanceManifestReport
import org.flowlang.standard.FlowStandardVersions

data class ComplianceGate(
    val id: String,
    val artifact: String,
    val status: String,
    val message: String
)

data class StandardComplianceReport(
    val standardVersion: String = FlowStandardVersions.FLOW_STANDARD_VERSION,
    val complianceReportVersion: String = "1.0",
    val profile: String,
    val flowName: String,
    val target: String,
    val status: String,
    val gates: List<ComplianceGate>,
    val failedGates: List<String>
)

class StandardComplianceAnalyzer {
    fun analyze(
        bundle: FlowArtifactBundleReport,
        contractIndex: StandardContractIndexReport,
        releaseProfile: StandardReleaseProfileReport,
        evidence: ArtifactEvidenceReport,
        integrity: ArtifactIntegrityReport,
        conformanceManifest: ConformanceManifestReport? = null
    ): StandardComplianceReport {
        val conformanceStatus = conformanceManifest?.status ?: "NOT_PROVIDED"
        val gates = listOf(
            gate("contract-index.present", "standard-contract-index.json", contractIndex.contracts.isNotEmpty(), "Contract index contains public contracts."),
            gate("release-profile.present", "standard-release-profile.json", releaseProfile.requirements.isNotEmpty(), "Release profile contains required gates."),
            gate("evidence.complete", "artifact-evidence-report.json", evidence.missingEvidence.isEmpty(), "Required derived artifacts have evidence."),
            gate("artifact-integrity.pass", "artifact-integrity-report.json", integrity.status == "PASS", "Artifact integrity status is ${integrity.status}."),
            gate(
                "conformance.pass",
                "conformance-manifest.json",
                conformanceManifest?.status == "PASS",
                "Conformance manifest status is $conformanceStatus; release compliance requires explicit PASS evidence."
            ),
            gate("bundle.contains-compliance", "flow-artifact-bundle.json", bundle.requiredArtifacts.contains("standard-compliance-report.json"), "Artifact bundle declares compliance report."),
            gate("bundle.contains-draft", "flow-artifact-bundle.json", bundle.requiredArtifacts.contains("flow-standard-draft.json"), "Artifact bundle declares Flow Standard Draft 0.4.")
        )
        val failed = gates.filter { it.status == "FAIL" }.map { it.id }
        return StandardComplianceReport(
            profile = releaseProfile.profile,
            flowName = bundle.flowName,
            target = bundle.target,
            status = if (failed.isEmpty()) "PASS" else "FAIL",
            gates = gates,
            failedGates = failed
        )
    }

    private fun gate(id: String, artifact: String, pass: Boolean, message: String): ComplianceGate =
        ComplianceGate(id, artifact, if (pass) "PASS" else "FAIL", message)
}
