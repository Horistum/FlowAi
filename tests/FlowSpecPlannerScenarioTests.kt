import org.flowlang.ast.*
import org.flowlang.capabilities.CompatibilityAnalyzer
import org.flowlang.capabilities.TargetCapability
import org.flowlang.generators.manifest.*
import org.flowlang.targets.builtin.JenkinsManifestGenerator
import org.flowlang.targets.builtin.JenkinsManifestRenderer
import org.flowlang.targets.builtin.GitHubActionsManifestGenerator
import org.flowlang.targets.builtin.GitHubActionsManifestRenderer
import org.flowlang.targets.builtin.TektonManifestGenerator
import org.flowlang.targets.builtin.TektonManifestRenderer
import org.flowlang.parser.*
import org.flowlang.planner.*
import org.flowlang.validator.FlowValidator
import java.io.File

/* ============================ planner ============================ */
fun plannerTests() {
    fun plan(steps: String, imports: String = "use module \"shell\" version \"1.0\"", systems: String = "system \"l\" { type: shell }") =
        planSrc("$imports\nflow \"t\" { systems { $systems } steps { $steps } }")

    H.ok("plan/if-node", plan("if a == b { shell.run l { command: \"x\" } }", systems = "system \"l\" { type: shell }").nodes.any { it is ConditionNode })
    H.ok("plan/for-node", plan("for x in items { shell.run l { command: \"x\" } }").nodes.any { it is LoopNode })
    H.ok("plan/parallel-node", plan("parallel { branch \"a\" { shell.run l { command: \"x\" } } }").nodes.any { it is ParallelGroupNode })
    H.ok("plan/match-node", plan("match a { when a == 1 { shell.run l { command: \"x\" } } }").nodes.any { it is MatchPlanNode })
    H.ok("plan/retry-node", plan("retry { max: 2 } { shell.run l { command: \"x\" } }").nodes.any { it is RetryGroupNode })
    H.ok("plan/try-node", plan("try { shell.run l { command: \"x\" } } on error { skip \"y\" }").nodes.any { it is TryPlanNode })
    H.ok("plan/transform-node", plan("transform a.items -> out { select { id: item.id } }").nodes.any { it is DataOpNode && it.kind == "Transform" })
    H.ok("plan/aggregate-node", plan("aggregate items -> agg { total: count() }").nodes.any { it is DataOpNode && it.kind == "Aggregate" })

    H.eq("plan/flatten-if", plan("if a == b { shell.run l { command: \"x\" }\nshell.run l { command: \"y\" } }").tasks.size, 2)
    H.eq("plan/flatten-parallel", plan("parallel { branch \"a\" { shell.run l { command: \"x\" } } branch \"b\" { shell.run l { command: \"y\" } } }").tasks.size, 2)

    run {
        val p = plan("shell.run l { command: \"a\" } -> first\nshell.run l { command: first.text } -> second")
        val second = p.tasks.first { it.resultName == "second" }
        val first = p.tasks.first { it.resultName == "first" }
        H.ok("plan/dep-edge", second.dependsOn.contains(first.id))
    }
    run {
        val p = plan("shell.run l { command: \"a\" } -> first\nshell.run l { command: \"b\" } -> second")
        val second = p.tasks.first { it.resultName == "second" }
        H.ok("plan/no-false-dep", second.dependsOn.isEmpty())
    }
    run {
        val p = planSrc("""
            use module "kubernetes" version "1.0"
            flow "t" { systems { system "c" { type: kubernetes } } steps { kubernetes.delete c { resource: "ns"
            name: "x"
            safety: onlyIf true } -> d } }
        """)
        H.ok("plan/destructive", p.tasks.any { it.destructive && it.safety != null })
    }
    run {
        val p = plan("shell.run l { command: \"x\" }")
        H.ok("plan/effects", p.tasks[0].effects.any { it.contains("shell") })
    }
}

/* ============================ end-to-end examples ============================ */
fun exampleFile(name: String): File {
    val candidates = listOf("examples", ".", "/home/claude/flow-prod/examples", "/home/claude/flow-prod")
    return candidates.map { File(it, name) }.firstOrNull { it.exists() } ?: File("examples/$name")
}

fun endToEndTests() {
    val cases = listOf(
        Triple("api-sync.flow", 6, 3),
        Triple("build-test.flow", 2, 2),
        Triple("complex-devops-flow.flow", 8, 7),
        Triple("deploy-with-approval.flow", 5, 4),
        Triple("kubernetes-cleanup.flow", 2, 2)
    )
    for ((file, steps, systems) in cases) {
        val f = exampleFile(file)
        if (!f.exists()) { H.ok("e2e/$file/exists", false); continue }
        try {
            val d = FlowParser().parse(f)
            H.ok("e2e/$file/parse", true)
            H.eq("e2e/$file/steps", d.flow.steps.size, steps)
            H.eq("e2e/$file/systems", d.flow.systems.size, systems)
            val rep = FlowValidator().validate(d)
            H.ok("e2e/$file/valid", rep.valid)
            H.ok("e2e/$file/no-errors", rep.issues.none { it.level == "error" })
            val plan = FlowPlanner().plan(d)
            H.ok("e2e/$file/plan-tasks", plan.tasks.isNotEmpty())
        } catch (e: Exception) {
            H.ok("e2e/$file :: ${e.message}", false)
        }
    }
    run {
        val d = FlowParser().parse(exampleFile("complex-devops-flow.flow"))
        H.ok("e2e/complex/has-parallel", d.flow.steps.any { it is ParallelNode })
        H.ok("e2e/complex/has-if", d.flow.steps.any { it is IfNode })
        H.ok("e2e/complex/on-error", d.flow.errorHandler != null)
        val plan = FlowPlanner().plan(d)
        H.ok("e2e/complex/plan-parallel", plan.nodes.any { it is ParallelGroupNode })
    }
    run {
        val d = FlowParser().parse(exampleFile("api-sync.flow"))
        H.ok("e2e/api/has-transform", d.flow.steps.any { it is TransformNode })
        H.ok("e2e/api/has-validate", d.flow.steps.any { it is ValidateNode })
        H.ok("e2e/api/has-aggregate", d.flow.steps.any { it is AggregateNode })
        H.ok("e2e/api/has-for", d.flow.steps.any { it is ForNode })
    }
    run {
        val d = FlowParser().parse(exampleFile("kubernetes-cleanup.flow"))
        val del = d.flow.steps.filterIsInstance<ActionNode>().first { it.action == "delete" }
        H.ok("e2e/k8s/delete-safety", del.safety != null)
    }
    run {
        val d = FlowParser().parse(exampleFile("deploy-with-approval.flow"))
        val ifn = d.flow.steps.filterIsInstance<IfNode>().first()
        H.ok("e2e/deploy/approve-in-if", ifn.then.any { it is ApproveNode })
    }
}

private val projectionTargets = mapOf(
    "jenkins" to testTargetCapability(target = "jenkins", description = "test"),
    "github-actions" to testTargetCapability(target = "github-actions", description = "test"),
    "tekton" to testTargetCapability(target = "tekton", description = "test")
)

private fun manifestFor(plan: ExecutionPlan, target: String): TargetManifest {
    val compatibility = CompatibilityAnalyzer(projectionTargets).analyze(plan, target)
    return when (target) {
        "jenkins" -> JenkinsManifestGenerator().generate(plan, compatibility)
        "github-actions" -> GitHubActionsManifestGenerator().generate(plan, compatibility)
        "tekton" -> TektonManifestGenerator().generate(plan, compatibility)
        else -> error("Unsupported test target: $target")
    }
}

private fun renderManifest(manifest: TargetManifest): String = when (manifest.target) {
    "jenkins" -> JenkinsManifestRenderer().render(manifest)
    "github-actions" -> GitHubActionsManifestRenderer().render(manifest)
    "tekton" -> TektonManifestRenderer().render(manifest)
    else -> error("Unsupported test target: ${manifest.target}")
}

/* ============================ round-trip / renderer ============================ */
fun roundTripTests() {
    val samples = listOf(
        "a.b == c", "a and b or c", "not x.ok", "json.items not empty",
        "text not contains \"x\"", "a?.b?.c", "[1, 2, 3]", "{ a: 1 }",
        "count(where item.active == true)", "secret(\"S\")", "GET", "x in [1, 2]"
    )
    for (s in samples) {
        try {
            val e1 = expr(s)
            val r1 = ExpressionRenderer.render(e1)
            val e2 = ExpressionParser.parseSource(r1)
            val r2 = ExpressionRenderer.render(e2)
            H.eq("rt/$s", r2, r1)
        } catch (ex: Exception) {
            H.ok("rt/$s :: ${ex.message}", false)
        }
    }

    run {
        val plan = FlowPlanner().plan(FlowParser().parse(exampleFile("api-sync.flow")))
        for (target in projectionTargets.keys) {
            val rendered = renderManifest(manifestFor(plan, target))
            H.ok("gen/$target/review-kind", rendered.contains("kind: TargetProjectionReview"))
            H.ok("gen/$target/non-executable", rendered.contains("executable: false"))
            H.ok("gen/$target/no-shell-step", !rendered.contains("sh(script:") && !rendered.contains("run: |") && !rendered.contains("script: |"))
        }
    }
}

fun legacyProjectionBoundaryTests() {
    for (file in listOf("build-test.flow", "complex-devops-flow.flow")) {
        val plan = FlowPlanner().plan(FlowParser().parse(exampleFile(file)))
        for (target in projectionTargets.keys) {
            val manifest = manifestFor(plan, target)
            val readiness = TargetRenderPolicy.evaluate(manifest)
            H.eq("legacy/$file/$target/mode", readiness.mode, TargetRenderMode.FAIL_FAST)
            H.ok("legacy/$file/$target/shell-blocked", readiness.findings.any { it.status == TargetMaterializationStatus.BLOCKED.name })
            try {
                renderManifest(manifest)
                H.ok("legacy/$file/$target/render-blocked", false)
            } catch (e: TargetRenderBlockedException) {
                H.ok("legacy/$file/$target/render-blocked", e.readiness.mode == TargetRenderMode.FAIL_FAST)
            }
        }
    }
}

/* ============================ stress: many generated cases ============================ */
fun stressTests() {
    val ops = listOf("==", "!=", ">", ">=", "<", "<=")
    for (op in ops) {
        parseOk("stress/cmp-in-flow/$op", "use module \"shell\" version \"1.0\"\nflow \"t\" { systems { system \"l\" { type: shell } } steps { shell.run l { command: \"x\" } -> r { expect { code $op 0 } } } }")
    }
    for (op in ops + listOf("in", "contains", "matches", "startsWith", "endsWith")) {
        val r1 = ExpressionRenderer.render(expr("a.b $op c"))
        val r2 = ExpressionRenderer.render(ExpressionParser.parseSource(r1))
        H.eq("stress/op-render/$op", r2, r1)
    }
    for (depth in 1..30) {
        val open = (1..depth).joinToString(" ") { "if a$it == 1 {" }
        val close = "fail \"deep\" " + "}".repeat(depth)
        parseOk("stress/nested-if/$depth", "flow \"t\" { steps { $open $close } }")
    }
    for (n in 1..30) {
        val branches = (1..n).joinToString("\n") { "branch \"b$it\" { skip \"s\" }" }
        val p = firstStmt("parallel { $branches }") as ParallelNode
        H.eq("stress/parallel-branches/$n", p.branches.size, n)
    }
    for (n in 1..40) {
        val rep = validateSrc("flow \"t\" { input { base$n: text } vars { v$n: base$n } steps { } }")
        H.ok("stress/var-scope/$n", rep.issues.none { it.level == "error" })
    }
    for (n in 1..40) {
        val mid = (1..n).joinToString("-") { "§{a$it}" }
        val e = expr(dollarize("\"$mid\"")) as TemplateStringNode
        H.eq("stress/template-parts/$n", e.parts.count { it is ReferenceNode }, n)
    }
    for (n in 0..40) {
        val items = (1..n).joinToString(", ") { "$it" }
        H.eq("stress/list-size/$n", (expr("[$items]") as ListLiteralNode).items.size, n)
    }
    for (n in 1..30) {
        val entries = (1..n).joinToString("\n") { "k$it: $it" }
        H.eq("stress/map-size/$n", (expr("{ $entries }") as MapLiteralNode).entries.size, n)
    }
    for (n in 2..30) {
        val chain = (1..n).joinToString(" and ") { "a$it" }
        val e = expr(chain) as LogicalExpressionNode
        H.eq("stress/and-chain/$n", e.operands.size, n)
    }
    for (n in 2..30) {
        val chain = (1..n).joinToString(" or ") { "a$it" }
        val e = expr(chain) as LogicalExpressionNode
        H.eq("stress/or-chain/$n", e.operands.size, n)
    }
    for (n in 2..30) {
        val path = (1..n).joinToString("?.") { "p$it" }
        val r = expr(path) as ReferenceNode
        H.ok("stress/safenav/$n", r.safe && r.path.size == n)
    }
    for (n in 1..25) {
        val e = expr("field$n not contains \"v\"")
        H.ok("stress/not-contains/$n", e is UnaryExpressionNode && e.operand is BinaryExpressionNode)
    }
    for (n in 1..25) {
        val e = expr("field$n not empty")
        H.ok("stress/not-empty/$n", e is UnaryExpressionNode && e.operand is UnaryPostfixExpressionNode)
    }
    for (n in 0..20) {
        H.eq("stress/int-lit/$n", (expr("$n") as NumberLiteralNode).isInteger, true)
        H.eq("stress/dec-lit/$n", (expr("$n.5") as NumberLiteralNode).isInteger, false)
    }

    data class Combo(val imp: String, val sys: String, val step: String)
    val combos = listOf(
        Combo("shell", "shell", "shell.run s { command: \"x\" }"),
        Combo("git", "git", "git.checkout s { branch: \"main\" }"),
        Combo("rest", "rest", "rest.call s { method: GET\npath: \"/x\" }"),
        Combo("notify", "notify", "notify.send s { subject: \"s\" }"),
        Combo("docker", "docker", "docker.build s { image: \"i\" }"),
        Combo("helm", "helm", "helm.template s { chart: \"c\" }"),
        Combo("argocd", "argocd", "argocd.sync s { app: \"a\" }"),
        Combo("kubernetes", "kubernetes", "kubernetes.get s { resource: \"pods\" }"),
        Combo("kubernetes", "kubernetes", "kubernetes.deploy s { namespace: \"n\" }"),
        Combo("database", "database", "database.query s { sql: \"select 1\" }"),
        Combo("database", "database", "database.upsert s { table: \"t\"\nkey: \"k\"\nvalues: \"v\" }")
    )
    fun systemBlock(sys: String): String = buildString {
        appendLine("system \"s\" {")
        appendLine("type: $sys")
        if (sys == "argocd") {
            appendLine("url: secret(\"ARGOCD_URL\")")
            appendLine("token: secret(\"ARGOCD_TOKEN\")")
        }
        append("}")
    }
    for ((i, c) in combos.withIndex()) {
        val src = """use module "${c.imp}" version "1.0"
flow "t" {
  systems {
    ${systemBlock(c.sys)}
  }
  steps {
    ${c.step}
  }
}"""
        val rep = validateSrc(src)
        H.ok("stress/module-valid/$i/${c.imp}", rep.issues.none { it.level == "error" })
        H.eq("stress/module-task-count/$i", planSrc(src).tasks.size, 1)
    }
}
