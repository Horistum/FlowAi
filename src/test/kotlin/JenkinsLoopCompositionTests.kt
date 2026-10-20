import java.io.File
import kotlin.test.*
import org.flowlang.adapters.yaml.TargetRegistryYamlLoader
import org.flowlang.ast.ActionNode
import org.flowlang.ast.ForNode
import org.flowlang.ast.IfNode
import org.flowlang.capabilities.SupportLevel
import org.flowlang.conformance.GitHubConditionCompositionFixture
import org.flowlang.conformance.LoopCompositionFixture
import org.flowlang.conformance.RetryCompositionFixture
import org.flowlang.distribution.reference.ReferenceTargetProjections
import org.flowlang.generators.manifest.*
import org.flowlang.materialization.TargetMaterializationRequest
import org.flowlang.modules.ModuleRegistry
import org.flowlang.targets.builtin.flatten

class JenkinsLoopCompositionTests {
    private val root = File(".")
    private val targets by lazy { TargetRegistryYamlLoader.loadDirectory(File(root, "targets")) }
    private val provider get() = ReferenceTargetProjections.registry.requireProvider("jenkins")
    private val pipeline by lazy { ReferenceTargetProjections.pipeline(targets, modules = ModuleRegistry.fromDirectory(File(root, "modules"))) }
    private fun compile(source: String) = RetryCompositionFixture.compile(root, source)
    private fun manifest(source: String = LoopCompositionFixture.source()) = pipeline.generate(
        TargetMaterializationRequest.fromCompilation(compile(source), testTargetSelection("jenkins", targets), strict = true))
    private fun source(body: String) = """
        version "1.0"
        use module "git" version "1.0"
        use module "docker" version "1.0"
        flow "composed-loop" {
          input { enabled: boolean default true }
          systems { system "source" { type: git } system "builder" { type: docker } }
          steps { for entry in ["first", "first", "last"] { $body } }
        }
    """.trimIndent()

    @Test fun sequentialIterationUsesIndependentLeafAndContinuityContracts() {
        val text = source("""
            ${RetryCompositionFixture.checkout("producer")}
            if enabled { docker.build builder { image: "example/app:test" path: "." push: false } }
            for nested in ["one", "two"] { ${RetryCompositionFixture.checkout("tail")} }
        """)
        assertFailsWith<UnresolvedPlanningContinuityException> { manifest(text) }
        val document = compile(text).ast
        val loop = document.flow.steps.single() as ForNode
        val bound = loop.copy(body = loop.body.map { step ->
            if (step is IfNode) step.copy(then = step.then.map { (it as ActionNode).copy(dependsOn = listOf("producer")) }) else step
        })
        val unit = GitHubConditionCompositionFixture.compile(root, document.copy(flow = document.flow.copy(steps = listOf(bound))))
        val composed = pipeline.generate(TargetMaterializationRequest.fromCompilation(unit,
            testTargetSelection("jenkins", targets), strict = true))
        val artifact = provider.render(composed)
        assertContains(artifact, "for (def entry in ['first', 'first', 'last']) {")
        assertContains(artifact, "for (def nested in ['one', 'two']) {")
        assertContains(artifact, "docker.build(")
        assertEquals(SupportLevel.UNSUPPORTED, targets.getValue("jenkins").dynamicLoops)
        val image = composed.jobs.single().steps.flatMap { it.flatten() }.single { it.rendererPayload?.reference == "docker-build" }
        val imageVariable = "flowImage_" + image.id.replace(Regex("[^A-Za-z0-9_]+"), "_").trim('_')
        for (item in listOf("docker", imageVariable)) {
            val colliding = bound.copy(item = item)
            assertFails {
                val collisionUnit = GitHubConditionCompositionFixture.compile(root,
                    document.copy(flow = document.flow.copy(steps = listOf(colliding))))
                provider.render(pipeline.generate(TargetMaterializationRequest.fromCompilation(collisionUnit,
                    testTargetSelection("jenkins", targets), strict = true)))
            }
            val job = composed.jobs.single(); val outer = job.steps.single()
            val error = assertFailsWith<IllegalArgumentException> { provider.renderer.render(composed.copy(jobs = listOf(job.copy(steps =
                listOf(outer.copy(params = outer.params + ("item" to item))))))) }
            assertContains(error.message.orEmpty(), if (item == "docker") "native execution scope" else "image result variables")
        }
    }

    @Test fun taskIdentityAndCardinalityDoNotDefineLoopEligibility() {
        for (count in listOf(1, 2, 5)) {
            val body = (1..count).joinToString("\n") { RetryCompositionFixture.checkout("renamed$it") }
            assertEquals(count, provider.render(manifest(source(body))).lineSequence().count { it.trim() == "checkout scmGit(" })
        }
        val artifact = provider.render(manifest(LoopCompositionFixture.source().replace("outer", "renamedOuter").replace("inner", "renamedInner")))
        assertContains(artifact, "for (def renamedOuter")
        assertContains(artifact, "for (def renamedInner")
    }

    @Test fun emptyBodiesCollectionsAndSiblingScopesCompose() {
        for (body in listOf("", "if enabled { }", "for nested in [] { }",
            "for nested in [\"one\"] { } for nested in [\"two\"] { }")) {
            assertContains(provider.render(manifest(source(body))), "for (def entry")
        }
        assertContains(provider.render(manifest(LoopCompositionFixture.source("empty"))), "for (def outer in []) {")
    }

    @Test fun nestedCollectionsAndLexicalItemScopesAreValidated() {
        val original = LoopCompositionFixture.source()
        for (changed in listOf(original.replace("[\"one\", \"two\"]", "[1, 2]"),
            original.replace("for inner in", "for outer in"), original.replace("for inner in", "for enabled in"),
            original.replace("for inner in", "for params in"))) assertFails { provider.render(manifest(changed)) }
        val escaped = source("for nested in [\"one\"] { } if nested == \"one\" { ${RetryCompositionFixture.checkout("escaped")} }")
        assertFails { provider.render(manifest(escaped)) }
    }

    @Test fun unsupportedLeavesAndControlsCannotBorrowIterationSupport() {
        val review = manifest(source("docker.push builder { image: \"example/app:test\" }"))
        assertEquals(TargetRenderMode.REVIEW_ONLY, TargetRenderPolicy.evaluate(review).mode)
        assertFailsWith<TargetRenderBlockedException> { provider.render(review) }
        for (body in listOf("approve manual { message: \"Iteration decision\" }",
            "retry { max: 2 delay: \"1s\" backoff: fixed } { ${RetryCompositionFixture.checkout("child")} }"))
            assertFails { provider.render(manifest(source(body))) }
        val m = manifest(); val job = m.jobs.single(); val loop = job.steps.first(); val leaf = loop.children.first()
        for (replacement in listOf(leaf.copy(type = "approval"),
            leaf.copy(rendererPayload = leaf.rendererPayload!!.copy(reference = "missing")),
            loop.copy(id = "nested", children = listOf(leaf)),
            loop.copy(id = "nested", params = mapOf("item" to "nested", "source" to "items"), children = listOf(leaf)))) {
            assertFails { provider.renderer.render(m.copy(jobs = listOf(job.copy(steps =
                listOf(loop.copy(children = listOf(replacement))) + job.steps.drop(1))))) }
        }
    }

    @Test fun iterationLocalResultsAreEstablishedBeforeUseAndCannotEscape() {
        val body = """git.checkout source { url: "https://example.invalid/repo.git" branch: "main" } -> result
            if result.ok == true { ${RetryCompositionFixture.checkout("consumer")} }"""
        val artifact = provider.render(manifest(source(body)))
        assertTrue(artifact.indexOf("for (def entry") < artifact.indexOf("def flowCheckoutSucceeded = true"))
        assertTrue(artifact.indexOf("def flowCheckoutSucceeded = true") < artifact.indexOf("if (flowCheckoutSucceeded"))
        val escaped = source(body).replace("steps { for", "steps { if result.ok == true { ${RetryCompositionFixture.checkout("escaped")} } for")
        assertFails { manifest(escaped) }
    }
}
