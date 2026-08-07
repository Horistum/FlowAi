package org.flowlang.tests

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.flowlang.roadmap.RoadmapStreamTransitionAuthority
import org.flowlang.roadmap.RoadmapTransitionPhase

class RoadmapStreamTransitionAr01EvidenceTests {
    @Test
    fun activeAr01AcceptsNoImplementationBoundaryYet() {
        val root = fixture(implementation = null, completion = null)
        val report = RoadmapStreamTransitionAuthority(root).analyze()
        assertEquals(RoadmapTransitionPhase.AR0_1_ACTIVE, report.phase)
        assertEquals("PASS", report.status, report.errors.joinToString(" | "))
    }

    @Test
    fun activeAr01AcceptsLaterImplementationBoundary() {
        val root = fixture(
            implementation = boundary(2807, 31196276487, 'd', 'e'),
            completion = null
        )
        val report = RoadmapStreamTransitionAuthority(root).analyze()
        assertEquals(RoadmapTransitionPhase.AR0_1_ACTIVE, report.phase)
        assertEquals("PASS", report.status, report.errors.joinToString(" | "))
    }

    @Test
    fun activeAr01RejectsImplementationBoundaryThatDoesNotFollowActivation() {
        val root = fixture(
            implementation = boundary(2797, 31196276486, 'd', 'e'),
            completion = null
        )
        val report = RoadmapStreamTransitionAuthority(root).analyze()
        assertEquals("FAIL", report.status)
        assertTrue(report.errors.any { "implementation evidence must be a later distinct" in it })
    }

    @Test
    fun activeAr01RejectsPrematureCompletionBoundary() {
        val root = fixture(
            implementation = boundary(2807, 31196276487, 'd', 'e'),
            completion = boundary(2808, 31196276488, 'f', '1')
        )
        val report = RoadmapStreamTransitionAuthority(root).analyze()
        assertEquals("FAIL", report.status)
        assertTrue(report.errors.any { "must not retain completion evidence" in it })
    }

    private fun fixture(implementation: String?, completion: String?): File {
        val root = createTempDirectory("flow-ar01-active-evidence").toFile()
        REQUIRED_FILES.forEach { path ->
            File(root, path).apply {
                parentFile.mkdirs()
                writeText(if (path.endsWith(".kt")) "package fixture\n" else "fixture\n")
            }
        }
        write(root, RoadmapStreamTransitionAuthority.ADAPTER_SEQUENCE, "package fixture\nobject AdapterRoadmapSequence\n")
        write(root, RoadmapStreamTransitionAuthority.ROADMAP_INDEX, """
            primaryRoadmapStream: architecture
            architectureDebtBacklog:
              - id: "AR0.1"
                status: active
            currentDecision:
              conformanceCorrectionState: complete
              activeConformanceCorrectionWorkPackage: ""
              closureItem: "0.9.7.10"
              closureItemStatus: completed
              completedAdapterItem: "A1.0"
              completedConformanceItem: "C0.4"
              nextItem: "AR0.1"
              nextItemName: "Authority Responsibility Consolidation"
              nextItemStream: architecture
        """)
        write(root, RoadmapStreamTransitionAuthority.ADAPTER_ROADMAP, """
            stream: adapters
            status: completed
            currentDecision:
              completedItem: "A1.0"
              nextItem: ""
            items:
              - version: "A1.0"
                status: completed
        """)
        write(root, RoadmapStreamTransitionAuthority.CONFORMANCE_ROADMAP, """
            stream: conformance
            status: completed
            currentDecision:
              completedItem: "C0.4"
              nextItem: ""
            items:
              - version: "C0.1"
                status: completed
              - version: "C0.1.1"
                status: completed
              - version: "C0.2"
                status: completed
              - version: "C0.3"
                status: completed
              - version: "C0.4"
                status: completed
        """)
        write(root, RoadmapStreamTransitionAuthority.ARCHITECTURE_ROADMAP, """
            stream: architecture
            status: active
            currentDecision:
              nextItem: "AR0.1"
              nextItemName: "Authority Responsibility Consolidation"
            items:
              - version: "AR0.1"
                status: next
        """)
        write(root, RoadmapStreamTransitionAuthority.RELEASE_STATE, """
            roadmapState:
              primaryStream: architecture
              closureItem: "0.9.7.10"
              closureItemStatus: completed
              completedAdapterItem: "A1.0"
              completedConformanceItem: "C0.4"
              nextItem: "AR0.1"
              nextItemName: "Authority Responsibility Consolidation"
        """)
        write(root, RoadmapStreamTransitionAuthority.CORRECTION_WORK_PACKAGE, completedEvidence(2700, '1', '2', 2701, '3', '4'))
        write(root, RoadmapStreamTransitionAuthority.A10_WORK_PACKAGE, completedEvidence(2710, '5', '6', 2711, '7', '8'))
        write(root, RoadmapStreamTransitionAuthority.C02_WORK_PACKAGE, completedEvidence(2720, '9', 'a', 2721, 'b', 'c'))
        write(root, RoadmapStreamTransitionAuthority.C03_WORK_PACKAGE, completedEvidence(2730, 'd', 'e', 2731, 'f', '0'))
        write(root, RoadmapStreamTransitionAuthority.C04_WORK_PACKAGE, """
            status: complete
            implementationEvidence:
              status: passed
              workflow: Flow CI
              runNumber: 2796
              runId: 31163987468
              exactHead: "${"2".repeat(40)}"
              mergeCandidate: "${"4".repeat(40)}"
            completionBoundary:
              status: passed
              workflow: Flow CI
              runNumber: 2798
              runId: 31166600475
              exactHead: "${"a".repeat(40)}"
              mergeCandidate: "${"b".repeat(40)}"
        """)
        val evidence = buildString {
            appendLine("version: \"AR0.1\"")
            appendLine("stream: architecture")
            appendLine("status: active")
            appendLine("activationEvidence:")
            appendLine("  status: passed")
            appendLine("  workflow: Flow CI")
            appendLine("  runNumber: 2798")
            appendLine("  runId: 31166600475")
            appendLine("  exactHead: \"${"a".repeat(40)}\"")
            appendLine("  mergeCandidate: \"${"b".repeat(40)}\"")
            appendEvidence("implementationEvidence", implementation)
            appendEvidence("completionBoundary", completion)
        }
        write(root, RoadmapStreamTransitionAuthority.AR01_WORK_PACKAGE, evidence)
        write(root, "standard/architecture/authority-responsibilities.yaml", """
            version: "1.0"
            purpose: "fixture"
            authorities: []
        """)
        write(root, RoadmapStreamTransitionAuthority.AR01_DOCUMENTATION, "# fixture\n")
        return root
    }

    private fun completedEvidence(
        implementationRun: Int,
        implementationHead: Char,
        implementationMerge: Char,
        completionRun: Int,
        completionHead: Char,
        completionMerge: Char
    ): String = """
        status: complete
        implementationEvidence:
          status: passed
          workflow: Flow CI
          runNumber: $implementationRun
          runId: ${40000000000L + implementationRun}
          exactHead: "${implementationHead.toString().repeat(40)}"
          mergeCandidate: "${implementationMerge.toString().repeat(40)}"
        completionBoundary:
          status: passed
          workflow: Flow CI
          runNumber: $completionRun
          runId: ${40000000000L + completionRun}
          exactHead: "${completionHead.toString().repeat(40)}"
          mergeCandidate: "${completionMerge.toString().repeat(40)}"
    """

    private fun boundary(run: Int, runId: Long, head: Char, merge: Char): String = """
        status: passed
        workflow: Flow CI
        runNumber: $run
        runId: $runId
        exactHead: "${head.toString().repeat(40)}"
        mergeCandidate: "${merge.toString().repeat(40)}"
    """.trimIndent()

    private fun StringBuilder.appendEvidence(name: String, evidence: String?) {
        if (evidence == null) {
            appendLine("$name: {}")
            return
        }
        appendLine("$name:")
        evidence.lineSequence().forEach { line -> appendLine("  $line") }
    }

    private fun write(root: File, path: String, content: String) {
        File(root, path).apply {
            parentFile.mkdirs()
            writeText(content.trimIndent() + "\n")
        }
    }

    companion object {
        private val REQUIRED_FILES = listOf(
            RoadmapStreamTransitionAuthority.ADAPTER_SEQUENCE,
            "src/main/kotlin/org/flowlang/roadmap/RoadmapStreamTransitionAuthority.kt",
            "src/main/kotlin/org/flowlang/conformance/RealWorldPolarityAuthority.kt",
            "src/test/kotlin/RoadmapStreamTransitionAuthorityTests.kt",
            "tests/RealWorldCorpusTests.kt",
            "docs/C0_1_BOUNDED_DOMAIN_CORPUS.md"
        )
    }
}
