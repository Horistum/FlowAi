package org.flowlang.serialization

import com.fasterxml.jackson.core.JsonFactory
import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.core.StreamReadFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.io.File

/** Strict single-document JSON contract boundary. Trees retain keys for schema-owned validation. */
object FlowJson {
    internal fun newMapper(): ObjectMapper = ContractReadPolicy.configure(ObjectMapper(
        JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .streamReadConstraints(ContractReadPolicy.constraints())
            .build()
    ))

    private val mapper = newMapper()

    fun <T> read(file: File, type: Class<T>): T = guarded(file.path) {
        read(ContractReadPolicy.readText(file), type, file.path)
    }

    fun <T> read(text: String, type: Class<T>, sourceName: String = "<json>"): T = guarded(sourceName) {
        ContractReadPolicy.validate(text, mapper.factory)
        mapper.readValue(text, type)
    }

    fun readTree(file: File): JsonNode = guarded(file.path) {
        readTree(ContractReadPolicy.readText(file), file.path)
    }

    fun readTree(text: String, sourceName: String = "<json>"): JsonNode = guarded(sourceName) {
        ContractReadPolicy.validate(text, mapper.factory)
        mapper.readTree(text)
    }

    private inline fun <T> guarded(source: String, block: () -> T): T = try {
        block()
    } catch (error: FlowJsonException) {
        throw error
    } catch (error: Exception) {
        val detail = (error as? JsonProcessingException)?.originalMessage ?: error.message ?: error.javaClass.simpleName
        throw FlowJsonException("Invalid JSON in '$source': $detail", error)
    }
}

class FlowJsonException(message: String, cause: Throwable? = null) : IllegalArgumentException(message, cause)
