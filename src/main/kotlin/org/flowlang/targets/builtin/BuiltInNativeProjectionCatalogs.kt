package org.flowlang.targets.builtin

import org.flowlang.generators.manifest.TargetNativeProjectionBindingContract
import org.flowlang.generators.manifest.TargetNativeProjectionCatalog
import org.flowlang.generators.manifest.TargetNativeProjectionDefinition
import org.flowlang.projection.ProjectionBindingKind

/**
 * Explicit native projection implementation evidence shipped by this
 * distribution. Empty catalogs are intentional and prevent registry metadata
 * from claiming executable behavior before an edge implementation exists.
 */
object BuiltInNativeProjectionCatalogs {
    val jenkins: TargetNativeProjectionCatalog = TargetNativeProjectionCatalog.of(
        "jenkins",
        TargetNativeProjectionDefinition(
            kind = BuiltInProjectionPayloadKinds.JENKINS_STEP,
            reference = "git",
            bindings = mapOf(
                "url" to TargetNativeProjectionBindingContract(
                    acceptedKinds = setOf(ProjectionBindingKind.TASK_PARAMETER)
                ),
                "branch" to TargetNativeProjectionBindingContract(
                    acceptedKinds = setOf(ProjectionBindingKind.TASK_PARAMETER)
                )
            )
        )
    )

    val githubActions: TargetNativeProjectionCatalog =
        TargetNativeProjectionCatalog.empty("github-actions")

    val tekton: TargetNativeProjectionCatalog =
        TargetNativeProjectionCatalog.empty("tekton")
}
