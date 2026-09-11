import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.flowlang.ast.FlowDocument
import org.flowlang.ast.FlowNode
import org.flowlang.ast.NumberLiteralNode
import org.flowlang.ast.StringLiteralNode
import org.flowlang.ast.SystemNode
import org.flowlang.frontend.FrontendCompilerComposition
import org.flowlang.intent.IntentCapabilityValidator
import org.flowlang.intent.IntentDocument
import org.flowlang.intent.IntentNumber
import org.flowlang.intent.IntentString
import org.flowlang.intent.IntentSystem
import org.flowlang.modules.FlowModule
import org.flowlang.modules.ModuleRegistry
import org.flowlang.modules.SchemaField
import org.flowlang.modules.SchemaType
import org.flowlang.modules.SystemTypeContract

class ModuleSchemaTypeCompatibilityTests {
    private val registry = ModuleRegistry(
        mapOf(
            "typed" to FlowModule(
                name = "typed",
                version = "1.0",
                systemTypes = mapOf(
                    "typed" to SystemTypeContract(
                        name = "typed",
                        input = mapOf(
                            "count" to SchemaField(type = SchemaType.NUMBER, required = true)
                        )
                    )
                )
            )
        )
    )

    @Test
    fun flowAndIntentRejectTheSameTextValueForNumberSchema() {
        val flow = FlowDocument(
            flow = FlowNode(
                name = "typed-flow",
                systems = listOf(
                    SystemNode(
                        name = "target",
                        systemType = "typed",
                        config = mapOf("count" to StringLiteralNode(value = "12"))
                    )
                )
            )
        )
        val flowReport = FrontendCompilerComposition.flowValidator(registry).validate(flow)
        assertFalse(flowReport.valid)
        assertTrue(flowReport.issues.any { it.code == "TYPE_MISMATCH" })

        val intent = IntentDocument(
            name = "typed-intent",
            systems = listOf(
                IntentSystem(
                    name = "target",
                    type = "typed",
                    config = mapOf("count" to IntentString("12"))
                )
            )
        )
        val intentReport = IntentCapabilityValidator(registry).validate(intent)
        assertFalse(intentReport.valid)
        assertTrue(intentReport.issues.any { it.code == "SYSTEM_CONFIG_TYPE_MISMATCH" })
    }

    @Test
    fun flowAndIntentAcceptNativeNumberForNumberSchema() {
        val flow = FlowDocument(
            flow = FlowNode(
                name = "typed-flow",
                systems = listOf(
                    SystemNode(
                        name = "target",
                        systemType = "typed",
                        config = mapOf("count" to NumberLiteralNode(value = 12.0, isInteger = true))
                    )
                )
            )
        )
        val flowReport = FrontendCompilerComposition.flowValidator(registry).validate(flow)
        assertTrue(flowReport.valid, flowReport.issues.toString())

        val intent = IntentDocument(
            name = "typed-intent",
            systems = listOf(
                IntentSystem(
                    name = "target",
                    type = "typed",
                    config = mapOf("count" to IntentNumber(value = 12.0, isInteger = true))
                )
            )
        )
        val intentReport = IntentCapabilityValidator(registry).validate(intent)
        assertTrue(intentReport.valid, intentReport.issues.toString())
    }
}