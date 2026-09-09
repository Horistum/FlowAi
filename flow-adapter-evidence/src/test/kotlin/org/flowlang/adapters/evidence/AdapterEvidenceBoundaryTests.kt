package org.flowlang.adapters.evidence

import kotlin.test.Test
import org.flowlang.testing.ExternalCompilerProbe

class AdapterEvidenceBoundaryTests {
    @Test fun explicitCatalogEvidenceCompositionCompilesIndependently() {
        ExternalCompilerProbe.accepts("""
            import java.io.File
            import org.flowlang.adapters.contract.AdapterCatalog
            import org.flowlang.adapters.control.AdapterControlMaterializationAuthority
            import org.flowlang.capabilities.TargetCapability
            import org.flowlang.generators.manifest.TargetProjectionProvider
            fun evidence(root: File, targets: Map<String, TargetCapability>, adapters: AdapterCatalog<TargetProjectionProvider>) =
                AdapterControlMaterializationAuthority(root, targets, adapters)
        """.trimIndent())
    }

    @Test fun noConcreteCatalogIsAvailableAsAnImplicitDefault() {
        ExternalCompilerProbe.rejects("""
            import java.io.File
            import org.flowlang.adapters.control.AdapterControlMaterializationAuthority
            import org.flowlang.capabilities.TargetCapability
            fun evidence(root: File, targets: Map<String, TargetCapability>) = AdapterControlMaterializationAuthority(root, targets)
        """.trimIndent(), "projections")
    }

    @Test fun genericEvidenceCannotImportConcreteImplementationsOrReferenceComposition() {
        listOf(
            "org.flowlang.targets.builtin.JenkinsManifestRenderer",
            "org.flowlang.targets.builtin.GitHubActionsManifestRenderer",
            "org.flowlang.targets.builtin.TektonManifestRenderer",
            "org.flowlang.adapters.continuity.BuiltInAdapterContinuityScopedSupport",
            "org.flowlang.distribution.reference.ReferenceAdapterEvidence",
            "org.flowlang.conformance.ConformanceRunner",
            "org.flowlang.adapters.testing.RenderingEvidenceTestFixture"
        ).forEach { type -> ExternalCompilerProbe.rejects("fun forbidden(value: $type) = value", "Unresolved reference") }
    }
}
