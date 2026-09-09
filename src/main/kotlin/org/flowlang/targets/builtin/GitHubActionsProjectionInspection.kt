package org.flowlang.targets.builtin

import org.flowlang.generators.manifest.TargetJob
import org.flowlang.generators.manifest.TargetManifest

/** Read-only projections for independent conformance; neither method authorizes executable generation. */
object GitHubActionsProjectionInspection {
    fun jobCondition(job: TargetJob, manifest: TargetManifest): String? =
        GitHubJobConditionAuthority.expression(job, manifest)

    fun triggerDocument(manifest: TargetManifest): String =
        GitHubActionsTriggerProjectionPlanner.render(manifest)
}
