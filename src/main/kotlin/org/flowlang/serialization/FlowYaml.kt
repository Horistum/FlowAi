package org.flowlang.serialization

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import java.io.File
import java.io.IOException

/**
 * Single YAML parsing boundary for Flow repository documents.
 *
 * All YAML consumers share the same Jackson configuration so scalar handling,
 * flow-style collections, comments, quoting and error reporting cannot drift
 * between module descriptors, intent documents, target registries and
 * conformance or architecture metadata.
 */
object FlowYaml {
    private val mapper: ObjectMapper = ObjectMapper(YAMLFactory())
        .registerKotlinModule()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

    private val mapType = object : TypeReference<LinkedHashMap<String, Any?>>() {}

    fun readMap(file: File): Map<String, Any?> = readMap(file.readText(), file.path)

    fun readMap(text: String, sourceName: String = "<yaml>"): Map<String, Any?> {
        if (text.isBlank()) return emptyMap()
        return read(text, mapType, sourceName)
    }

    fun <T> read(file: File, type: Class<T>): T = read(file.readText(), type, file.path)

    fun <T> read(text: String, type: Class<T>, sourceName: String = "<yaml>"): T =
        try {
            mapper.readValue(text, type)
        } catch (error: IOException) {
            throw invalidYaml(sourceName, error)
        }

    private fun <T> read(text: String, type: TypeReference<T>, sourceName: String): T =
        try {
            mapper.readValue(text, type)
        } catch (error: IOException) {
            throw invalidYaml(sourceName, error)
        }

    private fun invalidYaml(sourceName: String, error: IOException): FlowYamlException {
        val detail = (error as? JsonProcessingException)?.originalMessage
            ?: error.message
            ?: error.javaClass.simpleName
        return FlowYamlException("Invalid YAML in '$sourceName': $detail", error)
    }
}

class FlowYamlException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
