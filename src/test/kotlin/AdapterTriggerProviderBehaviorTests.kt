import org.flowlang.distribution.reference.ReferenceAdapterEvidence
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.flowlang.adapters.rendering.AdapterRenderedArtifactKind
import org.flowlang.adapters.trigger.AdapterTriggerAuthorizedRenderingAuthority
import org.flowlang.adapters.trigger.AdapterTriggerDecision
import org.flowlang.adapters.trigger.AdapterTriggerMaterializationAuthority
import org.flowlang.adapters.yaml.TargetRegistryYamlLoader
import org.flowlang.ast.ScheduleNode
import org.flowlang.ast.TriggerNode
import org.flowlang.capabilities.CompatibilityAnalyzer
import org.flowlang.capabilities.SupportLevel
import org.flowlang.modules.ModuleRegistry
import org.flowlang.parser.FlowParser
import org.flowlang.planner.ExecutionPlan
import org.flowlang.planner.FlowPlanner
import org.flowlang.targets.builtin.BuiltInTargetProjections
import org.flowlang.targets.builtin.GitHubActionsProjectionInspection

class AdapterTriggerProviderBehaviorTests {
    private val root = File(".")
    private val targets = TargetRegistryYamlLoader.loadDirectory(File(root, "targets"))
    private val modules = ModuleRegistry.fromDirectory(File(root, "modules"))
    private val analyzer = CompatibilityAnalyzer(targets)
    private val triggerAuthority = ReferenceAdapterEvidence.trigger(root, targets, BuiltInTargetProjections.registry)
    private val renderingAuthority = ReferenceAdapterEvidence.authorizedRendering(root, BuiltInTargetProjections.registry)

    @Test
    fun jenkinsManualTriggerRemainsManualOnly() {
        val plan = providerPlan(TriggerNode(id = "manual", triggerType = "MANUAL"))

        val bundle = renderAuthorized(plan, "jenkins")

        assertEquals(AdapterRenderedArtifactKind.EXECUTABLE_TARGET, bundle.artifact.kind)
        assertEquals("Jenkinsfile", bundle.artifact.fileName)
        assertFalse(bundle.artifact.content.contains("triggers {"))
        assertTrue(bundle.receipt.evidence.any {
            it.category == "manifest-metadata" &&
                it.reference.endsWith("metadata.adapterTriggerDecision") &&
                it.detail == AdapterTriggerDecision.MATCHED.name
        })
    }

    @Test
    fun jenkinsPortableCronPreservesExpression() {
        val plan = providerPlan(cron("nightly", "0 3 * * *", null))

        val bundle = renderAuthorized(plan, "jenkins")

        assertEquals(AdapterRenderedArtifactKind.EXECUTABLE_TARGET, bundle.artifact.kind)
        assertTrue(bundle.artifact.content.contains("cron('0 3 * * *')"))
        assertEquals(AdapterTriggerDecision.MATCHED.name, bundle.receipt.evidence.single {
            it.category == "manifest-metadata" && it.reference.endsWith("metadata.adapterTriggerDecision")
        }.detail)
    }

    @Test
    fun githubManualTriggerRendersWorkflowDispatch() {
        val plan = providerPlan(TriggerNode(id = "manual", triggerType = "MANUAL"))

        val bundle = renderAuthorized(plan, "github-actions")

        assertEquals(AdapterRenderedArtifactKind.EXECUTABLE_TARGET, bundle.artifact.kind)
        assertTrue(bundle.artifact.content.contains("  workflow_dispatch:"))
    }

    @Test
    fun githubPortableCronPreservesExpressionAndTimezone() {
        val plan = providerPlan(cron("nightly", "0 3 * * *", "Europe/Prague"))

        val bundle = renderAuthorized(plan, "github-actions")

        assertEquals(AdapterRenderedArtifactKind.EXECUTABLE_TARGET, bundle.artifact.kind)
        assertTrue(bundle.artifact.content.contains("cron: \"0 3 * * *\""))
        assertTrue(bundle.artifact.content.contains("timezone: \"Europe/Prague\""))
    }

    @Test
    fun githubNamedEventPreservesIdentityWithoutClaimingScenarioReadiness() {
        val plan = providerPlan(TriggerNode(id = "release", triggerType = "EVENT", event = "release"))
        val compatibility = analyzer.analyze(plan, "github-actions")
        assertEquals(SupportLevel.PARTIAL, compatibility.capabilityStatus)
        val provider = BuiltInTargetProjections.registry.requireProvider("github-actions")
        val generated = provider.generate(plan, compatibility)
        val assessment = triggerAuthority.requireMatched(plan, "github-actions")
        val manifest = triggerAuthority.reconcileDiagnostic(generated, assessment)

        val triggerProjection = GitHubActionsProjectionInspection.triggerDocument(manifest)
        val reviewBundle = renderingAuthority.render(manifest)

        assertTrue(triggerProjection.contains("  release:"))
        assertFalse(triggerProjection.contains("repository_dispatch"))
        assertEquals(SupportLevel.PARTIAL, manifest.compatibility.status)
        assertFalse(manifest.compatibility.executable)
        assertEquals(AdapterRenderedArtifactKind.REVIEW_EVIDENCE, reviewBundle.artifact.kind)
        assertEquals(AdapterTriggerDecision.MATCHED.name, manifest.metadata["adapterTriggerDecision"])
    }

    @Test
    fun unsupportedIntervalProducesReviewEvidenceAndNeverTargetSyntax() {
        val plan = providerPlan(
            TriggerNode(
                id = "frequent",
                triggerType = "SCHEDULE",
                schedule = ScheduleNode(kind = "INTERVAL", expression = "PT15M")
            )
        )
        val compatibility = analyzer.analyze(plan, "github-actions")
        val provider = BuiltInTargetProjections.registry.requireProvider("github-actions")
        val generated = provider.generate(plan, compatibility)
        val assessment = triggerAuthority.assess(plan, "github-actions")
        val manifest = triggerAuthority.reconcileDiagnostic(generated, assessment)

        val bundle = renderingAuthority.render(manifest)

        assertEquals(AdapterTriggerDecision.BLOCKED, assessment.decision)
        assertEquals(AdapterRenderedArtifactKind.REVIEW_EVIDENCE, bundle.artifact.kind)
        assertEquals("flow-github-actions-review.yaml", bundle.artifact.fileName)
        assertTrue(bundle.artifact.content.contains("executable: false"))
        assertFalse(bundle.artifact.content.contains("cron:"))
    }

    private fun renderAuthorized(plan: ExecutionPlan, target: String) =
        BuiltInTargetProjections.registry.requireProvider(target).let { provider ->
            val compatibility = analyzer.analyze(plan, target)
            assertEquals(SupportLevel.SUPPORTED, compatibility.status, compatibility.issues.joinToString())
            val generated = provider.generate(plan, compatibility)
            val assessment = triggerAuthority.requireMatched(plan, target)
            val manifest = triggerAuthority.reconcileDiagnostic(generated, assessment)
            renderingAuthority.render(manifest)
        }

    private fun providerPlan(trigger: TriggerNode): ExecutionPlan {
        val document = FlowParser().parse(
            """
            version "1.0"
            use module "git" version "1.0"

            flow "a0.7-provider-behavior" {
              systems {
                system "repo" {
                  type: git
                  url: "https://github.com/openai/openai.git"
                  branch: "main"
                }
              }

              steps {
                git.checkout repo {
                  depth: 2
                }
              }
            }
            """.trimIndent()
        )
        return FlowPlanner(modules).plan(
            document.copy(flow = document.flow.copy(triggers = listOf(trigger)))
        )
    }

    private fun cron(id: String, expression: String, timezone: String?) = TriggerNode(
        id = id,
        triggerType = "SCHEDULE",
        schedule = ScheduleNode(kind = "CRON", expression = expression, timezone = timezone)
    )
}
