package org.flowlang.tests

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.flowlang.conformance.ConformanceCheck
import org.flowlang.conformance.ConformanceManifestBuilder
import org.flowlang.conformance.ConformanceSummary
import java.io.File

class FlowConformanceManifestTests {
    @Test
    fun manifestSummarizesChecksVectorsSchemasAndArtifacts() {
        val summary = ConformanceSummary(listOf(
            ConformanceCheck("intent.valid.build-test-deploy", true),
            ConformanceCheck("target.strict.tekton-approval-unsupported", true),
            ConformanceCheck("schemas.public-outputs", true),
            ConformanceCheck("v0.3.10.public-artifact-bundle", true)
        ))

        val manifest = ConformanceManifestBuilder(File(".")).build(summary)

        assertEquals("PASS", manifest.status)
        assertEquals(4, manifest.totalChecks)
        assertEquals(4, manifest.passed)
        assertEquals(0, manifest.failed)
        assertTrue(manifest.areas.any { it.area == "intent" && it.passed == 1 })
        assertTrue(manifest.areas.any { it.area == "target" && it.passed == 1 })
        assertTrue(manifest.requiredChecks.contains("schemas.public-outputs"))
        assertTrue(manifest.vectors.any { it.path == "conformance/artifacts/public-artifact-bundle.conformance.yaml" })
        assertTrue(manifest.publicSchemas.any { it.artifact == "target-adapter-contract.json" && it.schema == "schemas/target-adapter-contract.schema.json" })
        assertTrue(manifest.publicSchemas.any { it.artifact == "conformance-manifest.json" && it.schema == "schemas/conformance-manifest.schema.json" })
        assertTrue(manifest.requiredArtifacts.contains("target-adapter-contract.json"))
        assertTrue(manifest.requiredArtifacts.contains("conformance-manifest.json"))
    }

    @Test
    fun failedChecksMakeManifestFail() {
        val summary = ConformanceSummary(listOf(
            ConformanceCheck("intent.valid.build-test-deploy", true),
            ConformanceCheck("target.strict.tekton-approval-unsupported", false, "expected failure")
        ))

        val manifest = ConformanceManifestBuilder(File(".")).build(summary)

        assertEquals("FAIL", manifest.status)
        assertEquals(listOf("target.strict.tekton-approval-unsupported"), manifest.failedChecks)
        assertTrue(manifest.areas.any { it.area == "target" && it.failed == 1 })
    }
}
