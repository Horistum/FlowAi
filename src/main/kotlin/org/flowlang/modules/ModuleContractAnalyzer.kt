package org.flowlang.modules

import org.flowlang.standard.FlowStandardVersions

data class CapabilityModuleContractReport(
    val standardVersion: String = FlowStandardVersions.FLOW_STANDARD_VERSION,
    val moduleContractVersion: String = "1.2",
    val totals: CapabilityModuleTotals,
    val modules: List<ModuleSummary>,
    val issues: List<CapabilityModuleIssue> = emptyList()
) {
    val valid: Boolean get() = issues.none { it.level == "error" }
}

data class CapabilityModuleTotals(
    val modules: Int,
    val systemTypes: Int,
    val actions: Int,
    val destructiveActions: Int,
    val targetImplicationActions: Int
)

data class ModuleSummary(
    val name: String,
    val version: String,
    val description: String = "",
    val systemTypes: List<String> = emptyList(),
    val actions: List<ModuleActionSummary> = emptyList()
)

data class ModuleActionSummary(
    val name: String,
    val targetTypes: List<String> = emptyList(),
    val inputs: List<String> = emptyList(),
    val outputs: List<String> = emptyList(),
    val effects: List<String> = emptyList(),
    val continuityProvides: List<String> = emptyList(),
    val continuityRequires: List<String> = emptyList(),
    val continuityPreserves: List<String> = emptyList(),
    val destructive: Boolean = false,
    val requiresSafety: Boolean = false,
    val secrets: List<String> = emptyList(),
    val requiredCapabilities: List<String> = emptyList(),
    val targetImplications: Map<String, String> = emptyMap(),
    val retrySupported: Boolean = false,
    val timeoutSupported: Boolean = false
)

data class CapabilityModuleIssue(
    val level: String,
    val module: String,
    val action: String? = null,
    val code: String,
    val message: String
)

/**
 * Capability module contract audit.
 *
 * Flow modules are dictionaries of capabilities and effects. They must help the
 * planner, validator and target negotiation layer reason about automation
 * semantics. They must not become plugin runtimes or renderer template owners.
 */
object ModuleContractAnalyzer {
    fun analyze(registry: ModuleRegistry): CapabilityModuleContractReport {
        val modules = registry.allModules().sortedBy { it.name }
        val issues = mutableListOf<CapabilityModuleIssue>()
        val summaries = modules.map { module ->
            if (module.description.isBlank()) {
                issues += issue("warning", module.name, null, "MODULE_DESCRIPTION_MISSING", "Module '${module.name}' should describe its purpose for adapter catalogs.")
            }
            if (module.systemTypes.isEmpty() && module.actions.isEmpty()) {
                issues += issue("error", module.name, null, "MODULE_EMPTY", "Module '${module.name}' declares no system types and no actions.")
            }
            ModuleSummary(
                name = module.name,
                version = module.version,
                description = module.description,
                systemTypes = module.systemTypes.keys.sorted(),
                actions = module.actions.values.sortedBy { it.name }.map { action ->
                    inspectAction(module, action, issues)
                    action.summary()
                }
            )
        }
        val actions = modules.flatMap { it.actions.values }
        return CapabilityModuleContractReport(
            totals = CapabilityModuleTotals(
                modules = modules.size,
                systemTypes = modules.sumOf { it.systemTypes.size },
                actions = actions.size,
                destructiveActions = actions.count { it.safety.destructive },
                targetImplicationActions = actions.count { it.targetImplications.isNotEmpty() }
            ),
            modules = summaries,
            issues = issues
        )
    }

    private fun inspectAction(module: FlowModule, action: ModuleActionContract, issues: MutableList<CapabilityModuleIssue>) {
        if (action.targetTypes.isEmpty()) {
            issues += issue("warning", module.name, action.name, "ACTION_TARGET_TYPES_MISSING", "Action '${module.name}.${action.name}' should declare targetTypes.")
        }
        if (action.input.isEmpty() && !action.additionalParams) {
            issues += issue("warning", module.name, action.name, "ACTION_INPUT_CONTRACT_EMPTY", "Action '${module.name}.${action.name}' has no input contract and does not allow additionalParams.")
        }
        if (action.output.isEmpty()) {
            issues += issue("warning", module.name, action.name, "ACTION_OUTPUT_CONTRACT_EMPTY", "Action '${module.name}.${action.name}' should declare output fields.")
        }
        if (action.effects.allEmpty()) {
            issues += issue("warning", module.name, action.name, "ACTION_EFFECTS_MISSING", "Action '${module.name}.${action.name}' should declare effects for safety and target negotiation.")
        }
        if (action.safety.destructive && !action.safety.requiresSafety) {
            issues += issue("error", module.name, action.name, "DESTRUCTIVE_ACTION_REQUIRES_SAFETY", "Destructive action '${module.name}.${action.name}' must require an explicit safety gate.")
        }
        action.input.filterValues { it.sensitive && it.type != "secret" }.forEach { (name, field) ->
            issues += issue("warning", module.name, action.name, "SENSITIVE_INPUT_NOT_SECRET", "Sensitive input '$name' on '${module.name}.${action.name}' is declared as '${field.type}', not 'secret'.")
        }
        action.secrets.forEach { secret ->
            if (secret.isBlank()) {
                issues += issue("warning", module.name, action.name, "SECRET_NAME_EMPTY", "Action '${module.name}.${action.name}' declares an empty secret name.")
            }
        }
    }

    private fun ModuleActionContract.summary(): ModuleActionSummary =
        ModuleActionSummary(
            name = name,
            targetTypes = targetTypes.sorted(),
            inputs = input.keys.sorted(),
            outputs = output.keys.sorted(),
            effects = effects.flatten(),
            continuityProvides = continuity.provides.map { "${it.kind.name.lowercase()}:${it.name}" },
            continuityRequires = continuity.requires.map { "${it.kind.name.lowercase()}:${it.name}" },
            continuityPreserves = continuity.preserves.map { "${it.kind.name.lowercase()}:${it.name}" },
            destructive = safety.destructive,
            requiresSafety = safety.requiresSafety,
            secrets = secrets,
            requiredCapabilities = requiredCapabilities,
            targetImplications = targetImplications.mapValues { it.value.support },
            retrySupported = retrySupported,
            timeoutSupported = timeoutSupported
        )

    private fun Effects.flatten(): List<String> =
        listOf(
            reads.map { "reads:$it" },
            writes.map { "writes:$it" },
            creates.map { "creates:$it" },
            updates.map { "updates:$it" },
            deletes.map { "deletes:$it" },
            executes.map { "executes:$it" },
            network.map { "network:$it" },
            filesystem.map { "filesystem:$it" }
        ).flatten()

    private fun Effects.allEmpty(): Boolean = flatten().isEmpty()

    private fun issue(level: String, module: String, action: String?, code: String, message: String): CapabilityModuleIssue =
        CapabilityModuleIssue(level = level, module = module, action = action, code = code, message = message)
}
