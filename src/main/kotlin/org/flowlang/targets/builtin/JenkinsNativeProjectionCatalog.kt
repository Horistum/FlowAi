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

object JenkinsNativeProjectionCatalog {
    val catalog: TargetNativeProjectionCatalog = TargetNativeProjectionCatalog.of(
        target = "jenkins",
        definitions = listOf(
            TargetNativeProjectionDefinition(
                kind = JenkinsProjectionPayloadKinds.JENKINS_STEP,
                reference = "git",
                bindings = mapOf(
                    "url" to TargetNativeProjectionBindingContract(setOf(ProjectionBindingKind.TASK_PARAMETER)),
                    "branch" to TargetNativeProjectionBindingContract(setOf(ProjectionBindingKind.TASK_PARAMETER)),
                    "depth" to TargetNativeProjectionBindingContract(setOf(ProjectionBindingKind.TASK_PARAMETER), required = false)
                )
            ),
            TargetNativeProjectionDefinition(
                kind = JenkinsProjectionPayloadKinds.JENKINS_STEP,
                reference = "docker-build",
                bindings = imageBuildBindings()
            )
        ),
        approvalDefinitions = listOf(
            TargetNativeApprovalProjectionDefinition(
                capability = "approval.manual",
                kind = JenkinsProjectionPayloadKinds.JENKINS_STEP,
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
                structure = TargetStructuralProjectionKind.RETRY,
                kind = JenkinsProjectionPayloadKinds.JENKINS_STRUCTURE,
                reference = "retry",
                implementationEvidenceReference = "src/main/kotlin/org/flowlang/targets/builtin/JenkinsManifestRenderer.kt#renderJenkinsRetry",
                behavioralEvidenceReference = "src/test/kotlin/JenkinsRetryRuntimeCertificationTests.kt#boundedRetryPreservesAttemptLimitAndEarlySuccess"
            ),
            TargetNativeStructuralProjectionDefinition(
                structure = TargetStructuralProjectionKind.CONDITION,
                kind = JenkinsProjectionPayloadKinds.JENKINS_STRUCTURE,
                reference = "if",
                implementationEvidenceReference = "src/main/kotlin/org/flowlang/targets/builtin/JenkinsManifestRenderer.kt#condition",
                behavioralEvidenceReference = "src/test/kotlin/TargetStructuralProjectionHonestyTests.kt#jenkinsConditionPreservesGuardedExecution"
            ),
            TargetNativeStructuralProjectionDefinition(
                structure = TargetStructuralProjectionKind.ERROR_BOUNDARY,
                kind = JenkinsProjectionPayloadKinds.JENKINS_STRUCTURE,
                reference = "try-catch",
                implementationEvidenceReference = "src/main/kotlin/org/flowlang/targets/builtin/JenkinsManifestRenderer.kt#error-boundary",
                behavioralEvidenceReference = "src/test/kotlin/TargetStructuralProjectionHonestyTests.kt#jenkinsErrorBoundaryPreservesHandlerExecution"
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
