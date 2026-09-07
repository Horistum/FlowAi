package org.flowlang.tests

import kotlin.test.Test
import kotlin.test.assertTrue
import org.flowlang.conformance.Ar02ClosureLifecycle

class Ar02LifecycleReceiptBindingTests {
    @Test
    fun localValidationAcceptsOneCompleteReceipt() {
        val receipt = validReceipt()
        assertTrue(Ar02ClosureLifecycle.boundaryErrors("validationBoundary", receipt).isEmpty())
        assertTrue(Ar02ClosureLifecycle.validationAliasErrors(receipt, receipt).isEmpty())
    }

    @Test
    fun matchingHeadAndRunCannotBorrowOtherJobOrMergeEvidence() {
        val receipt = validReceipt()
        val mutations = mapOf(
            "exactHeadJobId" to 999991L,
            "mergeCandidateJobId" to 999992L,
            "workflowRunNumber" to 999993,
            "syntheticMergeCandidate" to "abcdef0123456789abcdef0123456789abcdef0123"
        )
        mutations.forEach { (field, value) ->
            val local = receipt + (field to value)
            assertTrue(Ar02ClosureLifecycle.boundaryErrors("localValidation", local).isEmpty())
            val errors = Ar02ClosureLifecycle.validationAliasErrors(local, receipt)
            assertTrue(errors.any { "localValidation.$field" in it }, "$field: ${errors.joinToString()}")
        }
    }

    @Test
    fun everyReceiptFieldIsRequiredOnBothSides() {
        val receipt = validReceipt()
        receipt.keys.forEach { field ->
            val errors = Ar02ClosureLifecycle.validationAliasErrors(receipt - field, receipt - field)
            assertTrue(errors.any { "localValidation.$field" in it }, "Missing $field was accepted.")
        }
    }

    @Test
    fun localAliasCannotChangeStatusConclusionHeadOrRun() {
        val receipt = validReceipt()
        mapOf(
            "status" to "pending",
            "conclusion" to "failure",
            "exactHead" to "abcdef0123456789abcdef0123456789abcdef0123",
            "workflowRunId" to 999994L
        ).forEach { (field, value) ->
            val errors = Ar02ClosureLifecycle.validationAliasErrors(receipt + (field to value), receipt)
            assertTrue(errors.any { "localValidation.$field" in it }, errors.joinToString())
        }
    }

    @Test
    fun yamlIntegerWidthsDoNotChangeReceiptIdentityButStringsDo() {
        val receipt = validReceipt()
        val longNumber = receipt + ("workflowRunNumber" to 17L)
        assertTrue(Ar02ClosureLifecycle.validationAliasErrors(longNumber, receipt).isEmpty())
        val stringNumber = receipt + ("workflowRunNumber" to "17")
        assertTrue(Ar02ClosureLifecycle.validationAliasErrors(stringNumber, receipt).isNotEmpty())
    }

    /** Synthetic unit-test evidence, never used as a repository completion receipt. */
    private fun validReceipt(): Map<String, Any> = mapOf(
        "status" to "passed",
        "conclusion" to "success",
        "workflowRunId" to 123456L,
        "workflowRunNumber" to 17,
        "exactHeadJobId" to 234567L,
        "mergeCandidateJobId" to 234568L,
        "exactHead" to "0123456789abcdef0123456789abcdef01234567",
        "syntheticMergeCandidate" to "123456789abcdef0123456789abcdef012345678"
    )
}
