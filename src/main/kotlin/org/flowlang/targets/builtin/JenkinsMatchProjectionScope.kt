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
import org.flowlang.planner.MatchPlanNode
import org.flowlang.planner.PlanDependencyRelations

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
        val inputs = authorization.executionPlan.inputs.map { it.name }.toSet()
        if (matches.isEmpty() || matches.size != bound.size ||
            bound.any { !JenkinsSequentialControlScope.supportsBody(listOf(it), inputs) }) return declared
        return declared.copy(features = declared.features + ("match.basic" to SupportLevel.SUPPORTED),
            notes = declared.notes + "Match preserves exclusive literal selection across independently materialized tasks, conditions, fixed zero-delay retries, finite string loops and nested matches; string-literal sources and empty error branches only, with no implicit-result or branch-output continuity claim.")
    }

    companion object {
        /** Selection wrappers own no bindings; every branch inherits the same lexical scope. */
        internal fun supportsRenderedMatch(step: TargetStep, bindings: Set<String>): Boolean {
            val cases = step.children.takeWhile { it.type == "match-case" }
            return step.params.keys == setOf("source") &&
                step.children.map { it.type } == List(cases.size) { "match-case" } + listOf("match-error", "match-default") &&
                cases.all { it.params.keys == setOf("condition") } &&
                step.children.drop(cases.size).all { it.params.isEmpty() } &&
                supports(step.params.getValue("source"), cases.map { it.params.getValue("condition") }) &&
                step.children[cases.size].children.isEmpty() &&
                step.children.all { JenkinsSequentialControlScope.supportsRenderedBody(it.children, bindings) }
        }

        fun supports(source: String, conditions: List<String>): Boolean = runCatching {
            ExpressionParser.parseSource(source) is StringLiteralNode && conditions.isNotEmpty() &&
                conditions.map { ExpressionParser.parseSource(it) }.let { guards ->
                    guards.all { it is BooleanLiteralNode } && guards.count { (it as BooleanLiteralNode).value } <= 1
                }
        }.getOrDefault(false)
    }
}
