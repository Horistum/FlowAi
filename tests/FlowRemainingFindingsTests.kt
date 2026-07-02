package org.flowlang.tests

import org.flowlang.ast.IdentifierLiteralNode
import org.flowlang.ast.ReferenceNode
import org.flowlang.capabilities.CompatibilityReport
import org.flowlang.capabilities.SupportLevel
import org.flowlang.generators.manifest.JenkinsManifestGenerator
import org.flowlang.generators.manifest.TargetStep
import org.flowlang.modules.ModuleRegistry
import org.flowlang.parser.ExpressionParser
import org.flowlang.parser.FlowParser
import org.flowlang.planner.FlowPlanner
import org.flowlang.validator.FlowValidator
import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Regression guards for the remaining review findings resolved in this patch line:
 * #6 (notify masking), #8 (cross-branch DUPLICATE_RESULT false-positive),
 * #9 (typo in a schema'd module output slipping through as a warning),
 * #11 (all-caps bareword silently becoming a string literal instead of a reference).
 */
class FlowRemainingFindingsTests {
    private val registry = ModuleRegistry.fromDirectory(File("modules"), includeDefaults = true)
    private fun validate(src: String) = FlowValidator(registry).validate(FlowParser().parse(src))
    private fun flatten(step: TargetStep): List<TargetStep> = listOf(step) + step.children.flatMap { flatten(it) }

    @Test
    fun notifySendDoesNotMaskFailure() {
        val src = """
            use module "notify" version "1.0"
            flow "n" {
              systems { system "m" { type: notify channel: email } }
              steps { notify.send m { subject: "hi" body: "b" to: "x@y.z" } }
            }
        """.trimIndent()
        val ast = FlowParser().parse(src)
        assertTrue(FlowValidator(registry).validate(ast).valid)
        val plan = FlowPlanner(registry).plan(ast)
        val manifest = JenkinsManifestGenerator().generate(plan, CompatibilityReport(target = "jenkins", status = SupportLevel.SUPPORTED))
        val notifyRun = manifest.jobs.flatMap { it.steps }.flatMap { flatten(it) }.mapNotNull { it.run }.single { it.contains("mail -s") }
        assertTrue(notifyRun.contains(">&2") && notifyRun.contains("exit 1"), "notify failure must surface on stderr and be non-zero: $notifyRun")
        assertFalse(notifyRun.contains("|| echo "), "notify failure must not be masked by '|| echo': $notifyRun")
    }

    @Test
    fun sameResultNameInThenAndElseIsNotDuplicate() {
        val rep = validate("""
            use module "shell" version "1.0"
            flow "t" {
              input { env: option ["a","b"] required }
              systems { system "l" { type: shell } }
              steps {
                if env == "a" { shell.run l { command: "x" } -> r }
                else { shell.run l { command: "y" } -> r }
              }
            }
        """.trimIndent())
        assertTrue(rep.valid, rep.issues.toString())
        assertFalse(rep.issues.any { it.code == "DUPLICATE_RESULT" }, rep.issues.toString())
    }

    @Test
    fun sequentialDuplicateResultStillErrors() {
        val rep = validate("""
            use module "shell" version "1.0"
            flow "t" {
              systems { system "l" { type: shell } }
              steps {
                shell.run l { command: "x" } -> r
                shell.run l { command: "y" } -> r
              }
            }
        """.trimIndent())
        assertTrue(rep.issues.any { it.code == "DUPLICATE_RESULT" && it.level == "error" }, rep.issues.toString())
    }

    @Test
    fun typoInResultFieldOfSchemadModuleIsError() {
        val rep = validate("""
            use module "rest" version "1.0"
            flow "t" {
              systems { system "api" { type: rest baseUrl: secret("U") } }
              steps { rest.call api { method: GET path: "/x" } -> r { expect { statuss == 200 } } }
            }
        """.trimIndent())
        assertTrue(rep.issues.any { it.code == "UNKNOWN_RESULT_FIELD" && it.level == "error" }, rep.issues.toString())
        assertFalse(rep.valid, "a typo'd output field must fail validation")
    }

    @Test
    fun declaredResultFieldOfSchemadModuleIsValid() {
        val rep = validate("""
            use module "rest" version "1.0"
            flow "t" {
              systems { system "api" { type: rest baseUrl: secret("U") } }
              steps { rest.call api { method: GET path: "/x" } -> r { expect { code == 200 } } }
            }
        """.trimIndent())
        assertTrue(rep.valid, rep.issues.toString())
    }

    @Test
    fun allCapsNonVerbBarewordIsReferenceNotLiteral() {
        assertTrue(ExpressionParser.parseSource("PROD") is ReferenceNode, "PROD must parse as a reference, not a string literal")
        assertTrue(ExpressionParser.parseSource("GET") is IdentifierLiteralNode, "GET must stay a symbolic HTTP-verb literal")
    }
}
