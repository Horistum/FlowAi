package org.flowlang.lowering

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import org.flowlang.intent.IntentDocument
import org.flowlang.intent.IntentStep
import org.flowlang.intent.IntentSystemTypeAuthority
import org.flowlang.intent.IntentValue
import org.flowlang.intent.asTextOrNull
import org.flowlang.planner.ApprovalNode
import org.flowlang.planner.ExecutionPlan
import org.flowlang.planner.ExpressionRenderer
import org.flowlang.planner.PlanDependencyEvidence
import org.flowlang.planner.PlanDependencyKind
import org.flowlang.planner.PlanDependencyRelations
import org.flowlang.planner.PlanDependencyResolution
import org.flowlang.planner.PlanInput
import org.flowlang.planner.PlanNode
import org.flowlang.planner.PlanTrigger
import org.flowlang.planner.RuntimeParamRenderer
import org.flowlang.planner.TaskNode
import org.flowlang.standard.FlowStandardVersions

/**
 * Artifact-derived lowering authority.
 *
 * Source metadata records what each accepted field is expected to become. The
 * final report is issued only after an ExecutionPlan exists. Every target identity
 * is dereferenced against that plan, its value is hashed, and the result must match
 * the source-side expectation. Materialization can therefore reproduce the report
 * and reject stale, forged, missing or value-inconsistent evidence.
 */
object IntentLoweringAuthority {
    private val bindingMetadataParams = setOf("system", "tool", "engine")

    fun canonicalSystemType(type: String): String = IntentSystemTypeAuthority.bindingType(type)

    fun sourceMetadata(intent: IntentDocument, expressions: IntentExpressionParser): IntentSourceMetadata {
        val inputNames = intent.inputs.map { it.name }.toSet()
        val systems = intent.systems.map { system ->
            IntentSystemMetadata(
                name = system.name,
                sourceType = system.type,
                canonicalType = canonicalSystemType(system.type),
                purpose = system.purpose,
                config = system.config.toSortedMap().mapValues { (_, value) ->
                    RuntimeParamRenderer.render(IntentValueExpressionLowering.lower(value, expressions), inputNames)
                }
            )
        }
        val workflows = intent.workflows.map { workflow ->
            IntentWorkflowMetadata(workflow.name, workflow.kind.name, workflow.steps.map { it.id })
        }
        val policies = intent.policies.map { policy ->
            IntentPolicyMetadata(policy.name, policy.type.name, policy.condition, policy.message)
        }
        val fields = sourceFields(intent, systems, policies, inputNames, expressions).sortedBy(IntentSourceField::identity)

        require(fields.map { it.identity }.distinct().size == fields.size) {
            "Intent lowering source identities must be unique."
        }
        require(fields.map { it.targetIdentity }.distinct().size == fields.size) {
            "Intent lowering target identities must be unique."
        }

        return IntentSourceMetadata(
            description = intent.description,
            workflows = workflows,
            policies = policies,
            systems = systems,
            systemPurposes = intent.systems.mapNotNull { system ->
                system.purpose?.let { system.name to it }
            }.toMap(),
            failure = IntentFailureMetadata(
                notify = intent.failure.notify,
                rollback = intent.failure.rollback,
                stopOnError = intent.failure.stopOnError
            ),
            fields = fields
        )
    }

    fun report(plan: ExecutionPlan): IntentLoweringReport = report(
        LoweringPlanView(
            flowName = plan.flowName,
            inputs = plan.inputs,
            triggers = plan.triggers,
            sourceIntent = requireNotNull(plan.sourceIntent) {
                "Artifact-derived lowering evidence requires source intent metadata."
            },
            workflowPlans = listOf(plan)
        )
    )

    internal fun report(
        flowName: String,
        inputs: List<PlanInput>,
        triggers: List<PlanTrigger>,
        sourceIntent: IntentSourceMetadata,
        workflowPlans: List<ExecutionPlan>
    ): IntentLoweringReport = report(
        LoweringPlanView(flowName, inputs, triggers, sourceIntent, workflowPlans)
    )

    private fun report(view: LoweringPlanView): IntentLoweringReport {
        val sourceIntent = view.sourceIntent
        require(sourceIntent.fields.isNotEmpty()) {
            "Artifact-derived lowering evidence requires a non-empty source field catalog."
        }

        val evidence = sourceIntent.fields.sortedBy(IntentSourceField::identity).map { field ->
            val targetValue = resolveTarget(view, field.targetIdentity)
            val targetDigest = digest(field.valueKind, targetValue)
            require(targetDigest == field.expectedTargetDigest) {
                "Lowered target '${field.targetIdentity}' for source '${field.identity}' does not match the expected value."
            }
            if (field.disposition == IntentLoweringDisposition.PRESERVED) {
                require(field.transform == null) {
                    "Preserved source '${field.identity}' must not declare a transform."
                }
                require(field.sourceDigest == targetDigest) {
                    "Preserved source '${field.identity}' does not equal its concrete target value."
                }
            } else {
                require(!field.transform.isNullOrBlank()) {
                    "Transformed source '${field.identity}' must declare a transform."
                }
            }
            IntentLoweringEvidence(
                sourceIdentity = field.identity,
                sourcePath = field.sourcePath,
                targetIdentity = field.targetIdentity,
                disposition = field.disposition,
                valueKind = field.valueKind,
                sourceDigest = field.sourceDigest,
                targetDigest = targetDigest,
                transform = field.transform
            )
        }

        validateAuthoredOrderingGraph(view.workflowPlans, sourceIntent)

        val evidenceDigest = digest(
            "execution-plan-lowering-evidence",
            evidence.joinToString("\n") { entry ->
                canonicalRecord(
                    "sourceIdentity" to entry.sourceIdentity,
                    "targetIdentity" to entry.targetIdentity,
                    "disposition" to entry.disposition.name,
                    "valueKind" to entry.valueKind,
                    "sourceDigest" to entry.sourceDigest,
                    "targetDigest" to entry.targetDigest,
                    "transform" to entry.transform.orEmpty()
                )
            }
        )
        return IntentLoweringReport(evidenceDigest = evidenceDigest, evidence = evidence)
    }

    private fun sourceFields(
        intent: IntentDocument,
        systems: List<IntentSystemMetadata>,
        policies: List<IntentPolicyMetadata>,
        inputNames: Set<String>,
        expressions: IntentExpressionParser
    ): List<IntentSourceField> = buildList {
        add(preserved("intent/name", "$.name", "plan/flow/name", "string", intent.name))
        intent.description?.let {
            add(preserved("intent/description", "$.description", "plan/source-intent/description", "string", it))
        }

        intent.inputs.forEachIndexed { index, input ->
            val inputId = segment(input.name)
            val source = "$.inputs[$index]"
            add(preserved("input/$inputId/name", "$source.name", "plan/input/$inputId/name", "string", input.name))
            val projectedType = projectedInputType(input.type)
            add(transformed(
                identity = "input/$inputId/type",
                sourcePath = "$source.type",
                targetIdentity = "plan/input/$inputId/type",
                valueKind = "input-type",
                sourceValue = input.type,
                expectedTargetValue = projectedType,
                transform = "intent-input-type-normalization"
            ))
            add(preserved(
                "input/$inputId/required",
                "$source.required",
                "plan/input/$inputId/required",
                "boolean",
                input.required.toString()
            ))
            input.default?.let { value ->
                val rendered = ExpressionRenderer.render(IntentValueExpressionLowering.lower(value, expressions))
                add(preserved(
                    "input/$inputId/default",
                    "$source.default",
                    "plan/input/$inputId/default",
                    value.kind,
                    rendered
                ))
            }
        }

        intent.systems.forEachIndexed { index, system ->
            val metadata = systems.single { it.name == system.name }
            val systemId = segment(system.name)
            val source = "$.systems[$index]"
            add(preserved("system/$systemId/name", "$source.name", "plan/source-intent/system/$systemId/name", "string", system.name))
            if (system.type == metadata.canonicalType) {
                add(preserved(
                    "system/$systemId/type",
                    "$source.type",
                    "plan/source-intent/system/$systemId/canonical-type",
                    "string",
                    system.type
                ))
            } else {
                add(transformed(
                    "system/$systemId/type",
                    "$source.type",
                    "plan/source-intent/system/$systemId/canonical-type",
                    "string",
                    system.type,
                    metadata.canonicalType,
                    "canonical-system-type-normalization"
                ))
            }
            system.purpose?.let { purpose ->
                add(preserved(
                    "system/$systemId/purpose",
                    "$source.purpose",
                    "plan/source-intent/system/$systemId/purpose",
                    "string",
                    purpose
                ))
            }
            system.config.toSortedMap().forEach { (key, value) ->
                val expressionValue = ExpressionRenderer.render(IntentValueExpressionLowering.lower(value, expressions))
                val projectedValue = metadata.config.getValue(key)
                val target = "plan/source-intent/system/$systemId/config/${segment(key)}"
                if (expressionValue == projectedValue) {
                    add(preserved("system/$systemId/config/${segment(key)}", "$source.config.$key", target, value.kind, expressionValue))
                } else {
                    add(transformed(
                        "system/$systemId/config/${segment(key)}",
                        "$source.config.$key",
                        target,
                        value.kind,
                        expressionValue,
                        projectedValue,
                        "intent-value-runtime-binding"
                    ))
                }
            }
        }

        intent.triggers.forEachIndexed { index, trigger ->
            val triggerId = segment(trigger.id)
            val source = "$.triggers[$index]"
            add(preserved("trigger/$triggerId/id", "$source.id", "plan/trigger/$triggerId/id", "string", trigger.id))
            add(preserved("trigger/$triggerId/type", "$source.type", "plan/trigger/$triggerId/type", "enum", trigger.type.name))
            trigger.workflows.forEach { workflow ->
                add(preserved(
                    "trigger/$triggerId/workflow/${segment(workflow)}",
                    "$source.workflows",
                    "plan/trigger/$triggerId/workflow/${segment(workflow)}",
                    "string",
                    workflow
                ))
            }
            trigger.schedule?.let { schedule ->
                add(preserved("trigger/$triggerId/schedule/kind", "$source.schedule.kind", "plan/trigger/$triggerId/schedule/kind", "enum", schedule.kind.name))
                add(preserved("trigger/$triggerId/schedule/expression", "$source.schedule.expression", "plan/trigger/$triggerId/schedule/expression", "string", schedule.expression))
                schedule.timezone?.let { timezone ->
                    add(preserved("trigger/$triggerId/schedule/timezone", "$source.schedule.timezone", "plan/trigger/$triggerId/schedule/timezone", "string", timezone))
                }
            }
            trigger.event?.let { event ->
                add(preserved("trigger/$triggerId/event", "$source.event", "plan/trigger/$triggerId/event", "string", event))
            }
            trigger.params.toSortedMap().forEach { (key, value) ->
                val expressionValue = ExpressionRenderer.render(IntentValueExpressionLowering.lower(value, expressions))
                val projectedValue = RuntimeParamRenderer.render(IntentValueExpressionLowering.lower(value, expressions), emptySet())
                val target = "plan/trigger/$triggerId/param/${segment(key)}"
                if (expressionValue == projectedValue) {
                    add(preserved("trigger/$triggerId/param/${segment(key)}", "$source.params.$key", target, value.kind, expressionValue))
                } else {
                    add(transformed(
                        "trigger/$triggerId/param/${segment(key)}",
                        "$source.params.$key",
                        target,
                        value.kind,
                        expressionValue,
                        projectedValue,
                        "intent-value-runtime-binding"
                    ))
                }
            }
        }

        intent.workflows.forEachIndexed { workflowIndex, workflow ->
            val workflowId = segment(workflow.name)
            val workflowSource = "$.workflows[$workflowIndex]"
            add(preserved("workflow/$workflowId/name", "$workflowSource.name", "plan/source-intent/workflow/$workflowId/name", "string", workflow.name))
            add(preserved("workflow/$workflowId/kind", "$workflowSource.kind", "plan/source-intent/workflow/$workflowId/kind", "enum", workflow.kind.name))
            workflow.steps.forEachIndexed { stepIndex, step ->
                val stepId = segment(step.id)
                add(preserved(
                    "workflow/$workflowId/step/$stepId",
                    "$workflowSource.steps[$stepIndex].id",
                    "plan/source-intent/workflow/$workflowId/step/$stepId",
                    "workflow-step-membership",
                    step.id
                ))
                addStepFields(step, "$workflowSource.steps[$stepIndex]", inputNames, expressions)
            }
        }

        intent.policies.forEachIndexed { index, policy ->
            val metadata = policies.single { it.name == policy.name }
            val policyId = segment(policy.name)
            val source = "$.policies[$index]"
            add(preserved("policy/$policyId/name", "$source.name", "plan/source-intent/policy/$policyId/name", "string", metadata.name))
            add(preserved("policy/$policyId/type", "$source.type", "plan/source-intent/policy/$policyId/type", "enum", metadata.type))
            metadata.condition?.let { condition ->
                add(preserved("policy/$policyId/condition", "$source.condition", "plan/source-intent/policy/$policyId/condition", "expression", condition))
            }
            metadata.message?.let { message ->
                add(preserved("policy/$policyId/message", "$source.message", "plan/source-intent/policy/$policyId/message", "string", message))
            }
        }

        add(preserved("failure/notify", "$.failure.notify", "plan/source-intent/failure/notify", "boolean", intent.failure.notify.toString()))
        add(preserved("failure/rollback", "$.failure.rollback", "plan/source-intent/failure/rollback", "boolean", intent.failure.rollback.toString()))
        add(preserved("failure/stop-on-error", "$.failure.stopOnError", "plan/source-intent/failure/stop-on-error", "boolean", intent.failure.stopOnError.toString()))
    }

    private fun MutableList<IntentSourceField>.addStepFields(
        step: IntentStep,
        sourcePath: String,
        inputNames: Set<String>,
        expressions: IntentExpressionParser
    ) {
        val stepId = segment(step.id)
        val targetRoot = "plan/node/source/$stepId"
        add(transformed(
            identity = "step/$stepId/id",
            sourcePath = "$sourcePath.id",
            targetIdentity = "$targetRoot/identity",
            valueKind = "step-identity",
            sourceValue = step.id,
            expectedTargetValue = canonicalRecord(
                "sourceId" to step.id,
                "resultName" to step.id.replace('-', '_')
            ),
            transform = "step-id-to-source-and-result-identity"
        ))
        add(preserved(
            "step/$stepId/capability",
            "$sourcePath.capability",
            "$targetRoot/semantic-capability",
            "enum",
            step.capability.name
        ))
        step.description?.let { description ->
            add(preserved(
                "step/$stepId/description",
                "$sourcePath.description",
                "$targetRoot/source-description",
                "string",
                description
            ))
        }
        step.uses?.let { uses ->
            val module = uses.substringBefore('.')
            val action = uses.substringAfter('.', missingDelimiterValue = "")
            add(transformed(
                "step/$stepId/uses",
                "$sourcePath.uses",
                "$targetRoot/binding",
                "binding",
                uses,
                canonicalRecord("module" to module, "action" to action),
                "explicit-action-binding-resolution"
            ))
        }
        step.requires.forEach { requirement ->
            add(preserved(
                "step/$stepId/requires/${segment(requirement)}",
                "$sourcePath.requires",
                "$targetRoot/dependency/source/${segment(requirement)}",
                "step-reference",
                requirement
            ))
        }
        step.produces.forEach { output ->
            add(preserved(
                "step/$stepId/produces/${segment(output)}",
                "$sourcePath.produces",
                "$targetRoot/output/${segment(output)}",
                "output",
                output
            ))
        }
        step.params.toSortedMap().forEach { (key, value) ->
            val targetField = if (key in bindingMetadataParams) "binding-metadata" else "param"
            val targetIdentity = "$targetRoot/$targetField/${segment(key)}"
            if (step.capability.name == "APPROVE" && key == "message") {
                val message = value.asTextOrNull().orEmpty()
                add(preserved("step/$stepId/param/${segment(key)}", "$sourcePath.params.$key", targetIdentity, value.kind, message))
            } else {
                val expressionValue = ExpressionRenderer.render(IntentValueExpressionLowering.lower(value, expressions))
                val projectedValue = RuntimeParamRenderer.render(IntentValueExpressionLowering.lower(value, expressions), inputNames)
                if (expressionValue == projectedValue) {
                    add(preserved("step/$stepId/param/${segment(key)}", "$sourcePath.params.$key", targetIdentity, value.kind, expressionValue))
                } else {
                    add(transformed(
                        "step/$stepId/param/${segment(key)}",
                        "$sourcePath.params.$key",
                        targetIdentity,
                        value.kind,
                        expressionValue,
                        projectedValue,
                        "intent-value-runtime-binding"
                    ))
                }
            }
        }
    }

    private data class LoweringPlanView(
        val flowName: String,
        val inputs: List<PlanInput>,
        val triggers: List<PlanTrigger>,
        val sourceIntent: IntentSourceMetadata,
        val workflowPlans: List<ExecutionPlan>
    ) {
        init {
            require(flowName.isNotBlank()) { "Lowering plan view name must not be blank." }
            require(workflowPlans.isNotEmpty()) { "Lowering plan view must contain workflow plans." }
        }
    }

    private data class AuthoredOrderingEdge(val sourceStepId: String, val targetStepId: String) {
        override fun toString(): String = "$sourceStepId->$targetStepId"
    }

    /**
     * Proves ordering in both directions. Source field evidence already proves every authored
     * `requires` target exists in the concrete plan; this set-level check additionally proves
     * that the planner did not invent another authored ordering edge. Data-flow ordering is a
     * separate evidence class and therefore never substitutes for DECLARED_ORDERING.
     */
    private fun validateAuthoredOrderingGraph(
        workflowPlans: List<ExecutionPlan>,
        sourceIntent: IntentSourceMetadata
    ) {
        val expected = sourceIntent.fields.mapNotNull(::authoredOrderingEdge).toSet()
        val nodesById = workflowPlans
            .flatMap { plan -> PlanDependencyRelations.flatten(plan.nodes) }
            .groupBy(PlanNode::id)
        val declaredRelations = workflowPlans.flatMap { plan -> plan.dependencyRelations }.filter { relation ->
            relation.kind == PlanDependencyKind.ORDERING &&
                relation.evidence == PlanDependencyEvidence.DECLARED_ORDERING
        }
        val actual = declaredRelations.map { relation ->
            require(relation.resolution == PlanDependencyResolution.RESOLVED) {
                "Authored ordering relation '${relation.targetNodeId}' must be resolved."
            }
            val sourceNodeId = requireNotNull(relation.sourceNodeId) {
                "Authored ordering relation targeting '${relation.targetNodeId}' has no source node."
            }
            val sourceNode = unique(nodesById[sourceNodeId].orEmpty(), "declared ordering source '$sourceNodeId'")
            val targetNode = unique(
                nodesById[relation.targetNodeId].orEmpty(),
                "declared ordering target '${relation.targetNodeId}'"
            )
            val sourceStepId = requireNotNull(sourceIdOf(sourceNode)) {
                "Declared ordering source '$sourceNodeId' has no authored source identity."
            }
            val targetStepId = requireNotNull(sourceIdOf(targetNode)) {
                "Declared ordering target '${relation.targetNodeId}' has no authored source identity."
            }
            AuthoredOrderingEdge(sourceStepId, targetStepId)
        }
        require(actual.size == actual.toSet().size) {
            "ExecutionPlan contains duplicate DECLARED_ORDERING evidence for the same authored edge."
        }
        val actualSet = actual.toSet()
        val missing = (expected - actualSet).sortedBy(AuthoredOrderingEdge::toString)
        val extra = (actualSet - expected).sortedBy(AuthoredOrderingEdge::toString)
        require(missing.isEmpty() && extra.isEmpty()) {
            "Authored dependency graph changed during lowering: missing=${missing.joinToString()}, " +
                "extra=${extra.joinToString()}."
        }
    }

    private fun authoredOrderingEdge(field: IntentSourceField): AuthoredOrderingEdge? {
        val parts = field.identity.split('/').map(::unsegment)
        if (parts.size != 4 || parts[0] != "step" || parts[2] != "requires") return null
        return AuthoredOrderingEdge(sourceStepId = parts[3], targetStepId = parts[1])
    }

    private fun resolveTarget(view: LoweringPlanView, targetIdentity: String): String {
        val parts = targetIdentity.split('/').map(::unsegment)
        require(parts.firstOrNull() == "plan") { "Unknown lowering target identity '$targetIdentity'." }
        return when (parts.getOrNull(1)) {
            "flow" -> when (parts.getOrNull(2)) {
                "name" -> view.flowName
                else -> missing(targetIdentity)
            }
            "input" -> resolveInput(view.inputs, parts, targetIdentity)
            "trigger" -> resolveTrigger(view.triggers, parts, targetIdentity)
            "source-intent" -> resolveSourceIntent(view.sourceIntent, parts, targetIdentity)
            "node" -> resolveNode(view.workflowPlans, parts, targetIdentity)
            else -> missing(targetIdentity)
        }
    }

    private fun resolveInput(inputs: List<PlanInput>, parts: List<String>, identity: String): String {
        val input = unique(inputs.filter { it.name == parts.getOrNull(2) }, identity)
        return when (parts.getOrNull(3)) {
            "name" -> input.name
            "type" -> projectedInputType(input)
            "required" -> input.required.toString()
            "default" -> input.defaultExpression ?: missing(identity)
            else -> missing(identity)
        }
    }

    private fun resolveTrigger(triggers: List<PlanTrigger>, parts: List<String>, identity: String): String {
        val trigger = unique(triggers.filter { it.id == parts.getOrNull(2) }, identity)
        return when (parts.getOrNull(3)) {
            "id" -> trigger.id
            "type" -> trigger.type
            "workflow" -> parts.getOrNull(4)?.takeIf { it in trigger.workflows } ?: missing(identity)
            "schedule" -> when (parts.getOrNull(4)) {
                "kind" -> trigger.schedule?.kind ?: missing(identity)
                "expression" -> trigger.schedule?.expression ?: missing(identity)
                "timezone" -> trigger.schedule?.timezone ?: missing(identity)
                else -> missing(identity)
            }
            "event" -> trigger.event ?: missing(identity)
            "param" -> trigger.params[parts.getOrNull(4)] ?: missing(identity)
            else -> missing(identity)
        }
    }

    private fun resolveSourceIntent(
        source: IntentSourceMetadata,
        parts: List<String>,
        identity: String
    ): String = when (parts.getOrNull(2)) {
        "description" -> source.description ?: missing(identity)
        "workflow" -> {
            val workflow = unique(source.workflows.filter { it.name == parts.getOrNull(3) }, identity)
            when (parts.getOrNull(4)) {
                "name" -> workflow.name
                "kind" -> workflow.kind
                "step" -> {
                    val stepId = parts.getOrNull(5) ?: missing(identity)
                    stepId.takeIf { it in workflow.stepIds } ?: missing(identity)
                }
                else -> missing(identity)
            }
        }
        "system" -> {
            val system = unique(source.systems.filter { it.name == parts.getOrNull(3) }, identity)
            when (parts.getOrNull(4)) {
                "name" -> system.name
                "source-type" -> system.sourceType
                "canonical-type" -> system.canonicalType
                "purpose" -> system.purpose ?: missing(identity)
                "config" -> system.config[parts.getOrNull(5)] ?: missing(identity)
                else -> missing(identity)
            }
        }
        "policy" -> {
            val policy = unique(source.policies.filter { it.name == parts.getOrNull(3) }, identity)
            when (parts.getOrNull(4)) {
                "name" -> policy.name
                "type" -> policy.type
                "condition" -> policy.condition ?: missing(identity)
                "message" -> policy.message ?: missing(identity)
                else -> missing(identity)
            }
        }
        "failure" -> when (parts.getOrNull(3)) {
            "notify" -> source.failure.notify.toString()
            "rollback" -> source.failure.rollback.toString()
            "stop-on-error" -> source.failure.stopOnError.toString()
            else -> missing(identity)
        }
        else -> missing(identity)
    }

    private fun resolveNode(
        workflowPlans: List<ExecutionPlan>,
        parts: List<String>,
        identity: String
    ): String {
        require(parts.getOrNull(2) == "source") { "Unknown lowering node identity '$identity'." }
        val sourceId = parts.getOrNull(3) ?: missing(identity)
        val node = sourceNode(workflowPlans, sourceId, identity)
        return when (parts.getOrNull(4)) {
            "identity" -> canonicalRecord(
                "sourceId" to sourceIdOf(node).orEmpty(),
                "resultName" to resultNameOf(node).orEmpty()
            )
            "semantic-capability" -> when (node) {
                is TaskNode -> node.semanticCapability ?: missing(identity)
                is ApprovalNode -> "APPROVE"
                else -> missing(identity)
            }
            "source-description" -> sourceDescriptionOf(node) ?: missing(identity)
            "binding" -> when (node) {
                is TaskNode -> canonicalRecord("module" to node.module, "action" to node.action)
                else -> missing(identity)
            }
            "dependency" -> {
                require(parts.getOrNull(5) == "source") { "Unknown lowering dependency identity '$identity'." }
                val dependencySourceId = parts.getOrNull(6) ?: missing(identity)
                val dependency = sourceNode(workflowPlans, dependencySourceId, identity)
                val dependencies = dependenciesOf(node)
                dependency.id.takeIf { it in dependencies }?.let { dependencySourceId } ?: missing(identity)
            }
            "output" -> {
                val output = parts.getOrNull(5) ?: missing(identity)
                output.takeIf { it in outputsOf(node) } ?: missing(identity)
            }
            "param" -> {
                val key = parts.getOrNull(5) ?: missing(identity)
                when (node) {
                    is TaskNode -> node.params[key] ?: missing(identity)
                    is ApprovalNode -> if (key == "message") node.message ?: missing(identity) else missing(identity)
                    else -> missing(identity)
                }
            }
            "binding-metadata" -> {
                val key = parts.getOrNull(5) ?: missing(identity)
                (node as? TaskNode)?.bindingMetadata?.get(key) ?: missing(identity)
            }
            else -> missing(identity)
        }
    }

    private fun sourceNode(
        workflowPlans: List<ExecutionPlan>,
        sourceId: String,
        identity: String
    ): PlanNode = unique(
        workflowPlans.flatMap { plan ->
            PlanDependencyRelations.flatten(plan.nodes).filter { node -> sourceIdOf(node) == sourceId }
        },
        identity
    )

    private fun sourceIdOf(node: PlanNode): String? = when (node) {
        is TaskNode -> node.sourceId
        is ApprovalNode -> node.sourceId
        else -> null
    }

    private fun sourceDescriptionOf(node: PlanNode): String? = when (node) {
        is TaskNode -> node.sourceDescription
        is ApprovalNode -> node.sourceDescription
        else -> null
    }

    private fun resultNameOf(node: PlanNode): String? = when (node) {
        is TaskNode -> node.resultName
        is ApprovalNode -> node.resultName
        else -> null
    }

    private fun dependenciesOf(node: PlanNode): List<String> = when (node) {
        is TaskNode -> node.dependsOn
        is ApprovalNode -> node.dependsOn
        else -> emptyList()
    }

    private fun outputsOf(node: PlanNode): List<String> = when (node) {
        is TaskNode -> node.outputs
        is ApprovalNode -> node.outputs
        else -> emptyList()
    }

    private fun projectedInputType(type: String): String {
        val kind: String
        val choices: List<String>
        if (type.startsWith("option[") && type.endsWith("]")) {
            kind = "option"
            choices = type.substringAfter("option[").substringBeforeLast("]")
                .split(',').map { it.trim().trim('"', '\'') }.filter { it.isNotEmpty() }
        } else {
            kind = type
            choices = emptyList()
        }
        return canonicalRecord("kind" to kind, "choices" to canonicalList(choices))
    }

    private fun projectedInputType(input: PlanInput): String =
        canonicalRecord("kind" to input.type, "choices" to canonicalList(input.choices))

    private fun preserved(
        identity: String,
        sourcePath: String,
        targetIdentity: String,
        valueKind: String,
        value: String
    ): IntentSourceField = field(
        identity = identity,
        sourcePath = sourcePath,
        targetIdentity = targetIdentity,
        disposition = IntentLoweringDisposition.PRESERVED,
        valueKind = valueKind,
        sourceValue = value,
        expectedTargetValue = value,
        transform = null
    )

    private fun transformed(
        identity: String,
        sourcePath: String,
        targetIdentity: String,
        valueKind: String,
        sourceValue: String,
        expectedTargetValue: String,
        transform: String
    ): IntentSourceField = field(
        identity,
        sourcePath,
        targetIdentity,
        IntentLoweringDisposition.TRANSFORMED,
        valueKind,
        sourceValue,
        expectedTargetValue,
        transform
    )

    private fun field(
        identity: String,
        sourcePath: String,
        targetIdentity: String,
        disposition: IntentLoweringDisposition,
        valueKind: String,
        sourceValue: String,
        expectedTargetValue: String,
        transform: String?
    ): IntentSourceField {
        val sourceDigest = digest(valueKind, sourceValue)
        val targetDigest = digest(valueKind, expectedTargetValue)
        if (disposition == IntentLoweringDisposition.PRESERVED) {
            require(sourceDigest == targetDigest) { "Preserved source '$identity' must have an identical expected target value." }
        } else {
            require(!transform.isNullOrBlank()) { "Transformed source '$identity' must name its transform." }
        }
        return IntentSourceField(
            identity = identity,
            sourcePath = sourcePath,
            targetIdentity = targetIdentity,
            disposition = disposition,
            valueKind = valueKind,
            sourceDigest = sourceDigest,
            expectedTargetDigest = targetDigest,
            transform = transform
        )
    }

    private fun canonicalRecord(vararg fields: Pair<String, String>): String = fields.joinToString("|") { (key, value) ->
        "${key.length}:$key=${value.length}:$value"
    }

    private fun canonicalList(values: List<String>): String = values.joinToString(prefix = "[", postfix = "]", separator = "|") {
        "${it.length}:$it"
    }

    private fun digest(kind: String, value: String): String {
        val bytes = MessageDigest.getInstance("SHA-256")
            .digest("$kind\u0000$value".toByteArray(StandardCharsets.UTF_8))
        return bytes.joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }

    private fun segment(value: String): String = value.replace("~", "~0").replace("/", "~1")
    private fun unsegment(value: String): String = value.replace("~1", "/").replace("~0", "~")

    private fun missing(identity: String): Nothing =
        throw IllegalArgumentException("Lowering target '$identity' does not resolve to a concrete execution-plan value.")

    private fun <T> unique(values: List<T>, identity: String): T {
        require(values.size == 1) {
            "Lowering target '$identity' resolved to ${values.size} candidates; exactly one is required."
        }
        return values.single()
    }
}
