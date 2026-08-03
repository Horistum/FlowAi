import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.flowlang.adapters.control.AdapterControlDecision
import org.flowlang.adapters.rendering.AdapterRenderedArtifactKind
import org.flowlang.capabilities.ExecutionReadinessStatus
import org.flowlang.capabilities.SupportLevel
import org.flowlang.cli.honest.CliTargetEvidenceAuthority
import org.flowlang.cli.honest.CliTargetEvidenceOutcome
import org.flowlang.generators.manifest.TargetRenderMode
import org.flowlang.materialization.TargetSelectionAuthority
import org.flowlang.planner.ExecutionPlan
import org.flowlang.planner.RetryGroupNode
import org.flowlang.targets.TargetRegistryYamlLoader
import org.flowlang.targets.builtin.BuiltInTargetProjections

class AdapterControlCliMaterializationTests {
    private val rootDir = File(".")
    private val targets = TargetRegistryYamlLoader.loadDirectory(File(rootDir, "targets"))

    @Test
    fun unsupportedJenkinsRetryProducesReviewEvidenceAndNoJenkinsfile() {
        val plan = ExecutionPlan(
            flowName = "unsupported-retry",
            nodes = listOf(RetryGroupNode(
                id = "retry-deploy",
                max = 3,
                delay = "10s",
                backoff = "fixed",
                body = emptyList()
            ))
        )
        val selection = TargetSelectionAuthority.fromTestFixture(
            value = "jenkins",
            fixtureId = "a0.4-unsupported-retry",
            targets = targets
        )

        val result = CliTargetEvidenceAuthority(
            targets = targets,
            projections = BuiltInTargetProjections.registry,
            rootDir = rootDir
        ).evaluate(
            plan = plan,
            explicitSelection = selection,
            strict = false,
            renderRequested = true
        )

        assertTrue(result.diagnosticFallbackUsed)
        assertEquals(ExecutionReadinessStatus.BLOCKED, result.readiness.readiness)
        assertEquals(TargetRenderMode.REVIEW_ONLY, result.renderReadiness.mode)
        assertEquals(CliTargetEvidenceOutcome.REVIEW_ONLY, result.outcome)
        val rendered = requireNotNull(result.renderedArtifact)
        assertEquals(AdapterRenderedArtifactKind.REVIEW_EVIDENCE, rendered.kind)
        assertEquals("flow-jenkins-review.yaml", rendered.fileName)
        assertTrue(rendered.content.contains("kind: TargetProjectionReview"))
        assertTrue(rendered.content.contains("executable: false"))
        assertEquals(TargetRenderMode.REVIEW_ONLY, rendered.evidence.renderMode)
        assertEquals(AdapterControlDecision.BLOCKED.name, result.manifest.metadata["adapterControlDecision"])
        assertEquals(SupportLevel.UNSUPPORTED, result.compatibility.status)
        assertFalse(result.compatibility.executable)
        assertTrue(result.compatibility.issues.any { it.feature.contains("control.retry") })
        assertTrue(result.diagnostics.any { it.code == "CLI_RENDER_NOT_AUTHORIZED" })
    }
}
