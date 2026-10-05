package org.flowlang.conformance

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.*

class AtomicPublicationLifecycleTests {
    private fun live() = WorkflowSemanticsRecoveryLifecycle.load(File("."))
    private fun reject(s: WorkflowSemanticsRecoveryLifecycleSnapshot) = assertTrue(WorkflowSemanticsRecoveryLifecycle.errors(s).isNotEmpty())

    @Test fun publicationFollowsAcceptedLimitsWithoutClaimingClosure() {
        val s = live()
        assertEquals("AR-05D", s.artifactWorkPackage["selectedSlice"])
        assertEquals("AR-05E", s.artifactWorkPackage["nextSlice"])
        assertEquals(emptyList(), WorkflowSemanticsRecoveryLifecycle.errors(s))
        assertEquals(emptyList(), WorkflowSemanticsRecoveryLifecycle.errors(boundedIoCandidateSnapshot()))
        assertEquals(emptyList(), WorkflowSemanticsRecoveryLifecycle.errors(contractDistributionCandidateSnapshot()))
        assertEquals(emptyList(), WorkflowSemanticsRecoveryLifecycle.errors(cliArgumentCandidateSnapshot()))
    }

    @Test fun everyAcceptanceFieldAndTheUnmodifiedActualMainReceiptAreRequired() {
        val s = live()
        @Suppress("UNCHECKED_CAST") val slices = s.artifactWorkPackage["implementationSlices"] as List<Map<String, Any?>>
        for (index in 0..3) {
            @Suppress("UNCHECKED_CAST") val acceptance = slices[index]["acceptance"] as Map<String, Any?>
            for (key in acceptance.keys) for (value in listOf(null, "invented", -1)) {
                val changed = slices.mapIndexed { i, slice -> if (i == index) slice + ("acceptance" to (acceptance + (key to value))) else slice }
                reject(s.copy(artifactWorkPackage = s.artifactWorkPackage + ("implementationSlices" to changed)))
            }
        }
        for (raw in listOf(null, "", s.cliArgumentAcceptanceEvidence, s.boundedIoAcceptanceEvidence + "\n")) {
            reject(s.copy(boundedIoAcceptanceEvidence = raw))
        }
        for (index in slices.indices) reject(s.copy(artifactWorkPackage = s.artifactWorkPackage +
            ("implementationSlices" to slices.mapIndexed { i, slice -> if (i == index) slice + ("status" to "complete") else slice })))
        for (key in listOf("selectedSlice", "nextSlice", "completionDecision", "lifecycle")) {
            reject(s.copy(artifactWorkPackage = s.artifactWorkPackage + (key to "complete")))
        }
    }

    @Test fun loaderRejectsChangedAndMissingLimitsReceipt() {
        val root = createTempDirectory("atomic-publication-lifecycle-").toFile()
        try {
            File(".flow-agent").copyRecursively(File(root, ".flow-agent"))
            assertEquals(emptyList(), WorkflowSemanticsRecoveryLifecycle.errors(WorkflowSemanticsRecoveryLifecycle.load(root)))
            val file = File(root, AtomicPublicationLifecycle.EVIDENCE)
            file.appendText("\n")
            reject(WorkflowSemanticsRecoveryLifecycle.load(root))
            file.delete()
            reject(WorkflowSemanticsRecoveryLifecycle.load(root))
        } finally { root.deleteRecursively() }
    }
}
