package org.flowlang.intent

import java.io.File
import org.flowlang.serialization.FlowYaml
import org.flowlang.standard.FlowStandardVersions

/** Stable source-boundary failure. Human text may improve; [code] and [path] are contract evidence. */
class IntentSourceException(
    val code: String,
    val path: String,
    val sourceName: String,
    detail: String
) : IllegalStateException("$code at $path in $sourceName: $detail")

/**
 * Strict YAML/JSON boundary for the Standard Intent Model.
 *
 * Equivalent YAML and JSON shapes normalize identically. Malformed shapes, unknown
 * fields and invalid scalar types fail before they can become empty/default meaning.
 */
object IntentYamlLoader {
    private val rootFields = setOf("intentVersion", "kind", "name", "description", "inputs", "systems", "triggers", "workflows", "policies", "failure")

    fun load(file: File): IntentDocument = normalize(FlowYaml.readMap(file), file.path)

    fun loadText(text: String, sourceName: String = "<intent>"): IntentDocument =
        normalize(FlowYaml.readMap(text, sourceName), sourceName)

    fun normalize(root: Map<String, Any?>, sourceName: String = "<intent>"): IntentDocument {
        root.requireOnly(rootFields, "$", sourceName)
        val name = root.requiredString("name", "$.name", sourceName)
        val kind = root.optionalString("kind", "$.kind", sourceName) ?: "FlowIntentDocument"
        if (kind != "FlowIntentDocument") fail("UNSUPPORTED_INTENT_KIND", "$.kind", sourceName, "Unsupported intent kind '$kind'.")
        val intentVersion = root.optionalString("intentVersion", "$.intentVersion", sourceName) ?: FlowStandardVersions.INTENT_VERSION
        if (intentVersion != FlowStandardVersions.INTENT_VERSION) {
            fail(
                "UNSUPPORTED_INTENT_VERSION",
                "$.intentVersion",
                sourceName,
                "Unsupported intentVersion '$intentVersion'. Migrate to ${FlowStandardVersions.INTENT_VERSION}; schedules are top-level triggers in the 2.0 contract."
            )
        }
        return IntentDocument(
            intentVersion = intentVersion,
            kind = kind,
            name = name,
            description = root.optionalString("description", "$.description", sourceName, nullable = true),
            inputs = root.objectList("inputs", "$.inputs", sourceName).mapIndexed { index, value -> value.toIntentInput("$.inputs[$index]", sourceName) },
            systems = root.objectList("systems", "$.systems", sourceName).mapIndexed { index, value -> value.toIntentSystem("$.systems[$index]", sourceName) },
            triggers = root.objectList("triggers", "$.triggers", sourceName).mapIndexed { index, value -> value.toIntentTrigger("$.triggers[$index]", sourceName) },
            workflows = root.objectList("workflows", "$.workflows", sourceName).mapIndexed { index, value -> value.toIntentWorkflow("$.workflows[$index]", sourceName) },
            policies = root.objectList("policies", "$.policies", sourceName).mapIndexed { index, value -> value.toIntentPolicy("$.policies[$index]", sourceName) },
            failure = root.optionalObject("failure", "$.failure", sourceName)?.toIntentFailurePolicy("$.failure", sourceName) ?: IntentFailurePolicy()
        )
    }

    private fun Map<String, Any?>.toIntentInput(path: String, source: String): IntentInput {
        requireOnly(setOf("name", "type", "required", "default"), path, source)
        return IntentInput(
            name = requiredString("name", "$path.name", source),
            type = optionalString("type", "$path.type", source) ?: "text",
            required = optionalBoolean("required", "$path.required", source) ?: false,
            default = if (containsKey("default")) this["default"].toIntentValue("$path.default", source) else null
        )
    }

    private fun Map<String, Any?>.toIntentSystem(path: String, source: String): IntentSystem {
        val name = requiredString("name", "$path.name", source)
        val type = requiredString("type", "$path.type", source)
        val nestedRaw = optionalObject("config", "$path.config", source).orEmpty()
        val reserved = setOf("name", "type", "purpose", "config")
        val inlineRaw = filterKeys { it !in reserved }
        val duplicates = nestedRaw.keys.intersect(inlineRaw.keys)
        if (duplicates.isNotEmpty()) {
            fail("DUPLICATE_SYSTEM_CONFIG_SOURCE", path, source, "Config keys ${duplicates.sorted()} are declared both under config and inline.")
        }
        val nested = nestedRaw.mapValues { (key, value) -> value.toIntentValue("$path.config.$key", source) }
        val inline = inlineRaw.mapValues { (key, value) -> value.toIntentValue("$path.$key", source) }
        return IntentSystem(
            name = name,
            type = type,
            purpose = optionalString("purpose", "$path.purpose", source, nullable = true),
            config = nested + inline
        )
    }

    private fun Map<String, Any?>.toIntentTrigger(path: String, source: String): IntentTrigger {
        requireOnly(setOf("id", "type", "workflows", "schedule", "event", "params"), path, source)
        val id = requiredString("id", "$path.id", source)
        val type = strictEnum<IntentTriggerType>(requiredString("type", "$path.type", source), "UNKNOWN_TRIGGER_TYPE", "$path.type", source)
        val schedule = optionalObject("schedule", "$path.schedule", source)?.let { raw ->
            raw.requireOnly(setOf("kind", "expression", "timezone"), "$path.schedule", source)
            IntentSchedule(
                kind = strictEnum(raw.requiredString("kind", "$path.schedule.kind", source), "UNKNOWN_SCHEDULE_KIND", "$path.schedule.kind", source),
                expression = raw.requiredString("expression", "$path.schedule.expression", source),
                timezone = raw.optionalString("timezone", "$path.schedule.timezone", source, nullable = true)
            )
        }
        return IntentTrigger(
            id = id,
            type = type,
            workflows = if (containsKey("workflows")) {
                stringList("workflows", "$path.workflows", source)
            } else {
                listOf("main")
            },
            schedule = schedule,
            event = optionalString("event", "$path.event", source, nullable = true),
            params = optionalObject("params", "$path.params", source).orEmpty()
                .mapValues { (key, value) -> value.toIntentValue("$path.params.$key", source) }
        )
    }

    private fun Map<String, Any?>.toIntentWorkflow(path: String, source: String): IntentWorkflow {
        requireOnly(setOf("name", "kind", "steps"), path, source)
        val name = requiredString("name", "$path.name", source)
        return IntentWorkflow(
            name = name,
            kind = strictEnum(optionalString("kind", "$path.kind", source) ?: "CUSTOM", "UNKNOWN_WORKFLOW_KIND", "$path.kind", source),
            steps = objectList("steps", "$path.steps", source).mapIndexed { index, value -> value.toIntentStep("$path.steps[$index]", source) }
        )
    }

    private fun Map<String, Any?>.toIntentStep(path: String, source: String): IntentStep {
        requireOnly(setOf("id", "capability", "description", "uses", "requires", "produces", "params"), path, source)
        val id = requiredString("id", "$path.id", source)
        return IntentStep(
            id = id,
            capability = strictCapability(optionalString("capability", "$path.capability", source) ?: "CUSTOM", id, "$path.capability", source),
            description = optionalString("description", "$path.description", source, nullable = true),
            uses = optionalString("uses", "$path.uses", source, nullable = true),
            requires = stringList("requires", "$path.requires", source),
            produces = stringList("produces", "$path.produces", source),
            params = optionalObject("params", "$path.params", source).orEmpty()
                .mapValues { (key, value) -> value.toIntentValue("$path.params.$key", source) }
        )
    }

    private fun Map<String, Any?>.toIntentPolicy(path: String, source: String): IntentPolicy {
        requireOnly(setOf("name", "type", "condition", "message"), path, source)
        val name = requiredString("name", "$path.name", source)
        return IntentPolicy(
            name = name,
            type = strictEnum(optionalString("type", "$path.type", source) ?: "CUSTOM", "UNKNOWN_POLICY_TYPE", "$path.type", source),
            condition = optionalString("condition", "$path.condition", source, nullable = true),
            message = optionalString("message", "$path.message", source, nullable = true)
        )
    }

    private fun Map<String, Any?>.toIntentFailurePolicy(path: String, source: String): IntentFailurePolicy {
        requireOnly(setOf("notify", "rollback", "stopOnError"), path, source)
        return IntentFailurePolicy(
            notify = optionalBoolean("notify", "$path.notify", source) ?: false,
            rollback = optionalBoolean("rollback", "$path.rollback", source) ?: false,
            stopOnError = optionalBoolean("stopOnError", "$path.stopOnError", source) ?: true
        )
    }

    private fun Any?.toIntentValue(path: String, source: String): IntentValue = when (this) {
        null -> IntentNull()
        is IntentValue -> this
        is Boolean -> IntentBoolean(this)
        is Int -> IntentNumber(toDouble(), isInteger = true)
        is Long -> IntentNumber(toDouble(), isInteger = true)
        is Float -> IntentNumber(toDouble(), isInteger = false)
        is Double -> IntentNumber(this, isInteger = this % 1.0 == 0.0)
        is Number -> IntentNumber(toDouble(), isInteger = false)
        is String -> parseStringValue(this, path, source)
        is Map<*, *> -> {
            val values = linkedMapOf<String, IntentValue>()
            entries.forEach { (rawKey, rawValue) ->
                val key = rawKey as? String ?: fail("NON_STRING_OBJECT_KEY", path, source, "Object keys must be strings.")
                values[key] = rawValue.toIntentValue("$path.$key", source)
            }
            IntentObject(values)
        }
        is List<*> -> IntentList(mapIndexed { index, value -> value.toIntentValue("$path[$index]", source) })
        else -> fail("UNSUPPORTED_INTENT_VALUE_TYPE", path, source, "Unsupported value type '${this::class.qualifiedName}'.")
    }

    private fun parseStringValue(raw: String, path: String, source: String): IntentValue = when {
        raw.startsWith("secret:") -> IntentSecretRef(nonBlankSuffix(raw, "secret:", "EMPTY_SECRET_REFERENCE", path, source))
        raw.startsWith("ref:") -> IntentRef(referencePath(nonBlankSuffix(raw, "ref:", "EMPTY_INTENT_REFERENCE", path, source), path, source))
        raw.startsWith("expr:") -> IntentExpression(nonBlankSuffix(raw, "expr:", "EMPTY_INTENT_EXPRESSION", path, source))
        raw.matches(Regex("""^\$\{[A-Za-z_][A-Za-z0-9_.-]*}$""")) -> IntentRef(referencePath(raw.removePrefix("\${").removeSuffix("}"), path, source))
        raw.matches(Regex("""^\$[A-Za-z_][A-Za-z0-9_.-]*$""")) -> IntentRef(referencePath(raw.drop(1), path, source))
        else -> IntentString(raw)
    }

    private fun referencePath(raw: String, path: String, source: String): List<String> {
        val segments = raw.split('.')
        if (segments.any { it.isBlank() }) fail("INVALID_INTENT_REFERENCE", path, source, "Reference '$raw' contains an empty path segment.")
        return segments
    }

    private fun nonBlankSuffix(raw: String, prefix: String, code: String, path: String, source: String): String {
        val value = raw.removePrefix(prefix).trim()
        if (value.isBlank()) fail(code, path, source, "'$prefix' must be followed by a value.")
        return value
    }

    private fun Map<String, Any?>.requireOnly(allowed: Set<String>, path: String, source: String) {
        val unknown = keys - allowed
        if (unknown.isNotEmpty()) fail("UNKNOWN_INTENT_FIELD", path, source, "Unknown fields ${unknown.sorted()}.")
    }

    private fun Map<String, Any?>.requiredString(key: String, path: String, source: String): String =
        optionalString(key, path, source) ?: fail("MISSING_REQUIRED_INTENT_FIELD", path, source, "Required string field '$key' is missing.")

    private fun Map<String, Any?>.optionalString(
        key: String, path: String, source: String, nullable: Boolean = false
    ): String? = when (val value = this[key]) {
        null -> if (nullable || !containsKey(key)) null else nullField(path, source, "a string")
        is String -> value
        else -> fail("INTENT_FIELD_TYPE_MISMATCH", path, source, "Expected a string but found ${value::class.simpleName}.")
    }

    private fun Map<String, Any?>.optionalBoolean(key: String, path: String, source: String): Boolean? = when (val value = this[key]) {
        null -> if (!containsKey(key)) null else nullField(path, source, "a boolean")
        is Boolean -> value
        is String -> fail("INVALID_INTENT_BOOLEAN", path, source, "Expected a boolean, not quoted text '$value'.")
        else -> fail("INTENT_FIELD_TYPE_MISMATCH", path, source, "Expected a boolean but found ${value::class.simpleName}.")
    }

    @Suppress("UNCHECKED_CAST")
    private fun Map<String, Any?>.optionalObject(key: String, path: String, source: String): Map<String, Any?>? = when (val value = this[key]) {
        null -> if (!containsKey(key)) null else nullField(path, source, "an object")
        is Map<*, *> -> {
            if (value.keys.any { it !is String }) fail("NON_STRING_OBJECT_KEY", path, source, "Object keys must be strings.")
            value as Map<String, Any?>
        }
        else -> fail("INTENT_FIELD_TYPE_MISMATCH", path, source, "Expected an object but found ${value::class.simpleName}.")
    }

    @Suppress("UNCHECKED_CAST")
    private fun Map<String, Any?>.objectList(key: String, path: String, source: String): List<Map<String, Any?>> = when (val value = this[key]) {
        null -> if (!containsKey(key)) emptyList() else nullField(path, source, "a list of objects")
        is List<*> -> value.mapIndexed { index, item ->
            val map = item as? Map<*, *> ?: fail("INTENT_LIST_ITEM_TYPE_MISMATCH", "$path[$index]", source, "Expected an object.")
            if (map.keys.any { it !is String }) fail("NON_STRING_OBJECT_KEY", "$path[$index]", source, "Object keys must be strings.")
            map as Map<String, Any?>
        }
        else -> fail("INTENT_FIELD_TYPE_MISMATCH", path, source, "Expected a list of objects but found ${value::class.simpleName}.")
    }

    private fun Map<String, Any?>.stringList(key: String, path: String, source: String): List<String> = when (val value = this[key]) {
        null -> if (!containsKey(key)) emptyList() else nullField(path, source, "a string or list of strings")
        is String -> listOf(value)
        is List<*> -> value.mapIndexed { index, item ->
            item as? String ?: fail("INTENT_LIST_ITEM_TYPE_MISMATCH", "$path[$index]", source, "Expected a string.")
        }
        else -> fail("INTENT_FIELD_TYPE_MISMATCH", path, source, "Expected a string or list of strings but found ${value::class.simpleName}.")
    }

    // Absence may select a documented default. Authored null is a value and may
    // only cross fields whose contract explicitly permits it (including IntentValue).
    private fun nullField(path: String, source: String, expected: String): Nothing =
        fail("INTENT_FIELD_TYPE_MISMATCH", path, source, "Expected $expected but found null; explicit null is not allowed for this field.")

    private fun strictCapability(value: String, stepId: String, path: String, source: String): StandardCapability {
        val normalized = normalizeEnum(value)
        if (normalized == "SCHEDULE") {
            fail("REMOVED_SCHEDULE_CAPABILITY", path, source, "Intent step '$stepId' uses removed capability SCHEDULE. Declare a top-level trigger with type: SCHEDULE instead.")
        }
        StandardCapabilityCompatibility.resolveSourceName(normalized)?.let { return it }
        return enumValues<StandardCapability>().firstOrNull { it.name == normalized }
            ?: fail("UNKNOWN_STANDARD_CAPABILITY", path, source, "Intent step '$stepId' uses unknown capability '$value'.")
    }

    private inline fun <reified T : Enum<T>> strictEnum(value: String, code: String, path: String, source: String): T {
        val normalized = normalizeEnum(value)
        return enumValues<T>().firstOrNull { it.name == normalized }
            ?: fail(code, path, source, "Unknown ${T::class.simpleName} value '$value'.")
    }

    private fun normalizeEnum(value: String): String = value.trim().replace('-', '_').replace(' ', '_').uppercase()

    private fun fail(code: String, path: String, source: String, detail: String): Nothing =
        throw IntentSourceException(code, path, source, detail)
}
