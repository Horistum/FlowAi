package org.flowlang.tests

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.flowlang.conformance.BoundedDomainCorpusRoadmapLifecycleAuthority

class BoundedDomainCorpusRoadmapLifecycleAuthorityTests {
    @Test
    fun repositoryDeclaresOneValidImplementingC01Boundary() {
        val report = BoundedDomainCorpusRoadmapLifecycleAuthority(File(".")).analyze()

        assertEquals("IMPLEMENTING", report.phase)
        assertEquals("PASS", report.status, report.errors.joinToString(" | "))
    }

    @Test
    fun activeWorkPackageRejectsAuthoredImplementationEvidence() {
        val tempRoot = createTempDirectory("flow-c01-lifecycle").toFile()
        copyBoundary(tempRoot)
        val workPackage = File(tempRoot, BoundedDomainCorpusRoadmapLifecycleAuthority.WORK_PACKAGE)
        workPackage.appendText("\n${passingEvidence()}\n")

        val report = BoundedDomainCorpusRoadmapLifecycleAuthority(tempRoot).analyze()

        assertEquals("IMPLEMENTING", report.phase)
        assertEquals("FAIL", report.status)
        assertTrue(report.errors.any { "must not contain authored implementation evidence" in it })
    }

    @Test
    fun completedBoundarySelectsC02WithStrictDistinctCiEvidence() {
        val tempRoot = createTempDirectory("flow-c01-completed").toFile()
        copyBoundary(tempRoot)
        completeBoundary(tempRoot)

        val report = BoundedDomainCorpusRoadmapLifecycleAuthority(tempRoot).analyze()

        assertEquals("COMPLETED", report.phase)
        assertEquals("PASS", report.status, report.errors.joinToString(" | "))
    }

    @Test
    fun completedBoundaryRejectsUnknownEvidenceFields() {
        val tempRoot = createTempDirectory("flow-c01-evidence-fields").toFile()
        copyBoundary(tempRoot)
        completeBoundary(tempRoot)
        val workPackage = File(tempRoot, BoundedDomainCorpusRoadmapLifecycleAuthority.WORK_PACKAGE)
        workPackage.appendText("  fabricatedApproval: true\n")

        val report = BoundedDomainCorpusRoadmapLifecycleAuthority(tempRoot).analyze()

        assertEquals("COMPLETED", report.phase)
        assertEquals("FAIL", report.status)
        assertTrue(report.errors.any { "strict passing exact-head and merge-candidate evidence" in it })
    }

    @Test
    fun completedBoundaryRejectsSameExactAndMergeCandidateHead() {
        val tempRoot = createTempDirectory("flow-c01-evidence-heads").toFile()
        copyBoundary(tempRoot)
        completeBoundary(tempRoot)
        val workPackage = File(tempRoot, BoundedDomainCorpusRoadmapLifecycleAuthority.WORK_PACKAGE)
        workPackage.writeText(
            workPackage.readText().replace(
                "mergeCandidate: 2222222222222222222222222222222222222222",
                "mergeCandidate: 1111111111111111111111111111111111111111"
            )
        )

        val report = BoundedDomainCorpusRoadmapLifecycleAuthority(tempRoot).analyze()

        assertEquals("COMPLETED", report.phase)
        assertEquals("FAIL", report.status)
        assertTrue(report.errors.any { "strict passing exact-head and merge-candidate evidence" in it })
    }

    private fun completeBoundary(root: File) {
        val workPackage = File(root, BoundedDomainCorpusRoadmapLifecycleAuthority.WORK_PACKAGE)
        workPackage.writeText(workPackage.readText().replace("status: active", "status: complete"))
        workPackage.appendText("\n${passingEvidence()}\n")

        val conformanceRoadmap = File(root, BoundedDomainCorpusRoadmapLifecycleAuthority.CONFORMANCE_ROADMAP)
        conformanceRoadmap.writeText(
            conformanceRoadmap.readText()
                .replace("  completedItem: \"\"", "  completedItem: \"C0.1\"")
                .replace("  completedItemName: \"\"", "  completedItemName: \"Bounded Domain Corpus\"")
                .replace("  nextItem: \"C0.1\"", "  nextItem: \"C0.2\"")
                .replace("  nextItemName: \"Bounded Domain Corpus\"", "  nextItemName: \"Abstract Topology Matrix\"")
                .replaceFirst("    status: next", "    status: completed")
                .replaceFirst("    status: planned", "    status: next")
        )

        val roadmap = File(root, BoundedDomainCorpusRoadmapLifecycleAuthority.ROADMAP_INDEX)
        roadmap.writeText(
            roadmap.readText()
                .replace("  nextItem: \"C0.1\"", "  nextItem: \"C0.2\"")
                .replace("  nextItemName: \"Bounded Domain Corpus\"", "  nextItemName: \"Abstract Topology Matrix\"")
        )

        val releaseState = File(root, BoundedDomainCorpusRoadmapLifecycleAuthority.RELEASE_STATE)
        releaseState.writeText(
            releaseState.readText()
                .replace("  nextItem: \"C0.1\"", "  nextItem: \"C0.2\"")
                .replace("  nextItemName: \"Bounded Domain Corpus\"", "  nextItemName: \"Abstract Topology Matrix\"")
        )
    }

    private fun passingEvidence(): String = """
implementationEvidence:
  status: passed
  workflow: Flow CI
  runNumber: 2701
  runId: 33000000001
  exactHead: 1111111111111111111111111111111111111111
  mergeCandidate: 2222222222222222222222222222222222222222
""".trimIndent()

    private fun copyBoundary(targetRoot: File) {
        REQUIRED_PATHS.forEach { path ->
            val source = File(".", path)
            require(source.isFile) { "Test fixture source is missing: ${source.path}" }
            val target = File(targetRoot, path)
            target.parentFile.mkdirs()
            source.copyTo(target, overwrite = true)
        }
    }

    companion object {
        private val REQUIRED_PATHS = listOf(
            BoundedDomainCorpusRoadmapLifecycleAuthority.WORK_PACKAGE,
            BoundedDomainCorpusRoadmapLifecycleAuthority.CONFORMANCE_ROADMAP,
            BoundedDomainCorpusRoadmapLifecycleAuthority.ROADMAP_INDEX,
            BoundedDomainCorpusRoadmapLifecycleAuthority.RELEASE_STATE,
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
            "src/main/kotlin/org/flowlang/conformance/RealWorldCorpusConformanceChecks.kt",
            "tests/RealWorldCorpusTests.kt",
            "docs/C0_1_BOUNDED_DOMAIN_CORPUS.md"
        )
    }
}
