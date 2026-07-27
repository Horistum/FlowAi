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
    val reportVersion: String = "1.3",
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
    val nextCoreItem: String,
    val status: String,
    val checks: List<ReleaseMetadataHonestyCheck>,
    val failedChecks: List<String>
)

/**
 * Reconciles package, public-standard, Core-roadmap, bounded-correction and
 * bounded-closure axes.
 *
 * The correction identity remains historical after closure. The closure work
 * package owns only the READY to CLOSED transition; it cannot relabel the
 * correction item or publish a new package or public-standard version merely
 * because the paperwork has finally stopped moving.
 */
class ReleaseMetadataHonestyAuthority(private val rootDir: File = File(".")) {
    fun analyze(): ReleaseMetadataHonestyReport {
        val releaseStateFile = File(rootDir, ".flow-agent/release-state.yaml")
        val roadmapFile = File(rootDir, ".flow-agent/roadmap.yaml")
        val coreRoadmapFile = File(rootDir, ".flow-agent/roadmap-core-v0.9.7.9.yaml")
        val closureWorkPackageFile = File(
            rootDir,
            ".flow-agent/work-packages/v0.9.7.10-bounded-semantic-closure-gate.yaml"
        )
        val reportFile = File(rootDir, "REPORT.md")
        val changelogFile = File(rootDir, "CHANGELOG-v0.9.7.9.md")
        val gradleFile = File(rootDir, "build.gradle.kts")

        val releaseState = requiredYaml(releaseStateFile)
        val roadmap = requiredYaml(roadmapFile)
        val coreRoadmap = requiredYaml(coreRoadmapFile)
        val closureWorkPackage = requiredYaml(closureWorkPackageFile)
        val reportText = requiredText(reportFile)
        val changelogText = requiredText(changelogFile)
        val gradleText = requiredText(gradleFile)

        val packageVersion = FlowStandardVersions.IMPLEMENTATION_PACKAGE_VERSION
        val standardVersion = FlowStandardVersions.FLOW_STANDARD_VERSION
        val parentItem = "0.9.7.9"
        val parentName = "Intent Lowering and Diagnostic Honesty"
        val closureItem = "0.9.7.10"
        val closureName = "Bounded Semantic Closure Gate"

        val selectedPointer = roadmap.string("currentDecision", "activeCorrectionWorkPackage")
        val correctionWorkPackageFile = selectCorrectionWorkPackage(selectedPointer)
        val correctionPath = correctionWorkPackageFile.relativeTo(rootDir).invariantSeparatorsPath
        val correctionWorkPackage = requiredYaml(correctionWorkPackageFile)
        val correctionItem = correctionVersionFromPath(correctionPath)
        val correctionName = roadmap.string("currentDecision", "activeCorrectionWorkPackageName")
            .ifBlank { correctionWorkPackage.string("name") }
        val correctionStatus = correctionWorkPackage.string("status")
        val closureWorkStatus = closureWorkPackage.string("status")
        val parentStatus = coreRoadmap.itemStatus(parentItem)
        val closureStatus = coreRoadmap.itemStatus(closureItem)
        val coreTrackStatus = coreRoadmap.string("status")

        val closurePhase = when {
            correctionStatus == "complete" &&
                closureWorkStatus == "active" &&
                parentStatus == "completed" &&
                closureStatus == "next" &&
                coreTrackStatus == "active" -> "READY"
            correctionStatus == "complete" &&
                closureWorkStatus == "complete" &&
                parentStatus == "completed" &&
                closureStatus == "completed" &&
                coreTrackStatus == "completed" -> "CLOSED"
            else -> "INVALID"
        }
        val expectedClosureStatus = when (closurePhase) {
            "READY" -> "next"
            "CLOSED" -> "completed"
            else -> if (correctionStatus == "complete") closureStatus else "blocked"
        }
        val expectedTrackStatus = if (closurePhase == "CLOSED") "completed" else "active"
        val expectedCompletedItem = if (closurePhase == "CLOSED") closureItem else parentItem
        val expectedCompletedName = if (closurePhase == "CLOSED") closureName else parentName
        val expectedActivePointer = if (correctionStatus == "active") correctionPath else ""
        val validationSource = releaseState.string("lastKnownValidation", "validationSource")
        val validationNotes = releaseState.string("lastKnownValidation", "notes")

        val checks = buildList {
            add(booleanCheck(
                id = "release.correction.lifecycle-status",
                passed = correctionStatus in setOf("active", "complete"),
                observed = correctionStatus,
                source = correctionWorkPackageFile.path,
                message = "A bounded correction must be either active or complete."
            ))
            add(booleanCheck(
                id = "release.closure.lifecycle-status",
                passed = closureWorkStatus in setOf("active", "complete"),
                observed = closureWorkStatus,
                source = closureWorkPackageFile.path,
                message = "The bounded closure work package must be either active or complete."
            ))
            add(booleanCheck(
                id = "release.closure.phase",
                passed = closurePhase in setOf("READY", "CLOSED"),
                observed = closurePhase,
                source = closureWorkPackageFile.path,
                message = "Closure metadata must form one exact READY or CLOSED lifecycle state."
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
                "release.state.completed-item",
                expectedCompletedItem,
                releaseState.string("roadmapState", "completedItem"),
                releaseStateFile.path,
                "Release state must expose the latest completed Core item."
            ))
            add(equalsCheck(
                "release.state.completed-name",
                expectedCompletedName,
                releaseState.string("roadmapState", "completedItemName"),
                releaseStateFile.path,
                "Release state must expose the latest completed Core item name."
            ))
            add(equalsCheck(
                "release.roadmap.completed-item",
                expectedCompletedItem,
                roadmap.string("currentDecision", "completedItem"),
                roadmapFile.path,
                "The roadmap index must expose the latest completed Core item."
            ))
            add(equalsCheck(
                "release.roadmap.completed-name",
                expectedCompletedName,
                roadmap.string("currentDecision", "completedItemName"),
                roadmapFile.path,
                "The roadmap index must expose the latest completed Core item name."
            ))
            add(equalsCheck(
                "release.work-package.correction-item",
                correctionItem,
                correctionWorkPackage.string("version"),
                correctionWorkPackageFile.path,
                "The bounded correction identity must match its selected work-package path."
            ))
            add(equalsCheck(
                "release.work-package.correction-name",
                correctionName,
                correctionWorkPackage.string("name"),
                correctionWorkPackageFile.path,
                "The bounded correction work package must carry the correction name."
            ))
            add(equalsCheck(
                "release.work-package.parent",
                parentItem,
                correctionWorkPackage.string("parentCoreItem"),
                correctionWorkPackageFile.path,
                "The correction must name its parent Core item explicitly."
            ))
            add(equalsCheck(
                "release.work-package.closure-item",
                closureItem,
                closureWorkPackage.string("version"),
                closureWorkPackageFile.path,
                "The closure work package must carry the bounded closure identity."
            ))
            add(equalsCheck(
                "release.work-package.closure-name",
                closureName,
                closureWorkPackage.string("name"),
                closureWorkPackageFile.path,
                "The closure work package must carry the bounded closure name."
            ))
            add(equalsCheck(
                "release.roadmap.correction-state",
                if (correctionStatus == "complete") "complete" else "active",
                roadmap.string("currentDecision", "correctionState"),
                roadmapFile.path,
                "The roadmap correction state must match the correction work-package lifecycle."
            ))
            add(equalsCheck(
                "release.roadmap.active-correction-pointer",
                expectedActivePointer,
                selectedPointer,
                roadmapFile.path,
                "Only an active correction may be selected as the current correction work package."
            ))
            add(equalsCheck(
                "release.core.parent-status",
                if (correctionStatus == "complete") "completed" else "correction-required",
                parentStatus,
                coreRoadmapFile.path,
                "The parent Core item status must match the correction lifecycle."
            ))
            add(equalsCheck(
                "release.core.closure-status",
                expectedClosureStatus,
                closureStatus,
                coreRoadmapFile.path,
                "The closure item status must match the explicit closure phase."
            ))
            add(equalsCheck(
                "release.core.track-status",
                expectedTrackStatus,
                coreTrackStatus,
                coreRoadmapFile.path,
                "The Core track may become completed only in the CLOSED phase."
            ))
            add(equalsCheck(
                "release.roadmap.closure-status",
                expectedClosureStatus,
                roadmap.string("currentDecision", "nextCoreItemStatus"),
                roadmapFile.path,
                "The roadmap index must expose the same closure status as the Core roadmap."
            ))
            add(equalsCheck(
                "release.state.closure-item",
                closureItem,
                releaseState.string("roadmapState", "nextCoreItem"),
                releaseStateFile.path,
                "Release state must retain the bounded closure item identity."
            ))
            add(equalsCheck(
                "release.state.closure-name",
                closureName,
                releaseState.string("roadmapState", "nextCoreItemName"),
                releaseStateFile.path,
                "Release state must retain the bounded closure item name."
            ))
            add(equalsCheck(
                "release.state.bounded-closure-item",
                closureItem,
                releaseState.string("roadmapState", "boundedClosureItem"),
                releaseStateFile.path,
                "Release state must retain the explicit bounded closure identity."
            ))
            add(equalsCheck(
                "release.roadmap.closure-item",
                closureItem,
                roadmap.string("currentDecision", "nextCoreItem"),
                roadmapFile.path,
                "The roadmap index must retain the bounded closure item identity."
            ))
            add(equalsCheck(
                "release.roadmap.closure-name",
                closureName,
                roadmap.string("currentDecision", "nextCoreItemName"),
                roadmapFile.path,
                "The roadmap index must retain the bounded closure item name."
            ))
            add(booleanCheck(
                "release.closure.phase-alignment",
                closurePhase in setOf("READY", "CLOSED"),
                "correction=$correctionStatus, closureWork=$closureWorkStatus, parent=$parentStatus, " +
                    "closure=$closureStatus, track=$coreTrackStatus",
                closureWorkPackageFile.path,
                "Closure cannot start before corrections complete or close while any lifecycle axis remains active."
            ))
            add(booleanCheck(
                "release.closure.implementation-evidence",
                closureImplementationEvidenceValid(closureWorkPackage, closureWorkStatus),
                closureWorkPackage.string("validationEvidence", "status").ifBlank { "active phase" },
                closureWorkPackageFile.path,
                "A completed closure work package must cite structured passing exact-head and merge-candidate implementation evidence."
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
                "Core roadmap item status: `${if (correctionStatus == "complete") "completed" else "correction-required"}`",
                reportFile.path,
                "REPORT.md must expose the parent Core item status."
            ))
            val closureReportFragment = if (closurePhase == "CLOSED") {
                "Completed Core closure item: `$closureItem $closureName` (`completed`)"
            } else {
                "Next Core roadmap item: `$closureItem $closureName` (`next`)"
            }
            add(containsCheck(
                "release.report.closure-status",
                reportText,
                closureReportFragment,
                reportFile.path,
                "REPORT.md must expose whether closure is READY or CLOSED."
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
            closureItem = closureItem,
            closureWorkPackageStatus = closureWorkStatus,
            closurePhase = closurePhase,
            closureStatus = closureStatus,
            coreTrackStatus = coreTrackStatus,
            completedCoreItem = roadmap.string("currentDecision", "completedItem"),
            nextCoreItem = closureItem,
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
        closureWorkStatus: String
    ): Boolean {
        val evidence = closureWorkPackage.map("validationEvidence")
        if (closureWorkStatus == "active") return evidence.isEmpty() || evidence.string("status").isBlank()
        val exactHead = evidence.string("exactHead")
        val mergeCandidate = evidence.string("mergeCandidate")
        return closureWorkStatus == "complete" &&
            evidence.string("status") == "passed" &&
            evidence.string("workflow") == "Flow CI" &&
            evidence.string("runNumber").toIntOrNull()?.let { it > 0 } == true &&
            evidence.string("runId").toLongOrNull()?.let { it > 0 } == true &&
            SHA.matches(exactHead) &&
            SHA.matches(mergeCandidate) &&
            exactHead != mergeCandidate
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

    private fun Map<String, Any?>.map(vararg path: String): Map<String, Any?> {
        var current: Any? = this
        path.forEach { key -> current = (current as? Map<*, *>)?.get(key) }
        return (current as? Map<*, *>)
            ?.entries
            ?.associate { it.key.toString() to it.value }
            .orEmpty()
    }

    private fun Map<String, Any?>.mapList(key: String): List<Map<String, Any?>> {
        val values = this[key] as? Iterable<*> ?: return emptyList()
        return values.mapNotNull { value ->
            (value as? Map<*, *>)?.entries?.associate { it.key.toString() to it.value }
        }
    }

    private fun Map<String, Any?>.itemStatus(version: String): String =
        mapList("items").firstOrNull { it.string("version") == version }?.string("status").orEmpty()

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
        private val SHA = Regex("[0-9a-f]{40}")
    }
}
