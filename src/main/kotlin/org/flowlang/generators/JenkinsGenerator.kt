package org.flowlang.generators

import org.flowlang.ast.FlowDocument
import org.flowlang.capabilities.CompatibilityReport
import org.flowlang.capabilities.SupportLevel
import org.flowlang.generators.manifest.JenkinsManifestGenerator
import org.flowlang.generators.manifest.JenkinsManifestRenderer
import org.flowlang.modules.ModuleRegistry
import org.flowlang.planner.FlowPlanner

/**
 * Backward-compatible facade for callers that still use the legacy AST-direct
 * generator entry point.
 *
 * The implementation delegates to the canonical FlowPlanner -> TargetManifest ->
 * Jenkins renderer path so Flow no longer has two independent Jenkins generation
 * systems with different semantics.
 */
@Deprecated("Use FlowPlanner plus JenkinsManifestGenerator/JenkinsManifestRenderer directly.")
class JenkinsGenerator {
    fun generate(document: FlowDocument, registry: ModuleRegistry = ModuleRegistry()): String {
        val plan = FlowPlanner(registry).plan(document)
        val manifest = JenkinsManifestGenerator().generate(
            plan,
            CompatibilityReport(target = "jenkins", status = SupportLevel.SUPPORTED)
        )
        return JenkinsManifestRenderer().render(manifest)
    }
}
