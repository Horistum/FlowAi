package org.flowlang.adapters.testing

import org.flowlang.capabilities.CompatibilityReport
import org.flowlang.capabilities.TargetCapability
import org.flowlang.capabilities.TargetProjectionRule
import org.flowlang.compiler.CompilationAuthorization
import org.flowlang.generators.manifest.*
import org.flowlang.materialization.*
import org.flowlang.modules.ModuleRegistry
import org.flowlang.planner.ExecutionPlan
import org.flowlang.planner.PlannedWorkflowFailurePolicy
import org.flowlang.planner.TaskNode
import org.flowlang.topology.ExecutionTopologyAssessment

/** White-box migration API. This artifact is forbidden on every production classpath. */
object AdapterRuntimeTestFixtures {
    fun fromTestFixture(value: String, fixtureId: String, targets: Map<String, TargetCapability>): ExplicitTargetSelection =
        TargetSelectionAuthority.fromTestFixture(value, fixtureId, targets)

    fun fromExplicitConfiguration(value: String, source: String, targets: Map<String, TargetCapability>): ExplicitTargetSelection =
        TargetSelectionAuthority.fromExplicitConfiguration(value, source, targets)

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

    fun projectionAuthorization(
        compilationAuthorization: CompilationAuthorization,
        selection: ExplicitTargetSelection,
        compatibility: CompatibilityReport,
        strict: Boolean,
        topology: ExecutionTopologyAssessment,
        purpose: TargetProjectionAuthorizationPurpose = TargetProjectionAuthorizationPurpose.EXECUTION_CANDIDATE
    ): TargetProjectionAuthorization = TargetProjectionAuthorization(
        compilationAuthorization, selection, compatibility, strict, topology, purpose
    )
}

/** Test-only snapshot of a private materialization result, including deliberate mutation support. */
data class MaterializationResolutionFixture(
    val obligationGraph: org.flowlang.obligations.ArchitectureObligationGraph,
    val notesPackages: List<org.flowlang.notes.NotesPackageContract>,
    val negotiation: MaterializationNegotiation,
    val projectionPlan: org.flowlang.projection.TargetProjectionPlan,
    val artifact: org.flowlang.projection.TargetProjectionArtifact,
    val materialization: TargetMaterialization,
    val rendererPayload: TargetRendererPayload? = null
) {
    fun mappingNote(targetName: String, taskId: String): TargetMappingNote = production().mappingNote(targetName, taskId)

    internal fun production(): TargetMaterializationResolution = TargetMaterializationResolution(
        obligationGraph, notesPackages, negotiation, projectionPlan, artifact, materialization, rendererPayload
    )
}

object MaterializationResolverFixture {
    fun resolve(
        authorization: CompilationAuthorization,
        task: TaskNode,
        targetName: String,
        projectionRules: List<TargetProjectionRule> = emptyList(),
        nativeProjections: TargetNativeProjectionCatalog = TargetNativeProjectionCatalog.empty(targetName)
    ): MaterializationResolutionFixture {
        val result = TargetMaterializationResolver.resolve(authorization, task, targetName, projectionRules, nativeProjections)
        return MaterializationResolutionFixture(
            result.obligationGraph, result.notesPackages, result.negotiation, result.projectionPlan,
            result.artifact, result.materialization, result.rendererPayload
        )
    }
}

object MaterializationEvidenceFixture {
    fun requireValid(resolution: MaterializationResolutionFixture): MaterializationResolutionFixture {
        TargetMaterializationEvidenceAuthority.requireValid(resolution.production())
        return resolution
    }
}

object PlanningEvidenceValidatorFixture {
    fun validate(plan: ExecutionPlan, modules: ModuleRegistry): List<PlanningEvidenceIssue> =
        ExecutionPlanMaterializationValidator.validate(plan, modules)
    fun requireValid(plan: ExecutionPlan, modules: ModuleRegistry) =
        ExecutionPlanMaterializationValidator.requireValid(plan, modules)
}
