package org.flowlang.conformance

import java.io.File
import org.flowlang.release.ReleaseMetadataHonestyAuthority
import org.flowlang.serialization.FlowYaml
import org.flowlang.standard.FlowStandardVersions
import org.flowlang.standard.StandardModel

data class BoundedSemanticClosureEvidence(
    val id: String,
    val status: String,
    val expected: String,
    val observed: String,
    val source: String,
    val message: String
)

data class BoundedSemanticClosureReport(
    val closureVersion: String = "0.9.7.10",
    val track: String,
    val phase: String,
    val status: String,
    val declaredCoreItems: List<String>,
    val incompleteCoreItems: List<String>,
    val declaredConformanceChecks: List<String>,
    val observedConformanceChecks: List<String>,
    val missingConformanceChecks: List<String>,
    val unexpectedConformanceChecks: List<String>,
    val duplicateConformanceChecks: List<String>,
    val failedConformanceChecks: List<String>,
    val releaseProfileChecks: List<String>,
    val missingReleaseProfileChecks: List<String>,
    val anchoredRuntimeChecks: List<String>,
    val missingAnchoredRuntimeChecks: List<String>,
    val retainedReferenceChecks: List<String>,
    val missingRetainedReferenceChecks: List<String>,
    val standardModelIssues: List<String>,
    val checks: List<BoundedSemanticClosureEvidence>,
    val failedChecks: List<String>
)

/**
 * Frozen checklist of the checks that existed when v0.9.7.10 started.
 *
 * Closure deliberately does not discover new checks dynamically. A missing check
 * must fail, and a newly introduced check must reopen planning rather than quietly
 * expanding a track whose whole purpose is to be finite.
 */
object BoundedSemanticClosureCatalog {
    const val CLOSURE_CHECK_ID: String = "v0.9.7.10.bounded-semantic-closure"

    val declaredPriorChecks: List<String> = listOf(
        "intent.valid.build-test-deploy",
        "intent.invalid.argocd-missing-config",
        "target.strict.tekton-approval-unsupported",
        "generator.manifest.jenkins",
        "generator.manifest.github-actions",
        "generator.manifest.tekton.partial",
        "intent.yaml.flow-style",
        "snapshots.e2e.files-exist",
        "snapshots.e2e.content",
        "snapshots.rendered.standard-version",
        "standard.catalog",
        "standard.capability-contracts",
        "intent.design-report",
        "core.boundary.no-jackson",
        "modules.boundary.no-target-rendering",
        "ai.normalization.deployment",
        "ai.normalization.full-pipeline",
        "ai.normalization.missing-application-question",
        "schemas.public-outputs",
        "scenario-packs.catalog",
        "scenario-packs.normalization.full-pipelines",
        "scenario-packs.regression-coverage",
        "v0.3.1.scenario-packs",
        "v0.3.1.capability-negotiation",
        "v0.3.2.execution-plan.canonical",
        "v0.3.2.safety-policy-validation",
        "v0.3.4.capability-module-contracts",
        "v0.3.5.intent-decision-model",
        "v0.3.6.execution-plan-portability",
        "v0.3.7.execution-readiness",
        "v0.3.8.target-selection",
        "v0.3.9.target-decision-trace",
        "v0.3.10.public-artifact-bundle",
        "v0.3.11.conformance-manifest",
        "v0.3.12.target-adapter-contract",
        "v0.3.13.standard-diagnostic-catalog",
        "v0.3.14.diagnostic-coverage-report",
        "v0.3.15.artifact-integrity-report",
        "v0.3.16.standard-contract-index",
        "v0.3.17.standard-release-profile",
        "v0.3.18.artifact-evidence-report",
        "v0.3.19.standard-compliance-report",
        "v0.3.20.standard-freeze-report",
        "v0.3.21.compatibility-policy",
        "v0.3.22.reference-corpus",
        "v0.3.23.negative-conformance-corpus",
        "v0.4.2.target-conformance-profile",
        "v0.4.0.public-standard-draft",
        "v0.4.1.semantic-correctness-hardening",
        "v0.4.2.standard-boundary-no-sdk-runtime",
        "v0.4.3.architecture-governance-guardrails",
        "v0.4.4.ai-proposal-review",
        "v0.4.4.condition-expression-readiness",
        "v0.4.4.no-silent-condition-fallback",
        "v0.4.4.behavioral-generator-equivalence",
        "v0.4.5.standard-surface-freeze",
        "v0.4.6.compatibility-migration-policy",
        "v0.4.7.reference-intent-corpus",
        "v0.4.8.target-semantics-matrix",
        "v0.4.9.standard-export-bundle",
        "v0.5.0.standard-export-manifest",
        "v0.5.3.standard-bundle-verifier",
        "v0.5.4.data-driven-conformance-index",
        "governance.target-selection-provenance-cli-status-integrity",
        "governance.closure-blocking-safety-diagnostic-integrity",
        "v0.6.1.intent-corpus-expansion",
        "v0.6.2.required-clarification-contract",
        "v0.6.3.safety-policy-matrix",
        "v0.6.4.target-semantics-negative-corpus",
        "v0.6.5.execution-plan-semantic-invariants",
        "v0.6.6.ai-input-trust-boundary",
        "v0.6.7.standard-example-bundle",
        "v0.6.8.compatibility-promise",
        "v0.7.0.reference-corpus-execution-harness",
        "v0.7.1.architecture-debt-cleanup-and-drift-enforcement",
        "v0.7.3.standard-model-projection-coherence",
        "v0.7.4.architecture-delta-analyzer",
        "v0.7.5.purpose-coverage-ratio",
        "cli.release.diagnostic-honesty",
        ConformanceQualityGateNames.CORE_CONTRACT_CHECK,
        ConformanceQualityGateNames.SCENARIO_PACK_QUALITY
    )

    val retainedReferenceChecks: List<String> = listOf(
        "generator.manifest.jenkins",
        "snapshots.e2e.files-exist",
        "snapshots.e2e.content",
        "v0.4.7.reference-intent-corpus",
        "v0.7.0.reference-corpus-execution-harness",
        "governance.target-selection-provenance-cli-status-integrity",
        "governance.closure-blocking-safety-diagnostic-integrity"
    )
}

/**
 * Computes the bounded semantic closure result from authorities that predate
 * closure. The closure check itself is rejected if supplied as prior evidence.
 */
class BoundedSemanticClosureAuthority(private val rootDir: File = File(".")) {
    fun analyze(
        priorChecks: Collection<ConformanceCheck>,
        declaredChecks: Collection<String> = BoundedSemanticClosureCatalog.declaredPriorChecks
    ): BoundedSemanticClosureReport {
        val coreRoadmapFile = File(rootDir, ".flow-agent/roadmap-core-v0.9.7.9.yaml")
        val roadmapFile = File(rootDir, ".flow-agent/roadmap.yaml")
        val workPackageFile = File(rootDir, ".flow-agent/work-packages/v0.9.7.10-bounded-semantic-closure-gate.yaml")
        val workflowFile = File(rootDir, ".github/workflows/flow-agent-check.yml")

        val coreRoadmap = requiredYaml(coreRoadmapFile)
        val roadmap = requiredYaml(roadmapFile)
        val workPackage = requiredYaml(workPackageFile)
        val workflow = requiredYaml(workflowFile)

        val track = coreRoadmap.string("track")
        val roadmapItems = coreRoadmap.mapList("items").associateBy { it.string("version") }
        val declaredCoreItems = (1..9).map { "0.9.7.$it" }
        val incompleteCoreItems = declaredCoreItems.filter { roadmapItems[it]?.string("status") != "completed" }
        val closureStatus = roadmapItems["0.9.7.10"]?.string("status").orEmpty()
        val workPackageStatus = workPackage.string("status")
        val trackStatus = coreRoadmap.string("status")
        val roadmapClosureStatus = roadmap.string("currentDecision", "nextCoreItemStatus")
        val completedItem = roadmap.string("currentDecision", "completedItem")

        val phase = when {
            workPackageStatus == "active" &&
                closureStatus == "next" &&
                trackStatus == "active" &&
                completedItem == "0.9.7.9" -> "READY"
            workPackageStatus == "complete" &&
                closureStatus == "completed" &&
                trackStatus == "completed" &&
                completedItem == "0.9.7.10" -> "CLOSED"
            else -> "INVALID"
        }

        val declared = declaredChecks.toList()
        val observed = priorChecks.map { it.name }
        val observedCounts = observed.groupingBy { it }.eachCount()
        val duplicateChecks = observedCounts.filterValues { it > 1 }.keys.sorted()
        val missingChecks = declared.filterNot { observedCounts[it] == 1 }.distinct().sorted()
        val unexpectedChecks = observed.filterNot { it in declared }.distinct().sorted()
        val failedChecks = priorChecks.filterNot { it.passed }.map { it.name }.distinct().sorted()

        val releaseProfileChecks = StandardModel.releaseProfileCheckIds()
        val missingReleaseChecks = releaseProfileChecks.filterNot { observedCounts[it] == 1 }.sorted()
        val failedReleaseChecks = priorChecks
            .filter { it.name in releaseProfileChecks && !it.passed }
            .map { it.name }
            .distinct()
            .sorted()

        val anchoredRuntimeChecks = StandardModel.checks
            .filter { it.id in declared }
            .filter { it.externalAnchor.isNotBlank() || it.negativeFixture.isNotBlank() }
            .map { it.id }
            .distinct()
            .sorted()
        val missingAnchoredChecks = anchoredRuntimeChecks.filterNot { observedCounts[it] == 1 }.sorted()
        val failedAnchoredChecks = priorChecks
            .filter { it.name in anchoredRuntimeChecks && !it.passed }
            .map { it.name }
            .distinct()
            .sorted()

        val retainedReferenceChecks = BoundedSemanticClosureCatalog.retainedReferenceChecks
        val missingReferenceChecks = retainedReferenceChecks.filterNot { observedCounts[it] == 1 }.sorted()
        val failedReferenceChecks = priorChecks
            .filter { it.name in retainedReferenceChecks && !it.passed }
            .map { it.name }
            .distinct()
            .sorted()

        val modelIssues = StandardModel.wellFormednessIssues(rootDir)
        val releaseReport = runCatching { ReleaseMetadataHonestyAuthority(rootDir).analyze() }.getOrNull()
        val workflowEvidence = workflowEvidence(workflow)
        val completionEvidenceValid = completionEvidenceValid(workPackage, workPackageStatus)

        val checks = listOf(
            evidence(
                "closure.phase",
                phase in setOf("READY", "CLOSED"),
                "READY or CLOSED",
                phase,
                workPackageFile.path,
                "Closure lifecycle must be a valid two-phase state."
            ),
            evidence(
                "closure.core-items-complete",
                incompleteCoreItems.isEmpty(),
                "all completed",
                incompleteCoreItems.joinToString().ifBlank { "all completed" },
                coreRoadmapFile.path,
                "Every declared Core item from 0.9.7.1 through 0.9.7.9 must be completed."
            ),
            evidence(
                "closure.no-active-correction",
                roadmap.string("currentDecision", "correctionState") == "complete" &&
                    roadmap.string("currentDecision", "activeCorrectionWorkPackage").isBlank(),
                "correctionState=complete and no active pointer",
                "state=${roadmap.string("currentDecision", "correctionState")}, pointer=${roadmap.string("currentDecision", "activeCorrectionWorkPackage")}",
                roadmapFile.path,
                "Closure cannot run while a bounded correction remains active."
            ),
            evidence(
                "closure.roadmap-state-aligned",
                roadmap.string("currentDecision", "nextCoreItem") == "0.9.7.10" &&
                    roadmapClosureStatus == closureStatus,
                "item=0.9.7.10, status=$closureStatus",
                "item=${roadmap.string("currentDecision", "nextCoreItem")}, status=$roadmapClosureStatus",
                roadmapFile.path,
                "Roadmap index and Core roadmap must agree on the closure item and phase."
            ),
            evidence(
                "closure.release-metadata-honest",
                releaseReport?.status == "PASS" && releaseReport.correctionStatus == "complete",
                "PASS with completed correction track",
                releaseReport?.let { "${it.status}, correction=${it.correctionStatus}, closure=${it.closureStatus}" } ?: "unavailable",
                ".flow-agent/release-state.yaml",
                "Package, standard, correction and closure metadata must reconcile."
            ),
            evidence(
                "closure.standard-model-well-formed",
                modelIssues.isEmpty(),
                "no issues",
                modelIssues.joinToString().ifBlank { "no issues" },
                "StandardModel",
                "The existing standard model and all declared anchors must remain well formed."
            ),
            evidence(
                "closure.declared-check-set",
                declared.size == declared.toSet().size &&
                    missingChecks.isEmpty() &&
                    unexpectedChecks.isEmpty() &&
                    duplicateChecks.isEmpty() &&
                    BoundedSemanticClosureCatalog.CLOSURE_CHECK_ID !in observed,
                "exact finite declared set, excluding closure itself",
                "missing=${missingChecks.joinToString()}, unexpected=${unexpectedChecks.joinToString()}, duplicate=${duplicateChecks.joinToString()}",
                "ConformanceRunner prior checks",
                "Executed checks must match the frozen pre-closure catalog exactly once."
            ),
            evidence(
                "closure.all-prior-checks-pass",
                failedChecks.isEmpty(),
                "all PASS",
                failedChecks.joinToString().ifBlank { "all PASS" },
                "ConformanceRunner prior checks",
                "Closure cannot hide a failing prior conformance result."
            ),
            evidence(
                "closure.release-profile-checks-pass",
                missingReleaseChecks.isEmpty() && failedReleaseChecks.isEmpty(),
                "all present exactly once and PASS",
                "missing=${missingReleaseChecks.joinToString()}, failed=${failedReleaseChecks.joinToString()}",
                "StandardModel.releaseProfileCheckIds",
                "Every existing public release-profile check must remain active and passing."
            ),
            evidence(
                "closure.anchored-checks-pass",
                missingAnchoredChecks.isEmpty() && failedAnchoredChecks.isEmpty(),
                "all present exactly once and PASS",
                "missing=${missingAnchoredChecks.joinToString()}, failed=${failedAnchoredChecks.joinToString()}",
                "StandardModel externalAnchor and negativeFixture",
                "Every existing runtime check backed by external or negative evidence must pass."
            ),
            evidence(
                "closure.reference-proof-retained",
                missingReferenceChecks.isEmpty() && failedReferenceChecks.isEmpty(),
                "all retained reference checks PASS",
                "missing=${missingReferenceChecks.joinToString()}, failed=${failedReferenceChecks.joinToString()}",
                "BoundedSemanticClosureCatalog.retainedReferenceChecks",
                "The existing Jenkins, snapshot, corpus and governance reference proofs must remain live."
            ),
            evidence(
                "closure.exact-and-merge-ci-separated",
                workflowEvidence.isEmpty(),
                "separate structured exact-head and merge-candidate jobs",
                workflowEvidence.joinToString().ifBlank { "separate jobs verified" },
                workflowFile.path,
                "Exact-head evidence and synthetic merge-candidate evidence must remain separate."
            ),
            evidence(
                "closure.completion-evidence-structured",
                completionEvidenceValid,
                if (workPackageStatus == "complete") "structured passing implementation evidence" else "not required until completion phase",
                workPackage.string("validationEvidence", "status").ifBlank { "active phase" },
                workPackageFile.path,
                "Completed closure metadata must cite a passing external implementation run with exact and merge SHAs."
            ),
            evidence(
                "closure.version-boundary-unchanged",
                workPackage.string("versionBoundary", "packageVersion") == FlowStandardVersions.IMPLEMENTATION_PACKAGE_VERSION &&
                    workPackage.string("versionBoundary", "publicStandardVersion") == FlowStandardVersions.FLOW_STANDARD_VERSION,
                "package=${FlowStandardVersions.IMPLEMENTATION_PACKAGE_VERSION}, standard=${FlowStandardVersions.FLOW_STANDARD_VERSION}",
                "package=${workPackage.string("versionBoundary", "packageVersion")}, standard=${workPackage.string("versionBoundary", "publicStandardVersion")}",
                workPackageFile.path,
                "Closure must not publish a package or public-standard version change."
            )
        )

        val failedEvidence = checks.filter { it.status == "FAIL" }.map { it.id }
        return BoundedSemanticClosureReport(
            track = track,
            phase = phase,
            status = if (failedEvidence.isEmpty()) "PASS" else "FAIL",
            declaredCoreItems = declaredCoreItems,
            incompleteCoreItems = incompleteCoreItems,
            declaredConformanceChecks = declared,
            observedConformanceChecks = observed,
            missingConformanceChecks = missingChecks,
            unexpectedConformanceChecks = unexpectedChecks,
            duplicateConformanceChecks = duplicateChecks,
            failedConformanceChecks = failedChecks,
            releaseProfileChecks = releaseProfileChecks,
            missingReleaseProfileChecks = missingReleaseChecks,
            anchoredRuntimeChecks = anchoredRuntimeChecks,
            missingAnchoredRuntimeChecks = missingAnchoredChecks,
            retainedReferenceChecks = retainedReferenceChecks,
            missingRetainedReferenceChecks = missingReferenceChecks,
            standardModelIssues = modelIssues,
            checks = checks,
            failedChecks = failedEvidence
        )
    }

    private fun workflowEvidence(workflow: Map<String, Any?>): List<String> {
        val jobs = workflow.map("jobs")
        val exact = jobs.map("compile-test-conformance")
        val merge = jobs.map("merge-candidate-compile-test-conformance")
        val exactSteps = exact.mapList("steps")
        val mergeSteps = merge.mapList("steps")
        val issues = mutableListOf<String>()

        if (exact.isEmpty()) issues += "missing exact-head job"
        if (merge.isEmpty()) issues += "missing merge-candidate job"
        if (exactSteps.none { it.string("name") == "Verify Exact Checked-Out Revision" }) {
            issues += "missing exact-head revision verification"
        }
        if (mergeSteps.none { it.string("name") == "Verify Merge Candidate Revision" }) {
            issues += "missing merge-candidate revision verification"
        }
        val exactCheckout = exactSteps.firstOrNull { it.string("name") == "Checkout Exact Revision" }.orEmpty()
        if (!exactCheckout.string("with", "ref").contains("github.event.pull_request.head.sha")) {
            issues += "exact checkout does not select pull-request head SHA"
        }
        val exactCommands = exactSteps.map { it.string("run") }.joinToString("\n")
        val mergeCommands = mergeSteps.map { it.string("run") }.joinToString("\n")
        if (!exactCommands.contains("git rev-parse HEAD")) issues += "exact job does not verify git HEAD"
        if (!mergeCommands.contains("git rev-parse HEAD")) issues += "merge job does not verify git HEAD"
        if (exactSteps.none { it.string("name") == "Compile and Test" }) issues += "exact job does not run complete tests"
        if (exactSteps.none { it.string("name") == "Run Conformance" }) issues += "exact job does not run standalone conformance"
        if (mergeSteps.none { it.string("name") == "Compile and Test Merge Candidate" }) issues += "merge job does not run complete tests"
        if (mergeSteps.none { it.string("name") == "Run Merge Candidate Conformance" }) issues += "merge job does not run standalone conformance"
        return issues
    }

    private fun completionEvidenceValid(workPackage: Map<String, Any?>, status: String): Boolean {
        if (status == "active") return true
        val evidence = workPackage.map("validationEvidence")
        return status == "complete" &&
            evidence.string("status") == "passed" &&
            evidence.string("workflow") == "Flow CI" &&
            evidence.string("runNumber").toIntOrNull()?.let { it > 0 } == true &&
            SHA.matches(evidence.string("exactHead")) &&
            SHA.matches(evidence.string("mergeCandidate"))
    }

    private fun evidence(
        id: String,
        passed: Boolean,
        expected: String,
        observed: String,
        source: String,
        message: String
    ): BoundedSemanticClosureEvidence = BoundedSemanticClosureEvidence(
        id = id,
        status = if (passed) "PASS" else "FAIL",
        expected = expected,
        observed = observed,
        source = source,
        message = message
    )

    private fun requiredYaml(file: File): Map<String, Any?> {
        require(file.isFile) { "Required closure evidence is missing: ${file.path}" }
        return FlowYaml.readMap(file.readText(), file.path)
    }

    private fun Map<String, Any?>.string(vararg path: String): String {
        var current: Any? = this
        path.forEach { key -> current = (current as? Map<*, *>)?.get(key) }
        return current?.toString().orEmpty()
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
        (this[key] as? Iterable<*>)
            .orEmpty()
            .mapNotNull { value ->
                (value as? Map<*, *>)?.entries?.associate { it.key.toString() to it.value }
            }

    companion object {
        private val SHA = Regex("[0-9a-f]{40}")
    }
}

internal class BoundedSemanticClosureChecks(
    rootDir: File,
    registry: org.flowlang.modules.ModuleRegistry,
    targets: Map<String, org.flowlang.capabilities.TargetCapability>,
    projections: org.flowlang.generators.manifest.TargetProjectionRegistry
) : ConformanceCheckSupport(rootDir, registry, targets, projections) {
    fun checks(priorChecks: List<ConformanceCheck>): List<ConformanceCheck> = listOf(
        runCheck(BoundedSemanticClosureCatalog.CLOSURE_CHECK_ID) {
            val authority = BoundedSemanticClosureAuthority(rootDir)
            val report = authority.analyze(priorChecks)
            require(report.status == "PASS") {
                "Bounded semantic closure failed: ${report.failedChecks.joinToString()}"
            }

            val first = priorChecks.first()
            val missing = authority.analyze(priorChecks.filterNot { it === first })
            require(missing.status == "FAIL" && "closure.declared-check-set" in missing.failedChecks) {
                "Closure did not reject an omitted prior check."
            }

            val failed = authority.analyze(
                priorChecks.map { check ->
                    if (check === first) ConformanceCheck(check.name, false, "synthetic closure counterexample") else check
                }
            )
            require(failed.status == "FAIL" && "closure.all-prior-checks-pass" in failed.failedChecks) {
                "Closure did not reject a failing prior check."
            }

            val duplicate = authority.analyze(priorChecks + ConformanceCheck(first.name, true))
            require(duplicate.status == "FAIL" && "closure.declared-check-set" in duplicate.failedChecks) {
                "Closure did not reject duplicate check evidence."
            }

            val circular = authority.analyze(
                priorChecks + ConformanceCheck(BoundedSemanticClosureCatalog.CLOSURE_CHECK_ID, true)
            )
            require(circular.status == "FAIL" && "closure.declared-check-set" in circular.failedChecks) {
                "Closure accepted its own PASS result as prior evidence."
            }
        }
    )
}
