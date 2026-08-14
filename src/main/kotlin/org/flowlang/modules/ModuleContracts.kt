package org.flowlang.modules

import org.flowlang.continuity.StateLifetime

/**
 * Module + action contracts (docs/06). Modules own meaning; the core owns syntax.
 * Used by the validator and the planner.
 *
 * Target support and projection evidence deliberately do not live in this model.
 * Those decisions belong to versioned target registries and provider catalogs.
 */
data class FlowModule(
    val name: String,
    val version: String,
    val description: String = "Built-in Flow module contract.",
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
    val continuity: ContinuityContract = ContinuityContract(),
    val safety: SafetyContract = SafetyContract(),
    val idempotent: String = "unknown",
    val retrySupported: Boolean = false,
    val timeoutSupported: Boolean = false,
    val additionalParams: Boolean = false,
    val errors: Map<String, ModuleErrorRule> = emptyMap(),
    val secrets: List<String> = emptyList(),
    val requiredCapabilities: List<String> = emptyList(),
    val implementedCapabilities: Set<String> = emptySet()
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

enum class ContinuityKind {
    VALUE,
    WORKSPACE,
    STATE;

    val capability: String get() = "continuity.${name.lowercase()}"
}

/**
 * Named continuity channel owned by a module action contract.
 *
 * State lifetime is orthogonal to the channel kind. Omission on the module wire
 * contract deliberately preserves backward-compatible workflow-local semantics;
 * the planner materializes that default explicitly into ExecutionPlan 2.4.
 */
data class ContinuityChannel(
    val kind: ContinuityKind,
    val name: String,
    val stateLifetime: StateLifetime? = null
) {
    init {
        require(kind == ContinuityKind.STATE || stateLifetime == null) {
            "Only state continuity channels may declare a state lifetime."
        }
    }

    val effectiveStateLifetime: StateLifetime?
        get() = if (kind == ContinuityKind.STATE) stateLifetime ?: StateLifetime.WORKFLOW else null

    /** A stronger durable provider/preserver may satisfy a workflow-local requirement, never the reverse. */
    fun satisfies(required: ContinuityChannel): Boolean {
        if (kind != required.kind || name != required.name) return false
        if (kind != ContinuityKind.STATE) return true
        return when (required.effectiveStateLifetime) {
            StateLifetime.WORKFLOW -> true
            StateLifetime.DURABLE -> effectiveStateLifetime == StateLifetime.DURABLE
            null -> false
        }
    }
}

data class ContinuityContract(
    val provides: List<ContinuityChannel> = emptyList(),
    val requires: List<ContinuityChannel> = emptyList(),
    val preserves: List<ContinuityChannel> = emptyList()
) {
    val allChannels: List<ContinuityChannel> get() = provides + requires + preserves
}

data class SafetyContract(
    val destructive: Boolean = false,
    val requiresApproval: Boolean = false,
    val requiresSafety: Boolean = false
)
