package org.flowlang.conformance

import java.io.File
import org.flowlang.adapters.yaml.TargetRegistryYamlLoader
import org.flowlang.generators.manifest.TargetProjectionRegistry
import org.flowlang.modules.ModuleRegistry
import org.flowlang.release.ReleaseMetadataHonestyAuthority
import org.flowlang.targets.builtin.BuiltInTargetProjections

/**
 * CLI-facing conformance orchestrator.
 *
 * The historical monolith is split into explicit evidence phases. The frozen
 * Core pre-closure list is built independently and is the only input accepted
 * by [SemanticClosureChecks]. Frozen A0 adapter certification runs next. New
 * adapter evolution is composed through a separate post-A0 inventory. Bounded
 * C0.1 corpus evidence follows, C0.2 topology evidence remains independently
 * inventoried, and C0.3 semantic equivalence is appended last so it cannot
 * rewrite any earlier authority or treat implementation output as Core meaning.
 */
class ConformanceRunner(
    private val rootDir: File = File("."),
    private val registry: ModuleRegistry =
        if (File(rootDir, "modules").isDirectory) ModuleRegistry.fromDirectory(File(rootDir, "modules")) else ModuleRegistry(),
    private val targets: Map<String, org.flowlang.capabilities.TargetCapability> =
        TargetRegistryYamlLoader.loadDirectory(File(rootDir, "targets")).also {
            require(it.isNotEmpty()) { "No target registry found under ${File(rootDir, "targets").path}." }
        },
    private val projections: TargetProjectionRegistry = BuiltInTargetProjections.registry
) {
    fun run(): ConformanceSummary {
        val preClosureChecks = buildPreClosureChecks()
        val closureChecks = buildClosureChecks(preClosureChecks)
        val adapterChecks = AdapterStreamConformanceRunner(rootDir, targets, projections).checks()
        val adapterEvolutionChecks = AdapterExecutableContinuityConformanceRunner(rootDir, targets, projections).checks()
        val realWorldChecks = RealWorldCorpusConformanceChecks(rootDir, registry, targets, projections).checks()
        val topologyMatrixChecks = AbstractTopologyMatrixConformanceRunner(rootDir, registry, targets).checks()
        val semanticEquivalenceChecks = SemanticEquivalenceConformanceRunner(rootDir, registry).checks()

        return ConformanceSummary(
            preClosureChecks + closureChecks + adapterChecks + adapterEvolutionChecks +
                realWorldChecks + topologyMatrixChecks + semanticEquivalenceChecks
        )
    }

    private fun buildPreClosureChecks(): List<ConformanceCheck> {
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
        return checks.toList()
    }

    private fun buildClosureChecks(preClosureChecks: List<ConformanceCheck>): List<ConformanceCheck> {
        val releaseLifecycle = ReleaseMetadataHonestyAuthority(rootDir).analyze()
        return if (releaseLifecycle.status != "PASS" || releaseLifecycle.closurePhase != "CORRECTION_REQUIRED") {
            SemanticClosureChecks(rootDir).checks(preClosureChecks)
        } else {
            emptyList()
        }
    }
}
