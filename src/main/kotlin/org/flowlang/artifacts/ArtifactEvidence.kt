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
            ArtifactEvidenceEntry(
                artifact = artifact.name,
                derivedFrom = artifact.derivedFrom,
                evidenceType = if (artifact.derived) "derived" else "source",
                producer = producerFor(artifact.name),
                schema = artifact.schema,
                required = artifact.required
            )
        }
        return ArtifactEvidenceReport(
            flowName = bundle.flowName,
            target = bundle.target,
            evidence = evidence,
            missingEvidence = evidence
                .filter { it.required && it.evidenceType == "derived" && it.derivedFrom.isEmpty() }
                .map { it.artifact }
                .sorted()
        )
    }

    private fun producerFor(artifact: String): String = when (artifact) {
        "standard-contract-index.json" -> "StandardContractIndexAnalyzer"
        "standard-release-profile.json" -> "StandardReleaseProfile"
        "artifact-evidence-report.json" -> "ArtifactEvidenceAnalyzer"
        "standard-compliance-report.json" -> "StandardComplianceAnalyzer"
        "artifact-integrity-report.json" -> "ArtifactIntegrityAnalyzer"
        "diagnostic-coverage-report.json" -> "DiagnosticCoverageAnalyzer"
        "flow-artifact-bundle.json" -> "FlowArtifactBundleAnalyzer"
        "standard-freeze-report.json" -> "PublicStandardDraft.freeze"
        "compatibility-policy.json" -> "PublicStandardDraft.compatibilityPolicy"
        "reference-corpus-index.json" -> "PublicStandardDraft.referenceCorpus"
        "negative-conformance-corpus.json" -> "PublicStandardDraft.negativeCorpus"
        "target-conformance-profile.json" -> "PublicStandardDraft.targetConformanceProfile"
        "public-standard-surface.json" -> "StandardSurface.publicSurface"
        "compatibility-migration-policy.json" -> "StandardSurface.compatibilityMigrationPolicy"
        "reference-intent-corpus.json" -> "StandardSurface.referenceIntentCorpus"
        "target-semantics-matrix.json" -> "StandardSurface.targetSemanticsMatrix"
        "standard-export-bundle.json" -> "StandardSurface.standardExportBundle"
        "conformance-levels.json" -> "StandardSurface.conformanceLevels"
        "standard-export-manifest.json" -> "StandardSurface.standardExportManifest"
        "conformance-vector-index.json" -> "ConformanceVectorIndexBuilder"
        "standard-index.json" -> "PublicStandardDraft.standardIndex"
        "conformance-suite.json" -> "PublicStandardDraft.conformanceSuite"
        "flow-standard-draft.json" -> "PublicStandardDraft.draft"
        else -> "flow-public-pipeline"
    }
}
