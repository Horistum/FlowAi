package org.flowlang.generators.manifest

import org.flowlang.capabilities.TargetProjectionMode
import org.flowlang.capabilities.TargetProjectionRule
import org.flowlang.capabilities.TargetRendererPayloadTemplate
import org.flowlang.planner.TaskNode
import org.flowlang.projection.ProjectionBinding
import org.flowlang.projection.ProjectionBindingContract
import org.flowlang.projection.ProjectionBindingKind
import org.flowlang.projection.ProjectionBindingResolutionStatus
import org.flowlang.projection.TaskMetadataField

/**
 * Target-neutral contract for one binding accepted by a concrete native
 * projection implementation. The binding vocabulary remains portable; target
 * syntax and behavior remain owned by the edge implementation.
 */
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

/**
 * Opaque identity and typed input contract for one native projection handler.
 * Neither kind nor reference is interpreted by Flow Core.
 */
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

/**
 * Immutable, explicitly composed evidence that a target distribution owns and
 * implements selected native projection payloads.
 *
 * This is not discovery, a plugin lifecycle, or a target DSL. A NATIVE registry
 * rule is accepted only when this catalog contains a matching implementation
 * contract and its typed binding schema agrees exactly.
 */
class TargetNativeProjectionCatalog private constructor(
    val target: String,
    definitions: Iterable<TargetNativeProjectionDefinition>
) {
    private val definitionsByKey: Map<DefinitionKey, TargetNativeProjectionDefinition>

    init {
        require(target.isNotBlank()) { "Native projection catalog must declare a non-blank target id." }
        val indexed = linkedMapOf<DefinitionKey, TargetNativeProjectionDefinition>()
        definitions.forEach { definition ->
            val key = DefinitionKey(definition.kind, definition.reference)
            require(indexed.putIfAbsent(key, definition) == null) {
                "Duplicate native projection definition '${definition.kind}/${definition.reference}' for target '$target'."
            }
        }
        definitionsByKey = indexed.toMap()
    }

    val definitions: List<TargetNativeProjectionDefinition>
        get() = definitionsByKey.values.toList()

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
        val resolvedBindings = template.bindings.mapValues { (name, binding) ->
            resolveBinding(binding, task, name)
        }
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
            .mapNotNull { it.rendererPayload }
            .forEach(::requirePayload)
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
                "Native projection '$context' binding '$name' for target '$target' uses ${binding.kind}; " +
                    "accepted kinds: ${contract.acceptedKinds.sortedBy { it.name }.joinToString()}."
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
            "Native projection payload '${payload.kind}/${payload.reference}' for target '$target' contains unsupported bindings: " +
                unexpected.sorted().joinToString() + "."
        }
        val missing = definition.bindings
            .filterValues { it.required }
            .keys - payload.bindings.keys
        require(missing.isEmpty()) {
            "Native projection payload '${payload.kind}/${payload.reference}' for target '$target' is missing required bindings: " +
                missing.sorted().joinToString() + "."
        }
        payload.bindings.forEach { (name, binding) ->
            val contract = definition.bindings.getValue(name)
            require(binding.kind in contract.acceptedKinds) {
                "Native projection payload binding '$name' for target '$target' uses ${binding.kind}; " +
                    "accepted kinds: ${contract.acceptedKinds.sortedBy { it.name }.joinToString()}."
            }
            ProjectionBindingContract.requireManifest(binding, target, "$target.bindings.$name")
        }
    }

    private fun definitionFor(kind: String, reference: String): TargetNativeProjectionDefinition =
        definitionsByKey[DefinitionKey(kind, reference)]
            ?: error(
                "Target '$target' has no native projection implementation contract for '$kind/$reference'. " +
                    "Declared contracts: ${definitionsByKey.keys.sortedWith(compareBy<DefinitionKey> { it.kind }.thenBy { it.reference })
                        .joinToString { "${it.kind}/${it.reference}" }.ifBlank { "none" }}."
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
        fun of(
            target: String,
            definitions: Iterable<TargetNativeProjectionDefinition>
        ): TargetNativeProjectionCatalog = TargetNativeProjectionCatalog(target, definitions)

        fun of(
            target: String,
            vararg definitions: TargetNativeProjectionDefinition
        ): TargetNativeProjectionCatalog = of(target, definitions.asIterable())

        fun empty(target: String): TargetNativeProjectionCatalog = TargetNativeProjectionCatalog(target, emptyList())
    }
}
