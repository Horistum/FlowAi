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
            map(system["input"], "$source.systemTypes.$id.input")
        }

        val approvalByAction = mutableMapOf<String, Boolean>()
        actions.forEach { (id, body) ->
            val action = map(body, "$source.actions.$id", required = true)
            if (text(action, "kind", "$source.actions.$id") != "action") {
                throw ContractException("$source.actions.$id.kind must be action.")
            }
            list(action["targetTypes"], "$source.actions.$id.targetTypes", required = true)
            map(action["input"], "$source.actions.$id.input")
            map(action["output"], "$source.actions.$id.output")
            map(action["effects"], "$source.actions.$id.effects")
            if (action.containsKey("targetImplications")) {
                throw ContractException("$source.actions.$id cannot declare targetImplications.")
            }
            val safety = map(action["safety"], "$source.actions.$id.safety")
            bool(safety["destructive"], "$source.actions.$id.safety.destructive")
            val requirements = list(safety["requires"], "$source.actions.$id.safety.requires")
            val unknown = requirements.toSet() - setOf("safety", "approval")
            if (unknown.isNotEmpty()) throw ContractException("$source.actions.$id has unknown safety requirements: ${unknown.joinToString()}")
            approvalByAction[id] = "approval" in requirements
            map(action["retry"], "$source.actions.$id.retry").let { bool(it["supported"], "$source.actions.$id.retry.supported") }
            map(action["timeout"], "$source.actions.$id.timeout").let { bool(it["supported"], "$source.actions.$id.timeout.supported") }
            list(action["secrets"], "$source.actions.$id.secrets")
            list(action["requiredCapabilities"], "$source.actions.$id.requiredCapabilities")
        }

        val decoded = ModuleYamlLoader.loadText(yaml)
        return decoded.copy(actions = decoded.actions.mapValues { (id, action) ->
            action.copy(safety = action.safety.copy(requiresApproval = approvalByAction[id] == true))
        })
    }

    private fun text(map: Map<String, Any?>, key: String, path: String): String =
        map[key]?.toString()?.takeIf { it.isNotBlank() }
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
            .also { if (required && it.isEmpty()) throw ContractException("$path must not be empty.") }
        else -> throw ContractException("$path must be a list.")
    }

    private fun bool(value: Any?, path: String): Boolean = when (value) {
        null -> false
        is Boolean -> value
        else -> throw ContractException("$path must be boolean.")
    }

    private val MODULE_ID = Regex("[a-z][a-z0-9-]*")
}
