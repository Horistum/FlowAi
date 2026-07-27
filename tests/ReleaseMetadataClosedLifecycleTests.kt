import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.flowlang.cli.Json
import org.flowlang.release.ReleaseMetadataHonestyAuthority

class ReleaseMetadataClosedLifecycleTests {
    @Test
    fun structuredCorrectionRequiredLifecyclePassesWithoutNextProjection() = withMetadataFixture { root ->
        val report = ReleaseMetadataHonestyAuthority(root).analyze()

        assertEquals("PASS", report.status, report.failedChecks.joinToString())
        assertEquals("CORRECTION_REQUIRED", report.closurePhase)
        assertEquals("active", report.correctionStatus)
        assertEquals("correction-required", report.parentCoreItemStatus)
        assertEquals("correction-required", report.closureWorkPackageStatus)
        assertEquals("correction-required", report.closureStatus)
        assertEquals("active", report.coreTrackStatus)
        assertEquals("0.9.7.9", report.completedCoreItem)
        assertNull(report.nextCoreItem)
    }

    @Test
    fun structuredReadyLifecyclePassesWithRealNextProjection() = withMetadataFixture { root ->
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
        assertEquals("0.9.7.10", report.nextCoreItem)
    }

    @Test
    fun structuredClosedLifecyclePassesWithoutNextProjection() = withMetadataFixture { root ->
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
        assertNull(report.nextCoreItem)
    }

    @Test
    fun completedClosureWithoutStructuredImplementationEvidenceFails() = withMetadataFixture { root ->
        closeMetadata(root, includeImplementationEvidence = false)

        val report = ReleaseMetadataHonestyAuthority(root).analyze()

        assertEquals("FAIL", report.status)
        assertTrue("release.closure.implementation-evidence" in report.failedChecks)
    }

    @Test
    fun correctionRequiredCannotPublishNextProjection() = withMetadataFixture { root ->
        addNextProjection(File(root, ROADMAP), "currentDecision")
        addNextProjection(File(root, RELEASE_STATE), "roadmapState")

        val report = ReleaseMetadataHonestyAuthority(root).analyze()

        assertEquals("FAIL", report.status)
        assertEquals("CORRECTION_REQUIRED", report.closurePhase)
        assertTrue("release.roadmap.next-item" in report.failedChecks)
        assertTrue("release.state.next-item" in report.failedChecks)
    }

    @Test
    fun readyLifecycleWithoutNextProjectionFails() = withMetadataFixture { root ->
        readyMetadata(root)
        removeNextProjection(File(root, ROADMAP))
        removeNextProjection(File(root, RELEASE_STATE))

        val report = ReleaseMetadataHonestyAuthority(root).analyze()

        assertEquals("FAIL", report.status)
        assertEquals("READY", report.closurePhase)
        assertTrue("release.roadmap.next-item" in report.failedChecks)
        assertTrue("release.state.next-item" in report.failedChecks)
    }

    @Test
    fun closedLifecycleCannotRetainNextProjection() = withMetadataFixture { root ->
        closeMetadata(root, includeImplementationEvidence = true)
        addNextProjection(File(root, ROADMAP), "currentDecision")
        addNextProjection(File(root, RELEASE_STATE), "roadmapState")

        val report = ReleaseMetadataHonestyAuthority(root).analyze()

        assertEquals("FAIL", report.status)
        assertEquals("CLOSED", report.closurePhase)
        assertTrue("release.roadmap.next-item" in report.failedChecks)
        assertTrue("release.state.next-item" in report.failedChecks)
    }

    @Test
    fun activeCorrectionCannotPublishReadyClosureStatus() = withMetadataFixture { root ->
        val closure = File(root, CLOSURE_WORK_PACKAGE)
        closure.writeText(updateTopLevelStatus(closure.readText(), "active"))
        val core = File(root, CORE_ROADMAP)
        core.writeText(updateCoreClosureStatus(core.readText(), "next"))
        setClosureStatus(File(root, ROADMAP), "next")
        setClosureStatus(File(root, RELEASE_STATE), "next")
        addNextProjection(File(root, ROADMAP), "currentDecision")
        addNextProjection(File(root, RELEASE_STATE), "roadmapState")

        val report = ReleaseMetadataHonestyAuthority(root).analyze()

        assertEquals("FAIL", report.status)
        assertEquals("INVALID", report.closurePhase)
        assertTrue("release.closure.phase" in report.failedChecks)
    }

    @Test
    fun completedCorrectionCannotLeaveClosureCorrectionRequired() = withMetadataFixture { root ->
        completeCorrection(root)

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
        replaceScalar(File(root, ROADMAP), "completedItem", "0.9.7.9")

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
        val nextVariants = schema.path("properties").path("nextCoreItem").path("oneOf")

        assertEquals(report.reportVersion, schema.path("properties").path("reportVersion").path("const").asText())
        assertTrue(setOf("CORRECTION_REQUIRED", "READY", "CLOSED", "INVALID").all { it in phases })
        assertTrue(setOf("correction-required", "next", "completed").all { it in closureStatuses })
        assertTrue(nextVariants.any { it.path("type").asText() == "null" })
        assertTrue(
            setOf(
                "closureItem",
                "closureWorkPackageStatus",
                "closurePhase",
                "coreTrackStatus",
                "completedCoreItem",
                "nextCoreItem"
            ).all { it in required }
        )
    }

    private fun readyMetadata(root: File) {
        completeCorrection(root)

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
        setClosureStatus(File(root, ROADMAP), "next")
        setClosureStatus(File(root, RELEASE_STATE), "next")
        addNextProjection(File(root, ROADMAP), "currentDecision")
        addNextProjection(File(root, RELEASE_STATE), "roadmapState")

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

        listOf(File(root, ROADMAP), File(root, RELEASE_STATE)).forEach { file ->
            replaceScalar(file, "completedItem", "0.9.7.10")
            replaceScalar(file, "completedItemName", "Bounded Semantic Closure Gate")
            setClosureStatus(file, "completed")
            removeNextProjection(file)
        }

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

    private fun completeCorrection(root: File) {
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
    }

    private fun addNextProjection(file: File, section: String) {
        val text = file.readText()
        if (Regex("(?m)^\\s*nextCoreItem:").containsMatchIn(text)) return
        val anchor = Regex("(?m)^(\\s*)closureItemStatus:\s*\"?[^\"\\n]+\"?\\s*$")
            .find(text) ?: error("closureItemStatus missing in $section")
        val indent = anchor.groupValues[1]
        val addition = "\n${indent}nextCoreItem: \"0.9.7.10\"" +
            "\n${indent}nextCoreItemName: \"Bounded Semantic Closure Gate\"" +
            "\n${indent}nextCoreItemStatus: \"next\""
        file.writeText(text.replaceRange(anchor.range, anchor.value + addition))
    }

    private fun removeNextProjection(file: File) {
        val filtered = file.readText().lines().filterNot { line ->
            line.trimStart().startsWith("nextCoreItem:") ||
                line.trimStart().startsWith("nextCoreItemName:") ||
                line.trimStart().startsWith("nextCoreItemStatus:")
        }
        file.writeText(filtered.joinToString("\n").trimEnd() + "\n")
    }

    private fun setClosureStatus(file: File, status: String) {
        replaceScalar(file, "closureItemStatus", status)
    }

    private fun replaceScalar(file: File, key: String, value: String) {
        val pattern = Regex("(?m)^(\\s*${Regex.escape(key)}:\\s*)[^\\n#]+(\\s*)$")
        val text = file.readText()
        val match = pattern.find(text) ?: error("$key missing in ${file.path}")
        file.writeText(text.replaceRange(match.range, match.groupValues[1] + "\"$value\"" + match.groupValues[2]))
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
