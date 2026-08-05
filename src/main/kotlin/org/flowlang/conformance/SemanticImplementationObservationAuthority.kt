package org.flowlang.conformance

import java.io.File
import org.flowlang.adapters.continuity.AdapterContinuityEvidenceStatus
import org.flowlang.adapters.continuity.AdapterContinuitySatisfactionAuthority
import org.flowlang.adapters.rendering.AdapterArtifactRenderingBundle
import org.flowlang.adapters.rendering.AdapterRenderedArtifactKind
import org.flowlang.adapters.trigger.AdapterTriggerAuthorizedRenderingAuthority
import org.flowlang.adapters.trigger.AdapterTriggerMaterializationAuthority
import org.flowlang.adapters.yaml.TargetRegistryYamlLoader
import org.flowlang.capabilities.TargetCapability
import org.flowlang.generators.manifest.TargetManifest
import org.flowlang.generators.manifest.TargetManifestContractValidator
import org.flowlang.generators.manifest.TargetMaterializationStatus
import org.flowlang.generators.manifest.TargetProjectionRegistry
import org.flowlang.generators.manifest.TargetRenderMode
import org.flowlang.generators.manifest.TargetRenderPolicy
import org.flowlang.generators.manifest.TargetStep
import org.flowlang.materialization.TargetMaterializationRequest
import org.flowlang.materialization.TargetSelectionAuthority
import org.flowlang.planner.ExecutionPlan
import org.flowlang.planner.PlanDependencyKind
import org.flowlang.planner.PlanDependencyRelations
import org.flowlang.planner.TaskNode
import org.flowlang.targets.builtin.BuiltInTargetProjections

data class SemanticImplementationObservationProfile(
    val target: String,
    val manifest: TargetManifest,
    val rendering: AdapterArtifactRenderingBundle,
    val evidence: List<SemanticObservationEvidence>
)

/**
 * Converts adapter-owned projection evidence into target-neutral C0.3 evidence.
 *
 * Requirements are always derived before this authority is invoked. This class
 * may prove or reject a requirement, but it cannot add meaning to the plan.
 * Provider syntax is intentionally absent from requirement identity: the
 * evidence boundary is the typed production TargetManifest, its integrity-bound
 * rendering receipt and the independent adapter continuity authority.
 */
class SemanticImplementationObservationAuthority(
    private val rootDir: File = File("."),
    private val targets: Map<String, TargetCapability> =
        TargetRegistryYamlLoader.loadDirectory(File(rootDir, "targets")),
    private val projections: TargetProjectionRegistry = BuiltInTargetProjections.registry
) {
    private val manifestPipeline = BuiltInTargetProjections.pipeline(targets, rootDir)
    private val continuityAuthority = AdapterContinuitySatisfactionAuthority(rootDir, targets, projections)
    private val triggerAuthority = AdapterTriggerMaterializationAuthority(rootDir, targets, projections)
    private val renderingAuthority = AdapterTriggerAuthorizedRenderingAuthority(rootDir, projections)

    fun profile(
        plan: ExecutionPlan,
        target: String,
        scenarioId: String,
        requirements: List<SemanticObservationRequirement> = SemanticObservationAuthority.requirementsFor(plan)
    ): SemanticImplementationObservationProfile {
        require(target in targets) { "Unknown semantic implementation evidence target '$target'." }
        val derivedRequirements = SemanticObservationAuthority.requirementsFor(plan)
        require(requirements == derivedRequirements) {
            "Target-backed semantic evidence must consume the exact observation set derived from the supplied plan."
        }
        val selection = TargetSelectionAuthority.fromReferenceSnapshot(
            value = target,
            scenarioId = scenarioId,
            targets = targets
        )
        val generatedManifest = manifestPipeline.generate(TargetMaterializationRequest(plan, selection))
        val triggerAssessment = triggerAuthority.assess(plan, target)
        val manifest = triggerAuthority.reconcileDiagnostic(generatedManifest, triggerAssessment)
        val contract = TargetManifestContractValidator.validate(manifest)
        require(contract.valid) {
            "Target '$target' manifest violates its typed contract: " +
                contract.issues.joinToString(" | ") { "${it.code}:${it.path}:${it.message}" }
        }
        val readiness = TargetRenderPolicy.requireExecutable(manifest)
        val rendering = renderingAuthority.render(manifest)
        require(rendering.receipt.renderMode == readiness.mode && readiness.mode == TargetRenderMode.EXECUTABLE) {
            "Target '$target' rendering receipt does not preserve executable manifest readiness."
        }
        require(rendering.artifact.kind == AdapterRenderedArtifactKind.EXECUTABLE_TARGET) {
            "Target '$target' semantic equivalence evidence requires executable target syntax."
        }

        val tasks = PlanDependencyRelations.flatten(plan.nodes).filterIsInstance<TaskNode>()
        val tasksBySemanticIdentity = tasks.associateBy(::semanticIdentity)
        require(tasksBySemanticIdentity.size == tasks.size) {
            "C0.3 concrete plan contains duplicate semantic task identities."
        }
        val taskById = tasks.associateBy(TaskNode::id)
        val stepsBySourceTask = flattenSteps(manifest).mapNotNull { step ->
            step.metadata["sourceTask"]?.let { sourceTask -> sourceTask to step }
        }.groupBy({ it.first }, { it.second })
        require(stepsBySourceTask.values.none { it.size > 1 }) {
            "Target '$target' manifest maps one source task to multiple action steps."
        }
        val continuity = continuityAuthority.assess(plan, target)
        val continuityEvidenceById = continuity.evidence.associateBy { it.requirementId }

        val evidence = requirements.mapNotNull { requirement ->
            when (requirement.kind) {
                SemanticObservationKind.EFFECT -> taskEvidence(
                    requirement,
                    tasksBySemanticIdentity,
                    stepsBySourceTask,
                    target,
                    requireResultIdentity = false
                )
                SemanticObservationKind.RESULT_IDENTITY -> taskEvidence(
                    requirement,
                    tasksBySemanticIdentity,
                    stepsBySourceTask,
                    target,
                    requireResultIdentity = true
                )
                SemanticObservationKind.RESULT_VALUE -> resultValueEvidence(
                    requirement,
                    plan,
                    tasksBySemanticIdentity,
                    taskById,
                    stepsBySourceTask,
                    target
                )
                SemanticObservationKind.CONTINUITY -> continuityEvidence(
                    requirement,
                    tasksBySemanticIdentity,
                    continuity.requirements,
                    continuityEvidenceById,
                    target
                )
            }
        }
        return SemanticImplementationObservationProfile(target, manifest, rendering, evidence)
    }

    private fun taskEvidence(
        requirement: SemanticObservationRequirement,
        tasksBySemanticIdentity: Map<String, TaskNode>,
        stepsBySourceTask: Map<String, List<TargetStep>>,
        target: String,
        requireResultIdentity: Boolean
    ): SemanticObservationEvidence? {
        val producerIdentity = requirement.producerIdentity ?: return null
        val task = tasksBySemanticIdentity[producerIdentity] ?: return null
        val step = stepsBySourceTask[task.id]?.singleOrNull() ?: return null
        val native = nativeWitness(step, target)
        val status = if (requireResultIdentity) {
            when {
                step.metadata["resultName"].isNullOrBlank() -> return null
                step.metadata["resultName"] != requirement.value -> SemanticObservationEvidenceStatus.CONTRADICTORY
                native -> SemanticObservationEvidenceStatus.PRESERVED
                else -> SemanticObservationEvidenceStatus.WEAKENED
            }
        } else {
            if (native) SemanticObservationEvidenceStatus.PRESERVED else SemanticObservationEvidenceStatus.WEAKENED
        }
        return evidence(requirement, status, "$target:manifest:task:${task.id}")
    }

    private fun resultValueEvidence(
        requirement: SemanticObservationRequirement,
        plan: ExecutionPlan,
        tasksBySemanticIdentity: Map<String, TaskNode>,
        taskById: Map<String, TaskNode>,
        stepsBySourceTask: Map<String, List<TargetStep>>,
        target: String
    ): SemanticObservationEvidence? {
        val output = plan.outputs.singleOrNull {
            it.name == requirement.subject && it.type == requirement.value
        } ?: return evidence(
            requirement,
            SemanticObservationEvidenceStatus.CONTRADICTORY,
            "$target:manifest:output-contract-mismatch:${requirement.subject}"
        )
        val producerIdentity = requirement.producerIdentity ?: return null
        val semanticProducer = tasksBySemanticIdentity[producerIdentity] ?: return null
        val sourceNodeId = output.sourceNodeId ?: return null
        val sourceTask = taskById[sourceNodeId] ?: return evidence(
            requirement,
            SemanticObservationEvidenceStatus.CONTRADICTORY,
            "$target:manifest:unknown-output-producer:$sourceNodeId"
        )
        if (sourceTask != semanticProducer) {
            return evidence(
                requirement,
                SemanticObservationEvidenceStatus.CONTRADICTORY,
                "$target:manifest:output-producer-mismatch:${output.name}"
            )
        }
        val step = stepsBySourceTask[sourceTask.id]?.singleOrNull() ?: return null
        val projectedResultName = step.metadata["resultName"]?.takeIf(String::isNotBlank) ?: return null
        val status = when {
            sourceTask.resultName != output.name -> SemanticObservationEvidenceStatus.CONTRADICTORY
            projectedResultName != output.name -> SemanticObservationEvidenceStatus.CONTRADICTORY
            output.name !in sourceTask.outputs -> SemanticObservationEvidenceStatus.CONTRADICTORY
            nativeWitness(step, target) -> SemanticObservationEvidenceStatus.PRESERVED
            else -> SemanticObservationEvidenceStatus.WEAKENED
        }
        return evidence(requirement, status, "$target:manifest:output:${output.name}:${sourceTask.id}")
    }

    private fun continuityEvidence(
        requirement: SemanticObservationRequirement,
        tasksBySemanticIdentity: Map<String, TaskNode>,
        adapterRequirements: List<org.flowlang.adapters.continuity.AdapterContinuityRequirement>,
        adapterEvidenceById: Map<String, org.flowlang.adapters.continuity.AdapterContinuityEvidence>,
        target: String
    ): SemanticObservationEvidence? {
        val sourceTask = requirement.producerIdentity?.let(tasksBySemanticIdentity::get) ?: return null
        val targetTask = requirement.consumerIdentity?.let(tasksBySemanticIdentity::get) ?: return null
        val relationKind = runCatching { PlanDependencyKind.valueOf(requirement.subject) }.getOrNull()
            ?: return evidence(
                requirement,
                SemanticObservationEvidenceStatus.CONTRADICTORY,
                "$target:continuity:unknown-kind:${requirement.subject}"
            )
        val matched = adapterRequirements.filter {
            it.sourceNodeId == sourceTask.id &&
                it.targetNodeId == targetTask.id &&
                (it.channel ?: DEFAULT_CONTINUITY_CHANNEL) == requirement.value &&
                it.relationKind == relationKind
        }
        if (matched.isEmpty()) return null
        val statuses = matched.map { adapterRequirement ->
            adapterEvidenceById[adapterRequirement.id]?.status
                ?: return evidence(
                    requirement,
                    SemanticObservationEvidenceStatus.CONTRADICTORY,
                    "$target:continuity:missing-assessment:${adapterRequirement.id}"
                )
        }
        val status = when {
            statuses.all { it == AdapterContinuityEvidenceStatus.SATISFIED } ->
                SemanticObservationEvidenceStatus.PRESERVED
            statuses.any { it == AdapterContinuityEvidenceStatus.UNSUPPORTED } ->
                SemanticObservationEvidenceStatus.CONTRADICTORY
            else -> SemanticObservationEvidenceStatus.UNKNOWN
        }
        return evidence(
            requirement,
            status,
            "$target:continuity:${matched.joinToString(",") { it.id }}"
        )
    }

    private fun nativeWitness(step: TargetStep, target: String): Boolean {
        val payload = step.rendererPayload ?: return false
        return step.materialization.status == TargetMaterializationStatus.NATIVE &&
            payload.target == target &&
            payload.evidenceReference.isNotBlank()
    }

    private fun evidence(
        requirement: SemanticObservationRequirement,
        status: SemanticObservationEvidenceStatus,
        reference: String
    ): SemanticObservationEvidence = SemanticObservationEvidence(
        requirementId = requirement.id,
        fingerprint = requirement.fingerprint,
        status = status,
        evidenceReference = reference
    )

    private fun flattenSteps(manifest: TargetManifest): List<TargetStep> {
        fun flatten(step: TargetStep): List<TargetStep> = listOf(step) + step.children.flatMap(::flatten)
        return manifest.jobs.flatMap { job -> job.steps.flatMap(::flatten) }
    }

    private fun semanticIdentity(task: TaskNode): String =
        task.sourceId?.takeIf(String::isNotBlank) ?: task.id

    companion object {
        private const val DEFAULT_CONTINUITY_CHANNEL = "default"
    }
}
