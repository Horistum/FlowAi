package org.flowlang.conformance

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.*

class BoundedIoLifecycleTests {
    private fun live() = WorkflowSemanticsRecoveryLifecycle.load(File("."))
    private fun reject(s: WorkflowSemanticsRecoveryLifecycleSnapshot) = assertTrue(WorkflowSemanticsRecoveryLifecycle.errors(s).isNotEmpty())

    @Test fun limitsFollowAcceptedDistributionWithoutClaimingAtomicPublicationOrClosure() {
        val s = live()
        assertEquals("AR-05C", s.artifactWorkPackage["selectedSlice"])
        assertEquals("AR-05D", s.artifactWorkPackage["nextSlice"])
        assertEquals(emptyList(), WorkflowSemanticsRecoveryLifecycle.errors(s))
        assertEquals(emptyList(), WorkflowSemanticsRecoveryLifecycle.errors(contractDistributionCandidateSnapshot()))
        assertEquals(emptyList(), WorkflowSemanticsRecoveryLifecycle.errors(cliArgumentCandidateSnapshot()))
    }

    @Test fun everyAcceptanceFieldAndTheUnmodifiedActualMainReceiptAreRequired() {
        val s = live()
        @Suppress("UNCHECKED_CAST") val slices = s.artifactWorkPackage["implementationSlices"] as List<Map<String, Any?>>
        for (index in 0..2) {
            @Suppress("UNCHECKED_CAST") val acceptance = slices[index]["acceptance"] as Map<String, Any?>
            for (key in acceptance.keys) for (value in listOf(null, "invented", -1)) {
                val changed = slices.mapIndexed { i, slice -> if (i == index) slice + ("acceptance" to (acceptance + (key to value))) else slice }
                reject(s.copy(artifactWorkPackage = s.artifactWorkPackage + ("implementationSlices" to changed)))
            }
        }
        for (raw in listOf(null, "", s.cliArgumentAcceptanceEvidence, s.contractDistributionAcceptanceEvidence + "\n")) {
            reject(s.copy(contractDistributionAcceptanceEvidence = raw))
        }
        for (index in slices.indices) reject(s.copy(artifactWorkPackage = s.artifactWorkPackage +
            ("implementationSlices" to slices.mapIndexed { i, slice -> if (i == index) slice + ("status" to "complete") else slice })))
        for (key in listOf("selectedSlice", "nextSlice", "completionDecision", "lifecycle")) {
            reject(s.copy(artifactWorkPackage = s.artifactWorkPackage + (key to "complete")))
        }
    }

    @Test fun loaderRejectsChangedAndMissingDistributionReceipt() {
        val root = createTempDirectory("bounded-io-lifecycle-").toFile()
        try {
            File(".flow-agent").copyRecursively(File(root, ".flow-agent"))
            assertEquals(emptyList(), WorkflowSemanticsRecoveryLifecycle.errors(WorkflowSemanticsRecoveryLifecycle.load(root)))
            val file = File(root, BoundedIoLifecycle.EVIDENCE)
            file.appendText("\n")
            reject(WorkflowSemanticsRecoveryLifecycle.load(root))
            file.delete()
            reject(WorkflowSemanticsRecoveryLifecycle.load(root))
        } finally { root.deleteRecursively() }
    }
}
