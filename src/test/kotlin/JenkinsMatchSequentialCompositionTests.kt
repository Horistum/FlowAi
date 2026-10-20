import java.io.File
import kotlin.test.*
import org.flowlang.adapters.yaml.TargetRegistryYamlLoader
import org.flowlang.capabilities.SupportLevel
import org.flowlang.conformance.MatchCompositionFixture
import org.flowlang.conformance.RetryCompositionFixture
import org.flowlang.distribution.reference.ReferenceTargetProjections
import org.flowlang.generators.manifest.*
import org.flowlang.materialization.TargetMaterializationRequest
import org.flowlang.modules.ModuleRegistry

class JenkinsMatchSequentialCompositionTests {
    private val root = File(".")
    private val targets by lazy { TargetRegistryYamlLoader.loadDirectory(File(root, "targets")) }
    private val provider get() = ReferenceTargetProjections.registry.requireProvider("jenkins")
    private val pipeline by lazy { ReferenceTargetProjections.pipeline(targets, modules = ModuleRegistry.fromDirectory(File(root, "modules"))) }
    private fun manifest(source: String) = pipeline.generate(TargetMaterializationRequest.fromCompilation(
        RetryCompositionFixture.compile(root, source), testTargetSelection("jenkins", targets), strict = true))
    private fun retry(body: String) = """retry { max: 2 delay: "0s" backoff: fixed } { $body }"""
    private fun loop(body: String, name: String = "entry") = """for $name in ["first", "first", "last"] { $body }"""
    private fun match(body: String, fallback: String = "", guard: String = "true") =
        """match "inert" { when $guard { $body } default { $fallback } }"""
    private fun source(body: String) = """
        version "1.0"
        use module "git" version "1.0"
        use module "docker" version "1.0"
        flow "mixed-selection" {
          input { enabled: boolean default true }
          systems { system "source" { type: git } system "builder" { type: docker } }
          steps { $body }
        }
    """.trimIndent()

    @Test fun selectedDefaultAndEmptyBranchesComposeInBothRetryDirections() {
        for (direction in listOf("retry-match", "match-retry")) {
            for (outcome in listOf("success", "default", "empty", "failure")) {
                val m = manifest(MatchCompositionFixture.interactionSource(direction, outcome))
                val artifact = provider.render(m)
                assertEquals(if (direction == "retry-match") "retry" else "loop", m.jobs.single().steps.first().type)
                assertContains(artifact, "else if (${outcome != "default"})")
                assertContains(artifact, "} else {")
                if (outcome != "empty") {
                    assertContains(artifact, "retry(2) {")
                    assertContains(artifact, "for (def entry in ['alternate', 'selected', 'selected'])")
                    assertEquals(direction == "retry-match", artifact.indexOf("retry(2)") < artifact.indexOf("else if ("))
                }
            }
        }
        assertEquals(SupportLevel.UNSUPPORTED, targets.getValue("jenkins").match)
    }

    @Test fun recursiveCompositionDoesNotDependOnTaskNamesCountsOrBranchPosition() {
        for (count in listOf(1, 2, 5)) {
            val leaves = (1..count).joinToString("\n") { RetryCompositionFixture.checkout("renamed$it") }
            for (default in listOf(false, true)) {
                val nested = loop(match(retry("if enabled { ${match(loop(leaves, "inner"))} }")), "outer")
                val body = if (default) match("", nested, "false") else match(nested)
                val artifact = provider.render(manifest(source(retry(body) + RetryCompositionFixture.checkout("successor"))))
                assertEquals(count + 1, artifact.lineSequence().count { it.trim() == "checkout scmGit(" })
                assertContains(artifact, "for (def inner")
                assertContains(artifact, "for (def outer")
            }
        }
        provider.render(manifest(source(match(loop(""), loop("")) + match(retry("")))))
    }

    @Test fun lexicalScopeCrossesEveryBranchWithoutCaptureOrSiblingLeakage() {
        val leaf = RetryCompositionFixture.checkout("leaf")
        val consumer = """if entry == "first" { $leaf }"""
        assertContains(provider.render(manifest(source(loop(match(retry(consumer)))))), "entry == 'first'")
        for (name in listOf("entry", "enabled", "params")) {
            val nested = retry(loop(leaf, name))
            for (branch in listOf(match(nested), match(nested, "", "false"), match("", nested)))
                assertFails { provider.render(manifest(source(loop(branch)))) }
        }
        assertFails { provider.render(manifest(source(match(loop(""), consumer)))) }
    }

    @Test fun nestedPoliciesAndLeavesAreValidatedEvenOnUnselectedPaths() {
        val leaf = RetryCompositionFixture.checkout("leaf")
        for (body in listOf(retry(leaf).replace("0s", "1s"), loop(leaf).replace("[\"first\", \"first\", \"last\"]", "[1]"),
            match(leaf, "", "enabled"), "approve manual { message: \"Decision\" }")) {
            assertFails { provider.render(manifest(source(retry(match(loop(body), "", "false"))))) }
            assertFails { provider.render(manifest(source(loop(match("", retry(body)))))) }
        }
        val review = manifest(source(match(retry(loop("docker.push builder { image: \"example/app:test\" }")))))
        assertEquals(TargetRenderMode.REVIEW_ONLY, TargetRenderPolicy.evaluate(review).mode)
        assertFailsWith<TargetRenderBlockedException> { provider.render(review) }
    }

    @Test fun valueAndWorkspaceContinuityRemainIndependentOfControlSupport() {
        val producer = """git.checkout source { url: "https://example.invalid/repo.git" branch: "main" } -> result"""
        val consumer = """if result.ok == true { ${RetryCompositionFixture.checkout("consumer")} }"""
        assertContains(provider.render(manifest(source(match(loop(retry("$producer $consumer")))))), "if (flowCheckoutSucceeded")
        for (body in listOf(match(retry(producer), consumer), match(loop(producer)) + consumer))
            assertFails { provider.render(manifest(source(body))) }
        assertFailsWith<UnresolvedPlanningContinuityException> {
            manifest(source(match(loop("$producer docker.build builder { image: \"example/app:test\" path: \".\" push: false }"))))
        }
    }

    @Test fun forgedManifestsCannotHideInvalidBranchesPoliciesOrScope() {
        val m = manifest(source(loop(match(retry(loop(RetryCompositionFixture.checkout("leaf"), "inner"))))))
        val job = m.jobs.single(); val outer = job.steps.single(); val selection = outer.children.single()
        val branch = selection.children.first(); val attempt = branch.children.single(); val inner = attempt.children.single()
        fun render(changed: TargetStep) = provider.renderer.render(m.copy(jobs = listOf(job.copy(
            steps = listOf(outer.copy(children = listOf(changed)))))))
        val changedBodies = listOf(
            attempt.copy(params = attempt.params + ("delay" to "1s")),
            attempt.copy(children = listOf(inner.copy(params = inner.params + ("item" to "entry")))),
            attempt.copy(children = listOf(inner.copy(params = inner.params + ("item" to "enabled")))),
            attempt.copy(rendererPayload = attempt.rendererPayload!!.copy(reference = "unknown")),
            attempt.copy(type = "approval"))
        for (body in changedBodies) {
            assertFails { render(selection.copy(children = listOf(branch.copy(children = listOf(body))) + selection.children.drop(1))) }
            assertFails { render(selection.copy(children = selection.children.map {
                if (it.type == "match-default") it.copy(children = listOf(body)) else it
            })) }
        }
        for (changed in listOf(selection.copy(children = selection.children.reversed()),
            selection.copy(params = selection.params + ("source" to "enabled")),
            selection.copy(children = selection.children.map {
                if (it.type == "match-error") it.copy(children = listOf(attempt)) else it
            }), selection.copy(children = selection.children.map {
                if (it.type == "match-default") it.copy(params = mapOf("condition" to "false")) else it
            }))) assertFails { render(changed) }
    }
}
