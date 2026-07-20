package org.flowlang.modules

import java.io.File
import org.flowlang.parser.ExpressionParser
import org.flowlang.serialization.FlowYaml
import org.flowlang.serialization.FlowYamlException

/**
 * Public module YAML entrypoints.
 *
 * Every public load path delegates to [CanonicalModuleLoader]. The decoder below
 * is internal implementation detail and may only run after canonical structural
 * validation has succeeded.
 */
object ModuleYamlLoader {

    class LoadException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

    fun loadText(yaml: String): FlowModule = canonical {
        CanonicalModuleLoader.loadText(yaml)
    }

    fun loadFile(file: File): FlowModule = canonical {
        CanonicalModuleLoader.loadText(file.readText(), file.path)
    }

    fun loadDirectory(dir: File): List<FlowModule> = canonical {
        CanonicalModuleLoader.loadDirectory(dir)
    }

    internal fun decodeText(yaml: String, sourceName: String): FlowModule {
        val root = try {
            FlowYaml.readMap(yaml, sourceName)
        } catch (error: FlowYamlException) {
            throw LoadException(error.message ?: "Invalid module YAML in '$sourceName'.", error)
        }
        val name = root["name"]?.toString().orEmpty()
        val version = root["version"]?.toString().orEmpty()
        val description = root["description"]?.toString().orEmpty()

        val systemTypes = asMap(root["systemTypes"]).mapValues { (typeName, body) ->
            SystemTypeContract(name = typeName, input = parseSchema(asMap(body)["input"]))
        }
        val actions = asMap(root["actions"]).mapValues { (actionName, body) ->
            parseAction(actionName, asMap(body))
        }
        return FlowModule(name = name, version = version, description = description, systemTypes = systemTypes, actions = actions)
    }

    private inline fun <T> canonical(block: () -> T): T = try {
        block()
    } catch (error: CanonicalModuleLoader.ContractException) {
        throw LoadException(error.message ?: "Invalid canonical module descriptor.", error)
    }

    private fun parseAction(name: String, body: Map<String, Any?>): ModuleActionContract {
        val targetTypes = asList(body["targetTypes"]).map { it.toString() }.toSet()
        val input = parseSchema(body["input"])
        val output = parseSchema(body["output"])
        val effects = parseEffects(asMap(body["effects"]))
        val continuity = parseContinuity(asMap(body["continuity"]))
        val safetyMap = asMap(body["safety"])
        val safetyRequirements = strList(safetyMap["requires"])
        val destructive = boolOf(safetyMap["destructive"])
        val safety = SafetyContract(
            destructive = destructive,
            requiresApproval = "approval" in safetyRequirements,
            requiresSafety = "safety" in safetyRequirements || destructive
        )
        val idempotent = when (val value = body["idempotent"]) {
            is Boolean -> value.toString()
            null -> "unknown"
            else -> value.toString()
        }
        val retrySupported = boolOf(asMap(body["retry"])["supported"])
        val timeoutSupported = boolOf(asMap(body["timeout"])["supported"])
        val additionalParams = boolOf(body["additionalParams"])
        val secrets = strList(body["secrets"])
        val requiredCapabilities = strList(body["requiredCapabilities"])
        val errors = asMap(body["errors"]).mapValues { (errorName, errorBody) ->
            val error = asMap(errorBody)
            ModuleErrorRule(
                name = errorName,
                condition = ExpressionParser.parseSource(
                    error["when"]?.toString() ?: "false",
                    scope = "implicitResult"
                ),
                message = error["message"]?.toString() ?: errorName
            )
        }
        return ModuleActionContract(
            name = name,
            targetTypes = targetTypes,
            input = input,
            output = output,
            effects = effects,
            continuity = continuity,
            safety = safety,
            idempotent = idempotent,
            retrySupported = retrySupported,
            timeoutSupported = timeoutSupported,
            additionalParams = additionalParams,
            errors = errors,
            secrets = secrets,
            requiredCapabilities = requiredCapabilities
        )
    }

    private fun parseSchema(node: Any?): Map<String, SchemaField> =
        asMap(node).mapValues { (_, fieldBody) ->
            val field = asMap(fieldBody)
            SchemaField(
                type = field["type"]?.toString().orEmpty(),
                required = boolOf(field["required"]),
                sensitive = boolOf(field["sensitive"]),
                defaultValue = field["default"]
            )
        }

    private fun parseContinuity(map: Map<String, Any?>): ContinuityContract = ContinuityContract(
        provides = continuityChannels(map["provides"]),
        requires = continuityChannels(map["requires"]),
        preserves = continuityChannels(map["preserves"])
    )

    private fun continuityChannels(node: Any?): List<ContinuityChannel> = asList(node).map { raw ->
        val channel = asMap(raw)
        ContinuityChannel(
            kind = ContinuityKind.valueOf(channel.getValue("kind").toString().uppercase()),
            name = channel.getValue("name").toString()
        )
    }

    private fun parseEffects(map: Map<String, Any?>): Effects = Effects(
        reads = strList(map["reads"]),
        writes = strList(map["writes"]),
        creates = strList(map["creates"]),
        updates = strList(map["updates"]),
        deletes = strList(map["deletes"]),
        executes = strList(map["executes"]),
        network = strList(map["network"]),
        filesystem = strList(map["filesystem"])
    )

    @Suppress("UNCHECKED_CAST")
    private fun asMap(node: Any?): Map<String, Any?> = (node as? Map<String, Any?>) ?: emptyMap()
    private fun asList(node: Any?): List<Any?> = (node as? List<Any?>) ?: emptyList()
    private fun strList(node: Any?): List<String> = asList(node).map { it.toString() }
    private fun boolOf(node: Any?): Boolean = node == true
}
