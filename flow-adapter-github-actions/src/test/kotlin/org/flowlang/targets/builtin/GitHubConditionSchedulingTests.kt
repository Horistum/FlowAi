package org.flowlang.targets.builtin

import kotlin.test.*
import org.flowlang.capabilities.CompatibilityAnalyzer
import org.flowlang.capabilities.TargetCapability
import org.flowlang.generators.manifest.TargetJob
import org.flowlang.generators.manifest.TargetManifest
import org.flowlang.planner.ExecutionPlan

class GitHubConditionSchedulingTests {
    private val compatibility = CompatibilityAnalyzer(mapOf("github-actions" to TargetCapability(target = "github-actions", description = "Scheduling test", expressionSupport = org.flowlang.capabilities.TargetExpressionSupportDeclaration(
            profileId = "boolean-test", evidenceKind = org.flowlang.capabilities.TargetExpressionEvidenceKind.TARGET_NOTES,
            evidenceReference = "test", features = setOf("node.literal")))))
        .analyze(ExecutionPlan(flowName = "conditions"), "github-actions")
    private fun manifest(jobs: List<TargetJob>) = TargetManifest(target = "github-actions", flowName = "conditions",
        compatibility = compatibility, jobs = GitHubJobConditionAuthority.materialize(jobs))
    private fun job(id: String, vararg needs: String, guard: String? = null) = TargetJob(id = id,
        dependsOn = needs.toList(), metadata = guard?.let { mapOf("condition" to it) }.orEmpty())

    @Test fun booleanContractExcludesAmbientValuesCoercionsAndRuntimeFunctions() {
        for (source in listOf("true", "false", "enabled", "not enabled", "enabled and (true or false)", "enabled == true", "enabled != false"))
            assertTrue(GitHubBooleanCondition.supports(source, setOf("enabled")), source)
        for (source in listOf("unknown", "enabled.ok", "enabled == 1", "enabled == null", "'true'", "success()", "secret(\"token\")", "github.ref", "enabled > false"))
            assertFalse(GitHubBooleanCondition.supports(source, setOf("enabled")), source)
    }

    @Test fun nestedBooleanComparisonsRetainTheirOperandBoundaries() {
        val expression = org.flowlang.parser.ExpressionParser.parseSource("(true == false) != (false == true)")
        assertEquals("(true == false) != (false == true)",
            GitHubActionsTargetExpressionTranslator.renderGitHub(expression, emptySet()))
    }

    @Test fun conditionalAncestorsHaveExplicitIdempotentWaitsWithoutSerializingIndependentJobs() {
        val original = listOf(job("root"), job("optional", "root", guard = "false"),
            job("join", "optional"), job("tail", "join"), job("independent"))
        val m = manifest(original)
        assertEquals(listOf("optional", "root"), m.jobs[2].dependsOn)
        assertEquals(listOf("join", "optional", "root"), m.jobs[3].dependsOn)
        assertEquals("root", m.jobs[2].metadata["githubConditionAncestorWaits"])
        assertEquals(emptyList(), m.jobs.last().dependsOn)
        assertEquals(m.jobs, GitHubJobConditionAuthority.materialize(m.jobs))
        assertEquals(listOf("optional"), original[2].dependsOn)
    }

    @Test fun skippedPredecessorRequiresItsFalseGuardAndEveryAncestorSuccess() {
        val m = manifest(listOf(job("root"), job("optional", "root", guard = "false"), job("join", "optional")))
        val expression = GitHubJobConditionAuthority.expression(m.jobs.last(), m).orEmpty()
        assertEquals("!cancelled() && (needs.optional.result == 'success' || (needs.optional.result == 'skipped' && !(false))) && needs.root.result == 'success'", expression)
        assertFalse("always()" in expression)
        assertFalse("needs.root.result == 'skipped'" in expression)
    }

    @Test fun expectedSkipCannotHideFailureCancellationOrUnexpectedSkip() {
        // Independently specified truth table for the emitted status contract.
        for (cancelled in listOf(false, true)) for (guard in listOf(false, true))
            for (root in listOf("success", "failure", "cancelled", "skipped"))
                for (optional in listOf("success", "failure", "cancelled", "skipped")) {
                    val m = manifest(listOf(job("root"), job("optional", "root", guard = guard.toString()), job("join", "optional")))
                    var expression = GitHubJobConditionAuthority.expression(m.jobs.last(), m)!!
                    expression = expression.replace("!cancelled()", (!cancelled).toString())
                        .replace("needs.root.result == 'success'", (root == "success").toString())
                        .replace("needs.optional.result == 'success'", (optional == "success").toString())
                        .replace("needs.optional.result == 'skipped'", (optional == "skipped").toString())
                        .replace("&&", "and").replace("||", "or").replace("!", "not ")
                    val actual = evaluate(org.flowlang.parser.ExpressionParser.parseSource(expression))
                    val expected = !cancelled && root == "success" &&
                        (optional == "success" || optional == "skipped" && !guard)
                    assertEquals(expected, actual, "$cancelled/$guard/$root/$optional")
                }
    }

    @Test fun missingAndCyclicDependenciesCannotProduceAnExecutableSchedule() {
        assertFails { manifest(listOf(job("one", "missing"))) }
        assertFails { manifest(listOf(job("one", "two"), job("two", "one"))) }
        assertFails { manifest(listOf(job("one"), job("one"))) }
    }

    private fun evaluate(e: org.flowlang.ast.ExpressionNode): Boolean = when (e) {
        is org.flowlang.ast.BooleanLiteralNode -> e.value
        is org.flowlang.ast.UnaryExpressionNode -> { require(e.operator == "not"); !evaluate(e.operand) }
        is org.flowlang.ast.LogicalExpressionNode -> when (e.operator) {
            "and" -> e.operands.all(::evaluate)
            "or" -> e.operands.any(::evaluate)
            else -> error(e.operator)
        }
        else -> error("Unexpected test expression $e")
    }
}
