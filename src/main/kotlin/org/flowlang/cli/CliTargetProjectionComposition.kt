package org.flowlang.cli

import java.io.File
import org.flowlang.adapters.yaml.TargetRegistryYamlLoader
import org.flowlang.capabilities.CompatibilityReport
import org.flowlang.generators.manifest.TargetManifest
import org.flowlang.planner.ExecutionPlan
import org.flowlang.targets.builtin.BuiltInTargetProjections

/**
 * CLI composition facade. It keeps built-in projection ownership outside Core
 * while preserving the existing command presentation and artifact headings.
 */
internal object TargetManifestGenerationPipeline {
    private val targets by lazy {
        TargetRegistryYamlLoader.loadDirectory(File("targets")).also {
            require(it.isNotEmpty()) { "No target registry found under targets." }
        }
    }
    private val pipeline by lazy {
        BuiltInTargetProjections.pipeline(targets)
    }

    fun generate(
        plan: ExecutionPlan,
        compatibility: CompatibilityReport,
        strict: Boolean = false
    ): TargetManifest = pipeline.generate(plan, compatibility.target, strict)
}

internal typealias JenkinsManifestRenderer = org.flowlang.targets.builtin.JenkinsManifestRenderer
internal typealias GitHubActionsManifestRenderer = org.flowlang.targets.builtin.GitHubActionsManifestRenderer
internal typealias TektonManifestRenderer = org.flowlang.targets.builtin.TektonManifestRenderer
