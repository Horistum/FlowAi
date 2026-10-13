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

class ParallelBranchProjectionTests {
    companion object {
        private val modules by lazy { ModuleRegistry.fromDirectory(File("modules")) }
        private val targets by lazy { TargetRegistryYamlLoader.loadDirectory(File("targets")) }
        private val pipeline by lazy { ReferenceTargetProjections.pipeline(targets, modules = modules) }
        private val providers = listOf("jenkins", "github-actions", "tekton")
        private fun checkout(branch: String) = """git.checkout source { url: "https://example.invalid/repository.git" branch: "$branch" }"""
        private val source = """
            version "1.0"
            use module "git" version "1.0"
            flow "parallel-branches" {
              systems { system "source" { type: git } }
              steps {
                parallel failFast false {
                  ${checkout("unnamed")}
                  branch "branch-1" { ${checkout("named")} }
                  branch "same" { }
                  branch "same" { ${checkout("first")} ${checkout("second")} }
                  branch " spaced " { }
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
    private fun root(manifest: TargetManifest) = manifest.jobs.flatMap { it.steps }.single { it.type == "parallel" }
    private fun change(manifest: TargetManifest, transform: (TargetStep) -> TargetStep) = manifest.copy(jobs = manifest.jobs.map { job ->
        job.copy(steps = job.steps.map { if (it.type == "parallel") transform(it) else it })
    })
    private fun rejected(manifest: TargetManifest, code: String) {
        val report = TargetManifestContractValidator.validate(manifest)
        assertFalse(report.valid)
        assertContains(report.issues.map { it.code }, code)
        assertFails { TargetRendererContractValidator.requireRenderable(manifest, manifest.target) }
    }

    @Test fun sourceCompilationPreservesEveryBranchAndOptionalLabelAcrossProviders() {
        val plan = unit.authorization.executionPlan.nodes.single() as ParallelGroupNode
        assertEquals(listOf(null, "branch-1", "same", "same", " spaced "), plan.branches.map { it.name })
        for ((target, manifest) in manifests) {
            val parallel = root(manifest)
            assertTrue(TargetRendererContractValidator.validate(manifest, target).valid, target)
            assertEquals("false", parallel.metadata["failFast"])
            assertEquals("5", parallel.metadata["branchCount"])
            assertEquals(List(5) { "parallel-branch" }, parallel.children.map { it.type })
            assertEquals((0..4).map { it.toString() }, parallel.children.map { it.metadata["branchIndex"] })
            assertEquals(plan.branches.map { it.name }, parallel.children.map { it.metadata["branchName"] })
            assertEquals(listOf("branch-1", "branch-1", "same", "same", " spaced "), parallel.children.map { it.name })
            assertEquals(5, parallel.children.map { it.id }.toSet().size)
            assertEquals(listOf(listOf("unnamed"), listOf("named"), emptyList(), listOf("first", "second"), emptyList()),
                parallel.children.map { branch -> branch.children.map { it.params["branch"] } })
            assertEquals(plan.branches.map { b -> b.steps.map { it.id } },
                parallel.children.map { b -> b.children.map { it.metadata["sourceTask"] } })
        }
    }

    @Test fun nestedAndEmptyGroupsPreserveIndependentFailurePolicies() {
        val nested = compile(source.replace(checkout("named"), "parallel failFast true { branch \"inner\" { } }")
            .replace(checkout("unnamed"), "parallel { }"))
        for (target in providers) {
            val manifest = pipeline.generateDiagnosticEvidence(TargetDiagnosticMaterializationRequest.fromCompilation(nested,
                testTargetSelection(target, targets)))
            val outer = root(manifest)
            val empty = outer.children[0].children.single()
            val inner = outer.children[1].children.single()
            assertEquals("false", outer.metadata["failFast"])
            assertEquals("true", empty.metadata["failFast"])
            assertEquals("0", empty.metadata["branchCount"])
            assertTrue(empty.children.isEmpty())
            assertEquals("true", inner.metadata["failFast"])
            assertEquals("1", inner.metadata["branchCount"])
            assertEquals("inner", inner.children.single().metadata["branchName"])
            assertTrue(inner.children.single().children.isEmpty())
            assertTrue(TargetRendererContractValidator.validate(manifest, target).valid)
            assertFalse(TargetRenderPolicy.evaluate(manifest).executable)
        }
    }

    @Test fun omittedAndExplicitTrueFailurePoliciesProjectIdentically() {
        for (setting in listOf("", "failFast true")) {
            val compiled = compile(source.replace("failFast false", setting))
            for (target in providers) {
                val manifest = pipeline.generateDiagnosticEvidence(TargetDiagnosticMaterializationRequest.fromCompilation(compiled,
                    testTargetSelection(target, targets)))
                assertEquals("true", root(manifest).metadata["failFast"])
                assertTrue(TargetManifestContractValidator.validate(manifest).valid)
            }
        }
    }

    @Test fun flattenedOmittedDuplicatedOrReorderedBranchesFailClosed() {
        for (manifest in manifests.values) {
            val parallel = root(manifest)
            for (children in listOf(parallel.children.flatMap { it.children }, parallel.children.drop(1),
                parallel.children + parallel.children.last(), emptyList())) {
                rejected(change(manifest) { it.copy(children = children) }, "PARALLEL_BRANCHES_INVALID")
            }
            rejected(change(manifest) { it.copy(children = it.children.reversed()) }, "PARALLEL_BRANCH_ORDER_INVALID")
            for (count in listOf(null, "05", "-1", "unknown")) {
                rejected(change(manifest) { it.copy(metadata = (it.metadata - "branchCount") +
                    (count?.let { value -> mapOf("branchCount" to value) } ?: emptyMap())) }, "PARALLEL_BRANCHES_INVALID")
            }
            for (index in listOf(null, "00", "-1", "1")) {
                rejected(change(manifest) { it.copy(children = listOf(it.children[0].copy(metadata =
                    (it.children[0].metadata - "branchIndex") + (index?.let { value -> mapOf("branchIndex" to value) } ?: emptyMap()))) +
                    it.children.drop(1)) }, "PARALLEL_BRANCH_ORDER_INVALID")
            }
        }
    }

    @Test fun invalidFailurePoliciesParametersAndLabelLossAreRejected() {
        val manifest = manifests.getValue("jenkins")
        for (value in listOf(null, "", "TRUE", "0", "false ", "banana")) {
            rejected(change(manifest) { it.copy(metadata = (it.metadata - "failFast") +
                (value?.let { v -> mapOf("failFast" to v) } ?: emptyMap())) }, "PARALLEL_FAIL_FAST_INVALID")
        }
        for (label in listOf("", " ")) {
            assertFails { compile(source.replace("branch \" spaced \"", "branch \"$label\"")) }
            rejected(change(manifest) { it.copy(children = listOf(it.children[0].copy(name = label,
                metadata = it.children[0].metadata + ("branchName" to label))) + it.children.drop(1)) }, "PARALLEL_BRANCH_NAME_INVALID")
        }
        rejected(change(manifest) { it.copy(params = mapOf("failFast" to "false")) }, "PARALLEL_PARAMS_INVALID")
        rejected(change(manifest) { it.copy(children = listOf(it.children[0].copy(params = mapOf("command" to "ignored"))) +
            it.children.drop(1)) }, "PARALLEL_BRANCH_PARAMS_INVALID")
        rejected(change(manifest) { it.copy(children = it.children.take(2) + it.children[2].copy(metadata =
            it.children[2].metadata - "branchName") + it.children.drop(3)) }, "PARALLEL_BRANCH_NAME_INVALID")
        rejected(change(manifest) { it.copy(children = listOf(it.children[0].copy(name = "invented")) + it.children.drop(1)) },
            "PARALLEL_BRANCH_NAME_INVALID")
    }

    @Test fun detachedOrExecutableWrappersCannotImpersonateBranches() {
        val manifest = manifests.getValue("jenkins")
        val parallel = root(manifest)
        val branch = parallel.children.first()
        for (wrapper in parallel.children) {
            rejected(manifest.copy(jobs = listOf(TargetJob("detached", steps = listOf(wrapper)))), "PARALLEL_BRANCH_PARENT_INVALID")
        }
        rejected(change(manifest) { it.copy(children = listOf(branch.copy(children = listOf(parallel.children[1]))) + it.children.drop(1)) },
            "PARALLEL_BRANCH_PARENT_INVALID")
        for (wrapper in listOf(branch.copy(module = "git"), branch.copy(action = "checkout"), branch.copy(target = "source"),
            branch.copy(dependsOn = listOf("elsewhere")), branch.copy(rendererPayload = branch.children.single().rendererPayload),
            branch.copy(materialization = TargetMaterialization.native("git.checkout", "forged")))) {
            rejected(change(manifest) { it.copy(children = listOf(wrapper) + it.children.drop(1)) }, "PARALLEL_BRANCH_EXECUTION_INVALID")
        }
    }

    @Test fun derivedBranchIdentitiesRejectCollisionsWithoutUsingLabels() {
        val prototype = ((unit.authorization.executionPlan.nodes.single() as ParallelGroupNode).branches[0].steps.single() as TaskNode)
        for (id in listOf("routing_branch_1", "ROUTING_BRANCH_1")) {
            val plan = ExecutionPlan(flowName = "collision", nodes = listOf(ParallelGroupNode(id = "routing", branches =
                listOf(PlanBranch("display", listOf(prototype.copy(id = "body"))))), prototype.copy(id = id)))
            for (target in providers) {
                val failure = assertFailsWith<IllegalArgumentException> {
                    pipeline.generateDiagnosticEvidence(testDiagnosticMaterializationRequest(plan, target, targets))
                }
                assertContains(failure.message.orEmpty(), "projection.steps")
            }
        }
    }

    @Test fun groupingNeverPromotesParallelOrTurnsEmptyWrappersIntoLeafOperations() {
        for ((target, manifest) in manifests) {
            val parallel = root(manifest)
            assertEquals(TargetMaterializationStatus.ADAPTER_REQUIRED, parallel.materialization.status)
            assertFalse(ReferenceTargetProjections.nativeCatalogs.getValue(target).hasStructuralProjection(TargetStructuralProjectionKind.PARALLEL))
            val readiness = TargetRenderPolicy.evaluate(manifest)
            assertEquals(TargetRenderMode.REVIEW_ONLY, readiness.mode)
            val review = TargetReviewArtifactRenderer.render(manifest, readiness)
            for (wrapper in parallel.children.filter { it.children.isEmpty() }) {
                assertTrue(readiness.findings.none { it.nodeId == wrapper.id })
                assertFalse(review.contains("nodeId: \"${wrapper.id}\""))
            }
            assertFails { pipeline.generate(TargetMaterializationRequest.fromCompilation(unit, testTargetSelection(target, targets), strict = true)) }
            assertFails { ReferenceTargetProjections.registry.requireProvider(target).render(manifest) }
        }
        assertEquals(SupportLevel.UNSUPPORTED, targets.getValue("jenkins").parallel)
    }
}
