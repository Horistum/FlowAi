package org.flowlang.conformance

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertTrue

/** The live implementation follows, rather than replaces, its independently verified activation. */
class SourceDeclarationLifecycleTests {
    private val snapshot get() = WorkflowSemanticsRecoveryLifecycle.load(File("."))

    @Test fun actualImplementationHasIndependentActivationAndKeepsLaterSlicesPlanned() {
        val current = snapshot
        assertTrue(WorkflowSemanticsRecoveryLifecycle.errors(current).isEmpty(), errors(current))
        val slices = current.integrityWorkPackage["implementationSlices"] as List<*>
        assertEquals("implemented", section(slices.first())["status"])
        assertTrue(slices.drop(1).all { section(it)["status"] == "planned" })
        assertEquals("pending", section(section(current.integrityWorkPackage["lifecycle"])["implementationBoundary"])["status"])
    }

    @Test fun eachAcceptedActivationReceiptFieldIsRequired() {
        val current = snapshot
        val receipt = section(section(current.integrityWorkPackage["lifecycle"])["activationBoundary"])
        for (field in receipt.keys) rejected(withReceipt(current, receipt - field), "Language activation receipt")
    }

    @Test fun missingChangedOrUnrelatedEvidenceCannotAuthorizeProductChanges() {
        val current = snapshot
        rejected(current.copy(languageActivationEvidence = emptyMap()), "activation evidence bytes")
        rejected(current.copy(languageActivationSha256 = null), "activation evidence bytes")
        rejected(current.copy(languageActivationSha256 = "1".repeat(64)), "activation evidence bytes")
        rejected(current.copy(languageActivationEvidence = current.languageActivationEvidence + ("pullRequest" to 177)), "activation evidence bytes")
    }

    @Test fun predecessorReceiptOrPendingStateCannotStandInForAcceptedActivation() {
        val current = snapshot
        rejected(withReceipt(current, mapOf("status" to "pending")), "Language activation receipt")
        val predecessor = section(section(current.successorWorkPackage["lifecycle"])["integratedBoundary"])
        rejected(withReceipt(current, predecessor), "Language activation receipt")
    }

    @Test fun forgedCountsOrNumbersCannotStandInForCompleteActivationTests() {
        val current = snapshot
        val receipt = section(section(current.integrityWorkPackage["lifecycle"])["activationBoundary"])
        for (field in listOf("workflowRunId", "exactHeadJobId", "mergeCandidateJobId", "kotlinTests", "failures")) {
            for (value in listOf<Any?>(null, true, "0", 0.0, -1)) {
                rejected(withReceipt(current, receipt + (field to value)), "Language activation receipt")
            }
        }
    }

    @Test fun activationEvidenceCannotConcealFailuresOrDifferentIdentitySets() {
        val current = snapshot
        val junit = section(current.languageActivationEvidence["junit"])
        for (candidate in listOf("exactHead", "mergeCandidate")) {
            for ((field, value) in mapOf("failures" to 1, "errors" to 1, "skipped" to 1,
                    "tests" to 1543, "uniqueIdentities" to 1543, "identitiesSha256" to "f".repeat(64))) {
                val changed = junit + (candidate to (section(junit[candidate]) + (field to value)))
                rejected(current.copy(languageActivationEvidence = current.languageActivationEvidence + ("junit" to changed)), "complete identical test sets")
            }
        }
    }

    @Test fun implementationRequiresItsOwnFutureCiRatherThanBorrowedSuccess() {
        val current = snapshot
        val lifecycle = section(current.integrityWorkPackage["lifecycle"])
        for (name in listOf("implementationBoundary", "validationBoundary", "completionBoundary")) {
            rejected(current.copy(integrityWorkPackage = current.integrityWorkPackage + ("lifecycle" to
                (lifecycle + (name to lifecycle["activationBoundary"])))), "future receipt")
        }
        val slices = current.integrityWorkPackage["implementationSlices"] as List<*>
        val changed = listOf(section(slices.first()) - "acceptance") + slices.drop(1)
        rejected(current.copy(integrityWorkPackage = current.integrityWorkPackage + ("implementationSlices" to changed)), "current-revision CI")
    }

    @Test fun activationEvidenceUsesTheRealStrictSingleReadBoundary() {
        val root = createTempDirectory("source-lifecycle-").toFile()
        try {
            File(".flow-agent").copyRecursively(File(root, ".flow-agent"))
            val current = WorkflowSemanticsRecoveryLifecycle.load(root)
            assertTrue(WorkflowSemanticsRecoveryLifecycle.errors(current).isEmpty(), errors(current))
            File(root, LanguageContractIntegrityLifecycle.ACTIVATION_EVIDENCE).appendText("\n")
            rejected(WorkflowSemanticsRecoveryLifecycle.load(root), "activation evidence bytes")
            File(root, LanguageContractIntegrityLifecycle.ACTIVATION_EVIDENCE).writeText("status: passed\nstatus: passed\n")
            assertFails { WorkflowSemanticsRecoveryLifecycle.load(root) }
        } finally { check(root.deleteRecursively()) }
    }

    private fun withReceipt(current: WorkflowSemanticsRecoveryLifecycleSnapshot, receipt: Map<String, Any?>): WorkflowSemanticsRecoveryLifecycleSnapshot =
        current.copy(integrityWorkPackage = current.integrityWorkPackage + ("lifecycle" to
            (section(current.integrityWorkPackage["lifecycle"]) + ("activationBoundary" to receipt))))
    private fun section(value: Any?): Map<String, Any?> = (value as Map<*, *>).entries.associate { it.key.toString() to it.value }
    private fun errors(current: WorkflowSemanticsRecoveryLifecycleSnapshot) = WorkflowSemanticsRecoveryLifecycle.errors(current).joinToString(" | ")
    private fun rejected(current: WorkflowSemanticsRecoveryLifecycleSnapshot, fragment: String) {
        assertTrue(WorkflowSemanticsRecoveryLifecycle.errors(current).any { fragment in it }, "Expected $fragment: ${errors(current)}")
    }
}
