package org.flowlang.adapters.continuity

import org.flowlang.capabilities.SupportLevel
import org.flowlang.capabilities.TargetCapability
import org.flowlang.generators.manifest.TargetProjectionCapabilityResolver
import org.flowlang.planner.ExecutionPlan
import org.flowlang.topology.ExecutionTopologyKind
import org.flowlang.topology.ExecutionTopologyProfile
import org.flowlang.topology.ExecutionTopologySupportStatus

/**
 * Promotes only the exact A1.0 GitHub Actions workspace path for one concrete plan.
 *
 * The authored target registry intentionally remains UNSUPPORTED for generic
 * workspace continuity. A plan receives an effective SUPPORTED view only when
 * every workspace requirement matches the bounded producer/channel/consumer
 * declaration backed by the production artifact transfer implementation.
 */
class AdapterContinuityScopedCapabilityResolver(
    private val scopedSupports: List<AdapterContinuityScopedSupport> =
        BuiltInAdapterContinuityScopedSupport.declarations
) : TargetProjectionCapabilityResolver {
    override fun resolve(
        plan: ExecutionPlan,
        target: String,
        declared: TargetCapability
    ): TargetCapability {
        if (target != GITHUB_ACTIONS || declared.target != target) return declared
        val workspaceRequirements = AdapterContinuityRequirementAuthority.derive(plan)
            .filter { it.semantic == AdapterContinuitySemanticContract.ARTIFACT_SHARED_WORKSPACE }
        if (workspaceRequirements.isEmpty()) return declared
        val allMatched = workspaceRequirements.all { requirement ->
            AdapterContinuityScopedSupportAuthority.matchingSupport(
                plan = plan,
                target = target,
                requirement = requirement,
                declarations = scopedSupports
            ) != null
        }
        if (!allMatched) return declared

        val topology = requireNotNull(declared.topologyProfile) {
            "GitHub Actions scoped workspace promotion requires an authored topology profile."
        }
        val promotedTopology = topology.promoteWorkspacePropagation()
        return declared.copy(
            features = declared.features + (CONTINUITY_WORKSPACE to SupportLevel.SUPPORTED),
            topologyProfile = promotedTopology,
            notes = declared.notes + BOUNDED_NOTE
        )
    }

    private fun ExecutionTopologyProfile.promoteWorkspacePropagation(): ExecutionTopologyProfile {
        val matchingSupports = scopedSupports.filter { support ->
            support.target == GITHUB_ACTIONS &&
                support.semantic == AdapterContinuitySemanticContract.ARTIFACT_SHARED_WORKSPACE
        }
        require(matchingSupports.size == 1) {
            "GitHub Actions scoped workspace promotion requires exactly one bounded declaration."
        }
        val support = matchingSupports.single()
        val updatedTopologyDeclarations = declarations.map { topologyDeclaration ->
            if (topologyDeclaration.kind != ExecutionTopologyKind.WORKSPACE_PROPAGATION) {
                topologyDeclaration
            } else {
                topologyDeclaration.copy(
                    status = ExecutionTopologySupportStatus.SUPPORTED,
                    evidenceReference = support.evidenceReferences.first(),
                    detail = "A1.0 bounded artifact transfer supports ${support.sourceAction} to ${support.targetAction} on channel '${support.channel}'; no generic workspace claim is made."
                )
            }
        }
        require(updatedTopologyDeclarations.count { it.kind == ExecutionTopologyKind.WORKSPACE_PROPAGATION } == 1) {
            "GitHub Actions topology profile must declare workspacePropagation exactly once."
        }
        return copy(declarations = updatedTopologyDeclarations)
    }

    companion object {
        const val GITHUB_ACTIONS = "github-actions"
        const val CONTINUITY_WORKSPACE = "continuity.workspace"
        const val BOUNDED_NOTE =
            "A1.0 promotes workspace continuity only for the declared git.checkout/source to docker.build/source path through workflow artifact transfer."
    }
}
