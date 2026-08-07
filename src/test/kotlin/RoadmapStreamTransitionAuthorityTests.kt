package org.flowlang.tests

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.flowlang.roadmap.RoadmapStreamTransitionAuthority
import org.flowlang.roadmap.RoadmapTransitionPhase

class RoadmapStreamTransitionAuthorityTests {
    @Test
    fun repositoryActivatesAr01AfterCompletedC04() {
        val report = RoadmapStreamTransitionAuthority(File(".")).analyze()
        assertEquals(
            expected = RoadmapTransitionPhase.AR0_1_ACTIVE,
            actual = report.phase,
            message = report.errors.joinToString(" | ")
        )
        assertEquals("PASS", report.status, report.errors.joinToString(" | "))
    }

    @Test
    fun completedCorrectionActivatesA10AndKeepsC02Planned() {
        val root = createA10Boundary()
        val report = RoadmapStreamTransitionAuthority(root).analyze()
        assertEquals(RoadmapTransitionPhase.A1_0_ACTIVE, report.phase, report.errors.joinToString(" | "))
        assertEquals("PASS", report.status, report.errors.joinToString(" | "))
    }

    @Test
    fun completedA10ActivatesC02AndClosesAdapterFocus() {
        val root = createC02Boundary()
        val report = RoadmapStreamTransitionAuthority(root).analyze()
        assertEquals(RoadmapTransitionPhase.C0_2_ACTIVE, report.phase, report.errors.joinToString(" | "))
        assertEquals("PASS", report.status, report.errors.joinToString(" | "))
    }

    @Test
    fun completedC02ActivatesC03() {
        val root = createC03Boundary()
        val report = RoadmapStreamTransitionAuthority(root).analyze()
        assertEquals(RoadmapTransitionPhase.C0_3_ACTIVE, report.phase, report.errors.joinToString(" | "))
        assertEquals("PASS", report.status, report.errors.joinToString(" | "))
    }

    @Test
    fun completedC03ActivatesC04() {
        val root = createC04Boundary()
        val report = RoadmapStreamTransitionAuthority(root).analyze()
        assertEquals(RoadmapTransitionPhase.C0_4_ACTIVE, report.phase, report.errors.joinToString(" | "))
        assertEquals("PASS", report.status, report.errors.joinToString(" | "))
    }

    @Test
    fun completedC04ActivatesAr01() {
        val root = createAr01Boundary()
        val report = RoadmapStreamTransitionAuthority(root).analyze()
        assertEquals(RoadmapTransitionPhase.AR0_1_ACTIVE, report.phase, report.errors.joinToString(" | "))
        assertEquals("PASS", report.status, report.errors.joinToString(" | "))
    }

    @Test
    fun completedCorrectionRejectsMissingCompletionBoundary() {
        val root = createA10Boundary()
        val correction = File(root, RoadmapStreamTransitionAuthority.CORRECTION_WORK_PACKAGE)
        correction.writeText(correction.readText().substringBefore("completionBoundary:").trimEnd() + "\n")
        val report = RoadmapStreamTransitionAuthority(root).analyze()
        assertEquals("FAIL", report.status)
        assertTrue(report.errors.any { "completion boundary" in it })
    }

    @Test
    fun completedA10RejectsReusedImplementationBoundary() {
        val root = createC02Boundary()
        val a10 = File(root, RoadmapStreamTransitionAuthority.A10_WORK_PACKAGE)
        a10.writeText(
            a10.readText()
                .replace("runNumber: 2901", "runNumber: 2900")
                .replace("runId: 35000000001", "runId: 35000000000")
                .replace("7777777777777777777777777777777777777777", "5555555555555555555555555555555555555555")
                .replace("8888888888888888888888888888888888888888", "6666666666666666666666666666666666666666")
        )
        val report = RoadmapStreamTransitionAuthority(root).analyze()
        assertEquals("FAIL", report.status)
        assertTrue(report.errors.any { "Completed A1.0 implementation and completion evidence" in it })
    }

    @Test
    fun completedC03RejectsMissingCompletionBoundary() {
        val root = createC04Boundary()
        val c03 = File(root, RoadmapStreamTransitionAuthority.C03_WORK_PACKAGE)
        c03.writeText(c03.readText().substringBefore("completionBoundary:").trimEnd() + "\n")

        val report = RoadmapStreamTransitionAuthority(root).analyze()

        assertEquals("FAIL", report.status)
        assertTrue(report.errors.any { "Completed C0.3 requires a strict" in it })
    }

    @Test
    fun completedC04RejectsMissingCompletionBoundary() {
        val root = createAr01Boundary()
        val c04 = File(root, RoadmapStreamTransitionAuthority.C04_WORK_PACKAGE)
        c04.writeText(c04.readText().substringBefore("completionBoundary:").trimEnd() + "\n")

        val report = RoadmapStreamTransitionAuthority(root).analyze()

        assertEquals("FAIL", report.status)
        assertTrue(report.errors.any { "Completed C0.4 requires a strict" in it })
    }

    @Test
    fun a10ActivationRejectsPrematureC02() {
        val root = createA10Boundary()
        val conformance = File(root, RoadmapStreamTransitionAuthority.CONFORMANCE_ROADMAP)
        conformance.writeText(conformance.readText().replace("status: planned", "status: next"))
        val report = RoadmapStreamTransitionAuthority(root).analyze()
        assertEquals("FAIL", report.status)
        assertTrue(report.errors.any { "C0.2 must be 'planned'" in it })
    }

    @Test
    fun transitionRejectsAdapterOwnedCrossStreamKnowledge() {
        val root = createA10Boundary()
        File(root, RoadmapStreamTransitionAuthority.ADAPTER_SEQUENCE).writeText(
            """
            package fixture
            object AdapterRoadmapSequence {
                const val SUCCESSOR_STREAM = "conformance"
            }
            """.trimIndent() + "\n"
        )
        val report = RoadmapStreamTransitionAuthority(root).analyze()
        assertEquals("FAIL", report.status)
        assertTrue(report.errors.any { "globally-owned successor knowledge" in it })
    }

    private fun createA10Boundary(): File {
        val root = createTempDirectory("flow-roadmap-transition").toFile()
        REQUIRED_FILES.forEach { path -> File(root, path).apply { parentFile.mkdirs(); writeText("fixture\n") } }
        File(root, RoadmapStreamTransitionAuthority.ADAPTER_SEQUENCE).apply {
            parentFile.mkdirs()
            writeText("package fixture\nobject AdapterRoadmapSequence\n")
        }
        write(root, RoadmapStreamTransitionAuthority.ROADMAP_INDEX, """
            primaryRoadmapStream: adapters
            currentDecision:
              conformanceCorrectionState: complete
              activeConformanceCorrectionWorkPackage: ""
              closureItem: "0.9.7.10"
              closureItemStatus: completed
              completedAdapterItem: "A0.7"
              nextItem: "A1.0"
              nextItemName: "GitHub Actions Artifact and Workspace Continuity"
              nextItemStream: adapters
        """)
        write(root, RoadmapStreamTransitionAuthority.ADAPTER_ROADMAP, """
            stream: adapters
            status: active
            currentDecision:
              completedItem: "A0.7"
              nextItem: "A1.0"
            items:
              - version: "A0.7"
                status: completed
              - version: "A1.0"
                status: next
        """)
        write(root, RoadmapStreamTransitionAuthority.CONFORMANCE_ROADMAP, """
            stream: conformance
            currentDecision:
              completedItem: "C0.1.1"
              nextItem: ""
            items:
              - version: "C0.1"
                status: completed
              - version: "C0.1.1"
                status: completed
              - version: "C0.2"
                status: planned
        """)
        write(root, RoadmapStreamTransitionAuthority.RELEASE_STATE, """
            roadmapState:
              primaryStream: adapters
              closureItem: "0.9.7.10"
              closureItemStatus: completed
              completedAdapterItem: "A0.7"
              nextItem: "A1.0"
              nextItemName: "GitHub Actions Artifact and Workspace Continuity"
        """)
        writePassedCorrection(root)
        write(root, RoadmapStreamTransitionAuthority.A10_WORK_PACKAGE, "status: active\n")
        write(root, RoadmapStreamTransitionAuthority.C02_WORK_PACKAGE, "status: planned\n")
        return root
    }

    private fun createC02Boundary(): File {
        val root = createA10Boundary()
        write(root, RoadmapStreamTransitionAuthority.ROADMAP_INDEX, """
            primaryRoadmapStream: conformance
            currentDecision:
              conformanceCorrectionState: complete
              activeConformanceCorrectionWorkPackage: ""
              closureItem: "0.9.7.10"
              closureItemStatus: completed
              completedAdapterItem: "A1.0"
              nextItem: "C0.2"
              nextItemName: "Abstract Topology Matrix"
              nextItemStream: conformance
        """)
        write(root, RoadmapStreamTransitionAuthority.ADAPTER_ROADMAP, """
            stream: adapters
            status: completed
            currentDecision:
              completedItem: "A1.0"
              nextItem: ""
            items:
              - version: "A0.7"
                status: completed
              - version: "A1.0"
                status: completed
        """)
        write(root, RoadmapStreamTransitionAuthority.CONFORMANCE_ROADMAP, """
            stream: conformance
            currentDecision:
              completedItem: "C0.1.1"
              nextItem: "C0.2"
            items:
              - version: "C0.1"
                status: completed
              - version: "C0.1.1"
                status: completed
              - version: "C0.2"
                status: next
        """)
        write(root, RoadmapStreamTransitionAuthority.RELEASE_STATE, """
            roadmapState:
              primaryStream: conformance
              closureItem: "0.9.7.10"
              closureItemStatus: completed
              completedAdapterItem: "A1.0"
              nextItem: "C0.2"
              nextItemName: "Abstract Topology Matrix"
        """)
        write(root, RoadmapStreamTransitionAuthority.A10_WORK_PACKAGE, """
            status: complete
            implementationEvidence:
              status: passed
              workflow: Flow CI
              runNumber: 2900
              runId: 35000000000
              exactHead: "5555555555555555555555555555555555555555"
              mergeCandidate: "6666666666666666666666666666666666666666"
            completionBoundary:
              status: passed
              workflow: Flow CI
              runNumber: 2901
              runId: 35000000001
              exactHead: "7777777777777777777777777777777777777777"
              mergeCandidate: "8888888888888888888888888888888888888888"
        """)
        write(root, RoadmapStreamTransitionAuthority.C02_WORK_PACKAGE, "status: active\n")
        return root
    }

    private fun createC03Boundary(): File {
        val root = createC02Boundary()
        write(root, RoadmapStreamTransitionAuthority.ROADMAP_INDEX, """
            primaryRoadmapStream: conformance
            currentDecision:
              conformanceCorrectionState: complete
              activeConformanceCorrectionWorkPackage: ""
              closureItem: "0.9.7.10"
              closureItemStatus: completed
              completedAdapterItem: "A1.0"
              nextItem: "C0.3"
              nextItemName: "Semantic Equivalence Rules"
              nextItemStream: conformance
        """)
        write(root, RoadmapStreamTransitionAuthority.CONFORMANCE_ROADMAP, """
            stream: conformance
            currentDecision:
              completedItem: "C0.2"
              nextItem: "C0.3"
            items:
              - version: "C0.1"
                status: completed
              - version: "C0.1.1"
                status: completed
              - version: "C0.2"
                status: completed
              - version: "C0.3"
                status: next
              - version: "C0.4"
                status: planned
        """)
        write(root, RoadmapStreamTransitionAuthority.RELEASE_STATE, """
            roadmapState:
              primaryStream: conformance
              closureItem: "0.9.7.10"
              closureItemStatus: completed
              completedAdapterItem: "A1.0"
              nextItem: "C0.3"
              nextItemName: "Semantic Equivalence Rules"
        """)
        write(root, RoadmapStreamTransitionAuthority.C02_WORK_PACKAGE, completedEvidence(
            implementationRun = 3000,
            implementationRunId = 36000000000,
            implementationHeadDigit = "1",
            implementationMergeDigit = "2",
            completionRun = 3001,
            completionRunId = 36000000001,
            completionHeadDigit = "3",
            completionMergeDigit = "4"
        ))
        write(root, RoadmapStreamTransitionAuthority.C03_WORK_PACKAGE, "status: active\n")
        return root
    }

    private fun createC04Boundary(): File {
        val root = createC03Boundary()
        write(root, RoadmapStreamTransitionAuthority.ROADMAP_INDEX, """
            primaryRoadmapStream: conformance
            currentDecision:
              conformanceCorrectionState: complete
              activeConformanceCorrectionWorkPackage: ""
              closureItem: "0.9.7.10"
              closureItemStatus: completed
              completedAdapterItem: "A1.0"
              nextItem: "C0.4"
              nextItemName: "Adapter Profile Evidence"
              nextItemStream: conformance
        """)
        write(root, RoadmapStreamTransitionAuthority.CONFORMANCE_ROADMAP, """
            stream: conformance
            currentDecision:
              completedItem: "C0.3"
              nextItem: "C0.4"
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
                status: next
        """)
        write(root, RoadmapStreamTransitionAuthority.RELEASE_STATE, """
            roadmapState:
              primaryStream: conformance
              closureItem: "0.9.7.10"
              closureItemStatus: completed
              completedAdapterItem: "A1.0"
              nextItem: "C0.4"
              nextItemName: "Adapter Profile Evidence"
        """)
        write(root, RoadmapStreamTransitionAuthority.C03_WORK_PACKAGE, completedEvidence(
            implementationRun = 3100,
            implementationRunId = 37000000000,
            implementationHeadDigit = "5",
            implementationMergeDigit = "6",
            completionRun = 3101,
            completionRunId = 37000000001,
            completionHeadDigit = "7",
            completionMergeDigit = "8"
        ))
        return root
    }

    private fun createAr01Boundary(): File {
        val root = createC04Boundary()
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
        write(root, RoadmapStreamTransitionAuthority.C04_WORK_PACKAGE, completedEvidence(
            implementationRun = 3200,
            implementationRunId = 38000000000,
            implementationHeadDigit = "9",
            implementationMergeDigit = "a",
            completionRun = 3201,
            completionRunId = 38000000001,
            completionHeadDigit = "b",
            completionMergeDigit = "c"
        ))
        return root
    }

    private fun completedEvidence(
        implementationRun: Int,
        implementationRunId: Long,
        implementationHeadDigit: String,
        implementationMergeDigit: String,
        completionRun: Int,
        completionRunId: Long,
        completionHeadDigit: String,
        completionMergeDigit: String
    ): String = """
        status: complete
        implementationEvidence:
          status: passed
          workflow: Flow CI
          runNumber: $implementationRun
          runId: $implementationRunId
          exactHead: "${implementationHeadDigit.repeat(40)}"
          mergeCandidate: "${implementationMergeDigit.repeat(40)}"
        completionBoundary:
          status: passed
          workflow: Flow CI
          runNumber: $completionRun
          runId: $completionRunId
          exactHead: "${completionHeadDigit.repeat(40)}"
          mergeCandidate: "${completionMergeDigit.repeat(40)}"
    """

    private fun writePassedCorrection(root: File) {
        write(root, RoadmapStreamTransitionAuthority.CORRECTION_WORK_PACKAGE, """
            status: complete
            implementationEvidence:
              status: passed
              workflow: Flow CI
              runNumber: 2800
              runId: 34000000000
              exactHead: "1111111111111111111111111111111111111111"
              mergeCandidate: "2222222222222222222222222222222222222222"
            completionBoundary:
              status: passed
              workflow: Flow CI
              runNumber: 2801
              runId: 34000000001
              exactHead: "3333333333333333333333333333333333333333"
              mergeCandidate: "4444444444444444444444444444444444444444"
        """)
    }

    private fun write(root: File, path: String, content: String) {
        File(root, path).apply { parentFile.mkdirs(); writeText(content.trimIndent() + "\n") }
    }

    companion object {
        private val REQUIRED_FILES = listOf(
            "src/main/kotlin/org/flowlang/roadmap/RoadmapStreamTransitionAuthority.kt",
            "src/main/kotlin/org/flowlang/conformance/RealWorldPolarityAuthority.kt",
            "src/test/kotlin/RoadmapStreamTransitionAuthorityTests.kt",
            "tests/RealWorldCorpusTests.kt",
            "docs/C0_1_BOUNDED_DOMAIN_CORPUS.md"
        )
    }
}
