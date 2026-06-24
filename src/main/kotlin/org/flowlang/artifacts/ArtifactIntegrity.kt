package org.flowlang.artifacts

import org.flowlang.standard.DiagnosticCoverageReport
import org.flowlang.standard.FlowStandardVersions

data class ArtifactIntegrityIssue(
    val code: String,
    val severity: String,
    val artifact: String,
    val message: String
)

data class ArtifactIntegrityVersionObservation(
    val artifact: String,
    val standardVersion: String
)

data class ArtifactIntegrityReport(
    val standardVersion: String = FlowStandardVersions.FLOW_STANDARD_VERSION,
    val artifactIntegrityVersion: String = "1.0",
    val flowName: String,
    val target: String,
    val status: String,
    val requiredArtifactsExpected: List<String>,
    val requiredArtifactsPresent: List<String>,
    val missingRequiredArtifacts: List<String>,
    val artifactsWithoutSchema: List<String>,
    val standardVersionObservations: List<ArtifactIntegrityVersionObservation>,
    val standardVersionMismatches: List<ArtifactIntegrityVersionObservation>,
    val diagnosticCoverageStatus: String,
    val issues: List<ArtifactIntegrityIssue>
)

/**
 * Checks whether the public artifact set is internally consistent.
 *
 * This is a standard contract check, not an execution/runtime check. It does
 * not interpret intent or render target YAML. It verifies the artifact surface
 * that downstream tools consume.
 */
class ArtifactIntegrityAnalyzer {
    fun analyze(
        bundle: FlowArtifactBundleReport,
        presentArtifacts: Set<String>,
        standardVersionObservations: List<ArtifactIntegrityVersionObservation>,
        diagnosticCoverage: DiagnosticCoverageReport
    ): ArtifactIntegrityReport {
        val issues = mutableListOf<ArtifactIntegrityIssue>()
        val missingRequired = bundle.requiredArtifacts.filter { it !in presentArtifacts }.sorted()
        missingRequired.forEach { artifact ->
            issues += ArtifactIntegrityIssue(
                code = "ARTIFACT_REQUIRED_MISSING",
                severity = "error",
                artifact = artifact,
                message = "Required public artifact '$artifact' is missing from the exported artifact set."
            )
        }

        val artifactsWithoutSchema = bundle.artifacts
            .filter { it.required && it.derived && it.name.endsWith(".json") && it.schema.isBlank() }
            .map { it.name }
            .sorted()
        artifactsWithoutSchema.forEach { artifact ->
            issues += ArtifactIntegrityIssue(
                code = "ARTIFACT_SCHEMA_MISSING",
                severity = "warning",
                artifact = artifact,
                message = "Public JSON artifact '$artifact' does not declare a schema in flow-artifact-bundle.json."
            )
        }

        val versionMismatches = standardVersionObservations
            .filter { it.standardVersion.isNotBlank() && it.standardVersion != FlowStandardVersions.FLOW_STANDARD_VERSION }
            .sortedBy { it.artifact }
        versionMismatches.forEach { observation ->
            issues += ArtifactIntegrityIssue(
                code = "ARTIFACT_STANDARD_VERSION_MISMATCH",
                severity = "error",
                artifact = observation.artifact,
                message = "Artifact '${observation.artifact}' declares standardVersion '${observation.standardVersion}', expected '${FlowStandardVersions.FLOW_STANDARD_VERSION}'."
            )
        }

        if (diagnosticCoverage.status != "PASS") {
            issues += ArtifactIntegrityIssue(
                code = "ARTIFACT_DIAGNOSTIC_COVERAGE_FAILED",
                severity = "error",
                artifact = "diagnostic-coverage-report.json",
                message = "Diagnostic coverage status is '${diagnosticCoverage.status}'."
            )
        }

        val errors = issues.any { it.severity == "error" }
        return ArtifactIntegrityReport(
            flowName = bundle.flowName,
            target = bundle.target,
            status = if (errors) "FAIL" else "PASS",
            requiredArtifactsExpected = bundle.requiredArtifacts.sorted(),
            requiredArtifactsPresent = bundle.requiredArtifacts.filter { it in presentArtifacts }.sorted(),
            missingRequiredArtifacts = missingRequired,
            artifactsWithoutSchema = artifactsWithoutSchema,
            standardVersionObservations = standardVersionObservations.sortedBy { it.artifact },
            standardVersionMismatches = versionMismatches,
            diagnosticCoverageStatus = diagnosticCoverage.status,
            issues = issues.sortedWith(compareBy<ArtifactIntegrityIssue> { it.severity }.thenBy { it.artifact }.thenBy { it.code })
        )
    }
}
