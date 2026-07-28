package org.flowlang.intent

import org.flowlang.modules.ModuleActionContract
import org.flowlang.standard.StandardCapabilityContracts

enum class IntentBindingParameterSource {
    SEMANTIC,
    BINDING,
    DEFAULT
}

enum class IntentBindingEffectPolicy {
    PRESERVE_CANONICAL
}

/**
 * Target-neutral contract derived from one explicit module action implementation.
 *
 * It does not select an implementation. Selection remains explicit through
 * `uses: module.action` and `params.system`. The contract only describes how an
 * already selected action can represent the canonical capability.
 */
data class IntentBindingContract(
    val id: String,
    val capability: StandardCapability,
    val module: String,
    val action: String,
    val systemTypes: Set<String>,
    val mappedSemanticParameters: Set<String>,
    val unsupportedSemanticParameters: Set<String>,
    val bindingOnlyParameters: Set<String>,
    val effectPolicy: IntentBindingEffectPolicy = IntentBindingEffectPolicy.PRESERVE_CANONICAL
)

data class ResolvedIntentBindingParameters(
    val values: Map<String, IntentValue>,
    val sources: Map<String, IntentBindingParameterSource>
)

/** Single target-neutral authority for deriving and resolving explicit bindings. */
object IntentBindingContractAuthority {
    fun derive(
        module: String,
        action: String,
        capability: StandardCapability,
        contract: ModuleActionContract
    ): IntentBindingContract {
        val semanticParameters = canonicalSemanticParameters(capability)
        val mapped = if (contract.additionalParams) {
            semanticParameters
        } else {
            semanticParameters.intersect(contract.input.keys)
        }
        return IntentBindingContract(
            id = "$module.$action#${capability.name}",
            capability = capability,
            module = module,
            action = action,
            systemTypes = contract.targetTypes,
            mappedSemanticParameters = mapped,
            unsupportedSemanticParameters = semanticParameters - mapped,
            bindingOnlyParameters = contract.input.keys - mapped
        )
    }

    fun resolveParameters(
        step: IntentStep,
        binding: IntentBindingContract,
        action: ModuleActionContract
    ): ResolvedIntentBindingParameters {
        val supplied = step.params.filterKeys { it !in CanonicalIntentMeaningAuthority.BINDING_METADATA_PARAMS }
        val values = linkedMapOf<String, IntentValue>()
        val sources = linkedMapOf<String, IntentBindingParameterSource>()

        action.input.forEach { (name, field) ->
            val suppliedValue = supplied[name]
            when {
                suppliedValue != null -> {
                    values[name] = suppliedValue
                    sources[name] = if (name in binding.mappedSemanticParameters) {
                        IntentBindingParameterSource.SEMANTIC
                    } else {
                        IntentBindingParameterSource.BINDING
                    }
                }
                field.defaultValue != null -> {
                    values[name] = defaultIntentValue(field.defaultValue, "${binding.id}.$name")
                    sources[name] = IntentBindingParameterSource.DEFAULT
                }
            }
        }

        if (action.additionalParams) {
            supplied.filterKeys { it !in values }.forEach { (name, value) ->
                values[name] = value
                sources[name] = if (name in binding.mappedSemanticParameters) {
                    IntentBindingParameterSource.SEMANTIC
                } else {
                    IntentBindingParameterSource.BINDING
                }
            }
        }
        return ResolvedIntentBindingParameters(values, sources)
    }

    fun canonicalSemanticParameters(capability: StandardCapability): Set<String> {
        val contract = StandardCapabilityContracts.requireContract(capability)
        return (contract.requiredParams + contract.optionalParams).toSet() -
            CanonicalIntentMeaningAuthority.BINDING_METADATA_PARAMS
    }

    private fun defaultIntentValue(value: Any?, path: String): IntentValue = when (value) {
        null -> IntentNull()
        is String -> IntentString(value)
        is Boolean -> IntentBoolean(value)
        is Byte, is Short, is Int, is Long -> IntentNumber((value as Number).toDouble(), isInteger = true)
        is Float, is Double -> IntentNumber((value as Number).toDouble(), isInteger = false)
        is List<*> -> IntentList(value.mapIndexed { index, item -> defaultIntentValue(item, "$path[$index]") })
        is Map<*, *> -> IntentObject(value.entries.associate { (key, item) ->
            val name = key as? String ?: error("Binding default '$path' contains a non-text object key.")
            name to defaultIntentValue(item, "$path.$name")
        })
        else -> error("Binding default '$path' has unsupported type '${value.javaClass.name}'.")
    }
}
