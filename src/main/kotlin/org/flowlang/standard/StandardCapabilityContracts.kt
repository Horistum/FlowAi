package org.flowlang.standard

import org.flowlang.intent.StandardCapability

/**
 * Executable contract for standard capabilities.
 *
 * The catalog explains the vocabulary; contracts constrain AI and scenario-pack
 * output. Normalizers and lowerers must respect these contracts before any AST,
 * plan or target syntax is generated.
 *
 * Free-form runtime command text is deliberately not part of the Standard Intent
 * contract. Core is a target-neutral semantic standard, not a shell-command
 * transport. A caller that supplies `command` must therefore fail validation
 * until a typed semantic contract exists for the requested operation.
 */
data class StandardCapabilityContract(
    val capability: StandardCapability,
    val requiredParams: List<String> = emptyList(),
    val optionalParams: List<String> = emptyList(),
    val requiredSystems: List<String> = emptyList(),
    val loweringStrategy: String = "semantic-action",
    val targetFeatures: List<String> = emptyList(),
    val notes: List<String> = emptyList()
)

object StandardCapabilityContracts {
    val all: Map<StandardCapability, StandardCapabilityContract> = StandardCapability.values().associateWith { capability ->
        val def = StandardIntentCatalog.byCapability[capability]
        StandardCapabilityContract(
            capability = capability,
            requiredParams = def?.requiredParams ?: emptyList(),
            optionalParams = def?.optionalParams ?: emptyList(),
            requiredSystems = def?.requiredSystems ?: emptyList(),
            loweringStrategy = def?.loweringStrategy ?: "semantic-action",
            targetFeatures = def?.targetImplications ?: emptyList(),
            notes = def?.notes ?: emptyList()
        )
    }

    fun requireContract(capability: StandardCapability): StandardCapabilityContract =
        all[capability] ?: error("Missing standard capability contract for $capability")
}
