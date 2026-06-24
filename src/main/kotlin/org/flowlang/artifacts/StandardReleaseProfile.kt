package org.flowlang.artifacts

import org.flowlang.standard.FlowStandardVersions
import org.flowlang.standard.StandardModel


data class ReleaseRequirement(
    val id: String,
    val area: String,
    val artifact: String,
    val required: Boolean,
    val description: String
)

data class ReleaseGateClassification(
    val id: String,
    val category: String,
    val introducedIn: String,
    val activeQualityGate: Boolean,
    val description: String
)

data class StandardReleaseProfileReport(
    val standardVersion: String = FlowStandardVersions.FLOW_STANDARD_VERSION,
    val releaseProfileVersion: String = "1.2",
    val profile: String = "standard-public-release",
    val minimumStandardVersion: String = "0.4.0",
    val checkIdSemantics: String = "requiredConformanceChecks use the version in which the contract/check was introduced; standardVersion is the active release version.",
    val requirements: List<ReleaseRequirement>,
    val requiredArtifacts: List<String>,
    val requiredConformanceChecks: List<String>,
    val gateClassifications: List<ReleaseGateClassification>,
    val registryConsistencyChecks: List<String>,
    val behaviorSafetyNormalizationChecks: List<String>,
    val governanceChecks: List<String>
)

/**
 * Public release profile derived from StandardModel.
 *
 * Required check ids, categories and public artifacts are projected from one
 * model rather than repeated in parallel lists. This is boring, which is exactly
 * what a release profile should be.
 */
object StandardReleaseProfile {
    fun report(): StandardReleaseProfileReport {
        val requirements = releaseRequirements()
        val classifications = StandardModel.releaseProfileChecks().map { check ->
            ReleaseGateClassification(
                id = check.id,
                category = check.kind.category,
                introducedIn = check.introducedIn,
                activeQualityGate = check.kind.isSubstance,
                description = "Release gate classification for audit and drift review."
            )
        }
        return StandardReleaseProfileReport(
            requirements = requirements,
            requiredArtifacts = requirements.map { it.artifact }.distinct().sorted(),
            requiredConformanceChecks = classifications.map { it.id },
            gateClassifications = classifications,
            registryConsistencyChecks = classifications.filter { it.category == "registry-consistency" }.map { it.id },
            behaviorSafetyNormalizationChecks = classifications
                .filter { it.category in setOf("behavior", "safety", "normalization", "execution-plan") }
                .map { it.id },
            governanceChecks = classifications.filter { it.category == "governance" }.map { it.id }
        )
    }

    fun releaseRequirements(): List<ReleaseRequirement> = listOf(
        requirement("public.contract-index", "contract", "standard-contract-index.json", "Public contract index must be emitted."),
        requirement("public.release-profile", "release", "standard-release-profile.json", "Release profile must describe required public gates."),
        requirement("public.evidence", "evidence", "artifact-evidence-report.json", "Evidence report must describe derivation and provenance."),
        requirement("public.compliance", "compliance", "standard-compliance-report.json", "Compliance report must aggregate public release gates."),
        requirement("diagnostics.coverage-pass", "diagnostics", "diagnostic-coverage-report.json", "Diagnostic coverage must pass."),
        requirement("artifacts.integrity-pass", "artifacts", "artifact-integrity-report.json", "Artifact integrity must pass."),
        requirement("conformance.manifest-pass", "conformance", "conformance-manifest.json", "Conformance manifest must pass."),
        requirement("standard.freeze-pass", "standard", "standard-freeze-report.json", "Standard freeze report must pass."),
        requirement("standard.compatibility-policy", "standard", "compatibility-policy.json", "Compatibility policy must be published."),
        requirement("standard.reference-corpus", "standard", "reference-corpus-index.json", "Reference corpus must be published."),
        requirement("standard.negative-corpus", "standard", "negative-conformance-corpus.json", "Negative conformance corpus must be published."),
        requirement("target.conformance-profile", "target", "target-conformance-profile.json", "Target conformance profile must be published."),
        surfaceRequirement("standard.public-surface", "public-standard-surface.json", "Public standard surface must be frozen and published."),
        surfaceRequirement("standard.compatibility-migration", "compatibility-migration-policy.json", "Compatibility and migration policy must be published."),
        surfaceRequirement("standard.reference-intent-corpus", "reference-intent-corpus.json", "Reference intent corpus must be published."),
        surfaceRequirement("target.semantics-matrix", "target-semantics-matrix.json", "Target semantics matrix must be published."),
        surfaceRequirement("standard.export-bundle", "standard-export-bundle.json", "Standard export bundle manifest must be published."),
        surfaceRequirement("conformance.levels", "conformance-levels.json", "Public conformance levels must be published."),
        surfaceRequirement("standard.export-manifest", "standard-export-manifest.json", "Standard export manifest must be published."),
        surfaceRequirement("conformance.vector-index", "conformance-vector-index.json", "Conformance vector index must be published."),
        requirement("standard.index", "standard", "standard-index.json", "Standard index must be published."),
        requirement("standard.conformance-suite", "conformance", "conformance-suite.json", "Conformance suite index must be published."),
        surfaceRequirement("standard.draft", "flow-standard-draft.json", "Flow Standard Draft 0.4 must be published.")
    )

    private fun surfaceRequirement(id: String, artifactName: String, description: String): ReleaseRequirement {
        val artifact = StandardModel.artifacts.first { it.artifact == artifactName }
        return requirement(id, artifact.area, artifact.artifact, description)
    }


    private fun requirement(id: String, area: String, artifact: String, description: String): ReleaseRequirement =
        ReleaseRequirement(id, area, artifact, required = true, description = description)
}
