package org.flowlang.conformance

import java.io.File
import kotlin.io.path.createTempDirectory
import org.flowlang.artifacts.ArtifactPublicationVerifier
import org.flowlang.cli.honest.*
import org.flowlang.distribution.reference.ContractResourceResolver

/** Composed product probes: selected contract bytes become verified files, never optimistic names. */
internal class IntegratedProductIntegrityChecks {
    fun checks(): List<ConformanceCheck> = listOf(
        check("architecture-recovery.product.explicit-contracts-to-verified-publication") { root ->
            ContractResourceResolver().open().use { resources ->
                val source = File(resources.root, "examples/intent/checkout-build-image.intent.yaml")
                val out = File(root, "result with spaces")
                val result = executeCli(arrayOf("intent", "--out=${out.path}", "--target=jenkins", "--render",
                    "--contracts", resources.root.path, "--", source.path))
                require(result is CliExecutionResult.Targeted && result.exitCode == 0)
                require(result.artifacts.isNotEmpty() && result.artifacts.all { it.persisted })
                val report = ArtifactPublicationVerifier().verify(out)
                require(report.publication!!.coveredFiles.any { it.path == "Jenkinsfile" })
                val file = File(out, "Jenkinsfile")
                val bytes = file.readBytes()
                file.appendText("changed")
                require(runCatching { ArtifactPublicationVerifier().verify(out) }.isFailure)
                file.writeBytes(bytes)
                require(ArtifactPublicationVerifier().verify(out).status == "PASS")
            }
        },
        check("architecture-recovery.product.invalid-source-preserves-publication") { root ->
            val out = File(root, "published")
            require(executeCli(arrayOf("diagnostics", "--out", out.path)).exitCode == 0)
            val before = out.listFiles()!!.associate { it.name to it.readBytes().toList() }
            val input = File(root, "bad.yaml").apply { writeText("x: &a [1]\ny: *a\n") }
            val result = executeCli(arrayOf("intent", "--out", out.path, input.path))
            require(result is CliExecutionResult.Rejected && result.diagnostic.code == CliDiagnosticCode.INVALID_INPUT)
            require(result.diagnostic.message.contains("alias", ignoreCase = true) && result.artifacts.isEmpty())
            require(before == out.listFiles()!!.associate { it.name to it.readBytes().toList() })
            require(root.list()!!.none { ".staging-" in it })
            require(ArtifactPublicationVerifier().verify(out).status == "PASS")
        }
    )

    private fun check(name: String, action: (File) -> Unit): ConformanceCheck {
        val root = createTempDirectory("integrated-product-conformance-").toFile()
        val result = try { runCatching { action(root) } } finally { root.deleteRecursively() }
        return ConformanceCheck(name, result.isSuccess, result.exceptionOrNull()?.message)
    }
}
