package org.flowlang.conformance

import com.fasterxml.jackson.databind.JsonNode

/**
 * Tiny JSON-schema smoke validator for conformance vectors.
 *
 * This is intentionally not a full JSON Schema implementation. It covers the
 * subset used by Flow's draft schemas: type, required, properties, const, enum,
 * arrays/items and local $ref into $defs. Its purpose is to catch schema/output
 * drift in CI without making the pure core depend on a schema engine.
 */
object JsonSchemaSmokeValidator {
    fun validate(instance: JsonNode, schema: JsonNode) {
        validateNode(instance, schema, schema, "$")
    }

    private fun validateNode(instance: JsonNode, schema: JsonNode, root: JsonNode, path: String) {
        val resolved = resolve(schema, root)
        resolved.get("const")?.let { expected ->
            require(instance == expected) { "$path expected const ${expected.toJson()} but got ${instance.toJson()}" }
        }
        resolved.get("enum")?.takeIf { it.isArray }?.let { allowed ->
            require(allowed.any { it == instance }) { "$path expected one of ${allowed.toJson()} but got ${instance.toJson()}" }
        }
        resolved.get("type")?.asText(null)?.let { type ->
            require(matchesType(instance, type)) { "$path expected type $type but got ${instance.nodeType}" }
        }
        if (instance.isObject) {
            resolved.get("required")?.takeIf { it.isArray }?.forEach { req ->
                val name = req.asText()
                require(instance.has(name)) { "$path missing required property '$name'" }
            }
            val props = resolved.get("properties")
            if (props != null && props.isObject) {
                props.fields().forEachRemaining { (name, childSchema) ->
                    if (instance.has(name)) validateNode(instance.get(name), childSchema, root, "$path.$name")
                }
            }
        }
        if (instance.isArray) {
            resolved.get("items")?.let { itemSchema ->
                instance.forEachIndexed { idx, item -> validateNode(item, itemSchema, root, "$path[$idx]") }
            }
        }
    }

    private fun resolve(schema: JsonNode, root: JsonNode): JsonNode {
        val ref = schema.get("\$ref")?.asText() ?: return schema
        require(ref.startsWith("#/\$defs/")) { "Only local #/\$defs references are supported by the smoke validator: $ref" }
        val name = ref.removePrefix("#/\$defs/")
        return root.path("\$defs").path(name).takeIf { !it.isMissingNode } ?: error("Missing schema definition $ref")
    }

    private fun matchesType(node: JsonNode, type: String): Boolean = when (type) {
        "object" -> node.isObject
        "array" -> node.isArray
        "string" -> node.isTextual
        "boolean" -> node.isBoolean
        "number" -> node.isNumber
        "integer" -> node.isInt || node.isLong
        "null" -> node.isNull
        else -> true
    }

    private fun JsonNode.toJson(): String = toString()
}
