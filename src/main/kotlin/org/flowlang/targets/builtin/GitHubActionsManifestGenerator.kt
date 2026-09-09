package org.flowlang.targets.builtin

import org.flowlang.compiler.requireSingleWorkflowFailureProjection
import org.flowlang.generators.manifest.AdapterManifestLowering
import org.flowlang.generators.manifest.AdapterWorkflowProjectionLowering
import org.flowlang.generators.manifest.ReconciledTargetManifestGenerator
import org.flowlang.generators.manifest.TargetJob
import org.flowlang.generators.manifest.TargetManifest
import org.flowlang.generators.manifest.TargetNativeProjectionCatalog
import org.flowlang.generators.manifest.TargetProjectionAuthorization

class GitHubActionsManifestGenerator(
    override val nativeProjectionCatalog: TargetNativeProjectionCatalog = GitHubActionsNativeProjectionCatalog.catalog
) : ReconciledTargetManifestGenerator() {
    override val target: String = "github-actions"

    override fun buildManifest(authorization: TargetProjectionAuthorization): TargetManifest {
        val plan = authorization.plan
        val compatibility = authorization.compatibility
        val jobs = AdapterWorkflowProjectionLowering.jobPerTask(
            authorization.compilationAuthorization.requireSingleWorkflowFailureProjection(),
            authorization.compilationAuthorization,
            target,
            compatibility.projectionRules,
            nativeProjectionCatalog
        )
        val baseJobs = jobs.ifEmpty {
            listOf(TargetJob(
                id = AdapterManifestLowering.id(plan.flowName),
                name = plan.flowName,
                steps = listOf(AdapterManifestLowering.emptyStep(plan.flowName))
            ))
        }
        val materializedJobs = GitHubActionsWorkspaceContinuityPlanner.materialize(plan, baseJobs)
        return TargetManifest(
            target = target,
            flowName = plan.flowName,
            compatibility = compatibility,
            inputs = plan.inputs.map(AdapterManifestLowering::input),
            triggers = plan.triggers.map(AdapterManifestLowering::trigger),
            jobs = materializedJobs,
            mappingNotes = AdapterManifestLowering.mappingNotes(compatibility, target),
            metadata = AdapterManifestLowering.metadata(plan, "GitHubActionsManifestGenerator") + mapOf(
                "jobPerTask" to "true",
                "workspaceContinuityMechanism" to "workflow-artifact-transfer",
                "workspaceContinuityTransferCount" to GitHubActionsWorkspaceContinuityPlanner.transferCount(plan).toString()
            )
        )
    }
}
