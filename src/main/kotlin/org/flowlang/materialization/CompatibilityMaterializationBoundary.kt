package org.flowlang.materialization

import org.flowlang.capabilities.TargetCapability
import org.flowlang.planner.ExecutionPlan
import org.flowlang.planner.PlannedWorkflowFailurePolicy

/**
 * Inventoried AR-03C bridge for pre-compiler evidence consumers; removal: AR-07.
 *
 * These factories issue requests, not projection authorizations. The untouched
 * plan still crosses planning validation, graph integrity, capability, control
 * and topology gates before a concrete adapter receives any authorization.
 * Product frontends must use the CompilationUnit factories instead.
 */
@Deprecated("Evidence-only compatibility boundary; migrate to CompilationUnit before AR-07")
object CompatibilityMaterializationBoundary {
    fun executionRequest(
        plan: ExecutionPlan,
        selection: ExplicitTargetSelection,
        strict: Boolean = false,
        evidenceId: String = selection.evidence.source,
        failurePolicy: PlannedWorkflowFailurePolicy = PlannedWorkflowFailurePolicy.none()
    ): TargetMaterializationRequest = TargetMaterializationRequest.fromCompatibilityPlan(
        plan, selection, strict, evidenceId, failurePolicy
    )

    fun diagnosticRequest(
        plan: ExecutionPlan,
        selection: ExplicitTargetSelection,
        evidenceId: String = selection.evidence.source,
        failurePolicy: PlannedWorkflowFailurePolicy = PlannedWorkflowFailurePolicy.none()
    ): TargetDiagnosticMaterializationRequest = TargetDiagnosticMaterializationRequest.fromCompatibilityPlan(
        plan, selection, evidenceId, failurePolicy
    )

    fun conformanceSelection(
        value: String,
        checkId: String,
        targets: Map<String, TargetCapability>
    ): ExplicitTargetSelection = TargetSelectionAuthority.fromConformanceCheck(value, checkId, targets)

    fun legacySelection(
        value: String,
        source: String,
        targets: Map<String, TargetCapability>
    ): ExplicitTargetSelection = TargetSelectionAuthority.fromExplicitConfiguration(value, source, targets)
}
