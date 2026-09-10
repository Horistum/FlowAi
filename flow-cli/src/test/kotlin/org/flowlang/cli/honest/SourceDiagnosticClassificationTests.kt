package org.flowlang.cli.honest

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import org.flowlang.parser.FlowParser

/** Pure product error mapping: no registry, verification kit or working-directory fixtures. */
class SourceDiagnosticClassificationTests {
    @Test fun actualSourceFailuresUseInvalidInputWithoutAnExternalRegistry() {
        val sources = listOf("flow", "flow \"x\" { steps { @ } }",
            "flow \"x\" { steps { m.a s { safety: requiresApproval safety: onlyIf true } } }")
        for (source in sources) {
            val commands = CliCommandCatalog.of("parse-source" to CliCommandHandler { _, output ->
                FlowParser().parse(source)
                CliExecutionResult.Completed(output.snapshot())
            })
            val result = assertIs<CliExecutionResult.Rejected>(executeCli(arrayOf("parse-source"), commands))
            assertEquals(CliProcessExit.INVALID_INPUT.code, result.exitCode)
            assertEquals(CliDiagnosticCode.INVALID_INPUT, result.diagnostic.code)
            assertEquals(emptyList(), result.artifacts)
        }
    }
}
