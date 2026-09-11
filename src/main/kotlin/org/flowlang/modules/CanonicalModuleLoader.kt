package org.flowlang.modules

import java.io.File
import org.flowlang.serialization.FlowYaml
import org.flowlang.serialization.FlowYamlException

/** Strict authority for Flow module semantic and safety contracts. */
object CanonicalModuleLoader {
    class ContractException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

    fun loadDirectory(dir: File): List<FlowModule> {
        if (!dir.isDirectory) throw ContractException("Module directory does not exist: ${dir.path}")
        val files = dir.listFiles { file -> file.isFile && file.extension in setOf("yaml", "yml") }
            ?.sortedBy { it.name }
            .orEmpty()
        if (files.isEmpty()) throw ContractException("Module directory is empty: ${dir.path}")
        val modules = files.map { file -> loadText(file.readText(), file.path) }
        rejectDuplicateModuleIds(modules)
        return modules
    }

    fun loadTexts(texts: List<String>): List<FlowModule> {
        if (texts.isEmpty()) throw ContractException("At least one module descriptor is required.")
        val modules = texts.mapIndexed { index, text -> loadText(text, "<module-${index + 1}>") }
        rejectDuplicateModuleIds(modules)
        return modules
    }

    fun loadText(yaml: String, source: String = "<module>"): FlowModule {
        val root = try {
            FlowYaml.readMap(yaml, source)
        } catch (error: FlowYamlException) {
            throw ContractException(error.message ?: "Invalid module descriptor: $source", error)
        }
        rejectUnknownFields(root, MODULE_KEYS, source)
        if (text(root, "kind", source) != "FlowModule") {
            throw ContractException("$source.kind must be FlowModule.")
        }
        val name = text(root, "name", source)
        if (!MODULE_ID.matches(name)) throw ContractException("$source.name is invalid: $name")
        text(root, "version", source)
        text(root, "description", source)

        val systems = map(root["systemTypes"], "$source.systemTypes", required = true)
        val actions = map(root["actions"], "$source.actions", required = true)
        if (systems.isEmpty()) throw ContractException("$source.systemTypes must not be empty.")
        if (actions.isEmpty()) throw ContractException("$source.actions must not be empty.")

        systems.forEach { (id, body) ->
            validateMemberId(id, "$source.systemTypes")
            val path = "$source.systemTypes.$id"
            val system = map(body, path, required = true)
            rejectUnknownFields(system, SYSTEM_TYPE_KEYS, path)
            validateSchema(system["input"], "$path.input", required = true)
        }

        actions.forEach { (id, body) ->
            validateMemberId(id, "$source.actions")
            val path = "$source.actions.$id"
            val action = map(body, path, required = true)
            if (action.containsKey("targetImplications")) {
                throw ContractException("$path cannot declare targetImplications; adapter evidence belongs to target registries.")
            }
            rejectUnknownFields(action, ACTION_KEYS, path)
            if (text(action, "kind", path) != "action") {
                throw ContractException("$path.kind must be action.")
            }
            val targetTypes = list(action["targetTypes"], "$path.targetTypes", required = true)
            val unknownTargets = targetTypes.toSet() - systems.keys
            if (unknownTargets.isNotEmpty()) {
                throw ContractException("$path targets unknown system types: ${unknownTargets.sorted().joinToString()}")
            }
            validateSchema(action["input"], "$path.input", required = true)
            validateSchema(action["output"], "$path.output", required = true)
            validateEffects(action["effects"], "$path.effects")
            validateContinuity(action["continuity"], "$path.continuity")
            validateSafety(action["safety"], "$path.safety")
            validateSupportBlock(action["retry"], "$path.retry")
            validateSupportBlock(action["timeout"], "$path.timeout")
            bool(action["additionalParams"], "$path.additionalParams")
            list(action["secrets"], "$path.secrets")
            list(action["requiredCapabilities"], "$path.requiredCapabilities")
            val implemented = list(action["implements"], "$path.implements")
            val unknownImplemented = implemented.toSet() - STANDARD_CAPABILITIES
            if (unknownImplemented.isNotEmpty()) {
                throw ContractException("$path implements unknown standard capabilities: ${unknownImplemented.sorted().joinToString()}.")
            }
            validateIdempotent(action["idempotent"], "$path.idempotent")
            validateErrors(action["errors"], "$path.errors")
        }

        return ModuleYamlLoader.decodeText(yaml, source)
    }

    private fun rejectDuplicateModuleIds(modules: List<FlowModule>) {
        val duplicates = modules.groupBy { it.name }.filterValues { it.size > 1 }.keys.sorted()
        if (duplicates.isNotEmpty()) throw ContractException("Duplicate modules: ${duplicates.joinToString()}")
    }

    private fun validateSchema(value: Any?, path: String, required: Boolean) {
        map(value, path, required).forEach { (fieldName, rawField) ->
            if (fieldName.isBlank()) throw ContractException("$path contains a blank field name.")
            val fieldPath = "$path.$fieldName"
            val field = map(rawField, fieldPath, required = true)
            rejectUnknownFields(field, SCHEMA_FIELD_KEYS, fieldPath)
            val wireType = text(field, "type", fieldPath)
            val schemaType = SchemaType.fromWireName(wireType)
                ?: throw ContractException(
                    "$fieldPath.type must be one of: ${SchemaType.supportedWireNames.joinToString()}."
                )
            bool(field["required"], "$fieldPath.required")
            bool(field["sensitive"], "$fieldPath.sensitive")
            if (field.containsKey("default")) {
                val defaultValue = field["default"]
                    ?: throw ContractException("$fieldPath.default must not be null; omit the field when no default is intended.")
                SchemaTypeCompatibility.defaultValidationError(schemaType, defaultValue)?.let { problem ->
                    throw ContractException("$fieldPath.default $problem.")
                }
            }
        }
    }

    private fun validateEffects(value: Any?, path: String) {
        val effects = map(value, path, required = true)
        rejectUnknownFields(effects, EFFECT_KEYS, path)
        EFFECT_KEYS.forEach { key -> list(effects[key], "$path.$key") }
    }

    private fun validateContinuity(value: Any?, path: String) {
        if (value == null) return
        val continuity = map(value, path, required = true)
        rejectUnknownFields(continuity, CONTINUITY_KEYS, path)
        CONTINUITY_KEYS.forEach { key ->
            val channels = objectList(continuity[key], "$path.$key")
            val identities = channels.mapIndexed { index, channel ->
                val channelPath = "$path.$key[$index]"
                rejectUnknownFields(channel, CONTINUITY_CHANNEL_KEYS, channelPath)
                val kind = text(channel, "kind", channelPath).lowercase()
                if (kind !in CONTINUITY_KINDS) {
                    throw ContractException("$channelPath.kind must be one of: ${CONTINUITY_KINDS.sorted().joinToString()}.")
                }
                val name = text(channel, "name", channelPath)
                if (!CONTINUITY_CHANNEL.matches(name)) {
                    throw ContractException("$channelPath.name is invalid: $name")
                }
                if (channel.containsKey("lifetime")) {
                    if (kind != "state") {
                        throw ContractException("$channelPath.lifetime is valid only for state continuity channels.")
                    }
                    val lifetime = text(channel, "lifetime", channelPath).lowercase()
                    if (lifetime !in CONTINUITY_STATE_LIFETIMES) {
                        throw ContractException(
                            "$channelPath.lifetime must be one of: ${CONTINUITY_STATE_LIFETIMES.sorted().joinToString()}."
                        )
                    }
                }
                kind to name
            }
            if (identities.distinct().size != identities.size) {
                throw ContractException("$path.$key must not contain duplicate continuity channels.")
            }
        }
    }

    private fun validateSafety(value: Any?, path: String) {
        val safety = map(value, path, required = true)
        rejectUnknownFields(safety, SAFETY_KEYS, path)
        val destructive = bool(safety["destructive"], "$path.destructive", required = true)
        val requirements = list(safety["requires"], "$path.requires")
        val unknown = requirements.toSet() - SAFETY_REQUIREMENTS
        if (unknown.isNotEmpty()) {
            throw ContractException("$path has unknown requirements: ${unknown.sorted().joinToString()}")
        }
        if (destructive && "safety" !in requirements) {
            throw ContractException("$path destructive actions must require safety.")
        }
    }

    private fun validateSupportBlock(value: Any?, path: String) {
        if (value == null) return
        val block = map(value, path, required = true)
        rejectUnknownFields(block, SUPPORT_KEYS, path)
        bool(block["supported"], "$path.supported", required = true)
    }

    private fun validateErrors(value: Any?, path: String) {
        map(value, path).forEach { (name, rawError) ->
            if (name.isBlank()) throw ContractException("$path contains a blank error name.")
            val errorPath = "$path.$name"
            val error = map(rawError, errorPath, required = true)
            rejectUnknownFields(error, ERROR_KEYS, errorPath)
            text(error, "when", errorPath)
            text(error, "message", errorPath)
        }
    }

    private fun validateIdempotent(value: Any?, path: String) {
        when (value) {
            null, is Boolean -> Unit
            is String -> if (value.isBlank()) throw ContractException("$path must not be blank.")
            else -> throw ContractException("$path must be boolean or text.")
        }
    }

    private fun validateMemberId(id: String, path: String) {
        if (!MEMBER_ID.matches(id)) throw ContractException("$path contains invalid id: $id")
    }

    private fun rejectUnknownFields(map: Map<String, Any?>, allowed: Set<String>, path: String) {
        val unknown = map.keys - allowed
        if (unknown.isNotEmpty()) {
            throw ContractException("$path has unknown fields: ${unknown.sorted().joinToString()}")
        }
    }

    private fun text(map: Map<String, Any?>, key: String, path: String): String =
        (map[key] as? String)?.takeIf { it.isNotBlank() }
            ?: throw ContractException("$path.$key must be non-blank text.")

    @Suppress("UNCHECKED_CAST")
    private fun map(value: Any?, path: String, required: Boolean = false): Map<String, Any?> = when (value) {
        null -> if (required) throw ContractException("$path is required.") else emptyMap()
        is Map<*, *> -> value as Map<String, Any?>
        else -> throw ContractException("$path must be a map.")
    }

    @Suppress("UNCHECKED_CAST")
    private fun objectList(value: Any?, path: String): List<Map<String, Any?>> = when (value) {
        null -> emptyList()
        is List<*> -> value.mapIndexed { index, item ->
            item as? Map<String, Any?> ?: throw ContractException("$path[$index] must be a map.")
        }
        else -> throw ContractException("$path must be a list.")
    }

    private fun list(value: Any?, path: String, required: Boolean = false): List<String> = when (value) {
        null -> if (required) throw ContractException("$path is required.") else emptyList()
        is List<*> -> value.mapIndexed { index, item ->
            item as? String ?: throw ContractException("$path[$index] must be text.")
        }.also { items ->
            if (required && items.isEmpty()) throw ContractException("$path must not be empty.")
            if (items.any(String::isBlank)) throw ContractException("$path must not contain blank text.")
            if (items.distinct().size != items.size) throw ContractException("$path must not contain duplicates.")
        }
        else -> throw ContractException("$path must be a list.")
    }

    private fun bool(value: Any?, path: String, required: Boolean = false): Boolean = when (value) {
        null -> if (required) throw ContractException("$path is required.") else false
        is Boolean -> value
        else -> throw ContractException("$path must be boolean.")
    }

    private val MODULE_ID = Regex("[a-z][a-z0-9-]*")
    private val MEMBER_ID = Regex("[a-z][A-Za-z0-9-]*")
    private val MODULE_KEYS = setOf("kind", "name", "version", "description", "systemTypes", "actions")
    private val SYSTEM_TYPE_KEYS = setOf("input")
    private val ACTION_KEYS = setOf(
        "kind", "targetTypes", "input", "output", "effects", "continuity", "safety", "idempotent",
        "retry", "timeout", "additionalParams", "errors", "secrets", "requiredCapabilities", "implements"
    )
    private val STANDARD_CAPABILITIES = org.flowlang.intent.StandardCapability.values().map { it.name }.toSet()
    private val SCHEMA_FIELD_KEYS = setOf("type", "required", "sensitive", "default")
    private val EFFECT_KEYS = setOf("reads", "writes", "creates", "updates", "deletes", "executes", "network", "filesystem")
    private val CONTINUITY_KEYS = setOf("provides", "requires", "preserves")
    private val CONTINUITY_CHANNEL_KEYS = setOf("kind", "name", "lifetime")
    private val CONTINUITY_KINDS = setOf("value", "workspace", "state")
    private val CONTINUITY_STATE_LIFETIMES = setOf("workflow", "durable")
    private val CONTINUITY_CHANNEL = Regex("[a-z][A-Za-z0-9._-]*")
    private val SAFETY_KEYS = setOf("destructive", "requires")
    private val SAFETY_REQUIREMENTS = setOf("safety", "approval")
    private val SUPPORT_KEYS = setOf("supported")
    private val ERROR_KEYS = setOf("when", "message")
}