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
        workPackage.appendText(
            """
implementationEvidence:
  status: passed
  workflow: Flow CI
  runNumber: 1
  runId: 1
  exactHead: 1111111111111111111111111111111111111111
  mergeCandidate: 2222222222222222222222222222222222222222
""".trimIndent()
        )

        val report = BoundedDomainCorpusRoadmapLifecycleAuthority(tempRoot).analyze()

        assertEquals("IMPLEMENTING", report.phase)
        assertEquals("FAIL", report.status)
        assertTrue(report.errors.any { "must not contain authored implementation evidence" in it })
    }

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
            "conformance/corpus/real-world/accepted-scenarios.yaml",
            "conformance/corpus/real-world/cases/A04-data-transformation-etl/case.yaml",
            "conformance/corpus/real-world/cases/N05-runtime-generated-infrastructure/case.yaml",
            "schemas/real-world-case.schema.json",
            "src/main/kotlin/org/flowlang/conformance/RealWorldCorpusContracts.kt",
            "src/main/kotlin/org/flowlang/conformance/RealWorldCorpusRunner.kt",
            "src/main/kotlin/org/flowlang/conformance/RealWorldCorpusConformanceChecks.kt",
            "tests/RealWorldCorpusTests.kt",
            "docs/C0_1_BOUNDED_DOMAIN_CORPUS.md"
        )
    }
}
