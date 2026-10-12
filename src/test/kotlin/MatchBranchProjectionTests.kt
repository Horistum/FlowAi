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

class MatchBranchProjectionTests {
    companion object {
        private val modules by lazy { ModuleRegistry.fromDirectory(File("modules")) }
        private val targets by lazy { TargetRegistryYamlLoader.loadDirectory(File("targets")) }
        private val pipeline by lazy { ReferenceTargetProjections.pipeline(targets, modules = modules) }
        private val providers = listOf("jenkins", "github-actions", "tekton")
        private fun checkout(branch: String) = """git.checkout source { url: "https://example.invalid/repository.git" branch: "$branch" }"""
        private val source = """
            version "1.0"
            use module "git" version "1.0"
            flow "match-branches" {
              systems { system "source" { type: git } }
              steps {
                match "selected" {
                  when false { }
                  when true { ${checkout("selected")} ${checkout("alternate")} }
                  when error { ${checkout("error")} }
                  default { ${checkout("default")} }
                }
              }
            }
        """.trimIndent()
        private fun compile(text: String) = kotlin.io.path.createTempFile(suffix = ".flow").toFile().let { file ->
            try {
                file.writeText(text)
                FlowSourceFrontend(FrontendCompilerComposition.compiler(modules,
                    StandardEnvironmentSafetyPolicyNotes.policy(File(".")))).compile(file).requireAccepted()
            } finally { file.delete() }
        }
        private val unit by lazy { compile(source) }
        private val manifests by lazy { providers.associateWith { target ->
            pipeline.generateDiagnosticEvidence(TargetDiagnosticMaterializationRequest.fromCompilation(unit,
                testTargetSelection(target, targets)))
        } }
    }
    private fun root(manifest: TargetManifest) = manifest.jobs.flatMap { it.steps }.single { it.type == "match" }
    private fun change(manifest: TargetManifest, transform: (TargetStep) -> TargetStep) = manifest.copy(jobs = manifest.jobs.map { job ->
        job.copy(steps = job.steps.map { if (it.type == "match") transform(it) else it })
    })
    private fun rejected(manifest: TargetManifest, code: String) {
        val report = TargetManifestContractValidator.validate(manifest)
        assertFalse(report.valid)
        assertContains(report.issues.map { it.code }, code)
        assertFails { TargetRendererContractValidator.requireRenderable(manifest, manifest.target) }
    }

    @Test fun sourceCompilationPreservesConditionsOrderAndEveryBranchBodyOnAllProviders() {
        val plan = unit.authorization.executionPlan.nodes.single() as MatchPlanNode
        assertEquals(listOf("false", "true"), plan.cases.map { it.condition })
        for ((target, manifest) in manifests) {
            val match = root(manifest)
            assertTrue(TargetManifestContractValidator.validate(manifest).valid, target)
            assertEquals(plan.source, match.params["source"])
            assertEquals("2", match.metadata["caseCount"])
            assertEquals(listOf("match-case", "match-case", "match-error", "match-default"), match.children.map { it.type })
            assertEquals(plan.cases.map { it.condition }, match.children.take(2).map { it.params["condition"] })
            assertEquals(listOf("0", "1"), match.children.take(2).map { it.metadata["caseIndex"] })
            assertTrue(match.children[0].children.isEmpty())
            assertEquals(listOf(emptyList(), listOf("selected", "alternate"), listOf("error"), listOf("default")),
                match.children.map { branch -> branch.children.map { it.params["branch"] } })
            assertEquals(plan.cases.flatMap { it.steps }.map { it.id } + plan.errorCase.map { it.id } + plan.defaultSteps.map { it.id },
                match.children.flatMap { it.children }.map { it.metadata["sourceTask"] })
        }
    }

    @Test fun emptyFallbacksAndNestedMatchRetainSeparateBoundaries() {
        val nestedSource = source.replace(checkout("selected"), "match \"inner\" { when true { } }")
        val nested = compile(nestedSource)
        for (target in providers) {
            val manifest = pipeline.generateDiagnosticEvidence(TargetDiagnosticMaterializationRequest.fromCompilation(nested,
                testTargetSelection(target, targets)))
            val inner = root(manifest).children[1].children.first()
            assertEquals("match", inner.type)
            assertEquals("\"inner\"", inner.params["source"])
            assertEquals(listOf("match-case", "match-error", "match-default"), inner.children.map { it.type })
            assertTrue(inner.children.all { it.children.isEmpty() })
            assertTrue(TargetManifestContractValidator.validate(manifest).valid)
        }
    }

    @Test fun flattenedOmittedDuplicatedOrReorderedBranchesFailTheContract() {
        for (manifest in manifests.values) {
            val match = root(manifest)
            for (children in listOf(match.children.flatMap { it.children }, match.children.drop(1), match.children.dropLast(1),
                match.children + match.children.last(), match.children.take(2) + match.children.takeLast(2).reversed())) {
                rejected(change(manifest) { it.copy(children = children) }, "MATCH_BRANCHES_INVALID")
            }
            rejected(change(manifest) { it.copy(children = it.children.take(2).reversed() + it.children.drop(2)) }, "MATCH_CASE_ORDER_INVALID")
            rejected(change(manifest) { it.copy(metadata = it.metadata - "caseCount") }, "MATCH_BRANCHES_INVALID")
            rejected(change(manifest) { it.copy(metadata = it.metadata + ("caseCount" to "02")) }, "MATCH_BRANCHES_INVALID")
        }
    }

    @Test fun sourceAndConditionCannotBeOmittedBlankOrReplacedByUnknownBindings() {
        val manifest = manifests.getValue("jenkins")
        for (params in listOf(emptyMap(), mapOf("source" to " "), root(manifest).params + ("command" to "ignored")))
            rejected(change(manifest) { it.copy(params = params) }, "MATCH_SOURCE_INVALID")
        for (params in listOf(emptyMap(), mapOf("condition" to " "), mapOf("condition" to "true", "extra" to "ignored")))
            rejected(change(manifest) { it.copy(children = listOf(it.children[0].copy(params = params)) + it.children.drop(1)) }, "MATCH_BRANCH_PARAMS_INVALID")
        rejected(change(manifest) { it.copy(children = it.children.dropLast(1) + it.children.last().copy(params = mapOf("condition" to "true"))) }, "MATCH_BRANCH_PARAMS_INVALID")
    }

    @Test fun detachedWrappersAndExecutionBindingsCannotImpersonateBranches() {
        val manifest = manifests.getValue("jenkins")
        for (wrapper in root(manifest).children) {
            rejected(manifest.copy(jobs = listOf(TargetJob("detached", steps = listOf(wrapper)))), "MATCH_BRANCH_PARENT_INVALID")
        }
        val match = root(manifest)
        for (wrapper in listOf(match.children[0].copy(module = "git", action = "checkout", target = "source"),
            match.children[0].copy(dependsOn = listOf("elsewhere")),
            match.children[0].copy(materialization = TargetMaterialization.native("git.checkout", "forged")))) {
            rejected(change(manifest) { it.copy(children = listOf(wrapper) + it.children.drop(1)) }, "MATCH_BRANCH_EXECUTION_INVALID")
        }
    }

    @Test fun derivedWrapperCollisionsFailBeforeAnyProviderCanEmitAManifest() {
        val prototype = (unit.authorization.executionPlan.nodes.single() as MatchPlanNode).cases[1].steps.first() as TaskNode
        fun task(id: String) = prototype.copy(id = id)
        for (id in listOf("routing_case_1", "routing_error", "routing_default", "ROUTING_CASE_1")) {
            val plan = ExecutionPlan(flowName = "collision", nodes = listOf(
                MatchPlanNode(id = "routing", source = "\"selected\"", cases = listOf(MatchCase("true", listOf(task("body"))))), task(id)))
            for (target in providers) {
                val failure = assertFailsWith<IllegalArgumentException> {
                    pipeline.generateDiagnosticEvidence(testDiagnosticMaterializationRequest(plan, target, targets))
                }
                assertContains(failure.message.orEmpty(), "projection.steps")
            }
        }
    }

    @Test fun preservingMatchDoesNotGrantExecutionOrPromoteAnyTarget() {
        for ((target, manifest) in manifests) {
            assertEquals(TargetMaterializationStatus.ADAPTER_REQUIRED, root(manifest).materialization.status)
            assertFalse(TargetRenderPolicy.evaluate(manifest).executable)
            assertFalse(ReferenceTargetProjections.nativeCatalogs.getValue(target).hasStructuralProjection(TargetStructuralProjectionKind.MATCH))
            assertFails { pipeline.generate(TargetMaterializationRequest.fromCompilation(unit, testTargetSelection(target, targets), strict = true)) }
            assertFails { ReferenceTargetProjections.registry.requireProvider(target).render(manifest) }
        }
        assertEquals(SupportLevel.UNSUPPORTED, targets.getValue("jenkins").match)
    }

    @Test fun emptyBranchWrappersAreNotReportedAsLeafOperations() {
        val empty = compile(source.replace("when false { }", "when false { match \"empty\" { } }"))
        for (target in providers) {
            val manifest = pipeline.generateDiagnosticEvidence(TargetDiagnosticMaterializationRequest.fromCompilation(empty,
                testTargetSelection(target, targets)))
            val inner = root(manifest).children[0].children.single()
            assertEquals("0", inner.metadata["caseCount"])
            assertEquals(listOf("match-error", "match-default"), inner.children.map { it.type })
            assertTrue(TargetManifestContractValidator.validate(manifest).valid)
            val readiness = TargetRenderPolicy.evaluate(manifest)
            assertEquals(TargetRenderMode.REVIEW_ONLY, readiness.mode)
            val review = TargetReviewArtifactRenderer.render(manifest, readiness)
            for (wrapper in inner.children) {
                assertTrue(readiness.findings.none { it.nodeId == wrapper.id })
                assertFalse(review.contains("nodeId: \"${wrapper.id}\""))
            }
        }
    }
}
