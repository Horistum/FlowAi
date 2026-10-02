package org.flowlang.conformance

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.*

class IntegratedLanguageIntegrityLifecycleTests {
    private fun live() = languageIntegrityImplementationSnapshot()
    @Suppress("UNCHECKED_CAST") private fun section(value: Any?) = value as Map<String, Any?>
    @Suppress("UNCHECKED_CAST") private fun slices(value: Any?) = value as List<Map<String, Any?>>
    private fun rejected(value: WorkflowSemanticsRecoveryLifecycleSnapshot) =
        assertTrue(WorkflowSemanticsRecoveryLifecycle.errors(value).isNotEmpty())
    private fun changeSlice(current: WorkflowSemanticsRecoveryLifecycleSnapshot, id: String,
        transform: (Map<String, Any?>) -> Map<String, Any?>) = current.copy(integrityWorkPackage = current.integrityWorkPackage +
        ("implementationSlices" to slices(current.integrityWorkPackage["implementationSlices"]).map {
            if (it["id"] == id) transform(it) else it }))
    private fun changeBoundary(current: WorkflowSemanticsRecoveryLifecycleSnapshot, id: String,
        receipt: Map<String, Any?>) = current.copy(integrityWorkPackage = current.integrityWorkPackage +
        ("lifecycle" to (section(current.integrityWorkPackage["lifecycle"]) + (id to receipt))))

    @Test fun integratedAcceptanceRequiresAllFiveAcceptedPredecessorsAndCurrentValidation() {
        val current = live()
        assertEquals("AR-04F", current.integrityWorkPackage["selectedSlice"])
        assertEquals("", current.integrityWorkPackage["nextSlice"])
        assertTrue(WorkflowSemanticsRecoveryLifecycle.errors(current).isEmpty(),
            WorkflowSemanticsRecoveryLifecycle.errors(current).joinToString(" | "))
        for (id in listOf("AR-04A", "AR-04B", "AR-04C", "AR-04D", "AR-04E")) {
            rejected(changeSlice(current, id) { it + ("status" to "implemented") })
        }
        rejected(changeSlice(current, "AR-04F") { it + ("status" to "implemented") })
        rejected(changeSlice(current, "AR-04F") { it + ("acceptance" to mapOf("status" to "passed")) })
    }

    @Test fun everyStrictLoaderReceiptFieldAndBoundaryRemainsRequired() {
        val current = live()
        val receipt = section(slices(current.integrityWorkPackage["implementationSlices"])[4]["acceptance"])
        receipt.keys.forEach { field ->
            rejected(changeSlice(current, "AR-04E") { it + ("acceptance" to (receipt - field)) })
            rejected(changeSlice(current, "AR-04E") { it + ("acceptance" to (receipt + (field to "invented"))) })
        }
        rejected(changeSlice(current, "AR-04E") { it + ("acceptance" to (receipt + ("futureSuccess" to true))) })
        val evidence = current.strictLoaderEvidence
        for (field in listOf("head", "syntheticMerge", "mergedMain", "sourceTree", "workflowRunId", "postMerge", "physicalIsolation")) {
            rejected(current.copy(strictLoaderEvidence = evidence - field))
        }
        for (group in listOf("junit", "conformance")) {
            val boundaries = section(evidence[group])
            boundaries.keys.forEach { boundary ->
                val values = section(boundaries[boundary])
                values.keys.forEach { field ->
                    rejected(current.copy(strictLoaderEvidence = evidence +
                        (group to (boundaries + (boundary to (values - field))))))
                }
            }
        }
    }

    @Test fun loaderBindsTheActualStrictLoaderReceiptBytes() {
        val root = createTempDirectory("integrated-receipt-").toFile()
        try {
            File(".flow-agent").copyRecursively(File(root, ".flow-agent"))
            val before = WorkflowSemanticsRecoveryLifecycle.load(root)
            assertTrue(WorkflowSemanticsRecoveryLifecycle.errors(before).isEmpty())
            val receipt = File(root, LanguageContractIntegrityLifecycle.STRICT_LOADER_EVIDENCE)
            receipt.appendText("\n")
            val after = WorkflowSemanticsRecoveryLifecycle.load(root)
            assertEquals(before.strictLoaderEvidence, after.strictLoaderEvidence)
            assertNotEquals(before.strictLoaderSha256, after.strictLoaderSha256)
            rejected(after)
            receipt.delete()
            rejected(WorkflowSemanticsRecoveryLifecycle.load(root))
        } finally { root.deleteRecursively() }
    }

    @Test fun implementationAcceptancePreservesIndependentValidationAndCompletion() {
        val current = live()
        assertTrue(WorkflowSemanticsRecoveryLifecycle.errors(current).isEmpty())
        assertTrue(slices(current.integrityWorkPackage["implementationSlices"]).all { it["status"] == "complete" })
        val lifecycle = section(current.integrityWorkPackage["lifecycle"])
        assertEquals("passed", section(lifecycle["implementationBoundary"])["status"])
        for (name in listOf("validationBoundary", "completionBoundary")) {
            assertEquals(mapOf("status" to "pending"), section(lifecycle[name]))
            rejected(changeBoundary(current, name, section(lifecycle["implementationBoundary"])))
            rejected(changeBoundary(current, name, mapOf("status" to "pending", "workflowRunId" to 36971285367L)))
        }
        assertEquals("not-complete", section(current.integrityWorkPackage["completionDecision"])["status"])
        rejected(current.copy(integrityWorkPackage = current.integrityWorkPackage + ("status" to "complete")))
    }

    @Test fun everyIntegratedReceiptAndImplementationBoundaryFieldIsRequired() {
        val current = live()
        val receipt = section(slices(current.integrityWorkPackage["implementationSlices"])[5]["acceptance"])
        val boundary = section(section(current.integrityWorkPackage["lifecycle"])["implementationBoundary"])
        receipt.keys.forEach { field ->
            rejected(changeSlice(current, "AR-04F") { it + ("acceptance" to (receipt - field)) })
            rejected(changeSlice(current, "AR-04F") { it + ("acceptance" to (receipt + (field to "invented"))) })
        }
        boundary.keys.forEach { field ->
            rejected(changeBoundary(current, "implementationBoundary", boundary - field))
            for (value in listOf(null, "invented", true, -1, 1.0)) {
                rejected(changeBoundary(current, "implementationBoundary", boundary + (field to value)))
            }
        }
        rejected(changeSlice(current, "AR-04F") { it + ("acceptance" to (receipt + ("futureSuccess" to true))) })
        rejected(changeBoundary(current, "implementationBoundary", boundary + ("futureSuccess" to true)))
    }

    @Test fun integratedEvidenceCannotBeSubstitutedOrSelfAuthenticated() {
        val current = live()
        val document = requireNotNull(current.integratedLanguageEvidence)
        val altered = document.replace("\"tests\": 1733", "\"tests\": 1732")
        assertNotEquals(document, altered)
        for (value in listOf(null, "", "malformed: [", altered,
            File(LanguageContractIntegrityLifecycle.STRICT_LOADER_EVIDENCE).readText())) {
            rejected(current.copy(integratedLanguageEvidence = value))
        }
        val forgedDigest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(altered.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 0xff) }
        val forged = changeSlice(current.copy(integratedLanguageEvidence = altered), "AR-04F") {
            it + ("acceptance" to (section(it["acceptance"]) + ("evidenceSha256" to forgedDigest)))
        }
        val boundary = section(section(forged.integrityWorkPackage["lifecycle"])["implementationBoundary"])
        rejected(changeBoundary(forged, "implementationBoundary", boundary + ("evidenceSha256" to forgedDigest)))
        rejected(changeBoundary(current, "implementationBoundary",
            section(section(current.integrityWorkPackage["lifecycle"])["activationBoundary"])))
    }

    @Test fun loaderAuthenticatesTheIntegratedDocumentBeforeParsing() {
        val root = createTempDirectory("integrated-implementation-").toFile()
        try {
            File(".flow-agent").copyRecursively(File(root, ".flow-agent"))
            assertTrue(WorkflowSemanticsRecoveryLifecycle.errors(WorkflowSemanticsRecoveryLifecycle.load(root)).isEmpty())
            val receipt = File(root, LanguageContractIntegrityLifecycle.INTEGRATED_EVIDENCE)
            receipt.appendText("\n")
            rejected(WorkflowSemanticsRecoveryLifecycle.load(root))
            receipt.writeText("malformed: [")
            rejected(WorkflowSemanticsRecoveryLifecycle.load(root))
            receipt.delete()
            rejected(WorkflowSemanticsRecoveryLifecycle.load(root))
        } finally { root.deleteRecursively() }
    }

    @Test fun historicalIntegratedCandidateCannotInventImplementationAcceptance() {
        val current = live()
        val historical = changeBoundary(changeSlice(current, "AR-04F") { it + mapOf(
            "status" to "implemented", "acceptance" to mapOf("source" to "current-revision-ci",
                "requiredChecks" to listOf("compile-test-conformance", "merge-candidate-compile-test-conformance"))) },
            "implementationBoundary", mapOf("status" to "pending")).copy(integratedLanguageEvidence = null)
        assertTrue(WorkflowSemanticsRecoveryLifecycle.errors(historical).isEmpty(),
            WorkflowSemanticsRecoveryLifecycle.errors(historical).joinToString(" | "))
        rejected(changeSlice(historical, "AR-04F") { it + ("status" to "complete") })
        rejected(changeBoundary(historical, "implementationBoundary", mapOf("status" to "passed")))
    }

    @Test fun acceptedImplementationCannotCloseFindingsOrActivateTheSuccessor() {
        val current = live()
        for (id in listOf("F-07", "F-12", "F-13", "F-14", "F-21")) {
            rejected(current.copy(recovery = current.recovery + ("findingRegister" to
                slices(current.recovery["findingRegister"]).map { if (it["id"] == id) it + ("status" to "closed") else it })))
        }
        rejected(current.copy(recovery = current.recovery + ("milestones" to
            slices(current.recovery["milestones"]).map { if (it["id"] == "AR-05") it + ("status" to "active") else it })))
        val decision = section(current.integrityWorkPackage["completionDecision"])
        rejected(current.copy(integrityWorkPackage = current.integrityWorkPackage +
            ("completionDecision" to (decision + ("nextItemActivationState" to "active")))))
    }
}
