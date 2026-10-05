package org.flowlang.release

import java.io.File
import org.flowlang.serialization.FlowYaml
import org.flowlang.standard.FlowStandardVersions

data class ReleaseMetadataHonestyCheck(
    val id: String,
    val status: String,
    val expected: String,
    val observed: String,
    val source: String,
    val message: String
)

data class ReleaseMetadataHonestyReport(
    val reportVersion: String = "1.5",
    val implementationPackageVersion: String = FlowStandardVersions.IMPLEMENTATION_PACKAGE_VERSION,
    val publicStandardVersion: String = FlowStandardVersions.FLOW_STANDARD_VERSION,
    val completedCorrectionItem: String,
    val correctionStatus: String,
    val parentCoreItemStatus: String,
    val closureItem: String,
    val closureWorkPackageStatus: String,
    val closurePhase: String,
    val closureStatus: String,
    val coreTrackStatus: String,
    val completedCoreItem: String,
    @get:com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.ALWAYS)
    val nextCoreItem: String?,
    val status: String,
    val checks: List<ReleaseMetadataHonestyCheck>,
    val failedChecks: List<String>
)

/**
 * Reconciles package, public-standard, Core-roadmap, bounded-correction and
 * bounded-closure axes.
 *
 * closureItem is permanent identity. nextCoreItem is a phase projection and is
 * present only while the closure item is genuinely READY/next. A reopened or
 * completed track therefore cannot describe the closure identity as future work.
 */
class ReleaseMetadataHonestyAuthority(private val rootDir: File = File(".")) {
    fun analyze(): ReleaseMetadataHonestyReport {
        val releaseStateFile = File(rootDir, RELEASE_STATE)
        val roadmapFile = File(rootDir, ROADMAP)
        val coreRoadmapFile = File(rootDir, CORE_ROADMAP)
        val closureWorkPackageFile = File(rootDir, CLOSURE_WORK_PACKAGE)
        val reportFile = File(rootDir, REPORT)
        val gradleFile = File(rootDir, "build.gradle.kts")

        val releaseState = requiredYaml(releaseStateFile)
        val roadmap = requiredYaml(roadmapFile)
        val coreRoadmap = requiredYaml(coreRoadmapFile)
        val closureWorkPackage = requiredYaml(closureWorkPackageFile)
        val reportText = requiredText(reportFile)
        val gradleText = requiredText(gradleFile)

        val packageVersion = FlowStandardVersions.IMPLEMENTATION_PACKAGE_VERSION
        val standardVersion = FlowStandardVersions.FLOW_STANDARD_VERSION
        val closureItem = "0.9.7.10"
        val closureName = "Bounded Semantic Closure Gate"
        val preClosureItem = "0.9.7.9"
        val preClosureName = "Intent Lowering and Diagnostic Honesty"

        val selectedPointer = roadmap.string("currentDecision", "activeCorrectionWorkPackage")
        val correctionFile = selectCorrectionWorkPackage(selectedPointer)
        val correctionPath = correctionFile.relativeTo(rootDir).invariantSeparatorsPath
        val correction = requiredYaml(correctionFile)
        val correctionItem = correction.string("version")
        val correctionName = roadmap.string("currentDecision", "activeCorrectionWorkPackageName")
            .ifBlank { correction.string("name") }
        val correctionStatus = correction.string("status")
        val correctionTerminal = correctionStatus in TERMINAL_CORRECTION_STATUSES
        val parentItem = correction.string("parentCoreItem")
        val parentName = coreRoadmap.itemName(parentItem)
        val parentStatus = coreRoadmap.itemStatus(parentItem)
        val closureWorkStatus = closureWorkPackage.string("status")
        val closureStatus = coreRoadmap.itemStatus(closureItem)
        val coreTrackStatus = coreRoadmap.string("status")
        val readyParentStatus = if (parentItem == closureItem) "next" else "completed"

        val closurePhase = when {
            correctionStatus == "active" &&
                parentItem == closureItem &&
                closureWorkStatus == "correction-required" &&
                parentStatus == "correction-required" &&
                closureStatus == "correction-required" &&
                coreTrackStatus == "active" -> "CORRECTION_REQUIRED"
            correctionTerminal &&
                closureWorkStatus == "active" &&
                parentStatus == readyParentStatus &&
                closureStatus == "next" &&
                coreTrackStatus == "active" -> "READY"
            correctionTerminal &&
                closureWorkStatus == "complete" &&
                parentStatus == "completed" &&
                closureStatus == "completed" &&
                coreTrackStatus == "completed" -> "CLOSED"
            else -> "INVALID"
        }

        val expectedClosureStatus = when (closurePhase) {
            "CORRECTION_REQUIRED" -> "correction-required"
            "READY" -> "next"
            "CLOSED" -> "completed"
            else -> closureStatus
        }
        val expectedParentStatus = when (closurePhase) {
            "CORRECTION_REQUIRED" -> "correction-required"
            "READY" -> readyParentStatus
            "CLOSED" -> "completed"
            else -> parentStatus
        }
        val expectedTrackStatus = if (closurePhase == "CLOSED") "completed" else "active"
        val expectedCompletedItem = if (closurePhase == "CLOSED") closureItem else preClosureItem
        val expectedCompletedName = if (closurePhase == "CLOSED") closureName else preClosureName
        val expectedActivePointer = if (correctionStatus == "active") correctionPath else ""
        val expectedNextItem = if (closurePhase == "READY") closureItem else ""
        val expectedNextName = if (closurePhase == "READY") closureName else ""
        val expectedNextStatus = if (closurePhase == "READY") "next" else ""

        val validationSource = releaseState.string("lastKnownValidation", "validationSource")
        val validationNotes = releaseState.string("lastKnownValidation", "notes")
        val correctionChangelogFile = File(
            rootDir,
            if (correctionItem.startsWith("0.9.7.10.")) "CHANGELOG-v0.9.7.10.md" else "CHANGELOG-v0.9.7.9.md"
        )
        val correctionChangelogText = requiredText(correctionChangelogFile)

        val checks = mutableListOf<ReleaseMetadataHonestyCheck>()
        fun equal(id: String, expected: String, observed: String, source: File, message: String) {
            checks += equalsCheck(id, expected, observed, source.path, message)
        }
        fun boolean(id: String, passed: Boolean, observed: String, source: File, message: String) {
            checks += booleanCheck(id, passed, observed, source.path, message)
        }
        fun contains(id: String, observed: String, fragment: String, source: File, message: String) {
            checks += containsCheck(id, observed, fragment, source.path, message)
        }

        boolean(
            "release.correction.lifecycle-status",
            correctionStatus == "active" || correctionTerminal,
            correctionStatus,
            correctionFile,
            "A bounded correction must be active or explicitly terminal."
        )
        boolean(
            "release.closure.lifecycle-status",
            closureWorkStatus in setOf("correction-required", "active", "complete"),
            closureWorkStatus,
            closureWorkPackageFile,
            "The bounded closure work package must declare correction-required, active or complete."
        )
        boolean(
            "release.closure.phase",
            closurePhase in VALID_PHASES,
            closurePhase,
            closureWorkPackageFile,
            "Closure metadata must form one exact CORRECTION_REQUIRED, READY or CLOSED lifecycle state."
        )
        equal(
            "release.package.gradle",
            packageVersion,
            Regex("(?m)^version\\s*=\\s*\"([^\"]+)\"").find(gradleText)?.groupValues?.get(1).orEmpty(),
            gradleFile,
            "Gradle package version must match the typed implementation package version."
        )
        equal("release.package.state-current", packageVersion, releaseState.string("currentVersion"), releaseStateFile,
            "Release-state currentVersion must describe the published package axis.")
        equal("release.package.state-published", packageVersion,
            releaseState.string("versionBoundary", "publishedPackageVersion"), releaseStateFile,
            "Release-state publishedPackageVersion must match the package axis.")
        equal("release.standard.state-active", standardVersion, releaseState.string("activeStandardVersion"),
            releaseStateFile, "Release-state active standard version must match the typed public standard version.")
        equal("release.standard.state-boundary", standardVersion,
            releaseState.string("versionBoundary", "publicStandardVersion"), releaseStateFile,
            "Release-state public standard boundary must match the typed public standard version.")
        FlowStandardVersions.ARTIFACT_CONTRACT_VERSIONS.forEach { (contract, version) ->
            equal(
                "release.artifact.state-$contract",
                version,
                releaseState.string("versionBoundary", "artifactContracts", contract),
                releaseStateFile,
                "Release-state $contract contract version must match the typed artifact contract authority."
            )
            equal(
                "release.artifact.roadmap-$contract",
                version,
                roadmap.string("versionBoundary", "artifactContracts", contract),
                roadmapFile,
                "Roadmap $contract contract version must match the typed artifact contract authority."
            )
        }
        boolean(
            "release.artifact.state-no-global-version",
            "artifactContractVersion" !in releaseState.map("versionBoundary"),
            releaseState.string("versionBoundary", "artifactContractVersion").ifBlank { "absent" },
            releaseStateFile,
            "Diverged artifact contracts must not be collapsed into one global release-state version."
        )
        boolean(
            "release.artifact.roadmap-no-global-version",
            "artifactContractVersion" !in roadmap.map("versionBoundary"),
            roadmap.string("versionBoundary", "artifactContractVersion").ifBlank { "absent" },
            roadmapFile,
            "Diverged artifact contracts must not be collapsed into one global roadmap version."
        )
        contains("release.report.package", reportText, "Current published package line: `$packageVersion`", reportFile,
            "REPORT.md must state the current package line explicitly.")
        contains("release.report.standard", reportText, "Active public standard version: `$standardVersion`", reportFile,
            "REPORT.md must state the public standard axis explicitly.")

        equal("release.state.completed-item", expectedCompletedItem,
            releaseState.string("roadmapState", "completedItem"), releaseStateFile,
            "Release state must expose the latest completed Core item.")
        equal("release.state.completed-name", expectedCompletedName,
            releaseState.string("roadmapState", "completedItemName"), releaseStateFile,
            "Release state must expose the latest completed Core item name.")
        equal("release.roadmap.completed-item", expectedCompletedItem,
            roadmap.string("currentDecision", "completedItem"), roadmapFile,
            "The roadmap index must expose the latest completed Core item.")
        equal("release.roadmap.completed-name", expectedCompletedName,
            roadmap.string("currentDecision", "completedItemName"), roadmapFile,
            "The roadmap index must expose the latest completed Core item name.")

        equal("release.work-package.correction-item", correctionVersionFromPath(correctionPath), correctionItem,
            correctionFile, "The bounded correction identity must match its selected work-package path.")
        equal("release.work-package.correction-name", correctionName, correction.string("name"), correctionFile,
            "The bounded correction work package must carry the correction name.")
        boolean("release.work-package.parent", parentItem.isNotBlank() && parentName.isNotBlank(),
            "$parentItem:$parentName", correctionFile,
            "The correction must name an existing parent Core item explicitly.")
        equal("release.work-package.closure-item", closureItem, closureWorkPackage.string("version"),
            closureWorkPackageFile, "The closure work package must carry the bounded closure identity.")
        equal("release.work-package.closure-name", closureName, closureWorkPackage.string("name"),
            closureWorkPackageFile, "The closure work package must carry the bounded closure name.")

        equal("release.roadmap.correction-state", if (correctionStatus == "active") "active" else "complete",
            roadmap.string("currentDecision", "correctionState"), roadmapFile,
            "The roadmap correction state must match the correction work-package lifecycle.")
        equal("release.roadmap.active-correction-pointer", expectedActivePointer, selectedPointer, roadmapFile,
            "Only an active correction may be selected as the current correction work package.")

        equal("release.core.parent-status", expectedParentStatus, parentStatus, coreRoadmapFile,
            "The corrected parent status must match the explicit lifecycle.")
        equal("release.core.closure-status", expectedClosureStatus, closureStatus, coreRoadmapFile,
            "The closure item status must match the explicit closure phase.")
        equal("release.core.track-status", expectedTrackStatus, coreTrackStatus, coreRoadmapFile,
            "The Core track may become completed only in the CLOSED phase.")

        equal("release.roadmap.closure-item", closureItem,
            roadmap.string("currentDecision", "closureItem"), roadmapFile,
            "The roadmap index must carry an explicit closure item identity.")
        equal("release.roadmap.closure-name", closureName,
            roadmap.string("currentDecision", "closureItemName"), roadmapFile,
            "The roadmap index must carry an explicit closure item name.")
        equal("release.roadmap.closure-status", expectedClosureStatus,
            roadmap.string("currentDecision", "closureItemStatus"), roadmapFile,
            "The roadmap index closure status must match the Core roadmap.")
        equal("release.state.closure-item", closureItem,
            releaseState.string("roadmapState", "closureItem"), releaseStateFile,
            "Release state must carry an explicit closure item identity.")
        equal("release.state.closure-name", closureName,
            releaseState.string("roadmapState", "closureItemName"), releaseStateFile,
            "Release state must carry an explicit closure item name.")
        equal("release.state.closure-status", expectedClosureStatus,
            releaseState.string("roadmapState", "closureItemStatus"), releaseStateFile,
            "Release state closure status must match the Core roadmap.")

        equal("release.roadmap.next-item", expectedNextItem,
            roadmap.string("currentDecision", "nextCoreItem"), roadmapFile,
            "The roadmap may expose a next Core item only in READY.")
        equal("release.roadmap.next-name", expectedNextName,
            roadmap.string("currentDecision", "nextCoreItemName"), roadmapFile,
            "The roadmap may expose a next Core item name only in READY.")
        equal("release.roadmap.next-status", expectedNextStatus,
            roadmap.string("currentDecision", "nextCoreItemStatus"), roadmapFile,
            "The roadmap may expose next status only in READY.")
        equal("release.state.next-item", expectedNextItem,
            releaseState.string("roadmapState", "nextCoreItem"), releaseStateFile,
            "Release state may expose a next Core item only in READY.")
        equal("release.state.next-name", expectedNextName,
            releaseState.string("roadmapState", "nextCoreItemName"), releaseStateFile,
            "Release state may expose a next Core item name only in READY.")
        equal("release.state.next-status", expectedNextStatus,
            releaseState.string("roadmapState", "nextCoreItemStatus"), releaseStateFile,
            "Release state may expose next status only in READY.")

        boolean(
            "release.closure.phase-alignment",
            closurePhase in VALID_PHASES,
            "correction=$correctionStatus, parentItem=$parentItem, parent=$parentStatus, " +
                "closureWork=$closureWorkStatus, closure=$closureStatus, track=$coreTrackStatus",
            closureWorkPackageFile,
            "Closure may reopen only through CORRECTION_REQUIRED and may close only after READY."
        )
        boolean(
            "release.closure.implementation-evidence",
            closureImplementationEvidenceValid(closureWorkPackage, closureWorkStatus, correctionItem),
            closureWorkPackage.string("validationEvidence", "status").ifBlank { closureWorkStatus },
            closureWorkPackageFile,
            "Closure evidence must be superseded during correction, absent while READY and structurally passing when CLOSED."
        )

        val correctionLabel = if (correctionStatus == "active") "Active correction item" else "Completed correction item"
        contains("release.report.correction", reportText, "$correctionLabel: `$correctionItem $correctionName`",
            reportFile, "REPORT.md must expose the bounded correction lifecycle.")
        contains("release.report.parent-status", reportText, "Core roadmap item status: `$expectedParentStatus`",
            reportFile, "REPORT.md must expose the corrected parent Core item status.")
        val closureReportFragment = when (closurePhase) {
            "CORRECTION_REQUIRED" -> "Core closure correction: `$closureItem $closureName` (`correction-required`)"
            "READY" -> "Next Core roadmap item: `$closureItem $closureName` (`next`)"
            "CLOSED" -> "Completed Core closure item: `$closureItem $closureName` (`completed`)"
            else -> "INVALID"
        }
        contains("release.report.closure-status", reportText, closureReportFragment, reportFile,
            "REPORT.md must expose whether closure is CORRECTION_REQUIRED, READY or CLOSED.")
        contains("release.changelog.$correctionItem", correctionChangelogText, "### v$correctionItem ",
            correctionChangelogFile, "The bounded correction changelog must record the selected work item.")

        boolean("release.validation.last-merged-run", Regex("Flow CI #\\d+").containsMatchIn(validationSource),
            validationSource, releaseStateFile,
            "Committed metadata must identify an already-merged Flow CI run.")
        boolean("release.validation.last-merged-head", Regex("\\b[0-9a-f]{40}\\b").containsMatchIn(validationSource),
            validationSource, releaseStateFile,
            "Committed metadata must identify the exact already-merged validation head.")
        contains("release.validation.external-candidate-policy", validationNotes, "external exact-head CI evidence",
            releaseStateFile,
            "Candidate validation must remain external evidence until the candidate head passes.")

        val activeRoadmap = releaseState.string("roadmapState", "activeRoadmaps", "core")
        boolean("release.roadmap.active-file", File(rootDir, activeRoadmap).isFile, activeRoadmap,
            releaseStateFile, "The active Core roadmap path must resolve to a repository file.")

        val failed = checks.filter { it.status == "FAIL" }.map { it.id }
        return ReleaseMetadataHonestyReport(
            completedCorrectionItem = correctionItem,
            correctionStatus = correctionStatus,
            parentCoreItemStatus = parentStatus,
            closureItem = closureItem,
            closureWorkPackageStatus = closureWorkStatus,
            closurePhase = closurePhase,
            closureStatus = closureStatus,
            coreTrackStatus = coreTrackStatus,
            completedCoreItem = roadmap.string("currentDecision", "completedItem"),
            nextCoreItem = expectedNextItem.ifBlank { null },
            status = if (failed.isEmpty()) "PASS" else "FAIL",
            checks = checks,
            failedChecks = failed
        )
    }

    fun requireValid(): ReleaseMetadataHonestyReport = analyze().also { report ->
        require(report.status == "PASS") {
            "Release metadata honesty failed: ${report.failedChecks.joinToString()}"
        }
    }

    private fun closureImplementationEvidenceValid(
        closureWorkPackage: Map<String, Any?>,
        closureWorkStatus: String,
        correctionItem: String
    ): Boolean {
        val evidence = closureWorkPackage.map("validationEvidence")
        return when (closureWorkStatus) {
            "correction-required" ->
                closureWorkPackage.string("supersededByCorrection") == correctionItem &&
                    evidence.string("status") == "passed"
            "active" -> evidence.isEmpty() || evidence.string("status").isBlank()
            "complete" -> {
                val exactHead = evidence.string("exactHead")
                val mergeCandidate = evidence.string("mergeCandidate")
                evidence.string("status") == "passed" &&
                    evidence.string("workflow") == "Flow CI" &&
                    evidence.string("runNumber").toIntOrNull()?.let { it > 0 } == true &&
                    evidence.string("runId").toLongOrNull()?.let { it > 0 } == true &&
                    SHA.matches(exactHead) &&
                    SHA.matches(mergeCandidate) &&
                    exactHead != mergeCandidate
            }
            else -> false
        }
    }

    private fun selectCorrectionWorkPackage(activePointer: String): File {
        if (activePointer.isNotBlank()) {
            val selected = File(rootDir, activePointer)
            require(selected.isFile) { "Active bounded correction work package is missing: $activePointer" }
            return selected
        }
        return correctionWorkPackages().maxWithOrNull { left, right ->
            compareVersionKeys(versionKey(left), versionKey(right))
        } ?: error("No bounded correction work package exists for the v0.9.7 track")
    }

    private fun correctionWorkPackages(): List<File> =
        File(rootDir, ".flow-agent/work-packages").listFiles().orEmpty()
            .filter { it.isFile && it.extension in setOf("yaml", "yml") }
            .filter { file -> runCatching { requiredYaml(file).string("type") == "bounded-correction" }.getOrDefault(false) }
            .filter { file -> BOUNDED_CORRECTION_VERSION.matches(requiredYaml(file).string("version")) }

    private fun correctionVersionFromPath(path: String): String =
        CORRECTION_FILE.find(File(path).name)?.groupValues?.get(1)
            ?: error("Selected correction path does not carry a v0.9.7.x bounded-correction identity: $path")

    private fun versionKey(file: File): List<Int> = requiredYaml(file).string("version")
        .split('.')
        .mapNotNull(String::toIntOrNull)

    private fun compareVersionKeys(left: List<Int>, right: List<Int>): Int {
        repeat(maxOf(left.size, right.size)) { index ->
            val comparison = left.getOrElse(index) { 0 }.compareTo(right.getOrElse(index) { 0 })
            if (comparison != 0) return comparison
        }
        return 0
    }

    private fun requiredText(file: File): String {
        require(file.isFile) { "Required release metadata file is missing: ${file.path}" }
        return file.readText()
    }

    private fun requiredYaml(file: File): Map<String, Any?> = FlowYaml.readMap(requiredText(file), file.path)

    private fun Map<String, Any?>.string(vararg path: String): String {
        var current: Any? = this
        path.forEach { key -> current = (current as? Map<*, *>)?.get(key) }
        return when (val resolved = current) {
            is Iterable<*> -> resolved.joinToString("\n") { it.toString() }
            else -> resolved?.toString().orEmpty()
        }
    }

    private fun Map<String, Any?>.map(vararg path: String): Map<String, Any?> {
        var current: Any? = this
        path.forEach { key -> current = (current as? Map<*, *>)?.get(key) }
        return (current as? Map<*, *>)
            ?.entries
            ?.associate { it.key.toString() to it.value }
            .orEmpty()
    }

    private fun Map<String, Any?>.mapList(key: String): List<Map<String, Any?>> =
        (this[key] as? Iterable<*>)?.mapNotNull { value ->
            (value as? Map<*, *>)?.entries?.associate { it.key.toString() to it.value }
        }.orEmpty()

    private fun Map<String, Any?>.itemStatus(version: String): String =
        mapList("items").firstOrNull { it.string("version") == version }?.string("status").orEmpty()

    private fun Map<String, Any?>.itemName(version: String): String =
        mapList("items").firstOrNull { it.string("version") == version }?.string("name").orEmpty()

    private fun equalsCheck(
        id: String,
        expected: String,
        observed: String,
        source: String,
        message: String
    ): ReleaseMetadataHonestyCheck = ReleaseMetadataHonestyCheck(
        id = id,
        status = if (observed == expected) "PASS" else "FAIL",
        expected = expected,
        observed = observed,
        source = source,
        message = message
    )

    private fun containsCheck(
        id: String,
        observed: String,
        expectedFragment: String,
        source: String,
        message: String
    ): ReleaseMetadataHonestyCheck = ReleaseMetadataHonestyCheck(
        id = id,
        status = if (observed.contains(expectedFragment)) "PASS" else "FAIL",
        expected = "contains: $expectedFragment",
        observed = observed.take(320),
        source = source,
        message = message
    )

    private fun booleanCheck(
        id: String,
        passed: Boolean,
        observed: String,
        source: String,
        message: String
    ): ReleaseMetadataHonestyCheck = ReleaseMetadataHonestyCheck(
        id = id,
        status = if (passed) "PASS" else "FAIL",
        expected = "true",
        observed = observed,
        source = source,
        message = message
    )

    companion object {
        private const val RELEASE_STATE = ".flow-agent/release-state.yaml"
        private const val ROADMAP = ".flow-agent/roadmap.yaml"
        private const val CORE_ROADMAP = ".flow-agent/roadmap-core-v0.9.7.9.yaml"
        private const val CLOSURE_WORK_PACKAGE =
            ".flow-agent/work-packages/v0.9.7.10-bounded-semantic-closure-gate.yaml"
        private const val REPORT = "REPORT.md"
        private val CORRECTION_FILE = Regex("v(0\\.9\\.7\\.(?:9|10)\\.\\d+)-.+\\.ya?ml")
        private val BOUNDED_CORRECTION_VERSION = Regex("0\\.9\\.7\\.(?:9|10)\\.\\d+")
        private val SHA = Regex("[0-9a-f]{40}")
        private val TERMINAL_CORRECTION_STATUSES = setOf("complete", "completed")
        private val VALID_PHASES = setOf("CORRECTION_REQUIRED", "READY", "CLOSED")
    }
}
