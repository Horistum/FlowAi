package org.flowlang.tests

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.flowlang.roadmap.RoadmapStreamTransitionAuthority
import org.flowlang.roadmap.RoadmapTransitionPhase

class RoadmapStreamTransitionC10CompletionTests {
    @Test
    fun completedC10ClosesConformanceWithoutFallingBackToArchitectureComplete() {
        val root = terminalFixture()

        val report = RoadmapStreamTransitionAuthority(root).analyze()

        assertEquals(RoadmapTransitionPhase.C1_0_COMPLETE, report.phase, report.errors.joinToString(" | "))
        assertEquals("PASS", report.status, report.errors.joinToString(" | "))
    }

    @Test
    fun existingC10WithoutAValidStatusCannotFallBackToArchitectureComplete() {
        val root = terminalFixture()
        val c10 = File(root, RoadmapStreamTransitionAuthority.C10_WORK_PACKAGE)
        c10.writeText(c10.readText().replaceFirst("status: complete", "status: \"\""))

        val report = RoadmapStreamTransitionAuthority(root).analyze()

        assertEquals(RoadmapTransitionPhase.INVALID, report.phase)
        assertEquals("FAIL", report.status)
        assertTrue(report.errors.any { "Roadmap transition must be" in it })
    }

    @Test
    fun completedC10RejectsCompletionThatDoesNotFollowImplementation() {
        val root = terminalFixture(completionRun = 2812)

        val report = RoadmapStreamTransitionAuthority(root).analyze()

        assertEquals(RoadmapTransitionPhase.C1_0_COMPLETE, report.phase)
        assertEquals("FAIL", report.status)
        assertTrue(report.errors.any { "completion evidence must be a later distinct" in it })
    }

    @Test
    fun completedC10RejectsFabricatedSuccessor() {
        val root = terminalFixture()
        val roadmap = File(root, RoadmapStreamTransitionAuthority.ROADMAP_INDEX)
        roadmap.writeText(
            roadmap.readText()
                .replaceFirst("              nextItem: \"\"", "              nextItem: \"C1.1\"")
                .replaceFirst("              nextItemName: \"\"", "              nextItemName: \"Fabricated successor\"")
                .replaceFirst("              nextItemStream: \"\"", "              nextItemStream: conformance")
        )

        val report = RoadmapStreamTransitionAuthority(root).analyze()

        assertEquals(RoadmapTransitionPhase.C1_0_COMPLETE, report.phase)
        assertEquals("FAIL", report.status)
        assertTrue(report.errors.any { "no successor focus" in it })
    }

    @Test
    fun completedC10RejectsReleaseStateDrift() {
        val root = terminalFixture()
        val release = File(root, RoadmapStreamTransitionAuthority.RELEASE_STATE)
        release.writeText(
            release.readText().replace(
                "completedConformanceItem: \"C1.0\"",
                "completedConformanceItem: \"C0.4\""
            )
        )

        val report = RoadmapStreamTransitionAuthority(root).analyze()

        assertEquals(RoadmapTransitionPhase.C1_0_COMPLETE, report.phase)
        assertEquals("FAIL", report.status)
        assertTrue(report.errors.any { "Completed C1.0 release state" in it })
    }

    @Test
    fun completedC10RejectsArchitectureBacklogDrift() {
        val root = terminalFixture()
        val roadmap = File(root, RoadmapStreamTransitionAuthority.ROADMAP_INDEX)
        roadmap.writeText(roadmap.readText().replaceFirst("                status: completed", "                status: active"))

        val report = RoadmapStreamTransitionAuthority(root).analyze()

        assertEquals(RoadmapTransitionPhase.C1_0_COMPLETE, report.phase)
        assertEquals("FAIL", report.status)
        assertTrue(report.errors.any { "architecture debt backlog" in it })
    }

    private fun terminalFixture(completionRun: Int = 2814): File {
        val root = createTempDirectory("flow-c10-terminal").toFile()
        REQUIRED_FILES.forEach { path ->
            File(root, path).apply {
                parentFile.mkdirs()
                writeText(if (path.endsWith(".kt")) "package fixture\n" else "fixture\n")
            }
        }
        write(root, RoadmapStreamTransitionAuthority.ADAPTER_SEQUENCE, "package fixture\nobject AdapterRoadmapSequence\n")
        write(root, RoadmapStreamTransitionAuthority.ROADMAP_INDEX, """
            primaryRoadmapStream: conformance
            architectureDebtBacklog:
              - id: "AR0.1"
                status: completed
            currentDecision:
              conformanceCorrectionState: complete
              activeConformanceCorrectionWorkPackage: ""
              closureItem: "0.9.7.10"
              closureItemStatus: completed
              completedAdapterItem: "A1.0"
              completedConformanceItem: "C1.0"
              completedConformanceItemName: "Operational Domain Adequacy"
              completedArchitectureItem: "AR0.1"
              completedArchitectureItemName: "Authority Responsibility Consolidation"
              nextItem: ""
              nextItemName: ""
              nextItemStream: ""
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
              completedItem: "C1.0"
              completedItemName: "Operational Domain Adequacy"
              nextItem: ""
              nextItemName: ""
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
              - version: "C1.0"
                status: completed
        """)
        write(root, RoadmapStreamTransitionAuthority.ARCHITECTURE_ROADMAP, """
            stream: architecture
            status: completed
            currentDecision:
              completedItem: "AR0.1"
              completedItemName: "Authority Responsibility Consolidation"
              nextItem: ""
              nextItemName: ""
            items:
              - version: "AR0.1"
                status: completed
        """)
        write(root, RoadmapStreamTransitionAuthority.RELEASE_STATE, """
            roadmapState:
              primaryStream: conformance
              closureItem: "0.9.7.10"
              closureItemStatus: completed
              completedAdapterItem: "A1.0"
              completedConformanceItem: "C1.0"
              completedConformanceItemName: "Operational Domain Adequacy"
              completedArchitectureItem: "AR0.1"
              completedArchitectureItemName: "Authority Responsibility Consolidation"
              nextItem: ""
              nextItemName: ""
              nextItemStream: ""
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
        write(root, RoadmapStreamTransitionAuthority.AR01_WORK_PACKAGE, """
            version: "AR0.1"
            stream: architecture
            status: complete
            activationEvidence:
              status: passed
              workflow: Flow CI
              runNumber: 2798
              runId: 31166600475
              exactHead: "${"a".repeat(40)}"
              mergeCandidate: "${"b".repeat(40)}"
            implementationEvidence:
              status: passed
              workflow: Flow CI
              runNumber: 2807
              runId: 31196276487
              exactHead: "${"c".repeat(40)}"
              mergeCandidate: "${"d".repeat(40)}"
            completionBoundary:
              status: passed
              workflow: Flow CI
              runNumber: 2809
              runId: 31202921728
              exactHead: "${"e".repeat(40)}"
              mergeCandidate: "${"f".repeat(40)}"
        """)
        write(root, RoadmapStreamTransitionAuthority.C10_WORK_PACKAGE, """
            version: "C1.0"
            stream: conformance
            status: complete
            activationEvidence:
              status: passed
              workflow: Flow CI
              runNumber: 2809
              runId: 31202921728
              exactHead: "${"e".repeat(40)}"
              mergeCandidate: "${"f".repeat(40)}"
            implementationEvidence:
              status: passed
              workflow: Flow CI
              runNumber: 2812
              runId: 31238299719
              exactHead: "${"1".repeat(40)}"
              mergeCandidate: "${"2".repeat(40)}"
            completionBoundary:
              status: passed
              workflow: Flow CI
              runNumber: $completionRun
              runId: ${31250000000L + completionRun}
              exactHead: "${if (completionRun == 2812) "1".repeat(40) else "3".repeat(40)}"
              mergeCandidate: "${if (completionRun == 2812) "2".repeat(40) else "4".repeat(40)}"
        """)
        write(root, "standard/architecture/authority-responsibilities.yaml", """
            version: "1.0"
            purpose: "fixture"
            authorities: []
        """)
        write(root, RoadmapStreamTransitionAuthority.AR01_DOCUMENTATION, "# fixture\n")
        write(root, RoadmapStreamTransitionAuthority.C10_DOCUMENTATION, "# fixture\n")
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
