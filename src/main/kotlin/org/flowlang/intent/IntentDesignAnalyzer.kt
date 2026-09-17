package org.flowlang.intent

import org.flowlang.modules.ModuleCatalog
import org.flowlang.modules.ModuleCatalogIndex
import org.flowlang.standard.FlowStandardVersions
import org.flowlang.standard.StandardIntentCatalog

/**
 * Design-time analysis for human/AI intent before lowering.
 *
 * This is intentionally not a low-level validation report. It explains what the
 * automation wants, what systems/configuration it requires and which questions
 * still need architectural decisions. The goal is to keep the user in the role
 * of solution architect, not syntax janitor.
 */
data class IntentDesignReport(
    val standardVersion: String = FlowStandardVersions.FLOW_STANDARD_VERSION,
    val intentName: String,
    val summary: String,
    val capabilities: List<IntentCapabilityUse> = emptyList(),
    val requiredSystems: List<IntentRequiredSystem> = emptyList(),
    val missingDecisions: List<String> = emptyList(),
    val assumptions: List<String> = emptyList(),
    val portabilityNotes: List<String> = emptyList()
)

data class IntentCapabilityUse(
    val stepId: String,
    val capability: String,
    val category: String,
    val maturity: String,
    val description: String
)

data class IntentRequiredSystem(
    val name: String,
    val type: String,
    val declared: Boolean,
    val requiredConfig: List<String> = emptyList(),
    val missingConfig: List<String> = emptyList(),
    val reason: String
)

class IntentDesignAnalyzer(registry: ModuleCatalog) {
    private val registry = ModuleCatalogIndex.capture(registry)

    fun analyze(intent: IntentDocument): IntentDesignReport {
        val declaredSystems = intent.systems.associateBy { it.name }
        val steps = intent.workflows.flatMap { it.steps }
        val resolution = CanonicalIntentMeaningAuthority(registry).resolve(intent)
        val capabilities = steps.map { step ->
            val def = StandardIntentCatalog.byCapability[step.capability]
            IntentCapabilityUse(
                stepId = step.id,
                capability = step.capability.name,
                category = def?.category ?: "extension",
                maturity = def?.maturity ?: "unknown",
                description = def?.description ?: "Custom or unknown capability."
            )
        }

        val required = linkedMapOf<String, IntentRequiredSystem>()
        fun requireSystem(name: String, type: String, reason: String) {
            val declared = declaredSystems[name]
            val contract = registry.findSystemType(type)?.second
            val requiredFields = contract?.input?.filter { it.value.required }?.keys?.toList().orEmpty()
            val missing = if (declared == null) requiredFields else requiredFields.filter { it !in declared.config }
            required[name] = IntentRequiredSystem(
                name = name,
                type = type,
                declared = declared != null,
                requiredConfig = requiredFields,
                missingConfig = missing,
                reason = reason
            )
        }

        declaredSystems.values.forEach { sys ->
            val bindingType = IntentSystemTypeAuthority.bindingType(sys.type)
            val contract = registry.findSystemType(bindingType)?.second
            val requiredFields = contract?.input?.filter { it.value.required }?.keys?.toList().orEmpty()
            required[sys.name] = IntentRequiredSystem(
                name = sys.name,
                type = bindingType,
                declared = true,
                requiredConfig = requiredFields,
                missingConfig = requiredFields.filter { it !in sys.config },
                reason = "Declared by intent."
            )
        }

        resolution.bindings.forEach { binding ->
            when (binding.status) {
                IntentBindingStatus.RESOLVED -> requireSystem(
                    name = requireNotNull(binding.system),
                    type = requireNotNull(binding.systemType),
                    reason = "Explicit binding '${binding.requestedAction}' for step '${binding.stepId}'."
                )
                IntentBindingStatus.UNBOUND -> if (binding.capability != StandardCapability.APPROVE) {
                    requireSystem(
                        name = "standard",
                        type = "standard",
                        reason = "Unbound canonical capability '${binding.capability}' in step '${binding.stepId}'."
                    )
                }
                IntentBindingStatus.INVALID -> Unit
            }
        }

        if (intent.failure.notify) requireSystem("standard", "standard", "Semantic failure notification policy.")
        if (intent.failure.rollback) requireSystem("standard", "standard", "Failure rollback policy.")

        val missingDecisions = mutableListOf<String>()
        steps.filter { it.capability == StandardCapability.CUSTOM }.forEach { missingDecisions += "Step '${it.id}' uses CUSTOM capability. Define an organization/module contract before production use." }
        required.values.filter { !it.declared }.forEach { missingDecisions += "Required system '${it.name}' of type '${it.type}' is not declared. Flow can infer the need, but credentials/config must be supplied by policy, environment or system catalog." }
        required.values.filter { it.missingConfig.isNotEmpty() }.forEach { missingDecisions += "System '${it.name}' is missing required config: ${it.missingConfig.joinToString()}." }

        val assumptions = ConventionResolver.assumptions(intent)

        val portability = mutableListOf<String>()
        if (steps.any { it.capability == StandardCapability.APPROVE } || intent.policies.any { it.type == IntentPolicyType.APPROVAL }) {
            portability += "Approval materialization varies by target and requires either a provider-backed native approval contract or explicit external-gate evidence."
        }
        if (steps.any { it.requires.isNotEmpty() }) portability += "Intent uses DAG dependencies through requires; target generator must preserve dependency semantics."
        if (resolution.bindings.any { it.status == IntentBindingStatus.RESOLVED }) portability += "Explicit module bindings are implementation evidence and do not alter canonical intent meaning."
        if (steps.any { it.capability in setOf(StandardCapability.BACKUP, StandardCapability.RESTORE, StandardCapability.SECRET_ROTATE, StandardCapability.RUNBOOK, StandardCapability.INCIDENT) }) {
            portability += "Operational intents may require organization-specific modules or policies. This is expected and should be modeled through capability contracts, not custom parser syntax."
        }

        return IntentDesignReport(
            intentName = intent.name,
            summary = intent.description ?: "Flow intent '${intent.name}' with ${steps.size} step(s) across ${intent.workflows.size} workflow(s).",
            capabilities = capabilities,
            requiredSystems = required.values.toList(),
            missingDecisions = missingDecisions,
            assumptions = assumptions,
            portabilityNotes = portability
        )
    }
}
