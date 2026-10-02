package org.flowlang.conformance

import java.io.File
import kotlin.io.path.createTempDirectory
import org.flowlang.cli.honest.*

/** Public product behavior, including negative cases that falsify the original argv scanning. */
internal class CliArgumentIntegrityChecks(private val rootDir: File) {
    fun checks(): List<ConformanceCheck> = listOf(
        check("architecture-recovery.cli.option-values-never-become-source") {
            val root = createTempDirectory("cli-source-conformance-").toFile()
            try {
                val output = File(root, "result")
                val source = File(rootDir, "examples/intent/build-test-deploy.intent.yaml").absolutePath
                val result = executeCli(arrayOf("intent", "--out", output.path, source))
                require(result is CliExecutionResult.TargetNeutral) { "Authored source was not selected: ${result.diagnostics}" }
                require(File(output, "normalized-intent.json").isFile)
            } finally { root.deleteRecursively() }
        },
        check("architecture-recovery.cli.invalid-arguments-have-no-side-effects") {
            val root = createTempDirectory("cli-reject-conformance-").toFile()
            try {
                val output = File(root, "result")
                for (tail in listOf(listOf("--unknown"), listOf("--out=second"), listOf("extra"))) {
                    val result = executeCli((listOf("diagnostics", "--out", output.path) + tail).toTypedArray())
                    require(result is CliExecutionResult.Rejected && result.diagnostic.code == CliDiagnosticCode.INVALID_INPUT)
                    require(result.artifacts.isEmpty() && !output.exists())
                    require(result.presentation.items.filterIsInstance<CliPresentationItem.Section>()
                        .map { it.title } == listOf("CLI DIAGNOSTIC FAILURE"))
                }
            } finally { root.deleteRecursively() }
        }
    )

    private fun check(name: String, action: () -> Unit): ConformanceCheck {
        val result = runCatching(action)
        return ConformanceCheck(name, result.isSuccess, result.exceptionOrNull()?.message)
    }
}
