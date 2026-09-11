package org.flowlang.conformance

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class IntegratedModuleExtractionLifecycleTests {
    private val snapshot get() = moduleExtractionCandidateSnapshot()

    @Test fun integratedSliceRetainsAcceptedPredecessorsWithoutInventingItsOwnReceipt() {
        val current = snapshot
        assertTrue(WorkflowSemanticsRecoveryLifecycle.errors(current).isEmpty())
        assertEquals("AR-03D", current.successorWorkPackage["selectedSlice"])
        assertTrue(current.successorWorkPackage.containsKey("nextSlice"))
        assertEquals(null, current.successorWorkPackage["nextSlice"])
        assertEquals("pending", section(section(current.successorWorkPackage["lifecycle"])["completionBoundary"])["status"])
    }

    @Test fun integratedSliceRequiresDistinctMergedAdapterAndActualAcceptedRuns() {
        val current = snapshot
        listOf("predecessorMerge", "predecessorAcceptedHead").forEach { field ->
            rejected(change(current) { it + (field to null) }, "merged adapter predecessor")
            rejected(change(current) { it + (field to "a".repeat(40)) }, "merged adapter predecessor")
        }
        rejected(change(current) { it + ("predecessorMerge" to it["predecessorAcceptedHead"]) }, "merged adapter predecessor")
        rejected(change(current) { it + ("predecessorMainRunId" to it["predecessorAcceptedRunId"]) }, "merged adapter predecessor")
    }

    @Test fun acceptedRunAndBaselineFieldsRejectNonIntegerOrNonpositiveValues() {
        for (field in listOf("predecessorPullRequest", "predecessorMainRunId", "predecessorAcceptedRunId", "preservedBaselineTestIdentities")) {
            for (value in listOf<Any>(0, -1, 1.5, "175")) {
                rejected(change(snapshot) { it + (field to value) }, "test baseline")
            }
        }
    }

    @Test fun currentCandidateCannotSelfCertifyItsFutureCheckResult() {
        rejected(change(snapshot) { it + ("acceptance" to (section(it["acceptance"]) + ("conclusion" to "success"))) }, "manufactured result")
    }

    @Test fun productCannotBeRelabeledAsAVerificationDistribution() {
        rejected(change(snapshot) { it + ("productionProfile" to it["verificationProfile"]) }, "distribution profiles")
        rejected(change(snapshot) { it + ("productionProfile" to
            (section(it["productionProfile"]) + ("verificationDependencies" to "allowed"))) }, "distribution profiles")
    }

    @Test fun integratedEvidenceCannotOmitThePhysicalProductOrAdapterProof() {
        for (field in listOf("sourceOwnershipReport", "productIsolation", "compilerIsolation", "adapterIsolation", "requiredChecks")) {
            rejected(change(snapshot) { it + ("integratedEvidence" to (section(it["integratedEvidence"]) - field)) }, "physical deletion proofs")
        }
    }

    @Test fun integratedSliceCannotSkipItsCompatibilityDecisionOrActivateSuccessor() {
        rejected(change(snapshot) { it - "compatibilityInventory" }, "compatibility boundary inventory")
        val current = snapshot
        rejected(current.copy(successorWorkPackage = current.successorWorkPackage + ("nextSlice" to "AR-04")), "no implicit next activation")
    }

    @Test fun compatibilityContainmentCannotBePromotedToRetirement() {
        val current = snapshot
        val decision = section(current.successorWorkPackage["completionDecision"])
        for (patch in listOf(mapOf("candidateClosesFindings" to listOf("F-10", "F-20")),
            mapOf("containedFindings" to emptyList<String>()), mapOf("deferredClosureOwner" to "AR-03D"))) {
            rejected(current.copy(successorWorkPackage = current.successorWorkPackage + ("completionDecision" to (decision + patch))), "AR-07-owned")
        }
    }

    private fun change(current: WorkflowSemanticsRecoveryLifecycleSnapshot,
        mutation: (Map<Any?, Any?>) -> Map<Any?, Any?>): WorkflowSemanticsRecoveryLifecycleSnapshot {
        val work = current.successorWorkPackage
        val slices = (work["implementationSlices"] as List<*>).map { value ->
            val slice = section(value)
            if (slice["id"] == "AR-03D") mutation(slice) else slice
        }
        return current.copy(successorWorkPackage = work + ("implementationSlices" to slices))
    }

    private fun rejected(value: WorkflowSemanticsRecoveryLifecycleSnapshot, fragment: String) {
        val errors = WorkflowSemanticsRecoveryLifecycle.errors(value)
        assertTrue(errors.any { fragment in it }, errors.joinToString(" | "))
    }
    private fun section(value: Any?): Map<Any?, Any?> = (value as Map<*, *>).toMap()
}
