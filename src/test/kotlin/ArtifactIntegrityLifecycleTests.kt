package org.flowlang.conformance

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.*

class ArtifactIntegrityLifecycleTests {
    private fun live() = WorkflowSemanticsRecoveryLifecycle.load(File("."))
    @Suppress("UNCHECKED_CAST") private fun section(value: Any?) = value as Map<String, Any?>
    @Suppress("UNCHECKED_CAST") private fun records(value: Any?) = value as List<Map<String, Any?>>
    private fun reject(s: WorkflowSemanticsRecoveryLifecycleSnapshot) = assertTrue(WorkflowSemanticsRecoveryLifecycle.errors(s).isNotEmpty())

    @Test fun cliSliceActivatesOnlyItsOwnWorkAfterAcceptedLanguageCompletion() {
        val s = live()
        assertTrue(WorkflowSemanticsRecoveryLifecycle.errors(s).isEmpty(), WorkflowSemanticsRecoveryLifecycle.errors(s).joinToString(" | "))
        assertEquals("AR-05A", s.artifactWorkPackage["selectedSlice"])
        assertEquals("complete", s.integrityWorkPackage["status"])
        assertEquals("AR-05", section(s.recovery["currentDecision"])["nextItem"])
        assertTrue(WorkflowSemanticsRecoveryLifecycle.errors(languageIntegrityCompletedSnapshot()).isEmpty())
        assertTrue(WorkflowSemanticsRecoveryLifecycle.errors(languageIntegrityImplementationSnapshot()).isEmpty())
    }

    @Test fun everyBaselineFieldAndImmutableEvidenceAreRequired() {
        val s = live()
        val baseline = section(s.artifactWorkPackage["verifiedBaseline"])
        for (key in baseline.keys) for (value in listOf(null, false, "invented", -1)) {
            reject(s.copy(artifactWorkPackage = s.artifactWorkPackage + ("verifiedBaseline" to (baseline + (key to value)))))
        }
        for (key in baseline.keys) reject(s.copy(artifactWorkPackage = s.artifactWorkPackage + ("verifiedBaseline" to (baseline - key))))
        for (raw in listOf(null, "", "malformed: [", s.languageCompletionEvidence, s.artifactActivationEvidence + "\n")) {
            reject(s.copy(artifactActivationEvidence = raw))
        }
    }

    @Test fun currentPointersMustAgreeAndCannotSkipOrCompleteSlices() {
        val s = live()
        for ((owner, name) in listOf(s.recovery to "currentDecision", s.postToolchain to "currentDecision",
            s.postToolchain to "recoveryRoadmap", s.release to "roadmapState")) {
            val keys = when (name) {
                "roadmapState" -> listOf("activeRecoveryWorkPackage", "nextRecoveryActivationState", "completedRecoveryItem")
                "recoveryRoadmap" -> listOf("activeItem", "activeWorkPackage", "nextItem", "activationState")
                else -> listOf("workPackage", "nextItem", "activationState")
            }
            for (key in keys) {
                val changed = owner + (name to (section(owner[name]) - key))
                reject(when { owner === s.recovery -> s.copy(recovery = changed)
                    owner === s.release -> s.copy(release = changed)
                    else -> s.copy(postToolchain = changed) })
            }
        }
        for (key in listOf("status", "selectedSlice", "nextSlice", "authorization", "lifecycle", "completionDecision")) {
            reject(s.copy(artifactWorkPackage = s.artifactWorkPackage + (key to "complete")))
        }
        val slices = records(s.artifactWorkPackage["implementationSlices"])
        for (slice in slices) reject(s.copy(artifactWorkPackage = s.artifactWorkPackage + ("implementationSlices" to slices.map {
            if (it["id"] == slice["id"]) it + ("status" to "complete") else it })))
    }

    @Test fun historicalEvidenceAndFindingOwnershipRemainEnforced() {
        val s = live()
        reject(s.copy(integrityWorkPackage = s.integrityWorkPackage + ("status" to "active")))
        reject(s.copy(languageCompletionEvidence = s.languageCompletionEvidence + "\n"))
        reject(s.copy(global = s.global + ("currentTrack" to "AR-05")))
        for (finding in records(s.recovery["findingRegister"])) {
            reject(s.copy(recovery = s.recovery + ("findingRegister" to records(s.recovery["findingRegister"]).map {
                if (it["id"] == finding["id"]) it + ("status" to "invented") else it })))
        }
        for (id in listOf("AR-06", "AR-07")) reject(s.copy(recovery = s.recovery + ("milestones" to records(s.recovery["milestones"]).map {
            if (it["id"] == id) it + ("status" to "active") else it })))
        reject(s.copy(postToolchain = s.postToolchain + ("sequence" to records(s.postToolchain["sequence"]).map {
            if (it["id"] == "EF-09") it + ("status" to "active") else it })))
    }

    @Test fun fileLoaderRejectsMissingOrChangedActivationEvidence() {
        val root = createTempDirectory("artifact-activation-").toFile()
        try {
            File(".flow-agent").copyRecursively(File(root, ".flow-agent"))
            assertTrue(WorkflowSemanticsRecoveryLifecycle.errors(WorkflowSemanticsRecoveryLifecycle.load(root)).isEmpty())
            val evidence = File(root, ArtifactIntegrityLifecycle.EVIDENCE)
            evidence.appendText("\n")
            reject(WorkflowSemanticsRecoveryLifecycle.load(root))
            evidence.delete()
            reject(WorkflowSemanticsRecoveryLifecycle.load(root))
        } finally { root.deleteRecursively() }
    }
}
