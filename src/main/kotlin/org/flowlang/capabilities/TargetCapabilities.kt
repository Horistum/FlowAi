package org.flowlang.capabilities

/**
 * Platform capability model.
 *
 * Flow must not pretend that every target platform can execute every Flow feature.
 * This model is intentionally independent from parser and module implementation:
 * it describes what a target can represent natively, partially, or not at all.
 */
data class TargetCapability(
    val target: String,
    val description: String,
    val sequentialTasks: SupportLevel = SupportLevel.SUPPORTED,
    val parallel: SupportLevel = SupportLevel.SUPPORTED,
    val conditions: SupportLevel = SupportLevel.SUPPORTED,
    val dynamicLoops: SupportLevel = SupportLevel.PARTIAL,
    val match: SupportLevel = SupportLevel.PARTIAL,
    val retry: SupportLevel = SupportLevel.PARTIAL,
    val approvals: SupportLevel = SupportLevel.PARTIAL,
    val errorHandlers: SupportLevel = SupportLevel.PARTIAL,
    val artifacts: SupportLevel = SupportLevel.PARTIAL,
    val secrets: SupportLevel = SupportLevel.PARTIAL,
    val nativeRuntime: SupportLevel = SupportLevel.PARTIAL,
    val notes: List<String> = emptyList(),
    val features: Map<String, SupportLevel> = emptyMap(),
    val expressionSupport: TargetExpressionSupportDeclaration? = null,
    val projectionRules: List<TargetProjectionRule> = emptyList()
) {
    fun feature(name: String, fallback: SupportLevel): SupportLevel = features[name] ?: fallback
}

enum class SupportLevel { SUPPORTED, PARTIAL, UNSUPPORTED, REQUIRES_RUNTIME }

enum class TargetProjectionMode { NATIVE, NOTES_PROJECTED, ADAPTER_REQUIRED, UNSUPPORTED, BLOCKED }

/**
 * Structured renderer payload template with an opaque projection-consumer kind.
 * Core validates the identifier structurally; concrete edge renderers own its
 * interpretation.
 */
data class TargetRendererPayloadTemplate(
    val kind: String,
    val reference: String,
    val parameters: Map<String, String> = emptyMap()
)

data class TargetProjectionRule(
    val module: String,
    val action: String,
    val mode: TargetProjectionMode,
    val reason: String,
    val evidenceReference: String,
    val payload: TargetRendererPayloadTemplate? = null
) {
    fun matches(module: String, action: String): Boolean = this.module == module && this.action == action
}
