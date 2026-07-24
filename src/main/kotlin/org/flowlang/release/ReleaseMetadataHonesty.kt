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
    val reportVersion: String = "1.0",
    val implementationPackageVersion: String = FlowStandardVersions.IMPLEMENTATION_PACKAGE_VERSION,
    val publicStandardVersion: String = FlowStandardVersions.FLOW_STANDARD_VERSION,
    val completedCorrectionItem: String,
    val nextCoreItem: String,
    val status: String,
    val checks: List<ReleaseMetadataHonestyCheck>,
    val failedChecks: List<String>
)

/**
 * Reconciles repository release metadata without treating prose as an authority.
 *
 * Package, public-standard, artifact-contract, Core-roadmap and bounded-work
 * versions are independent axes. A correction work package may complete work
 * within one Core item, but it never replaces that parent item in roadmap state.
 */
class ReleaseMetadataHonestyAuthority(private val rootDir: File = File(".")) {
    fun analyze(): ReleaseMetadataHonestyReport {
        val releaseStateFile = File(rootDir, ".flow-agent/release-state.yaml")
        val roadmapFile = File(rootDir, ".flow-agent/roadmap.yaml")
        val workPackageFile = File(rootDir, ".flow-agent/work-packages/v0.9.7.9.7-cli-diagnostic-release-honesty.yaml")
        val reportFile = File(rootDir, "REPORT.md")
        val changelogFile = File(rootDir, "CHANGELOG-v0.9.7.9.md")
        val gradleFile = File(rootDir, "build.gradle.kts")

        val releaseState = requiredYaml(releaseStateFile)
        val roadmap = requiredYaml(roadmapFile)
        val workPackage = requiredYaml(workPackageFile)
        val reportText = requiredText(reportFile)
        val changelogText = requiredText(changelogFile)
        val gradleText = requiredText(gradleFile)

        val packageVersion = FlowStandardVersions.IMPLEMENTATION_PACKAGE_VERSION
        val standardVersion = FlowStandardVersions.FLOW_STANDARD_VERSION
        val parentItem = "0.9.7.9"
        val parentName = "Intent Lowering and Diagnostic Honesty"
        val completedCorrection = "0.9.7.9.7"
        val completedCorrectionName = "CLI Diagnostic and Release Honesty"
        val nextItem = "0.9.7.10"
        val nextName = "Bounded Semantic Closure Gate"
        val lastMergedValidation = "Flow CI #2001"
        val lastMergedHead = "002483835844c207df6375d4159e331bfcaf6415"

        val checks = buildList {
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
                "Release state must retain the completed parent Core item."
            ))
            add(equalsCheck(
                "release.state.parent-name",
                parentName,
                releaseState.string("roadmapState", "completedItemName"),
                releaseStateFile.path,
                "Release state must retain the completed parent Core item name."
            ))
            add(equalsCheck(
                "release.roadmap.parent-item",
                parentItem,
                roadmap.string("currentDecision", "completedItem"),
                roadmapFile.path,
                "The roadmap index must reference a real Core roadmap item, not a bounded correction id."
            ))
            add(equalsCheck(
                "release.roadmap.parent-name",
                parentName,
                roadmap.string("currentDecision", "completedItemName"),
                roadmapFile.path,
                "The roadmap index must retain the completed parent item name."
            ))
            add(equalsCheck(
                "release.work-package.correction-item",
                completedCorrection,
                workPackage.string("version"),
                workPackageFile.path,
                "The bounded correction identity belongs to its work package."
            ))
            add(equalsCheck(
                "release.work-package.correction-name",
                completedCorrectionName,
                workPackage.string("name"),
                workPackageFile.path,
                "The bounded correction work package must carry the correction name."
            ))
            add(equalsCheck(
                "release.work-package.status",
                "complete",
                workPackage.string("status"),
                workPackageFile.path,
                "The completed correction work package must use the canonical complete status."
            ))
            add(equalsCheck(
                "release.state.next-item",
                nextItem,
                releaseState.string("roadmapState", "nextCoreItem"),
                releaseStateFile.path,
                "Release state must advance to the bounded closure gate."
            ))
            add(equalsCheck(
                "release.roadmap.next-item",
                nextItem,
                roadmap.string("currentDecision", "nextCoreItem"),
                roadmapFile.path,
                "The roadmap index and release state must agree on the next Core item."
            ))
            add(containsCheck(
                "release.report.completed-correction",
                reportText,
                "Completed correction item: `$completedCorrection $completedCorrectionName`",
                reportFile.path,
                "REPORT.md must distinguish the bounded correction from the parent Core item."
            ))
            add(containsCheck(
                "release.report.parent-item",
                reportText,
                "Completed Core roadmap item: `$parentItem $parentName`",
                reportFile.path,
                "REPORT.md must retain the parent Core item separately."
            ))
            add(containsCheck(
                "release.report.next-item",
                reportText,
                "Next Core roadmap item: `$nextItem $nextName`",
                reportFile.path,
                "REPORT.md must identify the next Core item."
            ))
            (1..7).forEach { item ->
                val id = "0.9.7.9.$item"
                add(containsCheck(
                    "release.changelog.$id",
                    changelogText,
                    "### v$id ",
                    changelogFile.path,
                    "The bounded correction changelog must record every work item exactly once."
                ))
            }
            add(containsCheck(
                "release.validation.last-merged-run",
                releaseState.string("lastKnownValidation", "validationSource"),
                lastMergedValidation,
                releaseStateFile.path,
                "Committed metadata may cite only already-merged validation evidence."
            ))
            add(containsCheck(
                "release.validation.last-merged-head",
                releaseState.string("lastKnownValidation", "validationSource"),
                lastMergedHead,
                releaseStateFile.path,
                "Committed metadata must identify the exact already-merged validation head."
            ))
            add(containsCheck(
                "release.validation.external-candidate-policy",
                releaseState.string("lastKnownValidation", "candidateValidationPolicy"),
                "external exact-head CI evidence",
                releaseStateFile.path,
                "Candidate validation must remain external evidence until the candidate head actually passes."
            ))
            val activeRoadmap = releaseState.string("roadmapState", "activeRoadmaps", "core")
            add(booleanCheck(
                "release.roadmap.active-file",
                File(rootDir, activeRoadmap).isFile,
                activeRoadmap,
                releaseStateFile.path,
                "The active Core roadmap path must resolve to a real repository file."
            ))
        }

        val failed = checks.filter { it.status == "FAIL" }.map { it.id }
        return ReleaseMetadataHonestyReport(
            completedCorrectionItem = completedCorrection,
            nextCoreItem = nextItem,
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

    private fun requiredText(file: File): String {
        require(file.isFile) { "Required release metadata file is missing: ${file.path}" }
        return file.readText()
    }

    private fun requiredYaml(file: File): Map<String, Any?> = FlowYaml.readMap(requiredText(file), file.path)

    private fun Map<String, Any?>.string(vararg path: String): String {
        var current: Any? = this
        path.forEach { key -> current = (current as? Map<*, *>)?.get(key) }
        return current?.toString().orEmpty()
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
        observed = observed.take(240),
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
}
