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
    val reportVersion: String = "1.2",
    val implementationPackageVersion: String = FlowStandardVersions.IMPLEMENTATION_PACKAGE_VERSION,
    val publicStandardVersion: String = FlowStandardVersions.FLOW_STANDARD_VERSION,
    val completedCorrectionItem: String,
    val correctionStatus: String,
    val parentCoreItemStatus: String,
    val nextCoreItem: String,
    val closureStatus: String,
    val status: String,
    val checks: List<ReleaseMetadataHonestyCheck>,
    val failedChecks: List<String>
)

/**
 * Reconciles package, public-standard, Core-roadmap and bounded-correction axes.
 *
 * The correction identity is selected from the roadmap while work is active and
 * from the newest bounded work package after completion. This keeps the authority
 * usable for the correction track instead of hard-coding one historical item.
 */
class ReleaseMetadataHonestyAuthority(private val rootDir: File = File(".")) {
    fun analyze(): ReleaseMetadataHonestyReport {
        val releaseStateFile = File(rootDir, ".flow-agent/release-state.yaml")
        val roadmapFile = File(rootDir, ".flow-agent/roadmap.yaml")
        val coreRoadmapFile = File(rootDir, ".flow-agent/roadmap-core-v0.9.7.9.yaml")
        val reportFile = File(rootDir, "REPORT.md")
        val changelogFile = File(rootDir, "CHANGELOG-v0.9.7.9.md")
        val gradleFile = File(rootDir, "build.gradle.kts")

        val releaseState = requiredYaml(releaseStateFile)
        val roadmap = requiredYaml(roadmapFile)
        val coreRoadmapText = requiredText(coreRoadmapFile)
        val reportText = requiredText(reportFile)
        val changelogText = requiredText(changelogFile)
        val gradleText = requiredText(gradleFile)

        val packageVersion = FlowStandardVersions.IMPLEMENTATION_PACKAGE_VERSION
        val standardVersion = FlowStandardVersions.FLOW_STANDARD_VERSION
        val parentItem = "0.9.7.9"
        val parentName = "Intent Lowering and Diagnostic Honesty"
        val nextItem = "0.9.7.10"
        val nextName = "Bounded Semantic Closure Gate"

        val selectedPointer = roadmap.string("currentDecision", "activeCorrectionWorkPackage")
        val workPackageFile = selectCorrectionWorkPackage(selectedPointer)
        val correctionPath = workPackageFile.relativeTo(rootDir).invariantSeparatorsPath
        val workPackage = requiredYaml(workPackageFile)
        val correctionItem = correctionVersionFromPath(correctionPath)
        val correctionName = roadmap.string("currentDecision", "activeCorrectionWorkPackageName")
            .ifBlank { workPackage.string("name") }
        val correctionStatus = workPackage.string("status")
        val lifecycleValid = correctionStatus in setOf("active", "complete")
        val expectedParentStatus = if (correctionStatus == "complete") "completed" else "correction-required"
        val expectedClosureStatus = if (correctionStatus == "complete") "next" else "blocked"
        val expectedCorrectionState = if (correctionStatus == "complete") "complete" else "active"
        val expectedActivePointer = if (correctionStatus == "active") correctionPath else ""

        val parentStatus = roadmapItemStatus(coreRoadmapText, parentItem)
        val closureStatus = roadmapItemStatus(coreRoadmapText, nextItem)
        val validationSource = releaseState.string("lastKnownValidation", "validationSource")
        val validationNotes = releaseState.string("lastKnownValidation", "notes")

        val checks = buildList {
            add(booleanCheck(
                id = "release.correction.lifecycle-status",
                passed = lifecycleValid,
                observed = correctionStatus,
                source = workPackageFile.path,
                message = "A bounded correction must be either active or complete."
            ))
            add(equalsCheck(
                "release.package.gradle",
                packageVersion,
                Regex("(?m)^version\\s*=\\s*\"([^\"]+)\"").find(gradleText)?.groupValues?.get(1).orEmpty(),
                gradleFile.path,
                "Gradle package version must match the typed implementation package version."
            ))
            add(equalsCheck(
                "release.package.state-current",
                packageVersion,
                releaseState.string("currentVersion"),
                releaseStateFile.path,
                "Release-state currentVersion must describe the published package axis."
            ))
            add(equalsCheck(
                "release.package.state-published",
                packageVersion,
                releaseState.string("versionBoundary", "publishedPackageVersion"),
                releaseStateFile.path,
                "Release-state publishedPackageVersion must match the package axis."
            ))
            add(equalsCheck(
                "release.standard.state-active",
                standardVersion,
                releaseState.string("activeStandardVersion"),
                releaseStateFile.path,
                "Release-state active standard version must match the typed public standard version."
            ))
            add(equalsCheck(
                "release.standard.state-boundary",
                standardVersion,
                releaseState.string("versionBoundary", "publicStandardVersion"),
                releaseStateFile.path,
                "Release-state public standard boundary must match the typed public standard version."
            ))
            add(containsCheck(
                "release.report.package",
                reportText,
                "Current published package line: `$packageVersion`",
                reportFile.path,
                "REPORT.md must state the current package line explicitly."
            ))
            add(containsCheck(
                "release.report.standard",
                reportText,
                "Active public standard version: `$standardVersion`",
                reportFile.path,
                "REPORT.md must state the public standard axis explicitly."
            ))
            add(equalsCheck(
                "release.state.parent-item",
                parentItem,
                releaseState.string("roadmapState", "completedItem"),
                releaseStateFile.path,
                "Release state must retain the parent Core item identity during correction."
            ))
            add(equalsCheck(
                "release.state.parent-name",
                parentName,
                releaseState.string("roadmapState", "completedItemName"),
                releaseStateFile.path,
                "Release state must retain the parent Core item name."
            ))
            add(equalsCheck(
                "release.roadmap.parent-item",
                parentItem,
                roadmap.string("currentDecision", "completedItem"),
                roadmapFile.path,
                "The roadmap index must reference the parent Core item, not the correction id."
            ))
            add(equalsCheck(
                "release.roadmap.parent-name",
                parentName,
                roadmap.string("currentDecision", "completedItemName"),
                roadmapFile.path,
                "The roadmap index must retain the parent item name."
            ))
            add(equalsCheck(
                "release.work-package.correction-item",
                correctionItem,
                workPackage.string("version"),
                workPackageFile.path,
                "The bounded correction identity must match its selected work-package path."
            ))
            add(equalsCheck(
                "release.work-package.correction-name",
                correctionName,
                workPackage.string("name"),
                workPackageFile.path,
                "The bounded correction work package must carry the correction name."
            ))
            add(equalsCheck(
                "release.work-package.parent",
                parentItem,
                workPackage.string("parentCoreItem"),
                workPackageFile.path,
                "The correction must name its parent Core item explicitly."
            ))
            add(equalsCheck(
                "release.roadmap.correction-state",
                expectedCorrectionState,
                roadmap.string("currentDecision", "correctionState"),
                roadmapFile.path,
                "The roadmap correction state must match the work package lifecycle."
            ))
            add(equalsCheck(
                "release.roadmap.active-correction-pointer",
                expectedActivePointer,
                selectedPointer,
                roadmapFile.path,
                "Only an active correction may be selected as the current work package."
            ))
            add(equalsCheck(
                "release.core.parent-status",
                expectedParentStatus,
                parentStatus,
                coreRoadmapFile.path,
                "The parent Core item status must match the correction lifecycle."
            ))
            add(equalsCheck(
                "release.core.closure-status",
                expectedClosureStatus,
                closureStatus,
                coreRoadmapFile.path,
                "The closure item must remain blocked until the correction is complete."
            ))
            add(equalsCheck(
                "release.roadmap.closure-status",
                expectedClosureStatus,
                roadmap.string("currentDecision", "nextCoreItemStatus"),
                roadmapFile.path,
                "The roadmap index must expose the same closure status as the Core roadmap."
            ))
            add(equalsCheck(
                "release.state.next-item",
                nextItem,
                releaseState.string("roadmapState", "nextCoreItem"),
                releaseStateFile.path,
                "Release state must retain the bounded closure item identity."
            ))
            add(equalsCheck(
                "release.roadmap.next-item",
                nextItem,
                roadmap.string("currentDecision", "nextCoreItem"),
                roadmapFile.path,
                "The roadmap index and release state must agree on the closure item."
            ))

            val correctionLabel = if (correctionStatus == "complete") "Completed correction item" else "Active correction item"
            add(containsCheck(
                "release.report.correction",
                reportText,
                "$correctionLabel: `$correctionItem $correctionName`",
                reportFile.path,
                "REPORT.md must expose the bounded correction lifecycle."
            ))
            add(containsCheck(
                "release.report.parent-status",
                reportText,
                "Core roadmap item status: `$expectedParentStatus`",
                reportFile.path,
                "REPORT.md must expose the parent Core item status."
            ))
            add(containsCheck(
                "release.report.closure-status",
                reportText,
                "Next Core roadmap item: `$nextItem $nextName` (`$expectedClosureStatus`)",
                reportFile.path,
                "REPORT.md must expose whether closure is blocked or next."
            ))

            val correctionOrdinal = correctionItem.substringAfterLast('.').toIntOrNull() ?: 0
            (1..correctionOrdinal).forEach { item ->
                val id = "0.9.7.9.$item"
                add(containsCheck(
                    "release.changelog.$id",
                    changelogText,
                    "### v$id ",
                    changelogFile.path,
                    "The bounded correction changelog must record every work item exactly once."
                ))
            }

            add(booleanCheck(
                "release.validation.last-merged-run",
                Regex("Flow CI #\\d+").containsMatchIn(validationSource),
                validationSource,
                releaseStateFile.path,
                "Committed metadata must identify an already-merged Flow CI run."
            ))
            add(booleanCheck(
                "release.validation.last-merged-head",
                Regex("\\b[0-9a-f]{40}\\b").containsMatchIn(validationSource),
                validationSource,
                releaseStateFile.path,
                "Committed metadata must identify the exact already-merged validation head."
            ))
            add(containsCheck(
                "release.validation.external-candidate-policy",
                validationNotes,
                "external exact-head CI evidence",
                releaseStateFile.path,
                "Candidate validation must remain external evidence until the candidate head passes."
            ))

            val activeRoadmap = releaseState.string("roadmapState", "activeRoadmaps", "core")
            add(booleanCheck(
                "release.roadmap.active-file",
                File(rootDir, activeRoadmap).isFile,
                activeRoadmap,
                releaseStateFile.path,
                "The active Core roadmap path must resolve to a repository file."
            ))
        }

        val failed = checks.filter { it.status == "FAIL" }.map { it.id }
        return ReleaseMetadataHonestyReport(
            completedCorrectionItem = correctionItem,
            correctionStatus = correctionStatus,
            parentCoreItemStatus = parentStatus,
            nextCoreItem = nextItem,
            closureStatus = closureStatus,
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

    private fun selectCorrectionWorkPackage(activePointer: String): File {
        if (activePointer.isNotBlank()) {
            val selected = File(rootDir, activePointer)
            require(selected.isFile) { "Active bounded correction work package is missing: $activePointer" }
            return selected
        }
        val directory = File(rootDir, ".flow-agent/work-packages")
        val candidates = directory.listFiles().orEmpty()
            .filter { it.isFile && CORRECTION_FILE.matches(it.name) }
        return candidates.maxWithOrNull { left, right ->
            compareVersionKeys(versionKey(left.name), versionKey(right.name))
        } ?: error("No bounded correction work package exists for v0.9.7.9.x")
    }

    private fun correctionVersionFromPath(path: String): String =
        CORRECTION_FILE.find(File(path).name)?.groupValues?.get(1)
            ?: error("Selected correction path does not carry a v0.9.7.9.x identity: $path")

    private fun versionKey(name: String): List<Int> =
        CORRECTION_FILE.find(name)?.groupValues?.get(1)
            ?.split('.')
            ?.map { it.toInt() }
            ?: emptyList()

    private fun compareVersionKeys(left: List<Int>, right: List<Int>): Int {
        val size = maxOf(left.size, right.size)
        for (index in 0 until size) {
            val comparison = (left.getOrElse(index) { 0 }).compareTo(right.getOrElse(index) { 0 })
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
        val resolved = current
        return when (resolved) {
            is Iterable<*> -> resolved.joinToString("\n") { it.toString() }
            else -> resolved?.toString().orEmpty()
        }
    }

    private fun roadmapItemStatus(text: String, version: String): String {
        val blocks = Regex("(?m)^\\s*-\\s+version:\\s*[\"']?([^\"'\\n]+)[\"']?\\s*$")
            .findAll(text)
            .toList()
        val match = blocks.firstOrNull { it.groupValues[1].trim() == version } ?: return ""
        val start = match.range.last + 1
        val next = blocks.firstOrNull { it.range.first > match.range.first }?.range?.first ?: text.length
        return Regex("(?m)^\\s+status:\\s*[\"']?([^\"'\\n#]+)")
            .find(text.substring(start, next))
            ?.groupValues?.get(1)?.trim().orEmpty()
    }

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
        private val CORRECTION_FILE = Regex("v(0\\.9\\.7\\.9\\.\\d+)-.+\\.ya?ml")
    }
}
