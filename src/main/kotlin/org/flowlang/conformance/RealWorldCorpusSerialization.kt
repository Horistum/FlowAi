package org.flowlang.conformance

import com.fasterxml.jackson.core.JsonFactory
import com.fasterxml.jackson.core.StreamReadFeature
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import java.io.File
import java.io.IOException
import org.flowlang.serialization.FlowYaml
import org.flowlang.serialization.FlowYamlException

/** Strict serialization boundary for executable real-world corpus evidence. */
object RealWorldCorpusSerialization {
    private val jsonMapper = ObjectMapper(
        JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .build()
    )
        .registerKotlinModule()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true)
        .configure(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES, true)
        .configure(DeserializationFeature.FAIL_ON_NUMBERS_FOR_ENUMS, true)

    fun <T> readYaml(file: File, type: Class<T>): T = try {
        FlowYaml.readStrict(file, type)
    } catch (error: FlowYamlException) {
        throw invalidDocument(file, error)
    }

    fun <T> readJson(file: File, type: Class<T>): T = try {
        jsonMapper.readValue(file, type)
    } catch (error: IOException) {
        throw invalidDocument(file, error)
    }

    private fun invalidDocument(file: File, error: Throwable): IllegalArgumentException =
        IllegalArgumentException(
            "Invalid real-world corpus document '${file.path}': ${error.message ?: error.javaClass.simpleName}",
            error
        )
}
