package org.flowlang.compiler

import org.flowlang.ai.normalization.IntentProposalReviewEvidence
import org.flowlang.core.FlowAvailabilityAnalysis
import org.flowlang.core.FlowMergeContract
import org.flowlang.core.FlowProducerIdentity
import org.flowlang.intent.IntentValidationReport
import org.flowlang.planner.CanonicalExecutionPlan
import org.flowlang.planner.ExecutionPlan
import org.flowlang.planner.FlowPlanningResult
import org.flowlang.planner.PlanDependencyRelations
import org.flowlang.planner.TaskNode
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
    val executionPlan: ExecutionPlan,
    val canonicalPlan: CanonicalExecutionPlan
) {
    init {
        require(validationBinding.graphDigest == graphDigest.value) {
            "Compilation validation evidence is bound to '${validationBinding.graphDigest}', " +
                "but authorization carries '$graphDigest'."
        }
    }

    fun requireIntegrity(): CompilationAuthorization {
        CanonicalExecutionGraphValidator.requireValid(CanonicalExecutionGraphBuild(graph, bindings))
        CanonicalExecutionGraphDigestComputer.requireMatches(graph, graphDigest)
        val projected = CanonicalExecutionGraphProjection.toExecutionPlan(graph, bindings)
        require(projected == executionPlan) {
            "Graph-derived ExecutionPlan drifted after authorization."
        }
        val projectedCanonical = CanonicalExecutionGraphProjection.toCanonicalExecutionPlan(graph, bindings)
        require(projectedCanonical == canonicalPlan) {
            "Graph-derived CanonicalExecutionPlan drifted after authorization."
        }
        require(validationBinding.graphDigest == graphDigest.value) {
            "Compilation authorization evidence no longer matches the canonical graph digest."
        }
        return this
    }
}


internal data class AuthorizedCanonicalTask(
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
internal fun CompilationAuthorization.requireAuthorizedTask(task: TaskNode): AuthorizedCanonicalTask {
    requireIntegrity()
    val projectedTask = PlanDependencyRelations.flatten(executionPlan.nodes)
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
    internal fun authorizeCompatibilityPlan(
        plannerPlan: ExecutionPlan,
        evidenceId: String
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
            }
        )
    }

    private fun authorize(
        plannerPlan: ExecutionPlan,
        binding: (CanonicalExecutionGraphDigest) -> CompilationValidationBinding,
        mergeContracts: List<FlowMergeContract> = emptyList(),
        producerNodeIds: Map<FlowProducerIdentity, String> = emptyMap()
    ): CompilationAuthorization {
        val build = CanonicalExecutionGraphBuilder.build(plannerPlan, mergeContracts, producerNodeIds)
        CanonicalExecutionGraphValidator.requireValid(build)
        val digest = CanonicalExecutionGraphDigestComputer.digest(build.graph)
        val projected = CanonicalExecutionGraphProjection.toExecutionPlan(build)
        require(projected == plannerPlan) {
            "Canonical graph projection is not exactly equivalent to the planner output. " +
                "The authority cutover refuses dual semantic truth."
        }
        val canonical = CanonicalExecutionGraphProjection.toCanonicalExecutionPlan(build)
        return CompilationAuthorization(
            graph = build.graph,
            graphDigest = digest,
            bindings = build.bindings,
            validationBinding = binding(digest),
            executionPlan = projected,
            canonicalPlan = canonical
        ).requireIntegrity()
    }
}

/** Temporary source-compatibility alias retained until all AR-01 callers use the gate name. */
@Deprecated("Use CanonicalExecutionGraphGate")
internal val CanonicalExecutionGraphAuthority = CanonicalExecutionGraphGate
