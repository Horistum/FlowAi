package org.flowlang.modules

import org.flowlang.parser.ExpressionParser
import java.io.File

/**
 * Loads Flow module descriptors (docs/06, `module.yaml`) into [FlowModule] contracts.
 * Error rules' `when` conditions are parsed into Expression AST as the spec requires.
 */
object ModuleYamlLoader {

    class LoadException(message: String) : RuntimeException(message)

    private val builtInOrder = listOf(
        "shell.yaml",
        "git.yaml",
        "rest.yaml",
        "notify.yaml",
        "docker.yaml",
        "helm.yaml",
        "argocd.yaml",
        "kubernetes.yaml",
        "database.yaml",
        "standard.yaml"
    )

    fun loadText(yaml: String): FlowModule {
        val root = MiniYaml.parseMap(yaml)
        val kind = root["kind"]?.toString()
        if (kind != null && kind != "FlowModule") throw LoadException("expected kind: FlowModule but got '$kind'")
        val name = root["name"]?.toString() ?: throw LoadException("module descriptor missing 'name'")
        val version = root["version"]?.toString() ?: "1.0"
        val description = root["description"]?.toString().orEmpty()

        val systemTypes = asMap(root["systemTypes"]).mapValues { (typeName, body) ->
            SystemTypeContract(name = typeName, input = parseSchema(asMap(body)["input"]))
        }
        val actions = asMap(root["actions"]).mapValues { (actionName, body) ->
            parseAction(actionName, asMap(body))
        }
        return FlowModule(name = name, version = version, description = description, systemTypes = systemTypes, actions = actions)
    }

    fun loadFile(file: File): FlowModule = loadText(file.readText())

    /** Loads every *.yaml / *.yml descriptor in a directory. */
    fun loadDirectory(dir: File): List<FlowModule> {
        if (!dir.isDirectory) return emptyList()
        return dir.listFiles { f -> f.isFile && (f.extension == "yaml" || f.extension == "yml") }
            ?.sortedWith(compareBy<File> { file ->
                val index = builtInOrder.indexOf(file.name)
                if (index >= 0) index else Int.MAX_VALUE
            }.thenBy { it.name })
            ?.map { loadFile(it) }
            ?: emptyList()
    }

    // --- mapping helpers ------------------------------------------------------

    private fun parseAction(name: String, body: Map<String, Any?>): ModuleActionContract {
        val targetTypes = asList(body["targetTypes"]).map { it.toString() }.toSet()
        val input = parseSchema(body["input"])
        val output = parseSchema(body["output"])
        val effects = parseEffects(asMap(body["effects"]))
        val safetyMap = asMap(body["safety"])
        val safety = SafetyContract(
            destructive = boolOf(safetyMap["destructive"]),
            requiresSafety = asList(safetyMap["requires"]).map { it.toString() }.contains("safety")
                    || boolOf(safetyMap["destructive"])
        )
        val idempotent = when (val v = body["idempotent"]) {
            is Boolean -> v.toString()
            null -> "unknown"
            else -> v.toString()
        }
        val retrySupported = boolOf(asMap(body["retry"])["supported"])
        val timeoutSupported = boolOf(asMap(body["timeout"])["supported"])
        val additionalParams = boolOf(body["additionalParams"])
        val secrets = strList(body["secrets"])
        val requiredCapabilities = strList(body["requiredCapabilities"])
        val targetImplications = parseTargetImplications(body["targetImplications"])
        val errors = asMap(body["errors"]).mapValues { (errName, errBody) ->
            val eb = asMap(errBody)
            val whenSrc = eb["when"]?.toString() ?: "false"
            ModuleErrorRule(
                name = errName,
                condition = ExpressionParser.parseSource(whenSrc, scope = "implicitResult"),
                message = eb["message"]?.toString() ?: errName
            )
        }
        return ModuleActionContract(
            name = name, targetTypes = targetTypes, input = input, output = output,
            effects = effects, safety = safety, idempotent = idempotent,
            retrySupported = retrySupported, timeoutSupported = timeoutSupported,
            additionalParams = additionalParams, errors = errors, secrets = secrets,
            requiredCapabilities = requiredCapabilities, targetImplications = targetImplications
        )
    }

    private fun parseSchema(node: Any?): Map<String, SchemaField> =
        asMap(node).mapValues { (_, fieldBody) ->
            val fb = asMap(fieldBody)
            SchemaField(
                type = fb["type"]?.toString() ?: "any",
                required = boolOf(fb["required"]),
                sensitive = boolOf(fb["sensitive"]),
                defaultValue = fb["default"]
            )
        }

    private fun parseEffects(m: Map<String, Any?>): Effects = Effects(
        reads = strList(m["reads"]), writes = strList(m["writes"]),
        creates = strList(m["creates"]), updates = strList(m["updates"]),
        deletes = strList(m["deletes"]), executes = strList(m["executes"]),
        network = strList(m["network"]), filesystem = strList(m["filesystem"])
    )

    private fun parseTargetImplications(node: Any?): Map<String, TargetImplication> =
        asMap(node).mapValues { (target, raw) ->
            val m = asMap(raw)
            TargetImplication(
                target = target,
                support = m["support"]?.toString() ?: m["supported"]?.toString() ?: "unknown",
                requiredCapabilities = strList(m["requiredCapabilities"]),
                notes = strList(m["notes"])
            )
        }

    @Suppress("UNCHECKED_CAST")
    private fun asMap(node: Any?): Map<String, Any?> = (node as? Map<String, Any?>) ?: emptyMap()
    private fun asList(node: Any?): List<Any?> = (node as? List<Any?>) ?: emptyList()
    private fun strList(node: Any?): List<String> = asList(node).map { it.toString() }
    private fun boolOf(node: Any?): Boolean = node == true || node == "true"
}
