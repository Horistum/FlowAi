package org.flowlang.cli.honest

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class CliCommandCompositionTests {
    private fun completedHandler(value: String) = CliCommandHandler { _, output ->
        output.text(value)
        CliExecutionResult.Completed(output.snapshot())
    }

    @Test fun suppliedCommandUsesTheSameTypedExecutionAndPresentationBoundary() {
        val catalog = CliCommandCatalog.of("verify" to CliCommandHandler { args, output ->
            output.section("VERIFICATION INPUT", args)
            CliExecutionResult.Completed(output.snapshot())
        })
        val result = assertIs<CliExecutionResult.Completed>(executeCli(arrayOf("verify", "value"), catalog))
        assertEquals(0, result.exitCode)
        val section = assertIs<CliPresentationItem.Section>(result.presentation.items.single())
        assertEquals("VERIFICATION INPUT", section.title)
        assertEquals(listOf("value"), section.value)
    }

    @Test fun commandFailurePreservesEarlierEvidenceAndUsesExistingExitCodes() {
        val catalog = CliCommandCatalog.of("verify" to CliCommandHandler { _, output ->
            output.section("VERIFICATION EVIDENCE", "observed")
            error("verification refused")
        })
        val result = assertIs<CliExecutionResult.Rejected>(executeCli(arrayOf("verify"), catalog))
        assertEquals(CliDiagnosticCode.INTEGRITY_BLOCKED, result.diagnostic.code)
        assertEquals(4, result.exitCode)
        assertEquals(listOf("VERIFICATION EVIDENCE", "CLI DIAGNOSTIC FAILURE"),
            result.presentation.items.filterIsInstance<CliPresentationItem.Section>().map { it.title })
        assertEquals("verification refused", result.diagnostic.message)
    }

    @Test fun invalidInputAndUnexpectedFailuresRetainTheirDifferentOutcomes() {
        for ((exception, expected) in listOf(
            IllegalArgumentException("bad input") to CliProcessExit.INVALID_INPUT,
            UnsupportedOperationException("unexpected") to CliProcessExit.INTERNAL_ERROR
        )) {
            val catalog = CliCommandCatalog.of("verify" to CliCommandHandler { _, _ -> throw exception })
            val result = assertIs<CliExecutionResult.Rejected>(executeCli(arrayOf("verify"), catalog))
            assertEquals(expected.code, result.exitCode)
        }
    }

    @Test fun productCommandsCannotBeReplacedIncludingDuringHelp() {
        var called = false
        val catalog = CliCommandCatalog.of("intent" to CliCommandHandler { _, output ->
            called = true
            CliExecutionResult.Completed(output.snapshot())
        })
        for (args in listOf(emptyArray(), arrayOf("diagnostics"), arrayOf("intent"))) {
            val result = assertIs<CliExecutionResult.Rejected>(executeCli(args, catalog))
            assertEquals(CliDiagnosticCode.INVALID_INPUT, result.diagnostic.code)
        }
        assertFalse(called)
    }

    @Test fun duplicateCommandNamesAreNotSilentlyOverwritten() {
        assertFailsWith<IllegalArgumentException> {
            CliCommandCatalog.of("verify" to completedHandler("a"), "verify" to completedHandler("b"))
        }
    }

    @Test fun invalidNamesCannotBecomeAliasesOrArgumentParsers() {
        for (name in listOf("", " ", "Verify", "--verify", "verify value", "verify=other", "a--b", "../verify")) {
            assertFailsWith<IllegalArgumentException>(name) {
                CliCommandCatalog.of(name to completedHandler("ignored"))
            }
        }
    }

    @Test fun callerMutationsCannotChangeTheRegisteredNamesOrHandlers() {
        val entries = arrayOf("first" to completedHandler("original"), "second" to completedHandler("second"))
        val catalog = CliCommandCatalog.of(*entries)
        entries[0] = "different" to completedHandler("mutated")
        val exposedNames = catalog.names
        (exposedNames as MutableSet<String>).clear()
        assertEquals(setOf("first", "second"), catalog.names)
        val result = executeCli(arrayOf("first"), catalog)
        assertEquals("original", assertIs<CliPresentationItem.Text>(result.presentation.items.single()).value)
    }

    @Test fun supplyingACommandDoesNotChangeAnotherInvocation() {
        val catalog = CliCommandCatalog.of("verify" to completedHandler("installed"))
        assertIs<CliExecutionResult.Completed>(executeCli(arrayOf("verify"), catalog))
        assertIs<CliExecutionResult.Rejected>(executeCli(arrayOf("verify")))
        val text = executeCli(emptyArray(), catalog).presentation.items
            .filterIsInstance<CliPresentationItem.Text>().joinToString { it.value }
        assertTrue("verify" in text)
    }
}
