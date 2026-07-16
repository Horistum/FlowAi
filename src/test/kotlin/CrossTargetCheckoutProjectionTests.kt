import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.flowlang.capabilities.CompatibilityAnalyzer
import org.flowlang.capabilities.SupportLevel
import org.flowlang.capabilities.TargetProjectionMode
import org.flowlang.generators.manifest.TargetMaterializationResolver
import org.flowlang.generators.manifest.TargetMaterializationStatus
import org.flowlang.generators.manifest.TargetRenderMode
import org.flowlang.generators.manifest.TargetRenderPolicy
import org.flowlang.modules.ModuleRegistry
import org.flowlang.parser.FlowParser
import org.flowlang.planner.ExecutionPlan
import org.flowlang.planner.FlowPlanner
import org.flowlang.planner.TaskNode
import org.flowlang.targets.TargetRegistryYamlLoader
import org.flowlang.targets.builtin.BuiltInNativeProjectionCatalogs
import org.flowlang.targets.builtin.BuiltInTargetProjections
import org.flowlang.validator.FlowValidator

class CrossTargetCheckoutProjectionTests {
    private val modules by lazy { ModuleRegistry.fromDirectory(File("modules"), includeDefaults = true) }
    private val targets by lazy { TargetRegistryYamlLoader.loadDirectory(File("targets")) }
    private val analyzer by lazy { CompatibilityAnalyzer(targets) }

    @Test
    fun repositoryRegistryAndProviderCatalogsAgreeForEveryBuiltInCheckoutProjection() {
        val catalogs = mapOf(
            "jenkins" to BuiltInNativeProjectionCatalogs.jenkins,
            "github-actions" to BuiltInNativeProjectionCatalogs.githubActions,
            "tekton" to BuiltInNativeProjectionCatalogs.tekton
        )

        catalogs.forEach { (target, catalog) ->
            val capability = targets.getValue(target)
            assertEquals(SupportLevel.SUPPORTED, capability.features["git.checkout"])
            val rule = capability.projectionRules.single { it.matches("git", "checkout") }
            assertEquals(TargetProjectionMode.NATIVE, rule.mode)
            val payload = assertNotNull(rule.payload)
            assertTrue(catalog.definitions.any { definition ->
                definition.kind == payload.kind && definition.reference == payload.reference
            })
            catalog.requireCompatibleRules(capability.projectionRules)
        }
    }

    @Test
    fun sourceLanguagePlannerAndProjectionPipelinePreserveCheckoutSemanticsAcrossTargets() {
        val ast = FlowParser().parse(
            """
            version "1.0"
            use module "git" version "1.0"

            flow "checkout-source" {
              systems {
                system "repo" {
                  type: git
                  url: "https://github.com/openai/openai.git"
                  branch: "release/0.9.6"
                }
              }

              steps {
                git.checkout repo {
                  depth: 2
                } -> source
              }
            }
            """.trimIndent()
        )
        val validation = FlowValidator(modules).validate(ast)
        assertTrue(validation.valid, validation.issues.joinToString { "${it.code}: ${it.message}" })
        val plan = FlowPlanner(modules).plan(ast)
        val task = plan.tasks.single()

        assertTrue("git.checkout" in plan.requiredCapabilities)
        assertEquals("\"https://github.com/openai/openai.git\"", task.params["url"])
        assertEquals("\"release/0.9.6\"", task.params["branch"])
        assertEquals("2", task.params["depth"])

        selectedTargets().forEach { target ->
            val rendered = render(plan, target)
            assertTrue(rendered.isNotBlank())
        }
    }

    @Test
    fun checkoutRendersExecutableNativeArtifactsAcrossAllBuiltInTargets() {
        val plan = checkoutPlan(
            url = "https://github.com/openai/openai.git",
            branch = "release/0.9.6",
            depth = "2",
            taskTarget = "source-workspace"
        )

        val rendered = selectedTargets().associateWith { target -> render(plan, target) }

        assertTrue(rendered.getValue("jenkins").contains("checkout scmGit("))
        assertTrue(rendered.getValue("jenkins").contains("cloneOption(depth: 2"))
        assertTrue(rendered.getValue("jenkins").contains("https://github.com/openai/openai.git"))

        assertTrue(rendered.getValue("github-actions").contains("uses: \"actions/checkout@v4\""))
        assertTrue(rendered.getValue("github-actions").contains("repository: \"openai/openai\""))
        assertTrue(rendered.getValue("github-actions").contains("ref: \"release/0.9.6\""))
        assertTrue(rendered.getValue("github-actions").contains("fetch-depth: \"2\""))

        assertTrue(rendered.getValue("tekton").contains("- name: source-workspace"))
        assertTrue(rendered.getValue("tekton").contains("name: git-clone"))
        assertTrue(rendered.getValue("tekton").contains("value: \"https://github.com/openai/openai.git\""))
        assertTrue(rendered.getValue("tekton").contains("value: \"release/0.9.6\""))
        assertTrue(rendered.getValue("tekton").contains("value: \"2\""))
        assertTrue(rendered.getValue("tekton").contains("workspace: source-workspace"))
    }

    @Test
    fun omittedDepthPreservesFullHistoryCheckoutAcrossTargets() {
        val plan = checkoutPlan(
            url = "git@github.com:openai/openai.git",
            branch = "main",
            depth = null
        )

        val jenkins = render(plan, "jenkins")
        val github = render(plan, "github-actions")
        val tekton = render(plan, "tekton")

        assertTrue(jenkins.contains("git branch: 'main', url: 'git@github.com:openai/openai.git'"))
        assertTrue(github.contains("repository: \"openai/openai\""))
        assertTrue(github.contains("fetch-depth: \"0\""))
        assertTrue(tekton.contains("- name: depth\n          value: \"0\""))
    }

    @Test
    fun githubCheckoutRejectsRepositoryOutsideGithubInsteadOfRenderingInvalidUsesInput() {
        val plan = checkoutPlan(url = "https://git.example.invalid/acme/repository.git")
        val provider = BuiltInTargetProjections.registry.requireProvider("github-actions")
        val compatibility = analyzer.analyze(plan, "github-actions")
        val manifest = provider.generate(plan, compatibility)

        val failure = assertFailsWith<IllegalArgumentException> {
            provider.render(manifest)
        }

        assertTrue(failure.message.orEmpty().contains("not github.com"))
    }

    @Test
    fun invalidCheckoutDepthFailsClosedForEveryImplementedTarget() {
        listOf("two", "-1").forEach { invalidDepth ->
            val plan = checkoutPlan(
                url = "https://github.com/openai/openai.git",
                depth = invalidDepth
            )

            selectedTargets().forEach { target ->
                val provider = BuiltInTargetProjections.registry.requireProvider(target)
                val compatibility = analyzer.analyze(plan, target)
                val manifest = provider.generate(plan, compatibility)
                val failure = assertFailsWith<IllegalArgumentException> {
                    provider.render(manifest)
                }
                assertTrue(failure.message.orEmpty().contains("non-negative integer"))
            }
        }
    }

    @Test
    fun targetWithoutCheckoutProjectionEvidenceRemainsAdapterRequired() {
        val target = targets.getValue("local")
        val resolution = TargetMaterializationResolver.resolve(
            task = checkoutPlan("https://github.com/openai/openai.git").tasks.single(),
            targetName = target.target,
            projectionRules = target.projectionRules
        )

        assertEquals(TargetMaterializationStatus.ADAPTER_REQUIRED, resolution.materialization.status)
        assertEquals(null, resolution.rendererPayload)
        assertTrue(resolution.materialization.reason.contains("declares no projection rule"))
    }

    private fun render(plan: ExecutionPlan, target: String): String {
        val compatibility = analyzer.analyze(plan, target)
        assertEquals(SupportLevel.SUPPORTED, compatibility.status)
        assertEquals(SupportLevel.SUPPORTED, compatibility.capabilityStatus)
        val provider = BuiltInTargetProjections.registry.requireProvider(target)
        val manifest = provider.generate(plan, compatibility)
        assertEquals(TargetRenderMode.EXECUTABLE, TargetRenderPolicy.evaluate(manifest).mode)
        return provider.render(manifest)
    }

    private fun checkoutPlan(
        url: String,
        branch: String = "main",
        depth: String? = "0",
        taskTarget: String = "source"
    ): ExecutionPlan {
        val params = linkedMapOf("url" to url, "branch" to branch)
        depth?.let { params["depth"] = it }
        return ExecutionPlan(
            flowName = "checkout-source",
            requiredCapabilities = listOf("git.checkout"),
            nodes = listOf(TaskNode(
                id = "checkout",
                module = "git",
                action = "checkout",
                target = taskTarget,
                params = params,
                requiredCapabilities = listOf("git.checkout")
            ))
        )
    }

    private fun selectedTargets(): List<String> = listOf("jenkins", "github-actions", "tekton")
}
