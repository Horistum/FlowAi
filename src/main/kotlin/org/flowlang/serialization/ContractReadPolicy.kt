package org.flowlang.serialization

import org.flowlang.io.BoundedIo
import org.flowlang.io.InputLimits

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

/** Compatibility names for parser budgets owned by the central product policy. */
object ContractReadLimits {
    const val MAX_DOCUMENT_BYTES = InputLimits.MAX_SOURCE_BYTES
    const val MAX_DEPTH = InputLimits.MAX_DEPTH
    const val MAX_TOKENS = InputLimits.MAX_TOKENS
    const val MAX_STRING_LENGTH = InputLimits.MAX_STRING_LENGTH
    const val MAX_NAME_LENGTH = InputLimits.MAX_NAME_LENGTH
    const val MAX_NUMBER_LENGTH = InputLimits.MAX_NUMBER_LENGTH
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
        return try { BoundedIo.readBytes(file, ContractReadLimits.MAX_DOCUMENT_BYTES) }
        catch (error: org.flowlang.io.IoLimitException) {
            throw IllegalArgumentException("CONTRACT_DOCUMENT_LIMIT: document exceeds the byte budget.", error)
        }
    }

    fun readText(file: File): String = BoundedIo.decodeUtf8(readBytes(file))

    fun requireSize(text: String, sourceName: String? = null) {
        try { BoundedIo.textSize(text, ContractReadLimits.MAX_DOCUMENT_BYTES, "CONTRACT_DOCUMENT_LIMIT") }
        catch (failure: org.flowlang.io.IoLimitException) {
            throw IllegalArgumentException("CONTRACT_DOCUMENT_LIMIT: ${BoundedIo.diagnostic(sourceName ?: "document")} exceeds the byte budget.", failure)
        }
    }

    fun validate(text: String, factory: JsonFactory, inspect: (JsonParser) -> Unit = {}) {
        requireSize(text)
        factory.createParser(text).use { parser ->
            var depth = 0
            var tokens = 0
            var roots = 0
            val collectionEntries = java.util.ArrayDeque<Int>()
            while (true) {
                val token = parser.nextToken() ?: break
                inspect(parser)
                require(++tokens <= ContractReadLimits.MAX_TOKENS) { "CONTRACT_TOKEN_LIMIT: document exceeds the token budget." }
                if (depth == 0) {
                    require(++roots == 1) { "CONTRACT_TRAILING_CONTENT: exactly one document is required." }
                    require(token != JsonToken.VALUE_NULL) { "CONTRACT_NULL_ROOT: document must not be null." }
                }
                if (collectionEntries.isNotEmpty() &&
                    (token == JsonToken.FIELD_NAME || (parser.parsingContext.inArray() && token.isScalarValue) ||
                        ((token == JsonToken.START_ARRAY || token == JsonToken.START_OBJECT) && parser.parsingContext.parent?.inArray() == true))) {
                    val count = collectionEntries.removeLast() + 1
                    BoundedIo.requireWithin(count.toLong(), InputLimits.MAX_COLLECTION_ENTRIES, "CONTRACT_COLLECTION_LIMIT")
                    collectionEntries.addLast(count)
                }
                when (token) {
                    JsonToken.START_OBJECT, JsonToken.START_ARRAY -> {
                        collectionEntries.addLast(0)
                        require(++depth <= ContractReadLimits.MAX_DEPTH) { "CONTRACT_DEPTH_LIMIT: document is nested too deeply." }
                    }
                    JsonToken.END_OBJECT, JsonToken.END_ARRAY -> { depth--; collectionEntries.removeLast() }
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
