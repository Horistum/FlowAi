package org.flowlang.conformance

import java.io.File
import java.security.MessageDigest
import kotlin.io.path.createTempDirectory
import kotlin.test.*

class ArtifactIntegrityCompletionTests {
    private fun live() = WorkflowSemanticsRecoveryLifecycle.load(File("."))
    @Suppress("UNCHECKED_CAST") private fun section(value: Any?) = value as Map<String, Any?>
    @Suppress("UNCHECKED_CAST") private fun records(value: Any?) = value as List<Map<String, Any?>>
    private fun rejected(current: WorkflowSemanticsRecoveryLifecycleSnapshot) =
        assertTrue(WorkflowSemanticsRecoveryLifecycle.errors(current).isNotEmpty())
    private fun changeWork(current: WorkflowSemanticsRecoveryLifecycleSnapshot, key: String, value: Any?) =
        current.copy(artifactWorkPackage = current.artifactWorkPackage + (key to value))
    private fun boundary(current: WorkflowSemanticsRecoveryLifecycleSnapshot, name: String) =
        section(section(current.artifactWorkPackage["lifecycle"])[name])
    private fun changeBoundary(current: WorkflowSemanticsRecoveryLifecycleSnapshot, name: String, value: Any?) =
        changeWork(current, "lifecycle", section(current.artifactWorkPackage["lifecycle"]) + (name to value))

    @Test fun completedArtifactMilestoneAcceptsIndependentValidationAndActualMain() {
        val current = live()
        assertTrue(WorkflowSemanticsRecoveryLifecycle.errors(current).isEmpty(),
            WorkflowSemanticsRecoveryLifecycle.errors(current).joinToString(" | "))
        assertEquals("complete", current.artifactWorkPackage["status"])
        assertTrue(records(current.artifactWorkPackage["implementationSlices"]).all { it["status"] == "accepted" })
        val boundaries = section(current.artifactWorkPackage["lifecycle"])
        assertEquals(4, boundaries.values.map { section(it)["workflowRunId"] }.distinct().size)
        assertEquals("push", boundary(current, "completionBoundary")["event"])
        assertEquals("", section(current.release["roadmapState"])["activeRecoveryWorkPackage"])
        assertEquals("AR-06", section(current.recovery["currentDecision"])["nextItem"])
        assertEquals("not-activated", section(current.recovery["currentDecision"])["activationState"])
    }

    @Test fun everyValidationAndCompletionReceiptFieldIsRequiredAndTyped() {
        val current = live()
        for (name in listOf("validationBoundary", "completionBoundary")) {
            val receipt = boundary(current, name)
            for (field in receipt.keys) {
                rejected(changeBoundary(current, name, receipt - field))
                for (value in listOf(null, true, -1, 1.0, "invented")) {
                    rejected(changeBoundary(current, name, receipt + (field to value)))
                }
            }
            rejected(changeBoundary(current, name, receipt + ("futureSuccess" to true)))
            for (other in section(current.artifactWorkPackage["lifecycle"]).keys - name) {
                rejected(changeBoundary(current, name, boundary(current, other)))
            }
        }
    }

    @Test fun actualMainCannotBeRelabeledAsAHeadOrSyntheticMerge() {
        val current = live()
        val validation = boundary(current, "validationBoundary")
        val completion = boundary(current, "completionBoundary")
        for (key in listOf("exactHead", "syntheticMergeCandidate")) {
            rejected(changeBoundary(current, "completionBoundary", completion + ("mainCommit" to validation[key])))
        }
        for (key in listOf("syntheticMergeCandidate", "mergeCandidateJobId")) {
            rejected(changeBoundary(current, "completionBoundary", completion + (key to validation[key])))
        }
        rejected(changeBoundary(current, "completionBoundary", completion + ("event" to "pull_request")))
        rejected(changeBoundary(current, "completionBoundary", completion + ("workflowRunId" to validation["workflowRunId"])))
    }

    @Test fun completionDecisionAndEvidenceReferenceCannotOmitOrInventFields() {
        val current = live()
        for (name in listOf("completionDecision", "acceptanceEvidence")) {
            val value = section(current.artifactWorkPackage[name])
            for (key in value.keys) {
                rejected(changeWork(current, name, value - key))
                rejected(changeWork(current, name, value + (key to "invented")))
            }
            rejected(changeWork(current, name, value + ("futureSuccess" to true)))
        }
        for (slice in records(current.artifactWorkPackage["implementationSlices"])) {
            val values = records(current.artifactWorkPackage["implementationSlices"])
            rejected(changeWork(current, "implementationSlices", values.map {
                if (it["id"] == slice["id"]) it + ("status" to "implemented") else it }))
            rejected(changeWork(current, "implementationSlices", values.map {
                if (it["id"] == slice["id"]) it - "acceptance" else it }))
        }
        rejected(changeWork(current, "selectedSlice", "AR-05D"))
        val historical = artifactIntegrityImplementationSnapshot()
        val downgraded = records(current.artifactWorkPackage["implementationSlices"]).map {
            if (it["id"] == "AR-05E") it + mapOf("status" to "implemented", "acceptance" to mapOf(
                "source" to "current-revision-ci", "requiredChecks" to listOf("compile-test-conformance", "merge-candidate-compile-test-conformance"))) else it }
        rejected(changeBoundary(changeWork(current, "implementationSlices", downgraded),
            "implementationBoundary", mapOf("status" to "pending")))
        assertEquals(emptyList(), WorkflowSemanticsRecoveryLifecycle.errors(historical))
    }

    @Test fun allRoadmapPointersMustAgreeOnCompletedArtifactAndUnactivatedSuccessor() {
        val current = live()
        fun checkSection(owner: Map<String, Any?>, name: String, keys: List<String>,
            change: (Map<String, Any?>) -> WorkflowSemanticsRecoveryLifecycleSnapshot) {
            val values = section(owner[name])
            for (key in keys) {
                rejected(change(owner + (name to (values - key))))
                rejected(change(owner + (name to (values + (key to "invented")))))
            }
        }
        checkSection(current.recovery, "currentDecision", listOf("previousCompletedItem", "nextItem", "activationState", "workPackage")) {
            current.copy(recovery = it) }
        checkSection(current.postToolchain, "currentDecision", listOf("completedItem", "nextItem", "activationState", "workPackage")) {
            current.copy(postToolchain = it) }
        checkSection(current.postToolchain, "recoveryRoadmap", listOf("completedItem", "activeItem", "activeWorkPackage", "nextItem", "activationState")) {
            current.copy(postToolchain = it) }
        checkSection(current.release, "roadmapState", listOf("completedRecoveryItem", "completedRecoveryWorkPackage", "activeRecoveryWorkPackage", "nextRecoveryItem", "nextRecoveryActivationState")) {
            current.copy(release = it) }
        checkSection(current.release, "recoveryAcceptance", section(current.release["recoveryAcceptance"]).keys.toList()) {
            current.copy(release = it) }
        for (key in listOf("status", "completedItem", "activeItem", "nextItem", "activationState", "workPackage")) {
            rejected(current.copy(postToolchain = current.postToolchain + ("sequence" to records(current.postToolchain["sequence"]).map {
                if (it["id"] == "ARCHITECTURE-RECOVERY") it - key else it })))
        }
    }

    @Test fun closurePreservesEveryFindingOwnerAndChangesOnlyFourStatuses() {
        val current = live()
        val findings = records(current.recovery["findingRegister"])
        val owned = setOf("F-03", "F-05", "F-19", "F-22")
        assertEquals(owned, findings.filter { it["closureEvidence"] == ArtifactIntegrityCompletion.EVIDENCE }.map { it["id"] }.toSet())
        for (finding in findings) {
            for ((key, value) in listOf("closureMilestone" to "AR-99", "status" to "invented", "closureEvidence" to "invented")) {
                rejected(current.copy(recovery = current.recovery + ("findingRegister" to findings.map {
                    if (it["id"] == finding["id"]) it + (key to value) else it })))
            }
        }
        rejected(current.copy(recovery = current.recovery + ("findingRegister" to findings.dropLast(1))))
        rejected(current.copy(recovery = current.recovery + ("findingRegister" to (findings + findings.first()))))
        rejected(current.copy(recovery = current.recovery + ("findingRegister" to findings.reversed())))
        rejected(current.copy(recovery = current.recovery + ("findingRegister" to findings.map {
            if (it["id"] == "F-20") it + ("closureEvidence" to ArtifactIntegrityCompletion.EVIDENCE) else it })))
        for (id in listOf("AR-06", "AR-07")) {
            rejected(current.copy(recovery = current.recovery + ("milestones" to records(current.recovery["milestones"]).map {
                if (it["id"] == id) it + ("status" to "active") else it })))
        }
        rejected(current.copy(postToolchain = current.postToolchain + ("sequence" to records(current.postToolchain["sequence"]).map {
            if (it["id"] == "EF-09") it + ("status" to "active") else it })))
    }

    @Test fun closureEvidenceCannotBeMissingSubstitutedOrSelfAuthenticated() {
        val current = live()
        val original = requireNotNull(current.artifactCompletionEvidence)
        val changed = original.replace("\"tests\": 1828", "\"tests\": 1827")
        assertNotEquals(original, changed)
        for (document in listOf(null, "", "malformed: [", changed, current.integratedProductAcceptanceEvidence)) {
            rejected(current.copy(artifactCompletionEvidence = document))
        }
        val forgedHash = MessageDigest.getInstance("SHA-256").digest(changed.toByteArray())
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        var forged = changeWork(current.copy(artifactCompletionEvidence = changed), "acceptanceEvidence",
            mapOf("path" to ArtifactIntegrityCompletion.EVIDENCE, "sha256" to forgedHash))
        for (name in listOf("validationBoundary", "completionBoundary")) {
            forged = changeBoundary(forged, name, boundary(forged, name) + ("evidenceSha256" to forgedHash))
        }
        rejected(forged)
    }

    @Test fun realFileLoaderRejectsChangedMalformedAndDeletedCompletionEvidence() {
        val root = createTempDirectory("artifact-completion-").toFile()
        try {
            File(".flow-agent").copyRecursively(File(root, ".flow-agent"))
            assertTrue(WorkflowSemanticsRecoveryLifecycle.errors(WorkflowSemanticsRecoveryLifecycle.load(root)).isEmpty())
            val evidence = File(root, ArtifactIntegrityCompletion.EVIDENCE)
            evidence.appendText("\n")
            rejected(WorkflowSemanticsRecoveryLifecycle.load(root))
            evidence.writeText("malformed: [")
            rejected(WorkflowSemanticsRecoveryLifecycle.load(root))
            evidence.delete()
            rejected(WorkflowSemanticsRecoveryLifecycle.load(root))
        } finally { root.deleteRecursively() }
    }

    @Test fun acceptedImplementationStillCannotManufactureMilestoneCompletion() {
        val historical = artifactIntegrityImplementationSnapshot()
        assertTrue(WorkflowSemanticsRecoveryLifecycle.errors(historical).isEmpty())
        rejected(changeWork(historical, "status", "complete"))
        for (name in listOf("validationBoundary", "completionBoundary")) {
            rejected(changeBoundary(historical, name, boundary(historical, "implementationBoundary")))
        }
        val current = live()
        rejected(current.copy(successorWorkPackage = current.successorWorkPackage + ("status" to "active")))
        rejected(current.copy(global = current.global + ("currentTrack" to "AR-06")))
    }
}
