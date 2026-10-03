package org.flowlang.cli.honest

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Actual flow command integration uses the reference registry and belongs to the verification kit. */
class SourceInputDiagnosticTests {
    @Test fun duplicateSafetyHasTheInvalidInputExitAndProducesNoPlanningArtifacts() {
        withSource("flow \"x\" { steps { m.a s { safety: requiresApproval safety: onlyIf true } } }") { file ->
            val result = assertIs<CliExecutionResult.Rejected>(executeCli(arrayOf("flow", file.path)))
            assertEquals(2, result.exitCode)
            assertEquals(CliDiagnosticCode.INVALID_INPUT, result.diagnostic.code)
            assertTrue(result.diagnostic.message.contains("FLOW_DUPLICATE_DECLARATION"))
            assertTrue(result.diagnostic.message.contains("flow.steps[0].safety"))
            assertEquals(emptyList(), result.artifacts)
            val sections = result.presentation.items.filterIsInstance<CliPresentationItem.Section>()
            assertEquals(listOf("FLOW CONTRACT RESOURCE PROVENANCE", "CLI DIAGNOSTIC FAILURE"), sections.map { it.title })
            val provenance = assertIs<List<*>>(sections.first().value)
                .map { assertIs<org.flowlang.distribution.reference.ContractResourceProvenance>(it) }
            assertTrue(provenance.isNotEmpty())
            assertTrue(provenance.all { it.origin == org.flowlang.distribution.reference.ContractResourceOrigin.CLASSPATH })
            assertEquals(listOf("input.flow"), file.parentFile.list()!!.toList())
        }
    }

    @Test fun parserSyntaxFailureUsesInvalidInputRatherThanInternalFailure() {
        withSource("flow") { file ->
            val result = assertIs<CliExecutionResult.Rejected>(executeCli(arrayOf("flow", file.path)))
            assertEquals(CliProcessExit.INVALID_INPUT.code, result.exitCode)
            assertEquals(CliDiagnosticCode.INVALID_INPUT, result.diagnostic.code)
            assertEquals(emptyList(), result.artifacts)
        }
    }

    @Test fun lexicalFailureUsesTheSameInvalidInputClassification() {
        withSource("flow \"x\" { steps { @ } }") { file ->
            val result = assertIs<CliExecutionResult.Rejected>(executeCli(arrayOf("flow", file.path)))
            assertEquals(CliProcessExit.INVALID_INPUT.code, result.exitCode)
            assertEquals(CliDiagnosticCode.INVALID_INPUT, result.diagnostic.code)
            assertEquals(emptyList(), result.artifacts)
        }
    }

    private fun withSource(source: String, assess: (File) -> Unit) {
        val directory = createTempDirectory("source-input-diagnostic-").toFile()
        try { assess(File(directory, "input.flow").apply { writeText(source) }) }
        finally { check(directory.deleteRecursively()) }
    }
}
