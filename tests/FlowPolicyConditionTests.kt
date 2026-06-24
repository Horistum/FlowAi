package org.flowlang.tests

import org.flowlang.intent.PolicyCondition
import org.flowlang.intent.SafetyRequirement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Unit coverage for the typed [PolicyCondition] parser. The condition string remains
 * the serialized form; these assert the additive, in-memory classification used by the
 * safety validator (and guard the v0.3.2 cleanup regression: a clarification condition
 * must never be read as a retention rule).
 */
class FlowPolicyConditionTests {

    @Test
    fun standardRequirementsParseRegardlessOfSeparators() {
        assertEquals(SafetyRequirement.REQUIRES_APPROVAL, (PolicyCondition.parse("requiresApproval") as PolicyCondition.Requirement).kind)
        assertEquals(SafetyRequirement.REQUIRES_APPROVAL, (PolicyCondition.parse("requires-approval") as PolicyCondition.Requirement).kind)
        assertEquals(SafetyRequirement.REQUIRES_APPROVAL, (PolicyCondition.parse("Requires Approval") as PolicyCondition.Requirement).kind)
        assertEquals(SafetyRequirement.REQUIRES_BACKUP, (PolicyCondition.parse("requiresBackup") as PolicyCondition.Requirement).kind)
    }

    @Test
    fun retentionRulesAreRecognized() {
        assertTrue(PolicyCondition.parse("retention:14d") is PolicyCondition.RetentionRule)
        assertTrue(PolicyCondition.parse("olderThan:30d") is PolicyCondition.RetentionRule)
        assertTrue(PolicyCondition.parse("environment != prod") is PolicyCondition.RetentionRule)
    }

    @Test
    fun clarificationIsNeverMistakenForRetention() {
        // Regression guard: a clarification requirement must classify as a Requirement,
        // never a RetentionRule, even though cleanup messages often mention "retention".
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
