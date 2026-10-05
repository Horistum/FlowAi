package org.flowlang.artifacts

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.TextNode

/**
 * Deterministic JSON Schema validator for the subset published by Flow.
 *
 * This deliberately does not pretend to implement arbitrary JSON Schema. It validates every
 * assertion keyword currently used by Flow's published schemas and rejects a schema that adds
 * an unsupported assertion keyword. Annotation keywords are ignored. That fail-closed boundary
 * prevents conformance from reporting schema validity while silently skipping a new constraint.
 */
object ArtifactSchemaValidator {
    private val annotationKeywords = setOf(
        "\$schema",
        "\$id",
        "title",
        "description",
        "default",
        "examples"
    )

    private val assertionKeywords = setOf(
        "\$ref",
        "\$defs",
        "type",
        "required",
        "properties",
        "additionalProperties",
        "propertyNames",
        "const",
        "enum",
        "items",
        "minItems",
        "uniqueItems",
        "minLength",
        "pattern",
        "minimum",
        "maximum",
        "minProperties",
        "oneOf",
        "anyOf",
        "allOf",
        "if",
        "then",
        "else",
        "not"
    )

    fun validate(instance: JsonNode, schema: JsonNode) {
        requireSupportedSchema(schema, schema, "$")
        validateNode(instance, schema, schema, "$")
    }

    private fun validateNode(instance: JsonNode, schema: JsonNode, root: JsonNode, path: String) {
        if (schema.isBoolean) {
            require(schema.booleanValue()) { "$path is rejected by boolean false schema" }
            return
        }
        require(schema.isObject) { "$path schema must be an object or boolean" }

        schema.get("\$ref")?.asText()?.let { ref ->
            validateNode(instance, resolve(ref, root), root, path)
        }

        schema.get("allOf")?.takeIf { it.isArray }?.forEach { branch ->
            validateNode(instance, branch, root, path)
        }
        schema.get("anyOf")?.takeIf { it.isArray }?.let { branches ->
            require(branches.any { matches(instance, it, root, path) }) {
                "$path did not match any anyOf branch"
            }
        }
        schema.get("oneOf")?.takeIf { it.isArray }?.let { branches ->
            val matched = branches.count { matches(instance, it, root, path) }
            require(matched == 1) { "$path expected exactly one oneOf branch but matched $matched" }
        }
        schema.get("not")?.let { forbidden ->
            require(!matches(instance, forbidden, root, path)) { "$path matched a forbidden not schema" }
        }
        schema.get("if")?.let { condition ->
            val selected = if (matches(instance, condition, root, path)) schema.get("then") else schema.get("else")
            selected?.let { validateNode(instance, it, root, path) }
        }

        schema.get("const")?.let { expected ->
            require(instance == expected) { "$path expected const ${expected.toJson()} but got ${instance.toJson()}" }
        }
        schema.get("enum")?.takeIf { it.isArray }?.let { allowed ->
            require(allowed.any { it == instance }) {
                "$path expected one of ${allowed.toJson()} but got ${instance.toJson()}"
            }
        }
        schema.get("type")?.let { typeDeclaration ->
            val allowed = when {
                typeDeclaration.isTextual -> listOf(typeDeclaration.asText())
                typeDeclaration.isArray -> typeDeclaration.map { it.asText() }
                else -> error("$path schema type must be a string or array of strings")
            }
            require(allowed.any { matchesType(instance, it) }) {
                "$path expected type ${allowed.joinToString("|")} but got ${instance.nodeType}"
            }
        }

        if (instance.isTextual) {
            schema.get("minLength")?.asInt()?.let { minimum ->
                require(instance.textValue().length >= minimum) {
                    "$path expected minLength $minimum but was ${instance.textValue().length}"
                }
            }
            schema.get("pattern")?.asText()?.let { pattern ->
                require(Regex(pattern).containsMatchIn(instance.textValue())) {
                    "$path value '${instance.textValue()}' does not match pattern '$pattern'"
                }
            }
        }

        if (instance.isNumber) {
            schema.get("minimum")?.decimalValue()?.let { minimum ->
                require(instance.decimalValue() >= minimum) {
                    "$path expected minimum $minimum but got ${instance.decimalValue()}"
                }
            }
            schema.get("maximum")?.decimalValue()?.let { maximum ->
                require(instance.decimalValue() <= maximum) {
                    "$path expected maximum $maximum but got ${instance.decimalValue()}"
                }
            }
        }

        if (instance.isObject) {
            schema.get("required")?.takeIf { it.isArray }?.forEach { req ->
                val name = req.asText()
                require(instance.has(name)) { "$path missing required property '$name'" }
            }
            schema.get("minProperties")?.asInt()?.let { minimum ->
                require(instance.size() >= minimum) {
                    "$path expected at least $minimum properties but got ${instance.size()}"
                }
            }

            val props = schema.get("properties")
            val declaredNames = if (props != null && props.isObject) {
                props.fieldNames().asSequence().toSet()
            } else {
                emptySet()
            }
            if (props != null && props.isObject) {
                props.fields().forEachRemaining { (name, childSchema) ->
                    if (instance.has(name)) validateNode(instance.get(name), childSchema, root, "$path.$name")
                }
            }

            schema.get("propertyNames")?.let { nameSchema ->
                instance.fieldNames().forEachRemaining { name ->
                    validateNode(TextNode.valueOf(name), nameSchema, root, "$path.<property:$name>")
                }
            }

            schema.get("additionalProperties")?.let { additional ->
                val extras = instance.fieldNames().asSequence().filterNot { it in declaredNames }.toList()
                when {
                    additional.isBoolean && !additional.booleanValue() -> require(extras.isEmpty()) {
                        "$path contains additional properties ${extras.sorted()}"
                    }
                    additional.isObject || additional.isBoolean -> extras.forEach { name ->
                        validateNode(instance.get(name), additional, root, "$path.$name")
                    }
                    else -> error("$path additionalProperties must be a schema or boolean")
                }
            }
        }

        if (instance.isArray) {
            schema.get("minItems")?.asInt()?.let { minimum ->
                require(instance.size() >= minimum) { "$path expected at least $minimum items but got ${instance.size()}" }
            }
            if (schema.get("uniqueItems")?.asBoolean(false) == true) {
                val values = instance.toList()
                require(values.distinct().size == values.size) { "$path expected uniqueItems" }
            }
            schema.get("items")?.let { itemSchema ->
                instance.forEachIndexed { index, item -> validateNode(item, itemSchema, root, "$path[$index]") }
            }
        }
    }

    private fun matches(instance: JsonNode, schema: JsonNode, root: JsonNode, path: String): Boolean =
        try {
            validateNode(instance, schema, root, path)
            true
        } catch (_: IllegalArgumentException) {
            false
        } catch (_: IllegalStateException) {
            false
        }

    private fun requireSupportedSchema(schema: JsonNode, root: JsonNode, path: String) {
        if (schema.isBoolean) return
        require(schema.isObject) { "$path schema must be an object or boolean" }
        val supported = annotationKeywords + assertionKeywords
        val unknown = schema.fieldNames().asSequence().filterNot { it in supported }.toList()
        require(unknown.isEmpty()) { "$path contains unsupported schema keyword(s): ${unknown.sorted()}" }

        schema.get("\$defs")?.takeIf { it.isObject }?.fields()?.forEachRemaining { (name, child) ->
            requireSupportedSchema(child, root, "$path.\$defs.$name")
        }
        schema.get("properties")?.takeIf { it.isObject }?.fields()?.forEachRemaining { (name, child) ->
            requireSupportedSchema(child, root, "$path.properties.$name")
        }
        listOf("additionalProperties", "propertyNames", "items", "if", "then", "else", "not").forEach { keyword ->
            schema.get(keyword)?.let { child ->
                if (child.isObject || child.isBoolean) requireSupportedSchema(child, root, "$path.$keyword")
            }
        }
        listOf("allOf", "anyOf", "oneOf").forEach { keyword ->
            schema.get(keyword)?.takeIf { it.isArray }?.forEachIndexed { index, child ->
                requireSupportedSchema(child, root, "$path.$keyword[$index]")
            }
        }
        schema.get("\$ref")?.asText()?.let { ref -> resolve(ref, root) }
    }

    private fun resolve(ref: String, root: JsonNode): JsonNode {
        require(ref.startsWith("#/")) { "Only local JSON Pointer references are supported by the Flow schema validator: $ref" }
        var current = root
        ref.removePrefix("#/").split('/').forEach { rawSegment ->
            val segment = rawSegment.replace("~1", "/").replace("~0", "~")
            current = current.path(segment)
            require(!current.isMissingNode) { "Missing schema definition $ref" }
        }
        return current
    }

    private fun matchesType(node: JsonNode, type: String): Boolean = when (type) {
        "object" -> node.isObject
        "array" -> node.isArray
        "string" -> node.isTextual
        "boolean" -> node.isBoolean
        "number" -> node.isNumber
        "integer" -> node.isIntegralNumber
        "null" -> node.isNull
        else -> error("Unsupported JSON Schema type '$type'")
    }

    private fun JsonNode.toJson(): String = toString()
}
