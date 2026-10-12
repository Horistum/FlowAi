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
import org.flowlang.parser.ExpressionParser
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
        if (matches.isEmpty() || matches.size != bound.size || bound.any {
            !supports(it.source, it.cases.map { case -> case.condition }) || it.errorCase.isNotEmpty() ||
                !it.cases.all { case -> checkoutBody(case.steps) } || !checkoutBody(it.defaultSteps)
        }) return declared
        return declared.copy(features = declared.features + ("match.basic" to SupportLevel.SUPPORTED),
            notes = declared.notes + "Match supports string-literal sources, disjoint literal boolean cases, empty error branches and native checkout bodies only; no implicit-result or output-continuity claim.")
    }

    private fun checkoutBody(nodes: List<PlanNode>): Boolean = nodes.all { it is TaskNode && it.module == "git" && it.action == "checkout" }

    companion object {
        fun supports(source: String, conditions: List<String>): Boolean = runCatching {
            ExpressionParser.parseSource(source) is StringLiteralNode && conditions.isNotEmpty() &&
                conditions.map { ExpressionParser.parseSource(it) }.let { guards ->
                    guards.all { it is BooleanLiteralNode } && guards.count { (it as BooleanLiteralNode).value } <= 1
                }
        }.getOrDefault(false)
    }
}
