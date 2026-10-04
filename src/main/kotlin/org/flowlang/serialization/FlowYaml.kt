package org.flowlang.serialization

import org.flowlang.io.BoundedIo
import org.flowlang.io.InputLimits

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.core.StreamReadFeature
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import java.io.File

/**
 * Single YAML parsing boundary for Flow repository documents.
 *
 * All YAML consumers share this owner so scalar handling, flow-style collections,
 * comments, quoting, duplicate detection and error reporting cannot drift between
 * module descriptors, intent documents, target registries and conformance metadata.
 *
 * Every entry point rejects duplicates, trailing documents, coercion and excessive
 * complexity. [read] retains its source signature but has the same strict behavior
 * as [readStrict]; there is no permissive mapper. Map callers own field vocabulary.
 */
object FlowYaml {
    private val strictMapper = ContractReadPolicy.configure(ObjectMapper(
        YAMLFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .streamReadConstraints(ContractReadPolicy.constraints())
            .loaderOptions(org.yaml.snakeyaml.LoaderOptions().apply {
                codePointLimit = ContractReadLimits.MAX_DOCUMENT_BYTES
                nestingDepthLimit = ContractReadLimits.MAX_DEPTH
                maxAliasesForCollections = InputLimits.MAX_YAML_ALIASES
                setAllowDuplicateKeys(false)
                setAllowRecursiveKeys(false)
            })
            .build()
    ))

    private val mapType = object : TypeReference<LinkedHashMap<String, Any?>>() {}

    fun readMap(file: File): Map<String, Any?> = guarded(file.path) { readMap(ContractReadPolicy.readText(file), file.path) }

    fun readMap(text: String, sourceName: String = "<yaml>"): Map<String, Any?> {
        return readWith(strictMapper, text, mapType, sourceName)
    }

    fun <T> read(file: File, type: Class<T>): T = readStrict(file, type)

    fun <T> read(text: String, type: Class<T>, sourceName: String = "<yaml>"): T =
        readStrict(text, type, sourceName)

    fun <T> readStrict(file: File, type: Class<T>): T =
        guarded(file.path) { readStrict(ContractReadPolicy.readText(file), type, file.path) }

    fun <T> readStrict(text: String, type: Class<T>, sourceName: String = "<yaml>"): T =
        readWith(strictMapper, text, type, sourceName)

    private fun <T> readWith(
        owner: ObjectMapper,
        text: String,
        type: Class<T>,
        sourceName: String
    ): T = guarded(sourceName) {
        validate(text, owner)
        owner.readValue(text, type)
    }

    private fun <T> readWith(
        owner: ObjectMapper,
        text: String,
        type: TypeReference<T>,
        sourceName: String
    ): T = guarded(sourceName) {
        validate(text, owner)
        owner.readValue(text, type)
    }

    private fun validate(text: String, owner: ObjectMapper) =
        ContractReadPolicy.validate(text, owner.factory) { parser ->
            require(!(parser as com.fasterxml.jackson.dataformat.yaml.YAMLParser).isCurrentAlias) {
                "CONTRACT_YAML_ALIAS: alias references are unsupported; author the value explicitly."
            }
        }

    private inline fun <T> guarded(source: String, block: () -> T): T = try {
        block()
    } catch (error: FlowYamlException) {
        throw error
    } catch (error: Exception) {
        val detail = (error as? JsonProcessingException)?.originalMessage
            ?: error.message ?: error.javaClass.simpleName
        throw FlowYamlException("Invalid YAML in '${BoundedIo.diagnostic(source)}': ${BoundedIo.diagnostic(detail)}", error)
    }
}

class FlowYamlException(message: String, cause: Throwable? = null) : IllegalArgumentException(message, cause)
