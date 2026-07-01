import kotlin.test.Test
import kotlin.test.assertTrue
import org.flowlang.parser.FlowParser
import org.flowlang.validator.SafetyBoundaryValidator

class SafetyBoundaryHardeningTests {
    @Test
    fun productionMutatingActionRequiresApprovalInStrictBoundaryMode() {
        val issues = strictValidator().validate(parse(productionDeployFlow()))

        assertTrue(issues.any { it.code == "PRODUCTION_APPROVAL_REQUIRED" }, issues.toString())
    }

    @Test
    fun productionMutatingActionWithApprovalPassesStrictBoundaryMode() {
        val issues = strictValidator().validate(parse(productionDeployFlowWithApproval()))

        assertTrue(issues.none { it.level == "error" }, issues.toString())
    }

    @Test
    fun destructiveActionWithOnlyIfStillRequiresApproval() {
        val issues = defaultValidator().validate(parse(deleteFlowWithOnlyIfSafety()))

        assertTrue(issues.any { it.code == "APPROVAL_REQUIRED" }, issues.toString())
    }

    @Test
    fun destructiveActionWithApprovalPassesSafetyBoundary() {
        val issues = defaultValidator().validate(parse(deleteFlowWithApproval()))

        assertTrue(issues.none { it.level == "error" }, issues.toString())
    }

    @Test
    fun rollbackSensitiveActionRequiresApprovalOutsideErrorHandlers() {
        val issues = defaultValidator().validate(parse(rollbackFlowWithoutApproval()))

        assertTrue(issues.any { it.code == "ROLLBACK_APPROVAL_REQUIRED" }, issues.toString())
    }

    @Test
    fun rollbackSensitiveActionIsAllowedInsideErrorHandlerBoundary() {
        val issues = defaultValidator().validate(parse(rollbackInsideErrorHandlerFlow()))

        assertTrue(issues.none { it.code == "ROLLBACK_APPROVAL_REQUIRED" }, issues.toString())
    }

    private fun defaultValidator() = SafetyBoundaryValidator()
    private fun strictValidator() = SafetyBoundaryValidator(enforceProductionBoundary = true)
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

    private fun rollbackInsideErrorHandlerFlow() = """
        version "1.0"
        use module "standard" version "1.0"
        flow "rollback inside handler" {
          systems {
            system "standard" { type: standard }
          }
          steps {
            standard.execute standard {
              operation: "deploy"
              flow: "prod-deploy"
            }
          }
          on error {
            standard.rollback standard {
              flow: "prod-deploy"
            }
          }
        }
    """
}
