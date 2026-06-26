package org.flowlang.generators

import org.flowlang.capabilities.CompatibilityReport
import org.flowlang.capabilities.SupportLevel
import org.flowlang.generators.manifest.GitHubActionsManifestGenerator
import org.flowlang.generators.manifest.GitHubActionsManifestRenderer
import org.flowlang.planner.ExecutionPlan

/**
 * Backward-compatible facade for callers that still use the legacy GitHub Actions
 * generator entry point.
 *
 * The implementation delegates to the canonical TargetManifest renderer path so
 * Flow no longer maintains a second, weaker GitHub Actions generation system.
 */
@Deprecated("Use GitHubActionsManifestGenerator/GitHubActionsManifestRenderer directly.")
class GitHubActionsGenerator {
    fun generate(plan: ExecutionPlan): String {
        val manifest = GitHubActionsManifestGenerator().generate(
            plan,
            CompatibilityReport(target = "github-actions", status = SupportLevel.PARTIAL)
        )
        return GitHubActionsManifestRenderer().render(manifest)
    }
}
