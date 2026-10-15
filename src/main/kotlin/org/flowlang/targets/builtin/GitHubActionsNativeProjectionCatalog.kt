package org.flowlang.targets.builtin

import org.flowlang.generators.manifest.TargetNativeProjectionBindingContract
import org.flowlang.generators.manifest.TargetNativeProjectionCatalog
import org.flowlang.generators.manifest.TargetNativeProjectionDefinition
import org.flowlang.generators.manifest.TargetNativeStructuralProjectionDefinition
import org.flowlang.generators.manifest.TargetStructuralProjectionKind
import org.flowlang.generators.manifest.TargetStructuralProjectionSupportScope
import org.flowlang.projection.ProjectionBindingKind

object GitHubActionsNativeProjectionCatalog {
    val catalog: TargetNativeProjectionCatalog = TargetNativeProjectionCatalog.of(
        "github-actions",
        definitions = listOf(TargetNativeProjectionDefinition(
            kind = GitHubActionsProjectionPayloadKinds.GITHUB_ACTION,
            reference = "actions/checkout@v4",
            bindings = mapOf(
                "repository" to TargetNativeProjectionBindingContract(setOf(ProjectionBindingKind.TASK_PARAMETER)),
                "ref" to TargetNativeProjectionBindingContract(setOf(ProjectionBindingKind.TASK_PARAMETER)),
                "fetch-depth" to TargetNativeProjectionBindingContract(setOf(ProjectionBindingKind.TASK_PARAMETER), required = false)
            )
        ),
        TargetNativeProjectionDefinition(
            kind = GitHubActionsProjectionPayloadKinds.GITHUB_ACTION,
            reference = "docker/build-push-action@v7",
            bindings = imageBuildBindings()
        ),
        TargetNativeProjectionDefinition(
            kind = GitHubActionsProjectionPayloadKinds.GITHUB_ACTION,
            reference = GitHubActionsWorkspaceContinuityPlanner.UPLOAD_REFERENCE,
            bindings = mapOf(
                "name" to TargetNativeProjectionBindingContract(setOf(ProjectionBindingKind.ARTIFACT)),
                "path" to TargetNativeProjectionBindingContract(setOf(ProjectionBindingKind.LITERAL)),
                "if-no-files-found" to TargetNativeProjectionBindingContract(setOf(ProjectionBindingKind.LITERAL)),
                "include-hidden-files" to TargetNativeProjectionBindingContract(setOf(ProjectionBindingKind.LITERAL))
            )
        ),
        TargetNativeProjectionDefinition(
            kind = GitHubActionsProjectionPayloadKinds.GITHUB_ACTION,
            reference = GitHubActionsWorkspaceContinuityPlanner.DOWNLOAD_REFERENCE,
            bindings = mapOf(
                "name" to TargetNativeProjectionBindingContract(setOf(ProjectionBindingKind.ARTIFACT)),
                "path" to TargetNativeProjectionBindingContract(setOf(ProjectionBindingKind.LITERAL))
            )
        )),
        structuralDefinitions = listOf(TargetNativeStructuralProjectionDefinition(
            structure = TargetStructuralProjectionKind.CONDITION,
            kind = "github-job-condition",
            reference = "if",
            implementationEvidenceReference = "src/main/kotlin/org/flowlang/targets/builtin/GitHubJobConditionAuthority.kt#expression",
            behavioralEvidenceReference = "src/test/kotlin/SharedConditionRuntimeTests.kt#bothProvidersPreserveTheSharedLiteralGuard",
            supportScope = TargetStructuralProjectionSupportScope.PLAN_SCOPED
        ))
    )

    private fun imageBuildBindings(): Map<String, TargetNativeProjectionBindingContract> = mapOf(
        "image" to TargetNativeProjectionBindingContract(setOf(ProjectionBindingKind.TASK_PARAMETER)),
        "context" to TargetNativeProjectionBindingContract(setOf(ProjectionBindingKind.TASK_PARAMETER)),
        "dockerfile" to TargetNativeProjectionBindingContract(setOf(ProjectionBindingKind.TASK_PARAMETER), required = false),
        "push" to TargetNativeProjectionBindingContract(setOf(ProjectionBindingKind.TASK_PARAMETER))
    )
}
