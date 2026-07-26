package org.flowlang.artifacts

import org.flowlang.capabilities.TargetCapability
import org.flowlang.capabilities.TargetExpressionSupport
import org.flowlang.generators.manifest.TargetNativeProjectionCatalog
import org.flowlang.projection.ProjectionBindingKind
import org.flowlang.targets.builtin.BuiltInNativeProjectionCatalogs

/**
 * Builds the public target-semantics matrix exclusively from registry capability
 * evidence and provider-owned native projection contracts.
 *
 * Features without provider evidence are reported as review-only rather than
 * receiving a target-name-specific mechanism description.
 */
object TargetSemanticsAuthority {
    private val referenceConditions = listOf(
        "env == 'prod'",
        "stage != 'dev'",
        "count > 1",
        "name matches '^prod-'",
        "region in ['eu', 'us']"
    )

    fun build(
        targets: Map<String, TargetCapability>,
        nativeCatalogs: Map<String, TargetNativeProjectionCatalog> = BuiltInNativeProjectionCatalogs.byTarget
    ): TargetSemanticsMatrixReport {
        val targetIds = targets.keys.sorted()
        val entries = listOf(
            entry(
                feature = "conditions",
                targetIds = targetIds,
                diagnostic = "condition.expression"
            ) { target -> conditionSupport(targets.getValue(target)) },
            entry(
                feature = "approvals",
                targetIds = targetIds,
                diagnostic = "approval.strict"
            ) { target -> approvalSupport(nativeCatalogs[target]) },
            entry(
                feature = "manual-gates",
                targetIds = targetIds,
                diagnostic = "approval.strict"
            ) { target -> approvalSupport(nativeCatalogs[target]) },
            entry(
                feature = "strict-manual-approval",
                targetIds = targetIds,
                diagnostic = "approval.strict"
            ) { target -> approvalSupport(nativeCatalogs[target]) },
            entry(
                feature = "secrets",
                targetIds = targetIds,
                diagnostic = "secret.binding"
            ) { target -> bindingSupport(nativeCatalogs[target], setOf(ProjectionBindingKind.SECRET)) },
            entry(
                feature = "artifacts",
                targetIds = targetIds,
                diagnostic = "artifact.transport"
            ) { target -> bindingSupport(
                nativeCatalogs[target],
                setOf(ProjectionBindingKind.ARTIFACT, ProjectionBindingKind.TASK_OUTPUT)
            ) }
        )
        return TargetSemanticsMatrixReport(
            status = StandardSurfaceStatusAuthority.targetSemanticsMatrix(targetIds, entries),
            targetIds = targetIds,
            entries = entries,
            portabilityRule = "Only registry and provider-owned projection evidence may create a positive target feature claim; absent evidence remains adapter-required review-only."
        )
    }

    private fun conditionSupport(target: TargetCapability): String {
        val unsupported = referenceConditions.count { condition ->
            TargetExpressionSupport.unsupportedReason(target, condition) != null
        }
        return when {
            unsupported == 0 -> "native"
            unsupported < referenceConditions.size -> "partial"
            else -> "unsupported-blocked"
        }
    }

    private fun approvalSupport(catalog: TargetNativeProjectionCatalog?): String = when {
        catalog == null -> "not-declared-review-only"
        catalog.approvalDefinitions.any { it.capability == "approval.manual" } -> "native"
        else -> "adapter-required-review-only"
    }

    private fun bindingSupport(
        catalog: TargetNativeProjectionCatalog?,
        bindingKinds: Set<ProjectionBindingKind>
    ): String = when {
        catalog == null -> "not-declared-review-only"
        catalog.definitions.any { definition ->
            definition.bindings.values.any { contract ->
                contract.acceptedKinds.any { it in bindingKinds }
            }
        } -> "native"
        else -> "adapter-required-review-only"
    }

    private fun entry(
        feature: String,
        targetIds: List<String>,
        diagnostic: String,
        support: (String) -> String
    ): TargetSemanticsEntry = TargetSemanticsEntry(
        feature = feature,
        semanticsByTarget = targetIds.associateWith(support),
        requiredDiagnosticWhenUnsupported = diagnostic
    )
}
