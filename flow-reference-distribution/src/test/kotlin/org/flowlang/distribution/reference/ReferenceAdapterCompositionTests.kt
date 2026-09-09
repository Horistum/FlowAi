package org.flowlang.distribution.reference

import java.io.File
import kotlin.test.*
import org.flowlang.compiler.requireAccepted
import org.flowlang.frontend.FrontendCompilerComposition
import org.flowlang.frontend.intent.IntentYamlFrontend
import org.flowlang.generators.manifest.TargetProjectionRegistry
import org.flowlang.materialization.TargetDiagnosticMaterializationRequest
import org.flowlang.materialization.TargetSelectionAuthority
import org.flowlang.modules.ModuleRegistry
import org.flowlang.targets.TargetRegistryYamlLoader
import org.flowlang.targets.builtin.BuiltInTargetProjections
import org.flowlang.testing.ExternalCompilerProbe

class ReferenceAdapterCompositionTests {
    @Test fun allProvidersComeFromTheirOwnProductionModules() {
        assertEquals(setOf("jenkins", "github-actions", "tekton"), ReferenceTargetProjections.registry.targetIds)
        ReferenceTargetProjections.registry.targetIds.forEach { target ->
            val provider = ReferenceTargetProjections.registry.requireProvider(target)
            assertTrue(provider.generator.javaClass.protectionDomain.codeSource.location.toString().contains("flow-adapter-$target"))
            assertTrue(provider.renderer.javaClass.protectionDomain.codeSource.location.toString().contains("flow-adapter-$target"))
        }
    }

    @Suppress("DEPRECATION")
    @Test fun theCompatibilityFacadeDelegatesRatherThanComposingAnotherRegistry() {
        assertSame(ReferenceTargetProjections.registry, BuiltInTargetProjections.registry)
    }

    @Test fun anExplicitlyEmptyCatalogCannotFallBackToReferenceProviders() {
        val modules = ModuleRegistry()
        val compilation = IntentYamlFrontend(FrontendCompilerComposition.compiler(modules))
            .compile(File("examples/intent/checkout-build-image.intent.yaml")).requireAccepted()
        val targets = TargetRegistryYamlLoader.loadDirectory(File("targets"))
        val selection = TargetSelectionAuthority.fromReferenceSnapshot("jenkins", "ar03c-empty-catalog", targets)
        val pipeline = ReferenceTargetProjections.pipeline(targets, projections = TargetProjectionRegistry.empty(), modules = modules)
        val failure = assertFailsWith<IllegalStateException> {
            pipeline.generateDiagnosticEvidence(TargetDiagnosticMaterializationRequest.fromCompilation(compilation, selection))
        }
        assertTrue(failure.message.orEmpty().contains("No target projection provider"), failure.message)
    }

    @Test fun theReferenceDistributionDoesNotDependOnConformanceOrCli() {
        listOf("org.flowlang.conformance.ConformanceRunner", "org.flowlang.cli.honest.CliTargetEvidenceAuthority").forEach { type ->
            ExternalCompilerProbe.rejects("fun forbidden(value: $type) = value", "Unresolved reference")
        }
    }
}
