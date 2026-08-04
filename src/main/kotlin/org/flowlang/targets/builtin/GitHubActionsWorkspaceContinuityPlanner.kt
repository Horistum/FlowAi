package org.flowlang.targets.builtin

import org.flowlang.adapters.continuity.AdapterContinuityFamily
import org.flowlang.adapters.continuity.AdapterContinuityRequirement
import org.flowlang.adapters.continuity.AdapterContinuityRequirementAuthority
import org.flowlang.adapters.continuity.AdapterContinuityScopedSupport
import org.flowlang.adapters.continuity.AdapterContinuityScopedSupportAuthority
import org.flowlang.adapters.continuity.BuiltInAdapterContinuityScopedSupport
import org.flowlang.generators.manifest.TargetJob
import org.flowlang.generators.manifest.TargetMaterialization
import org.flowlang.generators.manifest.TargetRendererPayload
import org.flowlang.generators.manifest.TargetStep
import org.flowlang.generators.manifest.sanitizeId
import org.flowlang.planner.ExecutionPlan
import org.flowlang.projection.ProjectionBinding
import org.flowlang.projection.ProjectionBindingResolutionStatus

/**
 * Materializes the bounded GitHub Actions workspace bridge declared by A1.0.
 *
 * Jobs remain isolated. A resolved WORKSPACE relation is represented by one
 * producer upload and one consumer download under a deterministic artifact
 * identity. `needs` remains ordering evidence only and is never accepted as the
 * transfer mechanism.
 */
object GitHubActionsWorkspaceContinuityPlanner {
    const val TARGET = "github-actions"
    const val UPLOAD_REFERENCE = "actions/upload-artifact@v7"
    const val DOWNLOAD_REFERENCE = "actions/download-artifact@v8"
    const val WORKSPACE_PATH = "."
    const val PAYLOAD_KIND = BuiltInProjectionPayloadKinds.GITHUB_ACTION

    private val supportedScope: AdapterContinuityScopedSupport =
        BuiltInAdapterContinuityScopedSupport.githubActionsCheckoutBuildWorkspace

    fun materialize(plan: ExecutionPlan, jobs: List<TargetJob>): List<TargetJob> {
        val requirements = AdapterContinuityRequirementAuthority.derive(plan)
            .filter { it.family == AdapterContinuityFamily.ARTIFACT }
        if (requirements.isEmpty()) return jobs

        val jobsById = jobs.associateBy(TargetJob::id)
        require(jobsById.size == jobs.size) { "GitHub Actions manifest contains duplicate job ids." }

        val transfers = requirements.map { requirement ->
            val matched = AdapterContinuityScopedSupportAuthority.matchingSupport(
                plan = plan,
                target = TARGET,
                requirement = requirement,
                declarations = listOf(supportedScope)
            ) ?: throw UnsupportedGitHubActionsWorkspaceContinuityException(requirement)
            require(matched == supportedScope) {
                "GitHub Actions workspace planner consumed an unexpected scoped support declaration '${matched.identity}'."
            }
            val sourceJobId = sanitizeId(requireNotNull(requirement.sourceNodeId) {
                "Resolved GitHub Actions workspace requirement '${requirement.id}' has no producer."
            })
            val targetJobId = sanitizeId(requirement.targetNodeId)
            require(sourceJobId in jobsById) {
                "GitHub Actions workspace producer job '$sourceJobId' is missing for requirement '${requirement.id}'."
            }
            val targetJob = requireNotNull(jobsById[targetJobId]) {
                "GitHub Actions workspace consumer job '$targetJobId' is missing for requirement '${requirement.id}'."
            }
            require(sourceJobId in targetJob.dependsOn) {
                "GitHub Actions workspace consumer '$targetJobId' must retain ordering dependency '$sourceJobId'; continuity does not create ordering."
            }
            WorkspaceTransfer(
                requirement = requirement,
                sourceJobId = sourceJobId,
                targetJobId = targetJobId,
                artifactName = artifactIdentity(requirement)
            )
        }

        val duplicateConsumers = transfers.groupBy { it.targetJobId to it.artifactName }
            .filterValues { it.size > 1 }
        require(duplicateConsumers.isEmpty()) {
            "GitHub Actions workspace continuity contains duplicate consumer transfers: " +
                duplicateConsumers.keys.joinToString { "${it.first}:${it.second}" }
        }

        val uploadsBySource = transfers.groupBy(WorkspaceTransfer::sourceJobId).mapValues { (_, sourceTransfers) ->
            sourceTransfers.groupBy(WorkspaceTransfer::artifactName).map { (artifactName, group) ->
                require(group.map { it.requirement.channel }.distinct().size == 1) {
                    "GitHub Actions artifact '$artifactName' combines different workspace channels."
                }
                uploadStep(group.first())
            }.sortedBy(TargetStep::id)
        }
        val downloadsByTarget = transfers.groupBy(WorkspaceTransfer::targetJobId).mapValues { (_, targetTransfers) ->
            targetTransfers.map(::downloadStep).sortedBy(TargetStep::id)
        }

        return jobs.map { job ->
            val downloads = downloadsByTarget[job.id].orEmpty()
            val uploads = uploadsBySource[job.id].orEmpty()
            job.copy(
                steps = downloads + job.steps + uploads,
                metadata = job.metadata + mapOf(
                    "githubActionsWorkspaceDownloadCount" to downloads.size.toString(),
                    "githubActionsWorkspaceUploadCount" to uploads.size.toString()
                )
            )
        }
    }

    fun transferCount(plan: ExecutionPlan): Int = AdapterContinuityRequirementAuthority.derive(plan)
        .count { requirement ->
            requirement.family == AdapterContinuityFamily.ARTIFACT &&
                AdapterContinuityScopedSupportAuthority.matchingSupport(
                    plan = plan,
                    target = TARGET,
                    requirement = requirement,
                    declarations = listOf(supportedScope)
                ) != null
        }

    private fun uploadStep(transfer: WorkspaceTransfer): TargetStep = TargetStep(
        id = sanitizeId("continuity-upload-${transfer.artifactName}"),
        name = "Upload Flow workspace ${transfer.requirement.channel}",
        type = "adapter-continuity-upload",
        materialization = TargetMaterialization.native(
            capability = "continuity.workspace",
            reason = "GitHub Actions uploads the resolved Flow workspace after producer '${transfer.sourceJobId}'.",
            metadata = transfer.metadata()
        ),
        rendererPayload = TargetRendererPayload(
            kind = PAYLOAD_KIND,
            target = TARGET,
            reference = UPLOAD_REFERENCE,
            bindings = mapOf(
                "name" to artifactBinding(transfer.artifactName),
                "path" to literalBinding(WORKSPACE_PATH),
                "if-no-files-found" to literalBinding("error"),
                "include-hidden-files" to literalBinding("true")
            ),
            evidenceReference = "src/main/kotlin/org/flowlang/targets/builtin/GitHubActionsWorkspaceContinuityPlanner.kt#upload"
        ),
        metadata = transfer.metadata() + ("continuityRole" to "producer-upload")
    )

    private fun downloadStep(transfer: WorkspaceTransfer): TargetStep = TargetStep(
        id = sanitizeId("continuity-download-${transfer.artifactName}-${transfer.targetJobId}"),
        name = "Restore Flow workspace ${transfer.requirement.channel}",
        type = "adapter-continuity-download",
        materialization = TargetMaterialization.native(
            capability = "continuity.workspace",
            reason = "GitHub Actions restores the resolved Flow workspace before consumer '${transfer.targetJobId}'.",
            metadata = transfer.metadata()
        ),
        rendererPayload = TargetRendererPayload(
            kind = PAYLOAD_KIND,
            target = TARGET,
            reference = DOWNLOAD_REFERENCE,
            bindings = mapOf(
                "name" to artifactBinding(transfer.artifactName),
                "path" to literalBinding(WORKSPACE_PATH)
            ),
            evidenceReference = "src/main/kotlin/org/flowlang/targets/builtin/GitHubActionsWorkspaceContinuityPlanner.kt#download"
        ),
        metadata = transfer.metadata() + ("continuityRole" to "consumer-download")
    )

    private fun artifactIdentity(requirement: AdapterContinuityRequirement): String = sanitizeId(
        listOf(
            "flow-workspace",
            requireNotNull(requirement.sourceNodeId),
            requirement.channel ?: "default"
        ).joinToString("-")
    )

    private fun artifactBinding(name: String): ProjectionBinding = ProjectionBinding.artifact(name).copy(
        resolutionStatus = ProjectionBindingResolutionStatus.SYMBOLIC
    )

    private fun literalBinding(value: String): ProjectionBinding = ProjectionBinding.literal(value).copy(
        resolutionStatus = ProjectionBindingResolutionStatus.RESOLVED
    )

    private data class WorkspaceTransfer(
        val requirement: AdapterContinuityRequirement,
        val sourceJobId: String,
        val targetJobId: String,
        val artifactName: String
    ) {
        fun metadata(): Map<String, String> = mapOf(
            "continuityRequirementId" to requirement.id,
            "continuitySourceJob" to sourceJobId,
            "continuityTargetJob" to targetJobId,
            "continuityChannel" to requireNotNull(requirement.channel),
            "continuityArtifactIdentity" to artifactName
        )
    }
}

class UnsupportedGitHubActionsWorkspaceContinuityException(
    val requirement: AdapterContinuityRequirement
) : IllegalStateException(
    "GitHub Actions has no bounded workspace transfer implementation for requirement '${requirement.id}' " +
        "from '${requirement.sourceNodeId ?: "unresolved"}' to '${requirement.targetNodeId}' " +
        "on channel '${requirement.channel ?: "unspecified"}'."
)
