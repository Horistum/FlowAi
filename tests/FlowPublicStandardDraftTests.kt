package org.flowlang.tests

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.flowlang.artifacts.ArtifactEvidenceAnalyzer
import org.flowlang.artifacts.ArtifactIntegrityAnalyzer
import org.flowlang.artifacts.ArtifactIntegrityVersionObservation
import org.flowlang.artifacts.FlowArtifactBundleAnalyzer
import org.flowlang.artifacts.PublicStandardDraft
import org.flowlang.artifacts.StandardComplianceAnalyzer
import org.flowlang.artifacts.StandardContractIndexAnalyzer
import org.flowlang.artifacts.StandardReleaseProfile
import org.flowlang.standard.DiagnosticCoverageAnalyzer
import org.flowlang.standard.FlowStandardVersions
import org.flowlang.standard.ObservedDiagnosticCode

class FlowPublicStandardDraftTests {
    @Test
    fun publicStandardDraftAggregatesStandardSurfaceWithoutRuntimeExpansion() {
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
        val contractIndex = StandardContractIndexAnalyzer().analyze(bundle)
        val releaseProfile = StandardReleaseProfile.report()
        val evidence = ArtifactEvidenceAnalyzer().analyze(bundle)
        val compliance = StandardComplianceAnalyzer().analyze(bundle, contractIndex, releaseProfile, evidence, integrity)
        val freeze = PublicStandardDraft.freeze(contractIndex)
        val referenceCorpus = PublicStandardDraft.referenceCorpus()
        val negativeCorpus = PublicStandardDraft.negativeCorpus()
        val targetConformance = PublicStandardDraft.targetConformanceProfile()
        val draft = PublicStandardDraft.draft(bundle, compliance)

        assertEquals("PASS", freeze.status)
        assertEquals("0.4.0", releaseProfile.minimumStandardVersion)
        assertTrue(referenceCorpus.examples.size >= 8)
        assertTrue(negativeCorpus.cases.any { it.expectedDiagnostic == "ADAPTER_MUST_NOT_READ_INTENT" })
        assertTrue(negativeCorpus.cases.any { it.expectedDiagnostic == "ARCHITECTURE_RUNTIME_PACKAGE_FORBIDDEN" })
        assertTrue(targetConformance.forbiddenInputs.contains("normalized-intent.json"))
        assertTrue(draft.purpose.contains("Human/AI intent"))
        assertTrue(draft.publicArtifacts.contains("flow-standard-draft.json"))
        assertTrue(draft.requiredProfiles.contains("architecture-governance"))
    }
}
