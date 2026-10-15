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

class JenkinsConditionValueBoundaryTests {
    private val modules by lazy { ModuleRegistry.fromDirectory(File("modules")) }
    private val targets by lazy { TargetRegistryYamlLoader.loadDirectory(File("targets")) }
    private val pipeline by lazy { ReferenceTargetProjections.pipeline(targets, modules = modules) }
    private val provider get() = ReferenceTargetProjections.registry.requireProvider("jenkins")
    private fun checkout(branch: String) = """git.checkout source { url: "https://example.invalid/repo.git" branch: "$branch" }"""
    private fun compile(body: String, declarations: String = "") = kotlin.io.path.createTempFile(suffix = ".flow").toFile().let { file ->
        try {
            file.writeText("""
                version "1.0"
                use module "git" version "1.0"
                flow "condition-values" {
                  $declarations
                  systems { system "source" { type: git } }
                  steps { $body }
                }
            """.trimIndent())
            FlowSourceFrontend(FrontendCompilerComposition.compiler(modules,
                StandardEnvironmentSafetyPolicyNotes.policy(File(".")))).compile(file).requireAccepted()
        } finally { file.delete() }
    }

    @Test fun canonicalResultConditionRetainsMeaningButCannotClaimExecutableProjection() {
        val unit = compile("${checkout("first")} -> result if result.ok == true { ${checkout("second")} }")
        val manifest = pipeline.generateDiagnosticEvidence(TargetDiagnosticMaterializationRequest.fromCompilation(unit,
            testTargetSelection("jenkins", targets)))
        val steps = manifest.jobs.single().steps
        assertEquals("result", steps.first().metadata["resultName"])
        val condition = steps.single { it.type == "condition" }
        assertContains(condition.params.getValue("condition"), "result.ok")
        assertEquals("second", condition.children.single().params["branch"])
        assertEquals(TargetMaterializationStatus.ADAPTER_REQUIRED, condition.materialization.status)
        assertNull(condition.rendererPayload)
        assertContains(condition.materialization.reason, "no native value binding for result")
        assertTrue(TargetRendererContractValidator.validate(manifest, "jenkins").valid)
        assertEquals(TargetRenderMode.REVIEW_ONLY, TargetRenderPolicy.evaluate(manifest).mode)
        assertContains(TargetReviewArtifactRenderer.render(manifest, TargetRenderPolicy.evaluate(manifest)), "result")
        assertFails { provider.render(manifest) }
        assertFails { provider.render(pipeline.generate(TargetMaterializationRequest.fromCompilation(unit,
            testTargetSelection("jenkins", targets), strict = true))) }
    }

    @Test fun declaredInputAndUnusedResultAliasKeepNativeProjection() {
        val unit = compile("${checkout("first")} -> unused if enabled == true { ${checkout("second")} }",
            "input { enabled: boolean default true }")
        val manifest = pipeline.generate(TargetMaterializationRequest.fromCompilation(unit,
            testTargetSelection("jenkins", targets), strict = true))
        val artifact = provider.render(manifest)
        assertContains(artifact, "params.enabled")
        assertEquals(2, Regex("git branch:").findAll(artifact).count())
        assertFalse(artifact.contains("def unused"))
    }

    @Test fun directExecutableRendererCannotBypassMissingBindingCheck() {
        val unit = compile("if true { ${checkout("selected")} }")
        val valid = pipeline.generate(TargetMaterializationRequest.fromCompilation(unit,
            testTargetSelection("jenkins", targets), strict = true))
        assertContains(provider.render(valid), "if (true)")
        val guard = valid.jobs.single().steps.single()
        for (expression in listOf("result.ok == true", "currentBuild.result != null", "params.enabled == true")) {
            val forged = valid.copy(jobs = valid.jobs.map { it.copy(steps = listOf(
                guard.copy(params = mapOf("condition" to expression)))) })
            val failure = assertFailsWith<IllegalArgumentException> { provider.renderer.render(forged) }
            assertContains(failure.message.orEmpty(), "no native value binding")
        }
    }
}
