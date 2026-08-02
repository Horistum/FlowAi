package org.flowlang.artifacts

import org.flowlang.standard.FlowStandardVersions

data class ConformanceAreaSummary(
    val area: String,
    val requiredChecks: Int,
    val passed: Int,
    val failed: Int
)

data class ConformanceVectorEntry(
    val path: String,
    val area: String,
    val required: Boolean
)

data class ConformanceSchemaEntry(
    val artifact: String,
    val schema: String,
    val required: Boolean
)

data class ConformanceManifestReport(
    val standardVersion: String = FlowStandardVersions.FLOW_STANDARD_VERSION,
    val conformanceManifestVersion: String = "1.0",
    val implementation: String,
    val status: String,
    val totalChecks: Int,
    val passed: Int,
    val failed: Int,
    val areas: List<ConformanceAreaSummary>,
    val requiredChecks: List<String>,
    val failedChecks: List<String>,
    val vectors: List<ConformanceVectorEntry>,
    val publicSchemas: List<ConformanceSchemaEntry>,
    val requiredArtifacts: List<String>
)
