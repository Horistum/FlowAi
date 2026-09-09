package org.flowlang.targets.builtin

import kotlin.test.*
import org.flowlang.capabilities.CompatibilityReport
import org.flowlang.capabilities.SupportLevel
import org.flowlang.generators.manifest.TargetManifest
import org.flowlang.testing.ExternalCompilerProbe

class JenkinsModuleBoundaryTests {
    @Test fun generatorRendererAndNativeEvidenceHaveOneConcreteOwner() {
        val generator = JenkinsManifestGenerator()
        val renderer = JenkinsManifestRenderer()
        assertEquals("jenkins", generator.target)
        assertEquals(generator.target, renderer.target)
        assertEquals(generator.target, generator.nativeProjectionCatalog.target)
        assertTrue(generator.nativeProjectionCatalog.definitions.isNotEmpty())
        assertTrue(generator.javaClass.protectionDomain.codeSource.location.toString().contains("flow-adapter-jenkins"))
    }

    @Test fun aForeignManifestCannotBeRendered() {
        val manifest = TargetManifest(target = "foreign", flowName = "foreign",
            compatibility = CompatibilityReport("foreign", SupportLevel.UNSUPPORTED))
        assertFailsWith<IllegalArgumentException> { JenkinsManifestRenderer().render(manifest) }
    }

    @Test fun theConcreteAdapterCanBeUsedWithoutItsSiblings() {
        ExternalCompilerProbe.accepts("""
            import org.flowlang.targets.builtin.JenkinsManifestGenerator
            import org.flowlang.targets.builtin.JenkinsManifestRenderer
            import org.flowlang.generators.manifest.TargetProjectionProvider
            fun provider() = TargetProjectionProvider(JenkinsManifestGenerator(), JenkinsManifestRenderer())
        """.trimIndent())
        listOf(
            "org.flowlang.targets.builtin.GitHubActionsManifestRenderer",
            "org.flowlang.targets.builtin.TektonManifestRenderer",
            "org.flowlang.distribution.reference.ReferenceTargetProjections",
            "org.flowlang.conformance.ConformanceRunner"
        ).forEach { type -> ExternalCompilerProbe.rejects("fun forbidden(value: $type) = value", "Unresolved reference") }
    }
}
