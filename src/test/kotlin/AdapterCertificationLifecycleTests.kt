package org.flowlang.conformance

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.*

class AdapterCertificationLifecycleTests {
    private fun live() = WorkflowSemanticsRecoveryLifecycle.load(File("."))
    @Suppress("UNCHECKED_CAST") private fun section(value: Any?) = value as Map<String, Any?>
    @Suppress("UNCHECKED_CAST") private fun records(value: Any?) = value as List<Map<String, Any?>>
    private fun rejected(s: WorkflowSemanticsRecoveryLifecycleSnapshot) =
        assertTrue(WorkflowSemanticsRecoveryLifecycle.errors(s).isNotEmpty())

    @Test fun activationAcceptsMergedContractWithoutClosingBehavioralFindings() {
        val s = live()
        assertEquals(emptyList(), WorkflowSemanticsRecoveryLifecycle.errors(s))
        assertEquals("active", s.certificationWorkPackage["status"])
        assertEquals(listOf("AR-06A"), s.certificationWorkPackage["acceptedSlices"])
        assertEquals("AR-06B", s.certificationWorkPackage["selectedSlice"])
        assertEquals("complete", s.artifactWorkPackage["status"])
        assertTrue(records(s.recovery["findingRegister"]).filter { it["closureMilestone"] == "AR-06" }.all { it["status"] != "closed" })
        assertEquals(emptyList(), WorkflowSemanticsRecoveryLifecycle.errors(artifactIntegrityCompletedSnapshot()))
    }

    @Test fun everyChangedPointerMustMatchTheAuthenticatedTransition() {
        val s = live()
        val groups = listOf("recovery" to "currentDecision", "post" to "currentDecision", "post" to "recoveryRoadmap",
            "release" to "roadmapState", "release" to "recoveryPreparation")
        for ((owner, name) in groups) {
            val map = when (owner) { "recovery" -> s.recovery; "post" -> s.postToolchain; else -> s.release }
            val fields = section(map[name])
            val keys = when (name) {
                "currentDecision" -> listOf("activationState", "workPackage")
                "recoveryRoadmap" -> listOf("activeItem", "activeWorkPackage", "activationState")
                "roadmapState" -> listOf("activeRecoveryWorkPackage", "nextRecoveryActivationState")
                else -> listOf("status", "milestoneActivation", "validation")
            }
            for (key in keys) for (value in listOf(null, "invented", true)) {
                val changed = map + (name to (fields + (key to value)))
                rejected(when (owner) { "recovery" -> s.copy(recovery = changed); "post" -> s.copy(postToolchain = changed); else -> s.copy(release = changed) })
            }
        }
    }

    @Test fun activationCannotInventCompletionAcceptanceOrSupportPromotion() {
        val s = live()
        for (key in listOf("status", "selectedSlice", "acceptedSlices", "activationEvidence", "validation"))
            rejected(s.copy(certificationWorkPackage = s.certificationWorkPackage + (key to "complete")))
        val authorization = section(s.certificationWorkPackage["authorization"])
        for (key in listOf("acceptedMain", "workflowRunId")) rejected(s.copy(certificationWorkPackage =
            s.certificationWorkPackage + ("authorization" to (authorization + (key to "invented")))))
        val milestones = records(s.recovery["milestones"])
        for (id in listOf("AR-06", "AR-07")) rejected(s.copy(recovery = s.recovery + ("milestones" to milestones.map {
            if (it["id"] == id) it + ("status" to "completed") else it })))
        val findings = records(s.recovery["findingRegister"])
        rejected(s.copy(recovery = s.recovery + ("findingRegister" to findings.map {
            if (it["closureMilestone"] == "AR-06") it + ("status" to "closed") else it })))
        rejected(s.copy(artifactCompletionEvidence = s.artifactCompletionEvidence + "\n"))
        rejected(s.copy(global = s.global + ("currentTrack" to "AR-06")))
    }

    @Test fun duplicateMilestoneAndSequenceCannotDisappearDuringReplay() {
        val s = live()
        val milestones = records(s.recovery["milestones"])
        rejected(s.copy(recovery = s.recovery + ("milestones" to (milestones + milestones.single { it["id"] == "AR-06" }))))
        val sequence = records(s.postToolchain["sequence"])
        rejected(s.copy(postToolchain = s.postToolchain + ("sequence" to (sequence + sequence.single { it["id"] == "ARCHITECTURE-RECOVERY" }))))
    }

    @Test fun evidenceIsAuthenticatedBeforeParsingAndCannotBeSelfAttested() {
        val s = live()
        for (raw in listOf(null, "malformed: [", s.certificationActivationEvidence + "\n"))
            rejected(s.copy(certificationActivationEvidence = raw))
        val ref = section(s.certificationWorkPackage["activationEvidence"])
        rejected(s.copy(certificationWorkPackage = s.certificationWorkPackage + ("activationEvidence" to (ref + ("sha256" to "a".repeat(64))))))
    }

    @Test fun realFileLoaderRejectsDeletedOrAlteredActivationEvidence() {
        val root = createTempDirectory("certification-activation-").toFile()
        try {
            File(".flow-agent").copyRecursively(File(root, ".flow-agent"))
            assertEquals(emptyList(), WorkflowSemanticsRecoveryLifecycle.errors(WorkflowSemanticsRecoveryLifecycle.load(root)))
            val file = File(root, AdapterCertificationLifecycle.EVIDENCE)
            file.appendText("\n"); rejected(WorkflowSemanticsRecoveryLifecycle.load(root))
            file.writeText("malformed: ["); rejected(WorkflowSemanticsRecoveryLifecycle.load(root))
            file.delete(); rejected(WorkflowSemanticsRecoveryLifecycle.load(root))
        } finally { root.deleteRecursively() }
    }
}
