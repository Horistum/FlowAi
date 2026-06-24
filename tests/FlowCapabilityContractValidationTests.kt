package org.flowlang.tests

import org.flowlang.intent.IntentDocument
import org.flowlang.intent.IntentStep
import org.flowlang.intent.IntentString
import org.flowlang.intent.IntentToAstPlanner
import org.flowlang.intent.IntentValue
import org.flowlang.intent.IntentWorkflow
import org.flowlang.intent.IntentWorkflowKind
import org.flowlang.intent.StandardCapability
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Regression coverage for StandardCapabilityContracts.requiredParams enforcement
 * (IntentCapabilityValidator -> MISSING_REQUIRED_STEP_PARAM).
 *
 * These guard the class of defect that broke intent conformance in rc1.8.3: the
 * BACKUP capability requires a `subject` param, but a fixture supplied `source`.
 * Lowering must fail with MISSING_REQUIRED_STEP_PARAM when a required param is
 * absent and must succeed once the canonical param is provided.
 */
class FlowCapabilityContractValidationTests {

    private fun singleStep(cap: StandardCapability, params: Map<String, IntentValue>): IntentDocument =
        IntentDocument(
            name = "contract-test",
            workflows = listOf(
                IntentWorkflow(
                    name = "w",
                    kind = IntentWorkflowKind.CUSTOM,
                    steps = listOf(IntentStep("s", cap, params = params))
                )
            )
        )

    private fun assertMissingParam(cap: StandardCapability, param: String, params: Map<String, IntentValue>) {
        val error = assertFailsWith<IllegalStateException> {
            IntentToAstPlanner().plan(singleStep(cap, params))
        }
        val message = error.message ?: ""
        assertTrue(
            message.contains("MISSING_REQUIRED_STEP_PARAM") && message.contains("'$param'"),
            "Expected MISSING_REQUIRED_STEP_PARAM for '$param' on $cap but got: $message"
        )
    }

    private fun assertLowers(cap: StandardCapability, params: Map<String, IntentValue>) {
        // Must not throw: required params satisfied, intent lowers to an AST.
        IntentToAstPlanner().plan(singleStep(cap, params))
    }

    @Test
    fun backupRequiresSubjectParam() {
        assertMissingParam(StandardCapability.BACKUP, "subject", emptyMap())
    }

    @Test
    fun backupWithSubjectLowers() {
        // Mirrors the corrected conformance fixture (subject: database).
        assertLowers(StandardCapability.BACKUP, mapOf("subject" to IntentString("database")))
    }

    @Test
    fun backupWithLegacySourceParamStillFailsBecauseSubjectIsMissing() {
        // Guards the exact rc1.8.3 fixture regression: `source` is not the BACKUP subject.
        assertMissingParam(StandardCapability.BACKUP, "subject", mapOf("source" to IntentString("database")))
    }

    @Test
    fun restoreRequiresSubjectParam() {
        assertMissingParam(StandardCapability.RESTORE, "subject", emptyMap())
    }

    @Test
    fun restoreWithSubjectLowers() {
        assertLowers(StandardCapability.RESTORE, mapOf("subject" to IntentString("orders")))
    }

    @Test
    fun secretRotateRequiresSubjectParam() {
        assertMissingParam(StandardCapability.SECRET_ROTATE, "subject", emptyMap())
    }

    @Test
    fun secretRotateWithSubjectLowers() {
        assertLowers(StandardCapability.SECRET_ROTATE, mapOf("subject" to IntentString("api-token")))
    }

    @Test
    fun dataSyncRequiresSourceAndDestination() {
        assertMissingParam(StandardCapability.DATA_SYNC, "source", emptyMap())
        assertMissingParam(StandardCapability.DATA_SYNC, "destination", mapOf("source" to IntentString("crm")))
    }

    @Test
    fun dataSyncWithSourceAndDestinationLowers() {
        assertLowers(
            StandardCapability.DATA_SYNC,
            mapOf("source" to IntentString("crm"), "destination" to IntentString("warehouse"))
        )
    }
}
