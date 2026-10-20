import java.io.File
import kotlin.test.*
import org.flowlang.adapters.yaml.TargetRegistryYamlLoader
import org.flowlang.capabilities.SupportLevel
import org.flowlang.conformance.LoopCompositionFixture
import org.flowlang.conformance.RetryCompositionFixture
import org.flowlang.distribution.reference.ReferenceTargetProjections
import org.flowlang.generators.manifest.*
import org.flowlang.materialization.TargetMaterializationRequest
import org.flowlang.modules.ModuleRegistry
import org.flowlang.targets.builtin.flatten

class JenkinsRetryLoopCompositionTests {
    private val root = File(".")
    private val targets by lazy { TargetRegistryYamlLoader.loadDirectory(File(root, "targets")) }
    private val provider get() = ReferenceTargetProjections.registry.requireProvider("jenkins")
    private val pipeline by lazy { ReferenceTargetProjections.pipeline(targets, modules = ModuleRegistry.fromDirectory(File(root, "modules"))) }
    private fun manifest(source: String) = pipeline.generate(TargetMaterializationRequest.fromCompilation(
        RetryCompositionFixture.compile(root, source), testTargetSelection("jenkins", targets), strict = true))
    private fun retry(body: String) = """retry { max: 2 delay: "0s" backoff: fixed } { $body }"""
    private fun loop(body: String, name: String = "entry") = """for $name in ["first", "first", "last"] { $body }"""
    private fun source(body: String) = """
        version "1.0"
        use module "git" version "1.0"
        use module "docker" version "1.0"
        flow "mixed-controls" {
          input { enabled: boolean default true }
          systems { system "source" { type: git } system "builder" { type: docker } }
          steps { $body }
        }
    """.trimIndent()

    @Test fun bothNestingDirectionsPreserveAuthoredControlOwnership() {
        for (direction in listOf("retry-loop", "loop-retry")) {
            for (outcome in listOf("success", "failure", "empty")) {
                val m = manifest(LoopCompositionFixture.interactionSource(direction, outcome))
                val artifact = provider.render(m)
                val root = m.jobs.single().steps.first()
                assertEquals(if (direction == "retry-loop") "retry" else "loop", root.type)
                assertTrue(root.children.any { it.type == if (direction == "retry-loop") "loop" else "retry" })
                assertContains(artifact, "retry(2) {")
                assertContains(artifact, "for (def outer in ${if (outcome == "empty") "[]" else "['alternate', 'selected', 'selected']"}) {")
                assertEquals(direction == "retry-loop", artifact.indexOf("retry(2)") < artifact.indexOf("for (def outer"))
            }
        }
        assertEquals(SupportLevel.UNSUPPORTED, targets.getValue("jenkins").features["retry.task"])
        assertEquals(SupportLevel.UNSUPPORTED, targets.getValue("jenkins").dynamicLoops)
    }

    @Test fun alternatingControlsComposeWithoutIdentityOrCardinalityAllowlists() {
        for (count in listOf(1, 2, 5)) {
            val leaves = (1..count).joinToString("\n") { RetryCompositionFixture.checkout("renamed$it") }
            val body = retry(loop("if enabled { ${retry(loop(leaves, "inner"))} }", "outer"))
            val artifact = provider.render(manifest(source(body + RetryCompositionFixture.checkout("successor"))))
            assertEquals(count + 1, artifact.lineSequence().count { it.trim() == "checkout scmGit(" })
            assertEquals(2, Regex("retry\\(2\\)").findAll(artifact).count())
            assertContains(artifact, "for (def outer")
            assertContains(artifact, "for (def inner")
        }
        provider.render(manifest(source(retry(loop("")) + retry(loop("")))))
    }

    @Test fun retryBoundariesPreserveLexicalBindingsAndRejectCapture() {
        val valid = loop(retry("""if entry == "first" { ${loop(RetryCompositionFixture.checkout("leaf"), "inner")} }"""))
        assertContains(provider.render(manifest(source(valid))), "entry == 'first'")
        for (name in listOf("entry", "enabled", "params")) {
            assertFails { provider.render(manifest(source(loop(retry(loop(RetryCompositionFixture.checkout("leaf"), name)))))) }
        }
        assertFails { manifest(source(retry(loop("")) + """if entry == "first" { ${RetryCompositionFixture.checkout("escaped")} }""")) }
    }

    @Test fun everyNestedPolicyCollectionAndControlStillRequiresSupport() {
        val leaf = RetryCompositionFixture.checkout("leaf")
        for (body in listOf(loop(retry(leaf).replace("0s", "1s")), retry(loop(leaf).replace("[\"first\", \"first\", \"last\"]", "[1, 2]")),
            loop(retry(leaf).replace("backoff: fixed", "backoff: exponential")),
            retry(loop("approve manual { message: \"Decision\" }")))) {
            assertFails { provider.render(manifest(source(body))) }
        }
        val review = manifest(source(retry(loop("docker.push builder { image: \"example/app:test\" }"))))
        assertEquals(TargetRenderMode.REVIEW_ONLY, TargetRenderPolicy.evaluate(review).mode)
        assertFailsWith<TargetRenderBlockedException> { provider.render(review) }
    }

    @Test fun nestedLeafResultsRemainLocalAndContinuityIsNotInvented() {
        val producer = """git.checkout source { url: "https://example.invalid/repo.git" branch: "main" } -> result"""
        val consumer = """if result.ok == true { ${RetryCompositionFixture.checkout("consumer")} }"""
        val artifact = provider.render(manifest(source(loop(retry("$producer $consumer")))))
        assertTrue(artifact.indexOf("retry(2)") < artifact.indexOf("def flowCheckoutSucceeded = true"))
        assertTrue(artifact.indexOf("def flowCheckoutSucceeded = true") < artifact.indexOf("if (flowCheckoutSucceeded"))
        // Definite availability in Core does not establish a native child-scope export.
        val escapedRetry = manifest(source(loop(retry(producer) + consumer)))
        assertEquals(TargetRenderMode.REVIEW_ONLY, TargetRenderPolicy.evaluate(escapedRetry).mode)
        assertFalse(escapedRetry.compatibility.executable)
        assertFailsWith<TargetRenderBlockedException> { provider.render(escapedRetry) }
        assertFails { provider.render(manifest(source(retry(loop(producer) + consumer)))) }
        assertFailsWith<UnresolvedPlanningContinuityException> {
            manifest(source(retry(loop("$producer docker.build builder { image: \"example/app:test\" path: \".\" push: false }"))))
        }
    }

    @Test fun forgedMixedManifestsCannotBypassPolicyScopeOrLeafGates() {
        val m = manifest(source(loop(retry(loop(RetryCompositionFixture.checkout("leaf"), "inner")))))
        val job = m.jobs.single(); val outer = job.steps.single(); val retry = outer.children.single(); val inner = retry.children.single()
        for (changed in listOf(inner.copy(params = inner.params + ("item" to "entry")),
            inner.copy(params = inner.params + ("item" to "enabled")),
            inner.copy(params = inner.params + ("source" to "dynamicItems")),
            inner.copy(type = "approval"), inner.copy(rendererPayload = inner.rendererPayload!!.copy(reference = "missing")))) {
            val changedRetry = retry.copy(children = listOf(changed))
            val changedOuter = outer.copy(children = listOf(changedRetry))
            val forged = m.copy(jobs = listOf(job.copy(steps = listOf(changedOuter))))
            assertFails { provider.renderer.render(forged) }
        }
        for (policy in listOf(retry.params - "delay", retry.params + ("delay" to "1s"), retry.params + ("max" to "0"))) {
            val changedOuter = outer.copy(children = listOf(retry.copy(params = policy)))
            val forged = m.copy(jobs = listOf(job.copy(steps = listOf(changedOuter))))
            assertFails { provider.renderer.render(forged) }
        }
        assertEquals(1, m.jobs.single().steps.flatMap { it.flatten() }.count { it.type == "action" })
    }
}
