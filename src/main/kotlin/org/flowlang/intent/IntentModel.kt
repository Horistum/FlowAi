package org.flowlang.intent

import org.flowlang.standard.FlowStandardVersions

/**
 * High-level Standard Intent Model.
 *
 * This is intentionally above low-level Flow statements. It captures the human/AI
 * automation intent before it is lowered to canonical Flow AST and then to an
 * Execution Plan. The model is normalized from YAML/JSON first so users do not
 * have to care about YAML style quirks or target-specific lifecycle details.
 */
data class IntentDocument(
    val intentVersion: String = FlowStandardVersions.INTENT_VERSION,
    val kind: String = "FlowIntentDocument",
    val name: String,
    val description: String? = null,
    val inputs: List<IntentInput> = emptyList(),
    val systems: List<IntentSystem> = emptyList(),
    val triggers: List<IntentTrigger> = emptyList(),
    val workflows: List<IntentWorkflow> = emptyList(),
    val policies: List<IntentPolicy> = emptyList(),
    val failure: IntentFailurePolicy = IntentFailurePolicy()
)

data class IntentInput(
    val name: String,
    val type: String = "text",
    val required: Boolean = false,
    val default: IntentValue? = null
)

/**
 * Structured value model for intent YAML/JSON.
 *
 * Earlier prototypes flattened maps/lists into JSON strings. That was convenient,
 * and also exactly how one accidentally rebuilds YAML pain inside a standard.
 * Flow keeps intent values structured until the lowering layer deliberately turns
 * them into Flow expressions.
 */
sealed interface IntentValue { val kind: String }

data class IntentString(val value: String, override val kind: String = "string") : IntentValue
data class IntentNumber(val value: Double, val isInteger: Boolean = false, override val kind: String = "number") : IntentValue
data class IntentBoolean(val value: Boolean, override val kind: String = "boolean") : IntentValue
data class IntentNull(override val kind: String = "null") : IntentValue
data class IntentList(val items: List<IntentValue> = emptyList(), override val kind: String = "list") : IntentValue
data class IntentObject(val fields: Map<String, IntentValue> = emptyMap(), override val kind: String = "object") : IntentValue
data class IntentSecretRef(val name: String, override val kind: String = "secret") : IntentValue
data class IntentRef(val path: List<String>, override val kind: String = "ref") : IntentValue
data class IntentExpression(val source: String, override val kind: String = "expression") : IntentValue

fun IntentValue?.asTextOrNull(): String? = when (this) {
    null, is IntentNull -> null
    is IntentString -> value
    is IntentNumber -> if (isInteger) value.toLong().toString() else value.toString()
    is IntentBoolean -> value.toString()
    is IntentSecretRef -> "secret:$name"
    is IntentRef -> "ref:" + path.joinToString(".")
    is IntentExpression -> "expr:$source"
    is IntentList -> items.joinToString(",") { it.asTextOrNull().orEmpty() }
    is IntentObject -> fields.entries.joinToString(",") { (k, v) -> "$k=${v.asTextOrNull().orEmpty()}" }
}

fun IntentValue?.asBooleanOrNull(): Boolean? = when (this) {
    is IntentBoolean -> value
    is IntentString -> when (value.trim().lowercase()) {
        "true" -> true
        "false" -> false
        else -> null
    }
    else -> null
}

/**
 * A platform-neutral system dependency declared by the intent author or AI.
 *
 * [config] intentionally exists at the intent layer. Requirement validation must
 * happen before AST lowering, otherwise missing values such as ArgoCD url/token
 * become runtime failures instead of useful design-time diagnostics.
 */
data class IntentSystem(
    val name: String,
    val type: String,
    val purpose: String? = null,
    val config: Map<String, IntentValue> = emptyMap()
)


data class IntentTrigger(
    val id: String,
    val type: IntentTriggerType,
    val workflows: List<String> = listOf("main"),
    val schedule: IntentSchedule? = null,
    val event: String? = null,
    val params: Map<String, IntentValue> = emptyMap()
)

enum class IntentTriggerType { MANUAL, SCHEDULE, EVENT, WEBHOOK }

data class IntentSchedule(
    val kind: IntentScheduleKind,
    val expression: String,
    val timezone: String? = null
)

enum class IntentScheduleKind { CRON, INTERVAL, CALENDAR }

data class IntentWorkflow(
    val name: String,
    val kind: IntentWorkflowKind,
    val steps: List<IntentStep> = emptyList()
)

enum class IntentWorkflowKind {
    BUILD,
    TEST,
    DEPLOY,
    SYNC,
    DATA_PIPELINE,
    BACKUP,
    RESTORE,
    CLEANUP,
    REPORT,
    PROVISION,
    RUNBOOK,
    INCIDENT,
    SECRET_ROTATION,
    CUSTOM
}

data class IntentStep(
    val id: String,
    val capability: StandardCapability,
    val description: String? = null,
    val uses: String? = null,
    val requires: List<String> = emptyList(),
    val produces: List<String> = emptyList(),
    val params: Map<String, IntentValue> = emptyMap()
)

enum class StandardCapability {
    CHECKOUT,
    BUILD,
    TEST,
    PACKAGE,
    BUILD_IMAGE,
    PUSH_IMAGE,
    DEPLOY,
    VERIFY,
    APPROVE,
    ROLLBACK,
    NOTIFY,
    SYNC,
    DATA_SYNC,
    DATA_TRANSFORM,
    TRANSFORM,
    VALIDATE,
    BACKUP,
    RESTORE,
    CLEANUP,
    PROVISION,
    DEPROVISION,
    DATABASE_MIGRATE,
    CERTIFICATE_RENEW,
    KUBERNETES_MAINTENANCE,
    RUNBOOK,
    INCIDENT,
    SECRET_ROTATE,
    POLICY_CHECK,
    RUN_COMMAND,
    CALL_API,
    CUSTOM
}

data class IntentPolicy(
    val name: String,
    val type: IntentPolicyType,
    val condition: String? = null,
    val message: String? = null
)

enum class IntentPolicyType {
    APPROVAL,
    SAFETY,
    RETRY,
    TIMEOUT,
    TARGET_COMPATIBILITY,
    SECRET_HANDLING,
    CUSTOM
}

data class IntentFailurePolicy(
    val notify: Boolean = false,
    val rollback: Boolean = false,
    val stopOnError: Boolean = true
)
