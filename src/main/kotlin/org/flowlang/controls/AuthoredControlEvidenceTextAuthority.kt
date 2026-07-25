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
        if (denialPatterns.any { it.containsMatchIn(normalized) }) {
            return denied(normalized, "The authored text explicitly denies or declares unavailable control evidence.")
        }
        if (unresolvedPatterns.any { it.containsMatchIn(normalized) }) {
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
            backupIdentifier.matches(value) ||
            namedBackupReference.matches(value)
    }

    private fun concreteRollbackPlan(raw: String): Boolean {
        val value = raw.trim()
        val hasAction = rollbackActions.any { containsTerm(value, it) }
        val hasObject = rollbackObjects.any { containsTerm(value, it) }
        return tokenCount(value) >= 4 && hasAction &&
            (hasObject || concreteLocator(value) || changeReference.matches(value))
    }

    private fun concreteChangeReference(raw: String): Boolean {
        val value = raw.trim()
        return changeReference.matches(value) || uriReference.matches(value)
    }

    private fun concreteRetentionRule(raw: String): Boolean =
        retentionDuration.containsMatchIn(raw.trim())

    private fun concreteSafetyControl(raw: String): Boolean {
        val value = raw.trim()
        return dryRunControl.containsMatchIn(value) ||
            concreteBackupReference(value) ||
            concreteRollbackPlan(value) ||
            concreteChangeReference(value) ||
            concreteRetentionRule(value)
    }

    private fun concreteLocator(value: String): Boolean =
        uriReference.matches(value) ||
            unixPathReference.matches(value) ||
            windowsPathReference.matches(value)

    private fun containsTerm(value: String, term: String): Boolean =
        Regex("(?i)(^|[^A-Za-z0-9])${Regex.escape(term)}([^A-Za-z0-9]|$)").containsMatchIn(value)

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

    private val explicitConfirmations = setOf("true", "yes", "confirmed")

    private val denialPatterns = listOf(
        Regex("(^|[^a-z0-9])(false|no|none|denied|disabled|unavailable|missing|absent)([^a-z0-9]|$)"),
        Regex("(^|[^a-z0-9])not[ _-]+(available|confirmed|provided|present|created|complete|completed|approved)([^a-z0-9]|$)")
    )

    private val unresolvedPatterns = listOf(
        Regex("(^|[^a-z0-9])(unknown|unspecified|todo|tbd|pending|later|planned|unset|someday|eventually|maybe|placeholder)([^a-z0-9]|$)"),
        Regex("(^|[^a-z0-9])n[ /_-]*a([^a-z0-9]|$)"),
        Regex("(^|[^a-z0-9])not[ _-]+applicable([^a-z0-9]|$)"),
        Regex("(^|[^a-z0-9])ask[ _-]+later([^a-z0-9]|$)"),
        Regex("(^|[^a-z0-9])to[ _-]+be[ _-]+(defined|confirmed|provided|decided)([^a-z0-9]|$)"),
        Regex("(^|[^a-z0-9])(will|shall)[ _-]+be[ _-]+(defined|confirmed|provided|decided)([^a-z0-9]|$)")
    )

    private val uriReference = Regex("(?i)[A-Za-z][A-Za-z0-9+.-]*://\\S+")
    private val unixPathReference = Regex("(?:/|\\./|\\.\\./)[A-Za-z0-9._/-]+")
    private val windowsPathReference = Regex("(?i)[A-Z]:\\\\[A-Za-z0-9._\\\\ -]+")
    private val backupIdentifier = Regex("(?i)(backup|snapshot|archive)[-_:/][A-Za-z0-9][A-Za-z0-9._:/-]*")
    private val namedBackupReference = Regex(
        "(?i)(backup|snapshot|archive)\\s+[A-Za-z0-9._/-]*[0-9:/][A-Za-z0-9._:/-]*"
    )
    private val changeReference = Regex("(?i)([A-Z][A-Z0-9]{1,9}-[0-9]+|CHG[0-9]+|RFC[0-9]+)")
    private val retentionDuration = Regex(
        "(?i)\\b[1-9][0-9]*\\s*(minutes?|mins?|m|hours?|hrs?|h|days?|d|weeks?|wks?|w|months?|mos?|mo|years?|yrs?|y)\\b"
    )
    private val dryRunControl = Regex("(?i)\\bdry[ -]?run\\b")
    private val rollbackActions = setOf("restore", "rollback", "roll back", "revert", "redeploy", "fail over", "failover", "recover")
    private val rollbackObjects = setOf(
        "snapshot", "backup", "image", "version", "deployment", "database", "release",
        "configuration", "manifest", "commit", "artifact", "service"
    )
}
