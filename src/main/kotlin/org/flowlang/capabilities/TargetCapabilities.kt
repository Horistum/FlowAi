package org.flowlang.capabilities

import org.flowlang.projection.ProjectionBinding
import org.flowlang.topology.ExecutionTopologyProfile

/**
 * Platform capability model.
 *
 * Flow must not pretend that every target platform can execute every Flow feature.
 * This model is intentionally independent from parser and module implementation:
 * it describes what a target can represent natively, partially, or not at all.
 *
 * Missing declarations fail closed. A target becomes supported only through
 * explicit registry and provider evidence; constructor defaults must never promote
 * an omitted capability merely because a platform is generally programmable.
 */
data class TargetCapability(
    val target: String,
    val description: String,
    val sequentialTasks: SupportLevel = SupportLevel.UNSUPPORTED,
    val parallel: SupportLevel = SupportLevel.UNSUPPORTED,
    val conditions: SupportLevel = SupportLevel.UNSUPPORTED,
    val dynamicLoops: SupportLevel = SupportLevel.UNSUPPORTED,
    val match: SupportLevel = SupportLevel.UNSUPPORTED,
    val retry: SupportLevel = SupportLevel.UNSUPPORTED,
    val approvals: SupportLevel = SupportLevel.UNSUPPORTED,
    val errorHandlers: SupportLevel = SupportLevel.UNSUPPORTED,
    val artifacts: SupportLevel = SupportLevel.UNSUPPORTED,
    val secrets: SupportLevel = SupportLevel.UNSUPPORTED,
    val nativeRuntime: SupportLevel = SupportLevel.UNSUPPORTED,
    val notes: List<String> = emptyList(),
    val features: Map<String, SupportLevel> = emptyMap(),
    val expressionSupport: TargetExpressionSupportDeclaration? = null,
    val projectionRules: List<TargetProjectionRule> = emptyList(),
    val topologyProfile: ExecutionTopologyProfile? = null
) {
    fun feature(name: String, fallback: SupportLevel): SupportLevel = features[name] ?: fallback
}

enum class SupportLevel { SUPPORTED, PARTIAL, UNSUPPORTED, REQUIRES_RUNTIME }

enum class TargetProjectionMode { NATIVE, NOTES_PROJECTED, ADAPTER_REQUIRED, UNSUPPORTED, BLOCKED }

/**
 * Generic source-compatible name for an opaque projection-consumer identifier.
 * It is a String alias, not an enum or a registry of known target platforms.
 */
typealias TargetRendererPayloadKind = String

/**
 * Structured renderer payload template with target-neutral typed bindings.
 * Core validates binding structure; concrete edge renderers own target syntax.
 */
data class TargetRendererPayloadTemplate(
    val kind: TargetRendererPayloadKind,
    val reference: String,
    val bindings: Map<String, ProjectionBinding> = emptyMap()
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
