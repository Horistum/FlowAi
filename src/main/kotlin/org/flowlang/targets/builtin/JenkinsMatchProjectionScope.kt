package org.flowlang.targets.builtin

import org.flowlang.ast.BooleanLiteralNode
import org.flowlang.ast.StringLiteralNode
import org.flowlang.capabilities.SupportLevel
import org.flowlang.capabilities.TargetCapability
import org.flowlang.compiler.CanonicalMatchNode
import org.flowlang.compiler.CompilationAuthorization
import org.flowlang.generators.manifest.TargetNativeProjectionCatalog
import org.flowlang.generators.manifest.TargetProjectionCapabilityResolver
import org.flowlang.generators.manifest.TargetStructuralProjectionKind
import org.flowlang.generators.manifest.TargetStructuralProjectionSupportScope
import org.flowlang.generators.manifest.TargetStep
import org.flowlang.parser.ExpressionParser
import org.flowlang.planner.ConditionNode
import org.flowlang.planner.MatchPlanNode
import org.flowlang.planner.PlanDependencyRelations
import org.flowlang.planner.PlanNode
import org.flowlang.planner.TaskNode

/** Disjoint literal cases only: no inferred predicate precedence or implicit-result binding. */
class JenkinsMatchProjectionScope(private val catalog: TargetNativeProjectionCatalog?) : TargetProjectionCapabilityResolver {
    override fun resolve(authorization: CompilationAuthorization, target: String, declared: TargetCapability): TargetCapability {
        authorization.requireIntegrity()
        if (target != "jenkins" || declared.target != target || catalog == null || catalog.target != target ||
            catalog.structuralDefinitions.none { it.structure == TargetStructuralProjectionKind.MATCH &&
                it.kind == JenkinsProjectionPayloadKinds.JENKINS_STRUCTURE && it.reference == "literal-match" &&
                it.supportScope == TargetStructuralProjectionSupportScope.PLAN_SCOPED }) return declared
        val matches = authorization.graph.nodes.filterIsInstance<CanonicalMatchNode>()
        val bound = PlanDependencyRelations.flatten(authorization.executionPlan.nodes).filterIsInstance<MatchPlanNode>()
        if (matches.isEmpty() || matches.size != bound.size || bound.any { !supportedMatch(it) }) return declared
        return declared.copy(features = declared.features + ("match.basic" to SupportLevel.SUPPORTED),
            notes = declared.notes + "Match preserves exclusive literal selection across independently materialized tasks, conditions and nested matches; string-literal sources and empty error branches only, with no implicit-result or branch-output continuity claim.")
    }

    private fun supportedMatch(node: MatchPlanNode): Boolean =
        supports(node.source, node.cases.map { it.condition }) && node.errorCase.isEmpty() &&
            node.cases.all { supportedBody(it.steps) } && supportedBody(node.defaultSteps)

    private fun supportedBody(nodes: List<PlanNode>): Boolean = nodes.all { node -> when (node) {
        is TaskNode -> true // Leaf payloads, value bindings and continuity have independent owners.
        is ConditionNode -> supportedBody(node.then) && supportedBody(node.otherwise)
        is MatchPlanNode -> supportedMatch(node)
        else -> false
    } }

    companion object {
        /** Renderer callers must preserve the same sequential control boundary. */
        internal fun supportsRenderedBody(steps: List<TargetStep>): Boolean = steps.all { step -> when (step.type) {
            "action" -> step.children.isEmpty()
            "condition" -> supportsRenderedBody(step.children)
            "match" -> supportsRenderedMatch(step)
            else -> false
        } }

        internal fun supportsRenderedMatch(step: TargetStep): Boolean {
            val cases = step.children.takeWhile { it.type == "match-case" }
            return step.params.keys == setOf("source") &&
                step.children.map { it.type } == List(cases.size) { "match-case" } + listOf("match-error", "match-default") &&
                cases.all { it.params.keys == setOf("condition") } &&
                supports(step.params.getValue("source"), cases.map { it.params.getValue("condition") }) &&
                step.children[cases.size].children.isEmpty() && step.children.all { supportsRenderedBody(it.children) }
        }

        fun supports(source: String, conditions: List<String>): Boolean = runCatching {
            ExpressionParser.parseSource(source) is StringLiteralNode && conditions.isNotEmpty() &&
                conditions.map { ExpressionParser.parseSource(it) }.let { guards ->
                    guards.all { it is BooleanLiteralNode } && guards.count { (it as BooleanLiteralNode).value } <= 1
                }
        }.getOrDefault(false)
    }
}
