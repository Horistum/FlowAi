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
        val effectiveIntent = MandatorySafetyPolicy.apply(intent)
        val issues = mutableListOf<IntentValidationIssue>()
        val systemsByName = effectiveIntent.systems.associateBy { it.name }
        val steps = effectiveIntent.workflows.flatMap { it.steps }
        val stepIds = steps.map { it.id }

        if (effectiveIntent.name.isBlank()) issues += err("INTENT_NAME_EMPTY", "Intent name must not be empty.")
        stepIds.groupBy { it }.filterValues { it.size > 1 }.keys.forEach { id ->
            issues += err("DUPLICATE_INTENT_STEP", "Intent step '$id' is declared more than once.")
        }
        steps.forEach { step ->
            step.requires.filter { it !in stepIds }.forEach { missing ->
                issues += err("UNKNOWN_STEP_DEPENDENCY", "Unknown intent step dependency '$missing' required by '${step.id}'.")
            }
        }

        effectiveIntent.systems.forEach { system ->
            val normalizedType = normalizeSystemType(system.type)
            val resolved = registry.findSystemType(normalizedType)
            if (resolved == null) {
                issues += warn("UNKNOWN_SYSTEM_TYPE", "System '${system.name}' uses unknown type '$normalizedType'.")
            } else {
                val (_, contract) = resolved
                validateConfig("System '${system.name}'", system.config, contract.input, issues)
            }
        }

        issues += safetyPolicyValidator.validate(effectiveIntent)

        steps.forEach { step ->
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

            // ArgoCD is selected by capability DEPLOY with engine/tool=argocd, not by a generic deploy alone.
            if (step.capability == StandardCapability.DEPLOY && (step.params["engine"].asTextOrNull() ?: step.params["tool"].asTextOrNull())?.equals("argocd", ignoreCase = true) == true) {
                val argoName = explicitSystem ?: "argo"
                val argo = systemsByName[argoName]
                if (argo == null) {
                    issues += err("MISSING_REQUIRED_SYSTEM", "ArgoCD deploy step '${step.id}' requires system '$argoName' of type 'argocd'.")
                } else if (normalizeSystemType(argo.type) != "argocd") {
                    issues += err("INTENT_SYSTEM_TYPE_MISMATCH", "ArgoCD deploy step '${step.id}' targets '$argoName', but it is '${normalizeSystemType(argo.type)}'.")
                } else {
                    val argocdContract = registry.findSystemType("argocd")?.second
                    if (argocdContract != null) validateConfig("System '$argoName'", argo.config, argocdContract.input, issues)
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

    private fun requiredSystemType(step: IntentStep): String? = when (step.capability) {
        StandardCapability.CHECKOUT -> "git"
        StandardCapability.BUILD_IMAGE, StandardCapability.PUSH_IMAGE -> "docker"
        StandardCapability.DEPLOY, StandardCapability.VERIFY -> if ((step.params["engine"].asTextOrNull() ?: step.params["tool"].asTextOrNull())?.equals("argocd", true) == true) "argocd" else "kubernetes"
        StandardCapability.NOTIFY -> "notify"
        StandardCapability.CALL_API -> "rest"
        else -> null
    }

    private fun normalizeSystemType(type: String): String = when (type) {
        "dockerRegistry", "containerRegistry" -> "docker"
        "notification", "email" -> "notify"
        else -> type
    }

    private fun err(code: String, message: String) = IntentValidationIssue("error", code, message)
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
