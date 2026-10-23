import org.flowlang.frontend.FrontendCompilerComposition
import org.flowlang.modules.ModuleRegistry
import org.flowlang.parser.FlowParser
import org.flowlang.planner.FlowPlanner
import org.flowlang.validator.FlowValidator

fun descriptorRegistryParityTests() {
    val registry = ModuleRegistry.fromDirectory(modulesDir(), includeDefaults = false)
    val validator = FrontendCompilerComposition.flowValidator(registry)
    val planner = FlowPlanner(registry)
    val files = listOf(
        "api-sync.flow",
        "build-test.flow",
        "complex-devops-flow.flow",
        "deploy-with-approval.flow",
        "kubernetes-cleanup.flow"
    )
    for (file in files) {
        val source = exampleFile(file)
        try {
            val document = FlowParser().parse(source)
            val report = validator.validate(document)
            val expectedCodes = if (file == "api-sync.flow") {
                setOf("ACTION_RESULT_HANDLER_UNSUPPORTED", "DATA_OPERATION_PAYLOAD_UNSUPPORTED")
            } else if (file in setOf("complex-devops-flow.flow", "deploy-with-approval.flow")) {
                setOf("ACTION_RESULT_HANDLER_UNSUPPORTED", "GLOBAL_VARIABLE_INITIALIZER_UNSUPPORTED")
            } else setOf("ACTION_RESULT_HANDLER_UNSUPPORTED")
            H.ok("mod-parity/$file/unsupported-payload", !report.valid &&
                report.issues.filter { it.level == "error" }.map { it.code }.toSet() == expectedCodes)
            H.eq("mod-parity/$file/rejection-parity", report,
                FrontendCompilerComposition.flowValidator().validate(document))
            val failure = runCatching { planner.plan(document) }.exceptionOrNull()
            H.ok("mod-parity/$file/no-plan", if (document.flow.vars.isNotEmpty()) failure is org.flowlang.core.UnsupportedGlobalVariableInitializerException
                else failure is org.flowlang.core.UnsupportedActionResultHandlerException)
        } catch (error: Exception) {
            H.ok("mod-parity/$file :: ${error.message}", false)
        }
    }
    moduleRegistrySafetyParityTest(registry)
}
