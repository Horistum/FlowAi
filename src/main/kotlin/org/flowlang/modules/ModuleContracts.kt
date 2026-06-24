package org.flowlang.modules

/**
 * Module + action contracts (docs/06). Modules own meaning; the core owns syntax.
 * Used by the validator and the planner.
 */
data class FlowModule(
    val name: String,
    val version: String,
    val description: String = "",
    val systemTypes: Map<String, SystemTypeContract> = emptyMap(),
    val actions: Map<String, ModuleActionContract> = emptyMap()
) {
    val systemTypeNames: Set<String> get() = systemTypes.keys
}

data class SystemTypeContract(
    val name: String,
    val input: Map<String, SchemaField> = emptyMap()
)

data class ModuleActionContract(
    val name: String,
    val targetTypes: Set<String> = emptySet(),
    val input: Map<String, SchemaField> = emptyMap(),
    val output: Map<String, SchemaField> = emptyMap(),
    val effects: Effects = Effects(),
    val safety: SafetyContract = SafetyContract(),
    val idempotent: String = "unknown",
    val retrySupported: Boolean = false,
    val timeoutSupported: Boolean = false,
    val additionalParams: Boolean = false,
    val errors: Map<String, ModuleErrorRule> = emptyMap(),
    val secrets: List<String> = emptyList(),
    val requiredCapabilities: List<String> = emptyList(),
    val targetImplications: Map<String, TargetImplication> = emptyMap()
)

/**
 * Module error rule (docs/06). The `when` condition is parsed into an Expression AST
 * (scope = implicitResult, evaluated against the action's result object).
 */
data class ModuleErrorRule(
    val name: String,
    val condition: org.flowlang.ast.ExpressionNode,
    val message: String
)

data class SchemaField(
    val type: String,
    val required: Boolean = false,
    val sensitive: Boolean = false,
    val defaultValue: Any? = null
)

data class TargetImplication(
    val target: String,
    val support: String = "unknown",
    val requiredCapabilities: List<String> = emptyList(),
    val notes: List<String> = emptyList()
)

data class Effects(
    val reads: List<String> = emptyList(),
    val writes: List<String> = emptyList(),
    val creates: List<String> = emptyList(),
    val updates: List<String> = emptyList(),
    val deletes: List<String> = emptyList(),
    val executes: List<String> = emptyList(),
    val network: List<String> = emptyList(),
    val filesystem: List<String> = emptyList()
)

data class SafetyContract(
    val destructive: Boolean = false,
    val requiresSafety: Boolean = false
)
