package org.flowlang.tests

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.flowlang.cli.honest.CliExecutionResult
import org.flowlang.cli.honest.CliPresentationItem
import org.flowlang.cli.honest.executeCli

class ReviewedAiProposalCliConvergenceTests {
    @Test
    fun normalizeLowerUsesTheConvergedCompilerAndReturnsTargetNeutralPlanning() {
        val result = executeCli(
            arrayOf(
                "normalize",
                "Build and test the orders service.",
                "--lower"
            )
        )
        val targetNeutral = assertIs<CliExecutionResult.TargetNeutral>(result)
        val sections = targetNeutral.presentation.items
            .filterIsInstance<CliPresentationItem.Section>()
            .map { it.title }

        assertEquals(0, targetNeutral.exitCode)
        assertTrue("AI INTENT NORMALIZATION REPORT" in sections)
        assertTrue("INTENT CAPABILITY VALIDATION REPORT" in sections)
        assertTrue("GENERATED FLOW AST JSON" in sections)
        assertTrue("EXECUTION PLAN JSON" in sections)
        assertTrue("CANONICAL EXECUTION PLAN JSON" in sections)
        assertTrue("TARGET-NEUTRAL PLANNING EVIDENCE" in sections)
    }
}
