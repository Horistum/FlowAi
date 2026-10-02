package org.flowlang.conformance

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.*
import org.flowlang.cli.Json
import org.flowlang.intent.*
import org.flowlang.serialization.FlowYaml
import org.flowlang.adapters.yaml.IntentYamlLoader as AdapterIntentLoader

class IntentSourceTypeIntegrityTests {
    private val json = Json.mapper.copy().setSerializationInclusion(JsonInclude.Include.ALWAYS)
    private val yaml = ObjectMapper(YAMLFactory())
    private val baseline = """
        intentVersion: '2.0'
        kind: FlowIntentDocument
        name: typed-source
        inputs: [{name: enabled, type: boolean, required: true, default: false}]
        systems: [{name: target, type: remote, config: {count: 12}}]
        triggers:
          - id: daily
            type: SCHEDULE
            workflows: [main]
            schedule: {kind: CRON, expression: '0 0 * * *'}
            params: {}
        workflows:
          - name: main
            kind: CUSTOM
            steps:
              - {id: audit, capability: CUSTOM, requires: [], produces: [], params: {operation: inspect}}
        policies: [{name: guard, type: CUSTOM}]
        failure: {notify: false, rollback: false, stopOnError: true}
    """.trimIndent()

    private val nonNullablePaths = listOf(
        "intentVersion", "kind", "name", "inputs", "systems", "triggers", "workflows", "policies", "failure",
        "inputs.0.name", "inputs.0.type", "inputs.0.required",
        "systems.0.name", "systems.0.type", "systems.0.config",
        "triggers.0.id", "triggers.0.type", "triggers.0.workflows", "triggers.0.schedule", "triggers.0.params",
        "triggers.0.schedule.kind", "triggers.0.schedule.expression",
        "workflows.0.name", "workflows.0.kind", "workflows.0.steps",
        "workflows.0.steps.0.id", "workflows.0.steps.0.capability", "workflows.0.steps.0.requires",
        "workflows.0.steps.0.produces", "workflows.0.steps.0.params",
        "policies.0.name", "policies.0.type", "failure.notify", "failure.rollback", "failure.stopOnError")
    private val booleans = listOf("inputs.0.required", "failure.notify", "failure.rollback", "failure.stopOnError")

    @Suppress("UNCHECKED_CAST")
    private fun changed(path: String, value: Any?): Map<String, Any?> {
        val root = FlowYaml.readMap(baseline)
        val parts = path.split('.')
        var parent: Any? = root
        parts.dropLast(1).forEach { part ->
            parent = if (parent is List<*>) (parent as List<*>)[part.toInt()] else (parent as Map<*, *>)[part]
        }
        (parent as MutableMap<String, Any?>)[parts.last()] = value
        return root
    }

    private fun expectedPath(path: String) = "$" + path.split('.').joinToString("") {
        if (it.toIntOrNull() != null) "[$it]" else ".$it"
    }

    private fun rejection(path: String, code: String, source: String, load: () -> Any?) {
        val error = assertFailsWith<IntentSourceException>("Expected $code at $path from $source") { load() }
        assertEquals(code, error.code)
        assertEquals(expectedPath(path), error.path)
        assertEquals(source, error.sourceName)
    }

    @Test fun explicitNullCannotEraseAuthoredFieldsThroughEitherMapEntryPoint() {
        val positive = IntentYamlLoader.normalize(FlowYaml.readMap(baseline))
        assertEquals(positive, AdapterIntentLoader.normalize(FlowYaml.readMap(baseline)))
        nonNullablePaths.forEach { path ->
            for (load in listOf<(Map<String, Any?>, String) -> IntentDocument>(
                IntentYamlLoader::normalize, AdapterIntentLoader::normalize)) {
                rejection(path, "INTENT_FIELD_TYPE_MISMATCH", "null-map") { load(changed(path, null), "null-map") }
            }
        }
    }

    @Test fun yamlAndJsonFileAndTextReadersRejectTheSameNullFields() {
        val directory = createTempDirectory("intent-types-").toFile()
        try {
            val positive = IntentYamlLoader.loadText(baseline)
            for (text in listOf(baseline, json.writeValueAsString(FlowYaml.readMap(baseline)))) {
                val file = File(directory, "positive.yaml").apply { writeText(text) }
                assertEquals(positive, IntentYamlLoader.load(file))
                assertEquals(positive, AdapterIntentLoader.load(file))
            }
            nonNullablePaths.forEach { path ->
                val root = changed(path, null)
                listOf(json.writeValueAsString(root), yaml.writeValueAsString(root)).forEachIndexed { index, text ->
                    val file = File(directory, "invalid-$index.yaml").apply { writeText(text) }
                    listOf<() -> Any>(
                        { IntentYamlLoader.loadText(text, file.path) }, { IntentYamlLoader.load(file) },
                        { AdapterIntentLoader.loadText(text, file.path) }, { AdapterIntentLoader.load(file) }
                    ).forEach { load -> rejection(path, "INTENT_FIELD_TYPE_MISMATCH", file.path, load) }
                }
            }
        } finally { directory.deleteRecursively() }
    }

    @Test fun quotedBooleansAndNumbersNeverAcquireBooleanMeaning() {
        booleans.forEach { path ->
            for (value in listOf("true", "false", " TRUE ", "FALSE", "maybe", 0, 1)) {
                val code = if (value is String) "INVALID_INTENT_BOOLEAN" else "INTENT_FIELD_TYPE_MISMATCH"
                val root = changed(path, value)
                rejection(path, code, "boolean-map") { IntentYamlLoader.normalize(root, "boolean-map") }
                rejection(path, code, "boolean.json") {
                    AdapterIntentLoader.loadText(json.writeValueAsString(root), "boolean.json")
                }
            }
            for (value in listOf(true, false)) {
                val root = changed(path, value)
                assertEquals(IntentYamlLoader.normalize(root), IntentYamlLoader.loadText(json.writeValueAsString(root)))
            }
        }
        rejection("failure.stopOnError", "INVALID_INTENT_BOOLEAN", "boolean.yaml") {
            IntentYamlLoader.loadText("name: demo\nfailure: {stopOnError: 'false'}", "boolean.yaml")
        }
    }

    @Test fun omittedFieldsRetainDocumentedDefaultsAndEmptyCollectionsRemainValid() {
        val omitted = IntentYamlLoader.loadText("name: demo")
        assertEquals("FlowIntentDocument", omitted.kind)
        assertEquals(IntentFailurePolicy(), omitted.failure)
        assertTrue(omitted.workflows.isEmpty())
        assertEquals(omitted, IntentYamlLoader.loadText("""
            name: demo
            inputs: []
            systems: []
            workflows: []
            triggers: []
            policies: []
            failure: {}
        """.trimIndent()))
        val defaults = IntentYamlLoader.loadText("""
            name: demo
            inputs: [{name: label}]
            workflows: [{name: main, steps: [{id: inspect}]}]
        """.trimIndent())
        assertEquals("text", defaults.inputs.single().type)
        assertFalse(defaults.inputs.single().required)
        assertEquals(IntentWorkflowKind.CUSTOM, defaults.workflows.single().kind)
        assertEquals(StandardCapability.CUSTOM, defaults.workflows.single().steps.single().capability)
    }

    @Test fun nullableMetadataAndExplicitNullIntentValuesArePreserved() {
        for (path in listOf("description", "systems.0.purpose", "triggers.0.event", "triggers.0.schedule.timezone",
            "workflows.0.steps.0.description", "workflows.0.steps.0.uses", "policies.0.condition", "policies.0.message")) {
            val root = changed(path, null)
            assertEquals(IntentYamlLoader.loadText(baseline), IntentYamlLoader.normalize(root))
            assertEquals(IntentYamlLoader.normalize(root), IntentYamlLoader.loadText(json.writeValueAsString(root)))
        }
        val withValues = IntentYamlLoader.loadText("""
            name: nullable-values
            inputs: [{name: nullable, default: null}]
            systems: [{name: target, type: remote, config: {nullable: null}}]
            workflows:
              - name: main
                steps: [{id: inspect, params: {nullable: null, nested: [null, {value: null}]}}]
        """.trimIndent())
        assertIs<IntentNull>(withValues.inputs.single().default)
        assertIs<IntentNull>(withValues.systems.single().config.getValue("nullable"))
        val params = withValues.workflows.single().steps.single().params
        assertIs<IntentNull>(params.getValue("nullable"))
        val nested = assertIs<IntentList>(params.getValue("nested"))
        assertIs<IntentNull>(nested.items.first())
        assertIs<IntentNull>(assertIs<IntentObject>(nested.items.last()).fields.getValue("value"))
    }
}
