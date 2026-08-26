package org.flowlang.targets.builtin

import org.flowlang.generators.manifest.TargetApprovalProjectionField
import org.flowlang.generators.manifest.TargetNativeApprovalProjectionBindingContract
import org.flowlang.generators.manifest.TargetNativeApprovalProjectionDefinition
import org.flowlang.generators.manifest.TargetNativeProjectionBindingContract
import org.flowlang.generators.manifest.TargetNativeProjectionCatalog
import org.flowlang.generators.manifest.TargetNativeProjectionDefinition
import org.flowlang.generators.manifest.TargetNativeStructuralProjectionDefinition
import org.flowlang.generators.manifest.TargetStructuralProjectionKind
import org.flowlang.projection.ProjectionBindingKind

/**
 * Explicit native projection implementation evidence shipped by this distribution.
 * Every entry must correspond to concrete renderer behavior; absent entries keep
 * registry capability claims from becoming executable by optimism.
 */
object BuiltInNativeProjectionCatalogs {
    val jenkins: TargetNativeProjectionCatalog = TargetNativeProjectionCatalog.of(
        target = "jenkins",
        definitions = listOf(
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
        ),
        approvalDefinitions = listOf(
            TargetNativeApprovalProjectionDefinition(
                capability = "approval.manual",
                kind = BuiltInProjectionPayloadKinds.JENKINS_STEP,
                reference = "input",
                evidenceReference = "src/main/kotlin/org/flowlang/targets/builtin/JenkinsManifestRenderer.kt#approval.manual.input",
                bindings = mapOf(
                    "mode" to TargetNativeApprovalProjectionBindingContract(TargetApprovalProjectionField.MODE),
                    "message" to TargetNativeApprovalProjectionBindingContract(TargetApprovalProjectionField.MESSAGE)
                )
            )
        ),
        structuralDefinitions = listOf(
            TargetNativeStructuralProjectionDefinition(
                structure = TargetStructuralProjectionKind.CONDITION,
                kind = BuiltInProjectionPayloadKinds.JENKINS_STRUCTURE,
                reference = "if",
                implementationEvidenceReference =
                    "src/main/kotlin/org/flowlang/targets/builtin/JenkinsManifestRenderer.kt#condition",
                behavioralEvidenceReference =
                    "src/test/kotlin/TargetStructuralProjectionHonestyTests.kt#jenkinsConditionPreservesGuardedExecution"
            ),
            TargetNativeStructuralProjectionDefinition(
                structure = TargetStructuralProjectionKind.ERROR_BOUNDARY,
                kind = BuiltInProjectionPayloadKinds.JENKINS_STRUCTURE,
                reference = "try-catch",
                implementationEvidenceReference =
                    "src/main/kotlin/org/flowlang/targets/builtin/JenkinsManifestRenderer.kt#error-boundary",
                behavioralEvidenceReference =
                    "src/test/kotlin/TargetStructuralProjectionHonestyTests.kt#jenkinsErrorBoundaryPreservesHandlerExecution"
            )
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
        ),
        TargetNativeProjectionDefinition(
            kind = BuiltInProjectionPayloadKinds.GITHUB_ACTION,
            reference = GitHubActionsWorkspaceContinuityPlanner.UPLOAD_REFERENCE,
            bindings = mapOf(
                "name" to TargetNativeProjectionBindingContract(
                    acceptedKinds = setOf(ProjectionBindingKind.ARTIFACT)
                ),
                "path" to TargetNativeProjectionBindingContract(
                    acceptedKinds = setOf(ProjectionBindingKind.LITERAL)
                ),
                "if-no-files-found" to TargetNativeProjectionBindingContract(
                    acceptedKinds = setOf(ProjectionBindingKind.LITERAL)
                ),
                "include-hidden-files" to TargetNativeProjectionBindingContract(
                    acceptedKinds = setOf(ProjectionBindingKind.LITERAL)
                )
            )
        ),
        TargetNativeProjectionDefinition(
            kind = BuiltInProjectionPayloadKinds.GITHUB_ACTION,
            reference = GitHubActionsWorkspaceContinuityPlanner.DOWNLOAD_REFERENCE,
            bindings = mapOf(
                "name" to TargetNativeProjectionBindingContract(
                    acceptedKinds = setOf(ProjectionBindingKind.ARTIFACT)
                ),
                "path" to TargetNativeProjectionBindingContract(
                    acceptedKinds = setOf(ProjectionBindingKind.LITERAL)
                )
            )
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

    val byTarget: Map<String, TargetNativeProjectionCatalog> = listOf(
        jenkins,
        githubActions,
        tekton
    ).associateBy(TargetNativeProjectionCatalog::target)

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
