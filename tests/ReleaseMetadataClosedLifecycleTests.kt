import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.flowlang.cli.Json
import org.flowlang.release.ReleaseMetadataHonestyAuthority

class ReleaseMetadataClosedLifecycleTests {
    @Test
    fun structuredCorrectionRequiredLifecyclePasses() = withMetadataFixture { root ->
        val report = ReleaseMetadataHonestyAuthority(root).analyze()

        assertEquals("PASS", report.status, report.failedChecks.joinToString())
        assertEquals("CORRECTION_REQUIRED", report.closurePhase)
        assertEquals("active", report.correctionStatus)
        assertEquals("correction-required", report.parentCoreItemStatus)
        assertEquals("correction-required", report.closureWorkPackageStatus)
        assertEquals("correction-required", report.closureStatus)
        assertEquals("active", report.coreTrackStatus)
        assertEquals("0.9.7.9", report.completedCoreItem)
    }

    @Test
    fun structuredReadyLifecyclePasses() = withMetadataFixture { root ->
        readyMetadata(root)

        val report = ReleaseMetadataHonestyAuthority(root).analyze()

        assertEquals("PASS", report.status, report.failedChecks.joinToString())
        assertEquals("READY", report.closurePhase)
        assertEquals("complete", report.correctionStatus)
        assertEquals("next", report.parentCoreItemStatus)
        assertEquals("active", report.closureWorkPackageStatus)
        assertEquals("next", report.closureStatus)
        assertEquals("active", report.coreTrackStatus)
        assertEquals("0.9.7.9", report.completedCoreItem)
    }

    @Test
    fun structuredClosedLifecyclePasses() = withMetadataFixture { root ->
        closeMetadata(root, includeImplementationEvidence = true)

        val report = ReleaseMetadataHonestyAuthority(root).analyze()

        assertEquals("PASS", report.status, report.failedChecks.joinToString())
        assertEquals("CLOSED", report.closurePhase)
        assertEquals("complete", report.correctionStatus)
        assertEquals("completed", report.parentCoreItemStatus)
        assertEquals("complete", report.closureWorkPackageStatus)
        assertEquals("completed", report.closureStatus)
        assertEquals("completed", report.coreTrackStatus)
        assertEquals("0.9.7.10", report.completedCoreItem)
    }

    @Test
    fun completedClosureWithoutStructuredImplementationEvidenceFails() = withMetadataFixture { root ->
        closeMetadata(root, includeImplementationEvidence = false)

        val report = ReleaseMetadataHonestyAuthority(root).analyze()

        assertEquals("FAIL", report.status)
        assertTrue("release.closure.implementation-evidence" in report.failedChecks)
    }

    @Test
    fun activeCorrectionCannotPublishReadyClosureStatus() = withMetadataFixture { root ->
        val closure = File(root, CLOSURE_WORK_PACKAGE)
        closure.writeText(updateTopLevelStatus(closure.readText(), "active"))
        val core = File(root, CORE_ROADMAP)
        core.writeText(updateCoreClosureStatus(core.readText(), "next"))
        val roadmap = File(root, ROADMAP)
        roadmap.writeText(
            roadmap.readText().replaceFirst(
                "nextCoreItemStatus: \"correction-required\"",
                "nextCoreItemStatus: \"next\""
            )
        )

        val report = ReleaseMetadataHonestyAuthority(root).analyze()

        assertEquals("FAIL", report.status)
        assertEquals("INVALID", report.closurePhase)
        assertTrue("release.closure.phase" in report.failedChecks)
    }

    @Test
    fun completedCorrectionCannotLeaveClosureCorrectionRequired() = withMetadataFixture { root ->
        val correction = File(root, CORRECTION_WORK_PACKAGE)
        correction.writeText(correction.readText().replaceFirst("status: active", "status: complete"))
        val roadmap = File(root, ROADMAP)
        roadmap.writeText(
            roadmap.readText()
                .replaceFirst("correctionState: \"active\"", "correctionState: \"complete\"")
                .replaceFirst(
                    "activeCorrectionWorkPackage: \"$CORRECTION_WORK_PACKAGE\"",
                    "activeCorrectionWorkPackage: \"\""
                )
                .replaceFirst(
                    "activeCorrectionWorkPackageName: \"Standard and Closure Integrity Correction\"",
                    "activeCorrectionWorkPackageName: \"\""
                )
        )

        val report = ReleaseMetadataHonestyAuthority(root).analyze()

        assertEquals("FAIL", report.status)
        assertEquals("INVALID", report.closurePhase)
        assertTrue("release.closure.phase" in report.failedChecks)
    }

    @Test
    fun completedClosureWithActiveCoreTrackFails() = withMetadataFixture { root ->
        closeMetadata(root, includeImplementationEvidence = true)
        val core = File(root, CORE_ROADMAP)
        core.writeText(updateCoreTrackStatus(core.readText(), "active"))

        val report = ReleaseMetadataHonestyAuthority(root).analyze()

        assertEquals("FAIL", report.status)
        assertEquals("INVALID", report.closurePhase)
        assertTrue("release.closure.phase-alignment" in report.failedChecks)
    }

    @Test
    fun completedClosureWithStaleCompletedItemFails() = withMetadataFixture { root ->
        closeMetadata(root, includeImplementationEvidence = true)
        val roadmap = File(root, ROADMAP)
        roadmap.writeText(
            roadmap.readText().replaceFirst(
                "completedItem: \"0.9.7.10\"",
                "completedItem: \"0.9.7.9\""
            )
        )

        val report = ReleaseMetadataHonestyAuthority(root).analyze()

        assertEquals("FAIL", report.status)
        assertTrue("release.roadmap.completed-item" in report.failedChecks)
    }

    @Test
    fun schemaMatchesRuntimeLifecycleContract() {
        val report = ReleaseMetadataHonestyAuthority(File(".")).analyze()
        val schema = Json.mapper.readTree(File("schemas/release-metadata-honesty-report.schema.json"))
        val required = schema.path("required").map { it.asText() }.toSet()
        val phases = schema.path("properties").path("closurePhase").path("enum").map { it.asText() }.toSet()
        val closureStatuses = schema.path("properties").path("closureStatus").path("enum").map { it.asText() }.toSet()

        assertEquals(report.reportVersion, schema.path("properties").path("reportVersion").path("const").asText())
        assertTrue(setOf("CORRECTION_REQUIRED", "READY", "CLOSED", "INVALID").all { it in phases })
        assertTrue(setOf("correction-required", "next", "completed").all { it in closureStatuses })
        assertTrue(
            setOf(
                "closureItem",
                "closureWorkPackageStatus",
                "closurePhase",
                "coreTrackStatus",
                "completedCoreItem"
            ).all { it in required }
        )
    }

    private fun readyMetadata(root: File) {
        val correction = File(root, CORRECTION_WORK_PACKAGE)
        correction.writeText(correction.readText().replaceFirst("status: active", "status: complete"))

        val closure = File(root, CLOSURE_WORK_PACKAGE)
        closure.writeText(
            removeTopLevelSection(
                removeTopLevelSection(
                    updateTopLevelStatus(closure.readText(), "active"),
                    "supersededByCorrection"
                ),
                "validationEvidence"
            ).trimEnd() + "\n"
        )

        val core = File(root, CORE_ROADMAP)
        core.writeText(updateCoreClosureStatus(core.readText(), "next"))

        val roadmap = File(root, ROADMAP)
        roadmap.writeText(
            roadmap.readText()
                .replaceFirst("correctionState: \"active\"", "correctionState: \"complete\"")
                .replaceFirst(
                    "activeCorrectionWorkPackage: \"$CORRECTION_WORK_PACKAGE\"",
                    "activeCorrectionWorkPackage: \"\""
                )
                .replaceFirst(
                    "activeCorrectionWorkPackageName: \"Standard and Closure Integrity Correction\"",
                    "activeCorrectionWorkPackageName: \"\""
                )
                .replaceFirst("nextCoreItemStatus: \"correction-required\"", "nextCoreItemStatus: \"next\"")
        )

        val report = File(root, REPORT)
        report.writeText(
            report.readText()
                .replaceFirst("Active correction item:", "Completed correction item:")
                .replaceFirst("Core roadmap item status: `correction-required`", "Core roadmap item status: `next`")
                .replaceFirst(
                    "Core closure correction: `0.9.7.10 Bounded Semantic Closure Gate` (`correction-required`)",
                    "Next Core roadmap item: `0.9.7.10 Bounded Semantic Closure Gate` (`next`)"
                )
        )
    }

    private fun closeMetadata(root: File, includeImplementationEvidence: Boolean) {
        readyMetadata(root)
        val closure = File(root, CLOSURE_WORK_PACKAGE)
        val evidence = if (includeImplementationEvidence) {
            """

validationEvidence:
  status: passed
  workflow: "Flow CI"
  runNumber: "9999"
  runId: "123456789"
  exactHead: "1111111111111111111111111111111111111111"
  mergeCandidate: "2222222222222222222222222222222222222222"
""".trimEnd()
        } else {
            ""
        }
        closure.writeText(updateTopLevelStatus(closure.readText(), "complete").trimEnd() + evidence + "\n")

        val core = File(root, CORE_ROADMAP)
        core.writeText(
            updateCoreClosureStatus(
                updateCoreTrackStatus(core.readText(), "completed"),
                "completed"
            )
        )

        val roadmap = File(root, ROADMAP)
        roadmap.writeText(
            roadmap.readText()
                .replaceFirst("completedItem: \"0.9.7.9\"", "completedItem: \"0.9.7.10\"")
                .replaceFirst(
                    "completedItemName: \"Intent Lowering and Diagnostic Honesty\"",
                    "completedItemName: \"Bounded Semantic Closure Gate\""
                )
                .replaceFirst("nextCoreItemStatus: \"next\"", "nextCoreItemStatus: \"completed\"")
        )

        val releaseState = File(root, RELEASE_STATE)
        releaseState.writeText(
            releaseState.readText()
                .replaceFirst("completedItem: \"0.9.7.9\"", "completedItem: \"0.9.7.10\"")
                .replaceFirst(
                    "completedItemName: \"Intent Lowering and Diagnostic Honesty\"",
                    "completedItemName: \"Bounded Semantic Closure Gate\""
                )
        )

        val report = File(root, REPORT)
        report.writeText(
            report.readText()
                .replaceFirst(
                    "Completed Core roadmap identity: `0.9.7.9 Intent Lowering and Diagnostic Honesty`",
                    "Completed Core roadmap identity: `0.9.7.10 Bounded Semantic Closure Gate`"
                )
                .replaceFirst("Core roadmap item status: `next`", "Core roadmap item status: `completed`")
                .replaceFirst(
                    "Next Core roadmap item: `0.9.7.10 Bounded Semantic Closure Gate` (`next`)",
                    "Completed Core closure item: `0.9.7.10 Bounded Semantic Closure Gate` (`completed`)"
                )
        )
    }

    private fun removeTopLevelSection(text: String, section: String): String {
        val lines = text.lines()
        val start = lines.indexOfFirst { it == "$section:" || it.startsWith("$section: ") }
        if (start < 0) return text
        val end = (start + 1 until lines.size).firstOrNull { index ->
            lines[index].isNotBlank() && !lines[index].first().isWhitespace()
        } ?: lines.size
        return (lines.take(start) + lines.drop(end)).joinToString("\n")
    }

    private fun updateTopLevelStatus(text: String, status: String): String {
        val pattern = Regex("(?m)^(status:\\s*)([a-z-]+)(\\s*)$")
        val match = pattern.find(text) ?: error("Closure work-package status is missing.")
        return text.replaceRange(match.range, match.groupValues[1] + status + match.groupValues[3])
    }

    private fun updateCoreTrackStatus(text: String, status: String): String {
        val pattern = Regex("(?m)^(status:\\s*)([a-z-]+)(\\s*)$")
        val match = pattern.find(text) ?: error("Core roadmap track status is missing.")
        return text.replaceRange(match.range, match.groupValues[1] + status + match.groupValues[3])
    }

    private fun updateCoreClosureStatus(text: String, status: String): String {
        val pattern = Regex(
            "(?ms)(^\\s*- version:\\s*[\"']?0\\.9\\.7\\.10[\"']?\\s*$.*?^\\s*status:\\s*)([a-z-]+)(\\s*$)"
        )
        val match = pattern.find(text) ?: error("Closure roadmap item 0.9.7.10 is missing.")
        return text.replaceRange(match.range, match.groupValues[1] + status + match.groupValues[3])
    }

    private fun withMetadataFixture(assertions: (File) -> Unit) {
        val root = Files.createTempDirectory("flow-release-lifecycle").toFile()
        try {
            metadataFiles().forEach { path ->
                val source = File(path)
                val destination = File(root, path)
                destination.parentFile?.mkdirs()
                source.copyTo(destination, overwrite = true)
            }
            assertions(root)
        } finally {
            root.deleteRecursively()
        }
    }

    private fun metadataFiles(): List<String> = listOf(
        "build.gradle.kts",
        REPORT,
        "CHANGELOG-v0.9.7.9.md",
        "CHANGELOG-v0.9.7.10.md",
        RELEASE_STATE,
        ROADMAP,
        CORE_ROADMAP,
        CLOSURE_WORK_PACKAGE,
        CORRECTION_WORK_PACKAGE
    )

    companion object {
        private const val REPORT = "REPORT.md"
        private const val RELEASE_STATE = ".flow-agent/release-state.yaml"
        private const val ROADMAP = ".flow-agent/roadmap.yaml"
        private const val CORE_ROADMAP = ".flow-agent/roadmap-core-v0.9.7.9.yaml"
        private const val CLOSURE_WORK_PACKAGE =
            ".flow-agent/work-packages/v0.9.7.10-bounded-semantic-closure-gate.yaml"
        private const val CORRECTION_WORK_PACKAGE =
            ".flow-agent/work-packages/v0.9.7.10.1-standard-closure-integrity.yaml"
    }
}
