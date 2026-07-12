package org.flowlang.generators

import org.flowlang.ast.FlowDocument
import org.flowlang.modules.ModuleRegistry
import org.flowlang.planner.ExecutionPlan

/**
 * Compatibility tombstone for the removed legacy Jenkins test generator.
 *
 * The former implementation encoded Flow actions as Jenkins steps and generic
 * runtime command strings. Keeping that implementation in the test source set
 * made the obsolete projection path look supported and allowed tests to protect
 * behavior that Flow Core explicitly prohibits.
 *
 * Callers must use JenkinsManifestGenerator and JenkinsManifestRenderer through
 * the target manifest, materialization and renderer-readiness contracts.
 */
@Deprecated(
    message = "Legacy Jenkins test generation was removed in v0.9.5.7.4. Use the target manifest pipeline.",
    level = DeprecationLevel.WARNING
)
class JenkinsGenerator {
    fun generate(document: FlowDocument, registry: ModuleRegistry = ModuleRegistry()): String {
        document.hashCode()
        registry.hashCode()
        return removed()
    }

    fun generate(plan: ExecutionPlan): String {
        plan.hashCode()
        return removed()
    }

    private fun removed(): Nothing = throw UnsupportedOperationException(
        "Legacy Jenkins test generation is removed. Generate a TargetManifest and evaluate TargetRenderPolicy before rendering target syntax."
    )
}
