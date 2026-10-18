import java.io.File
import kotlin.test.*
import org.flowlang.adapters.yaml.TargetRegistryYamlLoader
import org.flowlang.capabilities.SupportLevel
import org.flowlang.conformance.RetryCompositionFixture
import org.flowlang.conformance.GitHubConditionCompositionFixture
import org.flowlang.ast.ActionNode
import org.flowlang.ast.IfNode
import org.flowlang.ast.RetryNode
import org.flowlang.distribution.reference.ReferenceTargetProjections
import org.flowlang.generators.manifest.*
import org.flowlang.materialization.TargetMaterializationRequest
import org.flowlang.modules.ModuleRegistry
import org.flowlang.targets.builtin.JenkinsRetryProjectionScope

class JenkinsRetryCompositionTests {
    private val root = File(".")
    private val targets by lazy { TargetRegistryYamlLoader.loadDirectory(File(root, "targets")) }
    private val provider get() = ReferenceTargetProjections.registry.requireProvider("jenkins")
    private val pipeline by lazy { ReferenceTargetProjections.pipeline(targets,
        modules = ModuleRegistry.fromDirectory(File(root, "modules"))) }
    private fun compile(source: String) = RetryCompositionFixture.compile(root, source)
    private fun manifest(source: String = RetryCompositionFixture.source()) = pipeline.generate(
        TargetMaterializationRequest.fromCompilation(compile(source), testTargetSelection("jenkins", targets), strict = true))
    private fun source(body: String) = """
        version "1.0"
        use module "git" version "1.0"
        use module "docker" version "1.0"
        flow "composed-body" {
          input { enabled: boolean default true }
          systems { system "source" { type: git } system "builder" { type: docker } }
          steps { retry { max: 2 delay: "0s" backoff: fixed } { $body } }
        }
    """.trimIndent()

    @Test fun sequentialCompositionUsesIndependentLeafAndControlContracts() {
        val checkout = RetryCompositionFixture.checkout("producer")
        val text = source("""
            $checkout
            if enabled { docker.build builder { image: "example/app:test" path: "." push: false } }
            retry { max: 4 delay: "0s" backoff: fixed } { ${RetryCompositionFixture.checkout("tail")} }
        """)
        // Ordering alone cannot supply workspace continuity. The existing parsed-AST boundary
        // carries the explicit dependency that Flow Source does not yet spell in its grammar.
        assertFailsWith<UnresolvedPlanningContinuityException> { manifest(text) }
        val document = compile(text).ast
        val retry = document.flow.steps.single() as RetryNode
        val bound = retry.copy(steps = retry.steps.map { step ->
            if (step is IfNode) step.copy(then = step.then.map { (it as ActionNode).copy(dependsOn = listOf("producer")) }) else step
        })
        val unit = GitHubConditionCompositionFixture.compile(root, document.copy(flow = document.flow.copy(steps = listOf(bound))))
        val artifact = provider.render(pipeline.generate(TargetMaterializationRequest.fromCompilation(unit,
            testTargetSelection("jenkins", targets), strict = true)))
        assertContains(artifact, "retry(2) {"); assertContains(artifact, "retry(4) {")
        assertContains(artifact, "docker.build("); assertContains(artifact, "if (params.enabled)")
        assertEquals(SupportLevel.UNSUPPORTED, targets.getValue("jenkins").features["retry.task"])
    }

    @Test fun identitiesTaskCountsAndIndependentSiblingsDoNotDefineEligibility() {
        for (count in listOf(1, 2, 5)) {
            val body = (1..count).joinToString("\n") { RetryCompositionFixture.checkout("renamed$it") }
            val artifact = provider.render(manifest(source(body)))
            assertEquals(count, artifact.lineSequence().count { it.trim() == "checkout scmGit(" })
        }
        val text = RetryCompositionFixture.source().replace("prefix", "renamedProducer")
        assertContains(provider.render(manifest(text)), "retry(3) {")
    }

    @Test fun emptyBodiesAreSuccessfulNoOpsAndDoNotInventAttempts() {
        assertContains(provider.render(manifest(source(""))), "retry(2) {\n")
        assertContains(provider.render(manifest(source("retry { max: 3 delay: \"0s\" backoff: fixed } { }"))), "retry(3) {")
    }

    @Test fun everyNestedPolicyAndProviderScopeMustBeProven() {
        val original = RetryCompositionFixture.source()
        for (source in listOf(original.replace("max: 3 delay: \"0s\"", "max: 3 delay: \"1s\""),
            original.replace("backoff: fixed", "backoff: exponential"))) assertFails { manifest(source) }
        val declared = targets.getValue("jenkins")
        val catalog = provider.nativeProjectionCatalog
        val broad = TargetNativeProjectionCatalog.of("jenkins", emptyList(), structuralDefinitions =
            catalog.structuralDefinitions.filter { it.structure == TargetStructuralProjectionKind.RETRY }.map {
                it.copy(supportScope = TargetStructuralProjectionSupportScope.TARGET_WIDE) })
        assertEquals(declared, JenkinsRetryProjectionScope(broad).resolve(compile(original).authorization, "jenkins", declared))
    }

    @Test fun unsupportedLeafBindingsAndControlMappingsCannotBorrowRetrySupport() {
        val missingLeaf = manifest(source("docker.push builder { image: \"example/app:test\" }"))
        assertEquals(TargetRenderMode.REVIEW_ONLY, TargetRenderPolicy.evaluate(missingLeaf).mode)
        assertFalse(missingLeaf.compatibility.executable)
        assertFalse(provider.render(missingLeaf).contains("retry(2)"))
        for (body in listOf("approve manual { message: \"Replay decision\" }",
            "for item in [\"one\"] { ${RetryCompositionFixture.checkout("child")} }"))
            assertFails { manifest(source(body)) }
        val m = manifest(); val job = m.jobs.single(); val retry = job.steps.first()
        val child = retry.children.first()
        for (replacement in listOf(child.copy(rendererPayload = child.rendererPayload!!.copy(reference = "missing")),
            child.copy(type = "approval", children = emptyList()),
            retry.copy(id = "nested", params = retry.params + ("delay" to "1s"), children = listOf(child)))) {
            val forged = m.copy(jobs = listOf(job.copy(steps = listOf(retry.copy(children = listOf(replacement))) + job.steps.drop(1))))
            assertFails { provider.renderer.render(forged) }
        }
    }

    @Test fun bindingsAreEstablishedInsideEachAttemptAndNeverEscapeIt() {
        val body = """git.checkout source { url: "https://example.invalid/repo.git" branch: "main" } -> result
            if result.ok == true { ${RetryCompositionFixture.checkout("consumer")} }"""
        val text = provider.render(manifest(source(body)))
        assertTrue(text.indexOf("retry(2)") < text.indexOf("def flowCheckoutSucceeded = true"))
        assertTrue(text.indexOf("def flowCheckoutSucceeded = true") < text.indexOf("if (flowCheckoutSucceeded"))
        val escaped = source(body).replace("steps { retry", "steps { if result.ok == true { ${RetryCompositionFixture.checkout("escaped")} } retry")
        assertFails { manifest(escaped) }
    }
}
