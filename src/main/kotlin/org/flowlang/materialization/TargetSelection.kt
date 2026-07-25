package org.flowlang.materialization

import org.flowlang.capabilities.TargetCapability
import org.flowlang.planner.ExecutionPlan
import org.flowlang.standard.FlowStandardVersions

/**
 * Auditable origin of an explicit target choice.
 *
 * The origin records where the choice entered Flow. It is evidence only; target
 * authorization is represented by [ExplicitTargetSelection] and can be issued
 * only by [TargetSelectionAuthority].
 */
enum class TargetSelectionOrigin {
    CLI_OPTION,
    INTENT_DECLARATION,
    EXPLICIT_CONFIGURATION
}

data class TargetSelectionEvidence(
    val target: String,
    val origin: TargetSelectionOrigin,
    val source: String
) {
    init {
        require(target.isNotBlank()) { "Target selection evidence must name a target." }
        require(source.isNotBlank()) { "Target selection evidence must name its source." }
    }
}

/**
 * Marker for a target choice validated against the active target registry.
 *
 * The only implementation is private to [TargetSelectionAuthority]. Callers can
 * consume an issued selection but cannot construct one from an arbitrary string.
 */
sealed interface ExplicitTargetSelection {
    val target: String
    val evidence: TargetSelectionEvidence
}

sealed interface TargetSelectionDecision {
    data object NotSelected : TargetSelectionDecision
    data class Selected(val selection: ExplicitTargetSelection) : TargetSelectionDecision
}

object TargetSelectionAuthority {
    private data class IssuedSelection(
        override val target: String,
        override val evidence: TargetSelectionEvidence
    ) : ExplicitTargetSelection

    fun fromCliOption(
        value: String?,
        targets: Map<String, TargetCapability>
    ): TargetSelectionDecision = value?.let {
        TargetSelectionDecision.Selected(issue(it, TargetSelectionOrigin.CLI_OPTION, "cli:--target", targets))
    } ?: TargetSelectionDecision.NotSelected

    fun fromIntentDeclaration(
        value: String,
        sourcePath: String,
        targets: Map<String, TargetCapability>
    ): ExplicitTargetSelection = issue(
        value = value,
        origin = TargetSelectionOrigin.INTENT_DECLARATION,
        source = sourcePath,
        targets = targets
    )

    fun fromExplicitConfiguration(
        value: String,
        source: String,
        targets: Map<String, TargetCapability>
    ): ExplicitTargetSelection = issue(
        value = value,
        origin = TargetSelectionOrigin.EXPLICIT_CONFIGURATION,
        source = source,
        targets = targets
    )

    fun requireSelected(
        decision: TargetSelectionDecision,
        operation: String
    ): ExplicitTargetSelection = when (decision) {
        TargetSelectionDecision.NotSelected -> throw MissingExplicitTargetSelectionException(operation)
        is TargetSelectionDecision.Selected -> decision.selection
    }

    private fun issue(
        value: String,
        origin: TargetSelectionOrigin,
        source: String,
        targets: Map<String, TargetCapability>
    ): ExplicitTargetSelection {
        require(targets.isNotEmpty()) { "Explicit target selection requires a non-empty target registry." }
        val normalized = value.trim()
        require(normalized.isNotEmpty()) { "Explicit target selection must not be blank." }
        val capability = targets[normalized]
            ?: throw UnknownExplicitTargetSelectionException(normalized, targets.keys.sorted())
        require(capability.target == normalized) {
            "Target registry key '$normalized' does not match declared target '${capability.target}'."
        }
        return IssuedSelection(
            target = normalized,
            evidence = TargetSelectionEvidence(normalized, origin, source)
        )
    }
}

class MissingExplicitTargetSelectionException(operation: String) : IllegalArgumentException(
    "$operation requires an explicit target selection. Flow never selects a target implicitly."
)

class UnknownExplicitTargetSelectionException(
    target: String,
    availableTargets: List<String>
) : IllegalArgumentException(
    "Unknown target '$target'. Available targets: ${availableTargets.joinToString().ifBlank { "none" }}."
)

data class TargetMaterializationRequest(
    val plan: ExecutionPlan,
    val selection: ExplicitTargetSelection,
    val strict: Boolean = false
) {
    val target: String get() = selection.target
}

data class TargetDiagnosticMaterializationRequest(
    val plan: ExecutionPlan,
    val selection: ExplicitTargetSelection
) {
    val target: String get() = selection.target
}

data class TargetSelectionEvidenceReport(
    val standardVersion: String = FlowStandardVersions.FLOW_STANDARD_VERSION,
    val planVersion: String,
    val flowName: String,
    val target: String,
    val origin: TargetSelectionOrigin,
    val source: String,
    val explicit: Boolean = true
) {
    companion object {
        fun from(plan: ExecutionPlan, selection: ExplicitTargetSelection): TargetSelectionEvidenceReport =
            TargetSelectionEvidenceReport(
                planVersion = plan.planVersion,
                flowName = plan.flowName,
                target = selection.target,
                origin = selection.evidence.origin,
                source = selection.evidence.source
            )
    }
}
