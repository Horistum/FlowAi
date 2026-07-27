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
            val contract = ArtifactContractAuthority.definitionFor(artifact.name, artifact.role)
            StandardContractEntry(
                artifact = artifact.name,
                role = artifact.role.name.lowercase(),
                schema = artifact.schema.ifBlank { schemaByArtifact[artifact.name].orEmpty() },
                required = artifact.required,
                derived = artifact.derived,
                pipelineIndex = artifact.pipelineIndex,
                introducedIn = contract.introducedIn,
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
}
