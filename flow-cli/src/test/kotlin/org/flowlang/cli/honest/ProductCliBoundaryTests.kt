package org.flowlang.cli.honest

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.flowlang.testing.ExternalCompilerProbe

class ProductCliBoundaryTests {
    @Test fun productRunsWithoutConformanceOrReleaseTooling() {
        val result = executeCli(arrayOf("diagnostics"))
        assertEquals(CliProcessExit.SUCCESS.code, result.exitCode)
        assertTrue(result.presentation.items.filterIsInstance<CliPresentationItem.Section>()
            .any { it.title == "FLOW STANDARD DIAGNOSTIC CATALOG" })
        listOf("org.flowlang.conformance.ConformanceRunner",
            "org.flowlang.release.StandardReleaseAssemblyAuthority",
            "org.flowlang.verification.VerificationCommands",
            "com.fasterxml.jackson.module.jsonSchema.JsonSchemaGenerator").forEach { name ->
            assertFailsWith<ClassNotFoundException>(name) { Class.forName(name) }
        }
    }

    @Test fun standaloneHelpListsOnlyActuallyInstalledCommands() {
        val help = assertIs<CliExecutionResult.Help>(executeCli(emptyArray()))
        val text = help.presentation.items.filterIsInstance<CliPresentationItem.Text>().joinToString { it.value }
        assertTrue("intent" in text && "standard-verify" in text)
        assertFalse("conformance" in text || "standard-export" in text || "release-profile" in text)
    }

    @Test fun absentVerificationDoesNotSilentlyRunOrReportSuccess() {
        val rejected = assertIs<CliExecutionResult.Rejected>(executeCli(arrayOf("conformance")))
        assertEquals(CliDiagnosticCode.UNKNOWN_COMMAND, rejected.diagnostic.code)
        assertEquals(CliProcessExit.INVALID_INPUT.code, rejected.exitCode)
        assertTrue(rejected.artifacts.isEmpty())
    }

    @Test fun anIndependentConsumerCanUseThePublicCompositionPort() {
        ExternalCompilerProbe.accepts("""
            package independent.consumer
            import org.flowlang.cli.honest.*
            val commands = CliCommandCatalog.of("verify" to CliCommandHandler { args, output ->
                output.text(args.joinToString())
                CliExecutionResult.Completed(output.snapshot())
            })
            fun invoke() = executeCli(arrayOf("verify", "explicit"), commands)
        """.trimIndent())
    }

    @Test fun productConsumersCannotCompileAgainstConformanceEvenUsingAnAlias() {
        ExternalCompilerProbe.rejects("""
            import org.flowlang.conformance.ConformanceRunner as Runner
            val forbidden: Runner? = null
        """.trimIndent(), "ConformanceRunner")
    }

    @Test fun productConsumersCannotCompileAgainstReleaseAssembly() {
        ExternalCompilerProbe.rejects(
            "val forbidden = org.flowlang.release.StandardReleaseAssemblyAuthority()", "release")
    }

    @Test fun externalCommandsCannotAccessTheInternalCollector() {
        ExternalCompilerProbe.rejects("""
            package org.flowlang.cli.honest
            val forbidden = CliOutputCollector()
        """.trimIndent(), "internal")
    }
}
