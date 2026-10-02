package org.flowlang.cli.honest

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.*
import org.flowlang.cli.Json
import org.flowlang.verification.executeVerificationCli

class CliArgumentIntegrityTests {
    private val intent = "examples/intent/build-test-deploy.intent.yaml"
    private fun json(result: CliExecutionResult) = Json.mapper.writeValueAsString(result.presentation)

    @Test fun bothHostsSelectTheAuthoredIntentAfterOptionValues() {
        for (execute in listOf<(Array<String>) -> CliExecutionResult>(::executeCli, ::executeVerificationCli)) {
            val root = createTempDirectory("cli-argument-intent-").toFile()
            try {
                val out = File(root, "result dir")
                val baseline = execute(arrayOf("intent", intent, "--target", "jenkins"))
                for (args in listOf(
                    arrayOf("intent", "--out", out.path, "--target", "jenkins", intent),
                    arrayOf("intent", "--target=jenkins", "--out=${out.path}", intent),
                    arrayOf("intent", "--out", out.path, intent, "--target", "jenkins")
                )) {
                    val result = execute(args)
                    assertIs<CliExecutionResult.Targeted>(result)
                    assertEquals(json(baseline), json(result))
                    assertTrue(File(out, "normalized-intent.json").isFile)
                }
            } finally { root.deleteRecursively() }
        }
    }

    @Test fun normalizationPreservesTextAfterInterspersedOptions() {
        val baseline = executeCli(arrayOf("normalize", "build", "and", "test", "--app", "shop", "--explain"))
        val reordered = executeCli(arrayOf("normalize", "--app", "shop", "build", "--explain", "and", "test"))
        assertIs<CliExecutionResult.Completed>(reordered)
        assertEquals(json(baseline), json(reordered))
    }

    @Test fun snapshotSourceIsIndependentOfOutputAndScenarioOptionOrder() {
        val root = createTempDirectory("cli-argument-snapshot-").toFile()
        try {
            val first = File(root, "first")
            val second = File(root, "second")
            val baseline = executeVerificationCli(arrayOf("reference-snapshot", intent, "--out", first.path,
                "--scenario-id", "argument-order", "--targets", "jenkins"))
            val reordered = executeVerificationCli(arrayOf("reference-snapshot", "--out", second.path,
                "--scenario-id=argument-order", "--targets=jenkins", intent))
            assertEquals(0, baseline.exitCode, json(baseline))
            assertEquals(0, reordered.exitCode, json(reordered))
            val names = first.walkTopDown().filter { it.isFile }.map { it.relativeTo(first).path }.toList().sorted()
            assertTrue(names.isNotEmpty())
            assertEquals(names, second.walkTopDown().filter { it.isFile }.map { it.relativeTo(second).path }.toList().sorted())
            for (name in names) assertContentEquals(File(first, name).readBytes(), File(second, name).readBytes(), name)
        } finally { root.deleteRecursively() }
    }

    @Test fun everyBuiltInRejectsMalformedArgumentsBeforeAnyOutputOrWrite() {
        val commands = listOf("intent", "normalize", "diagnostics", "standard-verify", "flow", "catalog",
            "targets", "modules", "scenarios", "scenario", "conformance", "reference-snapshot",
            "release-profile", "standard-draft", "standard-export")
        val root = createTempDirectory("cli-argument-reject-").toFile()
        try {
            val out = File(root, "existing").apply { mkdirs() }
            val marker = File(out, "keep.txt").apply { writeText("accepted bytes") }
            val invalid = listOf(listOf("--unknown"), listOf("--out"), listOf("--out="),
                listOf("--out", out.path, "--out=other"), listOf("--out", out.path, "--invalid"))
            for (command in commands) for (args in invalid) {
                val result = assertIs<CliExecutionResult.Rejected>(executeVerificationCli((listOf(command) + args).toTypedArray()))
                assertEquals(CliDiagnosticCode.INVALID_INPUT, result.diagnostic.code, "$command $args")
                assertEquals(2, result.exitCode)
                assertTrue(result.artifacts.isEmpty())
                assertEquals(listOf("CLI DIAGNOSTIC FAILURE"), result.presentation.items
                    .filterIsInstance<CliPresentationItem.Section>().map { it.title })
                assertEquals(listOf("keep.txt"), out.listFiles()!!.map { it.name })
                assertEquals("accepted bytes", marker.readText())
            }
            val absent = File(root, "absent")
            val result = executeCli(arrayOf("intent", intent, "--out", absent.path, "extra-source"))
            assertEquals(2, result.exitCode)
            assertFalse(absent.exists())
        } finally { root.deleteRecursively() }
    }

    @Test fun explicitAndPositionalSourcesCannotCompeteInEitherHost() {
        for (args in listOf(
            arrayOf("normalize", "text", "--file", "missing"),
            arrayOf("standard-verify", "missing", "--bundle", "other"),
            arrayOf("reference-snapshot", intent, "--intent", intent)
        )) {
            val result = assertIs<CliExecutionResult.Rejected>(executeVerificationCli(args))
            assertEquals(CliDiagnosticCode.INVALID_INPUT, result.diagnostic.code)
            assertTrue(result.diagnostic.message.contains("either"), result.diagnostic.message)
        }
    }
}
