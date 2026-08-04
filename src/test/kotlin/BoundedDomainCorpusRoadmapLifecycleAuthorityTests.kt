package org.flowlang.tests

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.flowlang.conformance.BoundedDomainCorpusRoadmapLifecycleAuthority

class BoundedDomainCorpusRoadmapLifecycleAuthorityTests {
    @Test
    fun repositoryDeclaresOneValidCompletedC01Boundary() {
        val report = BoundedDomainCorpusRoadmapLifecycleAuthority(File(".")).analyze()

        assertEquals("COMPLETED", report.phase)
        assertEquals("PASS", report.status, report.errors.joinToString(" | "))
    }

    @Test
    fun explicitImplementingBoundaryIsValidWithoutAuthoredEvidence() {
        val tempRoot = createBoundary(BoundaryPhase.IMPLEMENTING)

        val report = BoundedDomainCorpusRoadmapLifecycleAuthority(tempRoot).analyze()

        assertEquals("IMPLEMENTING", report.phase)
        assertEquals("PASS", report.status, report.errors.joinToString(" | "))
    }

    @Test
    fun activeWorkPackageRejectsAuthoredImplementationEvidence() {
        val tempRoot = createBoundary(
            phase = BoundaryPhase.IMPLEMENTING,
            evidence = passingEvidence()
        )

        val report = BoundedDomainCorpusRoadmapLifecycleAuthority(tempRoot).analyze()

        assertEquals("IMPLEMENTING", report.phase)
        assertEquals("FAIL", report.status)
        assertTrue(report.errors.any { "must not contain authored implementation evidence" in it })
    }

    @Test
    fun completedBoundarySelectsC02WithStrictDistinctCiEvidence() {
        val tempRoot = createBoundary(BoundaryPhase.COMPLETED)

        val report = BoundedDomainCorpusRoadmapLifecycleAuthority(tempRoot).analyze()

        assertEquals("COMPLETED", report.phase)
        assertEquals("PASS", report.status, report.errors.joinToString(" | "))
    }

    @Test
    fun completedBoundaryRejectsUnknownEvidenceFields() {
        val tempRoot = createBoundary(
            phase = BoundaryPhase.COMPLETED,
            evidence = passingEvidence(extraField = "fabricatedApproval: true")
        )

        val report = BoundedDomainCorpusRoadmapLifecycleAuthority(tempRoot).analyze()

        assertEquals("COMPLETED", report.phase)
        assertEquals("FAIL", report.status)
        assertTrue(report.errors.any { "strict passing exact-head and merge-candidate evidence" in it })
    }

    @Test
    fun completedBoundaryRejectsSameExactAndMergeCandidateHead() {
        val tempRoot = createBoundary(
            phase = BoundaryPhase.COMPLETED,
            evidence = passingEvidence(mergeCandidate = EXACT_HEAD)
        )

        val report = BoundedDomainCorpusRoadmapLifecycleAuthority(tempRoot).analyze()

        assertEquals("COMPLETED", report.phase)
        assertEquals("FAIL", report.status)
        assertTrue(report.errors.any { "strict passing exact-head and merge-candidate evidence" in it })
    }

    private fun createBoundary(
        phase: BoundaryPhase,
        evidence: String? = if (phase == BoundaryPhase.COMPLETED) passingEvidence() else null
    ): File {
        val root = createTempDirectory("flow-c01-${phase.name.lowercase()}").toFile()
        copyRequiredEvidence(root)
        writeLifecycleDocuments(root, phase, evidence)
        return root
    }

    private fun copyRequiredEvidence(targetRoot: File) {
        REQUIRED_EVIDENCE_PATHS.forEach { path ->
            val source = File(".", path)
            require(source.isFile) { "Test fixture source is missing: ${source.path}" }
            val target = File(targetRoot, path)
            target.parentFile.mkdirs()
            source.copyTo(target, overwrite = true)
        }
    }

    private fun writeLifecycleDocuments(root: File, phase: BoundaryPhase, evidence: String?) {
        val completed = phase == BoundaryPhase.COMPLETED
        writeFile(
            root,
            BoundedDomainCorpusRoadmapLifecycleAuthority.WORK_PACKAGE,
            buildString {
                appendLine("version: \"C0.1\"")
                appendLine("name: \"Bounded Domain Corpus\"")
                appendLine("status: ${if (completed) "complete" else "active"}")
                evidence?.let {
                    appendLine()
                    appendLine(it)
                }
            }
        )
        writeFile(
            root,
            BoundedDomainCorpusRoadmapLifecycleAuthority.CONFORMANCE_ROADMAP,
            """
            stream: conformance
            status: active
            items:
              - version: "C0.1"
                name: "Bounded Domain Corpus"
                status: ${if (completed) "completed" else "next"}
              - version: "C0.2"
                name: "Abstract Topology Matrix"
                status: ${if (completed) "next" else "planned"}
            currentDecision:
              completedItem: "${if (completed) "C0.1" else ""}"
              completedItemName: "${if (completed) "Bounded Domain Corpus" else ""}"
              nextItem: "${if (completed) "C0.2" else "C0.1"}"
              nextItemName: "${if (completed) "Abstract Topology Matrix" else "Bounded Domain Corpus"}"
            """.trimIndent() + "\n"
        )
        writeFile(
            root,
            BoundedDomainCorpusRoadmapLifecycleAuthority.ROADMAP_INDEX,
            """
            primaryRoadmapStream: conformance
            currentDecision:
              nextItem: "${if (completed) "C0.2" else "C0.1"}"
              nextItemName: "${if (completed) "Abstract Topology Matrix" else "Bounded Domain Corpus"}"
              nextItemStream: conformance
              completedAdapterItem: "A0.7"
            """.trimIndent() + "\n"
        )
        writeFile(
            root,
            BoundedDomainCorpusRoadmapLifecycleAuthority.RELEASE_STATE,
            """
            roadmapState:
              primaryStream: conformance
              nextItem: "${if (completed) "C0.2" else "C0.1"}"
              nextItemName: "${if (completed) "Abstract Topology Matrix" else "Bounded Domain Corpus"}"
              completedAdapterItem: "A0.7"
            """.trimIndent() + "\n"
        )
    }

    private fun writeFile(root: File, path: String, content: String) {
        File(root, path).apply {
            parentFile.mkdirs()
            writeText(content)
        }
    }

    private fun passingEvidence(
        mergeCandidate: String = MERGE_CANDIDATE,
        extraField: String? = null
    ): String = buildString {
        appendLine("implementationEvidence:")
        appendLine("  status: passed")
        appendLine("  workflow: Flow CI")
        appendLine("  runNumber: 2701")
        appendLine("  runId: 33000000001")
        appendLine("  exactHead: \"$EXACT_HEAD\"")
        appendLine("  mergeCandidate: \"$mergeCandidate\"")
        extraField?.let { appendLine("  $it") }
    }.trimEnd()

    private enum class BoundaryPhase {
        IMPLEMENTING,
        COMPLETED
    }

    companion object {
        private const val EXACT_HEAD = "1111111111111111111111111111111111111111"
        private const val MERGE_CANDIDATE = "2222222222222222222222222222222222222222"

        private val REQUIRED_EVIDENCE_PATHS = listOf(
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
