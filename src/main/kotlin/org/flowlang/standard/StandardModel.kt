package org.flowlang.standard

import java.io.File

/** Stable semantic category for a modeled conformance check. */
enum class GateKind(val category: String, val isSubstance: Boolean) {
    BEHAVIOR("behavior", true),
    SAFETY("safety", true),
    NORMALIZATION("normalization", true),
    EXECUTION_PLAN("execution-plan", true),
    PORTABILITY("portability", true),
    CONTRACT("contract", true),
    DIAGNOSTICS("diagnostics", true),
    COMPATIBILITY("compatibility", true),
    ARTIFACT_INTEGRITY("artifact-integrity", true),
    GOVERNANCE("governance", true),
    REGISTRY_CONSISTENCY("registry-consistency", false)
}

/**
 * PUBLIC_STANDARD checks define public release and candidate projections.
 * ROADMAP_GOVERNANCE checks model later package-level semantic and integrity
 * evidence without silently promoting it into the public 0.8.0 release profile.
 */
enum class StandardCheckScope {
    PUBLIC_STANDARD,
    ROADMAP_GOVERNANCE
}

enum class ArtifactVisibility {
    PUBLIC,
    INTERNAL
}

/** One explicitly modeled conformance check. */
data class StandardCheck(
    val id: String,
    val introducedIn: String,
    val kind: GateKind,
    val scope: StandardCheckScope = StandardCheckScope.PUBLIC_STANDARD,
    val inReleaseProfile: Boolean = true,
    val inCandidateLevel: Boolean = false,
    val inExportManifest: Boolean = false,
    val inPreClosureSuite: Boolean = true,
    val negativeFixture: String = "",
    val externalAnchor: String = ""
)

/** One artifact in the modeled standard surface. */
data class StandardArtifact(
    val artifact: String,
    val schema: String,
    val stability: String,
    val area: String,
    val introducedIn: String,
    val changeGate: String,
    val role: String,
    val notes: String = "Public standard surface entry derived from StandardModel.",
    val visibility: ArtifactVisibility = ArtifactVisibility.PUBLIC,
    val inExportBundle: Boolean = true,
    val inCandidateLevel: Boolean = false,
    val isEvidence: Boolean = false
)

/**
 * Single source of truth for public standard artifacts and intentionally modeled
 * conformance obligations.
 *
 * It is not the inventory of every implementation probe. The complete runner
 * sequence is independently frozen by ConformanceSuiteInventory. This model
 * declares the public standard gates and package-level semantic/governance
 * contracts that deserve durable identity. The closure authority verifies that
 * every modeled pre-closure check is present in the complete suite.
 */
object StandardModel {
    val checks: List<StandardCheck> = listOf(
        StandardCheck("v0.3.16.standard-contract-index", "0.3.16", GateKind.CONTRACT),
        StandardCheck("v0.3.17.standard-release-profile", "0.3.17", GateKind.CONTRACT),
        StandardCheck("v0.3.18.artifact-evidence-report", "0.3.18", GateKind.ARTIFACT_INTEGRITY),
        StandardCheck("v0.3.19.standard-compliance-report", "0.3.19", GateKind.CONTRACT),
        StandardCheck("v0.3.20.standard-freeze-report", "0.3.20", GateKind.ARTIFACT_INTEGRITY),
        StandardCheck("v0.3.21.compatibility-policy", "0.3.21", GateKind.COMPATIBILITY),
        StandardCheck(
            "v0.3.22.reference-corpus", "0.3.22", GateKind.BEHAVIOR,
            externalAnchor = "conformance/artifacts/reference-corpus-index.conformance.yaml"
        ),
        StandardCheck(
            "v0.3.23.negative-conformance-corpus", "0.3.23", GateKind.BEHAVIOR,
            negativeFixture = "conformance/artifacts/negative-conformance-corpus.conformance.yaml"
        ),
        StandardCheck(
            "v0.4.2.target-conformance-profile", "0.4.2", GateKind.PORTABILITY,
            inCandidateLevel = true, inExportManifest = true
        ),
        StandardCheck("v0.4.0.public-standard-draft", "0.4.0", GateKind.CONTRACT),
        StandardCheck(
            "v0.4.1.semantic-correctness-hardening", "0.4.1", GateKind.BEHAVIOR,
            externalAnchor = "conformance/intent/semantic-correctness-hardening.conformance.yaml"
        ),
        StandardCheck(
            "v0.4.2.standard-boundary-no-sdk-runtime", "0.4.2", GateKind.GOVERNANCE,
            negativeFixture = "conformance/architecture/standard-boundary-no-sdk-runtime.conformance.yaml"
        ),
        StandardCheck(
            "v0.4.3.architecture-governance-guardrails", "0.4.3", GateKind.GOVERNANCE,
            inExportManifest = true,
            negativeFixture = "conformance/architecture/architecture-governance-guardrails.conformance.yaml"
        ),
        StandardCheck("v0.4.4.ai-proposal-review", "0.4.4", GateKind.NORMALIZATION, inExportManifest = true),
        StandardCheck("v0.4.4.condition-expression-readiness", "0.4.4", GateKind.EXECUTION_PLAN, inExportManifest = true),
        StandardCheck(
            "v0.4.4.no-silent-condition-fallback", "0.4.4", GateKind.PORTABILITY,
            inExportManifest = true,
            negativeFixture = "conformance/targets/no-silent-condition-fallback.conformance.yaml"
        ),
        StandardCheck(
            "v0.4.4.behavioral-generator-equivalence", "0.4.4", GateKind.BEHAVIOR,
            inExportManifest = true,
            externalAnchor = "conformance/generators/behavioral-generator-equivalence.conformance.yaml"
        ),
        StandardCheck(
            "v0.4.5.standard-surface-freeze", "0.4.5", GateKind.CONTRACT,
            inCandidateLevel = true, inExportManifest = true
        ),
        StandardCheck(
            "v0.4.6.compatibility-migration-policy", "0.4.6", GateKind.COMPATIBILITY,
            inCandidateLevel = true, inExportManifest = true
        ),
        StandardCheck(
            "v0.4.7.reference-intent-corpus", "0.4.7", GateKind.BEHAVIOR,
            inCandidateLevel = true, inExportManifest = true,
            externalAnchor = "conformance/intent/reference-intent-corpus.conformance.yaml"
        ),
        StandardCheck(
            "v0.4.8.target-semantics-matrix", "0.4.8", GateKind.PORTABILITY,
            inCandidateLevel = true, inExportManifest = true
        ),
        StandardCheck(
            "v0.4.9.standard-export-bundle", "0.4.9", GateKind.ARTIFACT_INTEGRITY,
            inCandidateLevel = true, inExportManifest = true
        ),
        StandardCheck(
            "v0.5.0.standard-export-manifest", "0.5.0", GateKind.CONTRACT,
            inCandidateLevel = true, inExportManifest = true
        ),
        StandardCheck(
            "v0.5.3.standard-bundle-verifier", "0.5.3", GateKind.ARTIFACT_INTEGRITY,
            inCandidateLevel = true, inExportManifest = true
        ),
        StandardCheck(
            "v0.5.4.data-driven-conformance-index", "0.5.4", GateKind.ARTIFACT_INTEGRITY,
            inCandidateLevel = true, inExportManifest = true
        ),
        StandardCheck(
            "v0.6.1.intent-corpus-expansion", "0.6.1", GateKind.BEHAVIOR,
            inCandidateLevel = true, inExportManifest = true,
            externalAnchor = "conformance/standard/intent-corpus-expansion.conformance.yaml"
        ),
        StandardCheck(
            "v0.6.2.required-clarification-contract", "0.6.2", GateKind.NORMALIZATION,
            inCandidateLevel = true, inExportManifest = true
        ),
        StandardCheck(
            "v0.6.3.safety-policy-matrix", "0.6.3", GateKind.SAFETY,
            inCandidateLevel = true, inExportManifest = true,
            negativeFixture = "conformance/standard/safety-policy-matrix.conformance.yaml"
        ),
        StandardCheck(
            "v0.6.4.target-semantics-negative-corpus", "0.6.4", GateKind.PORTABILITY,
            inCandidateLevel = true, inExportManifest = true,
            negativeFixture = "conformance/standard/target-semantics-negative-corpus.conformance.yaml"
        ),
        StandardCheck(
            "v0.6.5.execution-plan-semantic-invariants", "0.6.5", GateKind.EXECUTION_PLAN,
            inCandidateLevel = true, inExportManifest = true
        ),
        StandardCheck(
            "v0.6.6.ai-input-trust-boundary", "0.6.6", GateKind.SAFETY,
            inCandidateLevel = true, inExportManifest = true,
            negativeFixture = "conformance/standard/ai-input-trust-boundary.conformance.yaml"
        ),
        StandardCheck(
            "v0.6.7.standard-example-bundle", "0.6.7", GateKind.BEHAVIOR,
            inCandidateLevel = true, inExportManifest = true,
            externalAnchor = "conformance/standard/standard-example-bundle.conformance.yaml"
        ),
        StandardCheck(
            "v0.6.8.compatibility-promise", "0.6.8", GateKind.COMPATIBILITY,
            inCandidateLevel = true, inExportManifest = true
        ),
        StandardCheck(
            "v0.7.0.reference-corpus-execution-harness", "0.7.0", GateKind.BEHAVIOR,
            inCandidateLevel = true, inExportManifest = true,
            externalAnchor = "src/main/kotlin/org/flowlang/conformance/ReferenceCorpusExecutionHarness.kt"
        ),
        StandardCheck(
            "v0.7.1.architecture-debt-cleanup-and-drift-enforcement", "0.7.1", GateKind.GOVERNANCE,
            inCandidateLevel = true, inExportManifest = true,
            negativeFixture = "tests/FlowArchitectureDebtCleanupTests.kt"
        ),
        StandardCheck(
            "v0.7.3.standard-model-projection-coherence", "0.7.3", GateKind.GOVERNANCE,
            inCandidateLevel = true, inExportManifest = true,
            negativeFixture = "tests/FlowArchitectureDebtCleanupTests.kt",
            externalAnchor = "src/main/kotlin/org/flowlang/standard/StandardModel.kt"
        ),
        StandardCheck(
            "v0.7.4.architecture-delta-analyzer", "0.7.4", GateKind.GOVERNANCE,
            inCandidateLevel = true, inExportManifest = true,
            negativeFixture = "tests/FlowArchitectureDeltaAnalyzerTests.kt",
            externalAnchor = "standard/architecture/standard-model-baseline-v0.7.3.yaml"
        ),
        StandardCheck(
            "v0.7.5.purpose-coverage-ratio", "0.7.5", GateKind.BEHAVIOR,
            inCandidateLevel = true, inExportManifest = true,
            negativeFixture = "tests/FlowPurposeCoverageRatioTests.kt",
            externalAnchor = "docs/V0_7_5_PURPOSE_COVERAGE_RATIO.md"
        ),

        roadmapCheck("v0.7.6.semantic-correctness-hardening", "0.7.6", GateKind.BEHAVIOR, "FIX_SUMMARY.md"),
        roadmapCheck(
            "v0.7.7.scenario-pack-quality-gates", "0.7.7", GateKind.BEHAVIOR,
            "src/main/kotlin/org/flowlang/standard/ScenarioPackQualityAnalyzer.kt"
        ),
        roadmapCheck("v0.8.0.core-contract-check", "0.8.0", GateKind.CONTRACT, "docs/V0_8_0_CORE_CONTRACT_CHECK.md"),
        roadmapCheck(
            "v0.8.1.target-capability-matrix", "0.8.1", GateKind.COMPATIBILITY,
            "src/main/kotlin/org/flowlang/capabilities/TargetCapabilityMatrix.kt"
        ),
        roadmapCheck(
            "v0.8.2.target-negotiation-report", "0.8.2", GateKind.COMPATIBILITY,
            "src/main/kotlin/org/flowlang/capabilities/TargetNegotiationReportAnalyzer.kt"
        ),
        roadmapCheck(
            "intent.canonical-meaning.inventory-independent", "0.9.7.5", GateKind.BEHAVIOR,
            "standard/conformance/pre-closure-check-inventory.yaml"
        ),
        roadmapCheck(
            "intent.effects.universal-state-transition-model", "0.9.7.6", GateKind.BEHAVIOR,
            "standard/conformance/pre-closure-check-inventory.yaml"
        ),
        roadmapCheck(
            "intent.controls.universal-policy-requirements", "0.9.7.7", GateKind.SAFETY,
            "standard/conformance/pre-closure-check-inventory.yaml",
            negativeFixture = "tests/ClosureBlockingSafetyIntegrityTests.kt"
        ),
        roadmapCheck(
            "planning.topology.abstract-execution-model", "0.9.7.8", GateKind.EXECUTION_PLAN,
            "standard/conformance/pre-closure-check-inventory.yaml"
        ),
        roadmapCheck(
            "intent.lowering.diagnostic-honesty", "0.9.7.9", GateKind.DIAGNOSTICS,
            "standard/conformance/pre-closure-check-inventory.yaml"
        ),
        roadmapCheck(
            "flow.environment-safety.production-integration", "0.9.7.9.3", GateKind.SAFETY,
            "standard/conformance/pre-closure-check-inventory.yaml",
            negativeFixture = "tests/ClosureBlockingSafetyIntegrityTests.kt"
        ),
        roadmapCheck(
            "ai.normalization.scenario-negation-token-boundary-honesty", "0.9.7.9.4", GateKind.NORMALIZATION,
            "standard/conformance/pre-closure-check-inventory.yaml"
        ),
        roadmapCheck(
            "planning.provider-backed-approval-topology-identity", "0.9.7.9.5", GateKind.PORTABILITY,
            "standard/conformance/pre-closure-check-inventory.yaml"
        ),
        roadmapCheck(
            "governance.derived-model-integrity", "0.9.7.9.6", GateKind.REGISTRY_CONSISTENCY,
            "standard/conformance/pre-closure-check-inventory.yaml"
        ),
        roadmapCheck(
            "cli.release.diagnostic-honesty", "0.9.7.9.7", GateKind.DIAGNOSTICS,
            "src/main/kotlin/org/flowlang/conformance/CliReleaseHonestyChecks.kt"
        ),
        roadmapCheck(
            "governance.closure-blocking-safety-diagnostic-integrity", "0.9.7.9.8", GateKind.GOVERNANCE,
            "src/main/kotlin/org/flowlang/conformance/ClosureBlockingIntegrityChecks.kt",
            negativeFixture = "tests/ReleaseMetadataClosedLifecycleTests.kt"
        ),
        roadmapCheck(
            "governance.target-selection-provenance-cli-status-integrity", "0.9.7.9.10", GateKind.GOVERNANCE,
            "src/main/kotlin/org/flowlang/conformance/TargetSelectionProvenanceIntegrityChecks.kt",
            negativeFixture = "tests/ReleaseMetadataClosedLifecycleTests.kt"
        ),
        roadmapCheck(
            "v0.9.7.10.bounded-semantic-closure", "0.9.7.10", GateKind.GOVERNANCE,
            "src/main/kotlin/org/flowlang/release/SemanticClosureAuthority.kt",
            negativeFixture = "tests/SemanticClosureAuthorityTests.kt",
            inPreClosureSuite = false
        )
    )

    val artifacts: List<StandardArtifact> = listOf(
        StandardArtifact("intent.schema.json", "schemas/intent.schema.json", "stable", "intent", "pre-0.3.10", "additive-only", "intent-contract"),
        StandardArtifact("execution-plan.schema.json", "schemas/execution-plan.schema.json", "stable", "plan", "0.3.2", "additive-only", "execution-plan-contract"),
        StandardArtifact("target-manifest.schema.json", "schemas/target-manifest.schema.json", "stable", "target", "pre-0.3.10", "additive-only", "target-manifest-contract"),
        StandardArtifact("conformance-manifest.json", "schemas/conformance-manifest.schema.json", "stable", "conformance", "0.3.11", "release-gate", "conformance-manifest-contract", isEvidence = true),
        StandardArtifact("standard-contract-index.json", "schemas/standard-contract-index.schema.json", "stable", "standard", "0.3.16", "release-gate", "contract-index"),
        StandardArtifact("standard-release-profile.json", "schemas/standard-release-profile.schema.json", "stable", "release", "0.3.17", "release-gate", "release-profile-contract"),
        StandardArtifact("public-standard-surface.json", "schemas/public-standard-surface.schema.json", "stable", "standard", "0.4.5", "surface-freeze", "public-surface-contract", inCandidateLevel = true),
        StandardArtifact("compatibility-migration-policy.json", "schemas/compatibility-migration-policy.schema.json", "stable", "compatibility", "0.4.6", "compatibility-gate", "compatibility-contract", inCandidateLevel = true),
        StandardArtifact("reference-intent-corpus.json", "schemas/reference-intent-corpus.schema.json", "stable", "intent", "0.4.7", "reference-corpus-gate", "behavioral-corpus-contract", inCandidateLevel = true),
        StandardArtifact("target-semantics-matrix.json", "schemas/target-semantics-matrix.schema.json", "stable", "target", "0.4.8", "target-semantics-gate", "portability-contract", inCandidateLevel = true),
        StandardArtifact("standard-export-bundle.json", "schemas/standard-export-bundle.schema.json", "stable", "export", "0.4.9", "export-bundle-gate", "export-bundle-contract", inCandidateLevel = true),
        StandardArtifact("conformance-levels.json", "schemas/conformance-levels.schema.json", "stable", "conformance", "0.5.0", "public-standard-candidate-gate", "conformance-levels-contract", inCandidateLevel = true),
        StandardArtifact("standard-export-manifest.json", "schemas/standard-export-manifest.schema.json", "stable", "export", "0.5.0", "public-standard-candidate-gate", "export-manifest-contract", inCandidateLevel = true),
        StandardArtifact("conformance-vector-index.json", "schemas/conformance-vector-index.schema.json", "stable", "conformance", "0.5.4", "data-driven-conformance-gate", "vector-index-contract", inCandidateLevel = true, isEvidence = true),
        StandardArtifact("flow-standard-draft.json", "schemas/flow-standard-draft.schema.json", "draft", "standard", "0.4.0", "draft-review", "draft-contract", inExportBundle = false)
    )

    fun releaseProfileChecks(): List<StandardCheck> = checks.filter { it.inReleaseProfile }
    fun releaseProfileCheckIds(): List<String> = releaseProfileChecks().map { it.id }
    fun candidateChecks(): List<StandardCheck> = checks.filter { it.inCandidateLevel }
    fun candidateCheckIds(): List<String> = candidateChecks().map { it.id }
    fun standardExportManifestCheckIds(): List<String> = checks.filter { it.inExportManifest }.map { it.id }
    fun modeledPreClosureCheckIds(): List<String> = checks.filter { it.inPreClosureSuite }.map { it.id }
    fun modeledPostClosureCheckIds(): List<String> = checks.filterNot { it.inPreClosureSuite }.map { it.id }
    fun registryConsistencyCheckIds(): List<String> = checks.filter { it.kind == GateKind.REGISTRY_CONSISTENCY }.map { it.id }
    fun substanceCheckIds(): List<String> = checks.filter { it.kind.isSubstance }.map { it.id }

    fun stableArtifacts(): List<String> = publicArtifacts().filter { it.stability == "stable" }.map { it.artifact }
    fun draftArtifacts(): List<String> = publicArtifacts().filter { it.stability == "draft" }.map { it.artifact }
    fun experimentalArtifacts(): List<String> = publicArtifacts().filter { it.stability == "experimental" }.map { it.artifact }
    fun internalArtifacts(): List<String> = artifacts.filter { it.visibility == ArtifactVisibility.INTERNAL }.map { it.artifact }
    fun exportBundleArtifacts(): List<String> = publicArtifacts().filter { it.inExportBundle }.map { it.artifact }
    fun candidateArtifacts(): List<String> = publicArtifacts().filter { it.inCandidateLevel }.map { it.artifact }
    fun evidenceArtifacts(): List<String> = artifacts.filter { it.isEvidence }.map { it.artifact }
    fun stableSchemas(): List<String> = publicArtifacts().filter { it.stability == "stable" && it.schema.isNotBlank() }.map { it.schema }

    val substanceGateCount: Int get() = checks.count { it.kind.isSubstance }
    val registryConsistencyGateCount: Int get() = checks.count { it.kind == GateKind.REGISTRY_CONSISTENCY }

    fun wellFormednessIssues(rootDir: File? = null): List<String> {
        val issues = mutableListOf<String>()
        val duplicateChecks = checks.groupingBy { it.id }.eachCount().filter { it.value > 1 }.keys
        if (duplicateChecks.isNotEmpty()) issues += "Duplicate check ids: ${duplicateChecks.joinToString()}"

        val duplicateArtifacts = artifacts.groupingBy { it.artifact }.eachCount().filter { it.value > 1 }.keys
        if (duplicateArtifacts.isNotEmpty()) issues += "Duplicate artifacts: ${duplicateArtifacts.joinToString()}"

        val profile = releaseProfileCheckIds().toSet()
        val unknownCandidateChecks = candidateCheckIds().filterNot(profile::contains)
        if (unknownCandidateChecks.isNotEmpty()) {
            issues += "Candidate checks missing from release profile: ${unknownCandidateChecks.joinToString()}"
        }
        val unknownManifestChecks = standardExportManifestCheckIds().filterNot(profile::contains)
        if (unknownManifestChecks.isNotEmpty()) {
            issues += "Export-manifest checks missing from release profile: ${unknownManifestChecks.joinToString()}"
        }
        checks.filter { it.scope == StandardCheckScope.ROADMAP_GOVERNANCE }
            .filter { it.inReleaseProfile || it.inCandidateLevel || it.inExportManifest }
            .forEach { check ->
                issues += "Roadmap-governance check '${check.id}' cannot enter a public release projection implicitly."
            }

        publicArtifacts().filter { it.stability == "stable" }.forEach { artifact ->
            if (artifact.schema.isBlank()) issues += "Stable artifact '${artifact.artifact}' has no schema."
            if (artifact.role.isBlank()) issues += "Stable artifact '${artifact.artifact}' has no validation role."
            if (artifact.changeGate.isBlank()) issues += "Stable artifact '${artifact.artifact}' has no change gate."
        }

        val anchoredSubstanceKinds = setOf(
            GateKind.BEHAVIOR,
            GateKind.SAFETY,
            GateKind.DIAGNOSTICS,
            GateKind.GOVERNANCE
        )
        checks.filter { it.kind in anchoredSubstanceKinds }
            .filter { it.negativeFixture.isBlank() && it.externalAnchor.isBlank() }
            .forEach { check ->
                issues += "Substance check '${check.id}' must define a negativeFixture or externalAnchor."
            }

        val guardrailKinds = setOf(GateKind.SAFETY, GateKind.GOVERNANCE)
        checks.filter { it.kind in guardrailKinds && it.negativeFixture.isBlank() }
            .forEach { check ->
                issues += "Guardrail check '${check.id}' must define a negativeFixture."
            }

        if (rootDir != null) {
            checks.flatMap { check ->
                listOf(check.negativeFixture, check.externalAnchor)
                    .filter { it.isNotBlank() }
                    .map { reference -> check.id to reference.substringBefore("::") }
            }.filter { (_, reference) -> referenceLooksLikePath(reference) }
                .filterNot { (_, reference) -> File(rootDir, reference).exists() }
                .forEach { (id, reference) ->
                    issues += "Evidence reference '$reference' for check '$id' does not exist."
                }
        }

        val unusedKinds = GateKind.entries.filter { kind -> checks.none { it.kind == kind } }
        if (unusedKinds.isNotEmpty()) {
            issues += "Gate kinds without an owning check: ${unusedKinds.joinToString { it.name }}"
        }
        if (substanceGateCount == 0) {
            issues += "Standard model has no substance gates."
        } else if (registryConsistencyGateCount * 3 >= substanceGateCount) {
            issues += "Registry-consistency gates ($registryConsistencyGateCount) must stay below one third of substance gates ($substanceGateCount)."
        }

        return issues
    }

    private fun publicArtifacts(): List<StandardArtifact> =
        artifacts.filter { it.visibility == ArtifactVisibility.PUBLIC }

    private fun referenceLooksLikePath(reference: String): Boolean =
        reference.contains("/") || reference.endsWith(".kt") || reference.endsWith(".yaml") ||
            reference.endsWith(".yml") || reference.endsWith(".json")

    private fun roadmapCheck(
        id: String,
        introducedIn: String,
        kind: GateKind,
        externalAnchor: String,
        negativeFixture: String = "",
        inPreClosureSuite: Boolean = true
    ): StandardCheck = StandardCheck(
        id = id,
        introducedIn = introducedIn,
        kind = kind,
        scope = StandardCheckScope.ROADMAP_GOVERNANCE,
        inReleaseProfile = false,
        inCandidateLevel = false,
        inExportManifest = false,
        inPreClosureSuite = inPreClosureSuite,
        negativeFixture = negativeFixture,
        externalAnchor = externalAnchor
    )
}
