package org.flowlang.generators.manifest

import org.flowlang.capabilities.TargetProjectionMode
import org.flowlang.capabilities.TargetProjectionRule
import org.flowlang.capabilities.TargetRendererPayloadTemplate
import org.flowlang.planner.ApprovalNode
import org.flowlang.planner.TaskNode
import org.flowlang.projection.ProjectionBinding
import org.flowlang.projection.ProjectionBindingContract
import org.flowlang.projection.ProjectionBindingKind
import org.flowlang.projection.ProjectionBindingResolutionStatus
import org.flowlang.projection.TaskMetadataField

/** Target-neutral contract for one binding accepted by a concrete native projection. */
data class TargetNativeProjectionBindingContract(
    val acceptedKinds: Set<ProjectionBindingKind>,
    val required: Boolean = true
) {
    init {
        require(acceptedKinds.isNotEmpty()) {
            "Native projection binding contract must accept at least one binding kind."
        }
    }
}

/** Opaque identity and typed input contract for one native action projection handler. */
data class TargetNativeProjectionDefinition(
    val kind: String,
    val reference: String,
    val bindings: Map<String, TargetNativeProjectionBindingContract> = emptyMap()
) {
    init {
        require(kind.isNotBlank()) { "Native projection definition must declare a non-blank payload kind." }
        require(reference.isNotBlank()) { "Native projection definition '$kind' must declare a non-blank reference." }
        bindings.forEach { (name, contract) ->
            require(name.isNotBlank()) {
                "Native projection definition '$kind/$reference' contains a blank binding name."
            }
            require(contract.acceptedKinds.isNotEmpty()) {
                "Native projection definition '$kind/$reference' binding '$name' accepts no binding kinds."
            }
        }
    }
}

enum class TargetApprovalProjectionField {
    NODE_ID,
    MODE,
    MESSAGE
}

data class TargetNativeApprovalProjectionBindingContract(
    val field: TargetApprovalProjectionField,
    val required: Boolean = true
)

/**
 * Provider-owned contract for materializing a semantic approval boundary.
 *
 * This contract is intentionally separate from module action projection rules:
 * ApprovalNode is a control boundary, not a fabricated module task. A target may
 * claim native approval only when its composed provider supplies this concrete
 * payload contract and the renderer owns the matching kind/reference pair.
 */
data class TargetNativeApprovalProjectionDefinition(
    val capability: String,
    val kind: String,
    val reference: String,
    val evidenceReference: String,
    val bindings: Map<String, TargetNativeApprovalProjectionBindingContract>
) {
    init {
        require(capability.isNotBlank()) { "Native approval projection must declare a capability." }
        require(capability.startsWith("approval.")) {
            "Native approval projection capability '$capability' is not an approval capability."
        }
        require(kind.isNotBlank()) { "Native approval projection '$capability' must declare a payload kind." }
        require(reference.isNotBlank()) { "Native approval projection '$capability' must declare a payload reference." }
        require(evidenceReference.isNotBlank()) { "Native approval projection '$capability' must cite provider evidence." }
        require(bindings.isNotEmpty()) { "Native approval projection '$capability' must declare typed bindings." }
        require(bindings.keys.none(String::isBlank)) { "Native approval projection '$capability' contains a blank binding name." }
        require(bindings.values.map(TargetNativeApprovalProjectionBindingContract::field).distinct().size == bindings.size) {
            "Native approval projection '$capability' maps the same semantic field more than once."
        }
    }

    internal fun payloadDefinition(): TargetNativeProjectionDefinition = TargetNativeProjectionDefinition(
        kind = kind,
        reference = reference,
        bindings = bindings.mapValues { (_, contract) ->
            TargetNativeProjectionBindingContract(
                acceptedKinds = setOf(ProjectionBindingKind.LITERAL),
                required = contract.required
            )
        }
    )
}

/** Structural semantics that require adapter-owned implementation evidence. */
enum class TargetStructuralProjectionKind(
    val stepType: String,
    val capability: String
) {
    CONDITION("condition", "condition.evaluate"),
    PARALLEL("parallel", "parallel.dag"),
    LOOP("loop", "loop.dynamic"),
    MATCH("match", "match.basic"),
    RETRY("retry", "retry.task"),
    ERROR_BOUNDARY("try", "errorHandlers.finally");

    companion object {
        fun fromStepType(stepType: String): TargetStructuralProjectionKind? =
            entries.firstOrNull { it.stepType == stepType }
    }
}

/**
 * Provider-owned proof that one structural construct has both production behavior
 * and an independent behavioral test. A platform capability declaration is not
 * enough, because native child leaves cannot preserve an unimplemented parent.
 */
data class TargetNativeStructuralProjectionDefinition(
    val structure: TargetStructuralProjectionKind,
    val kind: String,
    val reference: String,
    val implementationEvidenceReference: String,
    val behavioralEvidenceReference: String
) {
    init {
        require(kind.isNotBlank()) { "Native structural projection '${structure.name}' must declare a payload kind." }
        require(reference.isNotBlank()) { "Native structural projection '${structure.name}' must declare a payload reference." }
        require(implementationEvidenceReference.isNotBlank()) {
            "Native structural projection '${structure.name}' must cite production implementation evidence."
        }
        require(behavioralEvidenceReference.isNotBlank()) {
            "Native structural projection '${structure.name}' must cite behavioral evidence."
        }
    }

    internal fun payloadDefinition(): TargetNativeProjectionDefinition =
        TargetNativeProjectionDefinition(kind = kind, reference = reference)
}

data class TargetNativeStructuralProjectionResolution(
    val materialization: TargetMaterialization,
    val rendererPayload: TargetRendererPayload?
)

/**
 * Immutable, explicitly composed evidence that a target distribution owns and
 * implements selected native action and structural projection payloads.
 */
class TargetNativeProjectionCatalog private constructor(
    val target: String,
    actionDefinitions: Iterable<TargetNativeProjectionDefinition>,
    approvalProjectionDefinitions: Iterable<TargetNativeApprovalProjectionDefinition>,
    structuralProjectionDefinitions: Iterable<TargetNativeStructuralProjectionDefinition>
) {
    private val definitionsByKey: Map<DefinitionKey, TargetNativeProjectionDefinition>
    private val approvalDefinitionsByCapability: Map<String, TargetNativeApprovalProjectionDefinition>
    private val structuralDefinitionsByKind: Map<TargetStructuralProjectionKind, TargetNativeStructuralProjectionDefinition>
    private val actionDefinitionValues: List<TargetNativeProjectionDefinition>

    init {
        require(target.isNotBlank()) { "Native projection catalog must declare a non-blank target id." }
        actionDefinitionValues = actionDefinitions.toList()
        val approvals = approvalProjectionDefinitions.toList()
        val structures = structuralProjectionDefinitions.toList()
        val indexed = linkedMapOf<DefinitionKey, TargetNativeProjectionDefinition>()
        actionDefinitionValues.forEach { definition ->
            indexDefinition(indexed, definition)
        }
        approvals.forEach { approval ->
            indexDefinition(indexed, approval.payloadDefinition())
        }
        structures.forEach { structure ->
            indexDefinition(indexed, structure.payloadDefinition())
        }
        definitionsByKey = indexed.toMap()

        val approvalsByCapability = linkedMapOf<String, TargetNativeApprovalProjectionDefinition>()
        approvals.forEach { definition ->
            require(approvalsByCapability.putIfAbsent(definition.capability, definition) == null) {
                "Duplicate native approval projection capability '${definition.capability}' for target '$target'."
            }
        }
        approvalDefinitionsByCapability = approvalsByCapability.toMap()

        val structuresByKind = linkedMapOf<TargetStructuralProjectionKind, TargetNativeStructuralProjectionDefinition>()
        structures.forEach { definition ->
            require(structuresByKind.putIfAbsent(definition.structure, definition) == null) {
                "Duplicate native structural projection '${definition.structure}' for target '$target'."
            }
        }
        structuralDefinitionsByKind = structuresByKind.toMap()
    }

    /** Native action definitions only; structural and approval contracts have dedicated views. */
    val definitions: List<TargetNativeProjectionDefinition>
        get() = actionDefinitionValues

    val approvalDefinitions: List<TargetNativeApprovalProjectionDefinition>
        get() = approvalDefinitionsByCapability.values.toList()

    val structuralDefinitions: List<TargetNativeStructuralProjectionDefinition>
        get() = structuralDefinitionsByKind.values.toList()

    fun hasStructuralProjection(kind: TargetStructuralProjectionKind): Boolean =
        kind in structuralDefinitionsByKind

    fun requireCompatibleRules(rules: Iterable<TargetProjectionRule>) {
        rules.filter { it.mode == TargetProjectionMode.NATIVE }.forEach { rule ->
            val template = requireNotNull(rule.payload) {
                "Native projection rule '${rule.module}.${rule.action}' for target '$target' has no payload template."
            }
            requireTemplate(template, "${rule.module}.${rule.action}")
        }
    }

    fun compile(
        rule: TargetProjectionRule,
        task: TaskNode
    ): TargetRendererPayload {
        require(rule.mode == TargetProjectionMode.NATIVE) {
            "Projection rule '${rule.module}.${rule.action}' is '${rule.mode}', not NATIVE."
        }
        val template = requireNotNull(rule.payload) {
            "Native projection rule '${rule.module}.${rule.action}' for target '$target' has no payload template."
        }
        val definition = requireTemplate(template, "${rule.module}.${rule.action}")
        val resolvedBindings = template.bindings.mapNotNull { (name, binding) ->
            val resolved = resolveBinding(binding, task, name)
            val contract = definition.bindings.getValue(name)
            if (!contract.required && resolved.resolutionStatus == ProjectionBindingResolutionStatus.UNRESOLVED) {
                null
            } else {
                name to resolved
            }
        }.toMap()
        val payload = TargetRendererPayload(
            kind = template.kind,
            target = target,
            reference = template.reference,
            bindings = resolvedBindings,
            evidenceReference = rule.evidenceReference
        )
        requirePayload(payload, definition)
        return payload
    }

    /** Returns null when this provider has no owned implementation for the approval capability. */
    fun compileApproval(node: ApprovalNode): TargetRendererPayload? {
        val requestedCapability = node.requiredCapabilities
            .singleOrNull { it.startsWith("approval.") }
            ?: "approval.${node.mode.lowercase()}"
        val definition = approvalDefinitionsByCapability[requestedCapability] ?: return null
        val payloadDefinition = definition.payloadDefinition()
        val bindings = definition.bindings.mapNotNull { (name, contract) ->
            val value = when (contract.field) {
                TargetApprovalProjectionField.NODE_ID -> node.id
                TargetApprovalProjectionField.MODE -> node.mode
                TargetApprovalProjectionField.MESSAGE -> node.message ?: "Approval required"
            }.takeIf(String::isNotBlank)
            if (value == null) {
                require(!contract.required) {
                    "Native approval projection '${definition.capability}' for target '$target' cannot resolve required binding '$name'."
                }
                null
            } else {
                name to ProjectionBinding.literal(value).copy(
                    resolutionStatus = ProjectionBindingResolutionStatus.RESOLVED
                )
            }
        }.toMap()
        val payload = TargetRendererPayload(
            kind = definition.kind,
            target = target,
            reference = definition.reference,
            bindings = bindings,
            evidenceReference = definition.evidenceReference
        )
        requirePayload(payload, payloadDefinition)
        return payload
    }

    /**
     * Resolve one structural boundary. Missing evidence is an explicit adapter
     * requirement, never a semantic-only parent made executable by native children.
     */
    fun resolveStructure(
        kind: TargetStructuralProjectionKind,
        nodeId: String
    ): TargetNativeStructuralProjectionResolution {
        val definition = structuralDefinitionsByKind[kind]
            ?: return TargetNativeStructuralProjectionResolution(
                materialization = TargetMaterialization.adapterRequired(
                    capability = kind.capability,
                    reason = "Target provider '$target' has no owned ${kind.name.lowercase()} structural projection evidence for '$nodeId'.",
                    requirements = mapOf(
                        STRUCTURAL_KIND_METADATA to kind.name,
                        "target" to target,
                        "sourceNode" to nodeId
                    )
                ),
                rendererPayload = null
            )

        val payload = TargetRendererPayload(
            kind = definition.kind,
            target = target,
            reference = definition.reference,
            evidenceReference = definition.implementationEvidenceReference
        )
        requirePayload(payload, definition.payloadDefinition())
        return TargetNativeStructuralProjectionResolution(
            materialization = TargetMaterialization.native(
                capability = kind.capability,
                reason = "Structural ${kind.name.lowercase()} semantics are backed by provider-owned production and behavioral evidence.",
                metadata = mapOf(
                    STRUCTURAL_KIND_METADATA to kind.name,
                    STRUCTURAL_IMPLEMENTATION_EVIDENCE_METADATA to definition.implementationEvidenceReference,
                    STRUCTURAL_BEHAVIOR_EVIDENCE_METADATA to definition.behavioralEvidenceReference
                )
            ),
            rendererPayload = payload
        )
    }

    fun requirePayload(payload: TargetRendererPayload) {
        require(payload.target == target) {
            "Native projection catalog '$target' cannot validate payload target '${payload.target}'."
        }
        val definition = definitionFor(payload.kind, payload.reference)
        requirePayload(payload, definition)
    }

    fun requireManifest(manifest: TargetManifest) {
        require(manifest.target == target) {
            "Native projection catalog '$target' cannot validate manifest target '${manifest.target}'."
        }
        manifest.jobs.asSequence()
            .flatMap { job -> job.steps.asSequence() }
            .flatMap { step -> flatten(step) }
            .forEach { step ->
                step.rendererPayload?.let(::requirePayload)
                val structure = TargetStructuralProjectionKind.fromStepType(step.type)
                if (structure != null && step.materialization.status == TargetMaterializationStatus.NATIVE) {
                    requireStructuralStep(step, structure)
                }
            }
    }

    private fun requireStructuralStep(step: TargetStep, kind: TargetStructuralProjectionKind) {
        val definition = structuralDefinitionsByKind[kind]
            ?: error("Target '$target' has no native structural implementation contract for '${kind.name}'.")
        val payload = requireNotNull(step.rendererPayload) {
            "Native structural step '${step.id}' for target '$target' has no renderer payload."
        }
        require(payload.kind == definition.kind && payload.reference == definition.reference) {
            "Native structural step '${step.id}' uses '${payload.kind}/${payload.reference}', expected '${definition.kind}/${definition.reference}'."
        }
        val metadata = step.materialization.metadata
        require(metadata[STRUCTURAL_KIND_METADATA] == kind.name) {
            "Native structural step '${step.id}' does not preserve structural kind '${kind.name}'."
        }
        require(metadata[STRUCTURAL_IMPLEMENTATION_EVIDENCE_METADATA] == definition.implementationEvidenceReference) {
            "Native structural step '${step.id}' does not preserve exact implementation evidence."
        }
        require(metadata[STRUCTURAL_BEHAVIOR_EVIDENCE_METADATA] == definition.behavioralEvidenceReference) {
            "Native structural step '${step.id}' does not preserve exact behavioral evidence."
        }
    }

    private fun indexDefinition(
        indexed: MutableMap<DefinitionKey, TargetNativeProjectionDefinition>,
        definition: TargetNativeProjectionDefinition
    ) {
        val key = DefinitionKey(definition.kind, definition.reference)
        require(indexed.putIfAbsent(key, definition) == null) {
            "Duplicate native projection definition '${definition.kind}/${definition.reference}' for target '$target'."
        }
    }

    private fun requireTemplate(
        template: TargetRendererPayloadTemplate,
        context: String
    ): TargetNativeProjectionDefinition {
        val definition = definitionFor(template.kind, template.reference)
        val unexpected = template.bindings.keys - definition.bindings.keys
        require(unexpected.isEmpty()) {
            "Native projection '$context' for target '$target' declares unsupported bindings: ${unexpected.sorted().joinToString()}."
        }
        val missing = definition.bindings
            .filterValues { it.required }
            .keys - template.bindings.keys
        require(missing.isEmpty()) {
            "Native projection '$context' for target '$target' is missing required bindings: ${missing.sorted().joinToString()}."
        }
        template.bindings.forEach { (name, binding) ->
            ProjectionBindingContract.requireTemplate(binding, "$target.$context.bindings.$name")
            val contract = definition.bindings.getValue(name)
            require(binding.kind in contract.acceptedKinds) {
                "Native projection '$context' binding '$name' for target '$target' uses ${binding.kind}; accepted kinds: ${contract.acceptedKinds.sortedBy { it.name }.joinToString()}."
            }
        }
        return definition
    }

    private fun requirePayload(
        payload: TargetRendererPayload,
        definition: TargetNativeProjectionDefinition
    ) {
        require(payload.evidenceReference.isNotBlank()) {
            "Native projection payload '${payload.kind}/${payload.reference}' for target '$target' has no evidence reference."
        }
        val unexpected = payload.bindings.keys - definition.bindings.keys
        require(unexpected.isEmpty()) {
            "Native projection payload '${payload.kind}/${payload.reference}' for target '$target' contains unsupported bindings: ${unexpected.sorted().joinToString()}."
        }
        val missing = definition.bindings
            .filterValues { it.required }
            .keys - payload.bindings.keys
        require(missing.isEmpty()) {
            "Native projection payload '${payload.kind}/${payload.reference}' for target '$target' is missing required bindings: ${missing.sorted().joinToString()}."
        }
        payload.bindings.forEach { (name, binding) ->
            val contract = definition.bindings.getValue(name)
            require(binding.kind in contract.acceptedKinds) {
                "Native projection payload binding '$name' for target '$target' uses ${binding.kind}; accepted kinds: ${contract.acceptedKinds.sortedBy { it.name }.joinToString()}."
            }
            ProjectionBindingContract.requireManifest(binding, target, "$target.bindings.$name")
        }
    }

    private fun definitionFor(kind: String, reference: String): TargetNativeProjectionDefinition =
        definitionsByKey[DefinitionKey(kind, reference)]
            ?: error(
                "Target '$target' has no native projection implementation contract for '$kind/$reference'. Declared contracts: " +
                    definitionsByKey.keys.sortedWith(compareBy<DefinitionKey> { it.kind }.thenBy { it.reference })
                        .joinToString { "${it.kind}/${it.reference}" }.ifBlank { "none" } + "."
            )

    private fun resolveBinding(
        binding: ProjectionBinding,
        task: TaskNode,
        bindingName: String
    ): ProjectionBinding {
        ProjectionBindingContract.requireTemplate(binding, "$target.bindings.$bindingName")
        val resolved = when (binding.kind) {
            ProjectionBindingKind.LITERAL -> binding.copy(
                resolutionStatus = ProjectionBindingResolutionStatus.RESOLVED
            )
            ProjectionBindingKind.TASK_PARAMETER -> resolveTaskValue(
                binding = binding,
                value = task.params[binding.name] ?: binding.defaultValue,
                unresolvedReason = "Task '${task.id}' does not provide required parameter '${binding.name}' for projection binding '$bindingName'."
            )
            ProjectionBindingKind.TASK_INPUT -> resolveTaskValue(
                binding = binding,
                value = task.inputs[binding.name] ?: binding.defaultValue,
                unresolvedReason = "Task '${task.id}' does not provide required input '${binding.name}' for projection binding '$bindingName'."
            )
            ProjectionBindingKind.TASK_METADATA -> binding.copy(
                value = when (binding.field) {
                    TaskMetadataField.ID -> task.id
                    TaskMetadataField.TARGET -> task.target
                    null -> error("TASK_METADATA projection binding '$bindingName' has no field.")
                },
                resolutionStatus = ProjectionBindingResolutionStatus.RESOLVED,
                reason = null
            )
            ProjectionBindingKind.FLOW_INPUT,
            ProjectionBindingKind.SECRET,
            ProjectionBindingKind.ARTIFACT,
            ProjectionBindingKind.TASK_OUTPUT,
            ProjectionBindingKind.TARGET_EXPRESSION -> binding.copy(
                resolutionStatus = ProjectionBindingResolutionStatus.SYMBOLIC,
                reason = null
            )
        }
        ProjectionBindingContract.requireManifest(resolved, target, "$target.bindings.$bindingName")
        return resolved
    }

    private fun resolveTaskValue(
        binding: ProjectionBinding,
        value: String?,
        unresolvedReason: String
    ): ProjectionBinding = if (value != null) {
        binding.copy(
            value = value,
            resolutionStatus = ProjectionBindingResolutionStatus.RESOLVED,
            reason = null
        )
    } else {
        binding.copy(
            value = null,
            resolutionStatus = ProjectionBindingResolutionStatus.UNRESOLVED,
            reason = unresolvedReason
        )
    }

    private fun flatten(step: TargetStep): Sequence<TargetStep> = sequence {
        yield(step)
        step.children.forEach { child -> yieldAll(flatten(child)) }
    }

    private data class DefinitionKey(val kind: String, val reference: String)

    companion object {
        const val STRUCTURAL_KIND_METADATA = "structuralProjectionKind"
        const val STRUCTURAL_IMPLEMENTATION_EVIDENCE_METADATA = "structuralImplementationEvidence"
        const val STRUCTURAL_BEHAVIOR_EVIDENCE_METADATA = "structuralBehaviorEvidence"

        fun of(
            target: String,
            definitions: Iterable<TargetNativeProjectionDefinition>,
            approvalDefinitions: Iterable<TargetNativeApprovalProjectionDefinition> = emptyList(),
            structuralDefinitions: Iterable<TargetNativeStructuralProjectionDefinition> = emptyList()
        ): TargetNativeProjectionCatalog = TargetNativeProjectionCatalog(
            target,
            definitions,
            approvalDefinitions,
            structuralDefinitions
        )

        fun of(
            target: String,
            vararg definitions: TargetNativeProjectionDefinition
        ): TargetNativeProjectionCatalog = of(target, definitions.asIterable())

        fun empty(target: String): TargetNativeProjectionCatalog = of(target, emptyList())
    }
}
