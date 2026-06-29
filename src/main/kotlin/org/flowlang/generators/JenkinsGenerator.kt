package org.flowlang.generators

import org.flowlang.ast.FlowDocument
import org.flowlang.capabilities.CompatibilityReport
import org.flowlang.capabilities.SupportLevel
import org.flowlang.generators.manifest.JenkinsManifestGenerator
import org.flowlang.generators.manifest.JenkinsManifestRenderer
import org.flowlang.modules.ModuleRegistry
import org.flowlang.planner.*

/**
 * Backward-compatible facade for callers that still use the legacy Jenkins
 * generator entry point.
 *
 * AST generation delegates to the canonical FlowPlanner -> TargetManifest ->
 * Jenkins renderer path. The plan-based overload keeps the older draft output
 * shape so existing tests and callers do not lose a public entry point merely
 * because humans decided duplication was annoying.
 */
@Deprecated("Use FlowPlanner plus JenkinsManifestGenerator/JenkinsManifestRenderer directly.")
class JenkinsGenerator {
    fun generate(document: FlowDocument, registry: ModuleRegistry = ModuleRegistry()): String {
        val plan = FlowPlanner(registry).plan(document)
        val manifest = JenkinsManifestGenerator().generate(
            plan,
            CompatibilityReport(target = "jenkins", status = SupportLevel.SUPPORTED)
        )
        return JenkinsManifestRenderer().render(manifest)
    }

    fun generate(plan: ExecutionPlan): String {
        val sb = StringBuilder()
        sb.appendLine("// Generated draft for flow '${plan.flowName}'")
        sb.appendLine("pipeline {")
        sb.appendLine("  agent any")
        sb.appendLine("  stages {")
        plan.nodes.forEach { renderNode(it, sb, 2) }
        sb.appendLine("  }")
        sb.appendLine("}")
        return sb.toString()
    }

    private fun renderNode(node: PlanNode, sb: StringBuilder, depth: Int) {
        val pad = "  ".repeat(depth)
        when (node) {
            is TaskNode -> {
                sb.appendLine("$pad stage('${esc(node.id)}') {")
                sb.appendLine("$pad   steps { echo '${esc(node.module)}.${esc(node.action)} -> ${esc(node.target)}' }")
                if (node.destructive) sb.appendLine("$pad   // destructive; safety=${esc(node.safety ?: "none")}")
                sb.appendLine("$pad }")
            }
            is ConditionNode -> {
                sb.appendLine("$pad stage('${esc(node.id)}') {")
                sb.appendLine("$pad   when { expression { /* ${esc(node.condition)} */ true } }")
                sb.appendLine("$pad   stages {")
                node.then.forEach { renderNode(it, sb, depth + 2) }
                sb.appendLine("$pad   }")
                sb.appendLine("$pad }")
                if (node.otherwise.isNotEmpty()) node.otherwise.forEach { renderNode(it, sb, depth) }
            }
            is ParallelGroupNode -> {
                sb.appendLine("$pad stage('${esc(node.id)}') {")
                sb.appendLine("$pad   parallel {")
                node.branches.forEachIndexed { i, b ->
                    sb.appendLine("$pad     stage('${esc(b.name ?: "branch_$i")}') { stages {")
                    b.steps.forEach { renderNode(it, sb, depth + 4) }
                    sb.appendLine("$pad     } }")
                }
                sb.appendLine("$pad   }")
                sb.appendLine("$pad }")
            }
            is LoopNode -> {
                sb.appendLine("$pad stage('${esc(node.id)}') { /* for ${esc(node.item)} in ${esc(node.source)} */ steps {")
                node.body.forEach { renderNode(it, sb, depth + 2) }
                sb.appendLine("$pad } }")
            }
            is RetryGroupNode -> {
                sb.appendLine("$pad stage('${esc(node.id)}') { steps { retry(${node.max}) {")
                node.body.forEach { renderNode(it, sb, depth + 2) }
                sb.appendLine("$pad } } }")
            }
            is TryPlanNode -> {
                sb.appendLine("$pad stage('${esc(node.id)}') { steps { script { try {")
                node.body.forEach { renderNode(it, sb, depth + 2) }
                sb.appendLine("$pad } catch (e) {")
                node.errorHandler.forEach { renderNode(it, sb, depth + 2) }
                sb.appendLine("$pad } } } }")
            }
            is MatchPlanNode -> {
                sb.appendLine("$pad stage('${esc(node.id)}') { /* match ${esc(node.source)} */ }")
                node.cases.forEach { c -> c.steps.forEach { renderNode(it, sb, depth) } }
            }
            is ApprovalNode -> sb.appendLine("$pad stage('${esc(node.id)}') { steps { input message: '${esc(node.message ?: "Approve?")}' } }")
            is DataOpNode -> sb.appendLine("$pad stage('${esc(node.id)}') { /* ${esc(node.kind)} ${esc(node.detail ?: "")} */ }")
            is ControlNode -> sb.appendLine("$pad // ${esc(node.kind)}: ${esc(node.detail ?: "")}")
        }
    }

    private fun esc(s: String): String = s.replace("\\", "\\\\").replace("'", "\\'")
}
