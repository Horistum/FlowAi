import org.flowlang.frontend.FrontendCompilerComposition
import kotlin.test.Test
import kotlin.test.assertTrue
import org.flowlang.parser.FlowParser
import org.flowlang.validator.SafetyBoundaryValidator

/**
 * Regression guard for the v0.8.5 safety-boundary handler gap.
 *
 * The safety boundary walked control-flow statements (if/for/match/try/...) but never descended into
 * an action's RESULT HANDLER (`-> res { when ... { ... } }`). A destructive, approval-required action
 * could therefore bypass the entire gate simply by being nested in a handler branch of a benign
 * action. These tests pin the closure of that gap, and its legitimate exception: rollback-sensitive
 * actions inside `when error { ... }` branches remain allowed (an error branch IS an error handler).
 */
class SafetyBoundaryHandlerTests {
    private fun parse(source: String) = FlowParser().parse(source.trimIndent())

    @Test
    fun destructiveActionInsideResultHandlerStillRequiresApproval() {
        val src = """
            version "1.0"
            use module "kubernetes" version "1.0"
            flow "cleanup" {
              systems {
                system "k8s" { type: kubernetes }
              }
              steps {
                kubernetes.get k8s {
                  resource: "pods"
                } -> pods {
                  when ok == true {
                    kubernetes.delete k8s {
                      resource: "pods"
                      name: "stale"
                    }
                  }
                }
              }
            }
        """
        val issues = FrontendCompilerComposition.safetyValidator().validate(parse(src))
        assertTrue(
            issues.any { it.code == "APPROVAL_REQUIRED" },
            "a destructive action nested in a result-handler branch must not bypass the safety boundary: $issues"
        )
    }

    @Test
    fun rollbackInsideHandlerErrorBranchRemainsAllowed() {
        val src = """
            version "1.0"
            use module "kubernetes" version "1.0"
            use module "standard" version "1.0"
            flow "deploy" {
              systems {
                system "k8s" { type: kubernetes }
              }
              steps {
                kubernetes.get k8s {
                  resource: "deployments"
                } -> state {
                  when error {
                    standard.rollback k8s {
                      flow: "deploy"
                    }
                  }
                }
              }
            }
        """
        val issues = FrontendCompilerComposition.safetyValidator().validate(parse(src))
        assertTrue(
            issues.none { it.code == "ROLLBACK_APPROVAL_REQUIRED" },
            "rollback inside a handler error branch is a legitimate error handler and must stay allowed: $issues"
        )
    }
}
