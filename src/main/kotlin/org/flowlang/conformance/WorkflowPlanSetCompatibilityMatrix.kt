package org.flowlang.conformance

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import org.flowlang.cli.Json
import org.flowlang.compiler.CanonicalTryNode
import org.flowlang.compiler.CompilationUnit
import org.flowlang.planner.MultipleWorkflowCompatibilityViewException
import org.flowlang.planner.TryPlanNode
import org.flowlang.standard.FlowStandardVersions

/** Executable checks for the existing 1.1 contract, not a new wire contract or schema library. */
internal object WorkflowPlanSetCompatibilityMatrix {
    fun errors(
        single: CompilationUnit,
        multi: CompilationUnit,
        schema: JsonNode,
        serialized: JsonNode = Json.mapper.valueToTree(single.workflowPlanSet)
    ): List<String> = buildList {
        single.authorization.requireIntegrity()
        multi.authorization.requireIntegrity()
        val view = single.workflowPlanSet.workflows.single()
        if (single.executionPlan != view.executionPlan || single.canonicalPlan != view.canonicalPlan) {
            add("Legacy single-workflow views differ from their graph-derived plan set.")
        }
        if (single.workflowPlanSet.contractVersion != FlowStandardVersions.WORKFLOW_EXECUTION_PLAN_SET_VERSION ||
            schema.path("properties").path("contractVersion").path("const").asText() != "1.1"
        ) add("Public plan-set version and schema must remain 1.1.")
        val actual: JsonNode = Json.mapper.valueToTree(single.workflowPlanSet)
        if (actual != serialized) add("Serialized public evidence differs from the live typed projection.")
        addAll(schemaErrors(serialized, schema))
        addAll(schemaErrors(Json.mapper.valueToTree(multi.workflowPlanSet), schema))

        val tail = single.executionPlan.nodes.lastOrNull() as? TryPlanNode
        val handler = view.failurePolicy.handler
        if (handler == null || tail == null || tail.body.isNotEmpty() ||
            tail.errorHandler.map { it.id } != handler.nodeIds || single.graph.nodes.any { it is CanonicalTryNode }
        ) add("Failure tail is not the exact compatibility mirror of the typed handler region.")
        val accessors = listOf(
            runCatching { multi.ast }, runCatching { multi.validation },
            runCatching { multi.executionPlan }, runCatching { multi.canonicalPlan }
        )
        if (accessors.any { it.exceptionOrNull() !is MultipleWorkflowCompatibilityViewException }) {
            add("A legacy accessor selected or flattened a multi-workflow program.")
        }

        // Prove the actual checked-in schema rejects critical negative payloads.
        // Presence of a $defs node alone is not evidence that a field is required.
        fun mutant(name: String, edit: (ObjectNode) -> Unit) {
            val changed = actual.deepCopy<ObjectNode>()
            edit(changed)
            if (changed == actual || schemaErrors(changed, schema).isEmpty()) {
                add("Public schema did not reject '$name'.")
            }
        }
        mutant("missing failure policy") { (it.path("workflows").path(0) as ObjectNode).remove("failurePolicy") }
        mutant("wrong version") { it.put("contractVersion", "0.0") }
        mutant("invalid failure disposition") {
            (it.path("workflows").path(0).path("failurePolicy") as ObjectNode).put("disposition", "IGNORE")
        }
        mutant("missing error entry") {
            (it.path("workflows").path(0).path("failurePolicy").path("handler") as ObjectNode).remove("entry")
        }
        mutant("invalid availability type") {
            (it.path("workflows").path(0).path("failurePolicy").path("handler").path("entry") as ObjectNode)
                .put("priorSuccessfulValuesAvailable", "false")
        }
    }

    /**
     * Deliberately bounded to the keywords used by workflow-execution-plan-set
     * 1.1. Unknown validation keywords fail closed rather than being ignored.
     * Both positive and negative payloads exercise this same evaluator.
     */
    fun schemaErrors(value: JsonNode, schema: JsonNode): List<String> = buildList {
        fun visit(valueNode: JsonNode, rule: JsonNode, path: String, depth: Int) {
            if (depth > 64 || !rule.isObject) {
                add("$path: invalid or recursive contract schema.")
                return
            }
            val supported = setOf(
                "$" + "schema", "$" + "id", "$" + "defs", "$" + "ref", "title", "description",
                "type", "required", "properties", "additionalProperties", "items", "minItems",
                "uniqueItems", "minLength", "const", "enum"
            )
            (rule.fieldNames().asSequence().toSet() - supported).forEach { add("$path: unsupported schema keyword '$it'.") }
            rule.get("$" + "ref")?.let { reference ->
                val pointer = reference.asText()
                val target = if (reference.isTextual && pointer.startsWith("#/")) schema.at(pointer.removePrefix("#")) else null
                if (target == null || target.isMissingNode) add("$path: unresolved local schema reference '$pointer'.")
                else visit(valueNode, target, path, depth + 1)
            }
            rule.get("type")?.let { types ->
                val names = when {
                    types.isTextual -> listOf(types.asText())
                    types.isArray && types.all { it.isTextual } -> types.map { it.asText() }
                    else -> emptyList()
                }
                fun matches(type: String): Boolean = when (type) {
                    "object" -> valueNode.isObject
                    "array" -> valueNode.isArray
                    "string" -> valueNode.isTextual
                    "boolean" -> valueNode.isBoolean
                    "integer" -> valueNode.isIntegralNumber
                    "number" -> valueNode.isNumber
                    "null" -> valueNode.isNull
                    else -> false
                }
                if (names.isEmpty() || names.any { it !in setOf("object", "array", "string", "boolean", "integer", "number", "null") }) {
                    add("$path: invalid schema type.")
                } else if (names.none(::matches)) add("$path: value has the wrong type; expected $names.")
            }
            if (rule.has("const") && valueNode != rule.get("const")) add("$path: const mismatch.")
            rule.get("enum")?.let { choices ->
                if (!choices.isArray || choices.isEmpty || choices.none { it == valueNode }) add("$path: enum mismatch.")
            }
            listOf("minItems", "minLength").forEach { keyword ->
                rule.get(keyword)?.let { minimum ->
                    if (!minimum.isIntegralNumber || minimum.asInt() < 0) add("$path: invalid $keyword constraint.")
                    else if ((keyword == "minItems" && valueNode.isArray && valueNode.size() < minimum.asInt()) ||
                        (keyword == "minLength" && valueNode.isTextual && valueNode.asText().length < minimum.asInt())
                    ) add("$path: $keyword constraint failed.")
                }
            }
            rule.get("required")?.let { required ->
                if (!required.isArray || required.any { !it.isTextual || it.asText().isBlank() }) {
                    add("$path: invalid required-property constraint.")
                } else if (valueNode.isObject) required.forEach { key ->
                    if (!valueNode.has(key.asText())) add("$path: missing required '${key.asText()}'.")
                }
            }
            rule.get("additionalProperties")?.let {
                if (!it.isBoolean) add("$path: non-boolean additionalProperties is outside this contract evaluator.")
            }
            rule.get("properties")?.let { if (!it.isObject) add("$path: properties must be an object.") }
            if (valueNode.isObject) {
                val properties = rule.path("properties")
                valueNode.fields().forEach { (key, child) ->
                    if (properties.has(key)) visit(child, properties.get(key), "$path.$key", depth + 1)
                    else if (rule.path("additionalProperties").isBoolean && !rule.path("additionalProperties").asBoolean()) {
                        add("$path: unknown property '$key'.")
                    }
                }
            }
            rule.get("uniqueItems")?.let { unique ->
                if (!unique.isBoolean) add("$path: uniqueItems must be boolean.")
                else if (unique.asBoolean() && valueNode.isArray && valueNode.toList().distinct().size != valueNode.size()) {
                    add("$path: duplicate array values.")
                }
            }
            if (valueNode.isArray && rule.has("items")) valueNode.forEachIndexed { index, child ->
                visit(child, rule.get("items"), "$path[$index]", depth + 1)
            }
        }
        visit(value, schema, "$", 0)
    }
}
