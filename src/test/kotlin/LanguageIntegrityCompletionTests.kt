package org.flowlang.conformance

import java.io.File
import java.security.MessageDigest
import kotlin.io.path.createTempDirectory
import kotlin.test.*

class LanguageIntegrityCompletionTests {
    private fun live() = WorkflowSemanticsRecoveryLifecycle.load(File("."))
    @Suppress("UNCHECKED_CAST") private fun section(value: Any?) = value as Map<String, Any?>
    @Suppress("UNCHECKED_CAST") private fun records(value: Any?) = value as List<Map<String, Any?>>
    private fun rejected(current: WorkflowSemanticsRecoveryLifecycleSnapshot) =
        assertTrue(WorkflowSemanticsRecoveryLifecycle.errors(current).isNotEmpty())
    private fun changeWork(current: WorkflowSemanticsRecoveryLifecycleSnapshot, key: String, value: Any?) =
        current.copy(integrityWorkPackage = current.integrityWorkPackage + (key to value))
    private fun boundary(current: WorkflowSemanticsRecoveryLifecycleSnapshot, name: String) =
        section(section(current.integrityWorkPackage["lifecycle"])[name])
    private fun changeBoundary(current: WorkflowSemanticsRecoveryLifecycleSnapshot, name: String, value: Any?) =
        changeWork(current, "lifecycle", section(current.integrityWorkPackage["lifecycle"]) + (name to value))

    @Test fun completedLanguageMilestoneAcceptsIndependentValidationAndActualMain() {
        val current = live()
        assertTrue(WorkflowSemanticsRecoveryLifecycle.errors(current).isEmpty(),
            WorkflowSemanticsRecoveryLifecycle.errors(current).joinToString(" | "))
        assertEquals("complete", current.integrityWorkPackage["status"])
        assertTrue(records(current.integrityWorkPackage["implementationSlices"]).all { it["status"] == "complete" })
        val boundaries = section(current.integrityWorkPackage["lifecycle"])
        assertEquals(4, boundaries.values.map { section(it)["workflowRunId"] }.distinct().size)
        assertEquals("push", boundary(current, "completionBoundary")["event"])
        assertEquals("", section(current.release["roadmapState"])["activeRecoveryWorkPackage"])
        assertEquals("AR-05", section(current.recovery["currentDecision"])["nextItem"])
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
            for (other in section(current.integrityWorkPackage["lifecycle"]).keys - name) {
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
            val value = section(current.integrityWorkPackage[name])
            for (key in value.keys) {
                rejected(changeWork(current, name, value - key))
                rejected(changeWork(current, name, value + (key to "invented")))
            }
            rejected(changeWork(current, name, value + ("futureSuccess" to true)))
        }
        for (slice in records(current.integrityWorkPackage["implementationSlices"])) {
            val values = records(current.integrityWorkPackage["implementationSlices"])
            rejected(changeWork(current, "implementationSlices", values.map {
                if (it["id"] == slice["id"]) it + ("status" to "implemented") else it }))
            rejected(changeWork(current, "implementationSlices", values.map {
                if (it["id"] == slice["id"]) it - "acceptance" else it }))
        }
        rejected(changeWork(current, "selectedSlice", "AR-04E"))
    }

    @Test fun allRoadmapPointersMustAgreeOnCompletedLanguageAndUnactivatedSuccessor() {
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

    @Test fun closurePreservesEveryFindingOwnerAndChangesOnlyFiveStatuses() {
        val current = live()
        val findings = records(current.recovery["findingRegister"])
        val owned = setOf("F-07", "F-12", "F-13", "F-14", "F-21")
        assertEquals(owned, findings.filter { it["closureEvidence"] == LanguageIntegrityCompletion.EVIDENCE }.map { it["id"] }.toSet())
        for (finding in findings) {
            for ((key, value) in listOf("closureMilestone" to "AR-99", "status" to "invented", "closureEvidence" to "invented")) {
                rejected(current.copy(recovery = current.recovery + ("findingRegister" to findings.map {
                    if (it["id"] == finding["id"]) it + (key to value) else it })))
            }
        }
        rejected(current.copy(recovery = current.recovery + ("findingRegister" to findings.dropLast(1))))
        rejected(current.copy(recovery = current.recovery + ("findingRegister" to (findings + findings.first()))))
        for (id in listOf("AR-05", "AR-06", "AR-07")) {
            rejected(current.copy(recovery = current.recovery + ("milestones" to records(current.recovery["milestones"]).map {
                if (it["id"] == id) it + ("status" to "active") else it })))
        }
        rejected(current.copy(postToolchain = current.postToolchain + ("sequence" to records(current.postToolchain["sequence"]).map {
            if (it["id"] == "EF-09") it + ("status" to "active") else it })))
    }

    @Test fun closureEvidenceCannotBeMissingSubstitutedOrSelfAuthenticated() {
        val current = live()
        val original = requireNotNull(current.languageCompletionEvidence)
        val changed = original.replace("\"tests\": 1739", "\"tests\": 1738")
        assertNotEquals(original, changed)
        for (document in listOf(null, "", "malformed: [", changed, current.integratedLanguageEvidence)) {
            rejected(current.copy(languageCompletionEvidence = document))
        }
        val forgedHash = MessageDigest.getInstance("SHA-256").digest(changed.toByteArray())
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        var forged = changeWork(current.copy(languageCompletionEvidence = changed), "acceptanceEvidence",
            mapOf("path" to LanguageIntegrityCompletion.EVIDENCE, "sha256" to forgedHash))
        for (name in listOf("validationBoundary", "completionBoundary")) {
            forged = changeBoundary(forged, name, boundary(forged, name) + ("evidenceSha256" to forgedHash))
        }
        rejected(forged)
    }

    @Test fun realFileLoaderRejectsChangedMalformedAndDeletedCompletionEvidence() {
        val root = createTempDirectory("language-completion-").toFile()
        try {
            File(".flow-agent").copyRecursively(File(root, ".flow-agent"))
            assertTrue(WorkflowSemanticsRecoveryLifecycle.errors(WorkflowSemanticsRecoveryLifecycle.load(root)).isEmpty())
            val evidence = File(root, LanguageIntegrityCompletion.EVIDENCE)
            evidence.appendText("\n")
            rejected(WorkflowSemanticsRecoveryLifecycle.load(root))
            evidence.writeText("malformed: [")
            rejected(WorkflowSemanticsRecoveryLifecycle.load(root))
            evidence.delete()
            rejected(WorkflowSemanticsRecoveryLifecycle.load(root))
        } finally { root.deleteRecursively() }
    }

    @Test fun acceptedImplementationStillCannotManufactureMilestoneCompletion() {
        val historical = languageIntegrityImplementationSnapshot()
        assertTrue(WorkflowSemanticsRecoveryLifecycle.errors(historical).isEmpty())
        rejected(changeWork(historical, "status", "complete"))
        for (name in listOf("validationBoundary", "completionBoundary")) {
            rejected(changeBoundary(historical, name, boundary(historical, "implementationBoundary")))
        }
        val current = live()
        rejected(current.copy(successorWorkPackage = current.successorWorkPackage + ("status" to "active")))
        rejected(current.copy(global = current.global + ("currentTrack" to "AR-05")))
    }
}
