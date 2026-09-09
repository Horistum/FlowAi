package org.flowlang.targets.builtin

import org.flowlang.adapters.continuity.AdapterContinuityRequirement
import org.flowlang.adapters.continuity.AdapterContinuityRequirementAuthority
import org.flowlang.adapters.continuity.AdapterContinuityScopedSupport
import org.flowlang.adapters.continuity.AdapterContinuityScopedSupportAuthority
import org.flowlang.adapters.continuity.BuiltInAdapterContinuityScopedSupport
import org.flowlang.generators.manifest.TargetJob
import org.flowlang.generators.manifest.TargetMaterialization
import org.flowlang.generators.manifest.TargetRendererPayload
import org.flowlang.generators.manifest.TargetStep
import org.flowlang.generators.manifest.AdapterManifestLowering.id as sanitizeId
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
 *
 * Unsupported, unresolved or structurally incomplete continuity is preserved as
 * adapter-required diagnostic evidence. It never throws away the manifest, but
 * it also cannot produce executable readiness.
 */
object GitHubActionsWorkspaceContinuityPlanner {
    const val TARGET = "github-actions"
    const val UPLOAD_REFERENCE = "actions/upload-artifact@v7"
    const val DOWNLOAD_REFERENCE = "actions/download-artifact@v8"
    const val WORKSPACE_PATH = "."
    const val PAYLOAD_KIND = GitHubActionsProjectionPayloadKinds.GITHUB_ACTION

    private val supportedScope: AdapterContinuityScopedSupport =
        BuiltInAdapterContinuityScopedSupport.githubActionsCheckoutBuildWorkspace

    fun materialize(plan: ExecutionPlan, jobs: List<TargetJob>): List<TargetJob> {
        val requirements = AdapterContinuityRequirementAuthority.derive(plan)
        if (requirements.isEmpty()) return jobs

        val jobsById = jobs.associateBy(TargetJob::id)
        require(jobsById.size == jobs.size) { "GitHub Actions manifest contains duplicate job ids." }

        val blockers = mutableListOf<ContinuityBlocker>()
        val transfers = requirements.mapNotNull { requirement ->
            val matched = AdapterContinuityScopedSupportAuthority.matchingSupport(
                plan = plan,
                target = TARGET,
                requirement = requirement,
                declarations = listOf(supportedScope)
            )
            if (matched == null) {
                blockers += ContinuityBlocker(
                    requirement,
                    "No bounded GitHub Actions continuity implementation matches this producer, channel and consumer path."
                )
                return@mapNotNull null
            }
            require(matched == supportedScope) {
                "GitHub Actions workspace planner consumed an unexpected scoped support declaration '${matched.identity}'."
            }

            val sourceNodeId = requirement.sourceNodeId
            if (sourceNodeId == null) {
                blockers += ContinuityBlocker(requirement, "Resolved producer identity is unavailable.")
                return@mapNotNull null
            }
            val sourceJobId = sanitizeId(sourceNodeId)
            val targetJobId = sanitizeId(requirement.targetNodeId)
            val sourceJob = jobsById[sourceJobId]
            val targetJob = jobsById[targetJobId]
            when {
                sourceJob == null -> {
                    blockers += ContinuityBlocker(
                        requirement,
                        "Producer job '$sourceJobId' is absent from the generated manifest."
                    )
                    null
                }
                targetJob == null -> {
                    blockers += ContinuityBlocker(
                        requirement,
                        "Consumer job '$targetJobId' is absent from the generated manifest."
                    )
                    null
                }
                sourceJobId !in targetJob.dependsOn -> {
                    blockers += ContinuityBlocker(
                        requirement,
                        "Consumer job '$targetJobId' does not retain required ordering dependency '$sourceJobId'; continuity cannot invent ordering."
                    )
                    null
                }
                else -> WorkspaceTransfer(
                    requirement = requirement,
                    sourceJobId = sourceJobId,
                    targetJobId = targetJobId,
                    artifactName = artifactIdentity(requirement)
                )
            }
        }

        val duplicateConsumers = transfers.groupBy { it.targetJobId to it.artifactName }
            .filterValues { it.size > 1 }
        require(duplicateConsumers.isEmpty()) {
            "GitHub Actions workspace continuity contains duplicate consumer transfers: " +
                duplicateConsumers.keys.joinToString { "${it.first}:${it.second}" }
        }

        val uploadsBySource = transfers.groupBy(WorkspaceTransfer::sourceJobId).mapValues { (_, sourceTransfers) ->
            sourceTransfers.groupBy(WorkspaceTransfer::artifactName).map { (_, group) ->
                require(group.map { it.requirement.channel }.distinct().size == 1) {
                    "GitHub Actions artifact '${group.first().artifactName}' combines different workspace channels."
                }
                uploadStep(group.first())
            }.sortedBy(TargetStep::id)
        }
        val downloadsByTarget = transfers.groupBy(WorkspaceTransfer::targetJobId).mapValues { (_, targetTransfers) ->
            targetTransfers.map(::downloadStep).sortedBy(TargetStep::id)
        }
        val blockersByTarget = blockers.groupBy { sanitizeId(it.requirement.targetNodeId) }
        val orphanBlockers = blockers.filter { sanitizeId(it.requirement.targetNodeId) !in jobsById }
        val orphanHost = jobs.firstOrNull()?.id

        return jobs.map { job ->
            val downloads = downloadsByTarget[job.id].orEmpty()
            val uploads = uploadsBySource[job.id].orEmpty()
            val jobBlockers = blockersByTarget[job.id].orEmpty() +
                if (job.id == orphanHost) orphanBlockers else emptyList()
            val diagnosticSteps = jobBlockers.distinctBy { it.requirement.id }.map(::blockerStep)
            job.copy(
                steps = downloads + diagnosticSteps + job.steps + uploads,
                metadata = job.metadata + mapOf(
                    "githubActionsWorkspaceDownloadCount" to downloads.size.toString(),
                    "githubActionsWorkspaceUploadCount" to uploads.size.toString(),
                    "githubActionsContinuityBlockerCount" to diagnosticSteps.size.toString()
                )
            )
        }
    }

    fun transferCount(plan: ExecutionPlan): Int = AdapterContinuityRequirementAuthority.derive(plan)
        .count { requirement ->
            AdapterContinuityScopedSupportAuthority.matchingSupport(
                plan = plan,
                target = TARGET,
                requirement = requirement,
                declarations = listOf(supportedScope)
            ) != null
        }

    private fun blockerStep(blocker: ContinuityBlocker): TargetStep = TargetStep(
        id = sanitizeId("continuity-blocked-${blocker.requirement.id}"),
        name = "Blocked Flow continuity ${blocker.requirement.semantic}",
        type = "adapter-continuity-blocked",
        materialization = TargetMaterialization.adapterRequired(
            capability = "continuity.${blocker.requirement.semantic}",
            reason = blocker.reason,
            requirements = mapOf(
                "requirementId" to blocker.requirement.id,
                "sourceNodeId" to (blocker.requirement.sourceNodeId ?: "unresolved"),
                "targetNodeId" to blocker.requirement.targetNodeId,
                "channel" to (blocker.requirement.channel ?: "unspecified")
            )
        ),
        metadata = mapOf(
            "continuityRequirementId" to blocker.requirement.id,
            "continuityDecision" to "BLOCKED",
            "continuitySemantic" to blocker.requirement.semantic
        )
    )

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

    private data class ContinuityBlocker(
        val requirement: AdapterContinuityRequirement,
        val reason: String
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
