package org.flowlang.conformance

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.io.File
import org.flowlang.ast.*
import org.flowlang.adapters.yaml.TargetRegistryYamlLoader
import org.flowlang.compiler.*
import org.flowlang.distribution.reference.ReferenceTargetProjections
import org.flowlang.frontend.FrontendCompilerComposition
import org.flowlang.generators.manifest.TargetManifest
import org.flowlang.materialization.TargetMaterializationRequest
import org.flowlang.materialization.TargetSelectionAuthority
import org.flowlang.modules.ModuleRegistry
import org.flowlang.parser.ExpressionParser
import org.flowlang.safety.StandardEnvironmentSafetyPolicyNotes
import org.flowlang.serialization.FlowYaml

/** External regression fixture at the existing parsed-AST compiler boundary.
 * Explicit ordering is an AST contract; this fixture makes no source-parser or portability claim.
 */
internal object GitHubConditionCompositionFixture {
    const val REVISION = "c6149ff9ba87f88e25cdc0905cfb86cb935f7756"
    const val WORKFLOW = ".github/workflows/github-condition-composition.yml"
    val mapper = jacksonObjectMapper()
    fun checkout(name: String, vararg dependencies: String) = ActionNode(
        module = "git", action = "checkout", target = ReferenceNode(path = listOf("source")),
        params = mapOf("url" to StringLiteralNode(value = "https://github.com/Horistum/FlowAi.git"),
            "branch" to StringLiteralNode(value = REVISION)), result = ResultBindingNode(name = name),
        dependsOn = dependencies.toList())

    fun document() = FlowDocument(imports = listOf(ModuleImportNode(name = "git", version = "1.0")),
        metadata = MetadataNode(createdBy = "conformance-ast-fixture"),
        flow = FlowNode(name = "condition-composition", input = listOf(InputNode(name = "enabled",
            valueType = ValueTypeNode("boolean"), default = BooleanLiteralNode(value = true))),
            systems = listOf(SystemNode(name = "source", systemType = "git")), steps = listOf(
                checkout("root"),
                IfNode(condition = ExpressionParser.parseSource("enabled and true"), then = listOf(
                    checkout("optional", "root"),
                    IfNode(condition = ExpressionParser.parseSource("not false"),
                        then = listOf(checkout("selected", "optional")))) ,
                    otherwise = listOf(checkout("alternate", "root"))),
                checkout("join", "selected", "alternate"),
                checkout("tail", "join"),
                checkout("independent"))))

    fun compile(root: File, document: FlowDocument = document()): CompilationUnit {
        val bytes = mapper.writeValueAsBytes(document)
        return FrontendCompilerComposition.compiler(ModuleRegistry.fromDirectory(File(root, "modules")),
            StandardEnvironmentSafetyPolicyNotes.policy(root)).compile(FlowSourceCompilationInput(
                CompilationSource.fromBytes(CompilationFrontend.FLOW_SOURCE, "condition-composition.ast.json", bytes,
                    mediaType = "application/vnd.flow.ast+json"), document)).requireAccepted()
    }

    fun manifest(root: File, compilation: CompilationUnit, target: String): TargetManifest {
        val modules = ModuleRegistry.fromDirectory(File(root, "modules"))
        val targets = TargetRegistryYamlLoader.loadDirectory(File(root, "targets"))
        val selection = TargetSelectionAuthority.requireSelected(TargetSelectionAuthority.fromCliOption(target, targets), "composition regression")
        return ReferenceTargetProjections.pipeline(targets, rootDir = root, modules = modules)
            .generate(TargetMaterializationRequest.fromCompilation(compilation, selection, strict = true))
    }

    /** Compare every production job, edge, guard and native action to the checked-in native envelope. */
    @Suppress("UNCHECKED_CAST")
    fun validateEnvelope(generated: String, envelope: String) {
        val expected = FlowYaml.readMap(generated)["jobs"] as Map<String, Map<String, Any?>>
        val actual = FlowYaml.readMap(envelope)["jobs"] as Map<String, Map<String, Any?>>
        require(actual.keys == expected.keys + setOf("prepare", "old-default-mutant", "inverted-guard-mutant", "collect"))
        for ((id, job) in expected) {
            val native = actual.getValue(id)
            require(native.keys == setOf("name", "runs-on", "needs", "if", "timeout-minutes", "outputs", "steps"))
            require(native["name"] == job["name"] && native["runs-on"] == job["runs-on"])
            val needs = (job["needs"] as? List<String>).orEmpty()
            require(native["needs"] == listOf("prepare") + needs) { "Changed native dependencies for $id" }
            val condition = (job["if"] as? String)?.removePrefix("\${{ ")?.removeSuffix(" }}")
            val guard = when {
                condition == null -> "success()"
                "cancelled()" in condition -> condition
                else -> "success() && $condition"
            }
            require(native["if"] == "\${{ !cancelled() && needs.prepare.result == 'success' && ($guard) }}") {
                "Changed native guard for $id: expected $guard, found ${native["if"]}" }
            val steps = native["steps"] as List<Map<String, Any?>>
            require(steps.dropLast(1) == job["steps"]) { "Changed native action for $id" }
            require(steps.last() == observer()) { "Changed native observer for $id" }
            require(native["timeout-minutes"] == 10)
            require(native["outputs"] == mapOf("revision" to "\${{ steps.observe.outputs.revision }}"))
        }
        val old = actual.getValue("old-default-mutant")
        val inverted = actual.getValue("inverted-guard-mutant")
        require(old == actual.getValue("git_checkout_5") + mapOf("name" to "old-default-mutant",
            "if" to "\${{ success() }}")) { "Old-default mutant must only replace the join guard." }
        require(inverted == actual.getValue("git_checkout_2") + mapOf("name" to "inverted-guard-mutant",
            "if" to "\${{ !cancelled() && needs.prepare.result == 'success' && success() && !(inputs.enabled && true) }}")) {
            "Inverted mutant must only replace the optional guard." }
    }

    private fun observer(): Map<String, Any?> = mapOf("name" to "Observe native checkout", "id" to "observe",
        "shell" to "bash", "run" to "revision=$(git rev-parse HEAD)\ntest \"\$revision\" = \"$REVISION\"\nprintf 'revision=%s\\n' \"\$revision\" >> \"\$GITHUB_OUTPUT\"\n")
}

object GitHubConditionCompositionCli {
    @JvmStatic fun main(args: Array<String>) {
        require(args.size == 3) { "Expected repository root, executed workflow file and output directory." }
        val root = File(args[0]); val out = File(args[2]).apply { mkdirs() }
        val fixture = GitHubConditionCompositionFixture
        val compilation = fixture.compile(root)
        File(out, "fixture.ast.json").writeBytes(fixture.mapper.writeValueAsBytes(fixture.document()))
        File(out, "canonical-graph.json").writeBytes(fixture.mapper.writeValueAsBytes(compilation.authorization.graph))
        for (target in listOf("github-actions", "jenkins")) {
            val manifest = fixture.manifest(root, compilation, target)
            val rendered = ReferenceTargetProjections.registry.requireProvider(target).render(manifest)
            File(out, "$target.manifest.json").writeBytes(fixture.mapper.writeValueAsBytes(manifest))
            File(out, if (target == "jenkins") "Jenkinsfile" else "github-actions.yml").writeText(rendered)
            if (target == "github-actions") fixture.validateEnvelope(rendered, File(args[1]).readText())
        }
        File(args[1]).copyTo(File(out, "execution-envelope.yml"), overwrite = true)
        println("PASS: both projections consume the same canonical graph; native GitHub envelope matches generated jobs.")
    }
}
