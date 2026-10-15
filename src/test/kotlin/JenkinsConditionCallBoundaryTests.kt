import java.io.File
import kotlin.test.*
import org.flowlang.adapters.yaml.TargetRegistryYamlLoader
import org.flowlang.compiler.requireAccepted
import org.flowlang.distribution.reference.ReferenceTargetProjections
import org.flowlang.frontend.FrontendCompilerComposition
import org.flowlang.frontend.source.FlowSourceFrontend
import org.flowlang.generators.manifest.*
import org.flowlang.materialization.TargetDiagnosticMaterializationRequest
import org.flowlang.materialization.TargetMaterializationRequest
import org.flowlang.modules.ModuleRegistry
import org.flowlang.safety.StandardEnvironmentSafetyPolicyNotes

class JenkinsConditionCallBoundaryTests {
    private val modules by lazy { ModuleRegistry.fromDirectory(File("modules")) }
    private val targets by lazy { TargetRegistryYamlLoader.loadDirectory(File("targets")) }
    private val pipeline by lazy { ReferenceTargetProjections.pipeline(targets, modules = modules) }
    private val provider get() = ReferenceTargetProjections.registry.requireProvider("jenkins")
    private fun compile(expression: String) = kotlin.io.path.createTempFile(suffix = ".flow").toFile().let { file ->
        try {
            file.writeText("""
                version "1.0"
                use module "git" version "1.0"
                flow "condition-calls" {
                  input { name: text default "selected" }
                  systems { system "source" { type: git } }
                  steps {
                    if $expression {
                      git.checkout source { url: "https://example.invalid/repo.git" branch: "selected" }
                    }
                  }
                }
            """.trimIndent())
            FlowSourceFrontend(FrontendCompilerComposition.compiler(modules,
                StandardEnvironmentSafetyPolicyNotes.policy(File(".")))).compile(file).requireAccepted()
        } finally { file.delete() }
    }

    @Test fun acceptedCanonicalCallsRemainReviewOnlyWithoutNativeFunctionContracts() {
        for (expression in listOf("echo(\"message\") == null", "readFile(name) != null", "helper() == true")) {
            val unit = compile(expression)
            val manifest = pipeline.generateDiagnosticEvidence(TargetDiagnosticMaterializationRequest.fromCompilation(unit,
                testTargetSelection("jenkins", targets)))
            val condition = manifest.jobs.single().steps.single()
            assertEquals(TargetMaterializationStatus.ADAPTER_REQUIRED, condition.materialization.status)
            assertNull(condition.rendererPayload)
            assertContains(condition.materialization.reason, "no native function binding")
            assertEquals("selected", condition.children.single().params["branch"])
            assertTrue(TargetRendererContractValidator.validate(manifest, "jenkins").valid)
            assertEquals(TargetRenderMode.REVIEW_ONLY, TargetRenderPolicy.evaluate(manifest).mode)
            assertContains(TargetReviewArtifactRenderer.render(manifest, TargetRenderPolicy.evaluate(manifest)), "function")
            assertFails { provider.render(manifest) }
            assertFails { provider.render(pipeline.generate(TargetMaterializationRequest.fromCompilation(unit,
                testTargetSelection("jenkins", targets), strict = true))) }
        }
    }

    @Test fun directExecutableRendererCannotAuthorizeCallsThroughForgedReadiness() {
        val unit = compile("true")
        val valid = pipeline.generate(TargetMaterializationRequest.fromCompilation(unit,
            testTargetSelection("jenkins", targets), strict = true))
        assertContains(provider.render(valid), "if (true)")
        for (expression in listOf("echo(\"message\") == null", "name in [readFile(name)]", "true or helper() == true")) {
            val forged = valid.copy(jobs = valid.jobs.map { job -> job.copy(steps = job.steps.map {
                it.copy(params = mapOf("condition" to expression))
            }) })
            val failure = assertFailsWith<IllegalArgumentException> { provider.renderer.render(forged) }
            assertContains(failure.message.orEmpty(), "no native function binding")
        }
    }

    @Test fun typedSecretAndLiteralConditionsStillRenderNativeArtifacts() {
        for (expression in listOf("name == \"selected\"", "secret(\"token\") != null", "name in [\"echo(name)\"]")) {
            val unit = compile(expression)
            val manifest = pipeline.generate(TargetMaterializationRequest.fromCompilation(unit,
                testTargetSelection("jenkins", targets), strict = true))
            val text = provider.render(manifest)
            assertEquals(TargetRenderMode.EXECUTABLE, TargetRenderPolicy.evaluate(manifest).mode)
            assertEquals(1, Regex("git branch:").findAll(text).count())
            if (expression.startsWith("secret")) {
                assertContains(text, "credentials('token')")
                assertContains(text, "env.FLOW_SECRET_TOKEN")
            }
        }
    }
}
