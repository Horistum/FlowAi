package org.flowlang.artifacts

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ReleaseArtifactProvenanceTests {
    private val metadata = "release-metadata-honesty-report.json"
    private val inputs = listOf("build.gradle.kts", ".flow-agent/release-state.yaml", ".flow-agent/roadmap.yaml",
        "REPORT.md", "CHANGELOG-v0.9.7.9.md")

    @Test fun releaseMetadataHasItsActualProducerAndHistoricalIntroduction() {
        assertEquals(ArtifactContractDefinition(metadata, "flow.release.metadata-honesty", "0.9.7.9.7"),
            ArtifactContractAuthority.definitionFor(metadata, FlowArtifactRole.REPORT))
    }

    @Test fun declaredReleaseInputsAreExactProvenanceIdentifiersWithoutVerifierClasses() {
        val report = ArtifactEvidenceAnalyzer().analyze(bundle(metadata, inputs))
        assertTrue(report.missingEvidence.isEmpty())
        assertEquals(inputs, report.evidence.single().derivedFrom)
        assertEquals("flow.release.metadata-honesty", report.evidence.single().producer)
        assertFailsWith<ClassNotFoundException> { Class.forName("org.flowlang.release.ReleaseMetadataHonestyAuthority") }
    }

    @Test fun conformanceManifestCanCiteItsRealSourceOwnerWithoutImportingIt() {
        assertTrue(ArtifactContractAuthority.missingEvidence(bundle("conformance-manifest.json",
            listOf("conformance/", "src/main/kotlin/org/flowlang/conformance/ConformanceRunner.kt"))).isEmpty())
    }

    @Test fun releaseAnchorsCannotAuthorizeUnrelatedIntentEvidence() {
        for (source in inputs) {
            assertEquals(listOf("normalized-intent.json"), ArtifactContractAuthority.missingEvidence(
                bundle("normalized-intent.json", listOf(source))))
        }
    }

    @Test fun unknownOrMissingReleaseProvenanceStillFailsClosed() {
        for (sources in listOf(emptyList(), inputs + "invented.json", listOf("../REPORT.md"), listOf(".flow-agent/anything.yaml"))) {
            assertEquals(listOf(metadata), ArtifactContractAuthority.missingEvidence(bundle(metadata, sources)))
        }
    }

    @Test fun registrationDoesNotIntroduceAGenericProducerFallback() {
        assertFailsWith<IllegalStateException> {
            ArtifactContractAuthority.definitionFor("unregistered-release-report.json", FlowArtifactRole.REPORT)
        }
    }

    private fun bundle(name: String, sources: List<String>) = FlowArtifactBundleReport(
        flowName = "provenance-test", target = "", strict = false,
        artifacts = listOf(FlowArtifactEntry(name, FlowArtifactRole.REPORT, required = true,
            derived = true, pipelineIndex = 1, derivedFrom = sources)),
        requiredArtifacts = listOf(name), optionalArtifacts = emptyList(), pipeline = listOf(name)
    )
}
