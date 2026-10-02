package org.flowlang.conformance

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.*
import org.flowlang.cli.honest.*
import org.flowlang.frontend.FrontendCompilerComposition
import org.flowlang.frontend.intent.IntentYamlFrontend
import org.flowlang.compiler.CompilationResult
import org.flowlang.compiler.requireAccepted
import org.flowlang.modules.CanonicalModuleLoader
import org.flowlang.modules.ModuleRegistry

class LanguageIntegrityIntegrationTests {
    @Test fun standaloneAcceptanceExecutesAllFiveFindingFamiliesAndFrontendParity() {
        val checks = LanguageIntegrityIntegrationChecks().checks()
        assertEquals(listOf(LanguageIntegrityIntegrationChecks.PARITY, LanguageIntegrityIntegrationChecks.DECLARATIONS,
            LanguageIntegrityIntegrationChecks.SCHEMAS, LanguageIntegrityIntegrationChecks.VALUES,
            LanguageIntegrityIntegrationChecks.OWNERS, LanguageIntegrityIntegrationChecks.IDENTITIES,
            LanguageIntegrityIntegrationChecks.LOADERS), checks.map { it.name })
        assertTrue(checks.all { it.passed }, checks.filterNot { it.passed }.joinToString(" | "))
    }

    @Test fun failedCompilationCannotPoisonTheNextDocumentOnTheSameCompiler() {
        val typed = CanonicalModuleLoader.loadText(LanguageIntegrityIntegrationChecks.descriptor)
        val registry = ModuleRegistry((ModuleRegistry().allModules() + typed).associateBy { it.name })
        val frontend = IntentYamlFrontend(FrontendCompilerComposition.compiler(registry))
        val valid = LanguageIntegrityIntegrationChecks.intentYaml
        val before = frontend.compileText(valid).requireAccepted()
        assertIs<CompilationResult.Rejected>(frontend.compileText(valid.replace("id: record", "id: audit_step")))
        assertIs<CompilationResult.Rejected>(frontend.compileText(valid.replace("count: 12", "count: '12'")))
        assertFails { frontend.compileText(valid + "\nname: overwritten") }
        val after = frontend.compileText(valid).requireAccepted()
        assertEquals(before.graph, after.graph)
        assertEquals(before.graphDigest, after.graphDigest)
        assertEquals(before.executionPlan, after.executionPlan)
    }

    @Test fun cliRejectsMalformedOrAmbiguousIntentWithoutPublishingArtifacts() {
        val directory = createTempDirectory("integrated-cli-").toFile()
        try {
            val valid = """
                name: cli-integrity
                workflows:
                  - name: main
                    kind: CUSTOM
                    steps:
                      - {id: audit-step, capability: CUSTOM, params: {operation: inspect}}
                      - {id: record, capability: CUSTOM, params: {operation: record}}
            """.trimIndent()
            val source = File(directory, "input.yaml").apply { writeText(valid) }
            val acceptedOut = File(directory, "accepted")
            val positive = executeCli(arrayOf("intent", source.path, "--out", acceptedOut.path))
            assertEquals(0, positive.exitCode, positive.toString())
            assertTrue(acceptedOut.walkTopDown().any { it.isFile })
            val mutants = listOf(valid + "\nname: overwritten", valid + "\nunknown: true",
                valid + "\n---\n{}", valid.replace("id: record", "id: audit_step"),
                valid + "\npolicies: null", valid + "\nfailure: null",
                valid + "\nfailure: {stopOnError: null}", valid + "\nfailure: {stopOnError: 'false'}")
            mutants.forEachIndexed { index, text ->
                source.writeText(text)
                val output = File(directory, "rejected-$index")
                val result = assertIs<CliExecutionResult.Rejected>(executeCli(arrayOf("intent", source.path, "--out", output.path)))
                assertEquals(if (index == 0 || index == 2) CliDiagnosticCode.INVALID_INPUT else CliDiagnosticCode.INTEGRITY_BLOCKED,
                    result.diagnostic.code)
                assertEquals(if (index == 0 || index == 2) 2 else 4, result.exitCode)
                assertTrue(result.diagnostic.message.isNotBlank())
                assertFalse(output.exists(), "Rejected input published output: $text")
                // Reusing an existing output path must not erase or replace the
                // previously accepted bundle when source capture fails.
                val before = acceptedOut.walkTopDown().filter { it.isFile }
                    .associate { it.relativeTo(acceptedOut).path to it.readBytes().toList() }
                val reused = executeCli(arrayOf("intent", source.path, "--out", acceptedOut.path))
                assertIs<CliExecutionResult.Rejected>(reused)
                assertEquals(before, acceptedOut.walkTopDown().filter { it.isFile }
                    .associate { it.relativeTo(acceptedOut).path to it.readBytes().toList() })
            }
        } finally { directory.deleteRecursively() }
    }
}
