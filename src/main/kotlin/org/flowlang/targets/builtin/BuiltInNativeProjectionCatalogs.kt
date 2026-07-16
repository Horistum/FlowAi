package org.flowlang.targets.builtin

import org.flowlang.generators.manifest.TargetNativeProjectionBindingContract
import org.flowlang.generators.manifest.TargetNativeProjectionCatalog
import org.flowlang.generators.manifest.TargetNativeProjectionDefinition
import org.flowlang.projection.ProjectionBindingKind

/**
 * Explicit native projection implementation evidence shipped by this
 * distribution. Every entry must correspond to concrete renderer behavior;
 * absent entries keep registry claims from becoming executable by optimism.
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
                ),
                "depth" to TargetNativeProjectionBindingContract(
                    acceptedKinds = setOf(ProjectionBindingKind.TASK_PARAMETER),
                    required = false
                )
            )
        ),
        TargetNativeProjectionDefinition(
            kind = BuiltInProjectionPayloadKinds.JENKINS_STEP,
            reference = "docker-build",
            bindings = imageBuildBindings()
        )
    )

    val githubActions: TargetNativeProjectionCatalog = TargetNativeProjectionCatalog.of(
        "github-actions",
        TargetNativeProjectionDefinition(
            kind = BuiltInProjectionPayloadKinds.GITHUB_ACTION,
            reference = "actions/checkout@v4",
            bindings = mapOf(
                "repository" to TargetNativeProjectionBindingContract(
                    acceptedKinds = setOf(ProjectionBindingKind.TASK_PARAMETER)
                ),
                "ref" to TargetNativeProjectionBindingContract(
                    acceptedKinds = setOf(ProjectionBindingKind.TASK_PARAMETER)
                ),
                "fetch-depth" to TargetNativeProjectionBindingContract(
                    acceptedKinds = setOf(ProjectionBindingKind.TASK_PARAMETER),
                    required = false
                )
            )
        ),
        TargetNativeProjectionDefinition(
            kind = BuiltInProjectionPayloadKinds.GITHUB_ACTION,
            reference = "docker/build-push-action@v7",
            bindings = imageBuildBindings()
        )
    )

    val tekton: TargetNativeProjectionCatalog = TargetNativeProjectionCatalog.of(
        "tekton",
        TargetNativeProjectionDefinition(
            kind = BuiltInProjectionPayloadKinds.TEKTON_TASK,
            reference = "git-clone",
            bindings = mapOf(
                "url" to TargetNativeProjectionBindingContract(
                    acceptedKinds = setOf(ProjectionBindingKind.TASK_PARAMETER)
                ),
                "revision" to TargetNativeProjectionBindingContract(
                    acceptedKinds = setOf(ProjectionBindingKind.TASK_PARAMETER)
                ),
                "depth" to TargetNativeProjectionBindingContract(
                    acceptedKinds = setOf(ProjectionBindingKind.TASK_PARAMETER),
                    required = false
                ),
                "workspace" to TargetNativeProjectionBindingContract(
                    acceptedKinds = setOf(ProjectionBindingKind.TASK_METADATA)
                )
            )
        ),
        TargetNativeProjectionDefinition(
            kind = BuiltInProjectionPayloadKinds.TEKTON_TASK,
            reference = "buildah",
            bindings = imageBuildBindings() + mapOf(
                "workspace" to TargetNativeProjectionBindingContract(
                    acceptedKinds = setOf(ProjectionBindingKind.LITERAL)
                )
            )
        )
    )

    private fun imageBuildBindings(): Map<String, TargetNativeProjectionBindingContract> = mapOf(
        "image" to TargetNativeProjectionBindingContract(
            acceptedKinds = setOf(ProjectionBindingKind.TASK_PARAMETER)
        ),
        "context" to TargetNativeProjectionBindingContract(
            acceptedKinds = setOf(ProjectionBindingKind.TASK_PARAMETER)
        ),
        "dockerfile" to TargetNativeProjectionBindingContract(
            acceptedKinds = setOf(ProjectionBindingKind.TASK_PARAMETER),
            required = false
        ),
        "push" to TargetNativeProjectionBindingContract(
            acceptedKinds = setOf(ProjectionBindingKind.TASK_PARAMETER)
        )
    )
}
