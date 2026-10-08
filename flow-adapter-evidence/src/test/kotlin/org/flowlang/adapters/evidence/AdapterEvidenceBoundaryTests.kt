package org.flowlang.adapters.evidence

import kotlin.test.Test
import org.flowlang.testing.ExternalCompilerProbe

class AdapterEvidenceBoundaryTests {
    @Test fun authenticatedAdmissionCompilesWithoutConcreteAdaptersOrConformance() {
        ExternalCompilerProbe.accepts("""
            import org.flowlang.adapters.certification.*
            import org.flowlang.generators.manifest.TargetNativeProjectionCatalog
            fun admit(bundle: AdapterCertificationBundle, adapter: CertificationAdapterIdentity,
                captures: List<BoundCertificationScenario>, provider: TargetNativeProjectionCatalog,
                observations: List<SignedCertificationObservation>, trust: CertificationObservationTrust,
                resolver: CertificationEvidenceResolver) =
                AuthenticatedAdapterCertificationAdmission.evaluate(bundle, adapter, captures, provider, observations, trust, resolver)
        """.trimIndent())
    }

    @Test fun boundCertificationCompilesWithoutReferenceDistribution() {
        ExternalCompilerProbe.accepts("""
            import org.flowlang.adapters.certification.*
            import org.flowlang.adapters.rendering.AdapterArtifactRenderingAuthority
            import org.flowlang.generators.manifest.*
            import org.flowlang.materialization.TargetMaterializationRequest
            fun capture(source: ByteArray, request: TargetMaterializationRequest,
                pipeline: TargetManifestGenerationPipeline, rendering: AdapterArtifactRenderingAuthority,
                provider: TargetNativeProjectionCatalog) =
                BoundCertificationScenario.capture("scenario", source, request, pipeline, rendering, provider)
        """.trimIndent())
    }

    @Test fun callersCannotConstructTheirOwnBoundScenario() {
        ExternalCompilerProbe.rejects("""
            import org.flowlang.adapters.certification.*
            fun forge(reference: CertificationEvidenceReference) = BoundCertificationScenario(
                "forged", "target", "digest", reference, reference, reference, emptySet(), emptyMap())
        """.trimIndent(), "private")
    }

    @Test fun certificationAdmissionCompilesWithoutConcreteAdaptersOrConformance() {
        ExternalCompilerProbe.accepts("""
            import org.flowlang.adapters.certification.*
            import org.flowlang.generators.manifest.TargetNativeProjectionCatalog
            fun admit(bundle: AdapterCertificationBundle, adapter: CertificationAdapterIdentity,
                capabilities: Set<String>, provider: TargetNativeProjectionCatalog,
                observations: List<CertificationExecutionObservation>, resolver: CertificationEvidenceResolver) =
                AdapterCertificationAdmission.evaluate(bundle, adapter, capabilities, provider, observations, resolver)
        """.trimIndent())
    }

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
