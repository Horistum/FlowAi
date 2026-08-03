package org.flowlang.conformance

import java.io.File
import org.flowlang.artifacts.ConformanceAreaSummary
import org.flowlang.artifacts.ConformanceManifestReport
import org.flowlang.artifacts.ConformanceSchemaEntry
import org.flowlang.artifacts.ConformanceVectorEntry

class ConformanceManifestBuilder(private val rootDir: File = File(".")) {
    fun build(summary: ConformanceSummary, implementation: String = "flow-kotlin-cli"): ConformanceManifestReport {
        val areas = summary.checks.groupBy { areaOf(it.name) }
            .toSortedMap()
            .map { (area, checks) ->
                ConformanceAreaSummary(
                    area = area,
                    requiredChecks = checks.size,
                    passed = checks.count { it.passed },
                    failed = checks.count { !it.passed }
                )
            }
        return ConformanceManifestReport(
            implementation = implementation,
            status = if (summary.ok) "PASS" else "FAIL",
            totalChecks = summary.checks.size,
            passed = summary.passed,
            failed = summary.failed,
            areas = areas,
            requiredChecks = summary.checks.map { it.name },
            failedChecks = summary.checks.filter { !it.passed }.map { it.name },
            vectors = vectorEntries(),
            publicSchemas = publicSchemas(),
            requiredArtifacts = requiredArtifacts()
        )
    }

    private fun areaOf(check: String): String = check.substringBefore('.')

    private fun vectorEntries(): List<ConformanceVectorEntry> {
        val dir = File(rootDir, "conformance")
        if (!dir.isDirectory) return emptyList()
        return dir.walkTopDown()
            .filter { it.isFile && (it.extension == "yaml" || it.extension == "yml") }
            .map { file ->
                val rel = file.relativeTo(rootDir).path.replace(File.separatorChar, '/')
                ConformanceVectorEntry(
                    path = rel,
                    area = rel.removePrefix("conformance/").substringBefore('/'),
                    required = true
                )
            }
            .sortedBy { it.path }
            .toList()
    }

    private fun publicSchemas(): List<ConformanceSchemaEntry> = listOf(
        ConformanceSchemaEntry("normalized-intent.json", "schemas/intent.schema.json", true),
        ConformanceSchemaEntry("intent-design-report.json", "schemas/intent-design-report.schema.json", true),
        ConformanceSchemaEntry("intent-decision-report.json", "schemas/intent-decision-report.schema.json", true),
        ConformanceSchemaEntry("intent-capability-validation-report.json", "schemas/intent-capability-validation-report.schema.json", true),
        ConformanceSchemaEntry("flow-ast.json", "schemas/ast.schema.json", true),
        ConformanceSchemaEntry("validation-report.json", "schemas/validation-report.schema.json", true),
        ConformanceSchemaEntry("execution-plan.json", "schemas/execution-plan.schema.json", true),
        ConformanceSchemaEntry("canonical-execution-plan.json", "schemas/execution-plan.schema.json", true),
        ConformanceSchemaEntry("target-neutral-planning-report.json", "schemas/target-neutral-planning-report.schema.json", true),
        ConformanceSchemaEntry("target-selection-evidence.json", "schemas/target-selection-evidence.schema.json", true),
        ConformanceSchemaEntry("cli-target-outcome.json", "schemas/cli-target-outcome.schema.json", true),
        ConformanceSchemaEntry("target-render-readiness.json", "schemas/target-render-readiness.schema.json", true),
        ConformanceSchemaEntry("target-artifact-evidence.json", "schemas/target-artifact-evidence.schema.json", true),
        ConformanceSchemaEntry("compatibility-report.json", "schemas/compatibility-report.schema.json", true),
        ConformanceSchemaEntry("capability-negotiation-report.json", "schemas/capability-negotiation-report.schema.json", true),
        ConformanceSchemaEntry("execution-readiness-report.json", "schemas/execution-readiness-report.schema.json", true),
        ConformanceSchemaEntry("target-selection-report.json", "schemas/target-selection-report.schema.json", true),
        ConformanceSchemaEntry("target-decision-trace-report.json", "schemas/target-decision-trace-report.schema.json", true),
        ConformanceSchemaEntry("target-adapter-contract.json", "schemas/target-adapter-contract.schema.json", true),
        ConformanceSchemaEntry("adapter-diagnostics.json", "schemas/adapter-diagnostics.schema.json", true),
        ConformanceSchemaEntry("diagnostic-coverage-report.json", "schemas/diagnostic-coverage-report.schema.json", true),
        ConformanceSchemaEntry("artifact-integrity-report.json", "schemas/artifact-integrity-report.schema.json", true),
        ConformanceSchemaEntry("standard-contract-index.json", "schemas/standard-contract-index.schema.json", true),
        ConformanceSchemaEntry("standard-release-profile.json", "schemas/standard-release-profile.schema.json", true),
        ConformanceSchemaEntry("artifact-evidence-report.json", "schemas/artifact-evidence-report.schema.json", true),
        ConformanceSchemaEntry("standard-compliance-report.json", "schemas/standard-compliance-report.schema.json", true),
        ConformanceSchemaEntry("standard-freeze-report.json", "schemas/standard-freeze-report.schema.json", true),
        ConformanceSchemaEntry("compatibility-policy.json", "schemas/compatibility-policy.schema.json", true),
        ConformanceSchemaEntry("reference-corpus-index.json", "schemas/reference-corpus-index.schema.json", true),
        ConformanceSchemaEntry("negative-conformance-corpus.json", "schemas/negative-conformance-corpus.schema.json", true),
        ConformanceSchemaEntry("target-conformance-profile.json", "schemas/target-conformance-profile.schema.json", true),
        ConformanceSchemaEntry("public-standard-surface.json", "schemas/public-standard-surface.schema.json", true),
        ConformanceSchemaEntry("compatibility-migration-policy.json", "schemas/compatibility-migration-policy.schema.json", true),
        ConformanceSchemaEntry("reference-intent-corpus.json", "schemas/reference-intent-corpus.schema.json", true),
        ConformanceSchemaEntry("target-semantics-matrix.json", "schemas/target-semantics-matrix.schema.json", true),
        ConformanceSchemaEntry("standard-export-bundle.json", "schemas/standard-export-bundle.schema.json", true),
        ConformanceSchemaEntry("conformance-levels.json", "schemas/conformance-levels.schema.json", true),
        ConformanceSchemaEntry("standard-export-manifest.json", "schemas/standard-export-manifest.schema.json", true),
        ConformanceSchemaEntry("conformance-vector-index.json", "schemas/conformance-vector-index.schema.json", true),
        ConformanceSchemaEntry("standard-index.json", "schemas/standard-index.schema.json", true),
        ConformanceSchemaEntry("conformance-suite.json", "schemas/conformance-suite.schema.json", true),
        ConformanceSchemaEntry("flow-standard-draft.json", "schemas/flow-standard-draft.schema.json", true),
        ConformanceSchemaEntry("standard-diagnostic-catalog.json", "schemas/standard-diagnostic-catalog.schema.json", true),
        ConformanceSchemaEntry("flow-artifact-bundle.json", "schemas/flow-artifact-bundle.schema.json", true),
        ConformanceSchemaEntry("standard-bundle-verification.json", "schemas/standard-bundle-verification.schema.json", false),
        ConformanceSchemaEntry("target-manifest.json", "schemas/target-manifest.schema.json", false),
        ConformanceSchemaEntry("capability-module-contract-report.json", "schemas/capability-module-contract-report.schema.json", true),
        ConformanceSchemaEntry("conformance-manifest.json", "schemas/conformance-manifest.schema.json", true)
    )

    private fun requiredArtifacts(): List<String> = listOf(
        "standard-version.txt",
        "normalized-intent.json",
        "intent-decision-report.json",
        "execution-plan.json",
        "canonical-execution-plan.json",
        "capability-negotiation-report.json",
        "execution-readiness-report.json",
        "target-selection-report.json",
        "target-decision-trace-report.json",
        "target-adapter-contract.json",
        "adapter-diagnostics.json",
        "diagnostic-coverage-report.json",
        "artifact-integrity-report.json",
        "standard-contract-index.json",
        "standard-release-profile.json",
        "artifact-evidence-report.json",
        "standard-compliance-report.json",
        "standard-freeze-report.json",
        "compatibility-policy.json",
        "reference-corpus-index.json",
        "negative-conformance-corpus.json",
        "target-conformance-profile.json",
        "public-standard-surface.json",
        "compatibility-migration-policy.json",
        "reference-intent-corpus.json",
        "target-semantics-matrix.json",
        "standard-export-bundle.json",
        "conformance-levels.json",
        "standard-export-manifest.json",
        "conformance-vector-index.json",
        "standard-index.json",
        "conformance-suite.json",
        "flow-standard-draft.json",
        "standard-diagnostic-catalog.json",
        "flow-artifact-bundle.json",
        "conformance-manifest.json"
    )
}
