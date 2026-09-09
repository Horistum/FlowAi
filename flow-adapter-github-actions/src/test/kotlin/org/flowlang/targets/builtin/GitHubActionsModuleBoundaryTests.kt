package org.flowlang.targets.builtin

import kotlin.test.*
import org.flowlang.capabilities.CompatibilityReport
import org.flowlang.capabilities.SupportLevel
import org.flowlang.generators.manifest.TargetManifest
import org.flowlang.testing.ExternalCompilerProbe

class GitHubActionsModuleBoundaryTests {
    private fun renderer() = GitHubActionsManifestRenderer(
        org.flowlang.generators.manifest.TargetEnvironmentSafetyEvidenceResolver(
            org.flowlang.safety.EnvironmentSafetyPolicy(org.flowlang.safety.EnvironmentSafetyPolicyNotes(
                packageId = "test.environment", packageVersion = "1.0",
                rules = listOf(org.flowlang.safety.EnvironmentPolicyRule(
                    "test.sensitive", setOf("environment"), setOf("protected"),
                    org.flowlang.safety.EnvironmentSensitivity.SENSITIVE, reason = "Explicit test-only policy"
                )), unknownReason = "Unknown test environment"
            ))
        )
    )

    @Test fun generatorRendererAndNativeEvidenceHaveOneConcreteOwner() {
        val generator = GitHubActionsManifestGenerator()
        val renderer = renderer()
        assertEquals("github-actions", generator.target)
        assertEquals(generator.target, renderer.target)
        assertEquals(generator.target, generator.nativeProjectionCatalog.target)
        assertTrue(generator.nativeProjectionCatalog.definitions.isNotEmpty())
        assertTrue(generator.javaClass.protectionDomain.codeSource.location.toString().contains("flow-adapter-github-actions"))
    }

    @Test fun aForeignManifestCannotBeRendered() {
        val manifest = TargetManifest(target = "foreign", flowName = "foreign",
            compatibility = CompatibilityReport("foreign", SupportLevel.UNSUPPORTED))
        assertFailsWith<IllegalArgumentException> { renderer().render(manifest) }
    }

    @Test fun theConcreteAdapterCanBeUsedWithoutItsSiblings() {
        ExternalCompilerProbe.accepts("""
            import org.flowlang.targets.builtin.GitHubActionsManifestGenerator
            import org.flowlang.targets.builtin.GitHubActionsManifestRenderer
            import org.flowlang.generators.manifest.TargetProjectionProvider
            fun provider() = TargetProjectionProvider(GitHubActionsManifestGenerator(), GitHubActionsManifestRenderer())
        """.trimIndent())
        listOf(
            "org.flowlang.targets.builtin.JenkinsManifestRenderer",
            "org.flowlang.targets.builtin.TektonManifestRenderer",
            "org.flowlang.distribution.reference.ReferenceTargetProjections",
            "org.flowlang.conformance.ConformanceRunner"
        ).forEach { type -> ExternalCompilerProbe.rejects("fun forbidden(value: $type) = value", "Unresolved reference") }
    }
}
