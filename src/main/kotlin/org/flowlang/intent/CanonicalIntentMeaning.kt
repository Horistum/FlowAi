package org.flowlang.intent

import org.flowlang.modules.ModuleActionContract
import org.flowlang.modules.ModuleRegistry
import org.flowlang.standard.StandardCapabilityContracts

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
    val params: Map<String, IntentValue> = emptyMap()
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
class CanonicalIntentMeaningAuthority(private val registry: ModuleRegistry) {

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
        val normalizedSystemType = system?.type?.let(::normalizeSystemType)
        if (action != null && normalizedSystemType != null && normalizedSystemType !in action.targetTypes) {
            issues += issue(
                "BINDING_SYSTEM_TYPE_MISMATCH",
                "Action '$requested' supports ${action.targetTypes.sorted()}, but system '$systemName' is '$normalizedSystemType'."
            )
        }

        if (action != null) validateParameters(step, requested, action, issues)

        val semanticNames = semanticParameterNames(step)
        val bindingNames = bindingParameterNames(step, action, semanticNames)
        return IntentBindingEvidence(
            stepId = step.id,
            capability = step.capability,
            status = if (issues.isEmpty()) IntentBindingStatus.RESOLVED else IntentBindingStatus.INVALID,
            requestedAction = requested,
            module = moduleName,
            action = actionName,
            system = systemName,
            systemType = normalizedSystemType,
            implementedCapabilities = implemented,
            semanticParameters = semanticNames.sorted(),
            bindingParameters = bindingNames.sorted(),
            issues = issues
        )
    }

    private fun validateParameters(
        step: IntentStep,
        requested: String,
        action: ModuleActionContract,
        issues: MutableList<IntentBindingIssue>
    ) {
        val candidateParams = step.params.filterKeys { it !in BINDING_METADATA_PARAMS }
        action.input.filterValues { it.required }.keys.forEach { required ->
            if (candidateParams[required].isBlankIntent()) {
                issues += issue(
                    "BINDING_REQUIRED_PARAM_MISSING",
                    "Action '$requested' requires parameter '$required' on step '${step.id}'."
                )
            }
        }

        if (!action.additionalParams) {
            candidateParams.keys.filter { it !in action.input }.forEach { name ->
                if (name in semanticParameterNames(step)) {
                    issues += issue(
                        "BINDING_SEMANTIC_PARAM_UNSUPPORTED",
                        "Action '$requested' cannot represent canonical parameter '$name' from step '${step.id}'."
                    )
                } else {
                    issues += issue(
                        "BINDING_PARAMETER_UNKNOWN",
                        "Action '$requested' does not declare parameter '$name' on step '${step.id}'."
                    )
                }
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
                            params = semanticParameters(step)
                        )
                    }
                )
            },
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
            semanticNames: Set<String>
        ): Set<String> {
            val actionInputs = action?.input?.keys.orEmpty()
            return step.params.keys.filterTo(linkedSetOf()) { name ->
                name in BINDING_METADATA_PARAMS || (name in actionInputs && name !in semanticNames)
            }
        }

        private fun normalizeSystemType(type: String): String = when (type) {
            "dockerRegistry", "containerRegistry" -> "docker"
            "notification", "email" -> "notify"
            else -> type
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
