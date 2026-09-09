import org.flowlang.frontend.FrontendCompilerComposition
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.flowlang.capabilities.CompatibilityAnalyzer
import org.flowlang.capabilities.SupportLevel
import org.flowlang.capabilities.TargetProjectionMode
import org.flowlang.adapters.testing.MaterializationResolverFixture as TargetMaterializationResolver
import org.flowlang.generators.manifest.TargetMaterializationStatus
import org.flowlang.generators.manifest.TargetRenderMode
import org.flowlang.generators.manifest.TargetRenderPolicy
import org.flowlang.modules.ModuleRegistry
import org.flowlang.parser.FlowParser
import org.flowlang.planner.ExecutionPlan
import org.flowlang.planner.FlowPlanner
import org.flowlang.planner.PlanInput
import org.flowlang.planner.TaskNode
import org.flowlang.targets.TargetRegistryYamlLoader
import org.flowlang.targets.builtin.BuiltInNativeProjectionCatalogs
import org.flowlang.targets.builtin.BuiltInTargetProjections
import org.flowlang.validator.FlowValidator

class NativeImageBuildProjectionTests {
    private val modules by lazy { ModuleRegistry.fromDirectory(File("modules"), includeDefaults = true) }
    private val targets by lazy { TargetRegistryYamlLoader.loadDirectory(File("targets")) }
    private val analyzer by lazy { CompatibilityAnalyzer(targets) }

    @Test
    fun repositoryRegistryAndProviderCatalogsAgreeForEveryBuiltInImageBuildProjection() {
        val catalogs = mapOf(
            "jenkins" to BuiltInNativeProjectionCatalogs.jenkins,
            "github-actions" to BuiltInNativeProjectionCatalogs.githubActions,
            "tekton" to BuiltInNativeProjectionCatalogs.tekton
        )

        catalogs.forEach { (target, catalog) ->
            val capability = targets.getValue(target)
            assertEquals(SupportLevel.SUPPORTED, capability.features["docker.build"])
            val rule = capability.projectionRules.single { it.matches("docker", "build") }
            assertEquals(TargetProjectionMode.NATIVE, rule.mode)
            val payload = assertNotNull(rule.payload)
            assertTrue(catalog.definitions.any { definition ->
                definition.kind == payload.kind && definition.reference == payload.reference
            })
            catalog.requireCompatibleRules(capability.projectionRules)
        }
    }

    @Test
    fun sourceLanguagePlannerPreservesImageBuildSemanticsAndCapabilityEvidence() {
        val ast = FlowParser().parse(
            """
            version "1.0"
            use module "docker" version "1.0"

            flow "build-image" {
              input {
                version: text required
              }

              systems {
                system "registry" {
                  type: docker
                  url: secret("REGISTRY_URL")
                }
              }

              steps {
                docker.build registry {
                  image: "ghcr.io/acme/service:${'$'}{version}"
                  path: "services/api"
                  dockerfile: "services/api/Dockerfile"
                  push: true
                } -> image
              }
            }
            """.trimIndent()
        )
        val validation = FrontendCompilerComposition.flowValidator(modules).validate(ast)
        assertTrue(validation.valid, validation.issues.joinToString { "${it.code}: ${it.message}" })
        val plan = FlowPlanner(modules).plan(ast)
        val task = plan.tasks.single()

        assertTrue("container.image" in plan.requiredCapabilities)
        assertTrue("docker.build" in plan.requiredCapabilities)
        assertTrue("docker.build" in task.requiredCapabilities)
        assertEquals("\"ghcr.io/acme/service:${'$'}{version}\"", task.params["image"])
        assertEquals("\"services/api\"", task.params["path"])
        assertEquals("\"services/api/Dockerfile\"", task.params["dockerfile"])
        assertEquals("true", task.params["push"])
    }

    @Test
    fun imageBuildRendersNativeStructuredArtifactsAcrossBuiltInTargets() {
        val plan = imageBuildPlan(
            image = "ghcr.io/acme/service:${'$'}{version}",
            path = "services/api",
            dockerfile = "services/api/Dockerfile",
            push = "true",
            inputs = listOf(PlanInput(name = "version", required = true))
        )

        val rendered = selectedTargets().associateWith { target -> render(plan, target) }

        val jenkins = rendered.getValue("jenkins")
        val imageVariable = assertNotNull(
            Regex("""def ([A-Za-z0-9_]+) = docker\.build\("ghcr\.io/acme/service:\$\{params\.version}", 'services/api'\)""")
                .find(jenkins)
        ).groupValues[1]
        assertTrue(jenkins.contains("$imageVariable.push()"))
        assertTrue(!jenkins.contains("docker build"))
        assertTrue(jenkins.lineSequence().none { line ->
            val trimmed = line.trim()
            trimmed == "sh" || trimmed.startsWith("sh ") || trimmed.startsWith("sh(")
        })

        val github = rendered.getValue("github-actions")
        assertTrue(github.contains("uses: \"docker/build-push-action@v7\""))
        assertTrue(github.contains("context: \"{{defaultContext}}:services/api\""))
        assertTrue(github.contains("tags: \"ghcr.io/acme/service:${'$'}{{ inputs.version }}\""))
        assertTrue(github.contains("push: true"))
        assertTrue(!github.contains("run: docker"))

        val tekton = rendered.getValue("tekton")
        assertTrue(tekton.contains("- name: source"))
        assertTrue(tekton.contains("name: buildah"))
        assertTrue(tekton.contains("- name: IMAGE\n          value: \"ghcr.io/acme/service:${'$'}(params.version)\""))
        assertTrue(tekton.contains("- name: CONTEXT\n          value: \"services/api\""))
        assertTrue(tekton.contains("- name: DOCKERFILE\n          value: \"services/api/Dockerfile\""))
        assertTrue(tekton.contains("- name: SKIP_PUSH\n          value: \"false\""))
        assertTrue(tekton.contains("workspace: source"))
        assertTrue(!tekton.contains("script:"))
    }

    @Test
    fun omittedOptionalValuesPreserveDefaultContextDockerfileAndNoPushPolicy() {
        val plan = imageBuildPlan(image = "acme/service:1.0", path = null, dockerfile = null, push = null)

        val jenkins = render(plan, "jenkins")
        val github = render(plan, "github-actions")
        val tekton = render(plan, "tekton")

        assertTrue(jenkins.contains("docker.build('acme/service:1.0')"))
        assertTrue(!jenkins.contains(".push()"))

        assertTrue(github.contains("tags: \"acme/service:1.0\""))
        assertTrue(github.contains("push: false"))
        assertTrue(!github.contains("          context:"))
        assertTrue(!github.contains("          file:"))

        assertTrue(tekton.contains("- name: CONTEXT\n          value: \".\""))
        assertTrue(tekton.contains("- name: DOCKERFILE\n          value: \"./Dockerfile\""))
        assertTrue(tekton.contains("- name: SKIP_PUSH\n          value: \"true\""))
    }

    @Test
    fun customDockerfileIsStructuredForGithubAndTektonButFailsClosedForJenkins() {
        val plan = imageBuildPlan(
            image = "acme/service:1.0",
            path = "services/api",
            dockerfile = "services/api/Dockerfile.release",
            push = "false"
        )

        val github = render(plan, "github-actions")
        val tekton = render(plan, "tekton")
        assertTrue(github.contains("file: \"Dockerfile.release\""))
        assertTrue(tekton.contains("value: \"services/api/Dockerfile.release\""))

        val provider = BuiltInTargetProjections.registry.requireProvider("jenkins")
        val manifest = provider.generate(plan, analyzer.analyze(plan, "jenkins"))
        val failure = assertFailsWith<IllegalArgumentException> { provider.render(manifest) }
        assertTrue(failure.message.orEmpty().contains("without falling back to Docker CLI argument strings"))
    }

    @Test
    fun githubRejectsDockerfileOutsideSelectedGitContext() {
        val plan = imageBuildPlan(
            image = "acme/service:1.0",
            path = "services/api",
            dockerfile = "docker/Dockerfile.release",
            push = "false"
        )
        val provider = BuiltInTargetProjections.registry.requireProvider("github-actions")
        val manifest = provider.generate(plan, analyzer.analyze(plan, "github-actions"))

        val failure = assertFailsWith<IllegalArgumentException> { provider.render(manifest) }
        assertTrue(failure.message.orEmpty().contains("must be inside build context 'services/api'"))
    }

    @Test
    fun invalidPushPolicyFailsClosedForEveryImplementedTarget() {
        val plan = imageBuildPlan(image = "acme/service:1.0", push = "yes")

        selectedTargets().forEach { target ->
            val provider = BuiltInTargetProjections.registry.requireProvider(target)
            val manifest = provider.generate(plan, analyzer.analyze(plan, target))
            val failure = assertFailsWith<IllegalArgumentException> { provider.render(manifest) }
            assertTrue(failure.message.orEmpty().contains("compile-time boolean"))
        }
    }

    @Test
    fun unsafeOrDynamicBuildPathsFailClosedForEveryImplementedTarget() {
        listOf("../secret", "-f", "services api", "${'$'}{path}").forEach { invalidPath ->
            val plan = imageBuildPlan(
                image = "acme/service:1.0",
                path = invalidPath,
                push = "false",
                inputs = listOf(PlanInput(name = "path", required = true))
            )

            selectedTargets().forEach { target ->
                val provider = BuiltInTargetProjections.registry.requireProvider(target)
                val manifest = provider.generate(plan, analyzer.analyze(plan, target))
                val failure = assertFailsWith<IllegalArgumentException> { provider.render(manifest) }
                assertTrue(
                    failure.message.orEmpty().contains("workspace") ||
                        failure.message.orEmpty().contains("option-like") ||
                        failure.message.orEmpty().contains("unsupported path") ||
                        failure.message.orEmpty().contains("compile-time relative")
                )
            }
        }
    }

    @Test
    fun unknownImageInterpolationFailsClosedInsteadOfLeakingTargetSyntax() {
        val plan = imageBuildPlan(image = "acme/service:${'$'}{unknown}", push = "false")

        selectedTargets().forEach { target ->
            val provider = BuiltInTargetProjections.registry.requireProvider(target)
            val manifest = provider.generate(plan, analyzer.analyze(plan, target))
            val failure = assertFailsWith<IllegalArgumentException> { provider.render(manifest) }
            assertTrue(failure.message.orEmpty().contains("unsupported interpolation 'unknown'"))
        }
    }

    @Test
    fun targetWithoutImageBuildProjectionEvidenceRemainsAdapterRequired() {
        val target = targets.getValue("local")
        val resolution = TargetMaterializationResolver.resolve(
            task = imageBuildPlan(image = "acme/service:1.0").tasks.single(),
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

    private fun imageBuildPlan(
        image: String,
        path: String? = ".",
        dockerfile: String? = null,
        push: String? = "false",
        inputs: List<PlanInput> = emptyList()
    ): ExecutionPlan {
        val params = linkedMapOf("image" to image)
        path?.let { params["path"] = it }
        dockerfile?.let { params["dockerfile"] = it }
        push?.let { params["push"] = it }
        return ExecutionPlan(
            flowName = "build-image",
            inputs = inputs,
            requiredCapabilities = listOf("container.image", "docker.build"),
            nodes = listOf(TaskNode(
                id = "build-image",
                module = "docker",
                action = "build",
                target = "registry",
                params = params,
                requiredCapabilities = listOf("container.image", "docker.build")
            ))
        )
    }

    private fun selectedTargets(): List<String> = listOf("jenkins", "github-actions", "tekton")
}
