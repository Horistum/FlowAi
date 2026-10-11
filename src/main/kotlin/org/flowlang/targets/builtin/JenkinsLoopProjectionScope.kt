package org.flowlang.targets.builtin

import org.flowlang.ast.ListLiteralNode
import org.flowlang.ast.StringLiteralNode
import org.flowlang.capabilities.SupportLevel
import org.flowlang.capabilities.TargetCapability
import org.flowlang.compiler.CanonicalLoopNode
import org.flowlang.compiler.CompilationAuthorization
import org.flowlang.generators.manifest.TargetNativeProjectionCatalog
import org.flowlang.generators.manifest.TargetProjectionCapabilityResolver
import org.flowlang.generators.manifest.TargetStructuralProjectionKind
import org.flowlang.generators.manifest.TargetStructuralProjectionSupportScope
import org.flowlang.parser.ExpressionParser
import org.flowlang.planner.ConditionNode
import org.flowlang.planner.LoopNode
import org.flowlang.planner.PlanDependencyRelations
import org.flowlang.planner.PlanNode
import org.flowlang.planner.TaskNode

/** Finite sequential iteration owned by the adapter, with no generic support promotion. */
class JenkinsLoopProjectionScope(private val catalog: TargetNativeProjectionCatalog?) : TargetProjectionCapabilityResolver {
    override fun resolve(authorization: CompilationAuthorization, target: String, declared: TargetCapability): TargetCapability {
        authorization.requireIntegrity()
        if (target != "jenkins" || declared.target != target || catalog == null || catalog.target != target ||
            catalog.structuralDefinitions.none { it.structure == TargetStructuralProjectionKind.LOOP &&
                it.kind == JenkinsProjectionPayloadKinds.JENKINS_STRUCTURE && it.reference == "for-in" &&
                it.supportScope == TargetStructuralProjectionSupportScope.PLAN_SCOPED }) return declared
        val loops = authorization.graph.nodes.filterIsInstance<CanonicalLoopNode>()
        val bound = PlanDependencyRelations.flatten(authorization.executionPlan.nodes).filterIsInstance<LoopNode>()
        val inputs = authorization.executionPlan.inputs.map { it.name }.toSet()
        if (loops.isEmpty() || loops.size != bound.size || loops.any {
            !safeItem(it.item) || it.item in inputs || literalItems(it.source) == null
        } || bound.any { !supportedBody(it.body) }) return declared
        return declared.copy(features = declared.features + mapOf("loop.dynamic" to SupportLevel.SUPPORTED,
            "loops.dynamic" to SupportLevel.SUPPORTED),
            notes = declared.notes + "Sequential string-literal-list loops support native checkout and conditions only; no dynamic collections, nested loops or loop-output continuity claim.")
    }

    private fun supportedBody(nodes: List<PlanNode>): Boolean = nodes.isNotEmpty() && nodes.all { node -> when (node) {
        is TaskNode -> node.module == "git" && node.action == "checkout"
        is ConditionNode -> (node.then.isNotEmpty() || node.otherwise.isNotEmpty()) &&
            (node.then.isEmpty() || supportedBody(node.then)) && (node.otherwise.isEmpty() || supportedBody(node.otherwise))
        else -> false
    } }

    companion object {
        private val reserved = ("abstract assert boolean break byte case catch char class const continue def default do double else enum extends final finally float for goto if implements import in instanceof int interface long native new package private protected public return short static strictfp super switch synchronized this throw throws trait transient try void volatile while true false null as record sealed permits var yield " +
            "params env currentBuild steps git checkout input error pipeline script node stage retry parallel sleep flowError").split(' ').toSet()

        fun safeItem(item: String): Boolean = Regex("[A-Za-z_][A-Za-z0-9_]*").matches(item) && item !in reserved

        fun literalItems(source: String): List<String>? = runCatching {
            val list = ExpressionParser.parseSource(source) as? ListLiteralNode ?: return null
            if (list.items.any { it !is StringLiteralNode }) return null
            list.items.map { (it as StringLiteralNode).value }
        }.getOrNull()
    }
}
