package org.flowlang.generators

import org.flowlang.planner.*

/** Generates a GitHub Actions workflow *draft* from an execution plan. */
@Deprecated("Use manifest generators/renderers. Legacy draft generator is kept only for backward-compatibility tests.")
class GitHubActionsGenerator {
    fun generate(plan: ExecutionPlan): String {
        val sb = StringBuilder()
        sb.appendLine("# Generated draft for flow '${plan.flowName}'")
        sb.appendLine("name: ${yaml(plan.flowName)}")
        sb.appendLine("on: [workflow_dispatch]")
        sb.appendLine("jobs:")
        sb.appendLine("  flow:")
        sb.appendLine("    runs-on: ubuntu-latest")
        sb.appendLine("    steps:")
        plan.tasks.forEach { task ->
            sb.appendLine("      - name: ${yaml(task.id)}")
            sb.appendLine("        run: echo ${yaml("${task.module}.${task.action} -> ${task.target}")}")
            if (task.dependsOn.isNotEmpty())
                sb.appendLine("        # depends on: ${task.dependsOn.joinToString(", ")}")
            if (task.destructive)
                sb.appendLine("        # destructive; safety=${task.safety ?: "none"}")
        }
        return sb.toString()
    }

    private fun yaml(s: String): String = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}
