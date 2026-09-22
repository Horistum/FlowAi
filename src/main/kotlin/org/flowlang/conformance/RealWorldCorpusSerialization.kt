package org.flowlang.conformance

import java.io.File
import org.flowlang.serialization.FlowJson
import org.flowlang.serialization.FlowJsonException
import org.flowlang.serialization.FlowYaml
import org.flowlang.serialization.FlowYamlException

/** Strict serialization boundary for executable real-world corpus evidence. */
object RealWorldCorpusSerialization {
    fun <T> readYaml(file: File, type: Class<T>): T = try {
        FlowYaml.readStrict(file, type)
    } catch (error: FlowYamlException) {
        throw invalidDocument(file, error)
    }

    fun <T> readJson(file: File, type: Class<T>): T = try {
        FlowJson.read(file, type)
    } catch (error: FlowJsonException) {
        throw invalidDocument(file, error)
    }

    private fun invalidDocument(file: File, error: Throwable): IllegalArgumentException =
        IllegalArgumentException(
            "Invalid real-world corpus document '${file.path}': ${error.message ?: error.javaClass.simpleName}",
            error
        )
}
