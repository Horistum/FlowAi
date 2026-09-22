package org.flowlang.serialization

import com.fasterxml.jackson.core.JsonFactory
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.core.JsonToken
import com.fasterxml.jackson.core.StreamReadConstraints
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.MapperFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.cfg.CoercionAction
import com.fasterxml.jackson.databind.cfg.CoercionInputShape
import com.fasterxml.jackson.databind.type.LogicalType
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** Parser budgets, not the general source/output I/O policy owned by AR-05. */
object ContractReadLimits {
    const val MAX_DOCUMENT_BYTES = 8 * 1024 * 1024
    const val MAX_DEPTH = 64
    const val MAX_TOKENS = 200_000
    const val MAX_STRING_LENGTH = 1_048_576
    const val MAX_NAME_LENGTH = 1024
    const val MAX_NUMBER_LENGTH = 128
}

/** Shared mechanics only; each contract still owns its vocabulary and semantic validation. */
internal object ContractReadPolicy {
    fun constraints(): StreamReadConstraints = StreamReadConstraints.builder()
        .maxDocumentLength(ContractReadLimits.MAX_DOCUMENT_BYTES.toLong())
        .maxNestingDepth(ContractReadLimits.MAX_DEPTH)
        .maxStringLength(ContractReadLimits.MAX_STRING_LENGTH)
        .maxNameLength(ContractReadLimits.MAX_NAME_LENGTH)
        .maxNumberLength(ContractReadLimits.MAX_NUMBER_LENGTH)
        .build()

    fun configure(mapper: ObjectMapper): ObjectMapper = mapper
        .registerKotlinModule()
        .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
        .enable(DeserializationFeature.FAIL_ON_NUMBERS_FOR_ENUMS)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
        .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
        .apply {
            coercionConfigFor(LogicalType.Textual)
                .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail)
        }

    fun readBytes(file: File): ByteArray {
        // readNBytes bounds allocation even when a file grows after it is opened.
        val bytes = file.inputStream().use { it.readNBytes(ContractReadLimits.MAX_DOCUMENT_BYTES + 1) }
        require(bytes.size <= ContractReadLimits.MAX_DOCUMENT_BYTES) {
            "CONTRACT_DOCUMENT_LIMIT: '${file.path}' exceeds the byte budget."
        }
        return bytes
    }

    fun readText(file: File): String = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(readBytes(file))).toString()
    } catch (error: java.nio.charset.CharacterCodingException) {
        throw IllegalArgumentException("CONTRACT_UTF8: '${file.path}' must contain valid UTF-8.", error)
    }

    fun requireSize(text: String, sourceName: String? = null) {
        require(text.length <= ContractReadLimits.MAX_DOCUMENT_BYTES &&
            text.toByteArray(Charsets.UTF_8).size <= ContractReadLimits.MAX_DOCUMENT_BYTES) {
            "CONTRACT_DOCUMENT_LIMIT: ${sourceName?.let { "'$it'" } ?: "document"} exceeds the byte budget."
        }
    }

    fun validate(text: String, factory: JsonFactory, inspect: (JsonParser) -> Unit = {}) {
        requireSize(text)
        factory.createParser(text).use { parser ->
            var depth = 0
            var tokens = 0
            var roots = 0
            while (true) {
                val token = parser.nextToken() ?: break
                inspect(parser)
                require(++tokens <= ContractReadLimits.MAX_TOKENS) { "CONTRACT_TOKEN_LIMIT: document exceeds the token budget." }
                if (depth == 0) {
                    require(++roots == 1) { "CONTRACT_TRAILING_CONTENT: exactly one document is required." }
                    require(token != JsonToken.VALUE_NULL) { "CONTRACT_NULL_ROOT: document must not be null." }
                }
                when (token) {
                    JsonToken.START_OBJECT, JsonToken.START_ARRAY -> {
                        require(++depth <= ContractReadLimits.MAX_DEPTH) { "CONTRACT_DEPTH_LIMIT: document is nested too deeply." }
                    }
                    JsonToken.END_OBJECT, JsonToken.END_ARRAY -> depth--
                    JsonToken.FIELD_NAME -> require(parser.textLength <= ContractReadLimits.MAX_NAME_LENGTH) {
                        "CONTRACT_NAME_LIMIT: field name exceeds the length budget."
                    }
                    JsonToken.VALUE_STRING -> require(parser.textLength <= ContractReadLimits.MAX_STRING_LENGTH) {
                        "CONTRACT_STRING_LIMIT: scalar exceeds the length budget."
                    }
                    JsonToken.VALUE_NUMBER_INT, JsonToken.VALUE_NUMBER_FLOAT ->
                        require(parser.textLength <= ContractReadLimits.MAX_NUMBER_LENGTH) {
                            "CONTRACT_NUMBER_LIMIT: number exceeds the length budget."
                        }
                    else -> Unit
                }
            }
            require(roots == 1) { "CONTRACT_EMPTY_DOCUMENT: exactly one document is required." }
        }
    }
}
