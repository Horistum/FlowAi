package org.flowlang.targets.builtin

import org.flowlang.generators.manifest.TargetNativeProjectionBindingContract
import org.flowlang.generators.manifest.TargetNativeProjectionCatalog
import org.flowlang.generators.manifest.TargetNativeProjectionDefinition
import org.flowlang.projection.ProjectionBindingKind

object TektonNativeProjectionCatalog {
    val catalog: TargetNativeProjectionCatalog = TargetNativeProjectionCatalog.of(
        "tekton",
        TargetNativeProjectionDefinition(
            kind = TektonProjectionPayloadKinds.TEKTON_TASK,
            reference = "git-clone",
            bindings = mapOf(
                "url" to TargetNativeProjectionBindingContract(setOf(ProjectionBindingKind.TASK_PARAMETER)),
                "revision" to TargetNativeProjectionBindingContract(setOf(ProjectionBindingKind.TASK_PARAMETER)),
                "depth" to TargetNativeProjectionBindingContract(setOf(ProjectionBindingKind.TASK_PARAMETER), required = false),
                "workspace" to TargetNativeProjectionBindingContract(setOf(ProjectionBindingKind.TASK_METADATA))
            )
        ),
        TargetNativeProjectionDefinition(
            kind = TektonProjectionPayloadKinds.TEKTON_TASK,
            reference = "buildah",
            bindings = imageBuildBindings() + mapOf(
                "workspace" to TargetNativeProjectionBindingContract(setOf(ProjectionBindingKind.LITERAL))
            )
        )
    )

    private fun imageBuildBindings(): Map<String, TargetNativeProjectionBindingContract> = mapOf(
        "image" to TargetNativeProjectionBindingContract(setOf(ProjectionBindingKind.TASK_PARAMETER)),
        "context" to TargetNativeProjectionBindingContract(setOf(ProjectionBindingKind.TASK_PARAMETER)),
        "dockerfile" to TargetNativeProjectionBindingContract(setOf(ProjectionBindingKind.TASK_PARAMETER), required = false),
        "push" to TargetNativeProjectionBindingContract(setOf(ProjectionBindingKind.TASK_PARAMETER))
    )
}
