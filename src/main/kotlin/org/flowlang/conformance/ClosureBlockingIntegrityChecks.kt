package org.flowlang.conformance

import java.io.File
import org.flowlang.architecture.CiCdBiasInventoryAnalyzer
import org.flowlang.capabilities.CompatibilityAnalyzer
import org.flowlang.cli.honest.CliTargetEvidenceAuthority
import org.flowlang.cli.honest.CliTargetEvidenceOutcome
import org.flowlang.controls.AuthoredControlEvidenceTextAuthority
import org.flowlang.controls.AuthoredControlEvidenceTextStatus
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
            checkCorrectionLifecycle()
        }

    private fun checkAuthoredControlEvidence() {
        listOf("unknown", "TODO", "n/a", "pending", "to be confirmed").forEach { value ->
            require(
                AuthoredControlEvidenceTextAuthority.assess("backup", IntentString(value)).status ==
                    AuthoredControlEvidenceTextStatus.UNKNOWN
            ) { "Placeholder '$value' was accepted as positive backup evidence." }
        }
        require(
            AuthoredControlEvidenceTextAuthority
                .assess("backup", IntentString("s3://recovery/db-before-migration-42"))
                .status == AuthoredControlEvidenceTextStatus.CONFIRMED
        ) { "A concrete backup reference was not recognized as authored evidence." }
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
        require(!File(rootDir, "src/main/kotlin/org/flowlang/cli/FlowCli.kt").exists()) {
            "The legacy CLI entrypoint remains compiled in the application."
        }
        val cli = File(rootDir, "src/main/kotlin/org/flowlang/cli/honest/HonestFlowCli.kt").readText()
        require(!Regex("parseOption\\(args, \\\"--target\\\"\\)\\s*\\?:\\s*\\\"jenkins\\\"").containsMatchIn(cli)) {
            "The public CLI still selects Jenkins when target selection is absent."
        }
        require(!cli.contains("legacyMain")) {
            "The public CLI still delegates unknown commands to a legacy entrypoint."
        }
        require(cli.contains("--render requires an explicit --target")) {
            "The CLI does not expose its explicit target-selection boundary."
        }

        val intent = org.flowlang.adapters.yaml.IntentYamlLoader.load(
            File(rootDir, "examples/intent/build-test-deploy.intent.yaml")
        )
        val plan = FlowPlanner(registry).plan(IntentToAstPlanner(registry).plan(intent))
        val review = CliTargetEvidenceAuthority(targets, projections).evaluate(
            plan = plan,
            target = "github-actions",
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
        val damaged = plan.copy(
            topologyRequirements = plan.topologyRequirements.filterNot {
                it.source == ExecutionTopologyRequirementSource.CANONICAL_WORKFLOW ||
                    it.source == ExecutionTopologyRequirementSource.CANONICAL_CAPABILITY
            }
        )
        val failure = runCatching {
            MandatoryMaterializationAuthority(targets, registry)
                .authorizeDiagnosticEvidence(damaged, "jenkins")
        }.exceptionOrNull()
        require(failure is InvalidPlanningEvidenceException) {
            "Omitted canonical topology did not fail at the materialization authority."
        }
        require(failure.issues.any { it.code == "planning.topology.canonical.missing" }) {
            "Canonical topology loss failed without a specific planning diagnostic."
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
                .authorizeDiagnosticEvidence(damaged, "jenkins")
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
    }

    private fun checkCorrectionLifecycle() {
        val release = ReleaseMetadataHonestyAuthority(rootDir).requireValid()
        require(release.completedCorrectionItem == "0.9.7.9.8")
        when (release.correctionStatus) {
            "active" -> {
                require(release.parentCoreItemStatus == "correction-required")
                require(release.closureStatus == "blocked")
            }
            "complete" -> {
                require(release.parentCoreItemStatus == "completed")
                require(release.closureStatus == "next")
            }
            else -> error("Unknown bounded correction status '${release.correctionStatus}'.")
        }
    }
}