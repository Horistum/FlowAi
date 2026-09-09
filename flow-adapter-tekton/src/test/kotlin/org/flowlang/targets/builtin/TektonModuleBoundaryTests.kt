package org.flowlang.targets.builtin

import kotlin.test.*
import org.flowlang.capabilities.CompatibilityReport
import org.flowlang.capabilities.SupportLevel
import org.flowlang.generators.manifest.TargetManifest
import org.flowlang.testing.ExternalCompilerProbe

class TektonModuleBoundaryTests {
    @Test fun generatorRendererAndNativeEvidenceHaveOneConcreteOwner() {
        val generator = TektonManifestGenerator()
        val renderer = TektonManifestRenderer()
        assertEquals("tekton", generator.target)
        assertEquals(generator.target, renderer.target)
        assertEquals(generator.target, generator.nativeProjectionCatalog.target)
        assertTrue(generator.nativeProjectionCatalog.definitions.isNotEmpty())
        assertTrue(generator.javaClass.protectionDomain.codeSource.location.toString().contains("flow-adapter-tekton"))
    }

    @Test fun aForeignManifestCannotBeRendered() {
        val manifest = TargetManifest(target = "foreign", flowName = "foreign",
            compatibility = CompatibilityReport("foreign", SupportLevel.UNSUPPORTED))
        assertFailsWith<IllegalArgumentException> { TektonManifestRenderer().render(manifest) }
    }

    @Test fun theConcreteAdapterCanBeUsedWithoutItsSiblings() {
        ExternalCompilerProbe.accepts("""
            import org.flowlang.targets.builtin.TektonManifestGenerator
            import org.flowlang.targets.builtin.TektonManifestRenderer
            import org.flowlang.generators.manifest.TargetProjectionProvider
            fun provider() = TargetProjectionProvider(TektonManifestGenerator(), TektonManifestRenderer())
        """.trimIndent())
        listOf(
            "org.flowlang.targets.builtin.JenkinsManifestRenderer",
            "org.flowlang.targets.builtin.GitHubActionsManifestRenderer",
            "org.flowlang.distribution.reference.ReferenceTargetProjections",
            "org.flowlang.conformance.ConformanceRunner"
        ).forEach { type -> ExternalCompilerProbe.rejects("fun forbidden(value: $type) = value", "Unresolved reference") }
    }
}
