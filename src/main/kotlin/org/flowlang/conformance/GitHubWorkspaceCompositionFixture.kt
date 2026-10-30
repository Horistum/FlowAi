package org.flowlang.conformance

import java.io.File
import org.flowlang.ast.*
import org.flowlang.distribution.reference.ReferenceTargetProjections
import org.flowlang.serialization.FlowYaml

/** Parsed-AST verification input. Production applicability never consumes this fixture. */
internal object GitHubWorkspaceCompositionFixture {
    const val WORKFLOW = ".github/workflows/github-workspace-composition.yml"
    const val CONTEXT = "conformance/fixtures/workspace-bytes"
    const val REVISION_BINDING = "\${{ needs.prepare.outputs.revision }}"

    fun document(revision: String, consumers: Int = 2): FlowDocument {
        require(revision.matches(Regex("[0-9a-f]{40}")) && consumers > 0)
        val base = GitHubConditionCompositionFixture.document()
        val producer = GitHubConditionCompositionFixture.checkout("snapshot").copy(
            params = mapOf("url" to StringLiteralNode(value = "https://github.com/Horistum/FlowAi.git"),
                "branch" to StringLiteralNode(value = revision)))
        return base.copy(imports = base.imports + ModuleImportNode(name = "docker", version = "1.0"),
            flow = base.flow.copy(name = "workspace-composition",
                input = listOf(InputNode(name = "label", valueType = ValueTypeNode("text"),
                    default = StringLiteralNode(value = "independent input"))),
                systems = base.flow.systems + SystemNode(name = "builder", systemType = "docker"),
                steps = listOf(producer) + (1..consumers).map { index ->
                    ActionNode(module = "docker", action = "build", target = ReferenceNode(path = listOf("builder")),
                        params = mapOf("image" to StringLiteralNode(value = "flow-workspace:consumer$index"),
                            "path" to StringLiteralNode(value = CONTEXT), "push" to BooleanLiteralNode(value = false)),
                        result = ResultBindingNode(name = "consumer$index"), dependsOn = listOf("snapshot"))
                } + producer.copy(result = ResultBindingNode(name = "independent"))))
    }

    fun conditionalDocument(revision: String): FlowDocument {
        val base = document(revision)
        fun consumer(index: Int) = (base.flow.steps[1] as ActionNode).copy(
            result = ResultBindingNode(name = "consumer$index"),
            params = (base.flow.steps[1] as ActionNode).params +
                ("image" to StringLiteralNode(value = "flow-workspace:consumer$index")))
        return base.copy(flow = base.flow.copy(steps = base.flow.steps.dropLast(1) +
            IfNode(condition = BooleanLiteralNode(value = true), then = listOf(
                IfNode(condition = BooleanLiteralNode(value = false), then = listOf(consumer(3)),
                    otherwise = listOf(consumer(4)))), otherwise = listOf(consumer(5))) + base.flow.steps.last()))
    }

    fun conditionalProducerDocument(revision: String): FlowDocument {
        val base = conditionalDocument(revision)
        fun producer(name: String) = (base.flow.steps.first() as ActionNode).copy(result = ResultBindingNode(name = name))
        fun consumer(index: Int, source: String) = (base.flow.steps[1] as ActionNode).copy(
            result = ResultBindingNode(name = "consumer$index"), dependsOn = listOf(source),
            params = (base.flow.steps[1] as ActionNode).params +
                ("image" to StringLiteralNode(value = "flow-workspace:consumer$index")))
        return base.copy(flow = base.flow.copy(steps = base.flow.steps +
            IfNode(condition = BooleanLiteralNode(value = true), then = listOf(
                producer("selectedSnapshot"),
                IfNode(condition = BooleanLiteralNode(value = false),
                    then = listOf(consumer(6, "selectedSnapshot")),
                    otherwise = listOf(consumer(7, "selectedSnapshot"))),
                consumer(8, "selectedSnapshot")),
                otherwise = listOf(producer("unselectedSnapshot"), consumer(9, "unselectedSnapshot")))))
    }

    /** Faults belong only to the executed test envelope, never to the authored standard. */
    fun failureDocument(revision: String): FlowDocument {
        val base = conditionalProducerDocument(revision)
        fun producer(name: String) = (base.flow.steps.first() as ActionNode).copy(result = ResultBindingNode(name = name))
        fun consumer(index: Int, source: String) = (base.flow.steps[1] as ActionNode).copy(
            result = ResultBindingNode(name = "consumer$index"), dependsOn = listOf(source),
            params = (base.flow.steps[1] as ActionNode).params +
                ("image" to StringLiteralNode(value = "flow-workspace:consumer$index")))
        return base.copy(flow = base.flow.copy(steps = base.flow.steps +
            IfNode(condition = BooleanLiteralNode(value = true), then = listOf(
                producer("checkoutFailureSnapshot"), consumer(10, "checkoutFailureSnapshot"),
                producer("uploadFailureSnapshot"), consumer(11, "uploadFailureSnapshot")))))
    }

    /** Pin every native action and dependency. Only observation and named falsifying mutations may differ. */
    @Suppress("UNCHECKED_CAST")
    fun validateEnvelope(generated: String, envelope: String, revision: String) {
        val expected = FlowYaml.readMap(generated)["jobs"] as Map<String, Map<String, Any?>>
        val actual = FlowYaml.readMap(envelope)["jobs"] as Map<String, Map<String, Any?>>
        require(actual.keys == expected.keys + setOf("prepare", "missing-transfer-mutant", "hidden-file-mutant",
            "guard-omission-mutant", "producer-guard-omission-mutant",
            "failure-checkout-consumer-mutant", "failure-upload-consumer-mutant", "collect"))
        for ((id, job) in expected) {
            val native = actual.getValue(id)
            require(native.keys == setOf("name", "runs-on", "needs", "timeout-minutes", "outputs", "steps") +
                (if (job["if"] == null) emptySet() else setOf("if")))
            require(native["if"] == job["if"]) { "Changed generated guard in $id" }
            require(native["name"] == job["name"] && native["runs-on"] == job["runs-on"])
            require(native["needs"] == listOf("prepare") + (job["needs"] as? List<String>).orEmpty())
            require(native["timeout-minutes"] == 10)
            require(native["outputs"] == mapOf("inventory" to "\${{ steps.observe.outputs.inventory }}"))
            val steps = native["steps"] as List<Map<String, Any?>>
            val production = job["steps"] as List<Map<String, Any?>>
            val normalized = steps.take(production.size).map { step ->
                val bindings = step["with"] as? Map<String, Any?>
                when {
                    step["uses"] == "actions/checkout@v4" -> {
                        require(bindings?.get("ref") == if (id == "git_checkout_5")
                            "flow-workspace-missing-$REVISION_BINDING" else REVISION_BINDING)
                        step + ("with" to (bindings!! + ("ref" to revision)))
                    }
                    id == "git_checkout_6" && step["uses"] == "actions/upload-artifact@v7" -> {
                        require(bindings?.get("path") == ".flow-workspace-missing-$REVISION_BINDING")
                        step + ("with" to (bindings!! + ("path" to ".")))
                    }
                    else -> step
                }
            }
            require(normalized == production) { "Changed generated action in $id" }
            val extras = if (id == "git_checkout_1") listOf(observer(), hiddenUpload(production.last())) else listOf(observer())
            require(steps.drop(production.size) == extras) { "Changed observer or mutant upload in $id" }
        }
        require(actual.getValue("guard-omission-mutant") == actual.getValue("docker_build_3") - "if" +
            ("name" to "guard-omission-mutant")) { "Guard-omission mutant differs" }
        require(actual.getValue("producer-guard-omission-mutant") == actual.getValue("git_checkout_4") - "if" +
            ("name" to "producer-guard-omission-mutant")) { "Producer guard-omission mutant differs" }
        val baseline = actual.getValue("docker_build_1")
        val steps = baseline["steps"] as List<Map<String, Any?>>
        val build = steps[1] + mapOf("id" to "build", "continue-on-error" to true)
        val missing = baseline - "outputs" + mapOf("name" to "missing-transfer-mutant",
            "steps" to listOf(build, assertFailure()))
        require(actual.getValue("missing-transfer-mutant") == missing) { "Missing-transfer mutant differs" }
        val download = steps[0]
        val hidden = baseline - "outputs" + mapOf("name" to "hidden-file-mutant", "steps" to listOf(
            download + ("with" to ((download["with"] as Map<String, Any?>) + ("name" to "workspace-hidden-mutant"))),
            mapOf("name" to "Verify selective loss", "shell" to "bash", "run" to
                "test -f $CONTEXT/payload.txt\ntest ! -e $CONTEXT/.hidden\n"), build, assertFailure()))
        require(actual.getValue("hidden-file-mutant") == hidden) { "Hidden-file mutant differs" }
        for ((mutant, source) in mapOf("failure-checkout-consumer-mutant" to "docker_build_10",
            "failure-upload-consumer-mutant" to "docker_build_11")) {
            val consumer = actual.getValue(source)
            val actions = consumer["steps"] as List<Map<String, Any?>>
            val altered = consumer + mapOf("name" to mutant, "if" to "${'$'}{{ !cancelled() }}",
                "outputs" to mapOf("downloadOutcome" to "${'$'}{{ steps.download.outcome }}",
                    "buildOutcome" to "${'$'}{{ steps.build.outcome }}"),
                "steps" to listOf(actions[0] + mapOf("id" to "download", "continue-on-error" to true),
                    actions[1] + mapOf("id" to "build", "continue-on-error" to true),
                    mapOf("name" to "Require unavailable workspace", "shell" to "bash",
                        "env" to mapOf("DOWNLOAD" to "${'$'}{{ steps.download.outcome }}", "BUILD" to "${'$'}{{ steps.build.outcome }}"),
                        "run" to "test \"${'$'}DOWNLOAD\" = failure\ntest \"${'$'}BUILD\" = failure\n")))
            require(actual.getValue(mutant) == altered) { "Failure consumer mutant differs: $mutant" }
        }
    }

    private fun observer(): Map<String, Any?> = mapOf("name" to "Observe workspace bytes", "id" to "observe",
        "shell" to "bash", "run" to "python3 tools/verify_github_workspace_composition.py observe\n")
    @Suppress("UNCHECKED_CAST")
    private fun hiddenUpload(upload: Map<String, Any?>): Map<String, Any?> = upload + mapOf(
        "name" to "Prepare hidden-file mutant", "with" to ((upload["with"] as Map<String, Any?>) +
            mapOf("name" to "workspace-hidden-mutant", "include-hidden-files" to "false")))
    private fun assertFailure(): Map<String, Any?> = mapOf("name" to "Require falsification", "shell" to "bash",
        "env" to mapOf("OUTCOME" to "\${{ steps.build.outcome }}"), "run" to "test \"\$OUTCOME\" = failure\n")
}

object GitHubWorkspaceCompositionCli {
    @JvmStatic fun main(args: Array<String>) {
        require(args.size == 4) { "Expected repository root, executed envelope, output directory and source revision." }
        val root = File(args[0]); val out = File(args[2]).apply { mkdirs() }
        val fixture = GitHubWorkspaceCompositionFixture
        val document = fixture.failureDocument(args[3])
        val compilation = GitHubConditionCompositionFixture.compile(root, document)
        val manifest = GitHubConditionCompositionFixture.manifest(root, compilation, "github-actions")
        val rendered = ReferenceTargetProjections.registry.requireProvider("github-actions").render(manifest)
        fixture.validateEnvelope(rendered, File(args[1]).readText(), args[3])
        val mapper = GitHubConditionCompositionFixture.mapper
        File(out, "fixture.ast.json").writeBytes(mapper.writeValueAsBytes(document))
        File(out, "canonical-graph.json").writeBytes(mapper.writeValueAsBytes(compilation.authorization.graph))
        File(out, "github-actions.manifest.json").writeBytes(mapper.writeValueAsBytes(manifest))
        File(out, "github-actions.yml").writeText(rendered)
        File(args[1]).copyTo(File(out, "execution-envelope.yml"), overwrite = true)
        println("PASS: generated workspace transfers match the native execution envelope.")
    }
}
