import org.flowlang.adapters.continuity.AdapterContinuityScopedCapabilityResolver
import org.flowlang.capabilities.TargetProjectionRule
import org.flowlang.generators.manifest.TargetManifestGenerationPipeline
import org.flowlang.generators.manifest.TargetMaterializationResolution
import org.flowlang.generators.manifest.TargetMaterializationResolver
import org.flowlang.generators.manifest.TargetNativeProjectionCatalog
import org.flowlang.planner.TaskNode
import org.flowlang.capabilities.CompatibilityReport
import org.flowlang.capabilities.TargetCapability
import org.flowlang.compiler.CanonicalExecutionGraphGate
import org.flowlang.generators.manifest.TargetManifest
import org.flowlang.generators.manifest.TargetManifestGenerator
import org.flowlang.generators.manifest.TargetProjectionAuthorization
import org.flowlang.generators.manifest.TargetProjectionProvider
import org.flowlang.materialization.ExplicitTargetSelection
import org.flowlang.materialization.TargetDiagnosticMaterializationRequest
import org.flowlang.materialization.TargetMaterializationRequest
import org.flowlang.materialization.TargetSelectionAuthority
import org.flowlang.planner.ExecutionPlan
import org.flowlang.planner.PlanNode
import org.flowlang.planner.PlanTrigger
import org.flowlang.planner.PlannedWorkflowFailurePolicy
import org.flowlang.planner.TryPlanNode
import org.flowlang.planner.WorkflowFailureDisposition
import org.flowlang.planner.WorkflowFailureHandlerEntry
import org.flowlang.planner.WorkflowFailureHandlerRegion
import org.flowlang.planner.WorkflowFailurePolicy
import org.flowlang.topology.ExecutionTopologyMatchingAuthority
import org.flowlang.topology.ExecutionTopologyProfile

/**
 * Test-fixture migration helper. It may recognize the exact legacy mirror only
 * because tests deliberately start from an already projected ExecutionPlan.
 * Production code must enter through CompilationAuthorization and never infer
 * workflow failure meaning from node position, id or capability text.
 */
internal fun testWorkflowFailurePolicy(plan: ExecutionPlan): PlannedWorkflowFailurePolicy {
    val boundary = plan.nodes.lastOrNull() as? TryPlanNode
        ?: return PlannedWorkflowFailurePolicy.none()
    if (
        boundary.body.isNotEmpty() ||
        boundary.errorHandler.isEmpty() ||
        "errorHandlers.finally" !in plan.requiredCapabilities
    ) {
        return PlannedWorkflowFailurePolicy.none()
    }
    val authored = plan.sourceIntent?.workflows.orEmpty()
        .map { it.name }
        .filter(String::isNotBlank)
        .distinct()
    val triggered = plan.triggers.flatMap(PlanTrigger::workflows)
        .filter(String::isNotBlank)
        .filterNot { it == "main" && authored.isNotEmpty() }
        .distinct()
    val referenced = (authored + triggered).distinct()
    require(referenced.size <= 1) {
        "Test compatibility plan cannot reconstruct multiple workflow identities: ${referenced.joinToString()}."
    }
    val workflowName = referenced.singleOrNull() ?: "main"
    val handler = WorkflowFailureHandlerRegion(
        id = "workflow-failure:$workflowName",
        nodeIds = boundary.errorHandler.map(PlanNode::id),
        entry = WorkflowFailureHandlerEntry(
            errorBinding = "error",
            priorSuccessfulValuesAvailable = false
        )
    )
    return PlannedWorkflowFailurePolicy(
        policy = WorkflowFailurePolicy(WorkflowFailureDisposition.PROPAGATE, handler),
        handlerNodes = boundary.errorHandler,
        compatibilityBoundaryNodeId = boundary.id
    )
}

internal fun testCompatibilityAuthorization(
    plan: ExecutionPlan,
    evidenceId: String = "test:compatibility-plan"
) = CanonicalExecutionGraphGate.authorizeCompatibilityPlan(
    plannerPlan = plan,
    evidenceId = evidenceId,
    failurePolicy = testWorkflowFailurePolicy(plan)
)

internal fun TargetMaterializationResolver.resolve(
    task: TaskNode,
    targetName: String,
    projectionRules: List<TargetProjectionRule> = emptyList(),
    nativeProjections: TargetNativeProjectionCatalog = TargetNativeProjectionCatalog.empty(targetName)
): TargetMaterializationResolution {
    val canonicalTask = if (task.semanticCapability != null) task else task.copy(
        semanticCapability = when (task.module to task.action) {
            "shell" to "run" -> "manual.runtime.action"
            else -> "${task.module}.${task.action}".trim('.').ifBlank { "flow.action" }
        }
    )
    val authorization = testCompatibilityAuthorization(
        ExecutionPlan(flowName = "test-materialization-resolver", nodes = listOf(canonicalTask)),
        "test:materialization-resolver:${canonicalTask.id}"
    )
    return resolve(
        authorization = authorization,
        task = canonicalTask,
        targetName = targetName,
        projectionRules = projectionRules,
        nativeProjections = nativeProjections
    )
}

internal fun AdapterContinuityScopedCapabilityResolver.resolve(
    plan: ExecutionPlan,
    target: String,
    declared: TargetCapability
): TargetCapability = resolve(
    authorization = testCompatibilityAuthorization(plan, "test:continuity-capability:$target"),
    target = target,
    declared = declared
)

internal fun TargetManifestGenerationPipeline.effectiveTargets(
    plan: ExecutionPlan,
    target: String
): Map<String, TargetCapability> = effectiveTargets(
    authorization = testCompatibilityAuthorization(plan, "test:effective-targets:$target"),
    target = target
)

internal fun testTargetSelection(
    target: String,
    targets: Map<String, TargetCapability>,
    source: String = "test:explicit-target"
): ExplicitTargetSelection = TargetSelectionAuthority.fromTestFixture(target, source, targets)

internal fun testTargetSelection(
    target: String,
    source: String = "test:projection-fixture"
): ExplicitTargetSelection = testTargetSelection(
    target = target,
    targets = mapOf(target to TargetCapability(target, "Test projection fixture target.")),
    source = source
)

internal fun testMaterializationRequest(
    plan: ExecutionPlan,
    target: String,
    targets: Map<String, TargetCapability>,
    strict: Boolean = false,
    source: String = "test:materialization"
): TargetMaterializationRequest = TargetMaterializationRequest.fromCompatibilityPlan(
    plan = plan,
    selection = testTargetSelection(target, targets, source),
    strict = strict,
    evidenceId = source,
    failurePolicy = testWorkflowFailurePolicy(plan)
)

internal fun testDiagnosticMaterializationRequest(
    plan: ExecutionPlan,
    target: String,
    targets: Map<String, TargetCapability>,
    source: String = "test:diagnostic-materialization"
): TargetDiagnosticMaterializationRequest = TargetDiagnosticMaterializationRequest.fromCompatibilityPlan(
    plan = plan,
    selection = testTargetSelection(target, targets, source),
    evidenceId = source,
    failurePolicy = testWorkflowFailurePolicy(plan)
)

/** Low-level projection fixture support. Production callers cannot construct authorization. */
internal fun TargetManifestGenerator.generate(
    plan: ExecutionPlan,
    compatibility: CompatibilityReport,
    strict: Boolean = false
): TargetManifest {
    val authorization = testCompatibilityAuthorization(
        plan,
        "test:projection-generator:${compatibility.target}"
    )
    return generate(
        TargetProjectionAuthorization(
            compilationAuthorization = authorization,
            selection = testTargetSelection(compatibility.target),
            compatibility = compatibility,
            strict = strict,
            topology = ExecutionTopologyMatchingAuthority.assess(
                plan.topologyRequirements,
                ExecutionTopologyProfile.fullySupported(
                    compatibility.target,
                    "test:${compatibility.target}:topology"
                )
            )
        )
    )
}

/** Low-level provider fixture support. Production callers use TargetManifestGenerationPipeline. */
internal fun TargetProjectionProvider.generate(
    plan: ExecutionPlan,
    compatibility: CompatibilityReport,
    strict: Boolean = false
): TargetManifest {
    val authorization = testCompatibilityAuthorization(
        plan,
        "test:projection-provider:${compatibility.target}"
    )
    return generate(
        TargetProjectionAuthorization(
            compilationAuthorization = authorization,
            selection = testTargetSelection(compatibility.target),
            compatibility = compatibility,
            strict = strict,
            topology = ExecutionTopologyMatchingAuthority.assess(
                plan.topologyRequirements,
                ExecutionTopologyProfile.fullySupported(
                    compatibility.target,
                    "test:${compatibility.target}:topology"
                )
            )
        )
    )
}
