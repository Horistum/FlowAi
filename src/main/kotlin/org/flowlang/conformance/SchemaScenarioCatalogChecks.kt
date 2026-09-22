package org.flowlang.conformance

import org.flowlang.serialization.FlowJson

import org.flowlang.adapters.contract.TargetAdapterContractAnalyzer
import org.flowlang.artifacts.PublicStandardDraft
import org.flowlang.artifacts.StandardBundleVerifier
import org.flowlang.artifacts.StandardSurface
import org.flowlang.capabilities.CompatibilityAnalyzer
import org.flowlang.capabilities.ExecutionReadinessAnalyzer
import org.flowlang.capabilities.TargetDecisionTraceAnalyzer
import org.flowlang.capabilities.TargetSelectionAnalyzer
import org.flowlang.cli.Json
import org.flowlang.generators.manifest.TargetProjectionRegistry
import org.flowlang.intent.IntentCapabilityValidator
import org.flowlang.intent.IntentDecisionAnalyzer
import org.flowlang.modules.ModuleContractAnalyzer
import org.flowlang.modules.ModuleRegistry
import org.flowlang.planner.ExecutionPlanCanonicalizer
import org.flowlang.scenarios.ScenarioPackRegistry
import org.flowlang.standard.PublishedSchemaContracts
import org.flowlang.standard.StandardDiagnosticCatalog
import java.io.File

internal class SchemaScenarioCatalogChecks(
    rootDir: File,
    registry: ModuleRegistry,
    targets: Map<String, org.flowlang.capabilities.TargetCapability>,
    projections: TargetProjectionRegistry
) : ConformanceCheckSupport(rootDir, registry, targets, projections) {
    private val neutral = TargetNeutralConformanceFixture(rootDir, registry, targets)

    fun checks(): List<ConformanceCheck> = listOf(
        checkPublicOutputsMatchSchemas(),
        checkScenarioPackCatalog()
    )

    private fun checkPublicOutputsMatchSchemas(): ConformanceCheck = runCheck("schemas.public-outputs") {
        val ownershipIssues = PublishedSchemaContracts.coverageIssues(rootDir)
        require(ownershipIssues.isEmpty()) { ownershipIssues.joinToString(" | ") }
        val core = neutral.build()
        val schemaDir = File(rootDir, "schemas")
        val intent = core.intent
        JsonSchemaSmokeValidator.validate(Json.mapper.valueToTree(intent), FlowJson.readTree(File(schemaDir, "intent.schema.json")))
        JsonSchemaSmokeValidator.validate(Json.mapper.valueToTree(IntentDecisionAnalyzer(registry).analyze(intent)), FlowJson.readTree(File(schemaDir, "intent-decision-report.schema.json")))
        JsonSchemaSmokeValidator.validate(Json.mapper.valueToTree(IntentCapabilityValidator(registry).validate(intent)), FlowJson.readTree(File(schemaDir, "intent-capability-validation-report.schema.json")))
        JsonSchemaSmokeValidator.validate(Json.mapper.valueToTree(core.ast), FlowJson.readTree(File(schemaDir, "ast.schema.json")))
        JsonSchemaSmokeValidator.validate(Json.mapper.valueToTree(core.validation), FlowJson.readTree(File(schemaDir, "validation-report.schema.json")))
        JsonSchemaSmokeValidator.validate(Json.mapper.valueToTree(ExecutionPlanCanonicalizer.canonicalize(core.plan)), FlowJson.readTree(File(schemaDir, "execution-plan.schema.json")))
        JsonSchemaSmokeValidator.validate(Json.mapper.valueToTree(CompatibilityAnalyzer(targets).negotiate(core.plan)), FlowJson.readTree(File(schemaDir, "capability-negotiation-report.schema.json")))
        JsonSchemaSmokeValidator.validate(Json.mapper.valueToTree(TargetSelectionAnalyzer(targets).analyze(core.plan)), FlowJson.readTree(File(schemaDir, "target-selection-report.schema.json")))

        targets.keys.sorted().forEach { target ->
            JsonSchemaSmokeValidator.validate(Json.mapper.valueToTree(CompatibilityAnalyzer(targets).analyze(core.plan, target)), FlowJson.readTree(File(schemaDir, "compatibility-report.schema.json")))
            JsonSchemaSmokeValidator.validate(Json.mapper.valueToTree(ExecutionReadinessAnalyzer(targets).analyze(core.plan, target)), FlowJson.readTree(File(schemaDir, "execution-readiness-report.schema.json")))
            JsonSchemaSmokeValidator.validate(Json.mapper.valueToTree(TargetDecisionTraceAnalyzer(targets).analyze(core.plan, requestedTarget = target)), FlowJson.readTree(File(schemaDir, "target-decision-trace-report.schema.json")))
            val adapterContract = TargetAdapterContractAnalyzer(targets).analyze(core.plan, target)
            JsonSchemaSmokeValidator.validate(Json.mapper.valueToTree(adapterContract), FlowJson.readTree(File(schemaDir, "target-adapter-contract.schema.json")))
            JsonSchemaSmokeValidator.validate(Json.mapper.valueToTree(adapterContract.diagnostics), FlowJson.readTree(File(schemaDir, "adapter-diagnostics.schema.json")))
        }

        JsonSchemaSmokeValidator.validate(Json.mapper.valueToTree(StandardDiagnosticCatalog.report()), FlowJson.readTree(File(schemaDir, "standard-diagnostic-catalog.schema.json")))
        JsonSchemaSmokeValidator.validate(Json.mapper.valueToTree(neutral.diagnosticCoverage(core)), FlowJson.readTree(File(schemaDir, "diagnostic-coverage-report.schema.json")))
        JsonSchemaSmokeValidator.validate(Json.mapper.valueToTree(neutral.artifactIntegrity(core)), FlowJson.readTree(File(schemaDir, "artifact-integrity-report.schema.json")))
        JsonSchemaSmokeValidator.validate(Json.mapper.valueToTree(neutral.contractIndex(core)), FlowJson.readTree(File(schemaDir, "standard-contract-index.schema.json")))
        JsonSchemaSmokeValidator.validate(Json.mapper.valueToTree(referenceReleaseProfile()), FlowJson.readTree(File(schemaDir, "standard-release-profile.schema.json")))
        JsonSchemaSmokeValidator.validate(Json.mapper.valueToTree(neutral.evidence(core)), FlowJson.readTree(File(schemaDir, "artifact-evidence-report.schema.json")))
        val passingCompliance = neutral.compliance(core, neutral.passingManifest())
        JsonSchemaSmokeValidator.validate(Json.mapper.valueToTree(passingCompliance), FlowJson.readTree(File(schemaDir, "standard-compliance-report.schema.json")))
        JsonSchemaSmokeValidator.validate(Json.mapper.valueToTree(neutral.freeze(core)), FlowJson.readTree(File(schemaDir, "standard-freeze-report.schema.json")))
        JsonSchemaSmokeValidator.validate(Json.mapper.valueToTree(PublicStandardDraft.compatibilityPolicy()), FlowJson.readTree(File(schemaDir, "compatibility-policy.schema.json")))
        JsonSchemaSmokeValidator.validate(Json.mapper.valueToTree(PublicStandardDraft.referenceCorpus()), FlowJson.readTree(File(schemaDir, "reference-corpus-index.schema.json")))
        JsonSchemaSmokeValidator.validate(Json.mapper.valueToTree(PublicStandardDraft.negativeCorpus()), FlowJson.readTree(File(schemaDir, "negative-conformance-corpus.schema.json")))
        JsonSchemaSmokeValidator.validate(Json.mapper.valueToTree(PublicStandardDraft.targetConformanceProfile()), FlowJson.readTree(File(schemaDir, "target-conformance-profile.schema.json")))
        JsonSchemaSmokeValidator.validate(Json.mapper.valueToTree(StandardSurface.publicSurface()), FlowJson.readTree(File(schemaDir, "public-standard-surface.schema.json")))
        JsonSchemaSmokeValidator.validate(Json.mapper.valueToTree(StandardSurface.compatibilityMigrationPolicy()), FlowJson.readTree(File(schemaDir, "compatibility-migration-policy.schema.json")))
        JsonSchemaSmokeValidator.validate(Json.mapper.valueToTree(StandardSurface.referenceIntentCorpus()), FlowJson.readTree(File(schemaDir, "reference-intent-corpus.schema.json")))
        JsonSchemaSmokeValidator.validate(Json.mapper.valueToTree(org.flowlang.distribution.reference.ReferenceStandardArtifacts.targetSemanticsMatrix()), FlowJson.readTree(File(schemaDir, "target-semantics-matrix.schema.json")))
        JsonSchemaSmokeValidator.validate(Json.mapper.valueToTree(StandardSurface.standardExportBundle()), FlowJson.readTree(File(schemaDir, "standard-export-bundle.schema.json")))
        JsonSchemaSmokeValidator.validate(Json.mapper.valueToTree(StandardSurface.conformanceLevels()), FlowJson.readTree(File(schemaDir, "conformance-levels.schema.json")))
        JsonSchemaSmokeValidator.validate(Json.mapper.valueToTree(StandardSurface.standardExportManifest()), FlowJson.readTree(File(schemaDir, "standard-export-manifest.schema.json")))
        JsonSchemaSmokeValidator.validate(Json.mapper.valueToTree(ConformanceVectorIndexBuilder(rootDir).build()), FlowJson.readTree(File(schemaDir, "conformance-vector-index.schema.json")))
        JsonSchemaSmokeValidator.validate(Json.mapper.valueToTree(StandardBundleVerifier().verify(StrictStandardBundleFixture.create(rootDir))), FlowJson.readTree(File(schemaDir, "standard-bundle-verification.schema.json")))
        JsonSchemaSmokeValidator.validate(Json.mapper.valueToTree(neutral.standardIndex(core)), FlowJson.readTree(File(schemaDir, "standard-index.schema.json")))
        JsonSchemaSmokeValidator.validate(Json.mapper.valueToTree(referenceConformanceSuite()), FlowJson.readTree(File(schemaDir, "conformance-suite.schema.json")))
        JsonSchemaSmokeValidator.validate(Json.mapper.valueToTree(neutral.draft(core, passingCompliance)), FlowJson.readTree(File(schemaDir, "flow-standard-draft.schema.json")))
        JsonSchemaSmokeValidator.validate(Json.mapper.valueToTree(neutral.artifactBundle(core)), FlowJson.readTree(File(schemaDir, "flow-artifact-bundle.schema.json")))
        JsonSchemaSmokeValidator.validate(Json.mapper.valueToTree(ConformanceManifestBuilder(rootDir).build(ConformanceSummary(listOf(ConformanceCheck("schemas.public-outputs", true))))), FlowJson.readTree(File(schemaDir, "conformance-manifest.schema.json")))
        JsonSchemaSmokeValidator.validate(Json.mapper.valueToTree(ModuleContractAnalyzer.analyze(registry)), FlowJson.readTree(File(schemaDir, "capability-module-contract-report.schema.json")))

        val targetManifest = buildPipeline("jenkins", strict = false).manifest
        JsonSchemaSmokeValidator.validate(Json.mapper.valueToTree(targetManifest), FlowJson.readTree(File(schemaDir, "target-manifest.schema.json")))
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
