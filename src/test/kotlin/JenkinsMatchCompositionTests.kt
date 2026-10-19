import java.io.File
import kotlin.test.*
import org.flowlang.adapters.yaml.TargetRegistryYamlLoader
import org.flowlang.ast.ActionNode
import org.flowlang.ast.IfNode
import org.flowlang.ast.MatchNode
import org.flowlang.capabilities.SupportLevel
import org.flowlang.conformance.GitHubConditionCompositionFixture
import org.flowlang.conformance.MatchCompositionFixture
import org.flowlang.conformance.RetryCompositionFixture
import org.flowlang.distribution.reference.ReferenceTargetProjections
import org.flowlang.generators.manifest.*
import org.flowlang.materialization.TargetMaterializationRequest
import org.flowlang.modules.ModuleRegistry

class JenkinsMatchCompositionTests {
    private val root = File(".")
    private val targets by lazy { TargetRegistryYamlLoader.loadDirectory(File(root, "targets")) }
    private val provider get() = ReferenceTargetProjections.registry.requireProvider("jenkins")
    private val pipeline by lazy { ReferenceTargetProjections.pipeline(targets, modules = ModuleRegistry.fromDirectory(File(root, "modules"))) }
    private fun compile(source: String) = RetryCompositionFixture.compile(root, source)
    private fun manifest(source: String) = pipeline.generate(
        TargetMaterializationRequest.fromCompilation(compile(source), testTargetSelection("jenkins", targets), strict = true))
    private fun source(body: String, fallback: String = "") = """
        version "1.0"
        use module "git" version "1.0"
        use module "docker" version "1.0"
        flow "composed-match" {
          input { enabled: boolean default true }
          systems { system "source" { type: git } system "builder" { type: docker } }
          steps { match "inert" { when true { $body } default { $fallback } } }
        }
    """.trimIndent()

    @Test fun branchesUseIndependentLeafAndWorkspaceContinuityContracts() {
        val text = source("""
            ${RetryCompositionFixture.checkout("producer")}
            if enabled { docker.build builder { image: "example/app:test" path: "." push: false } }
        """)
        assertFailsWith<UnresolvedPlanningContinuityException> { manifest(text) }
        val document = compile(text).ast
        val match = document.flow.steps.single() as MatchNode
        val bound = match.copy(cases = match.cases.map { branch -> branch.copy(steps = branch.steps.map { step ->
            if (step is IfNode) step.copy(then = step.then.map { (it as ActionNode).copy(dependsOn = listOf("producer")) }) else step
        }) })
        val unit = GitHubConditionCompositionFixture.compile(root, document.copy(flow = document.flow.copy(steps = listOf(bound))))
        val composed = pipeline.generate(TargetMaterializationRequest.fromCompilation(unit, testTargetSelection("jenkins", targets), strict = true))
        val artifact = provider.render(composed)
        assertContains(artifact, "docker.build(")
        assertTrue(artifact.indexOf("checkout scmGit(") < artifact.indexOf("docker.build("))
        assertEquals(SupportLevel.UNSUPPORTED, targets.getValue("jenkins").match)
    }

    @Test fun taskAndCaseCardinalityIdentityAndSelectedPositionDoNotDefineEligibility() {
        for (count in listOf(1, 2, 5)) {
            val body = (1..count).joinToString("\n") { RetryCompositionFixture.checkout("renamed$it") }
            for (position in 0..count) {
                val cases = (0..count).joinToString("\n") { "when ${it == position} { ${if (it == position) body else ""} }" }
                val text = source("").replace("when true {  }", cases)
                assertEquals(count, provider.render(manifest(text)).lineSequence().count { it.trim() == "checkout scmGit(" })
            }
        }
    }

    @Test fun nestedConditionsMatchesAndEmptyBranchesRemainStructured() {
        for (outcome in listOf("success", "default", "empty", "failure")) {
            val artifact = provider.render(manifest(MatchCompositionFixture.source(outcome)))
            assertContains(artifact, "} else {")
            assertContains(artifact, "if (params.enabled)")
        }
        for (body in listOf("", "if enabled { }", "match \"nested\" { when true { } }",
            "match \"nested\" { when false { } default { } }"))
            assertContains(provider.render(manifest(source(body, body))), "if (true) {")
    }

    @Test fun branchValuesAreLocalAndEstablishedOnlyAfterNormalCompletion() {
        val producer = """git.checkout source { url: "https://example.invalid/repo.git" branch: "main" } -> result"""
        val consumer = "if result.ok == true { ${RetryCompositionFixture.checkout("consumer")} }"
        val artifact = provider.render(manifest(source("$producer match \"nested\" { when true { $consumer } }")))
        assertTrue(artifact.indexOf("def flowCheckoutSucceeded = true") < artifact.indexOf("if (flowCheckoutSucceeded"))
        // Sibling branch aliases can be reused but cannot establish one another's values.
        provider.render(manifest(source("$producer $consumer", "$producer ${consumer.replace("consumer", "other")}")))
        for (text in listOf(source(consumer, producer), source(producer, consumer),
            source("match \"nested\" { when true { $producer } } $consumer"),
            source("$producer ${consumer.replace("result.ok", "result.commit")}")))
            assertFails { provider.render(manifest(text)) }
        val original = manifest(source("$producer $consumer"))
        val job = original.jobs.single(); val match = job.steps.single(); val branch = match.children.first()
        val escaped = match.copy(children = listOf(branch.copy(children = branch.children.take(1))) +
            match.children.drop(1).map { if (it.type == "match-default") it.copy(children = branch.children.drop(1)) else it })
        assertFails { provider.renderer.render(original.copy(jobs = listOf(job.copy(steps = listOf(escaped))))) }
    }

    @Test fun unsupportedLeavesControlsAndNativeBindingsRemainBlocked() {
        val review = manifest(source("docker.push builder { image: \"example/app:test\" }"))
        assertEquals(TargetRenderMode.REVIEW_ONLY, TargetRenderPolicy.evaluate(review).mode)
        assertFailsWith<TargetRenderBlockedException> { provider.render(review) }
        for (body in listOf("approve manual { message: \"Decision\" }", "for item in [\"one\"] { }",
            "retry { max: 2 delay: \"0s\" backoff: fixed } { }",
            "match \"nested\" { when true { } when true { } }", "match \"nested\" { when enabled { } }"))
            assertFails { provider.render(manifest(source(body))) }
        val original = manifest(source(RetryCompositionFixture.checkout("child")))
        val job = original.jobs.single(); val match = job.steps.single(); val branch = match.children.first(); val leaf = branch.children.single()
        for (replacement in listOf(leaf.copy(type = "approval"), leaf.copy(children = listOf(leaf.copy(id = "nested"))),
            leaf.copy(rendererPayload = leaf.rendererPayload!!.copy(reference = "unknown")))) {
            val changed = match.copy(children = listOf(branch.copy(children = listOf(replacement))) + match.children.drop(1))
            assertFails { provider.renderer.render(original.copy(jobs = listOf(job.copy(steps = listOf(changed))))) }
        }
    }
}
