package org.flowlang.conformance

import java.io.File
import org.flowlang.architecture.CiCdBiasInventoryAnalyzer
import org.flowlang.capabilities.CompatibilityAnalyzer
import org.flowlang.cli.honest.CliTargetEvidenceAuthority
import org.flowlang.cli.honest.CliTargetEvidenceOutcome
import org.flowlang.cli.honest.CliArtifactRole
import org.flowlang.cli.honest.CliDiagnosticCode
import org.flowlang.cli.honest.CliExecutionResult
import org.flowlang.cli.honest.executeCli
import org.flowlang.controls.AuthoredControlEvidenceTextAuthority
import org.flowlang.controls.AuthoredControlEvidenceTextStatus
import org.flowlang.controls.CanonicalControlRequirementAuthority
import org.flowlang.controls.ControlDecisionStatus
import org.flowlang.controls.ControlEvidenceStatus
import org.flowlang.generators.manifest.InvalidPlanningEvidenceException
import org.flowlang.generators.manifest.MandatoryMaterializationAuthority
import org.flowlang.generators.manifest.TargetJob
import org.flowlang.generators.manifest.TargetManifest
import org.flowlang.generators.manifest.TargetProjectionRegistry
import org.flowlang.intent.IntentDocument
import org.flowlang.intent.IntentStep
import org.flowlang.intent.IntentString
import org.flowlang.intent.IntentToAstPlanner
import org.flowlang.intent.IntentWorkflow
import org.flowlang.intent.IntentWorkflowKind
import org.flowlang.intent.StandardCapability
import org.flowlang.modules.ModuleRegistry
import org.flowlang.planner.ApprovalNode
import org.flowlang.planner.ExecutionPlan
import org.flowlang.planner.FlowPlanner
import org.flowlang.planner.TaskNode
import org.flowlang.release.ReleaseMetadataHonestyAuthority
import org.flowlang.targets.builtin.GitHubJobConditionAuthority
import org.flowlang.topology.ExecutionTopologyRequirementSource

internal class ClosureBlockingIntegrityChecks(
    rootDir: File,
    registry: ModuleRegistry,
    targets: Map<String, org.flowlang.capabilities.TargetCapability>,
    projections: TargetProjectionRegistry
) : ConformanceCheckSupport(rootDir, registry, targets, projections) {
    fun checks(): List<ConformanceCheck> = listOf(
        checkClosureBlockingIntegrity()
    )

    private fun checkClosureBlockingIntegrity(): ConformanceCheck =
        runCheck("governance.closure-blocking-safety-diagnostic-integrity") {
            checkAuthoredControlEvidence()
            checkBiasInventory()
            checkCliBoundary()
            checkCanonicalTopology()
            checkRetainedDerivedProjection()
            checkGitHubCancellationSemantics()
            checkExactHeadCiBoundary()
            checkReleaseLifecycle()
        }

    private fun checkAuthoredControlEvidence() {
        val ambiguous = listOf(
            "backup" to "unknown",
            "backup" to "TODO",
            "backup" to "n/a",
            "backup" to "pending",
            "backup" to "to be confirmed",
            "backup" to "backup later",
            "backup" to "backup something",
            "rollbackPlan" to "rollback someday please",
            "retention" to "policy later",
            "safety" to "approval pending",
            "backup" to "approved"
        )
        ambiguous.forEach { (parameter, value) ->
            require(
                AuthoredControlEvidenceTextAuthority.assess(parameter, IntentString(value)).status ==
                    AuthoredControlEvidenceTextStatus.UNKNOWN
            ) { "Ambiguous control evidence '$parameter=$value' was accepted as positive evidence." }
        }
        require(
            AuthoredControlEvidenceTextAuthority
                .assess("backup", IntentString("s3://recovery/db-before-migration-42"))
                .status == AuthoredControlEvidenceTextStatus.CONFIRMED
        ) { "A concrete backup reference was not recognized as authored evidence." }

        val conflicting = CanonicalControlRequirementAuthority.assess(
            IntentDocument(
                name = "conflicting-control-evidence",
                workflows = listOf(
                    IntentWorkflow(
                        name = "migration",
                        kind = IntentWorkflowKind.CUSTOM,
                        steps = listOf(
                            IntentStep(
                                id = "migrate-confirmed",
                                capability = StandardCapability.DATABASE_MIGRATE,
                                params = mapOf("backup" to IntentString("s3://recovery/db-before-migration-42"))
                            ),
                            IntentStep(
                                id = "migrate-denied",
                                capability = StandardCapability.DATABASE_MIGRATE,
                                params = mapOf("backup" to IntentString("not available"))
                            )
                        )
                    )
                )
            )
        )
        require(conflicting.decision.status == ControlDecisionStatus.BLOCKED) {
            "Contradictory authored control evidence was allowed."
        }
        require(conflicting.evidence.single().status == ControlEvidenceStatus.UNSATISFIED) {
            "Contradictory authored control evidence was not represented as blocking evidence."
        }
        require(conflicting.evidence.single().detail.orEmpty().contains("Conflicting authored evidence")) {
            "Contradictory authored control evidence lacks an explicit conflict diagnostic."
        }
    }

    private fun checkBiasInventory() {
        val report = CiCdBiasInventoryAnalyzer(rootDir).analyze()
        require(report.healthStatus == "PASS") {
            "CI/CD bias governance cannot close: ${report.actionableEvidence.take(20).joinToString { "${it.path}:${it.line}:${it.term}:${it.lexicalContext}" }}"
        }
        require(report.actionableEvidence.isEmpty()) {
            "A PASS bias report still contains actionable semantic evidence."
        }
        require(report.evidence.none { it.actionable && it.lexicalContext.name == "CATALOG_DECLARATION" }) {
            "The CI/CD bias analyzer treats its own catalog as actionable evidence."
        }
    }

    private fun checkCliBoundary() {
        require(
            Thread.currentThread().contextClassLoader.getResource("org/flowlang/cli/FlowCliKt.class") == null
        ) { "The legacy CLI entrypoint remains on the compiled classpath." }

        val missingTarget = executeCli(arrayOf(
            "intent",
            File(rootDir, "examples/intent/build-test-deploy.intent.yaml").path,
            "--render"
        ))
        require(missingTarget is CliExecutionResult.Rejected) {
            "Render without explicit target selection did not produce a typed rejection."
        }
        require(missingTarget.diagnostic.code == CliDiagnosticCode.TARGET_REQUIRED_FOR_RENDER) {
            "Render without target returned ${missingTarget.diagnostic.code} instead of TARGET_REQUIRED_FOR_RENDER."
        }
        require(missingTarget.artifacts.none {
            it.role == CliArtifactRole.TARGET_MANIFEST || it.role == CliArtifactRole.RENDERED_TARGET
        }) { "Render without target selection exposed target artifacts." }

        val unknown = executeCli(arrayOf("unknown-target-command"))
        require(unknown is CliExecutionResult.Rejected && unknown.diagnostic.code == CliDiagnosticCode.UNKNOWN_COMMAND) {
            "Unknown command did not remain a typed terminal rejection."
        }

        val intent = org.flowlang.adapters.yaml.IntentYamlLoader.load(
            File(rootDir, "examples/intent/build-test-deploy.intent.yaml")
        )
        val plan = FlowPlanner(registry).plan(IntentToAstPlanner(registry).plan(intent))
        val review = CliTargetEvidenceAuthority(targets, projections).evaluate(
            plan = plan,
            explicitSelection = explicitTarget("github-actions", "conformance:closure-cli"),
            strict = false,
            renderRequested = true
        )
        require(review.outcome != CliTargetEvidenceOutcome.EXECUTABLE) {
            "The bounded build-test-deploy GitHub Actions target unexpectedly became executable."
        }
        require(review.renderedArtifact == null) {
            "Review or blocked diagnostic evidence emitted target syntax."
        }
        require(review.diagnostics.any { it.code == "CLI_RENDER_NOT_AUTHORIZED" }) {
            "A blocked render request lacks stable CLI diagnostics."
        }
    }

    private fun checkCanonicalTopology() {
        val plan = FlowPlanner(registry).plan(
            IntentToAstPlanner(registry).plan(
                IntentDocument(
                    name = "closure-topology",
                    workflows = listOf(
                        IntentWorkflow(
                            name = "delivery",
                            kind = IntentWorkflowKind.CUSTOM,
                            steps = listOf(IntentStep(id = "approve", capability = StandardCapability.APPROVE))
                        )
                    )
                )
            )
        )
        val omitted = plan.copy(
            topologyRequirements = plan.topologyRequirements.filterNot {
                it.source == ExecutionTopologyRequirementSource.CANONICAL_WORKFLOW ||
                    it.source == ExecutionTopologyRequirementSource.CANONICAL_CAPABILITY
            }
        )
        val omittedFailure = runCatching {
            MandatoryMaterializationAuthority(targets, registry)
                .authorizeDiagnosticEvidence(diagnosticMaterializationRequest(omitted, "jenkins", "conformance:closure-topology-omitted"))
        }.exceptionOrNull()
        require(omittedFailure is InvalidPlanningEvidenceException) {
            "Omitted canonical topology did not fail at the materialization authority."
        }
        require(omittedFailure.issues.any { it.code == "planning.topology.canonical.missing" }) {
            "Canonical topology loss failed without a specific planning diagnostic."
        }

        val withoutProvenance = plan.copy(sourceIntent = null)
        val provenanceFailure = runCatching {
            MandatoryMaterializationAuthority(targets, registry)
                .authorizeDiagnosticEvidence(diagnosticMaterializationRequest(withoutProvenance, "jenkins", "conformance:closure-topology-provenance"))
        }.exceptionOrNull()
        require(provenanceFailure is InvalidPlanningEvidenceException) {
            "Canonical topology without source provenance was accepted."
        }
        require(provenanceFailure.issues.any { it.code == "planning.topology.canonical.provenance.missing" }) {
            "Missing canonical source provenance lacks a specific planning diagnostic."
        }

        val fullyStripped = plan.copy(
            sourceIntent = null,
            topologyRequirements = plan.topologyRequirements.filterNot {
                it.source == ExecutionTopologyRequirementSource.CANONICAL_WORKFLOW ||
                    it.source == ExecutionTopologyRequirementSource.CANONICAL_CAPABILITY
            }
        )
        val strippedFailure = runCatching {
            MandatoryMaterializationAuthority(targets, registry)
                .authorizeDiagnosticEvidence(diagnosticMaterializationRequest(fullyStripped, "jenkins", "conformance:closure-topology-stripped"))
        }.exceptionOrNull()
        require(strippedFailure is InvalidPlanningEvidenceException) {
            "Removing both canonical claims and their source provenance hid intent-derived topology."
        }
        require(strippedFailure.issues.any { it.code == "planning.topology.canonical.provenance.missing" }) {
            "Stripped canonical provenance failed without a specific planning diagnostic."
        }
    }

    private fun checkRetainedDerivedProjection() {
        val damaged = ExecutionPlan(
            flowName = "closure-derived-projection",
            nodes = listOf(
                TaskNode(
                    id = "task",
                    module = "custom",
                    action = "run",
                    target = "system",
                    dependsOn = emptyList(),
                    dependencies = listOf("hidden")
                ),
                ApprovalNode(
                    id = "approve",
                    dependsOn = emptyList(),
                    dependencies = listOf("hidden")
                )
            )
        )
        val failure = runCatching {
            MandatoryMaterializationAuthority(targets, registry)
                .authorizeDiagnosticEvidence(diagnosticMaterializationRequest(damaged, "jenkins", "conformance:closure-derived-projection"))
        }.exceptionOrNull()
        require(failure is InvalidPlanningEvidenceException) {
            "Divergent retained dependency projections were accepted."
        }
        require(failure.issues.any { it.code == "planning.dependency.projection.invalid" }) {
            "Derived dependency divergence lacks a specific planning diagnostic."
        }
    }

    private fun checkGitHubCancellationSemantics() {
        val compatibility = CompatibilityAnalyzer(targets).analyze(
            ExecutionPlan(flowName = "github-condition"),
            "github-actions"
        )
        val ordinaryManifest = TargetManifest(
            target = "github-actions",
            flowName = "github-condition",
            compatibility = compatibility,
            jobs = listOf(
                TargetJob(id = "build"),
                TargetJob(id = "test", dependsOn = listOf("build"))
            )
        )
        require(GitHubJobConditionAuthority.expression(ordinaryManifest.jobs.last(), ordinaryManifest) == null) {
            "Ordinary GitHub dependencies override native success or cancellation semantics."
        }

        val approvalManifest = ordinaryManifest.copy(
            jobs = listOf(
                TargetJob(id = "approve", metadata = mapOf("providerApprovalPayload" to "true")),
                TargetJob(id = "deploy", dependsOn = listOf("approve"))
            )
        )
        val expression = GitHubJobConditionAuthority.expression(approvalManifest.jobs.last(), approvalManifest).orEmpty()
        require(expression.contains("!cancelled()") && !expression.contains("always()")) {
            "Provider-approval dependency evaluation does not preserve workflow cancellation."
        }

        val mixedManifest = ordinaryManifest.copy(
            jobs = listOf(
                TargetJob(id = "build"),
                TargetJob(id = "approve", metadata = mapOf("providerApprovalPayload" to "true")),
                TargetJob(id = "deploy", dependsOn = listOf("approve", "build"))
            )
        )
        val mixedExpression = GitHubJobConditionAuthority.expression(
            mixedManifest.jobs.last(),
            mixedManifest
        ).orEmpty()
        require(mixedExpression.contains("needs.approve.result == 'skipped'")) {
            "Provider-backed approval skip evidence was lost in a mixed dependency set."
        }
        require(mixedExpression.contains("needs.build.result == 'success'")) {
            "A mixed provider-approval condition bypasses the success gate of an ordinary dependency."
        }
        require(!mixedExpression.contains("needs.build.result == 'skipped'")) {
            "An ordinary dependency was incorrectly granted approval-style skip semantics."
        }
    }

    private fun checkExactHeadCiBoundary() {
        val workflow = File(rootDir, ".github/workflows/flow-agent-check.yml").readText()
        require(workflow.contains("github.event.pull_request.head.sha")) {
            "Flow CI does not select the pull request head SHA for exact-head validation."
        }
        require(workflow.contains("Verify Exact Checked-Out Revision")) {
            "Flow CI does not verify the revision checked out for exact-head validation."
        }
        require(workflow.contains("git rev-parse HEAD")) {
            "Flow CI does not compare the checked-out Git revision to the declared evidence SHA."
        }
        require(workflow.contains("merge-candidate-compile-test-conformance")) {
            "Flow CI lost the separate pull-request merge-candidate validation."
        }
    }

    private fun checkReleaseLifecycle() {
        val release = ReleaseMetadataHonestyAuthority(rootDir).requireValid()
        require(release.completedCorrectionItem.startsWith("0.9.7.9.")) {
            "Release honesty selected correction '${release.completedCorrectionItem}' outside the bounded 0.9.7.9.x track."
        }
        require(release.correctionStatus == "complete") {
            "Semantic closure conformance cannot run while bounded correction '${release.completedCorrectionItem}' is ${release.correctionStatus}."
        }
        require(release.parentCoreItemStatus == "completed") {
            "The corrected parent Core item must be completed before READY or CLOSED closure validation."
        }

        when (release.closurePhase) {
            "READY" -> {
                require(release.closureWorkPackageStatus == "active")
                require(release.closureStatus == "next")
                require(release.coreTrackStatus == "active")
                require(release.completedCoreItem == "0.9.7.9")
            }
            "CLOSED" -> {
                require(release.closureWorkPackageStatus == "complete")
                require(release.closureStatus == "completed")
                require(release.coreTrackStatus == "completed")
                require(release.completedCoreItem == release.closureItem)
            }
            else -> error("Unknown or invalid semantic closure phase '${release.closurePhase}'.")
        }
    }
}
