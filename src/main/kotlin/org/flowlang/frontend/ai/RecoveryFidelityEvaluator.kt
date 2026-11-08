package org.flowlang.frontend.ai

import org.flowlang.ast.*
import org.flowlang.compiler.*
import org.flowlang.core.FlowAvailabilityLimits
import org.flowlang.effects.EffectOperation
import org.flowlang.effects.EffectDomain
import org.flowlang.intent.StandardCapability
import org.flowlang.parser.ExpressionParser

/** Structural obligations over the authorized graph; never a recovery executor or runtime-success claim. */
internal class RecoveryFidelityEvaluator(private val graph: CanonicalExecutionGraph) {
    private class Uncheckable(val code: String) : RuntimeException()
    private val nodes = graph.nodes.associateBy { it.id }
    private val workflows = graph.workflows.associateBy { it.id }
    private val parents = graph.nodes.flatMap { parent -> parent.structuralChildren().map { it to parent.id } }.toMap()
    private var work = 0L
    private fun charge(amount: Int = 1) {
        work += amount
        if (work > FlowAvailabilityLimits.MAX_WORK_UNITS) throw Uncheckable("FIDELITY_REVIEW_LIMIT")
    }
    private fun review(): Nothing = throw Uncheckable("FIDELITY_UNCHECKABLE_RECOVERY_REGION")
    private fun select(selector: RequirementSelector): CanonicalNodeId? {
        var dynamic = false
        val matches = graph.nodes.filter { node ->
            charge()
            val capability = if (node is CanonicalApprovalNode) StandardCapability.APPROVE.name else node.semantics.capability?.value
            if (capability != selector.capability.name ||
                selector.workflow != null && workflows.getValue(node.workflow).name != selector.workflow) return@filter false
            var uncertain = false
            for ((key, expected) in selector.parameters) {
                val source = node.semantics.parameters[key] ?: return@filter false
                when (val expression = runCatching { ExpressionParser.parseSource(source) }.getOrNull()) {
                    is StringLiteralNode -> if (expression.value != expected) return@filter false
                    is NumberLiteralNode, is BooleanLiteralNode, is NullLiteralNode -> return@filter false
                    else -> uncertain = true
                }
            }
            if (uncertain) dynamic = true
            !uncertain
        }
        if (dynamic) throw Uncheckable("FIDELITY_DYNAMIC_SELECTION")
        if (matches.size > 1) throw Uncheckable("FIDELITY_AMBIGUOUS_OPERATION")
        return matches.singleOrNull()?.id
    }
    private fun selected(selectors: List<RequirementSelector>): List<CanonicalNodeId>? {
        val selected = selectors.map { select(it) ?: return null }
        if (selected.distinct().size != selected.size) review()
        return selected
    }
    private fun ancestry(id: CanonicalNodeId): List<CanonicalExecutionNode> {
        val result = mutableListOf<CanonicalExecutionNode>()
        var parent = parents[id]
        while (parent != null) {
            charge()
            if (result.size >= 64) throw Uncheckable("FIDELITY_REVIEW_LIMIT")
            val node = nodes.getValue(parent)
            result += node
            parent = parents[parent]
        }
        return result
    }
    private fun readOnly(node: CanonicalExecutionNode): Boolean = node.semantics.effects.isNotEmpty() &&
        node.semantics.effects.all { it.operation == EffectOperation.READ && it.domain != EffectDomain.UNKNOWN }

    /** Bounded, sequential operation regions. Branches, merges and nested attempts need a later proof. */
    private fun operations(ids: List<CanonicalNodeId>, allowRetry: Boolean): List<CanonicalNodeId> = ids.flatMap { id ->
        charge()
        when (val node = nodes.getValue(id)) {
            is CanonicalTaskNode, is CanonicalApprovalNode -> listOf(id)
            is CanonicalRetryNode -> {
                if (!allowRetry || node.max !in 1..100 || node.backoff != "fixed" ||
                    !Regex("(0|[1-9][0-9]{0,3})s").matches(node.delay) || node.delay.dropLast(1).toInt() > 3600) review()
                operations(node.bodyNodeIds, false).also { children ->
                    if (children.isEmpty() || children.any { !readOnly(nodes.getValue(it)) }) review()
                }
            }
            else -> review()
        }
    }
    fun evaluate(check: FidelityCheck): Pair<FidelityRequirementState, String> = try {
        val valid = when (check) {
            is RetryRegion -> retry(check)
            is FailureRegion -> failure(check)
            else -> review()
        }
        if (valid) FidelityRequirementState.SATISFIED to "FIDELITY_SATISFIED"
        else FidelityRequirementState.VIOLATED to "FIDELITY_VIOLATED"
    } catch (failure: Uncheckable) {
        FidelityRequirementState.REVIEW_REQUIRED to failure.code
    }
    private fun retry(check: RetryRegion): Boolean {
        val expected = selected(check.operations) ?: return false
        val ancestry = ancestry(expected.first())
        val region = ancestry.firstOrNull() as? CanonicalRetryNode ?: return false
        // No additional retry multiplication, conditional activation or hidden failure recovery.
        if (ancestry.drop(1).any { it !is CanonicalTryNode } || ancestry.count { it is CanonicalTryNode } > 1) review()
        val workflow = workflows.getValue(region.workflow)
        if (ancestry.last().id !in workflow.rootNodeIds || workflow.failurePolicy.handler != null) review()
        val actual = operations(region.bodyNodeIds, false)
        if (actual.any { !readOnly(nodes.getValue(it)) }) review()
        return actual == expected && region.max == check.maxAttempts && region.delay == "${check.delaySeconds}s" && region.backoff == "fixed"
    }
    private fun failure(check: FailureRegion): Boolean {
        val protected = selected(check.protected) ?: return false
        val handler = selected(check.handler) ?: return false
        val ancestors = ancestry(protected.first())
        val region = ancestors.firstOrNull { it is CanonicalTryNode } as? CanonicalTryNode ?: return false
        // Certify root operation regions only; no inference about conditional/nested failure paths.
        val workflow = workflows.getValue(region.workflow)
        if (parents[region.id] != null || region.id !in workflow.rootNodeIds || workflow.failurePolicy.handler != null) review()
        val actualProtected = operations(region.bodyNodeIds, true)
        val last = region.errorHandlerNodeIds.lastOrNull()?.let(nodes::getValue)
        val hasFail = last is CanonicalControlOperationNode && last.operation == CanonicalControlOperationKind.FAIL
        val actualHandler = operations(if (hasFail) region.errorHandlerNodeIds.dropLast(1) else region.errorHandlerNodeIds, true)
        if (actualHandler.any { "error" in nodes.getValue(it).semantics.outputs }) review()
        val dispositionMatches = when (check.disposition) {
            RequiredFailureDisposition.PROPAGATE -> hasFail && (last as CanonicalControlOperationNode).detail == "error"
            RequiredFailureDisposition.RECOVER -> !hasFail
        }
        return actualProtected == protected && actualHandler == handler && dispositionMatches
    }
}
