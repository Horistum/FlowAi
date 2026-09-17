package org.flowlang.intent

import org.flowlang.effects.CanonicalIntentEffectAuthority
import org.flowlang.effects.SemanticEffect
import org.flowlang.controls.CanonicalControlRequirementAuthority
import org.flowlang.controls.ControlRequirement
import org.flowlang.modules.ModuleActionContract
import org.flowlang.modules.ModuleCatalog
import org.flowlang.modules.ModuleCatalogIndex
import org.flowlang.standard.StandardCapabilityContracts
import org.flowlang.topology.CanonicalTopologyRequirementAuthority
import org.flowlang.topology.ExecutionTopologyRequirement

/**
 * Inventory-independent semantic meaning of a Flow intent.
 *
 * Concrete systems and module actions are intentionally absent. They belong to
 * [IntentBindingEvidence], which may vary between inventories without changing
 * the meaning represented here.
 */
data class CanonicalIntentMeaning(
    val intentVersion: String,
    val kind: String,
    val name: String,
    val description: String? = null,
    val inputs: List<IntentInput> = emptyList(),
    val triggers: List<IntentTrigger> = emptyList(),
    val workflows: List<CanonicalIntentWorkflow> = emptyList(),
    val controlRequirements: List<ControlRequirement> = emptyList(),
    val topologyRequirements: List<ExecutionTopologyRequirement> = emptyList(),
    val policies: List<IntentPolicy> = emptyList(),
    val failure: IntentFailurePolicy = IntentFailurePolicy()
)

data class CanonicalIntentWorkflow(
    val name: String,
    val kind: IntentWorkflowKind,
    val steps: List<CanonicalIntentStep> = emptyList()
)

data class CanonicalIntentStep(
    val id: String,
    val capability: StandardCapability,
    val description: String? = null,
    val requires: List<String> = emptyList(),
    val produces: List<String> = emptyList(),
    val params: Map<String, IntentValue> = emptyMap(),
    val effects: List<SemanticEffect> = emptyList()
)

enum class IntentBindingStatus { UNBOUND, RESOLVED, INVALID }

data class IntentBindingEvidence(
    val stepId: String,
    val capability: StandardCapability,
    val status: IntentBindingStatus,
    val requestedAction: String? = null,
    val module: String? = null,
    val action: String? = null,
    val system: String? = null,
    val systemType: String? = null,
    val implementedCapabilities: List<String> = emptyList(),
    val semanticParameters: List<String> = emptyList(),
    val bindingParameters: List<String> = emptyList(),
    val bindingContractId: String? = null,
    val resolvedParameters: Map<String, IntentValue> = emptyMap(),
    val parameterSources: Map<String, IntentBindingParameterSource> = emptyMap(),
    val effectPolicy: IntentBindingEffectPolicy? = null,
    val evidenceReferences: List<String> = emptyList(),
    val issues: List<IntentBindingIssue> = emptyList()
) {
    val resolved: Boolean get() = status == IntentBindingStatus.RESOLVED
}

data class IntentBindingIssue(
    val code: String,
    val message: String
)

data class CanonicalIntentResolution(
    val meaning: CanonicalIntentMeaning,
    val bindings: List<IntentBindingEvidence>
)

/**
 * Mandatory boundary between semantic intent and concrete module binding.
 *
 * [canonicalize] does not inspect a module registry. [resolve] adds explicit
 * binding evidence beside that meaning; it never mutates or enriches the
 * canonical meaning from inventory contents.
 */
class CanonicalIntentMeaningAuthority(registry: ModuleCatalog) {
    private val registry = ModuleCatalogIndex.capture(registry)

    fun resolve(intent: IntentDocument): CanonicalIntentResolution = CanonicalIntentResolution(
        meaning = canonicalize(intent),
        bindings = intent.workflows.flatMap { workflow -> workflow.steps.map { resolveBinding(intent, it) } }
    )

    private fun resolveBinding(intent: IntentDocument, step: IntentStep): IntentBindingEvidence {
        val requested = step.uses?.trim()?.takeIf { it.isNotEmpty() }
            ?: return IntentBindingEvidence(
                stepId = step.id,
                capability = step.capability,
                status = IntentBindingStatus.UNBOUND,
                semanticParameters = semanticParameterNames(step).sorted()
            )
        val issues = mutableListOf<IntentBindingIssue>()
        val parts = requested.split('.')
        val moduleName = parts.getOrNull(0)?.takeIf { it.isNotBlank() }
        val actionName = parts.getOrNull(1)?.takeIf { it.isNotBlank() }
        if (parts.size != 2 || moduleName == null || actionName == null) {
            issues += issue(
                "USES_REQUIRES_MODULE_ACTION",
                "Step '${step.id}' must declare uses as exactly '<module>.<action>', got '$requested'."
            )
        }

        val module = moduleName?.let(registry::findModule)
        if (moduleName != null && module == null) {
            issues += issue("BINDING_MODULE_UNKNOWN", "Step '${step.id}' binds to unknown module '$moduleName'.")
        }
        val action = if (module != null && actionName != null) module.actions[actionName] else null
        if (module != null && actionName != null && action == null) {
            issues += issue("BINDING_ACTION_UNKNOWN", "Step '${step.id}' binds to unknown action '$requested'.")
        }

        val implemented = action?.implementedCapabilities.orEmpty().sorted()
        if (action != null && step.capability.name !in action.implementedCapabilities) {
            issues += issue(
                "BINDING_CAPABILITY_NOT_IMPLEMENTED",
                "Action '$requested' does not declare implementation of canonical capability '${step.capability.name}'."
            )
        }
        val bindingContract = if (
            moduleName != null && actionName != null && action != null &&
            step.capability.name in action.implementedCapabilities
        ) {
            IntentBindingContractAuthority.derive(moduleName, actionName, step.capability, action)
        } else {
            null
        }

        val systemName = step.params[BINDING_SYSTEM_PARAM].asTextOrNull()
        if (action != null && systemName.isNullOrBlank()) {
            issues += issue(
                "EXPLICIT_BINDING_REQUIRES_SYSTEM",
                "Step '${step.id}' binds to '$requested' and must select a declared system through params.system."
            )
        }
        val system = systemName?.let { selected -> intent.systems.firstOrNull { it.name == selected } }
        if (!systemName.isNullOrBlank() && system == null) {
            issues += issue("UNKNOWN_INTENT_SYSTEM", "Step '${step.id}' references unknown system '$systemName'.")
        }
        val bindingSystemType = system?.type?.let(IntentSystemTypeAuthority::bindingType)
        if (action != null && bindingSystemType != null && bindingSystemType !in action.targetTypes) {
            issues += issue(
                "BINDING_SYSTEM_TYPE_MISMATCH",
                "Action '$requested' supports ${action.targetTypes.sorted()}, but system '$systemName' is '$bindingSystemType'."
            )
        }

        if (action != null && bindingContract != null) {
            validateParameters(step, requested, action, bindingContract, issues)
        }

        val semanticNames = semanticParameterNames(step)
        val bindingNames = bindingParameterNames(step, action, bindingContract, semanticNames)
        val valid = issues.isEmpty() && action != null && bindingContract != null
        val resolved = if (valid) {
            IntentBindingContractAuthority.resolveParameters(step, bindingContract, action)
        } else {
            ResolvedIntentBindingParameters(emptyMap(), emptyMap())
        }
        return IntentBindingEvidence(
            stepId = step.id,
            capability = step.capability,
            status = if (valid) IntentBindingStatus.RESOLVED else IntentBindingStatus.INVALID,
            requestedAction = requested,
            module = moduleName,
            action = actionName,
            system = systemName,
            systemType = bindingSystemType,
            implementedCapabilities = implemented,
            semanticParameters = semanticNames.sorted(),
            bindingParameters = bindingNames.sorted(),
            bindingContractId = bindingContract?.id,
            resolvedParameters = resolved.values,
            parameterSources = resolved.sources,
            effectPolicy = bindingContract?.effectPolicy,
            evidenceReferences = if (module != null && actionName != null) {
                listOf("module-contract:${module.name}/${module.version}/actions/$actionName")
            } else {
                emptyList()
            },
            issues = issues
        )
    }

    private fun validateParameters(
        step: IntentStep,
        requested: String,
        action: ModuleActionContract,
        binding: IntentBindingContract,
        issues: MutableList<IntentBindingIssue>
    ) {
        val candidateParams = step.params.filterKeys { it !in BINDING_METADATA_PARAMS }
        action.input.forEach { (required, field) ->
            if (field.required && field.defaultValue == null && candidateParams[required].isBlankIntent()) {
                issues += issue(
                    "BINDING_REQUIRED_PARAM_MISSING",
                    "Action '$requested' requires parameter '$required' on step '${step.id}'."
                )
            }
        }

        candidateParams.keys.filter { it in binding.unsupportedSemanticParameters }.forEach { name ->
            issues += issue(
                "BINDING_SEMANTIC_PARAM_UNSUPPORTED",
                "Action '$requested' cannot represent canonical parameter '$name' from step '${step.id}'."
            )
        }

        if (!action.additionalParams) {
            candidateParams.keys
                .filter { it !in action.input }
                .filter { it !in binding.unsupportedSemanticParameters }
                .forEach { name ->
                    issues += issue(
                        "BINDING_PARAMETER_UNKNOWN",
                        "Action '$requested' does not declare parameter '$name' on step '${step.id}'."
                    )
                }
        }
    }

    companion object {
        const val BINDING_SYSTEM_PARAM: String = "system"
        val BINDING_METADATA_PARAMS: Set<String> = setOf(BINDING_SYSTEM_PARAM, "tool", "engine")

        fun canonicalize(intent: IntentDocument): CanonicalIntentMeaning = CanonicalIntentMeaning(
            intentVersion = intent.intentVersion,
            kind = intent.kind,
            name = intent.name,
            description = intent.description,
            inputs = intent.inputs,
            triggers = intent.triggers,
            workflows = intent.workflows.map { workflow ->
                CanonicalIntentWorkflow(
                    name = workflow.name,
                    kind = workflow.kind,
                    steps = workflow.steps.map { step ->
                        CanonicalIntentStep(
                            id = step.id,
                            capability = step.capability,
                            description = step.description,
                            requires = step.requires,
                            produces = step.produces,
                            params = semanticParameters(step),
                            effects = CanonicalIntentEffectAuthority.effectsFor(step.capability, semanticParameters(step))
                        )
                    }
                )
            },
            controlRequirements = CanonicalControlRequirementAuthority.requirementsFor(intent),
            topologyRequirements = CanonicalTopologyRequirementAuthority.requirementsFor(intent),
            policies = intent.policies,
            failure = intent.failure
        )

        fun semanticParameters(step: IntentStep): Map<String, IntentValue> {
            val names = semanticParameterNames(step)
            return step.params.filterKeys { it in names }
        }

        fun semanticParameterNames(step: IntentStep): Set<String> =
            step.params.keys.intersect(allowedSemanticParameterNames(step))

        private fun allowedSemanticParameterNames(step: IntentStep): Set<String> {
            if (step.capability == StandardCapability.CUSTOM) {
                return step.params.keys - BINDING_METADATA_PARAMS
            }
            val contract = StandardCapabilityContracts.requireContract(step.capability)
            return (contract.requiredParams + contract.optionalParams).toSet() - BINDING_METADATA_PARAMS
        }

        private fun bindingParameterNames(
            step: IntentStep,
            action: ModuleActionContract?,
            binding: IntentBindingContract?,
            semanticNames: Set<String>
        ): Set<String> {
            val actionInputs = action?.input?.keys.orEmpty()
            return step.params.keys.filterTo(linkedSetOf()) { name ->
                name in BINDING_METADATA_PARAMS ||
                    name in binding?.bindingOnlyParameters.orEmpty() ||
                    (action?.additionalParams == true && name !in semanticNames && name !in BINDING_METADATA_PARAMS) ||
                    (name in actionInputs && name !in semanticNames)
            }
        }

        private fun IntentValue?.isBlankIntent(): Boolean = when (this) {
            null, is IntentNull -> true
            is IntentString -> value.isBlank()
            is IntentSecretRef -> name.isBlank()
            else -> false
        }

        private fun issue(code: String, message: String) = IntentBindingIssue(code = code, message = message)
    }
}
