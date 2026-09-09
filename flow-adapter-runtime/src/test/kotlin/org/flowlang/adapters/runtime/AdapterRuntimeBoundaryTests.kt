package org.flowlang.adapters.runtime

import kotlin.test.*
import org.flowlang.adapters.contract.AdapterCatalog
import org.flowlang.capabilities.TargetCapability
import org.flowlang.generators.manifest.*
import org.flowlang.materialization.*
import org.flowlang.modules.FlowModule
import org.flowlang.modules.ModuleCatalog
import org.flowlang.planner.ExecutionPlan
import org.flowlang.testing.ExternalCompilerProbe

@Suppress("DEPRECATION")
class AdapterRuntimeBoundaryTests {
    private val modules = object : ModuleCatalog {
        override fun findModule(name: String): FlowModule? = null
        override fun allModules(): Collection<FlowModule> = emptyList()
    }

    @Test fun aMissingProviderIsNotReplacedByABuiltInAdapter() {
        val targets = mapOf("custom" to TargetCapability("custom", "Explicit custom adapter"))
        val selection = CompatibilityMaterializationBoundary.conformanceSelection("custom", "catalog-missing", targets)
        val pipeline = TargetManifestGenerationPipeline(targets, TargetProjectionRegistry.empty(), modules = modules)
        val failure = assertFailsWith<IllegalStateException> {
            pipeline.generateDiagnosticEvidence(CompatibilityMaterializationBoundary.diagnosticRequest(ExecutionPlan("missing-provider"), selection))
        }
        assertTrue(failure.message.orEmpty().contains("No target projection provider"), failure.message)
    }

    @Test fun aCatalogCannotRelabelAProviderBeforeGeneration() {
        var calls = 0
        val provider = TargetProjectionProvider(object : TargetManifestGenerator {
            override val target = "original"
            override fun generate(authorization: TargetProjectionAuthorization): TargetManifest {
                calls++
                error("A relabeled provider must never be invoked")
            }
        }, object : TargetManifestRenderer {
            override val target = "original"
            override val artifactFileName = "custom.txt"
            override fun render(manifest: TargetManifest): String = error("Not invoked")
        })
        val catalog = object : AdapterCatalog<TargetProjectionProvider> {
            override val targetIds = setOf("forged")
            override fun adapterFor(target: String) = provider
        }
        val targets = mapOf("forged" to TargetCapability("forged", "Relabeled adapter"))
        val selection = CompatibilityMaterializationBoundary.conformanceSelection("forged", "relabel", targets)
        val pipeline = TargetManifestGenerationPipeline(targets, catalog, modules = modules)
        assertFailsWith<IllegalArgumentException> {
            pipeline.generateDiagnosticEvidence(CompatibilityMaterializationBoundary.diagnosticRequest(ExecutionPlan("relabel"), selection))
        }
        assertEquals(0, calls)
        assertFailsWith<IllegalArgumentException> { catalog.requireProvider("forged") }
    }

    @Test fun malformedCompatibilityEvidenceStillFailsBeforeProviderLookup() {
        val targets = mapOf("custom" to TargetCapability("custom", "Custom"))
        val selection = CompatibilityMaterializationBoundary.conformanceSelection("custom", "invalid-plan", targets)
        val pipeline = TargetManifestGenerationPipeline(targets, TargetProjectionRegistry.empty(), modules = modules)
        val failure = assertFailsWith<InvalidPlanningEvidenceException> {
            pipeline.generateDiagnosticEvidence(CompatibilityMaterializationBoundary.diagnosticRequest(ExecutionPlan(""), selection))
        }
        assertTrue(failure.issues.any { it.code == "planning.flow-name.missing" })
    }

    @Test fun capabilityMatrixUsesOnlyTheExplicitDistributionRequirements() {
        val targets = mapOf("custom" to TargetCapability("custom", "Independent distribution"))
        val matrix = org.flowlang.capabilities.TargetCapabilityMatrixAnalyzer(targets, setOf("custom")).analyze()
        assertEquals("PASS", matrix.status, matrix.issues.joinToString())
        assertEquals(listOf("custom"), matrix.targets)
        ExternalCompilerProbe.rejects("""
            import org.flowlang.capabilities.*
            fun implicit(targets: Map<String, TargetCapability>) = TargetCapabilityMatrixAnalyzer(targets)
        """.trimIndent(), "requiredTargets")
    }

    @Test fun independentConsumersCanComposeTheSPIWithDecodedContracts() {
        ExternalCompilerProbe.accepts("""
            import org.flowlang.adapters.contract.AdapterCatalog
            import org.flowlang.capabilities.TargetCapability
            import org.flowlang.generators.manifest.*
            import org.flowlang.modules.ModuleCatalog
            fun pipeline(targets: Map<String, TargetCapability>, adapters: AdapterCatalog<TargetProjectionProvider>, modules: ModuleCatalog) =
                TargetManifestGenerationPipeline(targets, adapters, modules = modules)
        """.trimIndent())
    }

    @Test fun externalConsumersCannotManufactureProjectionAuthorization() {
        ExternalCompilerProbe.rejects("""
            import org.flowlang.compiler.CompilationAuthorization
            import org.flowlang.materialization.ExplicitTargetSelection
            import org.flowlang.capabilities.CompatibilityReport
            import org.flowlang.topology.ExecutionTopologyAssessment
            import org.flowlang.generators.manifest.TargetProjectionAuthorization
            fun forge(a: CompilationAuthorization, s: ExplicitTargetSelection, c: CompatibilityReport, t: ExecutionTopologyAssessment) =
                TargetProjectionAuthorization(a, s, c, false, t)
        """.trimIndent(), "internal")
    }

    @Test fun aModuleCatalogIsRequiredRatherThanLoadedImplicitly() {
        ExternalCompilerProbe.rejects("""
            import org.flowlang.capabilities.TargetCapability
            import org.flowlang.generators.manifest.*
            fun pipeline(targets: Map<String, TargetCapability>, adapters: TargetProjectionRegistry) =
                TargetManifestGenerationPipeline(targets, adapters)
        """.trimIndent(), "modules")
    }

    @Test fun concreteAdaptersEvidenceDistributionAndFixturesAreAbsentFromTheProductionClasspath() {
        listOf(
            "org.flowlang.targets.builtin.JenkinsManifestRenderer",
            "org.flowlang.targets.builtin.GitHubActionsManifestRenderer",
            "org.flowlang.targets.builtin.TektonManifestRenderer",
            "org.flowlang.adapters.continuity.AdapterContinuitySatisfactionAuthority",
            "org.flowlang.distribution.reference.ReferenceTargetProjections",
            "org.flowlang.adapters.testing.AdapterRuntimeTestFixtures",
            "org.flowlang.conformance.ConformanceRunner"
        ).forEach { type -> ExternalCompilerProbe.rejects("fun forbidden(value: $type) = value", "Unresolved reference") }
    }
}
