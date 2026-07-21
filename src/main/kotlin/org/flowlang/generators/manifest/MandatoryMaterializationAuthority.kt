package org.flowlang.generators.manifest

import org.flowlang.capabilities.CompatibilityIssue
import org.flowlang.capabilities.CompatibilityLevel
import org.flowlang.capabilities.CompatibilityReport
import org.flowlang.capabilities.SupportLevel
import org.flowlang.capabilities.PlannerCapabilityConstraintGate
import org.flowlang.capabilities.TargetCapability
import org.flowlang.effects.CanonicalIntentEffectAuthority
import org.flowlang.effects.ModuleEffectCanonicalizer
import org.flowlang.effects.SemanticEffect
import org.flowlang.planner.ApprovalNode
import org.flowlang.planner.ConditionNode
import org.flowlang.planner.ControlNode
import org.flowlang.planner.DataOpNode
import org.flowlang.modules.ContinuityChannel
import org.flowlang.modules.ContinuityKind
import org.flowlang.modules.ModuleRegistry
import org.flowlang.planner.ExecutionPlan
import org.flowlang.planner.PlanDependencyEvidence
import org.flowlang.planner.PlanDependencyKind
import org.flowlang.planner.PlanDependencyRelation
import org.flowlang.planner.PlanDependencyRelations
import org.flowlang.planner.PlanDependencyResolution
import org.flowlang.planner.LoopNode
import org.flowlang.planner.MatchPlanNode
import org.flowlang.planner.ParallelGroupNode
import org.flowlang.planner.PlanNode
import org.flowlang.planner.RetryGroupNode
import org.flowlang.planner.TaskNode
import org.flowlang.planner.TryPlanNode
import org.flowlang.standard.FlowStandardVersions
import org.flowlang.intent.StandardCapability

/**
 * Evidence issued only by the canonical materialization authority.
 *
 * Concrete projection implementations may consume the validated plan and its
 * compatibility evidence, but callers cannot construct or substitute this
 * authorization. This keeps target implementations extensible without exposing
 * an alternate public bypass around planning validation.
 */
enum class TargetProjectionAuthorizationPurpose {
    EXECUTION_CANDIDATE,
    DIAGNOSTIC_EVIDENCE
}

class TargetProjectionAuthorization internal constructor(
    val plan: ExecutionPlan,
    val compatibility: CompatibilityReport,
    val strict: Boolean,
    val purpose: TargetProjectionAuthorizationPurpose = TargetProjectionAuthorizationPurpose.EXECUTION_CANDIDATE
) {
    val target: String get() = compatibility.target
}

data class PlanningEvidenceIssue(
    val code: String,
    val location: String,
    val message: String
)

class InvalidPlanningEvidenceException(
    val issues: List<PlanningEvidenceIssue>
) : IllegalArgumentException(
    "Execution plan is not valid for materialization: " +
        issues.joinToString { "${it.code} at ${it.location}: ${it.message}" }
)

/**
 * Single authority for the transition from an ExecutionPlan to target
 * projection authorization.
 *
 * It validates target-neutral plan integrity and derives compatibility from the
 * versioned target capability registry. Compatibility supplied by a caller is
 * never accepted as authorization evidence.
 */
class MandatoryMaterializationAuthority(
    private val targets: Map<String, TargetCapability>,
    private val modules: ModuleRegistry = ModuleRegistry()
) {
    private val capabilityGate = PlannerCapabilityConstraintGate(targets)

    init {
        require(targets.isNotEmpty()) {
            "Mandatory materialization authority requires a non-empty target capability registry."
        }
        targets.forEach { (id, target) ->
            require(id.isNotBlank()) { "Target capability registry contains a blank target id." }
            require(target.target == id) {
                "Target capability registry key '$id' does not match declared target '${target.target}'."
            }
        }
    }

    fun authorize(
        plan: ExecutionPlan,
        target: String,
        strict: Boolean = false
    ): TargetProjectionAuthorization {
        require(target.isNotBlank()) { "Materialization target must not be blank." }
        ExecutionPlanMaterializationValidator.requireValid(plan, modules)
        ExecutionPlanContinuityValidator.requireResolved(plan)
        val report = capabilityGate.requireProjectionAllowed(plan, target, strict)
        return TargetProjectionAuthorization(
            plan = plan,
            compatibility = report.compatibility,
            strict = strict,
            purpose = TargetProjectionAuthorizationPurpose.EXECUTION_CANDIDATE
        )
    }

    /**
     * Authorizes diagnostic artifact generation without pretending that an
     * unsupported target is executable. Structural planning evidence remains
     * mandatory and compatibility is still derived from the canonical target
     * registry; only the executable-capability gate is replaced by explicit
     * diagnostic intent.
     */
    fun authorizeDiagnosticEvidence(
        plan: ExecutionPlan,
        target: String
    ): TargetProjectionAuthorization {
        require(target.isNotBlank()) { "Diagnostic materialization target must not be blank." }
        ExecutionPlanMaterializationValidator.requireValid(plan, modules)
        val report = capabilityGate.check(plan, target, strict = false)
        val continuityIssues = ExecutionPlanContinuityValidator.blockers(plan).map { relation ->
            CompatibilityIssue(
                level = CompatibilityLevel.ERROR,
                target = target,
                nodeId = relation.targetNodeId,
                feature = "${relation.kind.capability}.planning",
                message = continuityMessage(relation)
            )
        }
        val compatibility = if (continuityIssues.isEmpty()) report.compatibility else report.compatibility.copy(
            status = SupportLevel.UNSUPPORTED,
            issues = (report.compatibility.issues + continuityIssues).distinct(),
            capabilityStatus = report.compatibility.capabilityStatus
        )
        return TargetProjectionAuthorization(
            plan = plan,
            compatibility = compatibility,
            strict = false,
            purpose = TargetProjectionAuthorizationPurpose.DIAGNOSTIC_EVIDENCE
        )
    }

    private fun continuityMessage(relation: PlanDependencyRelation): String = when (relation.resolution) {
        PlanDependencyResolution.UNRESOLVED ->
            "${relation.kind.name.lowercase()} continuity channel '${relation.channel}' has no proven provider for node '${relation.targetNodeId}'. Ordering alone is not continuity evidence."
        PlanDependencyResolution.AMBIGUOUS ->
            "${relation.kind.name.lowercase()} continuity channel '${relation.channel}' has multiple possible providers for node '${relation.targetNodeId}': ${relation.candidates.joinToString()}."
        PlanDependencyResolution.RESOLVED ->
            "Resolved continuity relation was unexpectedly reported as blocking."
    }
}

internal object ExecutionPlanMaterializationValidator {
    fun requireValid(plan: ExecutionPlan, modules: ModuleRegistry) {
        val issues = validate(plan, modules)
        if (issues.isNotEmpty()) throw InvalidPlanningEvidenceException(issues)
    }

    fun validate(plan: ExecutionPlan, modules: ModuleRegistry): List<PlanningEvidenceIssue> {
        val issues = mutableListOf<PlanningEvidenceIssue>()
        if (plan.flowName.isBlank()) {
            issues += issue("planning.flow-name.missing", "flowName", "Flow name must not be blank.")
        }
        if (plan.planVersion != FlowStandardVersions.EXECUTION_PLAN_VERSION) {
            issues += issue(
                "planning.plan-version.unsupported",
                "planVersion",
                "Expected execution plan version '${FlowStandardVersions.EXECUTION_PLAN_VERSION}', found '${plan.planVersion}'."
            )
        }

        val seenNodeIds = mutableSetOf<String>()
        validateNodes(plan.nodes, "nodes", seenNodeIds, issues)
        validateEffectEvidence(plan.nodes, "nodes", modules, issues)

        val duplicateInputs = plan.inputs.groupingBy { it.name }.eachCount().filterValues { it > 1 }.keys
        duplicateInputs.forEach { name ->
            issues += issue("planning.input.duplicate", "inputs.$name", "Plan input '$name' is declared more than once.")
        }
        plan.inputs.forEachIndexed { index, input ->
            if (input.name.isBlank()) issues += issue("planning.input.name.missing", "inputs[$index]", "Plan input name must not be blank.")
        }

        val duplicateTriggers = plan.triggers.groupingBy { it.id }.eachCount().filterValues { it > 1 }.keys
        duplicateTriggers.forEach { id ->
            issues += issue("planning.trigger.duplicate", "triggers.$id", "Plan trigger '$id' is declared more than once.")
        }
        plan.triggers.forEachIndexed { index, trigger ->
            if (trigger.id.isBlank()) issues += issue("planning.trigger.id.missing", "triggers[$index]", "Plan trigger id must not be blank.")
            if (trigger.type.isBlank()) issues += issue("planning.trigger.type.missing", "triggers[${trigger.id.ifBlank { index }}]", "Plan trigger type must not be blank.")
        }

        plan.requiredCapabilities.forEachIndexed { index, capability ->
            if (capability.isBlank()) {
                issues += issue("planning.capability.missing", "requiredCapabilities[$index]", "Required capability must not be blank.")
            }
        }
        validateDependencyRelations(plan, modules, seenNodeIds, issues)
        return issues
    }

    private fun validateDependencyRelations(
        plan: ExecutionPlan,
        modules: ModuleRegistry,
        nodeIds: Set<String>,
        issues: MutableList<PlanningEvidenceIssue>
    ) {
        val nodesById = PlanDependencyRelations.flatten(plan.nodes).associateBy { it.id }
        val duplicates = plan.dependencyRelations
            .groupBy(PlanDependencyRelations::relationKey)
            .filterValues { it.size > 1 }
        duplicates.keys.forEach { key ->
            issues += issue("planning.dependency.duplicate", "dependencyRelations", "Dependency relation is duplicated: ${key.joinToString()}.")
        }

        PlanDependencyRelations.flatten(plan.nodes).forEach { node ->
            PlanDependencyRelations.dependencies(node).forEach { source ->
                val represented = plan.dependencyRelations.any { relation ->
                    relation.kind == PlanDependencyKind.ORDERING &&
                        relation.sourceNodeId == source &&
                        relation.targetNodeId == node.id &&
                        relation.resolution == PlanDependencyResolution.RESOLVED
                }
                if (!represented) {
                    issues += issue(
                        "planning.ordering.evidence.missing",
                        "nodes.${node.id}.dependsOn",
                        "Ordering dependency '$source -> ${node.id}' has no explicit ordering relation."
                    )
                }
            }
        }

        plan.dependencyRelations.forEachIndexed { index, relation ->
            val location = "dependencyRelations[$index]"
            if (relation.targetNodeId !in nodeIds) {
                issues += issue("planning.dependency.target.unknown", location, "Dependency target '${relation.targetNodeId}' is not a plan node.")
            }
            relation.sourceNodeId?.let { source ->
                if (source !in nodeIds) {
                    issues += issue("planning.dependency.source.unknown", location, "Dependency source '$source' is not a plan node.")
                }
                if (source == relation.targetNodeId) {
                    issues += issue("planning.dependency.self", location, "Dependency relation cannot reference the same source and target node.")
                }
            }
            relation.candidates.filter { it !in nodeIds }.forEach { candidate ->
                issues += issue("planning.dependency.candidate.unknown", location, "Dependency candidate '$candidate' is not a plan node.")
            }

            if (relation.kind == PlanDependencyKind.ORDERING) {
                if (relation.channel != null) {
                    issues += issue("planning.ordering.channel.forbidden", location, "Ordering relations cannot declare a continuity channel.")
                }
                if (relation.resolution != PlanDependencyResolution.RESOLVED || relation.sourceNodeId == null) {
                    issues += issue("planning.ordering.unresolved", location, "Ordering relations must name one resolved source node.")
                }
            } else {
                if (relation.channel.isNullOrBlank()) {
                    issues += issue("planning.continuity.channel.missing", location, "Continuity relations must declare a non-blank channel.")
                }
                val capability = relation.kind.capability
                val targetTask = nodesById[relation.targetNodeId] as? TaskNode
                if (capability != null && targetTask != null && capability !in targetTask.requiredCapabilities) {
                    issues += issue(
                        "planning.continuity.capability.missing",
                        location,
                        "Target task '${targetTask.id}' must require capability '$capability'."
                    )
                }
            }

            when (relation.resolution) {
                PlanDependencyResolution.RESOLVED -> {
                    if (relation.sourceNodeId == null) {
                        issues += issue("planning.dependency.source.missing", location, "Resolved dependency relation must name a source node.")
                    }
                    validateResolvedPath(relation, nodesById, location, issues)
                }
                PlanDependencyResolution.UNRESOLVED -> {
                    if (relation.sourceNodeId != null || relation.candidates.isNotEmpty() || relation.path.isNotEmpty()) {
                        issues += issue("planning.continuity.unresolved.malformed", location, "Unresolved continuity must not fabricate a source, candidates or path.")
                    }
                }
                PlanDependencyResolution.AMBIGUOUS -> {
                    if (relation.sourceNodeId != null || relation.candidates.size < 2 || relation.path.isNotEmpty()) {
                        issues += issue("planning.continuity.ambiguous.malformed", location, "Ambiguous continuity must list at least two candidates and no selected source or path.")
                    }
                }
            }
        }

        nodesById.values.filterIsInstance<TaskNode>().forEach { task ->
            val continuity = modules.findAction(task.module, task.action)?.continuity ?: return@forEach
            continuity.requires.forEach { requirement ->
                val kind = requirement.kind.toPlanKind()
                val matches = plan.dependencyRelations.filter { relation ->
                    relation.targetNodeId == task.id &&
                        relation.kind == kind &&
                        relation.channel == requirement.name &&
                        relation.evidence == PlanDependencyEvidence.MODULE_CONTRACT
                }
                if (matches.size != 1) {
                    issues += issue(
                        "planning.continuity.requirement.evidence",
                        "nodes.${task.id}",
                        "Action '${task.module}.${task.action}' requires exactly one ${kind.name.lowercase()} continuity relation for channel '${requirement.name}', found ${matches.size}."
                    )
                } else if (matches.single().resolution == PlanDependencyResolution.RESOLVED) {
                    validateModuleContinuityPath(matches.single(), requirement, nodesById, modules, issues)
                }
            }
        }
    }

    private fun validateResolvedPath(
        relation: PlanDependencyRelation,
        nodesById: Map<String, PlanNode>,
        location: String,
        issues: MutableList<PlanningEvidenceIssue>
    ) {
        val source = relation.sourceNodeId ?: return
        if (relation.path.firstOrNull() != source || relation.path.lastOrNull() != relation.targetNodeId) {
            issues += issue("planning.dependency.path.invalid", location, "Resolved dependency path must start at '$source' and end at '${relation.targetNodeId}'.")
            return
        }
        if (relation.path.size < 2) {
            issues += issue("planning.dependency.path.short", location, "Resolved dependency path must contain source and target nodes.")
        }
        relation.path.zipWithNext().forEach { (upstream, downstream) ->
            val downstreamNode = nodesById[downstream]
            if (downstreamNode == null || upstream !in PlanDependencyRelations.dependencies(downstreamNode)) {
                issues += issue(
                    "planning.dependency.path.unordered",
                    location,
                    "Dependency path segment '$upstream -> $downstream' is not backed by an ordering dependency."
                )
            }
        }
    }

    private fun validateModuleContinuityPath(
        relation: PlanDependencyRelation,
        requirement: ContinuityChannel,
        nodesById: Map<String, PlanNode>,
        modules: ModuleRegistry,
        issues: MutableList<PlanningEvidenceIssue>
    ) {
        val path = relation.path
        val sourceTask = path.firstOrNull()?.let(nodesById::get) as? TaskNode
        val sourceContinuity = sourceTask?.let { modules.findAction(it.module, it.action)?.continuity }
        if (sourceContinuity == null || requirement !in sourceContinuity.provides) {
            issues += issue(
                "planning.continuity.provider.invalid",
                "dependencyRelations.${relation.targetNodeId}.${requirement.name}",
                "Resolved continuity source '${relation.sourceNodeId}' does not provide ${requirement.kind.name.lowercase()} channel '${requirement.name}'."
            )
        }
        path.drop(1).dropLast(1).forEach { nodeId ->
            val task = nodesById[nodeId] as? TaskNode
            val continuity = task?.let { modules.findAction(it.module, it.action)?.continuity }
            if (continuity == null || requirement !in continuity.preserves) {
                issues += issue(
                    "planning.continuity.preservation.invalid",
                    "dependencyRelations.${relation.targetNodeId}.${requirement.name}",
                    "Intermediate node '$nodeId' does not explicitly preserve ${requirement.kind.name.lowercase()} channel '${requirement.name}'."
                )
            }
        }
    }

    private fun validateEffectEvidence(
        nodes: List<PlanNode>,
        path: String,
        modules: ModuleRegistry,
        issues: MutableList<PlanningEvidenceIssue>
    ) {
        nodes.forEachIndexed { index, node ->
            val location = "$path[$index]"
            when (node) {
                is TaskNode -> validateTaskEffects(node, location, modules, issues)
                is DataOpNode -> validateCanonicalEffects(
                    semanticCapability = node.semanticCapability,
                    effectModel = node.effectModel,
                    legacyEffects = node.effects,
                    params = emptyMap(),
                    location = location,
                    issues = issues
                )
                is ConditionNode -> {
                    validateEffectEvidence(node.then, "$location.then", modules, issues)
                    validateEffectEvidence(node.otherwise, "$location.otherwise", modules, issues)
                }
                is LoopNode -> validateEffectEvidence(node.body, "$location.body", modules, issues)
                is ParallelGroupNode -> node.branches.forEachIndexed { branchIndex, branch ->
                    validateEffectEvidence(branch.steps, "$location.branches[$branchIndex]", modules, issues)
                }
                is MatchPlanNode -> {
                    node.cases.forEachIndexed { caseIndex, case ->
                        validateEffectEvidence(case.steps, "$location.cases[$caseIndex]", modules, issues)
                    }
                    validateEffectEvidence(node.errorCase, "$location.errorCase", modules, issues)
                    validateEffectEvidence(node.defaultSteps, "$location.default", modules, issues)
                }
                is RetryGroupNode -> validateEffectEvidence(node.body, "$location.body", modules, issues)
                is TryPlanNode -> {
                    validateEffectEvidence(node.body, "$location.body", modules, issues)
                    validateEffectEvidence(node.errorHandler, "$location.errorHandler", modules, issues)
                }
                is ApprovalNode, is ControlNode -> Unit
            }
        }
    }

    private fun validateTaskEffects(
        task: TaskNode,
        location: String,
        modules: ModuleRegistry,
        issues: MutableList<PlanningEvidenceIssue>
    ) {
        if (task.semanticCapability != null) {
            validateCanonicalEffects(
                semanticCapability = task.semanticCapability,
                effectModel = task.effectModel,
                legacyEffects = task.effects,
                params = task.params.ifEmpty { task.inputs },
                location = location,
                issues = issues
            )
            return
        }

        val contract = modules.findAction(task.module, task.action)
        if (contract != null) {
            val expected = ModuleEffectCanonicalizer.canonicalize(contract.effects)
            validateEffectList(expected, task.effectModel, task.effects, location, issues, "module contract")
        } else {
            validateEffectProjection(task.effectModel, task.effects, location, issues)
        }
    }

    private fun validateCanonicalEffects(
        semanticCapability: String?,
        effectModel: List<SemanticEffect>,
        legacyEffects: List<String>,
        params: Map<String, String>,
        location: String,
        issues: MutableList<PlanningEvidenceIssue>
    ) {
        if (semanticCapability.isNullOrBlank()) {
            if (effectModel.isNotEmpty() || legacyEffects.isNotEmpty()) {
                issues += issue(
                    "planning.effect.capability.missing",
                    "$location.semanticCapability",
                    "Canonical effect evidence must name its standard capability."
                )
            }
            return
        }

        val capability = runCatching { StandardCapability.valueOf(semanticCapability) }.getOrNull()
        if (capability == null) {
            issues += issue(
                "planning.effect.capability.unknown",
                "$location.semanticCapability",
                "Unknown canonical effect capability '$semanticCapability'."
            )
            return
        }
        val expected = CanonicalIntentEffectAuthority.effectsForRendered(capability, params)
        validateEffectList(expected, effectModel, legacyEffects, location, issues, "canonical intent")
    }

    private fun validateEffectList(
        expected: List<SemanticEffect>,
        actual: List<SemanticEffect>,
        legacyEffects: List<String>,
        location: String,
        issues: MutableList<PlanningEvidenceIssue>,
        authority: String
    ) {
        if (actual != expected) {
            issues += issue(
                "planning.effect.evidence.invalid",
                "$location.effectModel",
                "Effect evidence does not match the $authority authority. Expected $expected, found $actual."
            )
        }
        validateEffectProjection(actual, legacyEffects, location, issues)
        if (actual.distinct().size != actual.size) {
            issues += issue(
                "planning.effect.evidence.duplicate",
                "$location.effectModel",
                "Effect evidence must not contain duplicate entries."
            )
        }
    }

    private fun validateEffectProjection(
        effectModel: List<SemanticEffect>,
        legacyEffects: List<String>,
        location: String,
        issues: MutableList<PlanningEvidenceIssue>
    ) {
        val expectedProjection = effectModel.map(SemanticEffect::legacyIdentity).distinct()
        if (legacyEffects != expectedProjection) {
            issues += issue(
                "planning.effect.projection.invalid",
                "$location.effects",
                "Legacy effect projection must be derived from the typed effect model. Expected $expectedProjection, found $legacyEffects."
            )
        }
    }

    private fun validateNodes(
        nodes: List<PlanNode>,
        path: String,
        seenNodeIds: MutableSet<String>,
        issues: MutableList<PlanningEvidenceIssue>
    ) {
        nodes.forEachIndexed { index, node ->
            val location = "$path[$index]"
            if (node.id.isBlank()) {
                issues += issue("planning.node.id.missing", location, "Plan node id must not be blank.")
            } else if (!seenNodeIds.add(node.id)) {
                issues += issue("planning.node.id.duplicate", location, "Plan node id '${node.id}' is duplicated.")
            }

            when (node) {
                is TaskNode -> {
                    if (node.module.isBlank()) issues += issue("planning.task.module.missing", location, "Task module must not be blank.")
                    if (node.action.isBlank()) issues += issue("planning.task.action.missing", location, "Task action must not be blank.")
                }
                is ConditionNode -> {
                    if (node.condition.isBlank()) issues += issue("planning.condition.missing", location, "Condition expression must not be blank.")
                    validateNodes(node.then, "$location.then", seenNodeIds, issues)
                    validateNodes(node.otherwise, "$location.otherwise", seenNodeIds, issues)
                }
                is LoopNode -> {
                    if (node.item.isBlank()) issues += issue("planning.loop.item.missing", location, "Loop item must not be blank.")
                    if (node.source.isBlank()) issues += issue("planning.loop.source.missing", location, "Loop source must not be blank.")
                    validateNodes(node.body, "$location.body", seenNodeIds, issues)
                }
                is ParallelGroupNode -> node.branches.forEachIndexed { branchIndex, branch ->
                    validateNodes(branch.steps, "$location.branches[$branchIndex]", seenNodeIds, issues)
                }
                is MatchPlanNode -> {
                    if (node.source.isBlank()) issues += issue("planning.match.source.missing", location, "Match source must not be blank.")
                    node.cases.forEachIndexed { caseIndex, case ->
                        if (case.condition.isBlank()) issues += issue("planning.match.condition.missing", "$location.cases[$caseIndex]", "Match condition must not be blank.")
                        validateNodes(case.steps, "$location.cases[$caseIndex]", seenNodeIds, issues)
                    }
                    validateNodes(node.errorCase, "$location.errorCase", seenNodeIds, issues)
                    validateNodes(node.defaultSteps, "$location.default", seenNodeIds, issues)
                }
                is RetryGroupNode -> validateNodes(node.body, "$location.body", seenNodeIds, issues)
                is TryPlanNode -> {
                    validateNodes(node.body, "$location.body", seenNodeIds, issues)
                    validateNodes(node.errorHandler, "$location.errorHandler", seenNodeIds, issues)
                }
                is ApprovalNode, is DataOpNode, is ControlNode -> Unit
            }
        }
    }

    private fun issue(code: String, location: String, message: String) =
        PlanningEvidenceIssue(code = code, location = location, message = message)
}


class UnresolvedPlanningContinuityException(
    val relations: List<PlanDependencyRelation>
) : IllegalArgumentException(
    "Execution plan contains unresolved continuity evidence: " + relations.joinToString { relation ->
        "${relation.kind.name.lowercase()}:${relation.channel} at ${relation.targetNodeId} (${relation.resolution.name.lowercase()})"
    }
)

internal object ExecutionPlanContinuityValidator {
    fun blockers(plan: ExecutionPlan): List<PlanDependencyRelation> =
        plan.dependencyRelations.filter(PlanDependencyRelation::blocking)

    fun requireResolved(plan: ExecutionPlan) {
        val blockers = blockers(plan)
        if (blockers.isNotEmpty()) throw UnresolvedPlanningContinuityException(blockers)
    }
}

private fun ContinuityKind.toPlanKind(): PlanDependencyKind = when (this) {
    ContinuityKind.VALUE -> PlanDependencyKind.VALUE
    ContinuityKind.WORKSPACE -> PlanDependencyKind.WORKSPACE
    ContinuityKind.STATE -> PlanDependencyKind.STATE
}
