package org.flowlang.conformance

import org.flowlang.adapters.yaml.TargetRegistryYamlLoader
import org.flowlang.modules.ModuleRegistry
import org.flowlang.release.ReleaseMetadataHonestyAuthority
import org.flowlang.targets.builtin.BuiltInTargetProjections
import java.io.File
import org.flowlang.generators.manifest.TargetProjectionRegistry

/**
 * CLI-facing conformance orchestrator.
 *
 * The historical monolith is split into ordered groups while preserving the
 * exact Core pre-closure sequence and the data-driven vector index boundary.
 * The bounded semantic closure check terminates the frozen Core evidence phase.
 * Adapter-stream certification runs afterwards from its own committed inventory,
 * so adapter evolution cannot rewrite the evidence set that closed Core v0.9.7.
 */
class ConformanceRunner(
    private val rootDir: File = File("."),
    private val registry: ModuleRegistry =
        if (File(rootDir, "modules").isDirectory) {
            ModuleRegistry.fromDirectory(File(rootDir, "modules"))
        } else {
            ModuleRegistry()
        },
    private val targets: Map<String, org.flowlang.capabilities.TargetCapability> =
        TargetRegistryYamlLoader.loadDirectory(File(rootDir, "targets")).also {
            require(it.isNotEmpty()) {
                "No target registry found under ${File(rootDir, "targets").path}."
            }
        },
    private val projections: TargetProjectionRegistry = BuiltInTargetProjections.registry
) {
    fun run(): ConformanceSummary {
        val checks = mutableListOf<ConformanceCheck>()
        checks += CorePipelineSnapshotChecks(rootDir, registry, targets, projections).checks()
        checks += StandardArchitectureNormalizationChecks(rootDir, registry, targets, projections).checks()
        checks += SchemaScenarioCatalogChecks(rootDir, registry, targets, projections).checks()
        checks += ScenarioAndPlanChecks(rootDir, registry, targets, projections).checks()
        checks += PlanningReadinessChecks(rootDir, registry, targets, projections).checks()
        checks += DecisionAndArtifactChecks(rootDir, registry, targets, projections).checks()
        checks += StandardEvidenceChecks(rootDir, registry, targets, projections).checks()
        checks += SemanticBoundaryChecks(rootDir, registry, targets, projections).checks()
        checks += ProjectionSurfaceChecks(rootDir, registry, targets, projections).checks()
        checks += TargetSemanticsExportChecks(rootDir, registry, targets, projections).checks()
        checks += ExportManifestVerifierChecks(rootDir, registry, targets, projections).checks()
        checks += CliReleaseHonestyChecks(rootDir, registry, targets, projections).checks()
        checks += TargetSelectionProvenanceIntegrityChecks(rootDir, registry, targets, projections).checks()
        checks += ClosureBlockingIntegrityChecks(rootDir, registry, targets, projections).checks()
        checks += ClosureEvidenceBoundaryChecks(rootDir, registry, targets, projections).checks()
        checks += VectorIndexChecks(rootDir, registry, targets, projections).checks(checks.map { it.name })
        checks += IntentSafetyChecks(rootDir, registry, targets, projections).checks()
        checks += TrustAndReferenceChecks(rootDir, registry, targets, projections).checks()
        checks += ArchitectureCoherenceChecks(rootDir, registry, targets, projections).checks()
        checks += DeltaPurposeChecks(rootDir, registry, targets, projections).checks()
        checks += ConformanceQualityGates.run()

        val releaseLifecycle = ReleaseMetadataHonestyAuthority(rootDir).analyze()
        if (releaseLifecycle.status != "PASS" || releaseLifecycle.closurePhase != "CORRECTION_REQUIRED") {
            checks += SemanticClosureChecks(rootDir).checks(checks.toList())
        }
        checks += AdapterStreamConformanceRunner(rootDir, targets, projections).checks()
        return ConformanceSummary(checks)
    }
}
