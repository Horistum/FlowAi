package org.flowlang.release

import java.io.File
import org.flowlang.conformance.ConformanceCheck
import org.flowlang.standard.FlowStandardVersions
import org.flowlang.standard.StandardModel

data class SemanticClosureCheck(
    val id: String,
    val status: String,
    val evidence: List<String>,
    val message: String
)

data class SemanticClosureReport(
    val closureVersion: String = "1.0",
    val track: String = "v0.9.7-universal-semantic-foundation",
    val item: String = "0.9.7.10",
    val status: String,
    val checklist: List<SemanticClosureCheck>,
    val failedChecks: List<String>
)

/**
 * Finite, fail-closed authority for the v0.9.7 semantic-foundation closure.
 *
 * The authority introduces no new semantic requirement. It reconciles the
 * already-declared roadmap, correction, release and conformance evidence and
 * refuses closure when any required input is missing, failing or ambiguous.
 */
class SemanticClosureAuthority(private val rootDir: File = File(".")) {
    fun evaluate(completedConformance: List<ConformanceCheck>): SemanticClosureReport {
        val workPackage = File(rootDir, WORK_PACKAGE)
        val workPackageText = requiredText(workPackage)
        val roadmap = File(rootDir, CORE_ROADMAP)
        val roadmapText = requiredText(roadmap)
        val releaseState = File(rootDir, RELEASE_STATE)
        val releaseStateText = requiredText(releaseState)
        val declaredChecklist = yamlList(workPackageText, "closureChecklist")
        val requiredConformance = StandardModel.releaseProfileCheckIds()
        val observedById = completedConformance.associateBy { it.name }
        val activeCorrections = correctionWorkPackages()
            .filter { yamlScalar(requiredText(it), "status") == "active" }
            .map { it.name }
            .sorted()
        val incompletePriorItems = (1..9).map { "0.9.7.$it" }
            .filter { roadmapItemStatus(roadmapText, it) != "completed" }
        val releaseHonesty = ReleaseMetadataHonestyAuthority(rootDir).analyze()
        val missingRequiredChecks = requiredConformance.filterNot(observedById::containsKey)
        val failedRequiredChecks = requiredConformance.filter { observedById[it]?.passed == false }
        val allObservedFailures = completedConformance.filterNot { it.passed }.map { it.name }
        val closureItemStatus = roadmapItemStatus(roadmapText, "0.9.7.10")
        val referenceChecks = listOf(
            "v0.4.4.behavioral-generator-equivalence",
            "v0.4.7.reference-intent-corpus",
            "v0.7.0.reference-corpus-execution-harness"
        )
        val missingReferenceChecks = referenceChecks.filterNot(observedById::containsKey)
        val failedReferenceChecks = referenceChecks.filter { observedById[it]?.passed == false }

        val checks = listOf(
            check(
                id = "closure.checklist-exact",
                passed = declaredChecklist == CHECKLIST,
                evidence = listOf(WORK_PACKAGE, "declared=${declaredChecklist.joinToString()}", "expected=${CHECKLIST.joinToString()}"),
                message = "The closure checklist must be finite, ordered and exactly equal to the declared authority checklist."
            ),
            check(
                id = "closure.no-active-corrections",
                passed = activeCorrections.isEmpty(),
                evidence = if (activeCorrections.isEmpty()) listOf("all v0.9.7.9.x work packages complete") else activeCorrections,
                message = "No bounded v0.9.7.9.x correction may remain active during closure."
            ),
            check(
                id = "closure.prior-core-items-complete",
                passed = incompletePriorItems.isEmpty() && closureItemStatus in setOf("next", "active", "completed"),
                evidence = listOf(CORE_ROADMAP, "incomplete=${incompletePriorItems.joinToString()}", "closureStatus=$closureItemStatus"),
                message = "Core items 0.9.7.1 through 0.9.7.9 must be completed before the bounded closure item runs."
            ),
            check(
                id = "closure.release-metadata-honest",
                passed = releaseHonesty.status == "PASS",
                evidence = listOf("release-metadata-honesty=${releaseHonesty.status}") + releaseHonesty.failedChecks,
                message = "Release metadata axes and governance lifecycle must pass the existing honesty authority."
            ),
            check(
                id = "closure.required-checks-present",
                passed = missingRequiredChecks.isEmpty(),
                evidence = listOf("required=${requiredConformance.size}", "observed=${completedConformance.size}") + missingRequiredChecks,
                message = "Every check already required by the public release profile must have executed before closure."
            ),
            check(
                id = "closure.required-checks-pass",
                passed = failedRequiredChecks.isEmpty(),
                evidence = if (failedRequiredChecks.isEmpty()) listOf("all required release-profile checks passed") else failedRequiredChecks,
                message = "Every check already required by the public release profile must pass."
            ),
            check(
                id = "closure.no-failed-conformance",
                passed = allObservedFailures.isEmpty(),
                evidence = if (allObservedFailures.isEmpty()) listOf("no failed prior conformance checks") else allObservedFailures,
                message = "The closure gate cannot hide a failed non-release-profile conformance check."
            ),
            check(
                id = "closure.version-boundary-unchanged",
                passed = FlowStandardVersions.IMPLEMENTATION_PACKAGE_VERSION == "0.9.5" &&
                    FlowStandardVersions.FLOW_STANDARD_VERSION == "0.8.0" &&
                    yamlNestedScalar(releaseStateText, "versionBoundary", "artifactContractVersion") == "2.0",
                evidence = listOf(
                    "package=${FlowStandardVersions.IMPLEMENTATION_PACKAGE_VERSION}",
                    "standard=${FlowStandardVersions.FLOW_STANDARD_VERSION}",
                    "artifactContract=${yamlNestedScalar(releaseStateText, "versionBoundary", "artifactContractVersion")}"
                ),
                message = "Closure must not smuggle in a package, public-standard or artifact-contract version change."
            ),
            check(
                id = "closure.reference-evidence-live",
                passed = missingReferenceChecks.isEmpty() && failedReferenceChecks.isEmpty(),
                evidence = listOf("requiredReferenceChecks=${referenceChecks.joinToString()}") +
                    missingReferenceChecks.map { "$it:missing" } + failedReferenceChecks.map { "$it:failed" },
                message = "Behavioral generator, reference intent and executable corpus evidence must remain live at closure."
            )
        )
        val failed = checks.filter { it.status == "FAIL" }.map { it.id }
        return SemanticClosureReport(
            status = if (failed.isEmpty()) "PASS" else "FAIL",
            checklist = checks,
            failedChecks = failed
        )
    }

    fun requireClosed(completedConformance: List<ConformanceCheck>): SemanticClosureReport =
        evaluate(completedConformance).also { report ->
            require(report.status == "PASS") {
                "Semantic closure failed: ${report.failedChecks.joinToString()}"
            }
        }

    private fun correctionWorkPackages(): List<File> =
        File(rootDir, ".flow-agent/work-packages").listFiles().orEmpty()
            .filter { it.isFile && it.name.matches(Regex("v0\\.9\\.7\\.9\\.\\d+-.+\\.yaml")) }

    private fun requiredText(file: File): String {
        require(file.isFile) { "Required closure evidence is missing: ${file.path}" }
        return file.readText()
    }

    private fun roadmapItemStatus(text: String, version: String): String {
        val item = Regex(
            "(?ms)^\\s*- version:\\s*$version\\s*\\n(?:(?!^\\s*- version:).)*?^\\s*status:\\s*([a-z-]+)\\s*$"
        ).find(text)
        return item?.groupValues?.get(1).orEmpty()
    }

    private fun yamlScalar(text: String, key: String): String =
        Regex("(?m)^$key:\\s*\\\"?([^\\\"\\n]+)\\\"?\\s*$")
            .find(text)?.groupValues?.get(1)?.trim().orEmpty()

    private fun yamlNestedScalar(text: String, section: String, key: String): String {
        val block = Regex("(?ms)^$section:\\s*\\n((?:^[ ]{2}.+\\n?)*)").find(text)?.groupValues?.get(1).orEmpty()
        return Regex("(?m)^[ ]{2}$key:\\s*\\\"?([^\\\"\\n]+)\\\"?\\s*$")
            .find(block)?.groupValues?.get(1)?.trim().orEmpty()
    }

    private fun yamlList(text: String, key: String): List<String> {
        val lines = text.lineSequence().toList()
        val headerIndex = lines.indexOfFirst { it.trim() == "$key:" }
        if (headerIndex < 0) return emptyList()

        val headerIndent = lines[headerIndex].indexOfFirst { !it.isWhitespace() }
            .let { if (it < 0) 0 else it }
        return lines.drop(headerIndex + 1)
            .takeWhile { line ->
                line.isBlank() || line.indexOfFirst { !it.isWhitespace() }
                    .let { indent -> indent < 0 || indent > headerIndent }
            }
            .mapNotNull { line ->
                val trimmed = line.trim()
                if (!trimmed.startsWith("- ")) return@mapNotNull null
                trimmed.removePrefix("- ")
                    .trim()
                    .removeSurrounding("\"")
                    .takeIf { it.isNotBlank() }
            }
    }

    private fun check(id: String, passed: Boolean, evidence: List<String>, message: String): SemanticClosureCheck =
        SemanticClosureCheck(id, if (passed) "PASS" else "FAIL", evidence, message)

    companion object {
        const val CHECK_ID = "v0.9.7.10.bounded-semantic-closure"
        const val WORK_PACKAGE = ".flow-agent/work-packages/v0.9.7.10-bounded-semantic-closure-gate.yaml"
        const val CORE_ROADMAP = ".flow-agent/roadmap-core-v0.9.7.9.yaml"
        const val RELEASE_STATE = ".flow-agent/release-state.yaml"

        val CHECKLIST: List<String> = listOf(
            "closure.checklist-exact",
            "closure.no-active-corrections",
            "closure.prior-core-items-complete",
            "closure.release-metadata-honest",
            "closure.required-checks-present",
            "closure.required-checks-pass",
            "closure.no-failed-conformance",
            "closure.version-boundary-unchanged",
            "closure.reference-evidence-live"
        )
    }
}
