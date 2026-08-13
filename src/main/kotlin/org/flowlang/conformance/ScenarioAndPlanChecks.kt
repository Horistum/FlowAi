package org.flowlang.conformance

import org.flowlang.capabilities.CompatibilityAnalyzer
import org.flowlang.ai.normalization.AiIntentRequest
import org.flowlang.ai.normalization.ClarificationSeverity
import org.flowlang.ai.normalization.ScenarioPackIntentNormalizer
import org.flowlang.intent.IntentCapabilityValidator
import org.flowlang.intent.IntentToAstPlanner
import org.flowlang.modules.ModuleRegistry
import org.flowlang.planner.CanonicalExecutionPlanSemanticsAuthority
import org.flowlang.planner.ExecutionPlanCanonicalizer
import org.flowlang.planner.FlowPlanner
import org.flowlang.standard.FlowStandardVersions
import org.flowlang.validator.FlowValidator
import java.io.File
import org.flowlang.generators.manifest.TargetProjectionRegistry

internal class ScenarioAndPlanChecks(
    rootDir: File,
    registry: ModuleRegistry,
    targets: Map<String, org.flowlang.capabilities.TargetCapability>,
    projections: TargetProjectionRegistry
) : ConformanceCheckSupport(rootDir, registry, targets, projections) {
    fun checks(): List<ConformanceCheck> = listOf(
        checkScenarioPackNormalizationFullPipelines(),
        checkScenarioPackRegressionCoverage(),
        checkV031ScenarioPackVectors(),
        checkV031CapabilityNegotiationReport(),
        checkV032CanonicalExecutionPlan(),
        checkV032SafetyPolicies()
    )

    private fun checkScenarioPackNormalizationFullPipelines(): ConformanceCheck = runCheck("scenario-packs.normalization.full-pipelines") {
        val samples = listOf(
            "Deploy application billing-api to Kubernetes. Require approval in production. Verify health after deploy and rollback on failure.",
            "Back up PostgreSQL database every night, keep backups for 14 days and notify the team on failure.",
            "Synchronize customers from CRM to warehouse, transform fields and notify on failure.",
            "Rotate secret payment-api-token, verify the service and notify the security team.",
            "Run database migration for orders database to version 2026.06, create backup first, require approval, rollback on failure and verify schema after migration.",
            "Renew certificate api-gateway in namespace edge and verify service gateway after renewal.",
            "Run Kubernetes maintenance in namespace payments, drain nodes with approval, dry-run first and verify pods are healthy.",
            "Run incident runbook for api outage, collect diagnostics, notify the team and verify recovery."
        )
        samples.forEach { sample ->
            val response = ScenarioPackIntentNormalizer().normalize(AiIntentRequest(sample))
            val intentReport = IntentCapabilityValidator(registry).validate(response.normalizedIntent)
            if (response.report.openQuestions.none { it.severity == ClarificationSeverity.REQUIRED }) {
                intentReport.assertValid()
                val ast = IntentToAstPlanner(registry).plan(response.normalizedIntent)
                val validation = FlowValidator(registry).validate(ast)
                require(validation.valid) { "Scenario pack produced invalid Flow AST for '$sample': " + validation.issues.joinToString { it.code + ": " + it.message } }
                val plan = FlowPlanner(registry).plan(ast)
                require(plan.nodes.isNotEmpty()) { "Scenario pack produced empty execution plan for '$sample'." }
            }
        }
    }

    private fun checkScenarioPackRegressionCoverage(): ConformanceCheck = runCheck("scenario-packs.regression-coverage") {
        val normalizer = ScenarioPackIntentNormalizer()
        val build = normalizer.normalize(AiIntentRequest("Build and test the repo."))
        require(build.report.classification.type == "build-test") { "Build/test request must not fall back to custom." }
        val provision = normalizer.normalize(AiIntentRequest("Provision infrastructure with terraform."))
        require(provision.report.classification.type == "provision") { "Provisioning request must not fall back to custom." }
        val cleanup = normalizer.normalize(AiIntentRequest("Cleanup old docker images."))
        require(cleanup.report.classification.type == "cleanup") { "Cleanup request must not fall back to custom." }
        require(cleanup.report.openQuestions.any { it.severity == ClarificationSeverity.REQUIRED && it.field == "safety.cleanup.retention" }) { "Cleanup without retention must ask for required safety clarification." }
        require(!IntentCapabilityValidator(registry).validate(cleanup.normalizedIntent).valid) { "Cleanup without retention/safety must block lowering by default." }
        val cleanupWithRetention = normalizer.normalize(AiIntentRequest("Cleanup docker images older than 14 days."))
        require(cleanupWithRetention.report.classification.type == "cleanup") { "Cleanup with retention must still use cleanup pack." }
        IntentCapabilityValidator(registry).validate(cleanupWithRetention.normalizedIntent).assertValid()
        val rollback = normalizer.normalize(AiIntentRequest("Rollback the release."))
        require(rollback.report.classification.type != "deployment") { "Rollback-only request must not synthesize a full deployment." }
        val missing = normalizer.normalize(AiIntentRequest("Synchronize customer data and notify on failure."))
        require(missing.report.openQuestions.any { it.severity == ClarificationSeverity.REQUIRED }) { "Missing data-sync source/destination must be a required clarification." }
        require(!IntentCapabilityValidator(registry).validate(missing.normalizedIntent).valid) { "Required clarifications must block lowering by default." }
    }

    private fun checkV031ScenarioPackVectors(): ConformanceCheck = runCheck("v0.3.1.scenario-packs") {
        val normalizer = ScenarioPackIntentNormalizer()
        val database = normalizer.normalize(AiIntentRequest("Run database migration for orders database to version 2026.06, create backup first, require approval, rollback on failure and verify schema after migration."))
        require(database.report.classification.type == "database-migration") { "Database migration request must select database-migration pack." }
        require(database.normalizedIntent.workflows.flatMap { it.steps }.any { it.capability.name == "DATABASE_MIGRATE" }) { "Database migration pack must emit DATABASE_MIGRATE." }
        IntentCapabilityValidator(registry).validate(database.normalizedIntent).assertValid()

        val cert = normalizer.normalize(AiIntentRequest("Renew certificate api-gateway in namespace edge and verify service gateway after renewal."))
        require(cert.report.classification.type == "certificate-renewal") { "Certificate request must select certificate-renewal pack." }
        require(cert.normalizedIntent.workflows.flatMap { it.steps }.any { it.capability.name == "CERTIFICATE_RENEW" }) { "Certificate pack must emit CERTIFICATE_RENEW." }
        IntentCapabilityValidator(registry).validate(cert.normalizedIntent).assertValid()

        val k8s = normalizer.normalize(AiIntentRequest("Run Kubernetes maintenance in namespace payments, drain nodes with approval, dry-run first and verify pods are healthy."))
        require(k8s.report.classification.type == "kubernetes-maintenance") { "Kubernetes request must select kubernetes-maintenance pack." }
        require(k8s.normalizedIntent.workflows.flatMap { it.steps }.any { it.capability.name == "CLUSTER_MAINTENANCE" }) { "Kubernetes maintenance pack must emit target-neutral CLUSTER_MAINTENANCE." }
        IntentCapabilityValidator(registry).validate(k8s.normalizedIntent).assertValid()

        val missingDb = normalizer.normalize(AiIntentRequest("Run database migration tomorrow."))
        require(missingDb.report.openQuestions.any { it.severity == ClarificationSeverity.REQUIRED && it.field == "entities.database" }) { "Missing database must be a required clarification." }
        require(!IntentCapabilityValidator(registry).validate(missingDb.normalizedIntent).valid) { "Missing database must block lowering." }
    }

    private fun checkV031CapabilityNegotiationReport(): ConformanceCheck = runCheck("v0.3.1.capability-negotiation") {
        val artifacts = buildPipeline("jenkins", strict = false)
        val negotiation = CompatibilityAnalyzer(targets).negotiate(artifacts.plan, strict = false)
        require(negotiation.targets.isNotEmpty()) { "Negotiation report must include targets." }
        require(negotiation.targets.any { it.target == "jenkins" }) { "Negotiation report must include Jenkins." }
        require(negotiation.targets.any { it.target == "tekton" && it.unsupported.contains("approval.manual") }) {
            "Negotiation report must explain Tekton manual approval gap."
        }
        require(negotiation.requiredCapabilities.isNotEmpty()) { "Negotiation report must list required capabilities." }
    }

    private fun checkV032CanonicalExecutionPlan(): ConformanceCheck = runCheck("v0.3.2.execution-plan.canonical") {
        val artifacts = buildPipeline("jenkins", strict = false)
        val canonical = ExecutionPlanCanonicalizer.canonicalize(artifacts.plan)
        val nodes = canonical.nodes.flatMap { flattenCanonicalNode(it) }
        require(canonical.planVersion == FlowStandardVersions.EXECUTION_PLAN_VERSION) { "Canonical plan must carry current execution plan version." }
        require(nodes.isNotEmpty()) { "Canonical plan must contain nodes." }
        require(nodes.all { it.kind == it.kind.lowercase() }) { "Canonical node kinds must be lowercase: ${nodes.map { it.kind }.distinct().joinToString()}" }
        require(nodes.any { it.kind == "approval" }) { "Canonical plan must expose approval nodes as public lowercase kind." }
        val tasksById = artifacts.plan.tasks.associateBy { it.id }
        nodes.forEach { node ->
            val task = tasksById[node.id] ?: return@forEach
            val expectedKind = CanonicalExecutionPlanKindConformanceOracle.expectedTaskKind(task.semanticCapability)
            val authoritativeKind = CanonicalExecutionPlanSemanticsAuthority
                .kindForSemanticCapability(task.semanticCapability)
                .wireValue
            require(authoritativeKind == expectedKind) {
                "Canonical task '${node.id}' production semantic classification '$authoritativeKind' must equal independent conformance classification '$expectedKind'."
            }
            require(node.kind == expectedKind) {
                "Canonical task '${node.id}' kind '${node.kind}' must equal independent semantic conformance classification '$expectedKind'."
            }
        }
        require(canonical.requiredCapabilities.isNotEmpty()) { "Canonical plan must carry required capabilities." }
    }

    private fun checkV032SafetyPolicies(): ConformanceCheck = runCheck("v0.3.2.safety-policy-validation") {
        val normalizer = ScenarioPackIntentNormalizer()

        val cleanup = normalizer.normalize(AiIntentRequest("Cleanup old docker images."))
        val cleanupReport = IntentCapabilityValidator(registry).validate(cleanup.normalizedIntent)
        require(cleanup.report.openQuestions.any { it.severity == ClarificationSeverity.REQUIRED && it.field == "safety.cleanup.retention" }) {
            "Cleanup without retention must ask for required safety clarification."
        }
        require(cleanupReport.issues.any { it.code == "SAFETY_CLEANUP_REQUIRES_RETENTION" }) {
            "Cleanup without retention must be blocked by safety validator."
        }

        val cleanupWithRetention = normalizer.normalize(AiIntentRequest("Cleanup docker images older than 14 days."))
        IntentCapabilityValidator(registry).validate(cleanupWithRetention.normalizedIntent).assertValid()

        val k8sWithoutDryRun = normalizer.normalize(AiIntentRequest("Run Kubernetes maintenance in namespace payments, drain nodes with approval and verify pods are healthy."))
        val k8sReport = IntentCapabilityValidator(registry).validate(k8sWithoutDryRun.normalizedIntent)
        require(k8sReport.issues.any { it.code == "SAFETY_REQUIRES_DRY_RUN" }) {
            "Kubernetes maintenance without dry-run must be blocked by safety validator."
        }
    }
}