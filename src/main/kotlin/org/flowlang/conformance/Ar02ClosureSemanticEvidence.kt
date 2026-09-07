package org.flowlang.conformance

import com.fasterxml.jackson.databind.JsonNode
import org.flowlang.cli.Json
import org.flowlang.compiler.CanonicalApprovalNode
import org.flowlang.compiler.CanonicalExecutionGraph
import org.flowlang.compiler.CanonicalExecutionGraphBuild
import org.flowlang.compiler.CanonicalExecutionGraphDigestComputer
import org.flowlang.compiler.CanonicalExecutionGraphValidator
import org.flowlang.compiler.CompilationAuthorization
import org.flowlang.compiler.CompilationFrontend
import org.flowlang.compiler.CompilationUnit
import org.flowlang.planner.WorkflowExecutionPlanSet

internal data class Ar02MutationObservation(
    val id: String,
    val originalDigest: String,
    val mutatedDigest: String,
    val graphChanged: Boolean,
    val staleAuthorizationRejected: Boolean,
    val staleDigestRejected: Boolean,
    val graphRejected: Boolean,
    val mustRejectGraph: Boolean
)

/** Bounded conformance observations. These values are never product authorization. */
internal object Ar02ClosureSemanticEvidence {
    val mutationIds: Set<String> = setOf(
        "path-value", "merge-producer", "merge-edge", "workflow-membership", "trigger-routing",
        "failure-disposition", "handler-identity", "handler-membership", "error-binding", "entry-availability"
    )

    fun observeMutation(
        id: String,
        original: CompilationUnit,
        changed: CanonicalExecutionGraph,
        mustRejectGraph: Boolean = false
    ): Ar02MutationObservation {
        original.authorization.requireIntegrity()
        val digest = CanonicalExecutionGraphDigestComputer.digest(changed)
        val staleDigest = runCatching {
            CanonicalExecutionGraphDigestComputer.requireMatches(changed, original.graphDigest)
        }.exceptionOrNull()
        // Keep the old digest, source validation binding and public views. Neither
        // a changed graph nor its freshly computed hash may reuse that authority.
        val stale = runCatching {
            CompilationAuthorization(
                graph = changed,
                graphDigest = original.graphDigest,
                bindings = original.authorization.bindings,
                validationBinding = original.authorization.validationBinding,
                workflowPlanSet = original.workflowPlanSet
            ).requireIntegrity()
        }.exceptionOrNull()
        val rebound = runCatching {
            CompilationAuthorization(
                graph = changed,
                graphDigest = digest,
                bindings = original.authorization.bindings,
                validationBinding = original.authorization.validationBinding,
                workflowPlanSet = original.workflowPlanSet
            ).requireIntegrity()
        }.exceptionOrNull()
        val validation = CanonicalExecutionGraphValidator.validate(
            CanonicalExecutionGraphBuild(changed, original.authorization.bindings)
        )
        return Ar02MutationObservation(
            id, original.graphDigest.value, digest.value, changed != original.graph,
            isIntegrityRejection(stale) && isIntegrityRejection(rebound),
            isIntegrityRejection(staleDigest), !validation.valid, mustRejectGraph
        )
    }

    private fun isIntegrityRejection(failure: Throwable?): Boolean =
        failure is IllegalArgumentException || failure is IllegalStateException

    fun mutationErrors(observations: List<Ar02MutationObservation>): List<String> = buildList {
        val ids = observations.map { it.id }
        if (ids.toSet() != mutationIds || ids.size != mutationIds.size) {
            add("Mutation matrix must contain every required mutation exactly once: $ids")
        }
        observations.forEach { mutation ->
            if (!mutation.graphChanged || mutation.originalDigest == mutation.mutatedDigest) {
                add("${mutation.id}: semantic mutation was not observed by the graph digest.")
            }
            if (!mutation.staleAuthorizationRejected || !mutation.staleDigestRejected) {
                add("${mutation.id}: stale authorization or its digest was accepted.")
            }
            if (mutation.mustRejectGraph && !mutation.graphRejected) {
                add("${mutation.id}: invalid graph was accepted.")
            }
        }
    }

    fun frontendErrors(units: List<CompilationUnit>): List<String> = buildList {
        val kinds = units.map { it.source.frontend }
        if (kinds.toSet() != CompilationFrontend.entries.toSet() || kinds.size != CompilationFrontend.entries.size) {
            add("Common-subset convergence requires every frontend exactly once.")
            return@buildList
        }
        units.forEach { it.authorization.requireIntegrity() }
        if (units.map(::commonApprovalMeaning).distinct().size != 1) {
            add("The three frontends disagree on the common approval program's observable meaning.")
        }
        val intent = units.single { it.source.frontend == CompilationFrontend.INTENT_YAML }
        val ai = units.single { it.source.frontend == CompilationFrontend.REVIEWED_AI_PROPOSAL }
        if (intent.graph != ai.graph || intent.graphDigest != ai.graphDigest || semanticPlanSet(intent) != semanticPlanSet(ai)) {
            add("Intent YAML and reviewed AI disagree on exact canonical identity or graph-derived views.")
        }
        if (units.map { it.source.sha256 }.toSet().size != units.size) {
            add("Distinct frontend source provenance collapsed to one digest.")
        }
    }

    /**
     * Alpha-equivalence is deliberately limited to a single manual approval.
     * Intent-authored IDs and Source structural IDs remain different canonical
     * identities. Compare the full operation, inputs, output ownership, failure
     * disposition and required topology kinds without claiming equal raw hashes.
     * Multiple nodes, dependencies, controls, handlers and routes are not silently
     * erased: this observer rejects them and separate matrices cover those forms.
     */
    fun commonApprovalMeaning(unit: CompilationUnit): JsonNode {
        val graph = unit.graph
        val workflow = graph.workflows.single()
        val approval = graph.nodes.single() as? CanonicalApprovalNode
            ?: error("Common-subset fixture must contain exactly one approval.")
        require(workflow.rootNodeIds == listOf(approval.id) && approval.workflow == workflow.id)
        require(workflow.failurePolicy.handler == null)
        require(graph.valueMerges.isEmpty() && graph.dependencyEdges.isEmpty() && graph.triggers.isEmpty())
        require(graph.controlRequirements.isEmpty() && graph.controlEvidence.isEmpty())
        require(graph.outputs.all { it.sourceNodeId == approval.id })
        return Json.mapper.valueToTree(linkedMapOf(
            "program" to graph.flowName,
            "workflow" to workflow.name,
            "inputs" to graph.inputs,
            "mode" to approval.mode,
            "message" to approval.message,
            "result" to approval.resultName,
            "semantics" to approval.semantics,
            "outputs" to graph.outputs.map { listOf(it.name, it.type) },
            "capabilities" to graph.requiredCapabilities.map { it.value }.sorted(),
            "topologyNeeds" to graph.topologyRequirements.map { it.kind.name }.distinct().sorted(),
            "failurePolicy" to workflow.failurePolicy,
            "publicContractVersion" to unit.workflowPlanSet.contractVersion
        ))
    }

    fun semanticPlanSet(unit: CompilationUnit): WorkflowExecutionPlanSet {
        val planSet = unit.workflowPlanSet
        return planSet.copy(
            sourceIntent = null,
            loweringReport = null,
            workflows = planSet.workflows.map { view ->
                view.copy(
                    executionPlan = view.executionPlan.copy(sourceIntent = null, loweringReport = null),
                    canonicalPlan = view.canonicalPlan.copy(sourceIntent = null, loweringReport = null)
                )
            }
        )
    }
}
