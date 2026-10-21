import java.io.File
import java.security.MessageDigest
import org.flowlang.cli.Json
import org.flowlang.compiler.*
import org.flowlang.core.FlowAvailabilityAnalyzer
import org.flowlang.frontend.FrontendCompilerComposition
import org.flowlang.frontend.source.FlowSourceFrontend
import org.flowlang.modules.ModuleRegistry
import org.flowlang.parser.FlowParser
import org.flowlang.adapters.yaml.TargetRegistryYamlLoader
import org.flowlang.distribution.reference.ReferenceTargetProjections
import org.flowlang.materialization.*
import org.flowlang.safety.StandardEnvironmentSafetyPolicyNotes

/** Read-only audit observations. A finding is evidence of failure, never a passing regression gate. */
fun main(args: Array<String>) {
    val root = File(args[0])
    val out = File(args[1]).apply { mkdirs() }
    val compiler = FrontendCompilerComposition.compiler(ModuleRegistry.fromDirectory(File(root, "modules")),
        StandardEnvironmentSafetyPolicyNotes.policy(root))
    val frontend = FlowSourceFrontend(compiler)
    fun source(body: String, declarations: String = "", git: Boolean = false): String =
        "version \"1.0\"\n" + (if (git) "use module \"git\" version \"1.0\"\n" else "") +
        "flow \"audit\" {\n$declarations\nsteps {\n$body\n}\n}\n"
    fun compile(text: String, name: String): CompilationUnit {
        val file = File(out, "$name.flow").apply { writeText(text) }
        return frontend.compile(file).requireAccepted().also { unit ->
            File(out, "$name.ast.json").writeBytes(Json.bytes(unit.ast))
            File(out, "$name.graph.json").writeBytes(Json.bytes(unit.graph))
            File(out, "$name.plan.json").writeBytes(Json.bytes(unit.executionPlan))
        }
    }
    val checkout = "git.checkout repo { url: \"https://github.com/Horistum/FlowAi.git\" branch: \"main\" } -> result"
    val systems = "systems { system \"repo\" { type: git } }"
    val cases = linkedMapOf(
        "referenced-variable" to (source("if gate { approve manual { message: \"Review\" } }", "vars { gate: true }") to
            source("if gate { approve manual { message: \"Review\" } }", "vars { gate: false }")),
        "transform-predicate" to (source("transform [1] -> out { where true select { value: 1 } }") to
            source("transform [1] -> out { where false select { value: 1 } }")),
        "transform-selection" to (source("transform [1] -> out { select { value: 1 } }") to
            source("transform [1] -> out { select { value: 2 } }")),
        "aggregate-fields" to (source("aggregate [1] -> out { total: 1 }") to source("aggregate [1] -> out { total: 2 }")),
        "validation-rule" to (source("validate [1] { true }") to source("validate [1] { false }")),
        "action-expectation" to (source("$checkout { expect { true } }", systems, true) to
            source("$checkout { expect { false } }", systems, true)),
        "action-error-handler" to (source("$checkout", systems, true) to
            source("$checkout { when error { fail \"must stop\" } }", systems, true)),
        "action-conditional-handler" to (source("$checkout { when true { fail \"selected\" } }", systems, true) to
            source("$checkout { when false { fail \"selected\" } }", systems, true)),
        "approval-unknown-parameter" to (source("approve manual { message: \"Review\" unexpected: 1 }") to
            source("approve manual { message: \"Review\" unexpected: 2 }")),
        "input-nonstring-options" to (source("approve manual { message: \"Review\" }", "input { choice: option [1, 2] required }") to
            source("approve manual { message: \"Review\" }", "input { choice: option [3, 4] required }")),
        "positive-control-checkout-branch" to (source(checkout, systems, true) to
            source(checkout.replace("\"main\"", "\"release\""), systems, true)),
        "positive-control-direct-condition" to (source("if true { approve manual { message: \"Review\" } }") to
            source("if false { approve manual { message: \"Review\" } }"))
    )
    val targets = TargetRegistryYamlLoader.loadDirectory(File(root, "targets"))
    val modules = ModuleRegistry.fromDirectory(File(root, "modules"))
    val projections = ReferenceTargetProjections.fromContracts(root)
    val pipeline = ReferenceTargetProjections.pipeline(targets, rootDir = root, projections = projections, modules = modules)
    fun render(unit: CompilationUnit, target: String): String {
        val selection = TargetSelectionAuthority.requireSelected(TargetSelectionAuthority.fromCliOption(target, targets), "audit")
        return projections.requireProvider(target).render(
            pipeline.generate(TargetMaterializationRequest.fromCompilation(unit, selection, strict = true)))
    }
    fun sha(text: String) = MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
    val observations = cases.map { (id, pair) ->
        try {
            val left = compile(pair.first, "$id-left")
            val right = compile(pair.second, "$id-right")
            val row = linkedMapOf<String, Any>("id" to id, "accepted" to true,
                "leftSourceSha256" to left.source.sha256, "rightSourceSha256" to right.source.sha256,
                "leftGraphSha256" to left.graphDigest.value, "rightGraphSha256" to right.graphDigest.value,
                "leftValidationIssues" to left.validation.issues, "rightValidationIssues" to right.validation.issues,
                "sameGraph" to (left.graph == right.graph), "sameDigest" to (left.graphDigest == right.graphDigest),
                "samePlan" to (left.executionPlan == right.executionPlan))
            if (id.startsWith("action-") || id == "positive-control-checkout-branch" || id == "approval-unknown-parameter") row["rendering"] = listOf("jenkins", "github-actions").map { target ->
                try {
                    val a = render(left, target); val b = render(right, target)
                    File(out, "$id-$target-left.txt").writeText(a)
                    File(out, "$id-$target-right.txt").writeText(b)
                    mapOf("target" to target, "rendered" to true, "sameArtifact" to (a == b),
                        "leftSha256" to sha(a), "rightSha256" to sha(b))
                } catch (error: Exception) { mapOf("target" to target, "rendered" to false, "error" to error.toString()) }
            }
            row
        } catch (error: Exception) { mapOf("id" to id, "accepted" to false, "error" to error.toString()) }
    }
    val admissions = linkedMapOf(
        "unknown-input-type" to "input { value: unexpected required }",
        "boolean-string-default" to "input { value: boolean default \"not-a-boolean\" }",
        "option-default-outside-domain" to "input { value: option [\"a\", \"b\"] default \"c\" }"
    ).map { (id, declaration) ->
        try {
            val unit = compile(source("approve manual { message: \"Review\" }", declaration), id)
            mapOf("id" to id, "accepted" to true, "graphSha256" to unit.graphDigest.value,
                "validationIssues" to unit.validation.issues)
        } catch (error: Exception) { mapOf("id" to id, "accepted" to false, "error" to error.toString()) }
    }
    val sizes = if (args.size > 2) listOf(args[2].toInt()) else listOf(4, 8, 12, 14)
    val paths = sizes.map { count ->
        val text = source((1..count).joinToString("\n") { "if enabled { } else { }" }, "input { enabled: boolean required }")
        File(out, "paths-$count.flow").writeText(text)
        val ast = FlowParser().parse(text, "audit-paths.flow")
        val start = System.nanoTime()
        val analysis = FlowAvailabilityAnalyzer().analyze(ast)
        mapOf("conditions" to count, "sourceBytes" to text.toByteArray().size,
            "exitPaths" to analysis.normalExitState.paths.size, "milliseconds" to ((System.nanoTime() - start) / 1_000_000),
            "issues" to analysis.issues.size)
    }
    val result = mapOf("observations" to observations, "inputAdmission" to admissions, "pathExpansion" to paths)
    File(out, "observations.json").writeBytes(Json.bytes(result))
    println(String(Json.bytes(result)))
}
