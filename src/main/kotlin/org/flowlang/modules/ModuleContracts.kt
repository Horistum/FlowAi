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

/**
 * Closed type vocabulary for module system/action schemas.
 *
 * This is intentionally separate from Flow source input types and target-manifest
 * types. A module descriptor is a semantic contract, so accepting an unknown wire
 * string here would make every downstream type check fail open.
 */
enum class SchemaType(val wireName: String) {
    ANY("any"),
    TEXT("text"),
    NUMBER("number"),
    BOOLEAN("boolean"),
    LIST("list"),
    MAP("map"),
    SECRET("secret"),
    DURATION("duration"),
    ARTIFACT("artifact"),
    JSON("json"),
    YAML("yaml");

    override fun toString(): String = wireName

    companion object {
        private val byWireName: Map<String, SchemaType> = values().associateBy(SchemaType::wireName)
        val supportedWireNames: Set<String> = byWireName.keys.toSortedSet()

        fun fromWireName(value: String): SchemaType? = byWireName[value]
    }
}

/** Value categories shared by descriptor defaults and authored module values. */
enum class SchemaValueKind(val wireName: String) {
    NULL("null"),
    TEXT("text"),
    NUMBER("number"),
    BOOLEAN("boolean"),
    LIST("list"),
    MAP("map"),
    SECRET("secret"),
    DYNAMIC("dynamic")
}

/**
 * Single compatibility authority for module schema values.
 *
 * Frontends classify their concrete values into [SchemaValueKind]; this object is
 * the only place that decides whether the category satisfies a [SchemaType].
 */
object SchemaTypeCompatibility {
    fun accepts(type: SchemaType, kind: SchemaValueKind): Boolean = when (type) {
        SchemaType.ANY -> true
        SchemaType.TEXT -> kind in setOf(SchemaValueKind.TEXT, SchemaValueKind.SECRET, SchemaValueKind.DYNAMIC)
        SchemaType.NUMBER -> kind in setOf(SchemaValueKind.NUMBER, SchemaValueKind.DYNAMIC)
        SchemaType.BOOLEAN -> kind in setOf(SchemaValueKind.BOOLEAN, SchemaValueKind.DYNAMIC)
        SchemaType.LIST -> kind in setOf(SchemaValueKind.LIST, SchemaValueKind.DYNAMIC)
        SchemaType.MAP -> kind in setOf(SchemaValueKind.MAP, SchemaValueKind.DYNAMIC)
        SchemaType.SECRET -> kind == SchemaValueKind.SECRET
        SchemaType.DURATION -> kind in setOf(SchemaValueKind.TEXT, SchemaValueKind.DYNAMIC)
        SchemaType.ARTIFACT -> kind in setOf(SchemaValueKind.TEXT, SchemaValueKind.MAP, SchemaValueKind.DYNAMIC)
        SchemaType.JSON, SchemaType.YAML -> kind in setOf(
            SchemaValueKind.NULL,
            SchemaValueKind.TEXT,
            SchemaValueKind.NUMBER,
            SchemaValueKind.BOOLEAN,
            SchemaValueKind.LIST,
            SchemaValueKind.MAP,
            SchemaValueKind.DYNAMIC
        )
    }

    fun defaultValidationError(type: SchemaType, value: Any?): String? {
        if (!isRepresentableDefault(value)) {
            val kind = value?.javaClass?.name ?: "null"
            return "uses unsupported default value kind '$kind'"
        }
        val actual = defaultValueKind(value)
        return if (accepts(type, actual)) null
        else "expects schema type '${type.wireName}' but the default is '${actual.wireName}'"
    }

    fun defaultValueKind(value: Any?): SchemaValueKind = when (value) {
        null -> SchemaValueKind.NULL
        is String -> SchemaValueKind.TEXT
        is Number -> SchemaValueKind.NUMBER
        is Boolean -> SchemaValueKind.BOOLEAN
        is List<*> -> SchemaValueKind.LIST
        is Map<*, *> -> SchemaValueKind.MAP
        else -> throw IllegalArgumentException("Unsupported module schema default type '${value.javaClass.name}'.")
    }

    fun isRepresentableDefault(value: Any?): Boolean = when (value) {
        null, is String, is Number, is Boolean -> true
        is List<*> -> value.all(::isRepresentableDefault)
        is Map<*, *> -> value.entries.all { (key, item) -> key is String && isRepresentableDefault(item) }
        else -> false
    }
}

data class SchemaField(
    val type: SchemaType,
    val required: Boolean = false,
    val sensitive: Boolean = false,
    val defaultValue: Any? = null
) {
    constructor(
        type: String,
        required: Boolean = false,
        sensitive: Boolean = false,
        defaultValue: Any? = null
    ) : this(
        type = SchemaType.fromWireName(type)
            ?: throw IllegalArgumentException(
                "Unsupported module schema type '$type'. Supported types: ${SchemaType.supportedWireNames.joinToString()}."
            ),
        required = required,
        sensitive = sensitive,
        defaultValue = defaultValue
    )

    init {
        if (defaultValue != null) {
            val validationError = SchemaTypeCompatibility.defaultValidationError(type, defaultValue)
            require(validationError == null) { "Invalid module schema default: $validationError." }
        }
    }
}

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