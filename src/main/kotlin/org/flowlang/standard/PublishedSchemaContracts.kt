package org.flowlang.standard

import java.io.File

enum class PublishedSchemaAcceptanceKind {
    /** JSON Schema constrains interchange shape; production owners remain authoritative for semantic validity. */
    SYNTACTIC_INTERCHANGE,

    /** Reserved for a schema with bidirectional proof that schema and production acceptance are equivalent. */
    PRODUCTION_VALIDITY
}

data class PublishedSchemaContract(
    val path: String,
    val authority: PublishedSchemaAcceptanceKind,
    val productionOwner: String
)

/**
 * Explicit ownership index for every published JSON Schema.
 *
 * A schema is not silently promoted into a production validator merely because a
 * conformance smoke check happens to consume it. Production validity remains with
 * the named loader, typed contract or report producer unless bidirectional proof
 * justifies [PublishedSchemaAcceptanceKind.PRODUCTION_VALIDITY].
 */
object PublishedSchemaContracts {
    val contracts: List<PublishedSchemaContract> = listOf(
        interchange("adapter-diagnostics.schema.json"),
        interchange("ai-normalization-report.schema.json"),
        interchange("artifact-evidence-report.schema.json"),
        interchange("artifact-integrity-report.schema.json"),
        interchange("ast.schema.json", "org.flowlang.ast.FlowAst serialized contract"),
        interchange("capability-module-contract-report.schema.json"),
        interchange("capability-negotiation-report.schema.json", "org.flowlang.capabilities.TargetCapabilityNegotiationReport"),
        interchange("cli-target-outcome.schema.json"),
        interchange("compatibility-migration-policy.schema.json"),
        interchange("compatibility-policy.schema.json"),
        interchange("compatibility-report.schema.json", "org.flowlang.capabilities.CompatibilityReport"),
        interchange("conformance-levels.schema.json"),
        interchange("conformance-manifest.schema.json", "org.flowlang.conformance.ConformanceManifestBuilder"),
        interchange("conformance-suite.schema.json"),
        interchange("conformance-vector-index.schema.json", "org.flowlang.conformance.ConformanceVectorIndexBuilder"),
        interchange("diagnostic-coverage-report.schema.json"),
        interchange("execution-plan.schema.json", "org.flowlang.planner.ExecutionPlan serialized contract"),
        interchange("execution-readiness-report.schema.json", "org.flowlang.capabilities.ExecutionReadinessReport"),
        interchange("flow-artifact-bundle.schema.json"),
        interchange("flow-standard-draft.schema.json"),
        interchange("intent-capability-validation-report.schema.json", "org.flowlang.intent.IntentValidationReport"),
        interchange("intent-decision-report.schema.json"),
        interchange("intent-design-report.schema.json"),
        interchange("intent.schema.json", "org.flowlang.intent.IntentDocument + org.flowlang.intent.IntentYamlLoader"),
        interchange("module.schema.json", "org.flowlang.modules.CanonicalModuleLoader"),
        interchange("negative-conformance-corpus.schema.json"),
        interchange("operational-domain-case.schema.json", "org.flowlang.conformance.OperationalDomainCorpusLoader"),
        interchange("operational-domain-corpus.schema.json", "org.flowlang.conformance.OperationalDomainCorpusLoader"),
        interchange("public-standard-surface.schema.json"),
        interchange("real-world-case.schema.json", "org.flowlang.conformance.RealWorldCorpusLoader"),
        interchange("real-world-corpus.schema.json", "org.flowlang.conformance.RealWorldCorpusLoader"),
        interchange("real-world-evidence.schema.json", "org.flowlang.conformance.RealWorldCorpusLoader"),
        interchange("real-world-expected-plan.schema.json", "org.flowlang.conformance.RealWorldCorpusLoader"),
        interchange("real-world-mutation.schema.json", "org.flowlang.conformance.RealWorldCorpusLoader"),
        interchange("real-world-provenance.schema.json", "org.flowlang.conformance.RealWorldCorpusLoader"),
        interchange("real-world-scenario.schema.json", "org.flowlang.conformance.RealWorldCorpusLoader"),
        interchange("real-world-source.schema.json", "org.flowlang.conformance.RealWorldCorpusLoader"),
        interchange("real-world-target-assessments.schema.json", "org.flowlang.conformance.RealWorldCorpusLoader"),
        interchange("reference-corpus-index.schema.json"),
        interchange("reference-intent-corpus.schema.json"),
        interchange("release-metadata-honesty-report.schema.json", "org.flowlang.release.ReleaseMetadataHonestyAuthority"),
        interchange("scenario-pack.schema.json", "org.flowlang.scenarios.ScenarioPackRegistry"),
        interchange("standard-bundle-verification.schema.json"),
        interchange("standard-compliance-report.schema.json"),
        interchange("standard-contract-index.schema.json"),
        interchange("standard-diagnostic-catalog.schema.json", "org.flowlang.standard.StandardDiagnosticCatalog"),
        interchange("standard-export-bundle.schema.json"),
        interchange("standard-export-manifest.schema.json"),
        interchange("standard-freeze-report.schema.json"),
        interchange("standard-index.schema.json"),
        interchange("standard-intent-catalog.schema.json"),
        interchange("standard-release-profile.schema.json"),
        interchange("target-adapter-contract.schema.json"),
        interchange("target-artifact-evidence.schema.json"),
        interchange("target-conformance-profile.schema.json"),
        interchange("target-decision-trace-report.schema.json", "org.flowlang.capabilities.TargetDecisionTraceAnalyzer"),
        interchange("target-manifest.schema.json", "org.flowlang.generators.manifest.TargetManifest serialized contract"),
        interchange("target-neutral-planning-report.schema.json"),
        interchange("target-registry.schema.json", "org.flowlang.targets.TargetRegistryYamlLoader"),
        interchange("target-render-readiness.schema.json"),
        interchange("target-selection-evidence.schema.json"),
        interchange("target-selection-report.schema.json", "org.flowlang.capabilities.TargetSelectionReport"),
        interchange("target-semantics-matrix.schema.json"),
        interchange("validation-report.schema.json"),
        interchange(
            "workflow-execution-plan-set.schema.json",
            "org.flowlang.planner.WorkflowExecutionPlanSet serialized contract"
        )
    )

    fun coverageIssues(rootDir: File): List<String> {
        val schemaDir = File(rootDir, "schemas")
        val actual = schemaDir.listFiles { file -> file.isFile && file.name.endsWith(".schema.json") }
            ?.map { "schemas/${it.name}" }
            ?.sorted()
            .orEmpty()
        val declared = contracts.map(PublishedSchemaContract::path)
        return buildList {
            val duplicates = declared.groupingBy { it }.eachCount().filterValues { it != 1 }.keys.sorted()
            if (duplicates.isNotEmpty()) add("Schema ownership index contains duplicate paths: ${duplicates.joinToString()}.")
            val missing = actual - declared.toSet()
            if (missing.isNotEmpty()) add("Published schemas missing ownership classification: ${missing.joinToString()}.")
            val stale = declared.toSet() - actual.toSet()
            if (stale.isNotEmpty()) add("Schema ownership index references missing files: ${stale.sorted().joinToString()}.")
            contracts.filter { it.productionOwner.isBlank() }.forEach {
                add("Published schema '${it.path}' has no production owner.")
            }
        }
    }

    private fun interchange(
        fileName: String,
        productionOwner: String = "org.flowlang.artifacts.ArtifactContractAuthority registered producer/output contract"
    ): PublishedSchemaContract = PublishedSchemaContract(
        path = "schemas/$fileName",
        authority = PublishedSchemaAcceptanceKind.SYNTACTIC_INTERCHANGE,
        productionOwner = productionOwner
    )
}
