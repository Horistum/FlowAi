import org.flowlang.frontend.FrontendCompilerComposition
import org.flowlang.modules.ModuleRegistry
import org.flowlang.validator.FlowValidator

fun moduleRegistrySafetyParityTest(registry: ModuleRegistry) {
    val report = FrontendCompilerComposition.flowValidator(registry).validate(
        doc(
            """
            use module "kubernetes" version "1.0"
            flow "t" {
              systems { system "c" { type: kubernetes } }
              steps { kubernetes.delete c { resource: "ns" name: "x" } }
            }
            """.trimIndent()
        )
    )
    H.ok(
        "mod-parity/safety-enforced",
        report.issues.any { it.level == "error" && it.code == "SAFETY_REQUIRED" }
    )
}
