import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.flowlang.ai.normalization.AiIntentRequest
import org.flowlang.ai.normalization.ScenarioPackIntentNormalizer
import org.flowlang.artifacts.StandardSurface
import org.flowlang.ast.ActionNode
import org.flowlang.ast.StringLiteralNode
import org.flowlang.capabilities.CompatibilityAnalyzer
import org.flowlang.capabilities.MaterializationReadinessStatus
import org.flowlang.capabilities.ProjectionReadinessStatus
import org.flowlang.capabilities.SupportLevel
import org.flowlang.generators.manifest.JenkinsManifestRenderer
import org.flowlang.generators.manifest.TargetCompatibilityReadinessAnalyzer
import org.flowlang.generators.manifest.TargetManifestGenerationPipeline
import org.flowlang.generators.manifest.TargetMaterializationStatus
import org.flowlang.generators.manifest.TargetRenderMode
import org.flowlang.generators.manifest.TargetRenderPolicy
import org.flowlang.intent.IntentCapabilityValidator
import org.flowlang.intent.IntentDocument
import org.flowlang.intent.IntentScheduleKind
import org.flowlang.intent.IntentStep
import org.flowlang.intent.IntentString
import org.flowlang.intent.IntentSystem
import org.flowlang.intent.IntentToAstPlanner
import org.flowlang.intent.IntentTriggerType
import org.flowlang.intent.IntentWorkflow
import org.flowlang.intent.IntentWorkflowKind
import org.flowlang.intent.IntentYamlLoader
import org.flowlang.intent.StandardCapability
import org.flowlang.planner.ExecutionPlan
import org.flowlang.planner.FlowPlanner
import org.flowlang.planner.TaskNode
import org.flowlang.standard.FlowStandardVersions
import org.flowlang.targets.TargetRegistryYamlLoader

class UniversalModelCompletionTests {
    private val targets by lazy { TargetRegistryYamlLoader.loadDirectory(File("targets")) }
    private val compatibility by lazy { CompatibilityAnalyzer(targets) }

    @Test
    fun manifestGenerationAlwaysReconcilesCompatibilityAndReadiness() {
        val plan = ExecutionPlan(
            flowName = "semantic-test",
            nodes = listOf(
                TaskNode(
                    id = "test",
                    module = "standard",
                    action = "execute",
                    target = "standard",
                    params = mapOf("operation" to "test"),
                    requiredCapabilities = listOf("standard.execute")
                )
            )
        )
        val preliminary = compatibility.analyze(plan, "jenkins")
        assertEquals(SupportLevel.SUPPORTED, preliminary.status)
        assertFalse(preliminary.readinessEvidenceAvailable)

        val manifest = TargetManifestGenerationPipeline.generate(plan, preliminary)
        val concrete = TargetCompatibilityReadinessAnalyzer.analyze(manifest)

        assertTrue(manifest.compatibility.readinessEvidenceAvailable)
        assertEquals(SupportLevel.PARTIAL, manifest.compatibility.status)
        assertEquals(MaterializationReadinessStatus.REVIEW_REQUIRED, concrete.materializationReadiness)
        assertEquals(ProjectionReadinessStatus.REVIEW_ONLY, concrete.projectionReadiness)
        assertFalse(concrete.executable)
    }

    @Test
    fun nativeProjectionRequiresRealPayloadAndProducesExecutableJenkinsSyntax() {
        val plan = checkoutPlan()
        val manifest = TargetManifestGenerationPipeline.generate(plan, compatibility.analyze(plan, "jenkins"))
        val step = manifest.jobs.single().steps.single()

        assertEquals(TargetMaterializationStatus.NATIVE, step.materialization.status)
        assertNotNull(step.rendererPayload)
        assertEquals(TargetRenderMode.EXECUTABLE, TargetRenderPolicy.evaluate(manifest).mode)
        assertTrue(manifest.compatibility.executable)

        val rendered = JenkinsManifestRenderer().render(manifest)
        assertContains(rendered, "git branch:")
        assertContains(rendered, "url:")
        assertFalse(rendered.contains("steps: []"))
        assertFalse(rendered.contains("flow-materialization-required"))
        assertFalse(rendered.contains("Flow executes"))
    }

    @Test
    fun missingProjectionRuleFailsClosedInsteadOfBecomingExecutable() {
        val plan = ExecutionPlan(
            flowName = "unknown-action",
            nodes = listOf(TaskNode("work", module = "custom", action = "do", target = "custom", requiredCapabilities = listOf("custom.do")))
        )
        val manifest = TargetManifestGenerationPipeline.generate(plan, compatibility.analyze(plan, "jenkins"))
        val step = manifest.jobs.single().steps.single()

        assertEquals(TargetMaterializationStatus.ADAPTER_REQUIRED, step.materialization.status)
        assertEquals(TargetRenderMode.REVIEW_ONLY, TargetRenderPolicy.evaluate(manifest).mode)
        assertFalse(manifest.compatibility.executable)
    }

    @Test
    fun neutralDeployAndVerifyDoNotInventKubernetes() {
        val intent = IntentDocument(
            name = "neutral-deploy",
            systems = listOf(IntentSystem("standard", "standard")),
            workflows = listOf(
                IntentWorkflow(
                    "main",
                    IntentWorkflowKind.DEPLOY,
                    listOf(
                        IntentStep("deploy", StandardCapability.DEPLOY),
                        IntentStep("verify", StandardCapability.VERIFY, requires = listOf("deploy"))
                    )
                )
            )
        )
        val ast = IntentToAstPlanner().plan(intent)
        val actions = ast.flow.steps.filterIsInstance<ActionNode>()

        assertEquals(listOf("standard", "standard"), actions.map { it.module })
        assertEquals(listOf("deploy", "verify"), actions.map { (it.params["operation"] as StringLiteralNode).value })
        assertTrue(ast.flow.systems.none { it.systemType == "kubernetes" })
        assertTrue(actions.none { "namespace" in it.params || "selector" in it.params })
    }

    @Test
    fun explicitKubernetesUseRemainsExplicitRatherThanImplicit() {
        val intent = IntentDocument(
            name = "explicit-kubernetes",
            systems = listOf(IntentSystem("cluster", "kubernetes")),
            workflows = listOf(
                IntentWorkflow(
                    "main",
                    IntentWorkflowKind.DEPLOY,
                    listOf(
                        IntentStep(
                            "deploy",
                            StandardCapability.DEPLOY,
                            uses = "kubernetes.deploy",
                            params = mapOf("system" to IntentString("cluster"), "name" to IntentString("demo"))
                        )
                    )
                )
            )
        )
        val action = IntentToAstPlanner().plan(intent).flow.steps.single() as ActionNode

        assertEquals("kubernetes", action.module)
        assertEquals("deploy", action.action)
        assertEquals("cluster", action.target.path.single())
    }

    @Test
    fun intervalTriggerSurvivesYamlIntentAstAndPlan() {
        val intent = IntentYamlLoader.loadText(
            """
            intentVersion: "2.0"
            kind: FlowIntentDocument
            name: certificate-renewal
            triggers:
              - id: renew-every-30-days
                type: SCHEDULE
                workflows: [main]
                schedule:
                  kind: INTERVAL
                  expression: P30D
            workflows:
              - name: main
                kind: SECRET_ROTATION
                steps:
                  - id: renew
                    capability: CERTIFICATE_RENEW
                    params:
                      certificate: api-tls
            """.trimIndent()
        )
        IntentCapabilityValidator().validate(intent).assertValid()
        val ast = IntentToAstPlanner().plan(intent)
        val plan = FlowPlanner().plan(ast)

        assertEquals(IntentTriggerType.SCHEDULE, intent.triggers.single().type)
        assertEquals(IntentScheduleKind.INTERVAL, intent.triggers.single().schedule?.kind)
        assertEquals("INTERVAL", ast.flow.triggers.single().schedule?.kind)
        assertEquals("P30D", plan.triggers.single().schedule?.expression)
        assertContains(plan.requiredCapabilities, "trigger.schedule.interval")
    }

    @Test
    fun freeTextRecurringCertificateRenewalCreatesIntervalTrigger() {
        val result = ScenarioPackIntentNormalizer().normalize(
            AiIntentRequest("Renew certificate api-tls every 30 days and verify it.")
        )
        val schedule = result.normalizedIntent.triggers.single().schedule

        assertNotNull(schedule)
        assertEquals(IntentScheduleKind.INTERVAL, schedule.kind)
        assertEquals("P30D", schedule.expression)
    }

    @Test
    fun removedScheduleStepFailsWithMigrationMessage() {
        val failure = assertFailsWith<IllegalStateException> {
            IntentYamlLoader.loadText(
                """
                intentVersion: "2.0"
                kind: FlowIntentDocument
                name: legacy-schedule
                workflows:
                  - name: main
                    kind: CUSTOM
                    steps:
                      - id: schedule
                        capability: SCHEDULE
                """.trimIndent()
            )
        }
        assertContains(failure.message.orEmpty(), "top-level trigger")
    }

    @Test
    fun targetSemanticsAreRegistryKeyedAndVendorFieldFree() {
        val matrix = StandardSurface.targetSemanticsMatrix()
        val source = File("src/main/kotlin/org/flowlang/artifacts/StandardSurface.kt").readText()

        assertEquals("2.0", matrix.matrixVersion)
        assertTrue(matrix.entries.all { it.semanticsByTarget.keys == matrix.targetIds.toSet() })
        assertFalse(source.contains("val jenkins:"))
        assertFalse(source.contains("val githubActions:"))
        assertFalse(source.contains("val tekton:"))
    }

    @Test
    fun publicVersionsReflectTheBreakingContractMigration() {
        assertEquals("0.9.5", gradlePackageVersion())
        assertEquals("0.8.0", FlowStandardVersions.FLOW_STANDARD_VERSION)
        assertEquals("2.0", FlowStandardVersions.INTENT_VERSION)
        assertEquals("2.0", FlowStandardVersions.AST_VERSION)
        assertEquals("2.0", FlowStandardVersions.EXECUTION_PLAN_VERSION)
        assertEquals("2.0", FlowStandardVersions.TARGET_MANIFEST_VERSION)
        assertEquals("2.0", FlowStandardVersions.TARGET_REGISTRY_VERSION)

        val manifest = TargetManifestGenerationPipeline.generate(checkoutPlan(), compatibility.analyze(checkoutPlan(), "jenkins"))
        assertEquals("2.0", manifest.manifestVersion)
        assertFalse(File("schemas/target-manifest.schema.json").readText().contains("\"run\""))
    }

    @Test
    fun cliUsesTheCanonicalManifestGenerationBoundary() {
        val cli = File("src/main/kotlin/org/flowlang/cli/FlowCli.kt").readText()
        assertContains(cli, "TargetManifestGenerationPipeline.generate")
        assertFalse(cli.contains("JenkinsManifestGenerator()"))
        assertFalse(cli.contains("GitHubActionsManifestGenerator()"))
        assertFalse(cli.contains("TektonManifestGenerator()"))
    }

    @Test
    fun placeboRendererBranchesAreAbsentFromProductionSource() {
        val source = File("src/main/kotlin/org/flowlang/generators/manifest/TargetManifestRenderers.kt").readText()
        assertFalse(source.contains("steps: []"))
        assertFalse(source.contains("flow-materialization-required"))
        assertFalse(source.contains("Flow executes"))
    }

    private fun checkoutPlan(): ExecutionPlan = ExecutionPlan(
        flowName = "checkout-only",
        nodes = listOf(
            TaskNode(
                id = "checkout",
                module = "git",
                action = "checkout",
                target = "source",
                params = mapOf("url" to "https://example.invalid/repo.git", "branch" to "main"),
                requiredCapabilities = listOf("git.checkout")
            )
        )
    )

    private fun gradlePackageVersion(): String = Regex("(?m)^version\\s*=\\s*\"([^\"]+)\"")
        .find(File("build.gradle.kts").readText())
        ?.groupValues
        ?.get(1)
        ?: error("Missing package version")
}
