package org.flowlang.artifacts

import org.flowlang.standard.FlowStandardVersions

data class ArtifactEvidenceEntry(
    val artifact: String,
    val derivedFrom: List<String>,
    val evidenceType: String,
    val producer: String,
    val schema: String,
    val required: Boolean
)

data class ArtifactEvidenceReport(
    val standardVersion: String = FlowStandardVersions.FLOW_STANDARD_VERSION,
    val evidenceReportVersion: String = "1.0",
    val flowName: String,
    val target: String,
    val evidence: List<ArtifactEvidenceEntry>,
    val missingEvidence: List<String>
)

class ArtifactEvidenceAnalyzer {
    fun analyze(bundle: FlowArtifactBundleReport): ArtifactEvidenceReport {
        val evidence = bundle.artifacts.map { artifact ->
            val contract = ArtifactContractAuthority.definitionFor(artifact.name, artifact.role)
            ArtifactEvidenceEntry(
                artifact = artifact.name,
                derivedFrom = artifact.derivedFrom,
                evidenceType = if (artifact.derived) "derived" else "source",
                producer = contract.producer,
                schema = artifact.schema,
                required = artifact.required
            )
        }
        return ArtifactEvidenceReport(
            flowName = bundle.flowName,
            target = bundle.target,
            evidence = evidence,
            missingEvidence = ArtifactContractAuthority.missingEvidence(bundle)
        )
    }
}
