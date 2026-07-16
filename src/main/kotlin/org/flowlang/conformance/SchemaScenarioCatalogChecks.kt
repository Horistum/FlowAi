package org.flowlang.conformance

import org.flowlang.capabilities.CompatibilityAnalyzer
import org.flowlang.capabilities.ExecutionReadinessAnalyzer
import org.flowlang.capabilities.TargetDecisionTraceAnalyzer
import org.flowlang.capabilities.TargetSelectionAnalyzer
import org.flowlang.artifacts.StandardBundleVerifier
import org.flowlang.artifacts.StandardSurface
import org.flowlang.artifacts.PublicStandardDraft
import org.flowlang.adapters.contract.TargetAdapterContractAnalyzer
import org.flowlang.cli.Json
import org.flowlang.intent.IntentCapabilityValidator
import org.flowlang.intent.IntentDecisionAnalyzer
import org.flowlang.intent.IntentDocument
import org.flowlang.modules.ModuleContractAnalyzer
import org.flowlang.modules.ModuleRegistry
import org.flowlang.planner.ExecutionPlanCanonicalizer
import org.flowlang.standard.StandardDiagnosticCatalog
import org.flowlang.scenarios.ScenarioPackRegistry
import java.io.File
import org.flowlang.generators.manifest.TargetProjectionRegistry

internal class SchemaScenarioCatalogChecks(
    rootDir: File,
    registry: ModuleRegistry,
    targets: Map<String, org.flowlang.capabilities.TargetCapability>,
    projections: TargetProjectionRegistry
) : ConformanceCheckSupport(rootDir, registry, targets, projections) {
    fun checks(): List<ConformanceCheck> = listOf(
        checkPublicOutputsMatchSchemas(),
        checkScenarioPackCatalog()
    )

    private fun checkPublicOutputsMatchSchemas(): ConformanceCheck = runCheck("schemas.public-outputs") {
        val artifacts = buildPipeline("jenkins", strict = false)
        val schemaDir = File(rootDir, "schemas")
        val intent = artifacts.intent as org.flowlang.intent.IntentDocument
        JsonSchemaSmokeValidator.validate(Json.mapper.valueToTree(intent), Json.mapper.readTree(File(schemaDir, "intent.schema.json")))
        JsonSchemaSmokeValidator.validate(Json.mapper.valueToTree(IntentDecisionAnalyzer(registry).analyze(intent)), Json.mapper.readTree(File(schemaDir, "intent-decision-report.schema.json")))
        JsonSchemaSmokeValidator.validate(Json.mapper.valueToTree(IntentCapabilityValidator(registry).validate(intent)), Json.mapper.readTree(File(schemaDir, "intent-capability-validation-report.schema.json")))
        JsonSchemaSmokeValidator.validate(Json.mapper.valueToTree(artifacts.ast), Json.mapper.readTree(File(schemaDir, "ast.schema.json")))
        JsonSchemaSmokeValidator.validate(Json.mapper.valueToTree(artifacts.validation), Json.mapper.readTree(File(schemaDir, "validation-report.schema.json")))
        JsonSchemaSmokeValidator.validate(Json.mapper.valueToTree(artifacts.plan), Json.mapper.readTree(File(schemaDir, "execution-plan.schema.json")))
        JsonSchemaSmokeValidator.validate(Json.mapper.valueToTree(ExecutionPlanCanonicalizer.canonicalize(artifacts.plan)), Json.mapper.readTree(File(schemaDir, "execution-plan.schema.json")))
        JsonSchemaSmokeValidator.validate(Json.mapper.valueToTree(artifacts.compatibility), Json.mapper.readTree(File(schemaDir, "compatibility-report.schema.json")))
        JsonSchemaSmokeValidator.validate(Json.mapper.valueToTree(CompatibilityAnalyzer(targets).negotiate(artifacts.plan)), Json.mapper.readTree(File(schemaDir, "capability-negotiation-report.schema.json")))
        JsonSchemaSmokeValidator.validate(Json.mapper.valueToTree(ExecutionReadinessAnalyzer(targets).analyze(artifacts.plan, "jenkins")), Json.mapper.readTree(File(schemaDir, "execution-readiness-report.schema.json")))
        JsonSchemaSmokeValidator.validate(Json.mapper.valueToTree(TargetSelectionAnalyzer(targets).analyze(artifacts.plan)), Json.mapper.readTree(File(schemaDir, "target-selection-report.schema.json")))
        JsonSchemaSmokeValidator.validate(Json.mapper.valueToTree(TargetDecisionTraceAnalyzer(targets).analyze(artifacts.plan, requestedTarget = "jenkins")), Json.mapper.readTree(File(schemaDir, "target-decision-trace-report.schema.json")))
        val adapterContract = TargetAdapterContractAnalyzer(targets).analyze(artifacts.plan, "jenkins")
        JsonSchemaSmokeValidator.validate(Json.mapper.valueToTree(adapterContract), Json.mapper.readTree(File(schemaDir, "target-adapter-contract.schema.json")))
        JsonSchemaSmokeValidator.validate(Json.mapper.valueToTree(adapterContract.diagnostics), Json.mapper.readTree(File(schemaDir, "adapter-diagnostics.schema.json")))
        JsonSchemaSmokeValidator.validate(Json.mapper.valueToTree(StandardDiagnosticCatalog.report()), Json.mapper.readTree(File(schemaDir, "standard-diagnostic-catalog.schema.json")))
        JsonSchemaSmokeValidator.validate(Json.mapper.valueToTree(referenceDiagnosticCoverage(artifacts, "jenkins")), Json.mapper.readTree(File(schemaDir, "diagnostic-coverage-report.schema.json")))
        JsonSchemaSmokeValidator.validate(Json.mapper.valueToTree(referenceArtifactIntegrity(artifacts, "jenkins")), Json.mapper.readTree(File(schemaDir, "artifact-integrity-report.schema.json")))
        JsonSchemaSmokeValidator.validate(Json.mapper.valueToTree(referenceContractIndex(artifacts, "jenkins")), Json.mapper.readTree(File(schemaDir, "standard-contract-index.schema.json")))
        JsonSchemaSmokeValidator.validate(Json.mapper.valueToTree(referenceReleaseProfile()), Json.mapper.readTree(File(schemaDir, "standard-release-profile.schema.json")))
        JsonSchemaSmokeValidator.validate(Json.mapper.valueToTree(referenceEvidence(artifacts, "jenkins")), Json.mapper.readTree(File(schemaDir, "artifact-evidence-report.schema.json")))
        JsonSchemaSmokeValidator.validate(Json.mapper.valueToTree(referenceCompliance(artifacts, "jenkins")), Json.mapper.readTree(File(schemaDir, "standard-compliance-report.schema.json")))
        JsonSchemaSmokeValidator.validate(Json.mapper.valueToTree(referenceFreeze(artifacts, "jenkins")), Json.mapper.readTree(File(schemaDir, "standard-freeze-report.schema.json")))
        JsonSchemaSmokeValidator.validate(Json.mapper.valueToTree(PublicStandardDraft.compatibilityPolicy()), Json.mapper.readTree(File(schemaDir, "compatibility-policy.schema.json")))
        JsonSchemaSmokeValidator.validate(Json.mapper.valueToTree(PublicStandardDraft.referenceCorpus()), Json.mapper.readTree(File(schemaDir, "reference-corpus-index.schema.json")))
        JsonSchemaSmokeValidator.validate(Json.mapper.valueToTree(PublicStandardDraft.negativeCorpus()), Json.mapper.readTree(File(schemaDir, "negative-conformance-corpus.schema.json")))
        JsonSchemaSmokeValidator.validate(Json.mapper.valueToTree(PublicStandardDraft.targetConformanceProfile()), Json.mapper.readTree(File(schemaDir, "target-conformance-profile.schema.json")))
        JsonSchemaSmokeValidator.validate(Json.mapper.valueToTree(StandardSurface.publicSurface()), Json.mapper.readTree(File(schemaDir, "public-standard-surface.schema.json")))
        JsonSchemaSmokeValidator.validate(Json.mapper.valueToTree(StandardSurface.compatibilityMigrationPolicy()), Json.mapper.readTree(File(schemaDir, "compatibility-migration-policy.schema.json")))
        JsonSchemaSmokeValidator.validate(Json.mapper.valueToTree(StandardSurface.referenceIntentCorpus()), Json.mapper.readTree(File(schemaDir, "reference-intent-corpus.schema.json")))
        JsonSchemaSmokeValidator.validate(Json.mapper.valueToTree(StandardSurface.targetSemanticsMatrix()), Json.mapper.readTree(File(schemaDir, "target-semantics-matrix.schema.json")))
        JsonSchemaSmokeValidator.validate(Json.mapper.valueToTree(StandardSurface.standardExportBundle()), Json.mapper.readTree(File(schemaDir, "standard-export-bundle.schema.json")))
        JsonSchemaSmokeValidator.validate(Json.mapper.valueToTree(StandardSurface.conformanceLevels()), Json.mapper.readTree(File(schemaDir, "conformance-levels.schema.json")))
        JsonSchemaSmokeValidator.validate(Json.mapper.valueToTree(StandardSurface.standardExportManifest()), Json.mapper.readTree(File(schemaDir, "standard-export-manifest.schema.json")))
        JsonSchemaSmokeValidator.validate(Json.mapper.valueToTree(ConformanceVectorIndexBuilder(rootDir).build()), Json.mapper.readTree(File(schemaDir, "conformance-vector-index.schema.json")))
        JsonSchemaSmokeValidator.validate(Json.mapper.valueToTree(StandardBundleVerifier().verify(standardBundleFixture())), Json.mapper.readTree(File(schemaDir, "standard-bundle-verification.schema.json")))
        JsonSchemaSmokeValidator.validate(Json.mapper.valueToTree(referenceStandardIndex(artifacts, "jenkins")), Json.mapper.readTree(File(schemaDir, "standard-index.schema.json")))
        JsonSchemaSmokeValidator.validate(Json.mapper.valueToTree(referenceConformanceSuite()), Json.mapper.readTree(File(schemaDir, "conformance-suite.schema.json")))
        JsonSchemaSmokeValidator.validate(Json.mapper.valueToTree(referenceDraft(artifacts, "jenkins")), Json.mapper.readTree(File(schemaDir, "flow-standard-draft.schema.json")))
        JsonSchemaSmokeValidator.validate(Json.mapper.valueToTree(referenceArtifactBundle(artifacts, "jenkins")), Json.mapper.readTree(File(schemaDir, "flow-artifact-bundle.schema.json")))
        JsonSchemaSmokeValidator.validate(Json.mapper.valueToTree(ConformanceManifestBuilder(rootDir).build(ConformanceSummary(listOf(ConformanceCheck("schemas.public-outputs", true))))), Json.mapper.readTree(File(schemaDir, "conformance-manifest.schema.json")))
        JsonSchemaSmokeValidator.validate(Json.mapper.valueToTree(ModuleContractAnalyzer.analyze(registry)), Json.mapper.readTree(File(schemaDir, "capability-module-contract-report.schema.json")))
        JsonSchemaSmokeValidator.validate(Json.mapper.valueToTree(artifacts.manifest), Json.mapper.readTree(File(schemaDir, "target-manifest.schema.json")))
    }

    private fun checkScenarioPackCatalog(): ConformanceCheck = runCheck("scenario-packs.catalog") {
        val ids = ScenarioPackRegistry.packs.map { it.definition.id }.toSet()
        require(ids.containsAll(setOf("deployment", "build-test", "backup-restore", "data-sync", "secret-rotation", "provision", "cleanup", "incident-runbook"))) {
            "Scenario pack registry must cover deployment, build-test, backup/restore, data-sync, secret-rotation, provision, cleanup and incident-runbook."
        }
        require(ids.containsAll(setOf("database-migration", "certificate-renewal", "kubernetes-maintenance"))) {
            "Scenario pack registry must cover v0.3.1 database-migration, certificate-renewal and kubernetes-maintenance packs."
        }
        require(ScenarioPackRegistry.packs.all { it.definition.capabilities.isNotEmpty() }) { "Each scenario pack must declare standard capabilities." }
    }
}
