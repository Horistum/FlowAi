package org.flowlang.tests

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.flowlang.artifacts.FlowArtifactBundleAnalyzer
import org.flowlang.artifacts.FlowArtifactRole

class FlowArtifactBundleTests {
    @Test
    fun intentBundleDescribesPublicExportPipeline() {
        val bundle = FlowArtifactBundleAnalyzer().intentBundle(
            flowName = "build-test-deploy",
            target = "jenkins",
            strict = false,
            hasManifest = true,
            renderedArtifact = "Jenkinsfile"
        )

        assertEquals("build-test-deploy", bundle.flowName)
        assertEquals("jenkins", bundle.target)
        assertEquals("standard-version.txt", bundle.pipeline.first())
        assertEquals("flow-artifact-bundle.json", bundle.pipeline.last())
        assertTrue(bundle.requiredArtifacts.contains("standard-diagnostic-catalog.json"))
        assertTrue(bundle.requiredArtifacts.contains("execution-plan.json"))
        assertTrue(bundle.requiredArtifacts.contains("target-decision-trace-report.json"))
        assertTrue(bundle.requiredArtifacts.contains("target-adapter-contract.json"))
        assertTrue(bundle.requiredArtifacts.contains("adapter-diagnostics.json"))
        assertTrue(bundle.requiredArtifacts.contains("diagnostic-coverage-report.json"))
        assertTrue(bundle.requiredArtifacts.contains("artifact-integrity-report.json"))
        assertTrue(bundle.requiredArtifacts.contains("standard-contract-index.json"))
        assertTrue(bundle.requiredArtifacts.contains("standard-release-profile.json"))
        assertTrue(bundle.requiredArtifacts.contains("artifact-evidence-report.json"))
        assertTrue(bundle.requiredArtifacts.contains("standard-compliance-report.json"))
        assertTrue(bundle.requiredArtifacts.contains("standard-freeze-report.json"))
        assertTrue(bundle.requiredArtifacts.contains("compatibility-policy.json"))
        assertTrue(bundle.requiredArtifacts.contains("reference-corpus-index.json"))
        assertTrue(bundle.requiredArtifacts.contains("negative-conformance-corpus.json"))
        assertTrue(bundle.requiredArtifacts.contains("target-conformance-profile.json"))
        assertTrue(bundle.requiredArtifacts.contains("standard-index.json"))
        assertTrue(bundle.requiredArtifacts.contains("conformance-suite.json"))
        assertTrue(bundle.requiredArtifacts.contains("flow-standard-draft.json"))
        assertTrue(bundle.requiredArtifacts.contains("flow-artifact-bundle.json"))
        assertTrue(bundle.optionalArtifacts.contains("target-manifest.json"))
        assertTrue(bundle.optionalArtifacts.contains("Jenkinsfile"))
        assertEquals((1..bundle.artifacts.size).toList(), bundle.artifacts.map { it.pipelineIndex })
        assertEquals(FlowArtifactRole.METADATA, bundle.artifacts.last { it.name == "flow-artifact-bundle.json" }.role)
    }

    @Test
    fun normalizationBundleWithoutLoweringOnlyListsNormalizationArtifacts() {
        val bundle = FlowArtifactBundleAnalyzer().normalizationBundle(
            flowName = "deploy-request",
            target = "",
            strict = false,
            lowered = false,
            hasManifest = false,
            renderedArtifact = null
        )

        assertTrue(bundle.requiredArtifacts.contains("ai-normalization-report.json"))
        assertTrue(bundle.requiredArtifacts.contains("normalized-intent.json"))
        assertTrue(bundle.requiredArtifacts.contains("intent-decision-report.json"))
        assertTrue(!bundle.requiredArtifacts.contains("execution-plan.json"))
        assertTrue(!bundle.requiredArtifacts.contains("target-selection-report.json"))
        assertEquals("flow-artifact-bundle.json", bundle.pipeline.last())
    }
}
