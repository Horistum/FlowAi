package org.flowlang.tests

import org.flowlang.intent.PolicyCondition
import org.flowlang.intent.RetentionConstraintKind
import org.flowlang.intent.SafetyRequirement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Unit coverage for deterministic typed policy classification. */
class FlowPolicyConditionTests {

    @Test
    fun standardRequirementsParseRegardlessOfSeparators() {
        assertEquals(SafetyRequirement.REQUIRES_APPROVAL, (PolicyCondition.parse("requiresApproval") as PolicyCondition.Requirement).kind)
        assertEquals(SafetyRequirement.REQUIRES_APPROVAL, (PolicyCondition.parse("requires-approval") as PolicyCondition.Requirement).kind)
        assertEquals(SafetyRequirement.REQUIRES_APPROVAL, (PolicyCondition.parse("Requires Approval") as PolicyCondition.Requirement).kind)
        assertEquals(SafetyRequirement.REQUIRES_BACKUP, (PolicyCondition.parse("requiresBackup") as PolicyCondition.Requirement).kind)
    }

    @Test
    fun explicitRetentionFormsAreTypedWithoutSubstringInference() {
        val retention = PolicyCondition.parse("retention:14d") as PolicyCondition.RetentionRule
        assertEquals(RetentionConstraintKind.RETENTION, retention.kind)
        assertEquals("14d", retention.value)

        val ttl = PolicyCondition.parse("ttl: P30D") as PolicyCondition.RetentionRule
        assertEquals(RetentionConstraintKind.TTL, ttl.kind)
        assertEquals("P30D", ttl.value)

        val olderThan = PolicyCondition.parse("olderThan:30d") as PolicyCondition.RetentionRule
        assertEquals(RetentionConstraintKind.OLDER_THAN, olderThan.kind)
        assertEquals("30d", olderThan.value)
    }

    @Test
    fun environmentAndIncidentalRetentionTextRemainNonAuthoritativeCustomPolicy() {
        assertTrue(PolicyCondition.parse("environment != prod") is PolicyCondition.Custom)
        assertTrue(PolicyCondition.parse("onlyIf maintenanceWindow") is PolicyCondition.Custom)
        assertTrue(PolicyCondition.parse("notify when retention policy changes") is PolicyCondition.Custom)
    }

    @Test
    fun malformedStandardRetentionFailsClosedAsClarification() {
        listOf("retention", "retention:", "ttl:", "olderThan::30d").forEach { raw ->
            val result = PolicyCondition.analyze(raw)
            val parsed = result.condition as PolicyCondition.Requirement
            val issue = assertNotNull(result.issue, raw)
            assertEquals(SafetyRequirement.REQUIRES_CLARIFICATION, parsed.kind, raw)
            assertEquals("MALFORMED_RETENTION_POLICY", issue.code)
        }
    }

    @Test
    fun clarificationIsNeverMistakenForRetention() {
        val parsed = PolicyCondition.parse("requiresClarification")
        assertTrue(parsed is PolicyCondition.Requirement)
        assertEquals(SafetyRequirement.REQUIRES_CLARIFICATION, parsed.kind)
    }

    @Test
    fun unknownConditionsAreCustom() {
        assertTrue(PolicyCondition.parse("frobnicate the widget") is PolicyCondition.Custom)
        assertTrue(PolicyCondition.parse(null) is PolicyCondition.Custom)
    }
}
