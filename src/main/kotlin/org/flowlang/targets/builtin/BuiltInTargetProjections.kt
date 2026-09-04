package org.flowlang.targets.builtin

import java.io.File
import org.flowlang.adapters.continuity.AdapterContinuityProjectionExecutionGate
import org.flowlang.capabilities.TargetCapability
import org.flowlang.capabilities.TargetProjectionRule
import org.flowlang.compiler.AuthorizedWorkflowFailureProjection
import org.flowlang.compiler.CompilationAuthorization
import org.flowlang.compiler.requireSingleWorkflowFailureProjection
import org.flowlang.generators.manifest.ReconciledTargetManifestGenerator
import org.flowlang.generators.manifest.TargetJob
import org.flowlang.generators.manifest.TargetManifest
import org.flowlang.generators.manifest.TargetManifestGenerationPipeline
import org.flowlang.generators.manifest.TargetMappingNote
import org.flowlang.generators.manifest.TargetNativeProjectionCatalog
import org.flowlang.generators.manifest.TargetProjectionProvider
import org.flowlang.generators.manifest.TargetProjectionAuthorization
import org.flowlang.generators.manifest.TargetProjectionRegistry
import org.flowlang.generators.manifest.TargetStep
import org.flowlang.generators.manifest.TargetStructuralProjectionKind
import org.flowlang.generators.manifest.baseMetadata
import org.flowlang.generators.manifest.combineConditions
import org.flowlang.generators.manifest.emptyProjectionStep
import org.flowlang.generators.manifest.mapOfNotNull
import org.flowlang.generators.manifest.sanitizeId
import org.flowlang.generators.manifest.toMappingNotes
import org.flowlang.generators.manifest.toTargetInput
import org.flowlang.generators.manifest.toTargetStep
import org.flowlang.generators.manifest.toTargetSteps
import org.flowlang.generators.manifest.toTargetTrigger
import org.flowlang.planner.ApprovalNode
import org.flowlang.planner.ConditionNode
import org.flowlang.planner.ControlNode
import org.flowlang.planner.DataOpNode
import org.flowlang.planner.LoopNode
import org.flowlang.planner.MatchPlanNode
import org.flowlang.planner.ParallelGroupNode
import org.flowlang.planner.PlanNode
import org.flowlang.planner.RetryGroupNode
import org.flowlang.planner.TaskNode
import org.flowlang.planner.TryPlanNode

/** Explicit composition root for projection implementations shipped with this distribution. */
object BuiltInTargetProjections {
    val registry: TargetProjectionRegistry = TargetProjectionRegistry.of(
        TargetProjectionProvider(JenkinsManifestGenerator(), JenkinsManifestRenderer()),
        TargetProjectionProvider(GitHubActionsManifestGenerator(), GitHubActionsManifestRenderer()),
        TargetProjectionProvider(TektonManifestGenerator(), TektonManifestRenderer())
    )

    fun pipeline(
        targets: Map<String, TargetCapability>,
        rootDir: File = File(".")
    ): TargetManifestGenerationPipeline = TargetManifestGenerationPipeline(
        targets = targets,
        projections = registry,
        executionGates = listOf(
            AdapterContinuityProjectionExecutionGate(rootDir, targets, registry)
        )
    )
}

class JenkinsManifestGenerator(
    override val nativeProjectionCatalog: TargetNativeProjectionCatalog = BuiltInNativeProjectionCatalogs.jenkins
) : ReconciledTargetManifestGenerator() {
    override val target: String = "jenkins"

    override fun buildManifest(authorization: TargetProjectionAuthorization): TargetManifest {
        val plan = authorization.plan
        val compatibility = authorization.compatibility
        val failureProjection = authorization.compilationAuthorization
            .requireSingleWorkflowFailureProjection()
        val steps = failureProjection.toJenkinsTargetSteps(
            authorization.compilationAuthorization,
            target,
            compatibility.projectionRules,
            nativeProjectionCatalog
        )
        return TargetManifest(
            target = target,
            flowName = plan.flowName,
            compatibility = compatibility,
            inputs = plan.inputs.map { it.toTargetInput() },
            triggers = plan.triggers.map { it.toTargetTrigger() },
            jobs = listOf(TargetJob(id = sanitizeId(plan.flowName), name = plan.flowName, steps = steps)),
            mappingNotes = compatibility.toMappingNotes(target),
            metadata = baseMetadata(plan, "JenkinsManifestGenerator") + ("nodePreserving" to "true")
        )
    }
}

class GitHubActionsManifestGenerator(
    override val nativeProjectionCatalog: TargetNativeProjectionCatalog = BuiltInNativeProjectionCatalogs.githubActions
) : ReconciledTargetManifestGenerator() {
    override val target: String = "github-actions"

    override fun buildManifest(authorization: TargetProjectionAuthorization): TargetManifest {
        val plan = authorization.plan
        val compatibility = authorization.compatibility
        val jobs = mutableListOf<TargetJob>()
        authorization.compilationAuthorization.requireSingleWorkflowFailureProjection()
            .toTargetJobs(
                authorization.compilationAuthorization,
                jobs,
                targetName = target,
                projectionRules = compatibility.projectionRules,
                nativeProjections = nativeProjectionCatalog
            )
        val baseJobs = jobs.ifEmpty {
            listOf(
                TargetJob(
                    id = sanitizeId(plan.flowName),
                    name = plan.flowName,
                    steps = listOf(emptyProjectionStep(plan.flowName))
                )
            )
        }
        val materializedJobs = GitHubActionsWorkspaceContinuityPlanner.materialize(plan, baseJobs)
        return TargetManifest(
            target = target,
            flowName = plan.flowName,
            compatibility = compatibility,
            inputs = plan.inputs.map { it.toTargetInput() },
            triggers = plan.triggers.map { it.toTargetTrigger() },
            jobs = materializedJobs,
            mappingNotes = compatibility.toMappingNotes(target),
            metadata = baseMetadata(plan, "GitHubActionsManifestGenerator") + mapOf(
                "jobPerTask" to "true",
                "workspaceContinuityMechanism" to "workflow-artifact-transfer",
                "workspaceContinuityTransferCount" to
                    GitHubActionsWorkspaceContinuityPlanner.transferCount(plan).toString()
            )
        )
    }
}

class TektonManifestGenerator(
    override val nativeProjectionCatalog: TargetNativeProjectionCatalog = BuiltInNativeProjectionCatalogs.tekton
) : ReconciledTargetManifestGenerator() {
    override val target: String = "tekton"

    override fun buildManifest(authorization: TargetProjectionAuthorization): TargetManifest {
        val plan = authorization.plan
        val compatibility = authorization.compatibility
        val jobs = mutableListOf<TargetJob>()
        authorization.compilationAuthorization.requireSingleWorkflowFailureProjection()
            .toTargetJobs(
                authorization.compilationAuthorization,
                jobs,
                targetName = target,
                projectionRules = compatibility.projectionRules,
                nativeProjections = nativeProjectionCatalog
            )
        val resolvedJobs = jobs.ifEmpty {
            listOf(TargetJob(
                id = sanitizeId(plan.flowName),
                name = plan.flowName,
                steps = listOf(emptyProjectionStep(plan.flowName))
            ))
        }
        val inputs = plan.inputs.map { it.toTargetInput() }
        val partialNote = TargetMappingNote(
            "warning",
            target,
            "manifest",
            "target.partial",
            "Tekton renderer is a partial generator and requires notes-driven target projection before production use."
        )
        val untranslatableConditionNotes = resolvedJobs.mapNotNull { job ->
            val condition = job.metadata["condition"]
            if (
                condition != null &&
                TargetExpressionTranslator.tektonWhen(condition, inputs, compatibility.expressionSupport) == null
            ) {
                TargetMappingNote(
                    level = "error",
                    target = target,
                    nodeId = job.id,
                    feature = "condition.unsupported",
                    message = "Flow condition cannot be enforced as a native Tekton 'when' guard; without resolution the task would run unconditionally. Use a supported condition shape or an explicit target note before production use: $condition"
                )
            } else {
                null
            }
        }
        return TargetManifest(
            target = target,
            flowName = plan.flowName,
            compatibility = compatibility,
            inputs = inputs,
            jobs = resolvedJobs,
            mappingNotes = compatibility.toMappingNotes(target) + partialNote + untranslatableConditionNotes,
            metadata = baseMetadata(plan, "TektonManifestGenerator") + mapOf(
                "supportLevel" to "partial",
                "jobPerTask" to "true"
            )
        )
    }
}

private fun AuthorizedWorkflowFailureProjection.toJenkinsTargetSteps(
    authorization: CompilationAuthorization,
    targetName: String,
    projectionRules: List<TargetProjectionRule>,
    nativeProjections: TargetNativeProjectionCatalog
): List<TargetStep> {
    val handler = policy.handler ?: return normalNodes.flatMap {
        it.toTargetSteps(authorization, targetName, projectionRules, nativeProjections)
    }
    val bodyStep = TargetStep(
        id = sanitizeId("${handler.id}_body"),
        name = "${handler.id} body",
        type = "try-body",
        children = normalNodes.flatMap {
            it.toTargetSteps(authorization, targetName, projectionRules, nativeProjections)
        },
        metadata = mapOf(
            "sourceNodeKind" to "WorkflowFailurePolicy",
            "tryRole" to "body"
        )
    )
    val handlerStep = TargetStep(
        id = sanitizeId("${handler.id}_handler"),
        name = "${handler.id} error handler",
        type = "error-handler",
        children = handlerNodes.flatMap {
            it.toTargetSteps(authorization, targetName, projectionRules, nativeProjections)
        },
        metadata = mapOf(
            "sourceNodeKind" to "WorkflowFailurePolicy",
            "tryRole" to "errorHandler"
        )
    )
    val stepId = sanitizeId(handler.id)
    val structural = nativeProjections.resolveStructure(
        TargetStructuralProjectionKind.ERROR_BOUNDARY,
        stepId
    )
    return listOf(
        TargetStep(
            id = stepId,
            name = handler.id,
            type = TargetStructuralProjectionKind.ERROR_BOUNDARY.stepType,
            children = listOf(bodyStep, handlerStep),
            materialization = structural.materialization,
            rendererPayload = structural.rendererPayload,
            metadata = mapOf(
                "sourceNodeKind" to "WorkflowFailurePolicy",
                "flowLevelErrorBoundary" to "true",
                "workflowFailurePolicy" to "true",
                "workflowFailureDisposition" to policy.disposition.name,
                "workflowFailureErrorBinding" to handler.entry.errorBinding,
                "workflowFailurePriorSuccessfulValuesAvailable" to
                    handler.entry.priorSuccessfulValuesAvailable.toString(),
                "errorHandlerCount" to handlerNodes.size.toString()
            )
        )
    )
}

private fun AuthorizedWorkflowFailureProjection.toTargetJobs(
    authorization: CompilationAuthorization,
    out: MutableList<TargetJob>,
    targetName: String,
    projectionRules: List<TargetProjectionRule>,
    nativeProjections: TargetNativeProjectionCatalog
) {
    normalNodes.forEach { node ->
        node.toTargetJobs(
            authorization,
            out,
            condition = null,
            targetName = targetName,
            projectionRules = projectionRules,
            nativeProjections = nativeProjections
        )
    }
    val handler = policy.handler ?: return
    val guardDependencies = out.map(TargetJob::id)
    val handlerJobs = mutableListOf<TargetJob>()
    handlerNodes.forEach { node ->
        node.toTargetJobs(
            authorization,
            handlerJobs,
            condition = null,
            targetName = targetName,
            projectionRules = projectionRules,
            nativeProjections = nativeProjections
        )
    }
    out += handlerJobs.map { job ->
        job.copy(
            dependsOn = (job.dependsOn + guardDependencies).distinct(),
            metadata = job.metadata + mapOf(
                "errorHandler" to "true",
                "workflowFailurePolicy" to "true",
                "workflowFailureDisposition" to policy.disposition.name,
                "workflowFailureErrorBinding" to handler.entry.errorBinding,
                "workflowFailurePriorSuccessfulValuesAvailable" to
                    handler.entry.priorSuccessfulValuesAvailable.toString()
            )
        )
    }
}

private fun PlanNode.toTargetJobs(
    authorization: CompilationAuthorization,
    out: MutableList<TargetJob>,
    condition: String?,
    targetName: String,
    projectionRules: List<TargetProjectionRule>,
    nativeProjections: TargetNativeProjectionCatalog
) {
    when (this) {
        is TaskNode -> {
            val step = toTargetStep(authorization, targetName, projectionRules, nativeProjections).let {
                if (condition != null) it.copy(metadata = it.metadata + ("condition" to condition)) else it
            }
            out += TargetJob(
                id = sanitizeId(id),
                name = id,
                dependsOn = dependsOn.map(::sanitizeId),
                steps = listOf(step),
                metadata = mapOfNotNull("condition" to condition)
            )
        }
        is ApprovalNode -> {
            val step = toTargetSteps(authorization, targetName, projectionRules, nativeProjections).single().let {
                if (condition != null) it.copy(metadata = it.metadata + ("condition" to condition)) else it
            }
            out += TargetJob(
                id = sanitizeId(id),
                name = id,
                dependsOn = dependsOn.map(::sanitizeId),
                steps = listOf(step),
                metadata = mapOfNotNull(
                    "condition" to condition,
                    "semanticControl" to "approval",
                    "providerApprovalPayload" to (step.rendererPayload != null).toString()
                )
            )
        }
        is ConditionNode -> {
            then.forEach {
                it.toTargetJobs(authorization, out, combineConditions(condition, this.condition), targetName, projectionRules, nativeProjections)
            }
            otherwise.forEach {
                it.toTargetJobs(authorization, out, combineConditions(condition, "not (${this.condition})"), targetName, projectionRules, nativeProjections)
            }
        }
        is ParallelGroupNode -> branches.flatMap { it.steps }
            .forEach { it.toTargetJobs(authorization, out, condition, targetName, projectionRules, nativeProjections) }
        is RetryGroupNode -> body.forEach { it.toTargetJobs(authorization, out, condition, targetName, projectionRules, nativeProjections) }
        is TryPlanNode -> {
            val previousJobIds = out.map { it.id }
            val bodyJobs = mutableListOf<TargetJob>()
            body.forEach { it.toTargetJobs(authorization, bodyJobs, condition, targetName, projectionRules, nativeProjections) }
            out += bodyJobs
            val guardDependencies = bodyJobs.map { it.id }.ifEmpty { previousJobIds }
            val handlerJobs = mutableListOf<TargetJob>()
            errorHandler.forEach { it.toTargetJobs(authorization, handlerJobs, condition, targetName, projectionRules, nativeProjections) }
            out += handlerJobs.map { job ->
                job.copy(
                    dependsOn = (job.dependsOn + guardDependencies).distinct(),
                    metadata = job.metadata + ("errorHandler" to "true")
                )
            }
        }
        is LoopNode -> out += TargetJob(
            id = sanitizeId(id),
            name = id,
            steps = toTargetSteps(authorization, targetName, projectionRules, nativeProjections),
            metadata = mapOfNotNull("condition" to condition, "supportLevel" to "partial")
        )
        is MatchPlanNode -> out += TargetJob(
            id = sanitizeId(id),
            name = id,
            steps = toTargetSteps(authorization, targetName, projectionRules, nativeProjections),
            metadata = mapOfNotNull("condition" to condition, "supportLevel" to "partial")
        )
        is DataOpNode, is ControlNode -> out += TargetJob(
            id = sanitizeId(id),
            name = id,
            steps = toTargetSteps(authorization, targetName, projectionRules, nativeProjections),
            metadata = mapOfNotNull("condition" to condition)
        )
    }
}
