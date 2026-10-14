import java.io.File
import kotlin.test.*
import org.flowlang.adapters.yaml.TargetRegistryYamlLoader
import org.flowlang.capabilities.SupportLevel
import org.flowlang.compiler.requireAccepted
import org.flowlang.distribution.reference.ReferenceTargetProjections
import org.flowlang.frontend.FrontendCompilerComposition
import org.flowlang.frontend.source.FlowSourceFrontend
import org.flowlang.generators.manifest.*
import org.flowlang.materialization.TargetDiagnosticMaterializationRequest
import org.flowlang.materialization.TargetMaterializationRequest
import org.flowlang.modules.ModuleRegistry
import org.flowlang.planner.*
import org.flowlang.safety.StandardEnvironmentSafetyPolicyNotes

class RetryBoundaryProjectionTests {
    companion object {
        private val modules by lazy { ModuleRegistry.fromDirectory(File("modules")) }
        private val targets by lazy { TargetRegistryYamlLoader.loadDirectory(File("targets")) }
        private val pipeline by lazy { ReferenceTargetProjections.pipeline(targets, modules = modules) }
        private val providers = listOf("jenkins", "github-actions", "tekton")
        private val jobProviders = providers.drop(1)
        private fun checkout(branch: String) = """git.checkout source { url: "https://github.com/Horistum/FlowAi.git" branch: "$branch" }"""
        private fun source(body: String) = """
            version "1.0"
            use module "git" version "1.0"
            flow "retry-boundaries" {
              systems { system "source" { type: git } }
              steps { $body }
            }
        """.trimIndent()
        private fun retry(body: String, policy: String = """max: 5 delay: "2s" backoff: exponential""") = "retry { $policy } { $body }"
        private fun compile(body: String) = kotlin.io.path.createTempFile(suffix = ".flow").toFile().let { file ->
            try {
                file.writeText(source(body))
                FlowSourceFrontend(FrontendCompilerComposition.compiler(modules,
                    StandardEnvironmentSafetyPolicyNotes.policy(File(".")))).compile(file).requireAccepted()
            } finally { file.delete() }
        }
        private val unit by lazy { compile(retry(checkout("first") + " " + checkout("second")) + " " + checkout("after")) }
        private val manifests by lazy { providers.associateWith { target ->
            pipeline.generateDiagnosticEvidence(TargetDiagnosticMaterializationRequest.fromCompilation(unit,
                testTargetSelection(target, targets)))
        } }
    }
    private fun roots(manifest: TargetManifest) = manifest.jobs.flatMap { it.steps }
    private fun retryRoot(manifest: TargetManifest) = roots(manifest).single { it.type == "retry" }

    @Test fun sourcePolicyBodyOrderAndSuccessorRemainSeparateAcrossProviders() {
        val plan = unit.authorization.executionPlan.nodes.first() as RetryGroupNode
        for ((target, manifest) in manifests) {
            val group = retryRoot(manifest)
            assertTrue(TargetRendererContractValidator.validate(manifest, target).valid, target)
            assertEquals(plan.id, group.id)
            assertEquals(mapOf("max" to "5", "delay" to "2s", "backoff" to "exponential"), group.params)
            assertEquals(listOf("retry", "action"), roots(manifest).map { it.type })
            assertEquals(listOf("first", "second"), group.children.map { it.params["branch"] })
            assertEquals(plan.body.map { it.id }, group.children.map { it.metadata["sourceTask"] })
            assertEquals("after", roots(manifest).last().params["branch"])
            if (target in jobProviders) {
                assertEquals(listOf(group.id, roots(manifest).last().id), manifest.jobs.map { it.id })
                assertTrue(group.children.none { child -> manifest.jobs.any { it.id == child.id } })
            }
        }
    }

    @Test fun nestedAndEmptyRetriesRetainIndependentPoliciesAndIdentities() {
        val nested = compile(retry(checkout("before") + " " + retry("", """max: 2 delay: "0s" backoff: fixed""") + " " + checkout("after")))
        for (target in providers) {
            val manifest = pipeline.generateDiagnosticEvidence(TargetDiagnosticMaterializationRequest.fromCompilation(nested,
                testTargetSelection(target, targets)))
            val outer = retryRoot(manifest)
            val inner = outer.children[1]
            assertEquals(listOf("action", "retry", "action"), outer.children.map { it.type })
            assertEquals(mapOf("max" to "5", "delay" to "2s", "backoff" to "exponential"), outer.params)
            assertEquals(mapOf("max" to "2", "delay" to "0s", "backoff" to "fixed"), inner.params)
            assertNotEquals(outer.id, inner.id)
            assertTrue(inner.children.isEmpty())
            assertEquals(1, manifest.jobs.size)
            assertTrue(TargetRendererContractValidator.validate(manifest, target).valid)
            assertFalse(TargetRenderPolicy.evaluate(manifest).executable)
        }
        val empty = compile(retry(""))
        for (target in jobProviders) {
            val manifest = pipeline.generateDiagnosticEvidence(TargetDiagnosticMaterializationRequest.fromCompilation(empty,
                testTargetSelection(target, targets)))
            assertTrue(retryRoot(manifest).children.isEmpty())
            assertEquals(TargetMaterializationStatus.ADAPTER_REQUIRED, retryRoot(manifest).materialization.status)
        }
    }

    @Test fun enclosingBranchConditionsStayOnTheRetryJob() {
        val branches = compile("if true { if false { ${retry(checkout("then"))} } else { ${retry(checkout("else"))} } }")
        for (target in jobProviders) {
            val manifest = pipeline.generateDiagnosticEvidence(TargetDiagnosticMaterializationRequest.fromCompilation(branches,
                testTargetSelection(target, targets)))
            assertEquals(listOf("(true) and (false)", "(true) and (not (false))"), manifest.jobs.map { it.metadata["condition"] })
            assertEquals(listOf("then", "else"), manifest.jobs.map { job ->
                assertEquals("retry", job.steps.single().type)
                job.steps.single().children.single().params["branch"]
            })
            assertTrue(TargetRendererContractValidator.validate(manifest, target).valid)
        }
    }

    @Test fun bodyDependenciesStayOnTheirOriginalLeavesWithoutInventedJobEdges() {
        val prototype = (unit.authorization.executionPlan.nodes.first() as RetryGroupNode).body.first() as TaskNode
        val before = prototype.copy(id = "before")
        val first = prototype.copy(id = "first", dependsOn = listOf("before"))
        val second = prototype.copy(id = "second", dependsOn = listOf("first"))
        val group = RetryGroupNode("attempts", max = 3, delay = "0s", backoff = "fixed", body = listOf(first, second))
        val plan = ExecutionPlan(flowName = "dependency-boundary", nodes = listOf(before, group))
        for (target in jobProviders) {
            val manifest = pipeline.generateDiagnosticEvidence(testDiagnosticMaterializationRequest(plan, target, targets))
            val job = manifest.jobs.single { it.id == "attempts" }
            assertTrue(job.dependsOn.isEmpty())
            assertEquals(listOf(listOf("before"), listOf("first")), job.steps.single().children.map { it.dependsOn })
            assertFalse(TargetRenderPolicy.evaluate(manifest).executable)
        }
    }

    @Test fun retainedRetryIdentitiesParticipateInJobAndStepCollisionChecks() {
        val prototype = (unit.authorization.executionPlan.nodes.first() as RetryGroupNode).body.first() as TaskNode
        for (target in providers) {
            for (body in listOf(emptyList(), listOf(prototype.copy(id = "body")))) {
                val group = RetryGroupNode("attempts", max = 3, delay = "0s", backoff = "fixed", body = body)
                val plan = ExecutionPlan(flowName = "collision", nodes = listOf(group, prototype.copy(id = "ATTEMPTS")))
                val failure = assertFailsWith<IllegalArgumentException> {
                    pipeline.generateDiagnosticEvidence(testDiagnosticMaterializationRequest(plan, target, targets))
                }
                assertContains(failure.message.orEmpty(), if (target == "jenkins") "projection.steps" else "projection.jobs")
            }
            val group = RetryGroupNode("outer", max = 3, delay = "0s", backoff = "fixed", body = listOf(
                RetryGroupNode("inner", max = 2, delay = "0s", backoff = "fixed"), prototype.copy(id = "INNER")))
            val failure = assertFailsWith<IllegalArgumentException> {
                pipeline.generateDiagnosticEvidence(testDiagnosticMaterializationRequest(ExecutionPlan("nested-collision", nodes = listOf(group)), target, targets))
            }
            assertContains(failure.message.orEmpty(), "projection.steps")
        }
    }

    @Test fun nativeLeavesCannotHideUnsupportedRetryAndJenkinsKeepsItsNativeBoundary() {
        for (target in jobProviders) {
            val manifest = manifests.getValue(target)
            val group = retryRoot(manifest)
            assertEquals(TargetMaterializationStatus.ADAPTER_REQUIRED, group.materialization.status)
            assertNull(group.rendererPayload)
            assertFalse(ReferenceTargetProjections.nativeCatalogs.getValue(target).hasStructuralProjection(TargetStructuralProjectionKind.RETRY))
            val readiness = TargetRenderPolicy.evaluate(manifest)
            assertEquals(TargetRenderMode.REVIEW_ONLY, readiness.mode)
            assertTrue(readiness.findings.any { it.nodeId == group.id && it.status == "ADAPTER_REQUIRED" })
            assertContains(TargetReviewArtifactRenderer.render(manifest, readiness), group.id)
            assertFails { pipeline.generate(TargetMaterializationRequest.fromCompilation(unit, testTargetSelection(target, targets), strict = true)) }
            assertFails { ReferenceTargetProjections.registry.requireProvider(target).render(manifest) }
        }
        assertTrue(retryRoot(manifests.getValue("github-actions")).children.all { it.materialization.status == TargetMaterializationStatus.NATIVE })
        val bounded = compile(retry(checkout("selected"), """max: 3 delay: "0s" backoff: fixed"""))
        val jenkins = pipeline.generate(TargetMaterializationRequest.fromCompilation(bounded, testTargetSelection("jenkins", targets), strict = true))
        val rendered = ReferenceTargetProjections.registry.requireProvider("jenkins").render(jenkins)
        assertContains(rendered, "retry(3) {")
        assertEquals(1, Regex("git branch:").findAll(rendered).count())
        assertEquals(SupportLevel.UNSUPPORTED, targets.getValue("jenkins").retry)
    }
}
