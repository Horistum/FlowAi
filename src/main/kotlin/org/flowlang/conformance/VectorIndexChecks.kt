package org.flowlang.conformance

import org.flowlang.artifacts.StandardReleaseProfile
import org.flowlang.artifacts.StandardSurface
import org.flowlang.modules.ModuleRegistry
import java.io.File
import org.flowlang.generators.manifest.TargetProjectionRegistry

internal class VectorIndexChecks(
    rootDir: File,
    registry: ModuleRegistry,
    targets: Map<String, org.flowlang.capabilities.TargetCapability>,
    projections: TargetProjectionRegistry
) : ConformanceCheckSupport(rootDir, registry, targets, projections) {
    fun checks(priorCheckNames: List<String>): List<ConformanceCheck> = listOf(
        checkV054DataDrivenConformanceIndex(
            priorCheckNames + "v0.5.4.data-driven-conformance-index" + postVectorIndexChecks()
        )
    )

    private fun checkV054DataDrivenConformanceIndex(runnerChecks: List<String>): ConformanceCheck = runCheck("v0.5.4.data-driven-conformance-index") {
        val releaseProfile = StandardReleaseProfile.report()
        val candidate = StandardSurface.conformanceLevels().levels.first { it.id == "standard-candidate" }
        val requiredGate = "v0.5.4.data-driven-conformance-index"
        val index = ConformanceVectorIndexBuilder(rootDir).build(
            runnerChecks = runnerChecks,
            releaseProfileChecks = releaseProfile.requiredConformanceChecks
        )

        require(activeStandardAtLeast(0, 5, 4)) {
            "Data-driven conformance index must carry standardVersion 0.5.4 or later."
        }
        require(StandardSurface.publicSurface().stableArtifacts.contains("conformance-vector-index.json")) {
            "Conformance vector index must be part of the stable public surface."
        }
        require(StandardSurface.standardExportBundle().requiredFiles.contains("conformance-vector-index.json")) {
            "Standard export bundle must include conformance-vector-index.json."
        }
        require(candidate.requiredChecks.contains(requiredGate)) {
            "The standard-candidate conformance level must require the data-driven vector index gate."
        }
        require(releaseProfile.requiredConformanceChecks.contains(requiredGate)) {
            "The release profile must require the data-driven vector index gate."
        }
        require(index.status == "PASS") {
            "Conformance vector index must pass: missingRequired=${index.vectorsMissingRequiredCheck}, missingRunner=${index.checksMissingFromRunner}, missingReleaseVectors=${index.releaseProfileChecksMissingVector}"
        }
        require(index.vectorCount > 0) { "Conformance vector index must include public vector files." }
        require(index.entries.all { it.hasRequiredCheck }) { "Every public vector must declare requiredCheck or legacy name." }
        require(index.requiredChecksFromVectors.contains(requiredGate)) {
            "The data-driven vector index gate must be represented by a public vector."
        }
    }
}
