package org.flowlang.intent

import org.flowlang.modules.ModuleRegistry
import org.flowlang.modules.SchemaField
import org.flowlang.standard.StandardCapabilityContracts

/**
 * Capability/requirement validation for the Standard Intent Model.
 *
 * This layer runs before AST lowering. Its job is to catch target/system design
 * gaps while the document is still an intent, not after we already generated a
 * low-level Flow program. That keeps the user in the architect role instead of
 * forcing them to debug platform-specific runtime failures.
 */
class IntentCapabilityValidator(private val registry: ModuleRegistry = ModuleRegistry()) {
    private val safetyPolicyValidator = SafetyPolicyValidator()

    fun validate(intent: IntentDocument): IntentValidationReport {
        val issues = mutableListOf<IntentValidationIssue>()
        val systemsByName = intent.systems.associateBy { it.name }
        val steps = intent.workflows.flatMap { it.steps }
        val stepIds = steps.map { it.id }

        if (intent.name.isBlank()) issues += err("INTENT_NAME_EMPTY", "Intent name must not be empty.")
        val workflowNames = intent.workflows.map { it.name }.toSet()
        intent.triggers.groupBy { it.id }.filterValues { it.size > 1 }.keys.forEach { id ->
            issues += err("DUPLICATE_INTENT_TRIGGER", "Intent trigger '$id' is declared more than once.")
        }
        intent.triggers.forEach { trigger ->
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
        steps.forEach { step ->
            step.requires.filter { it !in stepIds }.forEach { missing ->
                issues += err("UNKNOWN_STEP_DEPENDENCY", "Unknown intent step dependency '$missing' required by '${step.id}'.")
            }
        }

        intent.systems.forEach { system ->
            val normalizedType = normalizeSystemType(system.type)
            val resolved = registry.findSystemType(normalizedType)
            if (resolved == null) {
                issues += warn("UNKNOWN_SYSTEM_TYPE", "System '${system.name}' uses unknown type '$normalizedType'.")
            } else {
                val (_, contract) = resolved
                validateConfig("System '${system.name}'", system.config, contract.input, issues)
            }
        }

        issues += MandatorySafetyPolicy.validate(intent)
        issues += safetyPolicyValidator.validate(intent)

        steps.forEach { step ->
            if (step.capability in setOf(StandardCapability.DEPLOY, StandardCapability.VERIFY) &&
                step.uses.isNullOrBlank() &&
                (step.params["engine"] != null || step.params["tool"] != null)
            ) {
                issues += err(
                    "TARGET_TOOL_HINT_REQUIRES_USES",
                    "Step '${step.id}' must express target-specific lowering through 'uses: <module>.<action>'; engine/tool hints are not target-neutral semantics."
                )
            }
            val contract = StandardCapabilityContracts.requireContract(step.capability)
            contract.requiredParams.forEach { required ->
                if (step.params[required].isBlankIntent()) {
                    issues += err("MISSING_REQUIRED_STEP_PARAM", "Step '${step.id}' capability '${step.capability}' requires params '$required'.")
                }
            }
            step.params.keys.filter { it !in (contract.requiredParams + contract.optionalParams) && contract.requiredParams.isNotEmpty() }.forEach { key ->
                issues += warn("UNKNOWN_STEP_PARAM", "Step '${step.id}' capability '${step.capability}' does not declare params '$key'.")
            }
            val requiredType = requiredSystemType(step)
            val explicitSystem = step.params["system"].asTextOrNull()
            if (explicitSystem != null) {
                val declared = systemsByName[explicitSystem]
                if (declared == null) {
                    issues += err("UNKNOWN_INTENT_SYSTEM", "Step '${step.id}' references unknown system '$explicitSystem'.")
                } else if (requiredType != null && normalizeSystemType(declared.type) != requiredType) {
                    issues += err(
                        "INTENT_SYSTEM_TYPE_MISMATCH",
                        "Step '${step.id}' requires system type '$requiredType', but system '$explicitSystem' is '${normalizeSystemType(declared.type)}'."
                    )
                }
            }

        }

        detectCycles(steps, issues)
        return IntentValidationReport(valid = issues.none { it.level == "error" }, issues = issues)
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

    private fun matchesType(value: IntentValue, field: SchemaField): Boolean = when (field.type) {
        "text", "duration" -> value is IntentString || value is IntentRef || value is IntentExpression || value is IntentSecretRef
        "secret" -> value is IntentSecretRef
        "boolean" -> value is IntentBoolean || (value is IntentString && (value.value.equals("true", true) || value.value.equals("false", true)))
        "number" -> value is IntentNumber || (value is IntentString && value.value.toDoubleOrNull() != null)
        "map", "object", "json", "yaml" -> value is IntentObject || field.type in setOf("json", "yaml")
        "list" -> value is IntentList
        "any" -> true
        else -> true
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

    private fun requiredSystemType(step: IntentStep): String? {
        step.uses?.substringBefore('.')?.takeIf { it.isNotBlank() }?.let { return normalizeSystemType(it) }
        return when (step.capability) {
            StandardCapability.CHECKOUT -> "git"
            StandardCapability.BUILD_IMAGE, StandardCapability.PUSH_IMAGE -> "docker"
            StandardCapability.NOTIFY -> "notify"
            StandardCapability.CALL_API -> "rest"
            else -> null
        }
    }

    private fun normalizeSystemType(type: String): String = when (type) {
        "dockerRegistry", "containerRegistry" -> "docker"
        "notification", "email" -> "notify"
        else -> type
    }

    private fun err(code: String, message: String) = IntentValidationIssue("error", code, message)

    companion object {
        private val ISO_INTERVAL = Regex("""^P(?=\d|T\d)(?:\d+Y)?(?:\d+M)?(?:\d+D)?(?:T(?:\d+H)?(?:\d+M)?(?:\d+(?:\.\d+)?S)?)?$""")
    }
    private fun warn(code: String, message: String) = IntentValidationIssue("warning", code, message)
}

data class IntentValidationReport(
    val valid: Boolean,
    val issues: List<IntentValidationIssue> = emptyList()
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
