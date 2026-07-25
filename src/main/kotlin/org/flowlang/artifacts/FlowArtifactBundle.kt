package org.flowlang.artifacts

import org.flowlang.standard.FlowStandardVersions

enum class FlowArtifactRole { METADATA, MODEL, REPORT, PLAN, MANIFEST, RENDERED }

data class FlowArtifactEntry(
    val name: String,
    val role: FlowArtifactRole,
    val schema: String = "",
    val required: Boolean,
    val derived: Boolean,
    val pipelineIndex: Int,
    val derivedFrom: List<String> = emptyList()
)

data class FlowArtifactBundleReport(
    val standardVersion: String = FlowStandardVersions.FLOW_STANDARD_VERSION,
    val bundleModelVersion: String = "1.0",
    val flowName: String,
    val target: String,
    val strict: Boolean,
    val artifacts: List<FlowArtifactEntry>,
    val requiredArtifacts: List<String>,
    val optionalArtifacts: List<String>,
    val pipeline: List<String>
)

/** Describes the public artifact bundle exported by the CLI. */
class FlowArtifactBundleAnalyzer {
    fun intentBundle(
        flowName: String,
        target: String,
        strict: Boolean,
        hasManifest: Boolean,
        renderedArtifact: String?
    ): FlowArtifactBundleReport = bundle(
        flowName = flowName,
        target = target,
        strict = strict,
        includeAiNormalization = false,
        includeDesignAndLowering = true,
        hasManifest = hasManifest,
        renderedArtifact = renderedArtifact
    )

    fun normalizationBundle(
        flowName: String,
        target: String,
        strict: Boolean,
        lowered: Boolean,
        hasManifest: Boolean,
        renderedArtifact: String?
    ): FlowArtifactBundleReport = bundle(
        flowName = flowName,
        target = target,
        strict = strict,
        includeAiNormalization = true,
        includeDesignAndLowering = lowered,
        hasManifest = hasManifest,
        renderedArtifact = renderedArtifact
    )

    private fun bundle(
        flowName: String,
        target: String,
        strict: Boolean,
        includeAiNormalization: Boolean,
        includeDesignAndLowering: Boolean,
        hasManifest: Boolean,
        renderedArtifact: String?
    ): FlowArtifactBundleReport {
        val entries = mutableListOf<FlowArtifactEntry>()

        fun add(
            name: String,
            role: FlowArtifactRole,
            schema: String = "",
            required: Boolean = true,
            derived: Boolean = true,
            derivedFrom: List<String> = emptyList()
        ) {
            entries += FlowArtifactEntry(
                name = name,
                role = role,
                schema = schema,
                required = required,
                derived = derived,
                pipelineIndex = entries.size + 1,
                derivedFrom = derivedFrom
            )
        }

        add("standard-version.txt", FlowArtifactRole.METADATA, required = true, derived = false)
        add("standard-diagnostic-catalog.json", FlowArtifactRole.METADATA, "schemas/standard-diagnostic-catalog.schema.json", required = true, derived = false)
        if (includeAiNormalization) {
            add("ai-normalization-report.json", FlowArtifactRole.REPORT, "schemas/ai-normalization-report.schema.json", derivedFrom = listOf("human-ai-intent"))
        }
        add("normalized-intent.json", FlowArtifactRole.MODEL, "schemas/intent.schema.json", derivedFrom = listOf("human-ai-intent"))
        if (includeDesignAndLowering) {
            add("intent-design-report.json", FlowArtifactRole.REPORT, "schemas/intent-design-report.schema.json", derivedFrom = listOf("normalized-intent.json"))
        }
        add("intent-decision-report.json", FlowArtifactRole.REPORT, "schemas/intent-decision-report.schema.json", derivedFrom = listOf("normalized-intent.json"))

        if (includeDesignAndLowering) {
            add("intent-capability-validation-report.json", FlowArtifactRole.REPORT, "schemas/intent-capability-validation-report.schema.json", derivedFrom = listOf("normalized-intent.json"))
            add("flow-ast.json", FlowArtifactRole.MODEL, "schemas/ast.schema.json", derivedFrom = listOf("normalized-intent.json"))
            add("validation-report.json", FlowArtifactRole.REPORT, "schemas/validation-report.schema.json", derivedFrom = listOf("flow-ast.json"))
            add("execution-plan.json", FlowArtifactRole.PLAN, "schemas/execution-plan.schema.json", derivedFrom = listOf("flow-ast.json"))
            add("canonical-execution-plan.json", FlowArtifactRole.PLAN, "schemas/execution-plan.schema.json", derivedFrom = listOf("execution-plan.json"))
            add("target-neutral-planning-report.json", FlowArtifactRole.REPORT, "schemas/target-neutral-planning-report.schema.json", required = false, derivedFrom = listOf("execution-plan.json"))
            add("compatibility-report.json", FlowArtifactRole.REPORT, "schemas/compatibility-report.schema.json", derivedFrom = listOf("execution-plan.json"))
            add("capability-negotiation-report.json", FlowArtifactRole.REPORT, "schemas/capability-negotiation-report.schema.json", derivedFrom = listOf("execution-plan.json"))
            add("execution-readiness-report.json", FlowArtifactRole.REPORT, "schemas/execution-readiness-report.schema.json", derivedFrom = listOf("capability-negotiation-report.json"))
            add("target-selection-report.json", FlowArtifactRole.REPORT, "schemas/target-selection-report.schema.json", derivedFrom = listOf("execution-readiness-report.json"))
            add("target-decision-trace-report.json", FlowArtifactRole.REPORT, "schemas/target-decision-trace-report.schema.json", derivedFrom = listOf("target-selection-report.json"))
            add("cli-target-outcome.json", FlowArtifactRole.REPORT, "schemas/cli-target-outcome.schema.json", required = false, derivedFrom = listOf("target-decision-trace-report.json", "target-manifest.json"))
            add("target-render-readiness.json", FlowArtifactRole.REPORT, "schemas/target-render-readiness.schema.json", required = false, derivedFrom = listOf("target-manifest.json"))
            add("target-adapter-contract.json", FlowArtifactRole.REPORT, "schemas/target-adapter-contract.schema.json", derivedFrom = listOf("execution-plan.json", "execution-readiness-report.json", "target-selection-report.json", "target-decision-trace-report.json"))
            add("adapter-diagnostics.json", FlowArtifactRole.REPORT, "schemas/adapter-diagnostics.schema.json", derivedFrom = listOf("target-adapter-contract.json"))
            add("diagnostic-coverage-report.json", FlowArtifactRole.REPORT, "schemas/diagnostic-coverage-report.schema.json", derivedFrom = listOf("intent-capability-validation-report.json", "validation-report.json", "execution-readiness-report.json", "target-adapter-contract.json", "adapter-diagnostics.json", "standard-diagnostic-catalog.json"))
            add("artifact-integrity-report.json", FlowArtifactRole.REPORT, "schemas/artifact-integrity-report.schema.json", derivedFrom = listOf("standard-version.txt", "diagnostic-coverage-report.json", "standard-diagnostic-catalog.json"))
            add("standard-contract-index.json", FlowArtifactRole.METADATA, "schemas/standard-contract-index.schema.json", derivedFrom = listOf("flow-artifact-bundle.json", "conformance-manifest.json"))
            add("standard-release-profile.json", FlowArtifactRole.METADATA, "schemas/standard-release-profile.schema.json", derivedFrom = listOf("standard-contract-index.json"))
            add("artifact-evidence-report.json", FlowArtifactRole.REPORT, "schemas/artifact-evidence-report.schema.json", derivedFrom = listOf("flow-artifact-bundle.json"))
            add("standard-compliance-report.json", FlowArtifactRole.REPORT, "schemas/standard-compliance-report.schema.json", derivedFrom = listOf("standard-contract-index.json", "standard-release-profile.json", "artifact-evidence-report.json", "artifact-integrity-report.json", "conformance-manifest.json"))
            add("standard-freeze-report.json", FlowArtifactRole.REPORT, "schemas/standard-freeze-report.schema.json", derivedFrom = listOf("standard-contract-index.json"))
            add("compatibility-policy.json", FlowArtifactRole.METADATA, "schemas/compatibility-policy.schema.json", required = true, derived = false)
            add("reference-corpus-index.json", FlowArtifactRole.METADATA, "schemas/reference-corpus-index.schema.json", required = true, derived = false)
            add("negative-conformance-corpus.json", FlowArtifactRole.METADATA, "schemas/negative-conformance-corpus.schema.json", required = true, derived = false)
            add("target-conformance-profile.json", FlowArtifactRole.METADATA, "schemas/target-conformance-profile.schema.json", required = true, derived = false)
            add("public-standard-surface.json", FlowArtifactRole.METADATA, "schemas/public-standard-surface.schema.json", required = true, derived = false)
            add("compatibility-migration-policy.json", FlowArtifactRole.METADATA, "schemas/compatibility-migration-policy.schema.json", required = true, derived = false)
            add("reference-intent-corpus.json", FlowArtifactRole.METADATA, "schemas/reference-intent-corpus.schema.json", required = true, derived = false)
            add("target-semantics-matrix.json", FlowArtifactRole.METADATA, "schemas/target-semantics-matrix.schema.json", required = true, derived = false)
            add("standard-export-bundle.json", FlowArtifactRole.METADATA, "schemas/standard-export-bundle.schema.json", required = true, derived = false)
            add("conformance-levels.json", FlowArtifactRole.METADATA, "schemas/conformance-levels.schema.json", required = true, derived = false)
            add("standard-export-manifest.json", FlowArtifactRole.METADATA, "schemas/standard-export-manifest.schema.json", required = true, derived = false)
            add("conformance-vector-index.json", FlowArtifactRole.METADATA, "schemas/conformance-vector-index.schema.json", required = true, derivedFrom = listOf("conformance/"))
            add("standard-index.json", FlowArtifactRole.METADATA, "schemas/standard-index.schema.json", derivedFrom = listOf("standard-contract-index.json", "flow-artifact-bundle.json"))
            add("conformance-suite.json", FlowArtifactRole.METADATA, "schemas/conformance-suite.schema.json", derivedFrom = listOf("conformance-manifest.json", "reference-corpus-index.json", "negative-conformance-corpus.json"))
            add("flow-standard-draft.json", FlowArtifactRole.REPORT, "schemas/flow-standard-draft.schema.json", derivedFrom = listOf("standard-index.json", "conformance-suite.json", "standard-compliance-report.json"))
            if (hasManifest) {
                add("target-manifest.json", FlowArtifactRole.MANIFEST, "schemas/target-manifest.schema.json", required = false, derivedFrom = listOf("execution-plan.json", "target-adapter-contract.json"))
            }
            renderedArtifact?.let {
                add(it, FlowArtifactRole.RENDERED, required = false, derivedFrom = listOf("target-manifest.json"))
            }
        }
        add("flow-artifact-bundle.json", FlowArtifactRole.METADATA, "schemas/flow-artifact-bundle.schema.json", required = true, derived = true, derivedFrom = entries.map { it.name })

        return FlowArtifactBundleReport(
            flowName = flowName,
            target = target,
            strict = strict,
            artifacts = entries,
            requiredArtifacts = entries.filter { it.required }.map { it.name },
            optionalArtifacts = entries.filter { !it.required }.map { it.name },
            pipeline = entries.map { it.name }
        )
    }
}