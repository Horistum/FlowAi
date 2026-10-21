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
            H.ok("mod-parity/$file/unsupported-handler", !report.valid &&
                report.issues.filter { it.level == "error" }.map { it.code }.toSet() == setOf("ACTION_RESULT_HANDLER_UNSUPPORTED"))
            H.eq("mod-parity/$file/rejection-parity", report,
                FrontendCompilerComposition.flowValidator().validate(document))
            val failure = runCatching { planner.plan(document) }.exceptionOrNull()
            H.ok("mod-parity/$file/no-plan", failure is org.flowlang.core.UnsupportedActionResultHandlerException)
        } catch (error: Exception) {
            H.ok("mod-parity/$file :: ${error.message}", false)
        }
    }
    moduleRegistrySafetyParityTest(registry)
}
