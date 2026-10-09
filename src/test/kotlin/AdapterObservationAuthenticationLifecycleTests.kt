package org.flowlang.conformance

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.*

class AdapterObservationAuthenticationLifecycleTests {
    private fun live() = AdapterObservationAuthenticationLifecycle.runtimeViewPredecessor(WorkflowSemanticsRecoveryLifecycle.load(File(".")))
    @Suppress("UNCHECKED_CAST") private fun section(value: Any?) = value as Map<String, Any?>
    private fun rejected(s: WorkflowSemanticsRecoveryLifecycleSnapshot) =
        assertTrue(WorkflowSemanticsRecoveryLifecycle.errors(s).isNotEmpty())

    @Test fun acceptedBindingSelectsAuthenticationWithoutPromotingSupport() {
        val s = live()
        assertEquals(emptyList(), WorkflowSemanticsRecoveryLifecycle.errors(s))
        assertEquals("AR-06C", s.certificationWorkPackage["selectedSlice"])
        assertEquals(listOf("AR-06A", "AR-06B"), s.certificationWorkPackage["acceptedSlices"])
        assertEquals(false, section(s.release["recoveryCertification"])["supportPromotion"])
        val previous = AdapterObservationAuthenticationLifecycle.predecessorSnapshot(s)
        assertEquals("AR-06B", previous.certificationWorkPackage["selectedSlice"])
        assertEquals(emptyList(), WorkflowSemanticsRecoveryLifecycle.errors(previous))
    }

    @Test fun everyAcceptanceAndCandidatePointerIsCheckedBeforeReplay() {
        val s = live()
        for (key in listOf("selectedSlice", "selectedWorkPackage", "acceptedSlices", "bindingAcceptanceEvidence", "validation"))
            rejected(s.copy(certificationWorkPackage = s.certificationWorkPackage + (key to "invented")))
        for (key in listOf("version", "status", "stream", "roadmapReference", "parentWorkPackage", "authorization", "validation"))
            rejected(s.copy(observationAuthenticationWorkPackage = s.observationAuthenticationWorkPackage + (key to "invented")))
        for (key in listOf("acceptedMain", "workflowRunId")) rejected(s.copy(certificationWorkPackage = s.certificationWorkPackage +
            ("authorization" to (section(s.certificationWorkPackage["authorization"]) + (key to "invented")))))
        val release = section(s.release["recoveryCertification"])
        for (key in release.keys) for (value in listOf(null, "invented", true))
            rejected(s.copy(release = s.release + ("recoveryCertification" to (release + (key to value)))))
        rejected(s.copy(release = s.release - "recoveryCertification"))
    }

    @Test fun receiptMustMatchInspectedBytesBeforeParsing() {
        val s = live()
        for (raw in listOf(null, "malformed: [", s.certificationBindingEvidence + "\n"))
            rejected(s.copy(certificationBindingEvidence = raw))
        val ref = section(s.certificationWorkPackage["bindingAcceptanceEvidence"])
        rejected(s.copy(certificationWorkPackage = s.certificationWorkPackage +
            ("bindingAcceptanceEvidence" to (ref + ("sha256" to "a".repeat(64))))))
    }

    @Test fun priorAcceptanceAndOpenFindingsRemainEnforced() {
        val s = live()
        rejected(s.copy(certificationActivationEvidence = s.certificationActivationEvidence + "\n"))
        rejected(s.copy(artifactCompletionEvidence = s.artifactCompletionEvidence + "\n"))
        rejected(s.copy(global = s.global + ("currentTrack" to "AR-06")))
        rejected(s.copy(release = s.release + ("recoveryPreparation" to
            (section(s.release["recoveryPreparation"]) + ("supportPromotion" to true)))))
        @Suppress("UNCHECKED_CAST") val findings = s.recovery["findingRegister"] as List<Map<String, Any?>>
        rejected(s.copy(recovery = s.recovery + ("findingRegister" to findings.map {
            if (it["closureMilestone"] == "AR-06") it + ("status" to "closed") else it })))
    }

    @Test fun fileLoaderRejectsMissingWorkPackageAndChangedOrDeletedReceipt() {
        val root = createTempDirectory("observation-lifecycle-").toFile()
        try {
            File(".flow-agent").copyRecursively(File(root, ".flow-agent"))
            fun load() = WorkflowSemanticsRecoveryLifecycle.load(root)
            assertEquals(emptyList(), WorkflowSemanticsRecoveryLifecycle.errors(load()))
            val work = File(root, AdapterObservationAuthenticationLifecycle.WORK_PACKAGE)
            val original = work.readText(); work.delete(); rejected(load()); work.writeText(original)
            val evidence = File(root, AdapterObservationAuthenticationLifecycle.EVIDENCE)
            evidence.appendText("\n"); rejected(load())
            evidence.writeText("malformed: ["); rejected(load())
            evidence.delete(); rejected(load())
        } finally { root.deleteRecursively() }
    }
}
