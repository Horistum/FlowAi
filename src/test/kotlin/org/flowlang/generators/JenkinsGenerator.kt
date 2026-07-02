package org.flowlang.generators

import org.flowlang.ast.*
import org.flowlang.modules.ModuleRegistry
import org.flowlang.planner.*

/**
 * Generates a Jenkins declarative pipeline.
 *
 * The primary entry point, [generate](document, registry), works from the Flow AST
 * (full fidelity: parameters, vars, systems, action params, safety, result handlers,
 * control flow). The pipeline shell is declarative; the flow body is emitted as
 * scripted Groovy inside `stage(...) { steps { script { ... } } }`, which is the robust
 * way to represent if/for/parallel/match/retry/try without fighting declarative limits.
 *
 * Action steps are mapped to real Jenkins steps where a natural mapping exists
 * (`sh`, `git`, `httpRequest`, `input`); others render as `sh`/`echo` with the intent.
 * `expect { }` rules are emitted as comments (their result-field semantics need the
 * runtime result object, which is out of scope for this generator phase).
 */
@Deprecated("Use manifest generators/renderers. Legacy draft generator is kept only for backward-compatibility tests.")
class JenkinsGenerator {

    // ---------------------------------------------------------------- AST-based

    fun generate(document: FlowDocument, registry: ModuleRegistry = ModuleRegistry()): String {
        val flow = document.flow
        val ctx = Ctx(
            sb = StringBuilder(),
            gx = GroovyExpr(flow.input.map { it.name }.toSet()),
            systems = flow.systems.associateBy { it.name },
            registry = registry
        )
        val sb = ctx.sb
        sb.appendLine("// Generated Jenkins pipeline for flow '${flow.name}'")
        sb.appendLine("pipeline {")
        sb.appendLine("  agent any")
        sb.appendLine("  options { timestamps() }")
        emitParameters(flow, sb)
        emitEnvironment(flow, sb)
        sb.appendLine("  stages {")
        sb.appendLine("    stage('${esc(flow.name)}') {")
        sb.appendLine("      steps {")
        sb.appendLine("        script {")
        var indent = 5
        flow.vars.forEach { v ->
            line(sb, indent, "def ${v.name} = ${ctx.gx.render(v.value)}")
        }
        val hasErr = flow.errorHandler != null
        if (hasErr) { line(sb, indent, "try {"); indent++ }
        flow.steps.forEach { emitTopLevel(it, indent, ctx) }
        if (hasErr) {
            indent--
            line(sb, indent, "} catch (flowError) {")
            line(sb, indent + 1, "def error = [message: flowError.getMessage()]")
            flow.errorHandler!!.steps.forEach { emit(it, indent + 1, ctx) }
            line(sb, indent + 1, "throw flowError")
            line(sb, indent, "}")
        }
        sb.appendLine("        }")
        sb.appendLine("      }")
        sb.appendLine("    }")
        sb.appendLine("  }")
        sb.appendLine("}")
        return sb.toString()
    }

    private fun emitParameters(flow: FlowNode, sb: StringBuilder) {
        if (flow.input.isEmpty()) return
        sb.appendLine("  parameters {")
        for (inp in flow.input) {
            val name = inp.name
            val def = inp.default
            when (inp.valueType.kind) {
                "boolean" -> sb.appendLine("    booleanParam(name: '${esc(name)}', defaultValue: ${(def as? BooleanLiteralNode)?.value ?: false})")
                "option" -> {
                    val choices = inp.valueType.values.filterIsInstance<StringLiteralNode>().joinToString(", ") { "'${esc(it.value)}'" }
                    sb.appendLine("    choice(name: '${esc(name)}', choices: [$choices])")
                }
                else -> {
                    val dv = when (def) {
                        is StringLiteralNode -> def.value
                        is NumberLiteralNode -> if (def.isInteger) def.value.toLong().toString() else def.value.toString()
                        else -> ""
                    }
                    sb.appendLine("    string(name: '${esc(name)}', defaultValue: '${esc(dv)}')")
                }
            }
        }
        sb.appendLine("  }")
    }

    /** Bind every secret referenced in system config as a credentials() environment var. */
    private fun emitEnvironment(flow: FlowNode, sb: StringBuilder) {
        val secrets = LinkedHashSet<String>()
        flow.systems.forEach { s -> s.config.values.forEach { collectSecrets(it, secrets) } }
        if (secrets.isEmpty()) return
        sb.appendLine("  environment {")
        secrets.forEach { sb.appendLine("    $it = credentials('$it')") }
        sb.appendLine("  }")
    }

    private fun collectSecrets(e: ExpressionNode, into: MutableSet<String>) {
        when (e) {
            is SecretRefNode -> into += e.name
            is TemplateStringNode -> e.parts.forEach { collectSecrets(it, into) }
            is BinaryExpressionNode -> { collectSecrets(e.left, into); collectSecrets(e.right, into) }
            is LogicalExpressionNode -> e.operands.forEach { collectSecrets(it, into) }
            is ListLiteralNode -> e.items.forEach { collectSecrets(it, into) }
            is MapLiteralNode -> e.entries.values.forEach { collectSecrets(it, into) }
            is CallExpressionNode -> e.args.forEach { collectSecrets(it, into) }
            else -> Unit
        }
    }

    /** Top-level statement, wrapped in a scripted stage(...) for visualization. */
    private fun emitTopLevel(stmt: StatementNode, indent: Int, ctx: Ctx) {
        val label = stageLabel(stmt)
        if (label == null) { emit(stmt, indent, ctx); return }
        line(ctx.sb, indent, "stage('${esc(label)}') {")
        emit(stmt, indent + 1, ctx)
        line(ctx.sb, indent, "}")
    }

    private fun stageLabel(stmt: StatementNode): String? = when (stmt) {
        is ActionNode -> "${stmt.module}.${stmt.action}"
        is IfNode -> "if"
        is ForNode -> "for ${stmt.item}"
        is ParallelNode -> "parallel"
        is MatchNode -> "match"
        is RetryNode -> "retry"
        is TryNode -> "try"
        is ApproveNode -> "approve"
        is TransformNode -> "transform ${stmt.target}"
        is AggregateNode -> "aggregate ${stmt.target}"
        is ValidateNode -> "validate"
        else -> null
    }

    private fun emit(stmt: StatementNode, indent: Int, ctx: Ctx) {
        val sb = ctx.sb
        when (stmt) {
            is ActionNode -> emitAction(stmt, indent, ctx)
            is IfNode -> {
                line(sb, indent, "if (${ctx.gx.render(stmt.condition)}) {")
                stmt.then.forEach { emit(it, indent + 1, ctx) }
                if (stmt.otherwise.isNotEmpty()) {
                    line(sb, indent, "} else {")
                    stmt.otherwise.forEach { emit(it, indent + 1, ctx) }
                }
                line(sb, indent, "}")
            }
            is ForNode -> {
                line(sb, indent, "for (${stmt.item} in ${ctx.gx.render(stmt.source)}) {")
                stmt.body.forEach { emit(it, indent + 1, ctx) }
                line(sb, indent, "}")
            }
            is ParallelNode -> {
                line(sb, indent, "parallel(")
                stmt.branches.forEachIndexed { i, b ->
                    val name = b.name ?: "branch_${i + 1}"
                    line(sb, indent + 1, "'${esc(name)}': {")
                    b.steps.forEach { emit(it, indent + 2, ctx) }
                    line(sb, indent + 1, "}${if (i < stmt.branches.size - 1) "," else ""}")
                }
                line(sb, indent, ")")
            }
            is MatchNode -> {
                val hasErr = stmt.errorCase != null
                if (hasErr) line(sb, indent, "try {")
                val base = if (hasErr) indent + 1 else indent
                stmt.cases.forEachIndexed { i, c ->
                    val kw = if (i == 0) "if" else "} else if"
                    line(sb, base, "$kw (${ctx.gx.render(c.condition ?: BooleanLiteralNode(value = true))}) {")
                    c.steps.forEach { emit(it, base + 1, ctx) }
                }
                if (stmt.defaultSteps.isNotEmpty()) {
                    line(sb, base, "} else {")
                    stmt.defaultSteps.forEach { emit(it, base + 1, ctx) }
                }
                if (stmt.cases.isNotEmpty() || stmt.defaultSteps.isNotEmpty()) line(sb, base, "}")
                if (hasErr) {
                    line(sb, indent, "} catch (matchError) {")
                    line(sb, indent + 1, "def error = [message: matchError.getMessage()]")
                    stmt.errorCase!!.forEach { emit(it, indent + 1, ctx) }
                    line(sb, indent, "}")
                }
            }
            is RetryNode -> {
                line(sb, indent, "retry(${stmt.policy.max}) {")
                if (stmt.policy.delay != "0s" && stmt.policy.backoff != "none") line(sb, indent + 1, "// delay=${stmt.policy.delay}, backoff=${stmt.policy.backoff}")
                stmt.steps.forEach { emit(it, indent + 1, ctx) }
                line(sb, indent, "}")
            }
            is TryNode -> {
                line(sb, indent, "try {")
                stmt.steps.forEach { emit(it, indent + 1, ctx) }
                line(sb, indent, "} catch (tryError) {")
                line(sb, indent + 1, "def error = [message: tryError.getMessage()]")
                stmt.errorHandler.steps.forEach { emit(it, indent + 1, ctx) }
                line(sb, indent, "}")
            }
            is SetNode -> line(sb, indent, "def ${stmt.name} = ${ctx.gx.render(stmt.value)}")
            is FailNode -> line(sb, indent, "error(${ctx.gx.render(stmt.message)})")
            is SkipNode -> { line(sb, indent, "echo 'SKIP: ' + ${ctx.gx.render(stmt.message)}"); line(sb, indent, "return") }
            is ApproveNode -> {
                val msg = stmt.params["message"]?.let { ctx.gx.render(it) } ?: "'Approval required'"
                val assign = stmt.result?.let { "def ${it.name} = " } ?: ""
                line(sb, indent, "${assign}input(message: $msg)")
            }
            is TransformNode -> line(sb, indent, "// transform ${ctx.flowRender(stmt.source)} -> ${stmt.target}")
            is AggregateNode -> line(sb, indent, "// aggregate ${ctx.flowRender(stmt.source)} -> ${stmt.target}")
            is ValidateNode -> stmt.rules.forEach { r ->
                val expr = r.reference ?: r.expression
                if (expr != null) line(sb, indent, "// validate: ${ctx.flowRender(expr)}")
            }
            is ExpectNode -> stmt.expressions.forEach { line(sb, indent, "// expect: ${ctx.flowRender(it)}") }
            is ErrorHandlerNode -> stmt.steps.forEach { emit(it, indent, ctx) }
        }
    }

    private fun emitAction(a: ActionNode, indent: Int, ctx: Ctx) {
        val sb = ctx.sb
        val onlyIf = a.safety?.takeIf { it.rule == "onlyIf" }?.condition
        val requiresApproval = a.safety?.rule == "requiresApproval"
        var ind = indent
        if (onlyIf != null) { line(sb, ind, "if (${ctx.gx.render(onlyIf)}) {"); ind++ }
        if (requiresApproval) line(sb, ind, "input(message: 'Approve ${esc(a.module)}.${esc(a.action)} on ${esc(a.target.path.joinToString("."))}?')")

        val hasErrHandler = a.handler?.rules?.any { it is WhenNode && it.isError } == true
        if (hasErrHandler) { line(sb, ind, "try {"); ind++ }

        emitActionCall(a, ind, ctx)

        a.handler?.rules?.filterIsInstance<ExpectNode>()?.forEach { ex ->
            ex.expressions.forEach { line(sb, ind, "// expect: ${ctx.flowRender(it)}") }
        }
        if (hasErrHandler) {
            ind--
            line(sb, ind, "} catch (stepError) {")
            line(sb, ind + 1, "def error = [message: stepError.getMessage()]")
            a.handler!!.rules.filterIsInstance<WhenNode>().filter { it.isError }.forEach { w ->
                w.steps.forEach { emit(it, ind + 1, ctx) }
            }
            line(sb, ind, "}")
        }
        if (onlyIf != null) { ind--; line(sb, ind, "}") }
    }

    private fun emitActionCall(a: ActionNode, indent: Int, ctx: Ctx) {
        val sb = ctx.sb
        val cfg = ctx.systems[a.target.path.firstOrNull()]?.config ?: emptyMap()
        fun p(name: String): String? = a.params[name]?.let { ctx.gx.render(it) }
        fun cfgv(name: String): String? = cfg[name]?.let { ctx.gx.render(it) }
        val assign = a.result?.let { "def ${it.name} = " } ?: ""

        val call: String = when ("${a.module}.${a.action}") {
            "shell.run" -> "sh(script: ${p("command") ?: "''"}, returnStdout: true).trim()"
            "git.checkout" -> {
                val url = cfgv("url") ?: "''"
                val branch = p("branch") ?: cfgv("branch") ?: "'main'"
                "git(url: $url, branch: $branch)"
            }
            "rest.call" -> {
                val base = cfgv("baseUrl")
                val path = p("path") ?: "''"
                val url = if (base != null) "$base + $path" else path
                val method = (a.params["method"] as? IdentifierLiteralNode)?.value
                    ?: (a.params["method"] as? StringLiteralNode)?.value ?: "GET"
                "httpRequest(url: $url, httpMode: '${esc(method)}', validResponseCodes: '100:599')"
            }
            "docker.build" -> "sh(script: \"docker build -t \" + ${p("image") ?: "''"} + \" \" + ${p("path") ?: "'.'"})"
            "helm.template" -> "sh(script: \"helm template \" + ${p("chart") ?: "''"})"
            "helm.upgrade" -> "sh(script: \"helm upgrade --install \" + ${p("release") ?: "''"} + \" \" + ${p("chart") ?: "''"})"
            "argocd.sync" -> "sh(script: \"argocd app sync \" + ${p("app") ?: "''"})"
            "kubernetes.get" -> "sh(script: \"kubectl get \" + ${p("resource") ?: "''"} + ${cfgNs(p("namespace"))}, returnStdout: true).trim()"
            "kubernetes.deploy" -> "sh(script: \"kubectl apply -f - \" + ${cfgNs(p("namespace"))})"
            "kubernetes.delete" -> "sh(script: \"kubectl delete \" + ${p("resource") ?: "''"} + \" \" + ${p("name") ?: "''"} + ${cfgNs(p("namespace"))})"
            "database.query" -> "sh(script: \"# db query: \" + ${p("sql") ?: "''"}, returnStdout: true).trim()"
            "database.upsert" -> "echo('db upsert into ' + ${p("table") ?: "''"})"
            "database.delete" -> "echo('db delete from ' + ${p("table") ?: "''"})"
            "notify.send" -> "echo('NOTIFY: ' + ${p("subject") ?: "''"})"
            else -> "echo('${esc(a.module)}.${esc(a.action)} on ${esc(a.target.path.joinToString("."))}')"
        }
        line(sb, indent, "$assign$call")
    }

    private fun cfgNs(ns: String?): String = if (ns != null) "\" -n \" + $ns" else "''"

    private fun line(sb: StringBuilder, indent: Int, text: String) {
        sb.append("  ".repeat(indent)).append(text).append('\n')
    }

    private class Ctx(
        val sb: StringBuilder,
        val gx: GroovyExpr,
        val systems: Map<String, SystemNode>,
        val registry: ModuleRegistry
    ) {
        fun flowRender(e: ExpressionNode): String = ExpressionRenderer.render(e).replace("*/", "* /")
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
