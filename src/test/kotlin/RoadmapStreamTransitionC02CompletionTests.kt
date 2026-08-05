package org.flowlang.tests

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.flowlang.roadmap.RoadmapStreamTransitionAuthority
import org.flowlang.roadmap.RoadmapTransitionPhase

class RoadmapStreamTransitionC02CompletionTests {
    @Test
    fun completedC02ActivatesAdjacentC03Focus() {
        val root = createC03Boundary()

        val report = RoadmapStreamTransitionAuthority(root).analyze()

        assertEquals(RoadmapTransitionPhase.C0_3_ACTIVE, report.phase, report.errors.joinToString(" | "))
        assertEquals("PASS", report.status, report.errors.joinToString(" | "))
    }

    @Test
    fun completedC02RejectsReusedImplementationBoundary() {
        val root = createC03Boundary()
        val c02 = File(root, RoadmapStreamTransitionAuthority.C02_WORK_PACKAGE)
        c02.writeText(
            c02.readText()
                .replace("runNumber: 3001", "runNumber: 3000")
                .replace("runId: 36000000001", "runId: 36000000000")
                .replace("7777777777777777777777777777777777777777", "5555555555555555555555555555555555555555")
                .replace("8888888888888888888888888888888888888888", "6666666666666666666666666666666666666666")
        )

        val report = RoadmapStreamTransitionAuthority(root).analyze()

        assertEquals("FAIL", report.status)
        assertTrue(report.errors.any { "Completed C0.2 implementation and completion evidence" in it })
    }

    @Test
    fun activeC03MayCarryImplementationEvidenceDuringValidation() {
        val root = createC03Boundary()
        File(root, RoadmapStreamTransitionAuthority.C03_WORK_PACKAGE).appendText(
            """
            implementationEvidence:
              status: passed
              workflow: Flow CI
              runNumber: 3100
              runId: 37000000000
              exactHead: "9999999999999999999999999999999999999999"
              mergeCandidate: "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
            """.trimIndent() + "\n"
        )

        val report = RoadmapStreamTransitionAuthority(root).analyze()

        assertEquals(RoadmapTransitionPhase.C0_3_ACTIVE, report.phase, report.errors.joinToString(" | "))
        assertEquals("PASS", report.status, report.errors.joinToString(" | "))
    }

    private fun createC03Boundary(): File {
        val root = createTempDirectory("flow-c0-3-transition").toFile()
        STATIC_FILES.forEach { path ->
            File(root, path).apply { parentFile.mkdirs(); writeText("fixture\n") }
        }
        File(root, RoadmapStreamTransitionAuthority.ADAPTER_SEQUENCE).apply {
            parentFile.mkdirs()
            writeText("package fixture\nobject AdapterRoadmapSequence\n")
        }
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
        write(root, RoadmapStreamTransitionAuthority.ADAPTER_ROADMAP, """
            status: completed
            currentDecision:
              completedItem: "A1.0"
              nextItem: ""
            items:
              - version: "A1.0"
                status: completed
        """)
        write(root, RoadmapStreamTransitionAuthority.CONFORMANCE_ROADMAP, """
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
        writePassedPackage(root, RoadmapStreamTransitionAuthority.CORRECTION_WORK_PACKAGE, 2800, 2801)
        writePassedPackage(root, RoadmapStreamTransitionAuthority.A10_WORK_PACKAGE, 2900, 2901)
        writePassedPackage(root, RoadmapStreamTransitionAuthority.C02_WORK_PACKAGE, 3000, 3001)
        write(root, RoadmapStreamTransitionAuthority.C03_WORK_PACKAGE, "status: active")
        return root
    }

    private fun writePassedPackage(root: File, path: String, implementationRun: Int, completionRun: Int) {
        val offset = implementationRun - 2800
        val implementationExact = when (offset) {
            0 -> "1111111111111111111111111111111111111111"
            100 -> "3333333333333333333333333333333333333333"
            else -> "5555555555555555555555555555555555555555"
        }
        val implementationMerge = when (offset) {
            0 -> "2222222222222222222222222222222222222222"
            100 -> "4444444444444444444444444444444444444444"
            else -> "6666666666666666666666666666666666666666"
        }
        val completionExact = if (offset == 200) {
            "7777777777777777777777777777777777777777"
        } else {
            (if (offset == 0) '5' else '7').toString().repeat(40)
        }
        val completionMerge = if (offset == 200) {
            "8888888888888888888888888888888888888888"
        } else {
            (if (offset == 0) '6' else '8').toString().repeat(40)
        }
        write(root, path, """
            status: complete
            implementationEvidence:
              status: passed
              workflow: Flow CI
              runNumber: $implementationRun
              runId: ${34000000000L + offset}
              exactHead: "$implementationExact"
              mergeCandidate: "$implementationMerge"
            completionBoundary:
              status: passed
              workflow: Flow CI
              runNumber: $completionRun
              runId: ${34000000001L + offset}
              exactHead: "$completionExact"
              mergeCandidate: "$completionMerge"
        """)
    }

    private fun write(root: File, path: String, content: String) {
        File(root, path).apply {
            parentFile.mkdirs()
            writeText(content.trimIndent() + "\n")
        }
    }

    companion object {
        private val STATIC_FILES = listOf(
            "src/main/kotlin/org/flowlang/roadmap/RoadmapStreamTransitionAuthority.kt",
            "src/main/kotlin/org/flowlang/conformance/RealWorldPolarityAuthority.kt",
            "src/test/kotlin/RoadmapStreamTransitionAuthorityTests.kt",
            "tests/RealWorldCorpusTests.kt",
            "docs/C0_1_BOUNDED_DOMAIN_CORPUS.md"
        )
    }
}
