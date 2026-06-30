import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.flowlang.parser.FlowParser
import org.flowlang.validator.FlowValidator
import org.flowlang.validator.SafetyBoundaryValidator

class SafetyBoundaryHardeningTests {
    @Test
    fun productionMutatingActionRequiresApprovalBeforeProjection() {
        val report = FlowValidator().validate(parse(productionDeployFlow()))

        assertFalse(report.valid)
        assertTrue(report.issues.any { it.code == "PRODUCTION_APPROVAL_REQUIRED" }, report.issues.toString())
    }

    @Test
    fun productionMutatingActionWithApprovalPassesSafetyBoundary() {
        val report = FlowValidator().validate(parse(productionDeployFlowWithApproval()))

        assertTrue(report.valid, report.issues.toString())
    }

    @Test
    fun destructiveActionWithOnlyIfStillRequiresApproval() {
        val report = FlowValidator().validate(parse(deleteFlowWithOnlyIfSafety()))

        assertFalse(report.valid)
        assertTrue(report.issues.any { it.code == "APPROVAL_REQUIRED" }, report.issues.toString())
    }

    @Test
    fun destructiveActionWithApprovalPassesSafetyBoundary() {
        val report = FlowValidator().validate(parse(deleteFlowWithApproval()))

        assertTrue(report.valid, report.issues.toString())
    }

    @Test
    fun rollbackSensitiveActionRequiresApproval() {
        val issues = SafetyBoundaryValidator().validate(parse(rollbackFlowWithoutApproval()))

        assertTrue(issues.any { it.code == "ROLLBACK_APPROVAL_REQUIRED" }, issues.toString())
    }

    private fun parse(source: String) = FlowParser().parse(source.trimIndent())

    private fun productionDeployFlow() = """
        version "1.0"
        use module "kubernetes" version "1.0"
        flow "prod deploy" {
          systems {
            system "k8s" { type: kubernetes }
          }
          steps {
            kubernetes.deploy k8s {
              app: "demo"
              namespace: "prod"
              image: "demo:1"
            }
          }
        }
    """

    private fun productionDeployFlowWithApproval() = """
        version "1.0"
        use module "kubernetes" version "1.0"
        flow "prod deploy approved" {
          systems {
            system "k8s" { type: kubernetes }
          }
          steps {
            kubernetes.deploy k8s {
              app: "demo"
              namespace: "prod"
              image: "demo:1"
              safety: requiresApproval
            }
          }
        }
    """

    private fun deleteFlowWithOnlyIfSafety() = """
        version "1.0"
        use module "kubernetes" version "1.0"
        flow "delete with weak safety" {
          input {
            allowDelete: boolean required
          }
          systems {
            system "k8s" { type: kubernetes }
          }
          steps {
            kubernetes.delete k8s {
              resource: "deployment"
              name: "demo"
              namespace: "dev"
              safety: onlyIf allowDelete == true
            }
          }
        }
    """

    private fun deleteFlowWithApproval() = """
        version "1.0"
        use module "kubernetes" version "1.0"
        flow "delete approved" {
          systems {
            system "k8s" { type: kubernetes }
          }
          steps {
            kubernetes.delete k8s {
              resource: "deployment"
              name: "demo"
              namespace: "dev"
              safety: requiresApproval
            }
          }
        }
    """

    private fun rollbackFlowWithoutApproval() = """
        version "1.0"
        use module "standard" version "1.0"
        flow "rollback without approval" {
          systems {
            system "standard" { type: standard }
          }
          steps {
            standard.rollback standard {
              flow: "prod-deploy"
            }
          }
        }
    """
}
