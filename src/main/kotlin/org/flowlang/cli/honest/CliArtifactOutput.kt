package org.flowlang.cli.honest

import java.io.File
import org.flowlang.cli.Json
import org.flowlang.io.BoundedIo
import org.flowlang.io.InputLimits
import org.flowlang.io.IoBudget

/** Preflight the complete encoded batch before touching its destination. Atomic publication is AR-05D. */
internal object CliArtifactOutput {
    fun write(directory: File, values: Map<String, Any>) {
        BoundedIo.requireWithin(values.size.toLong(), InputLimits.MAX_FILES, "OUTPUT_COUNT_LIMIT")
        val budget = IoBudget(InputLimits.MAX_TOTAL_OUTPUT_BYTES, code = "OUTPUT")
        val encoded = values.map { (name, value) ->
            require(name.isNotBlank() && name != "." && name != ".." && '/' !in name && '\\' !in name) {
                "Artifact names must be plain file names."
            }
            val maximum = minOf(InputLimits.MAX_ARTIFACT_BYTES, budget.remainingBytes())
            val bytes = BoundedIo.encode(maximum) { output ->
                val content = if (value is String && !name.endsWith(".json"))
                    BoundedIo.encodeText(value, maximum, "OUTPUT_BYTE_LIMIT") else Json.bytes(value, maximum)
                output.write(content)
                if (content.lastOrNull() != 10.toByte()) output.write(10)
            }
            budget.add(bytes.size)
            name to bytes
        }
        require(directory.mkdirs() || directory.isDirectory) { "Cannot create output directory: ${directory.path}" }
        encoded.forEach { (name, bytes) -> File(directory, name).writeBytes(bytes) }
    }
}
