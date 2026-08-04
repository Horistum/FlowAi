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
    fun repositoryActivatesA10AfterCompletedCorrection() {
        val report = RoadmapStreamTransitionAuthority(File(".")).analyze()
        assertEquals(
            expected = RoadmapTransitionPhase.A1_0_ACTIVE,
            actual = report.phase,
            message = report.errors.joinToString(" | ")
        )
        assertEquals("PASS", report.status, report.errors.joinToString(" | "))
    }

    @Test
    fun completedCorrectionActivatesA10AndKeepsC02Planned() {
        val root = createA10Boundary()
        val report = RoadmapStreamTransitionAuthority(root).analyze()
        assertEquals(
            expected = RoadmapTransitionPhase.A1_0_ACTIVE,
            actual = report.phase,
            message = report.errors.joinToString(" | ")
        )
        assertEquals("PASS", report.status, report.errors.joinToString(" | "))
    }

    @Test
    fun a10ActivationRejectsPrematureC02() {
        val root = createA10Boundary()
        val conformance = File(root, RoadmapStreamTransitionAuthority.CONFORMANCE_ROADMAP)
        conformance.writeText(conformance.readText().replace("status: planned", "status: next"))
        val report = RoadmapStreamTransitionAuthority(root).analyze()
        assertEquals("FAIL", report.status)
        assertTrue(report.errors.any { "C0.2 must remain planned" in it })
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
              completedItem: "C0.1"
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
        write(root, RoadmapStreamTransitionAuthority.CORRECTION_WORK_PACKAGE, """
            status: complete
            implementationEvidence:
              status: passed
              workflow: Flow CI
              runNumber: 2800
              runId: 34000000000
              exactHead: "1111111111111111111111111111111111111111"
              mergeCandidate: "2222222222222222222222222222222222222222"
        """)
        write(root, RoadmapStreamTransitionAuthority.A10_WORK_PACKAGE, "status: active\n")
        return root
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
