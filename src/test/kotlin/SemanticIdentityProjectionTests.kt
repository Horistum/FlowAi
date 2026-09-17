package org.flowlang.tests

import java.io.File
import kotlin.test.*
import org.flowlang.capabilities.*
import org.flowlang.generators.manifest.*
import org.flowlang.identity.*
import org.flowlang.planner.*
import org.flowlang.targets.builtin.*
import org.flowlang.targets.TargetRegistryYamlLoader
import org.flowlang.safety.EnvironmentSensitivity
import org.flowlang.projection.*
import org.flowlang.frontend.FrontendCompilerComposition
import org.flowlang.modules.ModuleRegistry
import org.flowlang.intent.IntentYamlLoader

class SemanticIdentityProjectionTests {
    private val targets = TargetRegistryYamlLoader.loadDirectory(File("targets"))
    private fun compatibility(plan: ExecutionPlan, target: String) = CompatibilityAnalyzer(targets).analyze(plan, target)
    private val generators = listOf(JenkinsManifestGenerator(), GitHubActionsManifestGenerator(), TektonManifestGenerator())
    private val renderers = listOf(JenkinsManifestRenderer(), GitHubActionsManifestRenderer(), TektonManifestRenderer())

    @Test fun builtInProjectionRejectsCaseAndPunctuationCollisionsBeforeBuildingJobs() {
        listOf(listOf("Audit", "audit"), listOf("a/b", "a-b"), listOf("???", "!!!")).forEach { ids ->
            listOf(ids, ids.reversed()).forEach { permutation ->
                val plan = ExecutionPlan(flowName = "identity", nodes = permutation.map { ControlNode(it, "Skip", "skip") })
                generators.forEach { generator ->
                    val failure = assertFailsWith<IdentityCollisionException> { generator.generate(plan, compatibility(plan, generator.target)) }
                    assertEquals("DERIVED_ID_COLLISION", failure.code)
                }
            }
        }
    }
    @Test fun aSyntheticStructuralNameCannotOverwriteAConcreteNode() {
        val plan = ExecutionPlan(flowName = "identity", nodes = listOf(
            ConditionNode(id = "gate", condition = "true", then = listOf(ControlNode("work", "Skip", "skip"))),
            ControlNode(id = "gate_then", kind = "Skip", detail = "skip")))
        assertFailsWith<IdentityCollisionException> { JenkinsManifestGenerator().generate(plan, compatibility(plan, "jenkins")) }
    }
    @Test fun equalDisplayLabelsDoNotCreateEqualStructuralBranchIds() {
        val plan = ExecutionPlan(flowName = "identity", nodes = listOf(
            ParallelGroupNode(id = "parallel", branches = listOf(PlanBranch("Display", listOf(ControlNode("one", "Skip", "skip"))),
                PlanBranch("Display", listOf(ControlNode("two", "Skip", "skip")))))))
        val manifest = JenkinsManifestGenerator().generate(plan, compatibility(plan, "jenkins"))
        val branches = manifest.jobs.single().steps.single().children
        assertEquals(listOf("Display", "Display"), branches.map { it.name })
        assertEquals(2, branches.map { it.id }.toSet().size)
    }
    @Test fun inputDeclarationAndReferencesUseTheSameCheckedTargetSpelling() {
        val plan = ExecutionPlan(flowName = "identity", inputs = listOf(PlanInput("Region")))
        listOf(GitHubActionsManifestGenerator(), TektonManifestGenerator()).forEach { generator ->
            val projected = generator.generate(plan, compatibility(plan, generator.target))
            assertEquals("Region", projected.inputs.single().name)
            val payloadKind = if (generator.target == "github-actions") "GITHUB_ACTION" else "TEKTON_TASK"
            val native = nativeManifest(generator.target, payloadKind, "example-task", listOf("work"),
                mapOf("region" to ProjectionBinding.flowInput("Region"))).copy(inputs = listOf(TargetInput("Region")))
            val rendered = renderers.single { it.target == generator.target }.render(native)
            val expected = if (generator.target == "github-actions") "\${{ inputs.region }}" else "\$(params.region)"
            assertTrue(rendered.contains(expected), rendered)
            assertFalse(rendered.contains("inputs.Region") || rendered.contains("params.Region"), rendered)
            val bad = plan.copy(inputs = listOf(PlanInput("Region"), PlanInput("region")))
            assertEquals("DERIVED_ID_COLLISION", assertFailsWith<IdentityCollisionException> {
                generator.generate(bad, compatibility(bad, generator.target))
            }.code)
        }
    }
    @Test fun rendererOnlyInputDeclarationsCannotBypassProjectionChecks() {
        for (renderer in renderers.filterNot { it.target == "jenkins" }) {
            val plan = ExecutionPlan(flowName = "identity")
            val manifest = generators.single { it.target == renderer.target }.generate(plan, compatibility(plan, renderer.target))
                .copy(inputs = listOf(TargetInput("Region"), TargetInput("region")))
            assertEquals("DERIVED_ID_COLLISION", assertFailsWith<IdentityCollisionException> { renderer.render(manifest) }.code)
        }
    }
    @Test fun secretEnvironmentCollisionsAreNotResolvedByReferenceOrder() {
        val plan = ExecutionPlan(flowName = "identity")
        renderers.forEach { renderer ->
            val step = TargetStep(id = "work", type = "action", module = "standard", action = "custom", target = "standard", params = mapOf("first" to "secret:a-b", "second" to "secret:a_b"),
                materialization = TargetMaterialization.semanticOnly("No executable claim"))
            val manifest = generators.single { it.target == renderer.target }.generate(plan, compatibility(plan, renderer.target))
                .copy(jobs = listOf(TargetJob("work", steps = listOf(step))))
            assertEquals("DERIVED_ID_COLLISION", assertFailsWith<IdentityCollisionException> { renderer.render(manifest) }.code)
        }
    }
    @Test fun aDifferentJobCannotBorrowProviderApprovalThroughASlug() {
        val plan = ExecutionPlan(flowName = "identity")
        val manifest = TargetManifest(target = "github-actions", flowName = plan.flowName, compatibility = compatibility(plan, "github-actions"),
            jobs = listOf(TargetJob("Approve", metadata = mapOf("providerApprovalPayload" to "true")),
                TargetJob("approve"), TargetJob("deploy", dependsOn = listOf("approve"))))
        assertNull(GitHubActionsProjectionInspection.jobCondition(manifest.jobs.last(), manifest))
        val actual = manifest.copy(jobs = manifest.jobs.drop(1).map { if (it.id == "approve") it.copy(metadata = mapOf("providerApprovalPayload" to "true")) else it })
        assertTrue(GitHubActionsProjectionInspection.jobCondition(actual.jobs.last(), actual).orEmpty().contains("'skipped'"))
    }
    @Test fun environmentEvidenceUsesExactDependencyIdentity() {
        val plan = ExecutionPlan(flowName = "identity")
        val approval = TargetJob("Approve", metadata = mapOf("approval" to "true"))
        val deploy = TargetJob("deploy", dependsOn = listOf("approve"), steps = listOf(TargetStep(id = "deploy", type = "action", params = mapOf("environment" to "prod"))))
        val manifest = TargetManifest(target = "github-actions", flowName = plan.flowName,
            compatibility = compatibility(plan, "github-actions"), jobs = listOf(approval, TargetJob("approve"), deploy))
        assertEquals(EnvironmentSensitivity.UNKNOWN, TargetEnvironmentSafetyEvidenceResolver().resolve(manifest, approval).sensitivity)
        assertEquals(EnvironmentSensitivity.SENSITIVE, TargetEnvironmentSafetyEvidenceResolver().resolve(
            manifest.copy(jobs = listOf(approval, deploy.copy(dependsOn = listOf("Approve")))), approval).sensitivity)
    }

    @Test fun jenkinsLocalVariableNamesCannotMergeDifferentImageSteps() {
        val bindings = mapOf("image" to ProjectionBinding.literal("registry.example/service:1"))
        val bad = nativeManifest("jenkins", "JENKINS_STEP", "docker-build", listOf("a-b", "a_b"), bindings)
        assertEquals("DERIVED_ID_COLLISION", assertFailsWith<IdentityCollisionException> { JenkinsManifestRenderer().render(bad) }.code)
        val good = nativeManifest("jenkins", "JENKINS_STEP", "docker-build", listOf("a-b", "a__b"), bindings)
        val rendered = JenkinsManifestRenderer().render(good)
        assertTrue(rendered.contains("def flowImage_a_b ="), rendered)
        assertTrue(rendered.contains("def flowImage_a__b ="), rendered)
    }
    @Test fun tektonWorkspaceDeclarationsDoNotAliasCaseVariants() {
        val bindings = mapOf("url" to ProjectionBinding.literal("https://github.com/example/repo.git"),
            "revision" to ProjectionBinding.literal("main"), "workspace" to ProjectionBinding.literal("Repo"))
        val base = nativeManifest("tekton", "TEKTON_TASK", "git-clone", listOf("one"), bindings)
        val first = base.jobs.single()
        val otherPayload = assertNotNull(first.steps.single().rendererPayload).let { payload ->
            payload.copy(bindings = payload.bindings + ("workspace" to ProjectionBinding.literal("repo").copy(
                resolutionStatus = ProjectionBindingResolutionStatus.RESOLVED)))
        }
        val second = first.copy(id = "two", steps = listOf(first.steps.single().copy(id = "two", rendererPayload = otherPayload)))
        assertEquals("DERIVED_ID_COLLISION", assertFailsWith<IdentityCollisionException> {
            TektonManifestRenderer().render(base.copy(jobs = listOf(first, second)))
        }.code)
        val shared = second.copy(steps = listOf(first.steps.single().copy(id = "two")))
        val rendered = TektonManifestRenderer().render(base.copy(jobs = listOf(first, shared)))
        assertEquals(1, Regex("(?m)^    - name: repo$").findAll(rendered).count(), rendered)
    }
    @Test fun continuityCompositionRejectsAmbiguousJobsBeforeLookingUpAProducer() {
        val modules = ModuleRegistry()
        val intent = IntentYamlLoader.load(File("examples/intent/checkout-build-image.intent.yaml"))
        val plan = FlowPlanner(modules).plan(FrontendCompilerComposition.intentPlanner(modules).plan(intent))
        assertTrue(plan.dependencyRelations.isNotEmpty())
        assertEquals("DERIVED_ID_COLLISION", assertFailsWith<IdentityCollisionException> {
            GitHubActionsWorkspaceContinuityPlanner.materialize(plan, listOf(TargetJob("Source"), TargetJob("source")))
        }.code)
    }
    @Test fun continuityGeneratedStepCannotOverwriteAnAuthoredStep() {
        val modules = ModuleRegistry()
        val intent = IntentYamlLoader.load(File("examples/intent/checkout-build-image.intent.yaml"))
        val plan = FlowPlanner(modules).plan(FrontendCompilerComposition.intentPlanner(modules).plan(intent))
        val source = plan.tasks.single { it.module == "git" }
        val target = plan.tasks.single { it.module == "docker" }
        val jobs = listOf(TargetJob(AdapterManifestLowering.id(source.id), steps = listOf(TargetStep("original", type = "skip"))),
            TargetJob(AdapterManifestLowering.id(target.id), dependsOn = listOf(AdapterManifestLowering.id(source.id)),
                steps = listOf(TargetStep("consumer", type = "skip"))))
        val generated = GitHubActionsWorkspaceContinuityPlanner.materialize(plan, jobs)
        val upload = generated.flatMap { it.steps }.single { it.type == "adapter-continuity-upload" }
        val conflicting = jobs.map { job -> if (job.id == AdapterManifestLowering.id(source.id))
            job.copy(steps = job.steps + TargetStep(upload.id, type = "skip")) else job }
        assertFailsWith<IdentityCollisionException> { GitHubActionsWorkspaceContinuityPlanner.materialize(plan, conflicting) }
        assertEquals(generated, GitHubActionsWorkspaceContinuityPlanner.materialize(plan, jobs))
    }

    /** Renderer unit fixture only; it does not certify a target or bypass the product compilation path. */
    private fun nativeManifest(target: String, kind: String, reference: String, ids: List<String>,
        bindings: Map<String, ProjectionBinding>): TargetManifest = TargetManifest(
        target = target, flowName = "identity",
        compatibility = CompatibilityReport(target = target, status = SupportLevel.SUPPORTED,
            capabilityStatus = SupportLevel.SUPPORTED, materializationReadiness = MaterializationReadinessStatus.COMPLETE,
            projectionReadiness = ProjectionReadinessStatus.EXECUTABLE, executable = true, readinessEvidenceAvailable = true),
        jobs = listOf(TargetJob("work", steps = ids.map { id -> TargetStep(id = id, type = "action", module = "example",
            action = "project", target = "artifact", materialization = TargetMaterialization.native("example.project", "Renderer unit fixture"),
            rendererPayload = TargetRendererPayload(kind = kind, target = target, reference = reference,
                bindings = bindings.mapValues { (_, binding) -> binding.copy(resolutionStatus =
                    if (binding.kind == ProjectionBindingKind.LITERAL) ProjectionBindingResolutionStatus.RESOLVED else ProjectionBindingResolutionStatus.SYMBOLIC) },
                evidenceReference = "test:semantic-identity-rendering")) })),
        metadata = mapOf("sourcePlanVersion" to "2.0", "generator" to "SemanticIdentityProjectionTests", "standardVersion" to "0.8.0",
            "capabilityCompatibility" to "SUPPORTED", "effectiveCompatibility" to "SUPPORTED", "materializationReadiness" to "COMPLETE",
            "projectionReadiness" to "EXECUTABLE", "executable" to "true")
    )
}
