package org.flowlang.targets.builtin

import org.flowlang.capabilities.SupportLevel
import org.flowlang.capabilities.TargetCapability
import org.flowlang.compiler.CompilationAuthorization
import org.flowlang.generators.manifest.TargetNativeProjectionCatalog
import org.flowlang.generators.manifest.TargetProjectionCapabilityResolver
import org.flowlang.generators.manifest.TargetStructuralProjectionKind
import org.flowlang.generators.manifest.TargetStructuralProjectionSupportScope
import org.flowlang.planner.ConditionNode
import org.flowlang.planner.TaskNode

/** A single literal guard needs no input translation, branch join or successor scheduling. */
class GitHubLiteralConditionProjectionScope(private val catalog: TargetNativeProjectionCatalog?) : TargetProjectionCapabilityResolver {
    override fun resolve(authorization: CompilationAuthorization, target: String, declared: TargetCapability): TargetCapability {
        authorization.requireIntegrity()
        if (target != "github-actions" || declared.target != target || catalog == null || catalog.target != target ||
            catalog.structuralDefinitions.none { it.structure == TargetStructuralProjectionKind.CONDITION &&
                it.kind == "github-job-condition" && it.reference == "if" &&
                it.supportScope == TargetStructuralProjectionSupportScope.PLAN_SCOPED }) return declared
        val plan = authorization.executionPlan
        if (plan.inputs.isNotEmpty() || plan.outputs.isNotEmpty() || plan.triggers.isNotEmpty() || plan.dependencies.isNotEmpty() ||
            authorization.graph.workflows.size != 1 || authorization.graph.workflows.single().failurePolicy.handler != null) return declared
        val condition = plan.nodes.singleOrNull() as? ConditionNode ?: return declared
        if (condition.condition !in setOf("true", "false") || condition.otherwise.isNotEmpty()) return declared
        val task = condition.then.singleOrNull() as? TaskNode ?: return declared
        if (task.module != "git" || task.action != "checkout" || task.dependsOn.isNotEmpty() || task.dependencies.isNotEmpty()) return declared
        return declared.copy(features = declared.features + mapOf("condition.evaluate" to SupportLevel.SUPPORTED,
            "conditions.inline" to SupportLevel.SUPPORTED),
            notes = declared.notes + "One literal Boolean job guard around one native checkout only; no inputs, else branch, nesting, joins or successor scheduling claim.")
    }
}
