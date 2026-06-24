package org.flowlang.intent

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import java.io.File

/**
 * Loads and normalizes the high-level Standard Intent Model from YAML.
 *
 * This loader deliberately uses Jackson YAML instead of the in-repo MiniYaml helper.
 * Intent input is a user/AI boundary and must accept normal YAML forms: block maps,
 * flow-style maps (`params: { app: demo }`), flow-style lists, quoted strings and
 * standard scalar handling. Complex maps/lists remain structured IntentValue
 * objects instead of being flattened into JSON strings.
 */
object IntentYamlLoader {
    private val mapper: ObjectMapper = ObjectMapper(YAMLFactory()).registerKotlinModule()
    private val mapType = object : TypeReference<Map<String, Any?>>() {}

    fun load(file: File): IntentDocument = loadText(file.readText(), file.path)

    fun loadText(text: String, sourceName: String = "<intent>"): IntentDocument {
        val root = mapper.readValue(text, mapType)
        return normalize(root, sourceName)
    }

    fun normalize(root: Map<String, Any?>, sourceName: String = "<intent>"): IntentDocument {
        val name = root.string("name") ?: error("Intent '$sourceName' is missing required field: name")
        val kind = root.string("kind") ?: "FlowIntentDocument"
        require(kind == "FlowIntentDocument") { "Unsupported intent kind '$kind' in $sourceName" }

        return IntentDocument(
            intentVersion = root.string("intentVersion") ?: "1.0",
            kind = kind,
            name = name,
            description = root.string("description"),
            inputs = root.listOfMaps("inputs").map { it.toIntentInput() },
            systems = root.listOfMaps("systems").map { it.toIntentSystem() },
            workflows = root.listOfMaps("workflows").map { it.toIntentWorkflow() },
            policies = root.listOfMaps("policies").map { it.toIntentPolicy() },
            failure = root.map("failure")?.toIntentFailurePolicy() ?: IntentFailurePolicy()
        )
    }

    private fun Map<String, Any?>.toIntentInput(): IntentInput = IntentInput(
        name = string("name") ?: error("Intent input is missing name"),
        type = string("type") ?: "text",
        required = bool("required") ?: false,
        default = this["default"]?.toIntentValue()
    )

    private fun Map<String, Any?>.toIntentSystem(): IntentSystem {
        val name = string("name") ?: error("Intent system is missing name")
        val type = string("type") ?: error("Intent system '$name' is missing type")
        val nested = map("config")?.mapValues { (_, v) -> v.toIntentValue() } ?: emptyMap()
        val reserved = setOf("name", "type", "purpose", "config")
        val inline = filterKeys { it !in reserved }.mapValues { (_, v) -> v.toIntentValue() }
        return IntentSystem(
            name = name,
            type = type,
            purpose = string("purpose"),
            config = nested + inline
        )
    }

    private fun Map<String, Any?>.toIntentWorkflow(): IntentWorkflow = IntentWorkflow(
        name = string("name") ?: error("Intent workflow is missing name"),
        kind = enumValue(string("kind") ?: "CUSTOM", IntentWorkflowKind.CUSTOM),
        steps = listOfMaps("steps").map { it.toIntentStep() }
    )

    private fun Map<String, Any?>.toIntentStep(): IntentStep = IntentStep(
        id = string("id") ?: error("Intent step is missing id"),
        capability = enumValue(string("capability") ?: "CUSTOM", StandardCapability.CUSTOM),
        description = string("description"),
        uses = string("uses"),
        requires = stringList("requires"),
        produces = stringList("produces"),
        params = map("params")?.mapValues { (_, v) -> v.toIntentValue() } ?: emptyMap()
    )

    private fun Map<String, Any?>.toIntentPolicy(): IntentPolicy = IntentPolicy(
        name = string("name") ?: error("Intent policy is missing name"),
        type = enumValue(string("type") ?: "CUSTOM", IntentPolicyType.CUSTOM),
        condition = string("condition"),
        message = string("message")
    )

    private fun Map<String, Any?>.toIntentFailurePolicy(): IntentFailurePolicy = IntentFailurePolicy(
        notify = bool("notify") ?: false,
        rollback = bool("rollback") ?: false,
        stopOnError = bool("stopOnError") ?: true
    )

    private fun Any?.toIntentValue(): IntentValue = when (this) {
        null -> IntentNull()
        is IntentValue -> this
        is Boolean -> IntentBoolean(this)
        is Int -> IntentNumber(toDouble(), isInteger = true)
        is Long -> IntentNumber(toDouble(), isInteger = true)
        is Float -> IntentNumber(toDouble(), isInteger = false)
        is Double -> IntentNumber(this, isInteger = this % 1.0 == 0.0)
        is Number -> IntentNumber(toDouble(), isInteger = false)
        is String -> parseStringValue(this)
        is Map<*, *> -> IntentObject(entries.mapNotNull { (k, v) -> k?.toString()?.let { it to v.toIntentValue() } }.toMap())
        is List<*> -> IntentList(map { it.toIntentValue() })
        else -> IntentString(toString())
    }

    private fun parseStringValue(raw: String): IntentValue = when {
        raw.startsWith("secret:") -> IntentSecretRef(raw.removePrefix("secret:"))
        raw.startsWith("ref:") -> IntentRef(raw.removePrefix("ref:").split('.').filter { it.isNotBlank() })
        raw.startsWith("expr:") -> IntentExpression(raw.removePrefix("expr:"))
        raw.matches(Regex("""^\$\{[A-Za-z_][A-Za-z0-9_.-]*}$""")) ->
            IntentRef(raw.removePrefix("\${").removeSuffix("}").split('.').filter { it.isNotBlank() })
        raw.matches(Regex("""^\$[A-Za-z_][A-Za-z0-9_.-]*$""")) ->
            IntentRef(raw.drop(1).split('.').filter { it.isNotBlank() })
        else -> IntentString(raw)
    }

    @Suppress("UNCHECKED_CAST")
    private fun Map<String, Any?>.map(key: String): Map<String, Any?>? = this[key] as? Map<String, Any?>

    private fun Map<String, Any?>.string(key: String): String? = when (val v = this[key]) {
        null -> null
        is String -> v
        is Number, is Boolean -> v.toString()
        else -> null
    }

    private fun Map<String, Any?>.bool(key: String): Boolean? = when (val v = this[key]) {
        is Boolean -> v
        is String -> when (v.trim().lowercase()) {
            "true" -> true
            "false" -> false
            else -> null
        }
        else -> null
    }

    @Suppress("UNCHECKED_CAST")
    private fun Map<String, Any?>.listOfMaps(key: String): List<Map<String, Any?>> = when (val v = this[key]) {
        is List<*> -> v.mapNotNull { it as? Map<String, Any?> }
        null -> emptyList()
        else -> error("Intent field '$key' must be a list of objects")
    }

    private fun Map<String, Any?>.stringList(key: String): List<String> = when (val v = this[key]) {
        is List<*> -> v.map { it?.toString() ?: "" }
        is String -> listOf(v)
        null -> emptyList()
        else -> listOf(v.toString())
    }

    private inline fun <reified T : Enum<T>> enumValue(value: String, default: T): T {
        val normalized = value.trim().replace('-', '_').replace(' ', '_')
        return enumValues<T>().firstOrNull { it.name.equals(normalized, ignoreCase = true) } ?: default
    }
}
