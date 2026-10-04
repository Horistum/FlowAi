package org.flowlang.conformance

import java.io.File
import kotlin.io.path.createTempDirectory
import org.flowlang.cli.Json
import org.flowlang.cli.honest.*
import org.flowlang.io.*

internal class BoundedIoConformanceChecks {
    fun checks(): List<ConformanceCheck> = listOf(
        check("architecture-recovery.io.oversize-input-rejected-before-output") {
            val root = createTempDirectory("bounded-input-conformance-").toFile()
            try {
                val source = File(root, "oversized.intent.yaml")
                java.io.RandomAccessFile(source, "rw").use { it.setLength(InputLimits.MAX_SOURCE_BYTES.toLong() + 1) }
                val destination = File(root, "output")
                val result = executeCli(arrayOf("intent", source.path, "--out", destination.path))
                require(result is CliExecutionResult.Rejected && result.diagnostic.code == CliDiagnosticCode.LIMIT_EXCEEDED)
                require(result.exitCode == 2 && result.artifacts.isEmpty() && !destination.exists())
                require(Json.bytes(result.presentation).size < 4096)
            } finally { root.deleteRecursively() }
        },
        check("architecture-recovery.io.streaming-output-byte-boundary") {
            require(Json.bytes("é", 4).contentEquals("\"é\"".toByteArray(Charsets.UTF_8)))
            require(BoundedIo.limitFailure(runCatching { Json.bytes("é", 3) }.exceptionOrNull()
                ?: error("Oversized serialization was accepted."))?.limitCode == "OUTPUT_BYTE_LIMIT")
            var afterOverflow = false
            val failure = runCatching { BoundedIo.encode(4) {
                it.write(byteArrayOf(1, 2, 3, 4)); it.write(5); afterOverflow = true
            } }.exceptionOrNull()
            require(failure is IoLimitException && !afterOverflow)
        }
    )

    private fun check(name: String, action: () -> Unit): ConformanceCheck {
        val result = runCatching(action)
        return ConformanceCheck(name, result.isSuccess, result.exceptionOrNull()?.message)
    }
}
