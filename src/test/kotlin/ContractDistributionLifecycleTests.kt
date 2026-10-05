package org.flowlang.conformance

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.*

class ContractDistributionLifecycleTests {
    private fun live() = contractDistributionCandidateSnapshot()
    private fun reject(s: WorkflowSemanticsRecoveryLifecycleSnapshot) = assertTrue(WorkflowSemanticsRecoveryLifecycle.errors(s).isNotEmpty())

    @Test fun resourcesFollowIndependentlyAcceptedCliWithoutClosingFindings() {
        val s = live()
        assertEquals("AR-05B", s.artifactWorkPackage["selectedSlice"])
        assertTrue(WorkflowSemanticsRecoveryLifecycle.errors(s).isEmpty(), WorkflowSemanticsRecoveryLifecycle.errors(s).joinToString(" | "))
        assertTrue(WorkflowSemanticsRecoveryLifecycle.errors(cliArgumentCandidateSnapshot()).isEmpty())
        @Suppress("UNCHECKED_CAST") val completion = s.artifactWorkPackage["completionDecision"] as Map<String, Any?>
        assertEquals(emptyList<String>(), completion["closesFindings"])
    }

    @Test fun everyAcceptanceFieldAndImmutableActualMainReceiptAreRequired() {
        val s = live()
        @Suppress("UNCHECKED_CAST") val slices = s.artifactWorkPackage["implementationSlices"] as List<Map<String, Any?>>
        for (index in 0..1) {
            @Suppress("UNCHECKED_CAST") val acceptance = slices[index]["acceptance"] as Map<String, Any?>
            for (key in acceptance.keys) for (value in listOf(null, "invented", -1)) {
                val changed = slices.mapIndexed { i, slice -> if (i == index) slice + ("acceptance" to (acceptance + (key to value))) else slice }
                reject(s.copy(artifactWorkPackage = s.artifactWorkPackage + ("implementationSlices" to changed)))
            }
        }
        for (raw in listOf(null, "", s.artifactActivationEvidence, s.cliArgumentAcceptanceEvidence + "\n")) {
            reject(s.copy(cliArgumentAcceptanceEvidence = raw))
        }
    }

    @Test fun sliceAndMilestoneTransitionsCannotSkipIndependentAcceptance() {
        val s = live()
        @Suppress("UNCHECKED_CAST") val slices = s.artifactWorkPackage["implementationSlices"] as List<Map<String, Any?>>
        for (index in slices.indices) for (status in listOf("complete", "invented")) {
            reject(s.copy(artifactWorkPackage = s.artifactWorkPackage + ("implementationSlices" to
                slices.mapIndexed { i, slice -> if (i == index) slice + ("status" to status) else slice })))
        }
        for (key in listOf("nextSlice", "completionDecision", "lifecycle", "status")) {
            reject(s.copy(artifactWorkPackage = s.artifactWorkPackage + (key to "complete")))
        }
        reject(s.copy(languageCompletionEvidence = s.languageCompletionEvidence + "\n"))
    }

    @Test fun loaderRejectsMissingOrChangedCliAcceptanceFile() {
        val root = createTempDirectory("resource-lifecycle-").toFile()
        try {
            File(".flow-agent").copyRecursively(File(root, ".flow-agent"))
            assertTrue(WorkflowSemanticsRecoveryLifecycle.errors(WorkflowSemanticsRecoveryLifecycle.load(root)).isEmpty())
            val evidence = File(root, ContractDistributionLifecycle.CLI_EVIDENCE)
            evidence.appendText("\n")
            reject(WorkflowSemanticsRecoveryLifecycle.load(root))
            evidence.delete()
            reject(WorkflowSemanticsRecoveryLifecycle.load(root))
        } finally { root.deleteRecursively() }
    }
}

/** Coherent historical resource candidate; current limits acceptance is checked separately. */
internal fun contractDistributionCandidateSnapshot(): WorkflowSemanticsRecoveryLifecycleSnapshot {
    val current = WorkflowSemanticsRecoveryLifecycle.load(File("."))
    @Suppress("UNCHECKED_CAST")
    val slices = current.artifactWorkPackage["implementationSlices"] as List<Map<String, Any?>>
    return current.copy(artifactWorkPackage = current.artifactWorkPackage + mapOf(
        "selectedSlice" to "AR-05B", "nextSlice" to "AR-05C",
        "implementationSlices" to slices.mapIndexed { index, slice -> when (index) {
            1 -> slice + mapOf("status" to "implemented", "acceptance" to mapOf("source" to "current-revision-ci",
                "requiredChecks" to listOf("compile-test-conformance", "merge-candidate-compile-test-conformance")))
            2, 3, 4 -> (slice - "acceptance") + ("status" to "planned")
            else -> slice
        } }))
}
