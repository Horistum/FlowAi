package org.flowlang.tests

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.flowlang.artifacts.ArtifactEvidenceAnalyzer
import org.flowlang.artifacts.ArtifactIntegrityAnalyzer
import org.flowlang.artifacts.ArtifactIntegrityVersionObservation
import org.flowlang.artifacts.FlowArtifactBundleAnalyzer
import org.flowlang.artifacts.StandardComplianceAnalyzer
import org.flowlang.artifacts.StandardContractIndexAnalyzer
import org.flowlang.artifacts.StandardReleaseProfile
import org.flowlang.standard.DiagnosticCoverageAnalyzer
import org.flowlang.standard.FlowStandardVersions
import org.flowlang.standard.ObservedDiagnosticCode

class FlowStandardClosureTests {
    @Test
    fun standardClosureReportsPassForReferenceBundle() {
        val bundle = FlowArtifactBundleAnalyzer().intentBundle(
            flowName = "build-test-deploy",
            target = "jenkins",
            strict = false,
            hasManifest = true,
            renderedArtifact = "Jenkinsfile"
        )
        val coverage = DiagnosticCoverageAnalyzer().analyze(
            listOf(ObservedDiagnosticCode("adapter-diagnostics.json", "ADAPTER_CONTRACT_READY", "adapterDiagnostics.issues", "info"))
        )
        val integrity = ArtifactIntegrityAnalyzer().analyze(
            bundle = bundle,
            presentArtifacts = bundle.pipeline.toSet(),
            standardVersionObservations = bundle.pipeline
                .filter { it.endsWith(".json") || it == "standard-version.txt" }
                .map { ArtifactIntegrityVersionObservation(it, FlowStandardVersions.FLOW_STANDARD_VERSION) },
            diagnosticCoverage = coverage
        )
        val index = StandardContractIndexAnalyzer().analyze(bundle)
        val profile = StandardReleaseProfile.report()
        val evidence = ArtifactEvidenceAnalyzer().analyze(bundle)
        val compliance = StandardComplianceAnalyzer().analyze(bundle, index, profile, evidence, integrity)

        assertTrue(index.requiredContracts.contains("standard-contract-index.json"))
        assertTrue(index.contracts.any { it.artifact == "standard-compliance-report.json" && it.introducedIn == "0.3.19" })
        assertTrue(profile.requiredArtifacts.contains("standard-compliance-report.json"))
        assertTrue(profile.requiredConformanceChecks.contains("v0.4.3.architecture-governance-guardrails"))
        assertTrue(evidence.missingEvidence.isEmpty())
        assertEquals("PASS", compliance.status)
        assertTrue(compliance.failedGates.isEmpty())
    }
}
