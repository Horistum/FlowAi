import org.flowlang.frontend.FrontendCompilerComposition
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.flowlang.cli.honest.runCli
import org.flowlang.parser.FlowParser
import org.flowlang.safety.EnvironmentParameterEvidence
import org.flowlang.safety.EnvironmentSensitivity
import org.flowlang.safety.EnvironmentValueKind
import org.flowlang.safety.StandardEnvironmentSafetyPolicyNotes
import org.flowlang.validator.FlowValidator

class EnvironmentSafetyProductionIntegrationTests {
    @Test
    fun standardPolicyPrioritizesSensitiveThenUnknownThenNonSensitive() {
        val policy = StandardEnvironmentSafetyPolicyNotes.policy()
        val evidence = policy.classify(
            listOf(
                EnvironmentParameterEvidence("namespace", EnvironmentValueKind.LITERAL, literalValue = "dev"),
                EnvironmentParameterEvidence("cluster", EnvironmentValueKind.REFERENCE, referencePath = listOf("targetCluster")),
                EnvironmentParameterEvidence("environment", EnvironmentValueKind.LITERAL, literalValue = "prod")
            )
        )
        assertEquals(EnvironmentSensitivity.SENSITIVE, evidence.sensitivity)
        assertEquals("environment.sensitive.production-like", evidence.ruleId)
    }

    @Test
    fun flowValidatorIncludesSensitiveEnvironmentSafetyByDefault() {
        val report = FrontendCompilerComposition.flowValidator().validate(parse(deploy(namespace = "\"prod\"")))
        assertFalse(report.valid)
        assertTrue(report.issues.any { it.code == "ENVIRONMENT_APPROVAL_REQUIRED" }, report.issues.toString())
    }

    @Test
    fun approvedSensitiveLiteralPassesEnvironmentBoundary() {
        val report = FrontendCompilerComposition.flowValidator().validate(parse(deploy(namespace = "\"production\"", approval = true)))
        assertTrue(report.issues.none { it.code.startsWith("ENVIRONMENT_") }, report.issues.toString())
    }

    @Test
    fun knownEngineeringLiteralPassesEnvironmentBoundary() {
        val report = FrontendCompilerComposition.flowValidator().validate(parse(deploy(namespace = "\"qa\"")))
        assertTrue(report.issues.none { it.code.startsWith("ENVIRONMENT_") }, report.issues.toString())
    }

    @Test
    fun unclassifiedLiteralFailsClosedButIsNotInventedAsProduction() {
        val report = FrontendCompilerComposition.flowValidator().validate(parse(deploy(namespace = null, environment = "\"customer-a\"")))
        assertFalse(report.valid)
        assertTrue(report.issues.any { it.code == "ENVIRONMENT_CLASSIFICATION_UNKNOWN" }, report.issues.toString())
        assertTrue(report.issues.none { it.code == "ENVIRONMENT_APPROVAL_REQUIRED" }, report.issues.toString())
    }

    @Test
    fun referenceRemainsReferenceAndFailsClosed() {
        val report = FrontendCompilerComposition.flowValidator().validate(parse(deploy(namespace = "environment", input = true)))
        val issue = report.issues.single { it.code == "ENVIRONMENT_CLASSIFICATION_UNKNOWN" }
        assertTrue(issue.message.contains("runtime reference 'environment'"), issue.message)
        assertFalse(issue.message.contains("namespace=environment"), issue.message)
    }

    @Test
    fun unrelatedProductionTextIsNotEnvironmentEvidence() {
        val report = FrontendCompilerComposition.flowValidator().validate(parse(deploy(namespace = null, app = "\"production\"")))
        assertTrue(report.issues.none { it.code.startsWith("ENVIRONMENT_") }, report.issues.toString())
    }

    @Test
    fun sensitiveEnvironmentConditionalApprovalGuardsDynamicEnvironment() {
        val report = FrontendCompilerComposition.flowValidator().validate(parse(
            """
            version "1.0"
            use module "kubernetes" version "1.0"
            flow "conditional environment approval" {
              input { environment: text required }
              systems { system "k8s" { type: kubernetes } }
              steps {
                if environment == "prod" {
                  approve manual { message: "Production approval" }
                }
                kubernetes.deploy k8s {
                  app: "demo"
                  namespace: environment
                  image: "demo:1"
                }
              }
            }
            """
        ))
        assertTrue(report.issues.none { it.code.startsWith("ENVIRONMENT_") }, report.issues.toString())
    }

    @Test
    fun unrelatedConditionalApprovalDoesNotAuthorizeDynamicEnvironment() {
        val report = FrontendCompilerComposition.flowValidator().validate(parse(
            """
            version "1.0"
            use module "kubernetes" version "1.0"
            flow "unrelated approval" {
              input {
                environment: text required
                allow: boolean required
              }
              systems { system "k8s" { type: kubernetes } }
              steps {
                if allow == true {
                  approve manual { message: "Unrelated approval" }
                }
                kubernetes.deploy k8s {
                  app: "demo"
                  namespace: environment
                  image: "demo:1"
                }
              }
            }
            """
        ))
        assertTrue(report.issues.any { it.code == "ENVIRONMENT_CLASSIFICATION_UNKNOWN" }, report.issues.toString())
    }

    @Test
    fun finiteOnlyIfGuardProvesDestructiveWorkIsNonSensitive() {
        val report = FrontendCompilerComposition.flowValidator().validate(parse(
            """
            version "1.0"
            use module "kubernetes" version "1.0"
            flow "finite non-sensitive cleanup" {
              input { environment: option ["dev", "test", "prod"] required }
              systems { system "k8s" { type: kubernetes } }
              steps {
                kubernetes.delete k8s {
                  resource: "namespace"
                  name: "old"
                  safety: onlyIf environment != "prod"
                }
              }
            }
            """
        ))
        assertTrue(report.issues.none { it.code in setOf("SAFETY_REQUIRED", "APPROVAL_REQUIRED") }, report.issues.toString())
    }

    @Test
    fun onlyIfGuardDoesNotPassWhenFiniteDomainStillContainsSensitiveValues() {
        val report = FrontendCompilerComposition.flowValidator().validate(parse(
            """
            version "1.0"
            use module "kubernetes" version "1.0"
            flow "unsafe finite cleanup" {
              input { environment: option ["dev", "test", "prod"] required }
              systems { system "k8s" { type: kubernetes } }
              steps {
                kubernetes.delete k8s {
                  resource: "namespace"
                  name: "old"
                  safety: onlyIf environment != "dev"
                }
              }
            }
            """
        ))
        assertTrue(report.issues.any { it.code == "APPROVAL_REQUIRED" }, report.issues.toString())
    }

    @Test
    fun conditionalApprovalTextDoesNotCountAsUnconditionalApproval() {
        val source = deploy(namespace = "\"prod\"")
            .replace("image: \"demo:1\"", "image: \"demo:1\"\n          safety: onlyIf allow == true")
            .replace("flow \"environment safety\" {", "flow \"environment safety\" {\n      input { allow: boolean required }")
        val report = FrontendCompilerComposition.flowValidator().validate(parse(source))
        assertTrue(report.issues.any { it.code == "ENVIRONMENT_APPROVAL_REQUIRED" }, report.issues.toString())
    }

    @Test
    fun cliBlocksUnsafeFlowBeforeExecutionPlanOutput() {
        val file = Files.createTempFile("flow-unsafe-environment", ".flow").toFile()
        file.writeText(deploy(namespace = "environment", input = true))
        val originalOut = System.out
        val output = ByteArrayOutputStream()
        try {
            System.setOut(PrintStream(output))
            val status = runCli(arrayOf("flow", file.absolutePath))
            assertEquals(2, status)
        } finally {
            System.setOut(originalOut)
            file.delete()
        }
        val text = output.toString()
        assertTrue(text.contains("ENVIRONMENT_CLASSIFICATION_UNKNOWN"), text)
        assertTrue(text.contains("CLI DIAGNOSTIC FAILURE"), text)
        assertFalse(text.contains("EXECUTION PLAN JSON"), text)
        assertFalse(text.contains("Exception in thread"), text)
    }

    private fun parse(source: String) = FlowParser().parse(source.trimIndent())

    private fun deploy(
        namespace: String?,
        app: String = "\"demo\"",
        approval: Boolean = false,
        input: Boolean = false,
        environment: String? = null
    ): String {
        val inputBlock = if (input) "input { environment: text required }" else ""
        val namespaceLine = namespace?.let { "namespace: $it" }.orEmpty()
        val environmentLine = environment?.let { "environment: $it" }.orEmpty()
        val approvalLine = if (approval) "safety: requiresApproval" else ""
        return """
            version "1.0"
            use module "kubernetes" version "1.0"
            flow "environment safety" {
              $inputBlock
              systems {
                system "k8s" { type: kubernetes }
              }
              steps {
                kubernetes.deploy k8s {
                  app: $app
                  $namespaceLine
                  $environmentLine
                  image: "demo:1"
                  $approvalLine
                }
              }
            }
        """
    }
}
