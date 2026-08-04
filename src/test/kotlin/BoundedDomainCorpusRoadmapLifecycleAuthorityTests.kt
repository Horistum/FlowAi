package org.flowlang.tests

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.flowlang.conformance.BoundedDomainCorpusRoadmapLifecycleAuthority

class BoundedDomainCorpusRoadmapLifecycleAuthorityTests {
    @Test
    fun repositoryDeclaresAValidCompletedLocalC01Boundary() {
        val report = BoundedDomainCorpusRoadmapLifecycleAuthority(File(".")).analyze()
        assertEquals("COMPLETED", report.phase)
        assertEquals("PASS", report.status, report.errors.joinToString(" | "))
    }

    @Test
    fun completedBoundaryRequiresDistinctImplementationAndCompletionEvidence() {
        val root = createBoundary(sameBoundary = false)
        val report = BoundedDomainCorpusRoadmapLifecycleAuthority(root).analyze()
        assertEquals("PASS", report.status, report.errors.joinToString(" | "))
    }

    @Test
    fun completedBoundaryRejectsReusedEvidence() {
        val root = createBoundary(sameBoundary = true)
        val report = BoundedDomainCorpusRoadmapLifecycleAuthority(root).analyze()
        assertEquals("FAIL", report.status)
        assertTrue(report.errors.any { "must be distinct" in it })
    }

    @Test
    fun completedBoundaryRejectsPrematureC02Selection() {
        val root = createBoundary(sameBoundary = false)
        val roadmap = File(root, BoundedDomainCorpusRoadmapLifecycleAuthority.CONFORMANCE_ROADMAP)
        roadmap.writeText(roadmap.readText().replace("status: planned", "status: next"))
        val report = BoundedDomainCorpusRoadmapLifecycleAuthority(root).analyze()
        assertEquals("FAIL", report.status)
        assertTrue(report.errors.any { "C0.2 must remain planned" in it })
    }

    private fun createBoundary(sameBoundary: Boolean): File {
        val root = createTempDirectory("flow-c01-local").toFile()
        REQUIRED_FILES.forEach { path ->
            val source = File(".", path)
            val target = File(root, path)
            target.parentFile.mkdirs()
            if (source.isFile) source.copyTo(target, overwrite = true) else target.writeText("fixture\n")
        }
        val implementationHead = "1111111111111111111111111111111111111111"
        val completionHead = if (sameBoundary) implementationHead else "3333333333333333333333333333333333333333"
        val implementationRun = 33000000001L
        val completionRun = if (sameBoundary) implementationRun else 33000000002L
        File(root, BoundedDomainCorpusRoadmapLifecycleAuthority.WORK_PACKAGE).writeText(
            """
            version: "C0.1"
            status: complete
            implementationEvidence:
              status: passed
              workflow: Flow CI
              runNumber: 2701
              runId: $implementationRun
              exactHead: "$implementationHead"
              mergeCandidate: "2222222222222222222222222222222222222222"
            completionBoundary:
              status: passed
              workflow: Flow CI
              runNumber: 2702
              runId: $completionRun
              exactHead: "$completionHead"
              mergeCandidate: "4444444444444444444444444444444444444444"
            """.trimIndent() + "\n"
        )
        File(root, BoundedDomainCorpusRoadmapLifecycleAuthority.CONFORMANCE_ROADMAP).writeText(
            """
            stream: conformance
            status: active
            currentDecision:
              completedItem: "C0.1"
              nextItem: ""
              nextItemName: ""
            items:
              - version: "C0.1"
                status: completed
              - version: "C0.2"
                status: planned
            """.trimIndent() + "\n"
        )
        return root
    }

    companion object {
        private val REQUIRED_FILES = listOf(
            "conformance/check-inventory.yaml",
            "conformance/corpus/real-world/manifest.yaml",
            "conformance/corpus/real-world/sources.yaml",
            "conformance/corpus/real-world/scenarios.yaml",
            "conformance/corpus/real-world/accepted-scenarios.yaml",
            "conformance/corpus/real-world/cases/A04-data-transformation-etl/case.yaml",
            "conformance/corpus/real-world/cases/P13-infrastructure-provision/case.yaml",
            "conformance/corpus/real-world/cases/N05-runtime-generated-pipeline/case.yaml",
            "schemas/real-world-case.schema.json",
            "src/main/kotlin/org/flowlang/conformance/RealWorldCorpusContracts.kt",
            "src/main/kotlin/org/flowlang/conformance/RealWorldCorpusRunner.kt",
            "src/main/kotlin/org/flowlang/conformance/RealWorldPolarityAuthority.kt",
            "src/main/kotlin/org/flowlang/conformance/RealWorldCorpusConformanceChecks.kt",
            "tests/RealWorldCorpusTests.kt",
            "docs/C0_1_BOUNDED_DOMAIN_CORPUS.md"
        )
    }
}
