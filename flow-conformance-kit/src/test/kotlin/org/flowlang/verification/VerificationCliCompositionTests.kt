package org.flowlang.verification

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.flowlang.cli.honest.CliDiagnosticCode
import org.flowlang.cli.honest.CliExecutionResult
import org.flowlang.cli.honest.executeCli

class VerificationCliCompositionTests {
    @Test fun theReferenceHostReusesTheSameTypedProductExecution() {
        assertEquals(executeCli(arrayOf("diagnostics")), executeVerificationCli(arrayOf("diagnostics")))
    }

    @Test fun verificationCommandsHaveOneExplicitNonGlobalComposition() {
        assertEquals(setOf("conformance", "reference-snapshot", "release-profile", "standard-draft", "standard-export"),
            VerificationCommands.catalog.names)
        val help = assertIs<CliExecutionResult.Help>(executeVerificationCli(emptyArray()))
        val text = help.presentation.items.toString()
        VerificationCommands.catalog.names.forEach { assertTrue(it in text) }
        assertIs<CliExecutionResult.Completed>(executeVerificationCli(arrayOf("release-profile")))
        val rejected = assertIs<CliExecutionResult.Rejected>(executeCli(arrayOf("release-profile")))
        assertEquals(CliDiagnosticCode.UNKNOWN_COMMAND, rejected.diagnostic.code)
    }

    @Test fun invalidVerificationInputCannotBecomeAnInternalFailureOrPublication() {
        for (command in listOf("standard-draft", "standard-export")) {
            val result = assertIs<CliExecutionResult.Rejected>(executeVerificationCli(arrayOf(command, "--out")))
            assertEquals(CliDiagnosticCode.INVALID_INPUT, result.diagnostic.code)
            assertEquals(2, result.exitCode)
        }
    }

    @Test fun unknownCommandRemainsRejectedByTheCommonProductBoundary() {
        val result = assertIs<CliExecutionResult.Rejected>(executeVerificationCli(arrayOf("not-a-command")))
        assertEquals(CliDiagnosticCode.UNKNOWN_COMMAND, result.diagnostic.code)
        assertEquals(2, result.exitCode)
    }
}
