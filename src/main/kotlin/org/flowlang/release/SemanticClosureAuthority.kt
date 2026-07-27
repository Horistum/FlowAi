package org.flowlang.release

import java.io.File
import org.flowlang.conformance.ConformanceCheck
import org.flowlang.conformance.ConformanceSuiteInventory
import org.flowlang.serialization.FlowYaml
import org.flowlang.standard.FlowStandardVersions
import org.flowlang.standard.StandardModel

data class SemanticClosureCheck(
    val id: String,
    val status: String,
    val evidence: List<String>,
    val message: String
)

data class SemanticClosureReport(
    val closureVersion: String = "1.1",
    val track: String = "v0.9.7-universal-semantic-foundation",
    val item: String = "0.9.7.10",
    val status: String,
    val checklist: List<SemanticClosureCheck>,
    val failedChecks: List<String>
)

/**
 * Finite, fail-closed authority for the v0.9.7 semantic-foundation closure.
 *
 * Release-profile membership determines public release obligations. It does not
 * define whether the complete conformance producer is still present. The latter
 * is proven independently by the exact pre-closure suite inventory.
 */
class SemanticClosureAuthority(private val rootDir: File = File(".")) {
    fun evaluate(completedConformance: List<ConformanceCheck>): SemanticClosureReport {
        val workPackage = requiredYaml(File(rootDir, WORK_PACKAGE))
        val roadmap = requiredYaml(File(rootDir, CORE_ROADMAP))
        val releaseState = requiredYaml(File(rootDir, RELEASE_STATE))
        val declaredChecklist = workPackage.stringList("closureChecklist")
        val inventory = ConformanceSuiteInventory.load(rootDir)
        val expectedChecks = inventory.preClosureChecks
        val observedChecks = completedConformance.map { it.name }
        val observedById = completedConformance.associateBy { it.name }
        val duplicateObserved = observedChecks.groupingBy { it }.eachCount()
            .filterValues { it > 1 }.keys.sorted()
        val missingInventoryChecks = expectedChecks.filterNot(observedById::containsKey)
        val unexpectedChecks = observedChecks.filterNot(expectedChecks.toSet()::contains)
        val releaseProfileChecks = StandardModel.releaseProfileCheckIds()
        val releaseChecksMissingFromInventory = releaseProfileChecks.filterNot(expectedChecks.toSet()::contains)
        val failedRequiredChecks = releaseProfileChecks.filter { observedById[it]?.passed == false }
        val allObservedFailures = completedConformance.filterNot { it.passed }.map { it.name }

        val correctionStates = correctionWorkPackages().map { file ->
            val status = requiredYaml(file).string("status")
            CorrectionState(file.name, status)
        }
        val activeCorrections = correctionStates.filter { it.status == "active" }.map { it.fileName }.sorted()
        val invalidCorrections = correctionStates.filterNot { it.status in TERMINAL_OR_ACTIVE_STATUSES }
            .map { "${it.fileName}:${it.status.ifBlank { "<missing>" }}" }
            .sorted()

        val incompletePriorItems = (1..9).map { "0.9.7.$it" }
            .filter { roadmap.itemStatus(it) != "completed" }
        val closureItemStatus = roadmap.itemStatus("0.9.7.10")
        val releaseHonesty = ReleaseMetadataHonestyAuthority(rootDir).analyze()
        val referenceChecks = listOf(
            "v0.4.4.behavioral-generator-equivalence",
            "v0.4.7.reference-intent-corpus",
            "v0.7.0.reference-corpus-execution-harness"
        )
        val missingReferenceChecks = referenceChecks.filterNot(observedById::containsKey)
        val failedReferenceChecks = referenceChecks.filter { observedById[it]?.passed == false }

        val exactInventory = inventory.closureCheck == CHECK_ID &&
            observedChecks == expectedChecks &&
            duplicateObserved.isEmpty() &&
            releaseChecksMissingFromInventory.isEmpty()

        val checks = listOf(
            check(
                id = "closure.checklist-exact",
                passed = declaredChecklist == CHECKLIST,
                evidence = listOf(WORK_PACKAGE, "declared=${declaredChecklist.joinToString()}", "expected=${CHECKLIST.joinToString()}"),
                message = "The closure checklist must be finite, ordered and exactly equal to the declared authority checklist."
            ),
            check(
                id = "closure.no-active-corrections",
                passed = activeCorrections.isEmpty() && invalidCorrections.isEmpty(),
                evidence = when {
                    invalidCorrections.isNotEmpty() -> invalidCorrections.map { "invalid=$it" }
                    activeCorrections.isNotEmpty() -> activeCorrections
                    else -> listOf("all v0.9.7.9.x work packages have an explicit terminal status")
                },
                message = "Every bounded correction status must be active, complete or completed, and none may remain active during closure."
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
                passed = exactInventory,
                evidence = listOf(
                    ConformanceSuiteInventory.PATH,
                    "inventoryVersion=${inventory.version}",
                    "expected=${expectedChecks.size}",
                    "observed=${observedChecks.size}",
                    "orderMatches=${observedChecks == expectedChecks}"
                ) + missingInventoryChecks.map { "missing=$it" } +
                    unexpectedChecks.map { "unexpected=$it" } +
                    duplicateObserved.map { "duplicate=$it" } +
                    releaseChecksMissingFromInventory.map { "release-profile-not-in-inventory=$it" },
                message = "The complete declared pre-closure conformance sequence must execute exactly once and in order."
            ),
            check(
                id = "closure.required-checks-pass",
                passed = failedRequiredChecks.isEmpty(),
                evidence = if (failedRequiredChecks.isEmpty()) listOf("all required release-profile checks passed") else failedRequiredChecks,
                message = "Every check required by the public release profile must pass."
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
                    releaseState.string("versionBoundary", "artifactContractVersion") == "2.0",
                evidence = listOf(
                    "package=${FlowStandardVersions.IMPLEMENTATION_PACKAGE_VERSION}",
                    "standard=${FlowStandardVersions.FLOW_STANDARD_VERSION}",
                    "artifactContract=${releaseState.string("versionBoundary", "artifactContractVersion")}"
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

    private fun requiredYaml(file: File): Map<String, Any?> {
        require(file.isFile) { "Required closure evidence is missing: ${file.path}" }
        return FlowYaml.readMap(file)
    }

    private fun Map<String, Any?>.string(vararg path: String): String {
        var current: Any? = this
        path.forEach { key -> current = (current as? Map<*, *>)?.get(key) }
        return current?.toString().orEmpty()
    }

    private fun Map<String, Any?>.stringList(key: String): List<String> =
        (this[key] as? Iterable<*>)?.mapNotNull { it as? String }.orEmpty()

    private fun Map<String, Any?>.mapList(key: String): List<Map<String, Any?>> =
        (this[key] as? Iterable<*>)?.mapNotNull { value ->
            (value as? Map<*, *>)?.entries?.associate { it.key.toString() to it.value }
        }.orEmpty()

    private fun Map<String, Any?>.itemStatus(version: String): String =
        mapList("items").firstOrNull { it.string("version") == version }?.string("status").orEmpty()

    private fun check(id: String, passed: Boolean, evidence: List<String>, message: String): SemanticClosureCheck =
        SemanticClosureCheck(id, if (passed) "PASS" else "FAIL", evidence, message)

    private data class CorrectionState(val fileName: String, val status: String)

    companion object {
        const val CHECK_ID = "v0.9.7.10.bounded-semantic-closure"
        const val WORK_PACKAGE = ".flow-agent/work-packages/v0.9.7.10-bounded-semantic-closure-gate.yaml"
        const val CORE_ROADMAP = ".flow-agent/roadmap-core-v0.9.7.9.yaml"
        const val RELEASE_STATE = ".flow-agent/release-state.yaml"
        private val TERMINAL_OR_ACTIVE_STATUSES = setOf("active", "complete", "completed")

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
