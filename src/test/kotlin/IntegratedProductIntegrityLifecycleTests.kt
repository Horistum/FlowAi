package org.flowlang.conformance

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.*

class IntegratedProductIntegrityLifecycleTests {
    private fun live() = integratedProductCandidateSnapshot()
    private fun reject(s: WorkflowSemanticsRecoveryLifecycleSnapshot) = assertTrue(WorkflowSemanticsRecoveryLifecycle.errors(s).isNotEmpty())

    @Test fun integrationFollowsAcceptedPublicationWithoutClaimingFutureClosure() {
        val s = live()
        assertEquals("AR-05E", s.artifactWorkPackage["selectedSlice"])
        assertEquals("", s.artifactWorkPackage["nextSlice"])
        assertEquals(emptyList(), WorkflowSemanticsRecoveryLifecycle.errors(s))
        assertEquals(emptyList(), WorkflowSemanticsRecoveryLifecycle.errors(boundedIoCandidateSnapshot()))
        assertEquals(emptyList(), WorkflowSemanticsRecoveryLifecycle.errors(contractDistributionCandidateSnapshot()))
        assertEquals(emptyList(), WorkflowSemanticsRecoveryLifecycle.errors(cliArgumentCandidateSnapshot()))
    }

    @Test fun everyAcceptanceFieldAndTheUnmodifiedActualMainReceiptAreRequired() {
        val s = live()
        @Suppress("UNCHECKED_CAST") val slices = s.artifactWorkPackage["implementationSlices"] as List<Map<String, Any?>>
        for (index in 0..4) {
            @Suppress("UNCHECKED_CAST") val acceptance = slices[index]["acceptance"] as Map<String, Any?>
            for (key in acceptance.keys) for (value in listOf(null, "invented", -1)) {
                val changed = slices.mapIndexed { i, slice -> if (i == index) slice + ("acceptance" to (acceptance + (key to value))) else slice }
                reject(s.copy(artifactWorkPackage = s.artifactWorkPackage + ("implementationSlices" to changed)))
            }
        }
        for (raw in listOf(null, "", s.cliArgumentAcceptanceEvidence, s.atomicPublicationAcceptanceEvidence + "\n")) {
            reject(s.copy(atomicPublicationAcceptanceEvidence = raw))
        }
        for (index in slices.indices) reject(s.copy(artifactWorkPackage = s.artifactWorkPackage +
            ("implementationSlices" to slices.mapIndexed { i, slice -> if (i == index) slice + ("status" to "complete") else slice })))
        for (key in listOf("selectedSlice", "nextSlice", "completionDecision", "lifecycle")) {
            reject(s.copy(artifactWorkPackage = s.artifactWorkPackage + (key to "complete")))
        }
    }

    @Test fun loaderRejectsChangedAndMissingPublicationReceipt() {
        val root = createTempDirectory("atomic-publication-lifecycle-").toFile()
        try {
            File(".flow-agent").copyRecursively(File(root, ".flow-agent"))
            assertEquals(emptyList(), WorkflowSemanticsRecoveryLifecycle.errors(WorkflowSemanticsRecoveryLifecycle.load(root)))
            val file = File(root, IntegratedProductIntegrityLifecycle.EVIDENCE)
            file.appendText("\n")
            reject(WorkflowSemanticsRecoveryLifecycle.load(root))
            file.delete()
            reject(WorkflowSemanticsRecoveryLifecycle.load(root))
        } finally { root.deleteRecursively() }
    }
}

/** Replay the integrated implementation candidate without borrowing later acceptance. */
internal fun integratedProductCandidateSnapshot(): WorkflowSemanticsRecoveryLifecycleSnapshot {
    val current = artifactIntegrityImplementationSnapshot()
    @Suppress("UNCHECKED_CAST")
    val slices = current.artifactWorkPackage["implementationSlices"] as List<Map<String, Any?>>
    @Suppress("UNCHECKED_CAST")
    val lifecycle = current.artifactWorkPackage["lifecycle"] as Map<String, Any?>
    return current.copy(integratedProductAcceptanceEvidence = null,
        artifactWorkPackage = current.artifactWorkPackage + mapOf(
            "lifecycle" to (lifecycle + ("implementationBoundary" to mapOf("status" to "pending"))),
            "implementationSlices" to slices.mapIndexed { index, slice -> if (index == 4)
                slice + mapOf("status" to "implemented", "acceptance" to mapOf("source" to "current-revision-ci",
                    "requiredChecks" to listOf("compile-test-conformance", "merge-candidate-compile-test-conformance")))
                else slice }))
}
