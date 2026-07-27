import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.flowlang.cli.Json
import org.flowlang.release.ReleaseMetadataHonestyAuthority

class ReleaseMetadataClosedLifecycleTests {
    @Test
    fun structuredClosedLifecyclePasses() = withMetadataFixture { root ->
        closeMetadata(root, includeImplementationEvidence = true)

        val report = ReleaseMetadataHonestyAuthority(root).analyze()

        assertEquals("PASS", report.status, report.failedChecks.joinToString())
        assertEquals("CLOSED", report.closurePhase)
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
    fun completedClosureWithActiveCoreTrackFails() = withMetadataFixture { root ->
        closeMetadata(root, includeImplementationEvidence = true)
        val core = File(root, CORE_ROADMAP)
        core.writeText(updateCoreTrackStatus(core.readText(), "active"))

        val report = ReleaseMetadataHonestyAuthority(root).analyze()

        assertEquals("FAIL", report.status)
        assertTrue("release.closure.phase" in report.failedChecks, report.failedChecks.joinToString())
        assertTrue("release.core.track-status" in report.failedChecks, report.failedChecks.joinToString())
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
    fun activeClosureCannotPublishCompletedRoadmapStatus() = withMetadataFixture { root ->
        val core = File(root, CORE_ROADMAP)
        core.writeText(updateCoreClosureStatus(core.readText(), "completed"))
        val roadmap = File(root, ROADMAP)
        roadmap.writeText(
            roadmap.readText().replaceFirst(
                "nextCoreItemStatus: \"next\"",
                "nextCoreItemStatus: \"completed\""
            )
        )

        val report = ReleaseMetadataHonestyAuthority(root).analyze()

        assertEquals("FAIL", report.status)
        assertEquals("INVALID", report.closurePhase)
        assertTrue("release.closure.phase" in report.failedChecks)
    }

    @Test
    fun schemaMatchesRuntimeClosedLifecycleContract() {
        val report = ReleaseMetadataHonestyAuthority(File(".")).analyze()
        val schema = Json.mapper.readTree(File("schemas/release-metadata-honesty-report.schema.json"))
        val required = schema.path("required").map { it.asText() }.toSet()
        val closureStatuses = schema.path("properties").path("closureStatus").path("enum").map { it.asText() }

        assertEquals(report.reportVersion, schema.path("properties").path("reportVersion").path("const").asText())
        assertTrue("completed" in closureStatuses)
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

    private fun closeMetadata(root: File, includeImplementationEvidence: Boolean) {
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
        closure.writeText(
            closure.readText().replaceFirst("status: active", "status: complete").trimEnd() + evidence + "\n"
        )

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
                .replaceFirst(
                    "Next Core roadmap item: `0.9.7.10 Bounded Semantic Closure Gate` (`next`)",
                    "Completed Core closure item: `0.9.7.10 Bounded Semantic Closure Gate` (`completed`)"
                )
        )
    }

    private fun updateCoreTrackStatus(text: String, status: String): String {
        val pattern = Regex("(?m)^(status:\\s*)([a-z-]+)(\\s*)$")
        val match = pattern.find(text) ?: error("Core roadmap track status is missing.")
        val replacement = match.groupValues[1] + status + match.groupValues[3]
        return text.replaceRange(match.range, replacement)
    }

    private fun updateCoreClosureStatus(text: String, status: String): String {
        val pattern = Regex(
            "(?ms)(^\\s*- version:\\s*[\"']?0\\.9\\.7\\.10[\"']?\\s*$.*?^\\s*status:\\s*)([a-z-]+)(\\s*$)"
        )
        val match = pattern.find(text) ?: error("Closure roadmap item 0.9.7.10 is missing.")
        val replacement = match.groupValues[1] + status + match.groupValues[3]
        return text.replaceRange(match.range, replacement)
    }

    private fun withMetadataFixture(assertions: (File) -> Unit) {
        val root = Files.createTempDirectory("flow-release-closed-lifecycle").toFile()
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
        RELEASE_STATE,
        ROADMAP,
        CORE_ROADMAP,
        CLOSURE_WORK_PACKAGE,
        latestCorrectionPath()
    )

    private fun latestCorrectionPath(): String = File(".flow-agent/work-packages")
        .listFiles().orEmpty()
        .filter { it.isFile && CORRECTION_FILE.matches(it.name) }
        .maxWithOrNull { left, right -> compareVersionKeys(versionKey(left.name), versionKey(right.name)) }
        ?.relativeTo(File("."))
        ?.invariantSeparatorsPath
        ?: error("No bounded correction work package exists.")

    private fun versionKey(name: String): List<Int> = CORRECTION_FILE.find(name)
        ?.groupValues
        ?.get(1)
        ?.split('.')
        ?.map(String::toInt)
        .orEmpty()

    private fun compareVersionKeys(left: List<Int>, right: List<Int>): Int {
        repeat(maxOf(left.size, right.size)) { index ->
            val comparison = left.getOrElse(index) { 0 }.compareTo(right.getOrElse(index) { 0 })
            if (comparison != 0) return comparison
        }
        return 0
    }

    companion object {
        private const val REPORT = "REPORT.md"
        private const val RELEASE_STATE = ".flow-agent/release-state.yaml"
        private const val ROADMAP = ".flow-agent/roadmap.yaml"
        private const val CORE_ROADMAP = ".flow-agent/roadmap-core-v0.9.7.9.yaml"
        private const val CLOSURE_WORK_PACKAGE =
            ".flow-agent/work-packages/v0.9.7.10-bounded-semantic-closure-gate.yaml"
        private val CORRECTION_FILE = Regex("v(0\\.9\\.7\\.9\\.\\d+)-.+\\.yaml")
    }
}
