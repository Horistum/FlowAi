package org.flowlang.intent

import org.flowlang.modules.ModuleCatalog
import org.flowlang.modules.ModuleCatalogIndex
import org.flowlang.controls.CanonicalControlRequirementAuthority
import org.flowlang.controls.ControlAssessment
import org.flowlang.modules.SchemaField
import org.flowlang.modules.SchemaTypeCompatibility
import org.flowlang.modules.SchemaValueKind
import org.flowlang.standard.StandardCapabilityContracts

/**
 * Capability/requirement validation for the Standard Intent Model.
 *
 * This layer runs before AST lowering. Its job is to catch target/system design
 * gaps while the document is still an intent, not after we already generated a
 * low-level Flow program. That keeps the user in the architect role instead of
 * forcing them to debug platform-specific runtime failures.
 */
class IntentCapabilityValidator(registry: ModuleCatalog) {
    private val registry = ModuleCatalogIndex.capture(registry)

    fun validate(intent: IntentDocument): IntentValidationReport {
        val issues = mutableListOf<IntentValidationIssue>()
        val resolution = CanonicalIntentMeaningAuthority(registry).resolve(intent)
        val bindingsByStep = resolution.bindings.associateBy { it.stepId }
        val steps = intent.workflows.flatMap { it.steps }
        val stepIds = steps.map { it.id }

        if (intent.name.isBlank()) issues += err("INTENT_NAME_EMPTY", "Intent name must not be empty.")
        intent.workflows.filter { it.name.isBlank() }.forEach {
            issues += err("EMPTY_INTENT_WORKFLOW_NAME", "Intent workflow name must not be empty.")
        }
        intent.workflows.groupBy { it.name }.filterValues { it.size > 1 }.keys.forEach { name ->
            issues += err("DUPLICATE_INTENT_WORKFLOW", "Intent workflow '$name' is declared more than once.")
        }
        if (!intent.failure.stopOnError) {
            issues += err(
                "STOP_ON_ERROR_FALSE_UNSUPPORTED",
                "failure.stopOnError=false is not represented by the canonical error-handler contract and cannot be lowered honestly."
            )
        }
        intent.policies.filter { it.type !in SUPPORTED_POLICY_TYPES }.forEach { policy ->
            issues += err(
                "UNSUPPORTED_INTENT_POLICY_LOWERING",
                "Policy '${policy.name}' uses type '${policy.type}', which has no canonical lowering contract."
            )
        }
        val workflowNames = intent.workflows.map { it.name }.filter(String::isNotBlank).toSet()
            .ifEmpty { setOf("main") }
        intent.triggers.groupBy { it.id }.filterValues { it.size > 1 }.keys.forEach { id ->
            issues += err("DUPLICATE_INTENT_TRIGGER", "Intent trigger '$id' is declared more than once.")
        }
        intent.triggers.forEach { trigger ->
            if (trigger.workflows.isEmpty()) {
                issues += err(
                    "EMPTY_TRIGGER_WORKFLOW_ROUTE",
                    "Trigger '${trigger.id}' must route to at least one workflow."
                )
            }
            trigger.workflows.filter(String::isBlank).forEach {
                issues += err(
                    "EMPTY_TRIGGER_WORKFLOW_ROUTE",
                    "Trigger '${trigger.id}' contains an empty workflow route."
                )
            }
            trigger.workflows.groupBy { it }.filterValues { it.size > 1 }.keys.forEach { workflow ->
                issues += err(
                    "DUPLICATE_TRIGGER_WORKFLOW_ROUTE",
                    "Trigger '${trigger.id}' routes to workflow '$workflow' more than once."
                )
            }
            trigger.workflows.filter { it !in workflowNames }.forEach { workflow ->
                issues += err("UNKNOWN_TRIGGER_WORKFLOW", "Trigger '${trigger.id}' references unknown workflow '$workflow'.")
            }
            when (trigger.type) {
                IntentTriggerType.SCHEDULE -> {
                    val schedule = trigger.schedule
                    if (schedule == null) issues += err("SCHEDULE_TRIGGER_MISSING_SCHEDULE", "Schedule trigger '${trigger.id}' must declare schedule evidence.")
                    else {
                        if (schedule.expression.isBlank()) issues += err("SCHEDULE_EXPRESSION_EMPTY", "Schedule trigger '${trigger.id}' must declare a non-empty expression.")
                        if (schedule.kind == IntentScheduleKind.INTERVAL && !ISO_INTERVAL.matches(schedule.expression)) {
                            issues += err("SCHEDULE_INTERVAL_INVALID", "Interval trigger '${trigger.id}' must use an ISO-8601 duration such as P30D.")
                        }
                    }
                }
                IntentTriggerType.EVENT, IntentTriggerType.WEBHOOK -> if (trigger.event.isNullOrBlank()) {
                    issues += err("EVENT_TRIGGER_MISSING_EVENT", "Trigger '${trigger.id}' must declare event.")
                }
                IntentTriggerType.MANUAL -> if (trigger.schedule != null || !trigger.event.isNullOrBlank()) {
                    issues += err("MANUAL_TRIGGER_HAS_EXTERNAL_CONDITION", "Manual trigger '${trigger.id}' must not declare schedule or event conditions.")
                }
            }
        }
        stepIds.groupBy { it }.filterValues { it.size > 1 }.keys.forEach { id ->
            issues += err("DUPLICATE_INTENT_STEP", "Intent step '$id' is declared more than once.")
        }
        val workflowByStep = intent.workflows.flatMap { workflow ->
            workflow.steps.map { step -> step.id to workflow.name }
        }.groupBy({ it.first }, { it.second })
        steps.forEach { step ->
            val owner = workflowByStep[step.id]?.singleOrNull()
            step.requires.filter { requirement ->
                requirement in stepIds && workflowByStep[requirement]?.singleOrNull() != owner
            }.forEach { dependency ->
                issues += err(
                    "CROSS_WORKFLOW_STEP_DEPENDENCY",
                    "Step '${step.id}' in workflow '$owner' cannot depend on '$dependency' in workflow " +
                        "'${workflowByStep[dependency]?.singleOrNull()}'. Inter-workflow ordering requires an explicit future contract."
                )
            }
            step.requires.filter { it !in stepIds }.forEach { missing ->
                issues += err("UNKNOWN_STEP_DEPENDENCY", "Unknown intent step dependency '$missing' required by '${step.id}'.")
            }
            step.produces.filter { it.isBlank() }.forEach {
                issues += err("EMPTY_STEP_OUTPUT", "Step '${step.id}' declares an empty output name.")
            }
            step.produces.groupBy { it }.filterValues { it.size > 1 }.keys.forEach { output ->
                issues += err("DUPLICATE_STEP_OUTPUT", "Step '${step.id}' declares output '$output' more than once.")
            }
        }

        issues += IntentSourceContradictionAuthority.validationIssues(intent)

        intent.systems.forEach { system ->
            val bindingType = IntentSystemTypeAuthority.bindingType(system.type)
            val resolved = registry.findSystemType(bindingType)
            if (resolved == null) {
                issues += warn("UNKNOWN_SYSTEM_TYPE", "System '${system.name}' uses unknown type '$bindingType'.")
            } else {
                val (_, contract) = resolved
                validateConfig("System '${system.name}'", system.config, contract.input, issues)
            }
        }

        val controlAssessment = CanonicalControlRequirementAuthority.assess(intent)
        issues += CanonicalControlRequirementAuthority.validationIssues(controlAssessment)

        resolution.bindings.flatMap { binding ->
            binding.issues.map { issue -> err(issue.code, issue.message) }
        }.forEach(issues::add)

        steps.forEach { step ->
            val binding = bindingsByStep.getValue(step.id)
            val bindingHints = step.params.keys.intersect(CanonicalIntentMeaningAuthority.BINDING_METADATA_PARAMS)
            if (step.uses.isNullOrBlank() && bindingHints.isNotEmpty()) {
                issues += err(
                    "BINDING_HINT_REQUIRES_USES",
                    "Step '${step.id}' declares binding metadata ${bindingHints.sorted()} without explicit uses: <module>.<action>."
                )
            }

            val contract = StandardCapabilityContracts.requireContract(step.capability)
            contract.requiredParams.forEach { required ->
                if (step.params[required].isBlankIntent()) {
                    issues += err("MISSING_REQUIRED_STEP_PARAM", "Step '${step.id}' capability '${step.capability}' requires params '$required'.")
                }
            }
            val acceptedBindingParams = binding.bindingParameters.toSet()
            if (step.capability != StandardCapability.CUSTOM) {
                step.params.keys
                    .filter { it !in (contract.requiredParams + contract.optionalParams) }
                    .filter { it !in acceptedBindingParams }
                    .filter { it !in CanonicalIntentMeaningAuthority.BINDING_METADATA_PARAMS }
                    .forEach { key ->
                        issues += err("UNKNOWN_STEP_PARAM", "Step '${step.id}' capability '${step.capability}' does not declare semantic params '$key'; accepting it would silently discard the value during canonical lowering.")
                    }
            }
        }

        detectCycles(steps, issues)
        return IntentValidationReport(
            valid = issues.none { it.level == "error" },
            issues = issues,
            meaning = resolution.meaning,
            bindings = resolution.bindings,
            controlAssessment = controlAssessment
        )
    }

    private fun validateConfig(
        label: String,
        config: Map<String, IntentValue>,
        schema: Map<String, SchemaField>,
        issues: MutableList<IntentValidationIssue>
    ) {
        schema.forEach { (key, field) ->
            if (field.required && config[key].isBlankIntent()) {
                issues += err("MISSING_SYSTEM_CONFIG", "$label requires config '$key'.")
            }
        }
        config.keys.filter { it !in schema.keys }.forEach { key ->
            issues += warn("UNKNOWN_SYSTEM_CONFIG", "$label does not define config '$key'.")
        }
        config.forEach { (key, value) ->
            val field = schema[key] ?: return@forEach
            if (field.sensitive && value !is IntentSecretRef) {
                issues += err("SECRET_CONFIG_NOT_SECRET_REF", "$label config '$key' is sensitive and must use secret:<NAME>.")
            }
            if (!matchesType(value, field)) {
                issues += err("SYSTEM_CONFIG_TYPE_MISMATCH", "$label config '$key' must be ${field.type}.")
            }
        }
    }

    private fun IntentValue?.isBlankIntent(): Boolean = when (this) {
        null, is IntentNull -> true
        is IntentString -> value.isBlank()
        is IntentSecretRef -> name.isBlank()
        else -> false
    }

    private fun matchesType(value: IntentValue, field: SchemaField): Boolean =
        SchemaTypeCompatibility.accepts(field.type, value.schemaValueKind())

    private fun IntentValue.schemaValueKind(): SchemaValueKind = when (this) {
        is IntentString -> SchemaValueKind.TEXT
        is IntentNumber -> SchemaValueKind.NUMBER
        is IntentBoolean -> SchemaValueKind.BOOLEAN
        is IntentNull -> SchemaValueKind.NULL
        is IntentList -> SchemaValueKind.LIST
        is IntentObject -> SchemaValueKind.MAP
        is IntentSecretRef -> SchemaValueKind.SECRET
        is IntentRef, is IntentExpression -> SchemaValueKind.DYNAMIC
    }

    private fun detectCycles(steps: List<IntentStep>, issues: MutableList<IntentValidationIssue>) {
        val byId = steps.associateBy { it.id }
        val state = mutableMapOf<String, VisitState>()
        val reported = linkedSetOf<String>()

        fun visit(id: String) {
            when (state[id]) {
                VisitState.DONE -> return
                VisitState.VISITING -> {
                    if (reported.add(id)) {
                        issues += err("CYCLIC_STEP_DEPENDENCY", "Cyclic dependency detected at step '$id'.")
                    }
                    return
                }
                null -> Unit
            }

            state[id] = VisitState.VISITING
            byId[id]?.requires.orEmpty().forEach { dep -> if (dep in byId) visit(dep) }
            state[id] = VisitState.DONE
        }

        steps.forEach { visit(it.id) }
    }

    private enum class VisitState { VISITING, DONE }

    private fun err(code: String, message: String) = IntentValidationIssue("error", code, message)

    companion object {
        private val SUPPORTED_POLICY_TYPES = setOf(IntentPolicyType.APPROVAL, IntentPolicyType.SAFETY, IntentPolicyType.CUSTOM)
        private val ISO_INTERVAL = Regex("""^P(?=\d|T\d)(?:\d+Y)?(?:\d+M)?(?:\d+D)?(?:T(?:\d+H)?(?:\d+M)?(?:\d+(?:\.\d+)?S)?)?$""")
    }

    private fun warn(code: String, message: String) = IntentValidationIssue("warning", code, message)
}

data class IntentValidationReport(
    val valid: Boolean,
    val issues: List<IntentValidationIssue> = emptyList(),
    val meaning: CanonicalIntentMeaning,
    val bindings: List<IntentBindingEvidence> = emptyList(),
    val controlAssessment: ControlAssessment = ControlAssessment()
) {
    fun assertValid() {
        if (!valid) error("Intent validation failed: " + issues.filter { it.level == "error" }.joinToString { it.code + ": " + it.message })
    }
}

data class IntentValidationIssue(
    val level: String,
    val code: String,
    val message: String
)