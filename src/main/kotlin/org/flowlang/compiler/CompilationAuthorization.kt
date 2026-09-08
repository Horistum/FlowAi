package org.flowlang.compiler

import org.flowlang.ai.normalization.IntentProposalReviewEvidence
import org.flowlang.core.FlowAvailabilityAnalysis
import org.flowlang.core.FlowMergeContract
import org.flowlang.core.FlowProducerIdentity
import org.flowlang.intent.IntentValidationReport
import org.flowlang.planner.CanonicalExecutionPlan
import org.flowlang.planner.ExecutionPlan
import org.flowlang.planner.ExecutionProgramPlanningResult
import org.flowlang.planner.FlowPlanningResult
import org.flowlang.planner.PlanDependencyRelations
import org.flowlang.planner.PlanNode
import org.flowlang.planner.PlannedWorkflowFailurePolicy
import org.flowlang.planner.TaskNode
import org.flowlang.planner.WorkflowExecutionPlanSet
import org.flowlang.validator.ValidationReport

enum class CompilationAuthorizationOrigin {
    COMPILATION_UNIT,
    COMPATIBILITY_PLAN
}

data class CompilationValidationBinding(
    val origin: CompilationAuthorizationOrigin,
    val graphDigest: String,
    val sourceSha256: String? = null,
    val intentValid: Boolean? = null,
    val flowValid: Boolean,
    val graphValid: Boolean,
    val proposalReviewValid: Boolean? = null
) {
    init {
        require(GRAPH_DIGEST.matches(graphDigest)) {
            "Compilation validation binding must name a lowercase SHA-256 graph digest."
        }
        require(sourceSha256 == null || GRAPH_DIGEST.matches(sourceSha256)) {
            "Compilation validation binding source digest must be absent or lowercase SHA-256 text."
        }
        require(flowValid) { "Compilation authorization cannot bind a failed Flow validation." }
        require(graphValid) { "Compilation authorization cannot bind an invalid canonical graph." }
        when (origin) {
            CompilationAuthorizationOrigin.COMPILATION_UNIT -> require(sourceSha256 != null) {
                "Compilation-unit authorization must bind the exact source digest."
            }
            CompilationAuthorizationOrigin.COMPATIBILITY_PLAN -> {
                require(sourceSha256 == null && intentValid == null && proposalReviewValid == null) {
                    "Compatibility-plan authorization cannot claim source, Intent or proposal-review evidence."
                }
            }
        }
        if (proposalReviewValid != null) {
            require(proposalReviewValid && intentValid == true) {
                "Proposal-review authorization may only bind a successful reviewed Intent validation."
            }
        }
    }

    private companion object {
        val GRAPH_DIGEST = Regex("[0-9a-f]{64}")
    }
}

/**
 * Unforgeable-in-API authorization for one exact canonical graph.
 *
 * The planner may still be used as a construction stage, but the value exposed
 * to downstream consumers is reconstructed from the validated graph and its
 * separately typed binding envelope. No caller-supplied plan can repair or widen
 * graph meaning after this boundary.
 */
class CompilationAuthorization internal constructor(
    val graph: CanonicalExecutionGraph,
    val graphDigest: CanonicalExecutionGraphDigest,
    internal val bindings: CanonicalExecutionBindingSet,
    val validationBinding: CompilationValidationBinding,
    val workflowPlanSet: WorkflowExecutionPlanSet
) {
    val executionPlan: ExecutionPlan
        get() = workflowPlanSet.requireSingleExecutionPlan("CompilationAuthorization.executionPlan")

    val canonicalPlan: CanonicalExecutionPlan
        get() = workflowPlanSet.requireSingleCanonicalPlan("CompilationAuthorization.canonicalPlan")

    init {
        require(validationBinding.graphDigest == graphDigest.value) {
            "Compilation validation evidence is bound to '${validationBinding.graphDigest}', " +
                "but authorization carries '$graphDigest'."
        }
    }

    fun requireIntegrity(): CompilationAuthorization {
        requireBoundGraph(graph, graphDigest)
        return this
    }

    /**
     * Validated diagnostic data, not an authorization factory. A modified view
     * must pass the graph validator and cannot replace this authorization.
     */
    fun inspectionView(): CanonicalExecutionGraphBuild {
        requireIntegrity()
        return CanonicalExecutionGraphBuild(graph, bindings)
    }

    /** Verify that an externally retained graph still belongs to this exact authority. */
    fun requireMatchingGraph(candidate: CanonicalExecutionGraph, digest: CanonicalExecutionGraphDigest) {
        requireIntegrity()
        requireBoundGraph(candidate, digest)
    }

    private fun requireBoundGraph(candidate: CanonicalExecutionGraph, digest: CanonicalExecutionGraphDigest) {
        CanonicalExecutionGraphValidator.requireValid(CanonicalExecutionGraphBuild(candidate, bindings))
        CanonicalExecutionGraphDigestComputer.requireMatches(candidate, digest)
        val projected = CanonicalExecutionGraphProjection.toWorkflowExecutionPlanSet(candidate, bindings)
        require(projected == workflowPlanSet) {
            "Graph-derived WorkflowExecutionPlanSet drifted after authorization."
        }
        require(validationBinding.graphDigest == digest.value) {
            "Compilation authorization evidence no longer matches the canonical graph digest."
        }
    }

}


@ConsistentCopyVisibility
data class AuthorizedCanonicalTask internal constructor(
    val node: CanonicalTaskNode,
    val binding: CanonicalTaskBinding,
    val compatibilityTask: TaskNode
)

/**
 * Resolves one compatibility TaskNode back to the exact task owned by this authorization.
 *
 * Materialization may inspect implementation binding only after this equality gate. Semantic
 * capability is read from [AuthorizedCanonicalTask.node], never reconstructed from module/action.
 */
fun CompilationAuthorization.requireAuthorizedTask(task: TaskNode): AuthorizedCanonicalTask {
    requireIntegrity()
    val projectedTask = workflowPlanSet.workflows
        .flatMap { workflow -> PlanDependencyRelations.flatten(workflow.executionPlan.nodes) }
        .filterIsInstance<TaskNode>()
        .singleOrNull { candidate -> candidate.id == task.id }
        ?: error("Task '${task.id}' is not present in the graph-derived ExecutionPlan.")
    require(projectedTask == task) {
        "Task '${task.id}' differs from the graph-derived compatibility view."
    }
    val metadata = bindings.nodeMetadata.singleOrNull { projection -> projection.planNodeId == task.id }
        ?: error("Task '${task.id}' has no canonical projection metadata.")
    val node = graph.nodes.singleOrNull { candidate -> candidate.id == metadata.nodeId }
        as? CanonicalTaskNode
        ?: error("Task '${task.id}' does not resolve to one canonical task node.")
    val binding = bindings.tasks.singleOrNull { candidate -> candidate.nodeId == node.id }
        ?: error("Canonical task '${node.id}' has no implementation binding.")
    require(binding.module == task.module && binding.action == task.action && binding.target == task.target) {
        "Task '${task.id}' implementation binding differs from its authorized graph binding."
    }
    return AuthorizedCanonicalTask(node, binding, projectedTask)
}

object CanonicalExecutionGraphGate {
    internal fun authorizeCompilation(
        source: CompilationSource,
        intentValidation: IntentValidationReport?,
        proposalReview: IntentProposalReviewEvidence?,
        flowValidation: ValidationReport,
        planning: FlowPlanningResult,
        availability: FlowAvailabilityAnalysis
    ): CompilationAuthorization {
        require(flowValidation.valid) {
            "Canonical graph authorization requires successful Flow validation."
        }
        requireCompilationEvidence(source, intentValidation, proposalReview)
        return authorize(
            plannerPlan = planning.plan,
            mergeContracts = availability.merges,
            producerNodeIds = planning.producerNodeIds,
            failurePolicy = planning.failurePolicy,
            binding = { digest ->
                CompilationValidationBinding(
                    origin = CompilationAuthorizationOrigin.COMPILATION_UNIT,
                    graphDigest = digest.value,
                    sourceSha256 = source.sha256,
                    intentValid = intentValidation?.valid,
                    flowValid = flowValidation.valid,
                    graphValid = true,
                    proposalReviewValid = proposalReview?.accepted
                )
            }
        )
    }

    internal fun authorizeCompilation(
        source: CompilationSource,
        intentValidation: IntentValidationReport?,
        proposalReview: IntentProposalReviewEvidence?,
        planning: ExecutionProgramPlanningResult
    ): CompilationAuthorization {
        require(planning.workflows.isNotEmpty()) {
            "Canonical graph authorization requires at least one validated workflow."
        }
        requireCompilationEvidence(source, intentValidation, proposalReview)
        return authorizeProgram(
            planning = planning,
            binding = { digest ->
                CompilationValidationBinding(
                    origin = CompilationAuthorizationOrigin.COMPILATION_UNIT,
                    graphDigest = digest.value,
                    sourceSha256 = source.sha256,
                    intentValid = intentValidation?.valid,
                    flowValid = true,
                    graphValid = true,
                    proposalReviewValid = proposalReview?.accepted
                )
            }
        )
    }

    fun authorizeCompilation(
        source: CompilationSource,
        intentValidation: IntentValidationReport?,
        proposalReview: IntentProposalReviewEvidence?,
        flowValidation: ValidationReport,
        plannerPlan: ExecutionPlan
    ): CompilationAuthorization {
        require(flowValidation.valid) {
            "Canonical graph authorization requires successful Flow validation."
        }
        requireCompilationEvidence(source, intentValidation, proposalReview)
        return authorize(
            plannerPlan = plannerPlan,
            binding = { digest ->
                CompilationValidationBinding(
                    origin = CompilationAuthorizationOrigin.COMPILATION_UNIT,
                    graphDigest = digest.value,
                    sourceSha256 = source.sha256,
                    intentValid = intentValidation?.valid,
                    flowValid = flowValidation.valid,
                    graphValid = true,
                    proposalReviewValid = proposalReview?.accepted
                )
            }
        )
    }

    private fun requireCompilationEvidence(
        source: CompilationSource,
        intentValidation: IntentValidationReport?,
        proposalReview: IntentProposalReviewEvidence?
    ) {
        when (source.frontend) {
            CompilationFrontend.FLOW_SOURCE -> require(intentValidation == null && proposalReview == null) {
                "Flow Source authorization cannot claim Intent or AI proposal-review evidence."
            }
            CompilationFrontend.INTENT_YAML -> require(intentValidation?.valid == true && proposalReview == null) {
                "Intent YAML authorization requires successful Intent validation and no AI review evidence."
            }
            CompilationFrontend.REVIEWED_AI_PROPOSAL -> {
                require(intentValidation?.valid == true && proposalReview?.accepted == true) {
                    "Reviewed AI proposal authorization requires successful proposal review and Intent validation."
                }
                require(proposalReview.validation == intentValidation) {
                    "Reviewed AI proposal authorization requires the exact compiler Intent validation report."
                }
            }
        }
    }

    /**
     * Source- and binary-compatible pre-AR-01C entrypoint for non-AI frontends.
     * Reviewed proposals must use the proposal-aware overload above.
     */
    fun authorizeCompilation(
        source: CompilationSource,
        intentValidation: IntentValidationReport?,
        flowValidation: ValidationReport,
        plannerPlan: ExecutionPlan
    ): CompilationAuthorization = authorizeCompilation(
        source = source,
        intentValidation = intentValidation,
        proposalReview = null,
        flowValidation = flowValidation,
        plannerPlan = plannerPlan
    )

    /**
     * Compatibility ingress for inventoried conformance and manually assembled
     * plan fixtures. Callers must retain the raw plan until the established
     * planning-evidence validator has inspected it; this gate is evaluated only
     * after that boundary or when graph evidence is explicitly requested.
     */
    fun authorizeCompatibilityPlan(
        plannerPlan: ExecutionPlan,
        evidenceId: String
    ): CompilationAuthorization = authorizeCompatibilityPlan(
        plannerPlan, evidenceId, PlannedWorkflowFailurePolicy.none()
    )

    /** Untrusted compatibility input is validated; it can never claim source or proposal evidence. */
    fun authorizeCompatibilityPlan(
        plannerPlan: ExecutionPlan,
        evidenceId: String,
        failurePolicy: PlannedWorkflowFailurePolicy
    ): CompilationAuthorization {
        require(evidenceId.isNotBlank()) { "Compatibility-plan evidence id must not be blank." }
        return authorize(
            plannerPlan = plannerPlan,
            binding = { digest ->
                CompilationValidationBinding(
                    origin = CompilationAuthorizationOrigin.COMPATIBILITY_PLAN,
                    graphDigest = digest.value,
                    sourceSha256 = null,
                    intentValid = null,
                    flowValid = true,
                    graphValid = true
                )
            },
            failurePolicy = failurePolicy
        )
    }

    private fun authorize(
        plannerPlan: ExecutionPlan,
        binding: (CanonicalExecutionGraphDigest) -> CompilationValidationBinding,
        mergeContracts: List<FlowMergeContract> = emptyList(),
        producerNodeIds: Map<FlowProducerIdentity, String> = emptyMap(),
        failurePolicy: PlannedWorkflowFailurePolicy = PlannedWorkflowFailurePolicy.none()
    ): CompilationAuthorization {
        val build = CanonicalExecutionGraphBuilder.build(
            plannerPlan,
            mergeContracts,
            producerNodeIds,
            failurePolicy
        )
        CanonicalExecutionGraphValidator.requireValid(build)
        val digest = CanonicalExecutionGraphDigestComputer.digest(build.graph)
        val projectedSet = CanonicalExecutionGraphProjection.toWorkflowExecutionPlanSet(build)
        require(projectedSet.requireSingleExecutionPlan() == plannerPlan) {
            "Canonical graph projection is not exactly equivalent to the planner output. " +
                "The authority cutover refuses dual semantic truth."
        }
        return CompilationAuthorization(
            graph = build.graph,
            graphDigest = digest,
            bindings = build.bindings,
            validationBinding = binding(digest),
            workflowPlanSet = projectedSet
        ).requireIntegrity()
    }

    private fun authorizeProgram(
        planning: ExecutionProgramPlanningResult,
        binding: (CanonicalExecutionGraphDigest) -> CompilationValidationBinding
    ): CompilationAuthorization {
        val build = CanonicalExecutionGraphBuilder.build(planning)
        CanonicalExecutionGraphValidator.requireValid(build)
        val digest = CanonicalExecutionGraphDigestComputer.digest(build.graph)
        val projectedSet = CanonicalExecutionGraphProjection.toWorkflowExecutionPlanSet(build)
        val expectedPlans = planning.workflows.sortedBy { it.workflowName }.map { workflow ->
            workflow.workflowName to workflow.planning.plan
        }
        val projectedPlans = projectedSet.workflows.map { workflow ->
            workflow.workflowName to workflow.executionPlan
        }
        if (projectedPlans != expectedPlans) {
            val differences = expectedPlans.zip(projectedPlans).joinToString(" | ") { (expectedEntry, projectedEntry) ->
                val (expectedName, expected) = expectedEntry
                val (projectedName, projected) = projectedEntry
                buildList {
                    if (expectedName != projectedName) add("workflowName=$expectedName/$projectedName")
                    if (expected.flowName != projected.flowName) add("flowName=${expected.flowName}/${projected.flowName}")
                    if (expected.planVersion != projected.planVersion) add("planVersion=${expected.planVersion}/${projected.planVersion}")
                    if (expected.inputs != projected.inputs) add("inputs=${expected.inputs}/${projected.inputs}")
                    if (expected.triggers != projected.triggers) add("triggers=${expected.triggers}/${projected.triggers}")
                    if (expected.outputs != projected.outputs) add("outputs=${expected.outputs}/${projected.outputs}")
                    if (expected.dependencies != projected.dependencies) add("dependencies=${expected.dependencies}/${projected.dependencies}")
                    if (expected.requiredCapabilities != projected.requiredCapabilities) {
                        add("requiredCapabilities=${expected.requiredCapabilities}/${projected.requiredCapabilities}")
                    }
                    if (expected.targetHints != projected.targetHints) add("targetHints=${expected.targetHints}/${projected.targetHints}")
                    if (expected.sourceIntent != projected.sourceIntent) add("sourceIntent=${expected.sourceIntent}/${projected.sourceIntent}")
                    if (expected.loweringReport != projected.loweringReport) add("loweringReport differs")
                    if (expected.assumptions != projected.assumptions) add("assumptions=${expected.assumptions}/${projected.assumptions}")
                    if (expected.controlRequirements != projected.controlRequirements) add("controlRequirements differ")
                    if (expected.controlEvidence != projected.controlEvidence) add("controlEvidence differs")
                    if (expected.controlDecision != projected.controlDecision) {
                        add("controlDecision=${expected.controlDecision}/${projected.controlDecision}")
                    }
                    if (expected.nodes != projected.nodes) add("nodes=${expected.nodes}/${projected.nodes}")
                    if (expected.dependencyRelations != projected.dependencyRelations) {
                        add("dependencyRelations=${expected.dependencyRelations}/${projected.dependencyRelations}")
                    }
                    if (expected.topologyRequirements != projected.topologyRequirements) {
                        add("topologyRequirements=${expected.topologyRequirements}/${projected.topologyRequirements}")
                    }
                }.joinToString(", ").ifBlank { "no field difference reported" }
            }
            error("Canonical graph workflow projection mismatch: $differences")
        }
        require(projectedSet.flowName == planning.flowName) {
            "Canonical graph program name differs from the planner envelope."
        }
        require(projectedSet.inputs == planning.inputs && projectedSet.triggers == planning.triggers) {
            "Canonical graph changed program inputs or trigger routing."
        }
        require(
            projectedSet.sourceIntent == planning.sourceIntent &&
                projectedSet.loweringReport == planning.loweringReport
        ) {
            "Canonical graph projection changed multi-workflow source or lowering evidence."
        }
        return CompilationAuthorization(
            graph = build.graph,
            graphDigest = digest,
            bindings = build.bindings,
            validationBinding = binding(digest),
            workflowPlanSet = projectedSet
        ).requireIntegrity()
    }
}

/** Temporary source-compatibility alias retained until all AR-01 callers use the gate name. */
@Deprecated("Use CanonicalExecutionGraphGate")
internal val CanonicalExecutionGraphAuthority = CanonicalExecutionGraphGate
