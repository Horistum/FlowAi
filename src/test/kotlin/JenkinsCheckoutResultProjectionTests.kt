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

class JenkinsCheckoutResultProjectionTests {
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

    private fun native(expected: Boolean = true): TargetManifest = pipeline.generate(TargetMaterializationRequest.fromCompilation(
        compile("${checkout("first")} -> result if result.ok == $expected { ${checkout("second")} }"),
        testTargetSelection("jenkins", targets), strict = true))

    @Test fun successfulProducerBindsBothBooleanGuardsAfterNativeCompletion() {
        for (expected in listOf(true, false)) {
            val manifest = native(expected)
            assertEquals(TargetRenderMode.EXECUTABLE, TargetRenderPolicy.evaluate(manifest).mode)
            val text = provider.render(manifest)
            assertContains(text, "def flowCheckoutSucceeded = true")
            assertContains(text, "if (flowCheckoutSucceeded == $expected)")
            assertTrue(text.indexOf("git branch: 'first'") < text.indexOf("def flowCheckoutSucceeded"))
            assertTrue(text.indexOf("def flowCheckoutSucceeded") < text.indexOf("if (flowCheckoutSucceeded"))
            assertFalse(text.lineSequence().filterNot { it.trimStart().startsWith("//") }.any { it.contains("result.ok") }); assertFalse(text.contains("def result"))
        }
    }

    @Test fun unsupportedCanonicalConsumersPreserveReviewEvidence() {
        for (expression in listOf("result.path == \"repo\"")) {
            val unit = compile("${checkout("first")} -> result if $expression { ${checkout("second")} }")
            val m = pipeline.generateDiagnosticEvidence(TargetDiagnosticMaterializationRequest.fromCompilation(unit,
                testTargetSelection("jenkins", targets)))
            val guard = m.jobs.single().steps.last()
            assertEquals(TargetMaterializationStatus.ADAPTER_REQUIRED, guard.materialization.status, expression)
            assertNull(guard.rendererPayload); assertEquals(1, guard.children.size)
            assertFails { provider.render(m) }
        }
    }

    @Test fun executableRendererRejectsWidenedExpressionsAndMissingProducer() {
        val m = native(); val job = m.jobs.single(); val producer = job.steps.first(); val guard = job.steps.last()
        for (expression in listOf("result.path == true", "result == true", "result[\"ok\"] == true",
            "result?.ok == true", "exists(result.ok)")) {
            val forged = m.copy(jobs = listOf(job.copy(steps = listOf(producer, guard.copy(params = mapOf("condition" to expression))))))
            assertFails { provider.renderer.render(forged) }
        }
        assertFails { provider.renderer.render(m.copy(jobs = listOf(job.copy(steps = listOf(guard))))) }
    }

    @Test fun executableRendererRejectsExpandedShapeAndScope() {
        val m = native(); val job = m.jobs.single(); val producer = job.steps.first(); val guard = job.steps.last()
        val child = guard.children.single()
        val variants = listOf(
            listOf(producer.copy(dependsOn = listOf("outside")), guard),
            listOf(producer.copy(rendererPayload = producer.rendererPayload!!.copy(reference = "other")), guard),
            listOf(producer, guard.copy(children = listOf(child.copy(dependsOn = listOf("outside"))))),
            listOf(producer, guard.copy(id = producer.id)),
            listOf(guard, producer),
            listOf(guard.copy(id = "outer", params = mapOf("condition" to "true"), children = listOf(producer)), guard)
        )
        for (steps in variants) assertFails { provider.renderer.render(m.copy(jobs = listOf(job.copy(steps = steps)))) }
        assertFails { provider.renderer.render(m.copy(jobs = listOf(job.copy(steps = listOf(producer)),
            job.copy(id = "second-job", steps = listOf(guard))))) }
    }
    @Test fun shallowAndImmutableRevisionShapesStayOutsideTheBoundedContract() {
        val m = native(); val job = m.jobs.single()
        for (index in listOf(0)) for ((name, value) in listOf("depth" to "1", "branch" to "a".repeat(40))) {
            val producer = job.steps.first(); val guard = job.steps.last()
            val step = if (index == 0) producer else guard.children.single()
            val payload = step.rendererPayload!!
            val binding = org.flowlang.projection.ProjectionBinding.literal(value).copy(
                resolutionStatus = org.flowlang.projection.ProjectionBindingResolutionStatus.RESOLVED)
            val changed = step.copy(rendererPayload = payload.copy(bindings = payload.bindings + (name to binding)))
            val steps = if (index == 0) listOf(changed, guard) else listOf(producer, guard.copy(children = listOf(changed)))
            assertFails { provider.renderer.render(m.copy(jobs = listOf(job.copy(steps = steps)))) }
        }
    }

    private fun materialize(body: String, declarations: String = ""): TargetManifest =
        pipeline.generate(TargetMaterializationRequest.fromCompilation(compile(body, declarations),
            testTargetSelection("jenkins", targets), strict = true))

    @Test fun independentInputsAndStepsDoNotInvalidateAnExistingBinding() {
        val original = "${checkout("first")} -> result if result.ok == true { ${checkout("second")} }"
        for (prefix in listOf("", checkout("before"))) for (suffix in listOf("", checkout("after"))) {
            val text = provider.render(materialize("$prefix $original $suffix", "input { enabled: boolean default true }"))
            assertContains(text, "if (flowCheckoutSucceeded == true)")
            assertEquals(1, Regex("def flowCheckoutSucceeded = true").findAll(text).count())
            assertTrue(text.indexOf("git branch: 'first'") < text.indexOf("def flowCheckoutSucceeded"))
        }
    }

    @Test fun existingBooleanExpressionsConsumeExplicitValuesWithoutAmbientAliases() {
        for (expression in listOf("result.ok != false", "not (result.ok == false)",
            "result.ok == true and enabled == true", "true == result.ok")) {
            val text = provider.render(materialize("${checkout("first")} -> result if $expression { ${checkout("second")} }",
                "input { enabled: boolean default true }"))
            assertContains(text, "def flowCheckoutSucceeded = true")
            assertFalse(text.lineSequence().filterNot { it.trimStart().startsWith("//") }.any { it.contains("result.ok") })
        }
    }

    @Test fun multipleProducersAndRepeatedNestedConsumersHaveDistinctBindings() {
        val text = provider.render(materialize("""
            ${checkout("first")} -> first
            ${checkout("second")} -> second
            if first.ok == true {
                ${checkout("inner")} -> inner
                if second.ok == true and inner.ok == true { ${checkout("consumer")} }
            }
            if first.ok == false { ${checkout("unused")} }
        """))
        assertContains(text, "def flowCheckoutSucceeded = true")
        assertContains(text, "def flowCheckoutSucceeded2 = true")
        assertContains(text, "def flowCheckoutSucceeded3 = true")
        assertContains(text, "flowCheckoutSucceeded2 == true")
        assertContains(text, "flowCheckoutSucceeded3 == true")
        assertContains(text, "if (flowCheckoutSucceeded == false)")
        assertTrue(text.indexOf("git branch: 'inner'") < text.indexOf("def flowCheckoutSucceeded3"))
    }

    @Test fun consumerBodiesCanComposeDifferentNativeActionsAndMultipleChildren() {
        val text = provider.render(materialize("""
            ${checkout("first")} -> result
            if result.ok == true {
                approve manual { message: "Review the result?" }
                ${checkout("second")}
                ${checkout("third")}
            }
        """))
        assertContains(text, "input message: 'Review the result?'")
        assertContains(text, "git branch: 'third'")
        assertTrue(text.indexOf("if (flowCheckoutSucceeded") < text.indexOf("input message:"))
    }

    @Test fun nestedProducersCannotEscapeOrSupplySiblingBranches() {
        val m = native(); val job = m.jobs.single(); val producer = job.steps.first(); val guard = job.steps.last()
        val wrapper = guard.copy(id = "outer", params = mapOf("condition" to "true"), children = listOf(producer))
        val sibling = wrapper.copy(id = "sibling", children = listOf(guard))
        for (steps in listOf(listOf(wrapper, guard), listOf(wrapper, sibling), listOf(guard, producer))) {
            assertFails { provider.renderer.render(m.copy(jobs = listOf(job.copy(steps = steps)))) }
        }
        assertFails { provider.renderer.render(m.copy(jobs = listOf(job.copy(steps = listOf(producer,
            producer.copy(id = "duplicate-alias"), guard))))) }
    }

    @Test fun rendererOwnedNamesCannotCaptureLoopBindings() {
        val text = provider.render(materialize("""
            ${checkout("first")} -> result
            for flowCheckoutSucceeded in ["item"] {
                if result.ok == true { ${checkout("second")} }
            }
        """))
        assertContains(text, "def flowCheckoutSucceeded2 = true")
        assertContains(text, "if (flowCheckoutSucceeded2 == true)")
        assertFalse(text.contains("def flowCheckoutSucceeded = true"))
    }

    @Test fun unrelatedNativeCheckoutModesDoNotChangeTheProducerContract() {
        val m = native(); val job = m.jobs.single(); val guard = job.steps.last(); val child = guard.children.single()
        val payload = child.rendererPayload!!
        val depth = org.flowlang.projection.ProjectionBinding.literal("1").copy(
            resolutionStatus = org.flowlang.projection.ProjectionBindingResolutionStatus.RESOLVED)
        val changed = child.copy(rendererPayload = payload.copy(bindings = payload.bindings + ("depth" to depth)))
        val text = provider.renderer.render(m.copy(jobs = listOf(job.copy(steps = listOf(job.steps.first(),
            guard.copy(children = listOf(changed)))))))
        assertContains(text, "def flowCheckoutSucceeded = true")
        assertContains(text, "cloneOption(depth: 1")
    }
}
