import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import org.flowlang.ast.ActionNode
import org.flowlang.ast.FlowDocument
import org.flowlang.ast.FlowNode
import org.flowlang.ast.ModuleImportNode
import org.flowlang.ast.ReferenceNode
import org.flowlang.ast.SecretRefNode
import org.flowlang.ast.StringLiteralNode
import org.flowlang.ast.SystemNode
import org.flowlang.intent.StandardCapability
import org.flowlang.modules.ModuleRegistry
import org.flowlang.planner.FlowPlanner

class ModuleCapabilityAndConfigPropagationTests {
    private val registry = ModuleRegistry.fromDirectory(File("modules"))

    @Test
    fun argocdContractCapabilitiesAndSystemConfigReachTheExecutionTask() {
        val document = FlowDocument(
            imports = listOf(ModuleImportNode(name = "argocd", version = "1.0")),
            flow = FlowNode(
                name = "argocd-propagation",
                systems = listOf(
                    SystemNode(
                        name = "argo",
                        systemType = "argocd",
                        config = mapOf(
                            "url" to StringLiteralNode(value = "https://argocd.example.invalid"),
                            "token" to SecretRefNode(name = "ARGOCD_TOKEN")
                        )
                    )
                ),
                steps = listOf(
                    ActionNode(
                        module = "argocd",
                        action = "sync",
                        target = ReferenceNode(path = listOf("argo")),
                        params = mapOf("app" to StringLiteralNode(value = "payments")),
                        semanticCapability = StandardCapability.DEPLOY.name
                    )
                )
            )
        )

        val task = FlowPlanner(registry).plan(document).tasks.single()

        assertContains(task.requiredCapabilities, "argocd.api")
        assertContains(task.requiredCapabilities, "secrets.runtime")
        assertEquals("\"https://argocd.example.invalid\"", task.params["url"])
        assertEquals("secret:ARGOCD_TOKEN", task.params["token"])
        assertEquals("\"payments\"", task.params["app"])
    }
}
