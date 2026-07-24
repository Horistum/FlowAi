package org.flowlang.conformance

import org.flowlang.adapters.yaml.IntentYamlLoader
import org.flowlang.ai.normalization.AiIntentRequest
import org.flowlang.ai.normalization.ScenarioPackIntentNormalizer
import org.flowlang.capabilities.CompatibilityAnalyzer
import org.flowlang.capabilities.ExecutionReadinessAnalyzer
import org.flowlang.capabilities.ExecutionReadinessStatus
import org.flowlang.capabilities.TargetSelectionAnalyzer
import org.flowlang.controls.CanonicalControlRequirementAuthority
import org.flowlang.generators.manifest.TargetCompatibilityReadinessAnalyzer
import org.flowlang.generators.manifest.TargetMaterializationStatus
import org.flowlang.generators.manifest.TargetProjectionRegistry
import org.flowlang.generators.manifest.TargetRenderMode
import org.flowlang.generators.manifest.TargetRenderPolicy
import org.flowlang.intent.IntentDecisionAnalyzer
import org.flowlang.intent.IntentDocument
import org.flowlang.intent.IntentPolicy
import org.flowlang.intent.IntentPolicyType
import org.flowlang.intent.IntentStep
import org.flowlang.intent.IntentWorkflow
import org.flowlang.intent.IntentWorkflowKind
import org.flowlang.intent.StandardCapability
import org.flowlang.modules.ModuleContractAnalyzer
import org.flowlang.modules.ModuleRegistry
import org.flowlang.planner.ApprovalNode
import org.flowlang.planner.ExecutionPlan
import org.flowlang.topology.CanonicalTopologyRequirementAuthority
import org.flowlang.topology.ExecutionTopologyKind
import java.io.File

internal class PlanningReadinessChecks(
    rootDir: File,
    registry: ModuleRegistry,
    targets: Map<String, org.flowlang.capabilities.TargetCapability>,
    projections: TargetProjectionRegistry
) : ConformanceCheckSupport(rootDir, registry, targets, projections) {
    fun checks(): List<ConformanceCheck> = listOf(
        checkV034CapabilityModuleContracts(),
        checkV035IntentDecisionModel(),
        checkV036ExecutionPlanPortability(),
        checkV037ExecutionReadinessReport(),
        checkV038TargetSelectionReport(),
        checkProviderBackedApprovalAndTopologyIdentity()
    )

    private fun checkV034CapabilityModuleContracts(): ConformanceCheck = runCheck("v0.3.4.capability-module-contracts") {
        val report = ModuleContractAnalyzer.analyze(registry)
        val errors = report.issues.filter { it.level == "error" }
        require(report.valid) { "Capability module contract report contains errors: ${errors.joinToString { it.code + " " + it.module + "." + (it.action ?: "") }}" }
        require(report.totals.modules >= 5) { "Expected loaded module descriptors." }
        require(report.totals.actions > 0) { "Capability module contract report must include actions." }
        require(report.modules.any { it.name == "docker" && it.actions.any { action -> action.name == "build" && action.outputs.contains("digest") } }) {
            "Docker build contract must expose image digest output."
        }
        require(report.modules.any { it.actions.any { action -> action.destructive && action.requiresSafety } }) {
            "At least one destructive action must require safety so adapters can enforce gates."
        }
        require(report.modules.flatMap { it.actions }.any { it.requiredCapabilities.isNotEmpty() }) {
            "Module contracts must expose planner-consumed required capabilities."
        }
    }

    private fun checkV035IntentDecisionModel(): ConformanceCheck = runCheck("v0.3.5.intent-decision-model") {
        val normalizer = ScenarioPackIntentNormalizer()
        val analyzer = IntentDecisionAnalyzer(registry)

        val cleanup = analyzer.analyze(normalizer.normalize(AiIntentRequest("Cleanup old docker images.")).normalizedIntent)
        require(cleanup.missingDecisions.any { it.field == "safety.cleanup.retention" && it.blocksLowering }) {
            "Cleanup without retention must produce a blocking missing decision."
        }
        require(!cleanup.validForLowering) { "Cleanup without retention must not be valid for lowering in decision report." }

        val backup = analyzer.analyze(normalizer.normalize(AiIntentRequest("Back up PostgreSQL database every night, keep backups for 14 days and notify the team on failure.")).normalizedIntent)
        require(backup.missingDecisions.any { it.field == "schedule.timezone" && it.severity == "recommended" && !it.blocksLowering }) {
            "Backup schedule without timezone must produce a recommended missing decision."
        }

        val migration = analyzer.analyze(normalizer.normalize(AiIntentRequest("Run database migration for orders database to version 2026.06, require approval and verify schema after migration.")).normalizedIntent)
        require(migration.missingDecisions.any { it.field == "safety.backup" && it.blocksLowering }) {
            "Database migration without backup must produce a blocking backup decision."
        }

        val prodDeploy = IntentYamlLoader.loadText("""
            kind: FlowIntentDocument
            name: prod-deploy-without-approval
            workflows:
              - name: cd
                kind: DEPLOY
                steps:
                  - id: deploy
                    capability: DEPLOY
                    params:
                      namespace: prod
                      environment: prod
                      image: billing-api:1.0
        """.trimIndent())
        val prodReport = analyzer.analyze(prodDeploy)
        require(prodReport.safetyGates.any { it.policy == "forbidProductionWithoutApproval" && it.blocksLowering }) {
            "Production deploy without approval must produce a blocking safety gate."
        }
        require(prodReport.missingDecisions.any { it.field == "safety.approval" && it.blocksLowering }) {
            "Production deploy without approval must ask for approval decision."
        }
    }

    private fun checkV036ExecutionPlanPortability(): ConformanceCheck = runCheck("v0.3.6.execution-plan-portability") {
        val artifacts = buildPipeline("jenkins", strict = false)
        val negotiation = CompatibilityAnalyzer(targets).negotiate(artifacts.plan, strict = false)
        require(negotiation.portabilityScore in 0.0..1.0) { "Portability score must be normalized to 0.0..1.0." }
        require(negotiation.targets.all { it.portabilityScore in 0.0..1.0 }) { "Every target must expose a normalized portability score." }
        require(negotiation.portableCapabilities.isNotEmpty()) { "Report must identify capabilities portable across all registered targets." }
        require(negotiation.targetSpecificCapabilities.contains("approval.manual")) { "Manual approval must be marked target-specific when some targets only partially support or reject it." }
        require(negotiation.blockingPortabilityIssues.any { it.target == "tekton" && it.capability == "approval.manual" }) {
            "Report must expose Tekton manual approval as a blocking portability issue."
        }
        require(negotiation.requiredWorkarounds.any { it.target == "github-actions" && it.capability == "approval.manual" }) {
            "Report must expose GitHub Actions manual approval as a target-specific workaround."
        }
    }

    private fun checkV037ExecutionReadinessReport(): ConformanceCheck = runCheck("v0.3.7.execution-readiness") {
        val artifacts = buildPipeline("jenkins", strict = false)
        val analyzer = ExecutionReadinessAnalyzer(targets)

        val preliminaryJenkins = analyzer.analyze(artifacts.plan, "jenkins", strict = false)
        require(preliminaryJenkins.readiness == ExecutionReadinessStatus.READY) { "Jenkins capability readiness should pass before manifest evaluation." }
        require(!preliminaryJenkins.productionReady) { "Capability readiness alone must not claim production readiness." }
        require(!preliminaryJenkins.readinessEvidenceAvailable) { "Preliminary readiness must expose missing artifact evidence." }
        val jenkins = TargetCompatibilityReadinessAnalyzer.reconcile(preliminaryJenkins, artifacts.manifest)
        require(jenkins.readiness == ExecutionReadinessStatus.DEGRADED) { "Reference Jenkins manifest must be review-only." }
        require(!jenkins.productionReady && !jenkins.executable) { "Review-only Jenkins manifest must not be production-ready." }
        require(jenkins.readinessEvidenceAvailable) { "Concrete Jenkins readiness must carry manifest evidence." }

        val githubManifest = manifestPipeline.generateDiagnosticEvidence(artifacts.plan, "github-actions")
        val github = TargetCompatibilityReadinessAnalyzer.reconcile(
            analyzer.analyze(artifacts.plan, "github-actions", strict = false),
            githubManifest
        )
        require(github.readiness == ExecutionReadinessStatus.BLOCKED) { "GitHub Actions must be blocked without workspace continuity evidence." }
        require(!github.generationAllowed && !github.productionReady && !github.executable) {
            "Blocked GitHub Actions evidence must not claim generation or production readiness."
        }

        val tektonManifest = manifestPipeline.generateDiagnosticEvidence(artifacts.plan, "tekton")
        val tekton = TargetCompatibilityReadinessAnalyzer.reconcile(
            analyzer.analyze(artifacts.plan, "tekton", strict = false),
            tektonManifest
        )
        require(tekton.readiness == ExecutionReadinessStatus.BLOCKED) { "Tekton must remain blocked by effective compatibility." }
        require(!tekton.generationAllowed && !tekton.productionReady) { "Blocked Tekton target must not allow executable generation." }
    }

    private fun checkV038TargetSelectionReport(): ConformanceCheck = runCheck("v0.3.8.target-selection") {
        val artifacts = buildPipeline("jenkins", strict = false)
        val preliminary = TargetSelectionAnalyzer(targets).analyze(artifacts.plan, strict = false)
        require(preliminary.recommendedTarget.isEmpty()) { "Capability-only selection must not recommend a target." }
        val manifests = listOf(
            artifacts.manifest,
            manifestPipeline.generateDiagnosticEvidence(artifacts.plan, "github-actions"),
            manifestPipeline.generateDiagnosticEvidence(artifacts.plan, "tekton")
        )
        val manifestTargets = manifests.map { it.target }.toSet()
        val report = TargetCompatibilityReadinessAnalyzer.reconcile(preliminary, manifests)
        require(report.candidates.size == targets.size) { "Target selection must evaluate every registered target." }
        require(report.recommendedTarget.isEmpty()) { "Reference deployment has no executable target recommendation." }
        require(report.readyTargets.isEmpty()) { "Review-only reference manifests must not be classified as ready." }
        require(report.degradedTargets.contains("jenkins")) { "Jenkins must remain a review-only degraded target." }
        require(report.blockedTargets.containsAll(listOf("github-actions", "tekton"))) {
            "GitHub Actions and Tekton must be classified as blocked."
        }
        require(report.candidates.filter { it.target in manifestTargets }.all { it.readinessEvidenceAvailable }) {
            "Candidates with concrete manifests must carry readiness evidence."
        }
        require(report.candidates.filter { it.target !in manifestTargets }.none { it.readinessEvidenceAvailable }) {
            "Candidates without concrete manifests must remain explicitly unevaluated."
        }
        require(report.candidates.map { it.rank } == (1..report.candidates.size).toList()) { "Candidate ranks must be contiguous." }
    }

    private fun checkProviderBackedApprovalAndTopologyIdentity(): ConformanceCheck =
        runCheck("planning.provider-backed-approval-topology-identity") {
            val plan = ExecutionPlan(
                flowName = "approval-proof",
                nodes = listOf(ApprovalNode(id = "approve", message = "Approve release"))
            )
            val jenkins = manifestPipeline.generateDiagnosticEvidence(plan, "jenkins")
            val jenkinsApproval = jenkins.jobs.single().steps.single()
            require(jenkinsApproval.materialization.status == TargetMaterializationStatus.NATIVE)
            require(jenkinsApproval.rendererPayload?.reference == "input") {
                "Jenkins approval was not backed by its provider-owned input payload."
            }

            val github = manifestPipeline.generateDiagnosticEvidence(plan, "github-actions")
            val githubApproval = github.jobs.single().steps.single()
            require(githubApproval.materialization.status == TargetMaterializationStatus.ADAPTER_REQUIRED)
            require(githubApproval.rendererPayload == null)
            require(TargetRenderPolicy.evaluate(github).mode == TargetRenderMode.REVIEW_ONLY) {
                "GitHub Actions claimed executable approval from capability metadata without provider payload evidence."
            }

            val policies = listOf("prod approval", "prod-approval").map { name ->
                IntentPolicy(name, IntentPolicyType.APPROVAL, message = "Approval for $name")
            }
            val controlIntent = IntentDocument(
                name = "control-identity",
                workflows = listOf(IntentWorkflow(
                    "main",
                    IntentWorkflowKind.DEPLOY,
                    listOf(IntentStep("approve", StandardCapability.APPROVE))
                )),
                policies = policies
            )
            val controls = CanonicalControlRequirementAuthority.requirementsFor(controlIntent)
            require(controls.size == 2 && controls.map { it.id }.toSet().size == 2) {
                "Lossy control slugging discarded or merged a distinct approval obligation."
            }

            val topologyIntent = IntentDocument(
                name = "topology-identity",
                workflows = listOf("release api", "release-api").map { name ->
                    IntentWorkflow(name, IntentWorkflowKind.BUILD, listOf(IntentStep("build-$name", StandardCapability.BUILD)))
                }
            )
            val topology = CanonicalTopologyRequirementAuthority.requirementsFor(topologyIntent)
                .filter { it.kind == ExecutionTopologyKind.WORKFLOW_SCOPE }
            require(topology.size == 2 && topology.map { it.id }.toSet().size == 2) {
                "Lossy topology slugging discarded a distinct workflow scope."
            }
        }
}
