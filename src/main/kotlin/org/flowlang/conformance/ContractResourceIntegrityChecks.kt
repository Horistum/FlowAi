package org.flowlang.conformance

import java.io.File
import java.security.MessageDigest
import org.flowlang.cli.honest.*
import org.flowlang.distribution.reference.ContractResourceOrigin
import org.flowlang.distribution.reference.ContractResourceResolver

internal class ContractResourceIntegrityChecks {
    fun checks(): List<ConformanceCheck> = listOf(
        check("architecture-recovery.resources.packaged-bytes-and-provenance") {
            ContractResourceResolver().open().use { resources ->
                require(resources.provenance.isNotEmpty())
                for (record in resources.provenance) {
                    val bytes = File(resources.root, record.path).readBytes()
                    val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
                        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
                    require(record.sha256 == digest && record.sizeBytes == bytes.size.toLong())
                    require(record.origin == ContractResourceOrigin.CLASSPATH)
                }
                val result = executeCli(arrayOf("intent"))
                require(result is CliExecutionResult.TargetNeutral) { result.diagnostics.toString() }
            }
        },
        check("architecture-recovery.resources.incomplete-override-never-falls-back") {
            ContractResourceResolver().open().use { external ->
                require(File(external.root, "modules/standard.yaml").delete())
                val output = File(external.root, "forbidden-output")
                val result = executeCli(arrayOf("intent", "--contracts", external.root.path, "--out", output.path))
                require(result is CliExecutionResult.Rejected && result.diagnostic.code == CliDiagnosticCode.INVALID_INPUT)
                require(result.artifacts.isEmpty() && !output.exists())
            }
        }
    )

    private fun check(name: String, action: () -> Unit): ConformanceCheck {
        val result = runCatching(action)
        return ConformanceCheck(name, result.isSuccess, result.exceptionOrNull()?.message)
    }
}
