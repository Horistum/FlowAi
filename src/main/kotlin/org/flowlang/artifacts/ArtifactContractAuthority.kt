package org.flowlang.artifacts

import org.flowlang.standard.StandardModel

/** Stable contract metadata for one emitted artifact. */
data class ArtifactContractDefinition(
    val artifact: String,
    val producer: String,
    val introducedIn: String
)

/**
 * Single fail-closed authority for artifact identity, producer provenance,
 * introduction metadata and derivation references.
 *
 * An artifact not registered here is not silently assigned a generic producer
 * or a fictional historical version. Distribution-specific rendered output is
 * the only open name set and is authorized by the RENDERED role, not by a
 * string fallback.
 */
object ArtifactContractAuthority {
    private val registeredExternalSources = setOf(
        "human-ai-intent",
        "conformance/"
    )

    private val producerByArtifact: Map<String, String> = mapOf(
        "standard-version.txt" to "flow.standard.version",
        "standard-diagnostic-catalog.json" to "flow.standard.diagnostic-catalog",
        "ai-normalization-report.json" to "flow.ai.normalization",
        "normalized-intent.json" to "flow.intent.normalization",
        "intent-design-report.json" to "flow.intent.design-analysis",
        "intent-decision-report.json" to "flow.intent.decision",
        "intent-capability-validation-report.json" to "flow.intent.capability-validation",
        "flow-ast.json" to "flow.intent.lowering",
        "validation-report.json" to "flow.ast.validation",
        "execution-plan.json" to "flow.execution-plan.planner",
        "canonical-execution-plan.json" to "flow.execution-plan.canonicalizer",
        "target-neutral-planning-report.json" to "flow.planning.target-neutral-evidence",
        "target-selection-evidence.json" to "flow.target-selection.authority",
        "compatibility-report.json" to "flow.target.compatibility",
        "capability-negotiation-report.json" to "flow.target.negotiation",
        "execution-readiness-report.json" to "flow.target.readiness",
        "target-selection-report.json" to "flow.target.selection-analysis",
        "target-decision-trace-report.json" to "flow.target.decision-trace",
        "cli-target-outcome.json" to "flow.cli.target-evidence",
        "target-render-readiness.json" to "flow.target.render-policy",
        "target-artifact-evidence.json" to "flow.adapter.artifact-rendering",
        "target-adapter-contract.json" to "flow.adapter.contract",
        "adapter-diagnostics.json" to "flow.adapter.diagnostics",
        "diagnostic-coverage-report.json" to "flow.diagnostics.coverage",
        "artifact-integrity-report.json" to "flow.artifact.integrity",
        "conformance-manifest.json" to "flow.conformance.manifest",
        "standard-contract-index.json" to "flow.standard.contract-index",
        "standard-release-profile.json" to "flow.standard.release-profile",
        "artifact-evidence-report.json" to "flow.artifact.evidence",
        "standard-compliance-report.json" to "flow.standard.compliance",
        "standard-freeze-report.json" to "flow.standard.freeze",
        "compatibility-policy.json" to "flow.standard.compatibility-policy",
        "reference-corpus-index.json" to "flow.standard.reference-corpus",
        "negative-conformance-corpus.json" to "flow.standard.negative-corpus",
        "target-conformance-profile.json" to "flow.standard.target-conformance-profile",
        "public-standard-surface.json" to "flow.standard.public-surface",
        "compatibility-migration-policy.json" to "flow.standard.compatibility-migration",
        "reference-intent-corpus.json" to "flow.standard.reference-intent-corpus",
        "target-semantics-matrix.json" to "flow.standard.target-semantics",
        "standard-export-bundle.json" to "flow.standard.export-bundle",
        "conformance-levels.json" to "flow.standard.conformance-levels",
        "standard-export-manifest.json" to "flow.standard.export-manifest",
        "conformance-vector-index.json" to "flow.conformance.vector-index",
        "standard-index.json" to "flow.standard.index",
        "conformance-suite.json" to "flow.conformance.suite",
        "flow-standard-draft.json" to "flow.standard.draft",
        "target-manifest.json" to "flow.target.manifest",
        "flow-artifact-bundle.json" to "flow.artifact.bundle"
    )

    private val introducedInByArtifact: Map<String, String> = buildMap {
        StandardModel.artifacts.forEach { artifact -> put(artifact.artifact, artifact.introducedIn) }
        putAll(mapOf(
            "standard-version.txt" to "pre-0.3.10",
            "standard-diagnostic-catalog.json" to "0.3.13",
            "ai-normalization-report.json" to "0.7.6",
            "normalized-intent.json" to "pre-0.3.10",
            "intent-design-report.json" to "0.7.6",
            "intent-decision-report.json" to "pre-0.3.10",
            "intent-capability-validation-report.json" to "pre-0.3.10",
            "flow-ast.json" to "pre-0.3.10",
            "validation-report.json" to "pre-0.3.10",
            "execution-plan.json" to "pre-0.3.10",
            "canonical-execution-plan.json" to "0.3.9",
            "target-neutral-planning-report.json" to "0.9.7.9.8",
            "target-selection-evidence.json" to "0.9.7.9.9",
            "compatibility-report.json" to "pre-0.3.10",
            "capability-negotiation-report.json" to "0.3.8",
            "execution-readiness-report.json" to "0.3.7",
            "target-selection-report.json" to "0.3.8",
            "target-decision-trace-report.json" to "0.3.9",
            "cli-target-outcome.json" to "0.9.7.9.8",
            "target-render-readiness.json" to "0.9.7.9.8",
            "target-artifact-evidence.json" to "A0.6",
            "target-adapter-contract.json" to "0.3.12",
            "adapter-diagnostics.json" to "0.3.12",
            "diagnostic-coverage-report.json" to "0.3.14",
            "artifact-integrity-report.json" to "0.3.15",
            "standard-contract-index.json" to "0.3.16",
            "standard-release-profile.json" to "0.3.17",
            "artifact-evidence-report.json" to "0.3.18",
            "standard-compliance-report.json" to "0.3.19",
            "standard-freeze-report.json" to "0.3.20",
            "compatibility-policy.json" to "0.3.21",
            "reference-corpus-index.json" to "0.3.22",
            "negative-conformance-corpus.json" to "0.3.23",
            "target-conformance-profile.json" to "0.4.2",
            "standard-index.json" to "0.4.0",
            "conformance-suite.json" to "0.4.0",
            "flow-standard-draft.json" to "0.4.0",
            "target-manifest.json" to "pre-0.3.10",
            "flow-artifact-bundle.json" to "0.3.10"
        ))
    }

    fun definitionFor(artifact: String, role: FlowArtifactRole): ArtifactContractDefinition {
        if (role == FlowArtifactRole.RENDERED) {
            require(artifact.isNotBlank()) { "Rendered artifact name must not be blank." }
            return ArtifactContractDefinition(
                artifact = artifact,
                producer = "flow.target.renderer",
                introducedIn = "distribution-specific"
            )
        }
        val producer = producerByArtifact[artifact]
            ?: error("Artifact '$artifact' has no registered producer contract.")
        val introducedIn = introducedInByArtifact[artifact]
            ?: error("Artifact '$artifact' has no registered introduced-version contract.")
        return ArtifactContractDefinition(artifact, producer, introducedIn)
    }

    fun missingEvidence(bundle: FlowArtifactBundleReport): List<String> {
        val names = bundle.artifacts.map { it.name }
        val nameSet = names.toSet()
        val invalid = linkedSetOf<String>()

        names.groupingBy { it }.eachCount()
            .filterValues { it > 1 }
            .keys
            .forEach(invalid::add)

        bundle.artifacts.forEach { artifact ->
            definitionFor(artifact.name, artifact.role)
            if (artifact.required && artifact.derived) {
                if (artifact.derivedFrom.isEmpty()) {
                    invalid += artifact.name
                }
                if (artifact.derivedFrom.any { source ->
                        source !in nameSet && source !in registeredExternalSources
                    }) {
                    invalid += artifact.name
                }
            }
        }
        return invalid.sorted()
    }
}
