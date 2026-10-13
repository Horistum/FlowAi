package org.flowlang.targets.builtin

import org.flowlang.generators.manifest.TargetApprovalProjectionField
import org.flowlang.generators.manifest.TargetNativeApprovalProjectionBindingContract
import org.flowlang.generators.manifest.TargetNativeApprovalProjectionDefinition
import org.flowlang.generators.manifest.TargetNativeProjectionBindingContract
import org.flowlang.generators.manifest.TargetNativeProjectionCatalog
import org.flowlang.generators.manifest.TargetNativeProjectionDefinition
import org.flowlang.generators.manifest.TargetNativeStructuralProjectionDefinition
import org.flowlang.generators.manifest.TargetStructuralProjectionKind
import org.flowlang.generators.manifest.TargetStructuralProjectionSupportScope
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
                structure = TargetStructuralProjectionKind.PARALLEL,
                kind = JenkinsProjectionPayloadKinds.JENKINS_STRUCTURE,
                reference = "approval-parallel",
                implementationEvidenceReference = "src/main/kotlin/org/flowlang/targets/builtin/JenkinsManifestRenderer.kt#renderJenkinsParallel",
                behavioralEvidenceReference = "src/test/kotlin/JenkinsParallelRuntimeCertificationTests.kt#parallelGatesPreserveConcurrentPendingJoinAndRejection",
                supportScope = TargetStructuralProjectionSupportScope.PLAN_SCOPED
            ),
            TargetNativeStructuralProjectionDefinition(
                structure = TargetStructuralProjectionKind.MATCH,
                kind = JenkinsProjectionPayloadKinds.JENKINS_STRUCTURE,
                reference = "literal-match",
                implementationEvidenceReference = "src/main/kotlin/org/flowlang/targets/builtin/JenkinsManifestRenderer.kt#renderJenkinsMatch",
                behavioralEvidenceReference = "src/test/kotlin/JenkinsMatchRuntimeCertificationTests.kt#literalMatchPreservesSelectedDefaultEmptyAndFailure",
                supportScope = TargetStructuralProjectionSupportScope.PLAN_SCOPED
            ),
            TargetNativeStructuralProjectionDefinition(
                structure = TargetStructuralProjectionKind.LOOP,
                kind = JenkinsProjectionPayloadKinds.JENKINS_STRUCTURE,
                reference = "for-in",
                implementationEvidenceReference = "src/main/kotlin/org/flowlang/targets/builtin/JenkinsManifestRenderer.kt#renderJenkinsLoop",
                behavioralEvidenceReference = "src/test/kotlin/JenkinsLoopRuntimeCertificationTests.kt#literalLoopPreservesOrderEmptyInputAndFailure",
                supportScope = TargetStructuralProjectionSupportScope.PLAN_SCOPED
            ),
            TargetNativeStructuralProjectionDefinition(
                structure = TargetStructuralProjectionKind.RETRY,
                kind = JenkinsProjectionPayloadKinds.JENKINS_STRUCTURE,
                reference = "retry",
                implementationEvidenceReference = "src/main/kotlin/org/flowlang/targets/builtin/JenkinsManifestRenderer.kt#renderJenkinsRetry",
                behavioralEvidenceReference = "src/test/kotlin/JenkinsRetryRuntimeCertificationTests.kt#boundedRetryPreservesAttemptLimitAndEarlySuccess",
                supportScope = TargetStructuralProjectionSupportScope.PLAN_SCOPED
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
