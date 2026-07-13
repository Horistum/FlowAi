import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.flowlang.ast.ActionNode
import org.flowlang.ast.BinaryExpressionNode
import org.flowlang.ast.ErrorHandlerNode
import org.flowlang.ast.FlowDocument
import org.flowlang.ast.FlowNode
import org.flowlang.ast.IfNode
import org.flowlang.ast.InputNode
import org.flowlang.ast.ModuleImportNode
import org.flowlang.ast.ReferenceNode
import org.flowlang.ast.ResultBindingNode
import org.flowlang.ast.StringLiteralNode
import org.flowlang.ast.SystemNode
import org.flowlang.ast.ValueTypeNode
import org.flowlang.capabilities.CompatibilityReport
import org.flowlang.capabilities.SupportLevel
import org.flowlang.generators.manifest.JenkinsManifestGenerator
import org.flowlang.generators.manifest.JenkinsManifestRenderer
import org.flowlang.generators.manifest.TargetExpressionTranslator
import org.flowlang.generators.manifest.TargetInput
import org.flowlang.generators.manifest.TargetManifest
import org.flowlang.tests.TargetExpressionTestEvidence
import org.flowlang.generators.manifest.TargetRenderBlockedException
import org.flowlang.generators.manifest.TargetRenderMode
import org.flowlang.planner.FlowPlanner
import java.io.File

class MainMissingReviewFixesTests {
    @Test
    fun githubListLiteralConditionRendersValidJsonArray() {
        val rendered = TargetExpressionTranslator.github(
            "region in [\"eu\", \"us\"]",
            listOf(TargetInput(name = "region", type = "text")),
            TargetExpressionTestEvidence.declaration("github-actions")
        )

        assertTrue(rendered.contains("fromJSON('[\"eu\",\"us\"]')"), rendered)
        assertFalse(rendered.contains("fromJSON('[eu, us]')"), rendered)
    }

    @Test
    fun jenkinsManifestWrapsFlowLevelErrorHandlerAroundRealBody() {
        val boundary = jenkinsManifestWithFlowLevelErrorHandler()
            .jobs.single()
            .steps.single { it.type == "try" }

        val body = boundary.children.single { it.type == "try-body" }
        val handler = boundary.children.single { it.type == "error-handler" }

        assertTrue(body.children.isNotEmpty(), "Flow-level error handler must wrap the real flow body in the Jenkins manifest.")
        assertTrue(handler.children.isNotEmpty(), "Flow-level error handler must preserve handler steps in the Jenkins manifest.")
    }

    @Test
    fun blockedFlowLevelBodyFailsBeforeJenkinsSyntaxIsReturned() {
        val failure = assertFailsWith<TargetRenderBlockedException> {
            JenkinsManifestRenderer().render(jenkinsManifestWithFlowLevelErrorHandler())
        }

        assertEquals(TargetRenderMode.FAIL_FAST, failure.readiness.mode)
        assertTrue(failure.readiness.findings.any { it.status == "BLOCKED" })
        assertTrue(failure.readiness.findings.any { it.nodeId.contains("shell") || it.reason.contains("runtime", ignoreCase = true) })
    }

    @Test
    fun inconsistentSchemaDomainsAreNormalizedToFlowlangDev() {
        listOf(
            File("schemas/ai-normalization-report.schema.json"),
            File("schemas/intent-design-report.schema.json")
        ).forEach { schema ->
            val text = schema.readText()
            assertTrue(text.contains("\"\$id\": \"https://flowlang.dev/schemas/"), schema.path)
            assertFalse(text.contains("https://flowlang.org/schemas/"), schema.path)
        }
    }

    private fun jenkinsManifestWithFlowLevelErrorHandler(): TargetManifest = JenkinsManifestGenerator().generate(
        FlowPlanner().plan(flowWithErrorHandler()),
        CompatibilityReport(target = "jenkins", status = SupportLevel.SUPPORTED)
    )

    private fun flowWithErrorHandler(): FlowDocument = FlowDocument(
        imports = listOf(
            ModuleImportNode(name = "shell", version = "1.0"),
            ModuleImportNode(name = "notify", version = "1.0")
        ),
        flow = FlowNode(
            name = "flow-error-boundary",
            input = listOf(InputNode(name = "environment", valueType = ValueTypeNode(kind = "text"), required = true)),
            systems = listOf(
                SystemNode(name = "local", systemType = "shell"),
                SystemNode(name = "mailer", systemType = "notify")
            ),
            steps = listOf(
                IfNode(
                    condition = BinaryExpressionNode(
                        operator = "==",
                        left = ReferenceNode(path = listOf("environment")),
                        right = StringLiteralNode(value = "prod")
                    ),
                    then = listOf(shellAction("echo deploy", "deployResult"))
                )
            ),
            errorHandler = ErrorHandlerNode(steps = listOf(
                ActionNode(
                    module = "notify",
                    action = "send",
                    target = ReferenceNode(path = listOf("mailer")),
                    params = mapOf(
                        "subject" to StringLiteralNode(value = "Failed"),
                        "body" to ReferenceNode(path = listOf("error", "message"), scope = "error", safe = true)
                    )
                )
            ))
        )
    )

    private fun shellAction(command: String, resultName: String): ActionNode = ActionNode(
        module = "shell",
        action = "run",
        target = ReferenceNode(path = listOf("local")),
        params = mapOf("command" to StringLiteralNode(value = command)),
        result = ResultBindingNode(name = resultName)
    )
}
