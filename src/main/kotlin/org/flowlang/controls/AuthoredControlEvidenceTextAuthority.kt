package org.flowlang.controls

import org.flowlang.intent.IntentBoolean
import org.flowlang.intent.IntentString
import org.flowlang.intent.IntentValue

enum class AuthoredControlEvidenceTextStatus {
    CONFIRMED,
    DENIED,
    UNKNOWN
}

data class AuthoredControlEvidenceTextAssessment(
    val status: AuthoredControlEvidenceTextStatus,
    val normalizedValue: String?,
    val reason: String
)

/**
 * Classifies authored textual control evidence without treating arbitrary non-empty
 * text as proof. Positive confirmations and concrete, control-specific references
 * are accepted. Explicit denial remains unsatisfied. Placeholders, ambiguity and
 * unrelated prose remain unknown and therefore fail closed.
 */
object AuthoredControlEvidenceTextAuthority {
    fun assess(parameterName: String, value: IntentValue?): AuthoredControlEvidenceTextAssessment = when (value) {
        null -> unknown(null, "No authored value is present.")
        is IntentBoolean -> if (value.value) {
            confirmed("true", "The authored boolean explicitly confirms the control evidence.")
        } else {
            denied("false", "The authored boolean explicitly denies the control evidence.")
        }
        is IntentString -> assessText(parameterName, value.value)
        else -> unknown(null, "The authored value type is not valid textual control evidence.")
    }

    private fun assessText(parameterName: String, raw: String): AuthoredControlEvidenceTextAssessment {
        val normalized = normalize(raw)
        if (normalized.isBlank()) return unknown(normalized, "Blank text is not control evidence.")
        if (normalized in explicitDenials) {
            return denied(normalized, "The authored text explicitly denies or declares unavailable control evidence.")
        }
        if (normalized in placeholders || placeholderPatterns.any { it.matches(normalized) }) {
            return unknown(normalized, "Placeholder or unresolved text cannot satisfy a control requirement.")
        }
        if (normalized in explicitConfirmations) {
            return confirmed(normalized, "The authored text is an explicit positive confirmation.")
        }

        val confirmed = when (parameterName.lowercase()) {
            "backup" -> concreteBackupReference(raw)
            "rollbackplan" -> concreteRollbackPlan(raw)
            "changeticket", "changerequest", "ticket", "changeid" -> concreteChangeReference(raw)
            "retention" -> concreteRetentionRule(raw)
            "safety" -> concreteSafetyControl(raw)
            else -> false
        }
        return if (confirmed) {
            confirmed(normalized, "The authored text contains concrete control-specific evidence.")
        } else {
            unknown(normalized, "Unrecognized text is not positive control evidence.")
        }
    }

    private fun concreteBackupReference(raw: String): Boolean {
        val value = raw.trim()
        return concreteLocator(value) ||
            backupReference.matches(value) ||
            tokenCount(value) >= 2 && backupTerms.any { value.contains(it, ignoreCase = true) }
    }

    private fun concreteRollbackPlan(raw: String): Boolean {
        val value = raw.trim()
        return tokenCount(value) >= 3 && rollbackActions.any { value.contains(it, ignoreCase = true) }
    }

    private fun concreteChangeReference(raw: String): Boolean {
        val value = raw.trim()
        return changeReference.matches(value) || concreteLocator(value)
    }

    private fun concreteRetentionRule(raw: String): Boolean {
        val value = raw.trim()
        return retentionDuration.containsMatchIn(value) ||
            tokenCount(value) >= 2 && retentionTerms.any { value.contains(it, ignoreCase = true) }
    }

    private fun concreteSafetyControl(raw: String): Boolean {
        val value = raw.trim()
        return tokenCount(value) >= 2 && safetyTerms.any { value.contains(it, ignoreCase = true) }
    }

    private fun concreteLocator(value: String): Boolean =
        value.contains("://") ||
            value.startsWith("/") ||
            value.startsWith("./") ||
            value.startsWith("../") ||
            value.contains(Regex("[A-Za-z0-9._-]+/[A-Za-z0-9._/-]+"))

    private fun normalize(value: String): String = value.trim().lowercase().replace(Regex("\\s+"), " ")

    private fun tokenCount(value: String): Int = value
        .split(Regex("[^A-Za-z0-9]+"))
        .count { it.isNotBlank() }

    private fun confirmed(value: String?, reason: String) = AuthoredControlEvidenceTextAssessment(
        AuthoredControlEvidenceTextStatus.CONFIRMED,
        value,
        reason
    )

    private fun denied(value: String?, reason: String) = AuthoredControlEvidenceTextAssessment(
        AuthoredControlEvidenceTextStatus.DENIED,
        value,
        reason
    )

    private fun unknown(value: String?, reason: String) = AuthoredControlEvidenceTextAssessment(
        AuthoredControlEvidenceTextStatus.UNKNOWN,
        value,
        reason
    )

    private val explicitConfirmations = setOf(
        "true", "yes", "required", "confirmed", "available", "created", "present",
        "provided", "enabled", "complete", "completed", "approved"
    )
    private val explicitDenials = setOf(
        "false", "no", "none", "denied", "disabled", "unavailable", "not available",
        "not-confirmed", "not confirmed", "missing", "absent"
    )
    private val placeholders = setOf(
        "unknown", "unspecified", "n/a", "na", "not applicable", "todo", "tbd",
        "pending", "later", "ask later", "planned", "not set", "unset"
    )
    private val placeholderPatterns = listOf(
        Regex("to be (defined|confirmed|provided|decided)"),
        Regex("(will|shall) be (defined|confirmed|provided|decided)"),
        Regex("unknown.*"),
        Regex("pending.*")
    )
    private val backupReference = Regex("(?i)(backup|snapshot|archive)[-_:/ ]?[A-Za-z0-9][A-Za-z0-9._:/-]*")
    private val changeReference = Regex("(?i)([A-Z][A-Z0-9]{1,9}-[0-9]+|CHG[0-9]+|RFC[0-9]+)")
    private val retentionDuration = Regex("(?i)\\b[0-9]+\\s*(minute|hour|day|week|month|year)s?\\b")
    private val backupTerms = setOf("backup", "snapshot", "archive", "restore point")
    private val rollbackActions = setOf("restore", "rollback", "roll back", "revert", "redeploy", "fail over", "failover", "recover")
    private val retentionTerms = setOf("retain", "retention", "expire", "delete after", "policy")
    private val safetyTerms = setOf("approval", "dry run", "dry-run", "backup", "rollback", "retention", "guard", "change ticket")
}