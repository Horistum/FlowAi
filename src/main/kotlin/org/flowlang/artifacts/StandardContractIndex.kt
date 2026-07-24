package org.flowlang.artifacts

import org.flowlang.conformance.ConformanceManifestReport
import org.flowlang.standard.FlowStandardVersions

data class StandardContractEntry(
    val artifact: String,
    val role: String,
    val schema: String,
    val required: Boolean,
    val derived: Boolean,
    val pipelineIndex: Int,
    val introducedIn: String,
    val derivedFrom: List<String> = emptyList()
)

data class StandardContractIndexReport(
    val standardVersion: String = FlowStandardVersions.FLOW_STANDARD_VERSION,
    val contractIndexVersion: String = "1.0",
    val flowName: String,
    val target: String,
    val contracts: List<StandardContractEntry>,
    val requiredContracts: List<String>,
    val optionalContracts: List<String>,
    val publicSchemas: List<String>,
    val conformanceVectors: List<String>
)

class StandardContractIndexAnalyzer {
    fun analyze(
        bundle: FlowArtifactBundleReport,
        conformanceManifest: ConformanceManifestReport? = null
    ): StandardContractIndexReport {
        val schemaByArtifact = conformanceManifest
            ?.publicSchemas
            .orEmpty()
            .associate { it.artifact to it.schema }
        val contracts = bundle.artifacts.map { artifact ->
            StandardContractEntry(
                artifact = artifact.name,
                role = artifact.role.name.lowercase(),
                schema = artifact.schema.ifBlank { schemaByArtifact[artifact.name].orEmpty() },
                required = artifact.required,
                derived = artifact.derived,
                pipelineIndex = artifact.pipelineIndex,
                introducedIn = introducedIn(artifact.name),
                derivedFrom = artifact.derivedFrom
            )
        }
        return StandardContractIndexReport(
            flowName = bundle.flowName,
            target = bundle.target,
            contracts = contracts,
            requiredContracts = contracts.filter { it.required }.map { it.artifact },
            optionalContracts = contracts.filter { !it.required }.map { it.artifact },
            publicSchemas = contracts.mapNotNull { it.schema.ifBlank { null } }.distinct().sorted(),
            conformanceVectors = conformanceManifest?.vectors.orEmpty().map { it.path }.sorted()
        )
    }

    private fun introducedIn(artifact: String): String = when (artifact) {
        "flow-artifact-bundle.json" -> "0.3.10"
        "conformance-manifest.json" -> "0.3.11"
        "target-adapter-contract.json", "adapter-diagnostics.json" -> "0.3.12"
        "standard-diagnostic-catalog.json" -> "0.3.13"
        "diagnostic-coverage-report.json" -> "0.3.14"
        "artifact-integrity-report.json" -> "0.3.15"
        "standard-contract-index.json" -> "0.3.16"
        "standard-release-profile.json" -> "0.3.17"
        "artifact-evidence-report.json" -> "0.3.18"
        "standard-compliance-report.json" -> "0.3.19"
        "standard-freeze-report.json" -> "0.3.20"
        "compatibility-policy.json" -> "0.3.21"
        "reference-corpus-index.json" -> "0.3.22"
        "negative-conformance-corpus.json" -> "0.3.23"
        "target-conformance-profile.json" -> "0.4.2"
        "public-standard-surface.json" -> "0.4.5"
        "compatibility-migration-policy.json" -> "0.4.6"
        "reference-intent-corpus.json" -> "0.4.7"
        "target-semantics-matrix.json" -> "0.4.8"
        "standard-export-bundle.json" -> "0.4.9"
        "conformance-levels.json", "standard-export-manifest.json" -> "0.5.0"
        "conformance-vector-index.json" -> "0.5.4"
        "standard-index.json", "conformance-suite.json", "flow-standard-draft.json" -> "0.4.0"
        "release-metadata-honesty-report.json" -> "0.9.7.9.7"
        else -> "pre-0.3.10"
    }
}
