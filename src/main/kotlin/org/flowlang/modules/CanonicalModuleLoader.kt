package org.flowlang.modules

import java.io.File
import org.flowlang.serialization.FlowYaml
import org.flowlang.serialization.FlowYamlException

/** Strict authority in front of the low-level module YAML decoder. */
object CanonicalModuleLoader {
    class ContractException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

    fun loadDirectory(dir: File): List<FlowModule> {
        if (!dir.isDirectory) throw ContractException("Module directory does not exist: ${dir.path}")
        val files = dir.listFiles { file -> file.isFile && file.extension in setOf("yaml", "yml") }
            ?.sortedBy { it.name }
            .orEmpty()
        if (files.isEmpty()) throw ContractException("Module directory is empty: ${dir.path}")
        val modules = files.map { file -> loadText(file.readText(), file.path) }
        val duplicates = modules.groupBy { it.name }.filterValues { it.size > 1 }.keys.sorted()
        if (duplicates.isNotEmpty()) throw ContractException("Duplicate modules: ${duplicates.joinToString()}")
        return modules
    }

    fun loadTexts(texts: List<String>): List<FlowModule> {
        if (texts.isEmpty()) throw ContractException("At least one module descriptor is required.")
        val modules = texts.mapIndexed { index, text -> loadText(text, "<module-${index + 1}>") }
        val duplicates = modules.groupBy { it.name }.filterValues { it.size > 1 }.keys.sorted()
        if (duplicates.isNotEmpty()) throw ContractException("Duplicate modules: ${duplicates.joinToString()}")
        return modules
    }

    fun loadText(yaml: String, source: String = "<module>"): FlowModule {
        val root = try {
            FlowYaml.readMap(yaml, source)
        } catch (error: FlowYamlException) {
            throw ContractException(error.message ?: "Invalid module descriptor: $source", error)
        }
        if (text(root, "kind", source) != "FlowModule") throw ContractException("$source.kind must be FlowModule.")
        val name = text(root, "name", source)
        if (!MODULE_ID.matches(name)) throw ContractException("$source.name is invalid: $name")
        text(root, "version", source)
        text(root, "description", source)
        val systems = map(root["systemTypes"], "$source.systemTypes", required = true)
        val actions = map(root["actions"], "$source.actions", required = true)
        if (systems.isEmpty()) throw ContractException("$source.systemTypes must not be empty.")
        if (actions.isEmpty()) throw ContractException("$source.actions must not be empty.")
        systems.forEach { (id, body) ->
            val system = map(body, "$source.systemTypes.$id", required = true)
            validateSchema(system["input"], "$source.systemTypes.$id.input")
        }

        val approvalByAction = mutableMapOf<String, Boolean>()
        actions.forEach { (id, body) ->
            val path = "$source.actions.$id"
            val action = map(body, path, required = true)
            if (text(action, "kind", path) != "action") {
                throw ContractException("$path.kind must be action.")
            }
            list(action["targetTypes"], "$path.targetTypes", required = true)
            validateSchema(action["input"], "$path.input")
            validateSchema(action["output"], "$path.output")
            validateEffects(action["effects"], "$path.effects")
            if (action.containsKey("targetImplications")) {
                throw ContractException("$path cannot declare targetImplications.")
            }
            val safety = map(action["safety"], "$path.safety")
            bool(safety["destructive"], "$path.safety.destructive")
            val requirements = list(safety["requires"], "$path.safety.requires")
            val unknown = requirements.toSet() - setOf("safety", "approval")
            if (unknown.isNotEmpty()) throw ContractException("$path has unknown safety requirements: ${unknown.joinToString()}")
            approvalByAction[id] = "approval" in requirements
            map(action["retry"], "$path.retry").let { bool(it["supported"], "$path.retry.supported") }
            map(action["timeout"], "$path.timeout").let { bool(it["supported"], "$path.timeout.supported") }
            bool(action["additionalParams"], "$path.additionalParams")
            list(action["secrets"], "$path.secrets")
            list(action["requiredCapabilities"], "$path.requiredCapabilities")
            validateIdempotent(action["idempotent"], "$path.idempotent")
            validateErrors(action["errors"], "$path.errors")
        }

        val decoded = ModuleYamlLoader.loadText(yaml)
        return decoded.copy(actions = decoded.actions.mapValues { (id, action) ->
            action.copy(safety = action.safety.copy(requiresApproval = approvalByAction[id] == true))
        })
    }

    private fun validateSchema(value: Any?, path: String) {
        map(value, path).forEach { (fieldName, rawField) ->
            val fieldPath = "$path.$fieldName"
            val field = map(rawField, fieldPath, required = true)
            text(field, "type", fieldPath)
            bool(field["required"], "$fieldPath.required")
            bool(field["sensitive"], "$fieldPath.sensitive")
        }
    }

    private fun validateEffects(value: Any?, path: String) {
        val effects = map(value, path)
        EFFECT_KEYS.forEach { key -> list(effects[key], "$path.$key") }
        val unknown = effects.keys - EFFECT_KEYS
        if (unknown.isNotEmpty()) throw ContractException("$path has unknown fields: ${unknown.sorted().joinToString()}")
    }

    private fun validateErrors(value: Any?, path: String) {
        map(value, path).forEach { (name, rawError) ->
            val errorPath = "$path.$name"
            val error = map(rawError, errorPath, required = true)
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

    private fun text(map: Map<String, Any?>, key: String, path: String): String =
        (map[key] as? String)?.takeIf { it.isNotBlank() }
            ?: throw ContractException("$path.$key must be non-blank text.")

    @Suppress("UNCHECKED_CAST")
    private fun map(value: Any?, path: String, required: Boolean = false): Map<String, Any?> = when (value) {
        null -> if (required) throw ContractException("$path is required.") else emptyMap()
        is Map<*, *> -> value as Map<String, Any?>
        else -> throw ContractException("$path must be a map.")
    }

    private fun list(value: Any?, path: String, required: Boolean = false): List<String> = when (value) {
        null -> if (required) throw ContractException("$path is required.") else emptyList()
        is List<*> -> value.mapIndexed { index, item -> item as? String ?: throw ContractException("$path[$index] must be text.") }
            .also {
                if (required && it.isEmpty()) throw ContractException("$path must not be empty.")
                if (it.any(String::isBlank)) throw ContractException("$path must not contain blank text.")
            }
        else -> throw ContractException("$path must be a list.")
    }

    private fun bool(value: Any?, path: String): Boolean = when (value) {
        null -> false
        is Boolean -> value
        else -> throw ContractException("$path must be boolean.")
    }

    private val MODULE_ID = Regex("[a-z][a-z0-9-]*")
    private val EFFECT_KEYS = setOf("reads", "writes", "creates", "updates", "deletes", "executes", "network", "filesystem")
}
