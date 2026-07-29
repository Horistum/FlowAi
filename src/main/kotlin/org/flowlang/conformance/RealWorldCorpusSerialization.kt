package org.flowlang.conformance

import com.fasterxml.jackson.core.JsonFactory
import com.fasterxml.jackson.core.StreamReadFeature
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import java.io.File
import java.io.IOException

/** Strict serialization boundary for executable real-world corpus evidence. */
object RealWorldCorpusSerialization {
    private val yamlMapper = ObjectMapper(
        YAMLFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .build()
    ).strict()

    private val jsonMapper = ObjectMapper(
        JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .build()
    ).strict()

    fun <T> readYaml(file: File, type: Class<T>): T = read(file, type, yamlMapper)

    fun <T> readJson(file: File, type: Class<T>): T = read(file, type, jsonMapper)

    private fun ObjectMapper.strict(): ObjectMapper = registerKotlinModule()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true)
        .configure(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES, true)
        .configure(DeserializationFeature.FAIL_ON_NUMBERS_FOR_ENUMS, true)

    private fun <T> read(file: File, type: Class<T>, mapper: ObjectMapper): T = try {
        mapper.readValue(file, type)
    } catch (error: IOException) {
        throw IllegalArgumentException(
            "Invalid real-world corpus document '${file.path}': ${error.message ?: error.javaClass.simpleName}",
            error
        )
    }
}
