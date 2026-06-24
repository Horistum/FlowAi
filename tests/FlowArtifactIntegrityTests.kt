package org.flowlang.tests

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.flowlang.artifacts.ArtifactIntegrityAnalyzer
import org.flowlang.artifacts.ArtifactIntegrityVersionObservation
import org.flowlang.artifacts.FlowArtifactBundleAnalyzer
import org.flowlang.standard.DiagnosticCoverageAnalyzer
import org.flowlang.standard.FlowStandardVersions
import org.flowlang.standard.ObservedDiagnosticCode

class FlowArtifactIntegrityTests {
    @Test
    fun artifactIntegrityPassesForCompleteReferenceArtifactSet() {
        val bundle = FlowArtifactBundleAnalyzer().intentBundle(
            flowName = "build-test-deploy",
            target = "jenkins",
            strict = false,
            hasManifest = true,
            renderedArtifact = "Jenkinsfile"
        )
        val coverage = DiagnosticCoverageAnalyzer().analyze(
            listOf(
                ObservedDiagnosticCode("adapter-diagnostics.json", "ADAPTER_CONTRACT_READY", "adapterDiagnostics.issues", "info")
            )
        )
        val report = ArtifactIntegrityAnalyzer().analyze(
            bundle = bundle,
            presentArtifacts = bundle.pipeline.toSet(),
            standardVersionObservations = bundle.pipeline
                .filter { it.endsWith(".json") || it == "standard-version.txt" }
                .map { ArtifactIntegrityVersionObservation(it, FlowStandardVersions.FLOW_STANDARD_VERSION) },
            diagnosticCoverage = coverage
        )

        assertEquals("PASS", report.status)
        assertTrue(report.requiredArtifactsExpected.contains("artifact-integrity-report.json"))
        assertTrue(report.requiredArtifactsExpected.contains("diagnostic-coverage-report.json"))
        assertTrue(report.missingRequiredArtifacts.isEmpty())
        assertTrue(report.standardVersionMismatches.isEmpty())
        assertEquals("PASS", report.diagnosticCoverageStatus)
    }

    @Test
    fun artifactIntegrityFailsForMissingRequiredArtifact() {
        val bundle = FlowArtifactBundleAnalyzer().intentBundle(
            flowName = "build-test-deploy",
            target = "jenkins",
            strict = false,
            hasManifest = true,
            renderedArtifact = "Jenkinsfile"
        )
        val coverage = DiagnosticCoverageAnalyzer().analyze(emptyList())
        val report = ArtifactIntegrityAnalyzer().analyze(
            bundle = bundle,
            presentArtifacts = bundle.pipeline.toSet() - "execution-plan.json",
            standardVersionObservations = emptyList(),
            diagnosticCoverage = coverage
        )

        assertEquals("FAIL", report.status)
        assertTrue(report.missingRequiredArtifacts.contains("execution-plan.json"))
        assertTrue(report.issues.any { it.code == "ARTIFACT_REQUIRED_MISSING" })
    }
}
