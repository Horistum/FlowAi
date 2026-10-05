package org.flowlang.conformance

import java.io.File
import java.security.MessageDigest
import kotlin.io.path.createTempDirectory
import kotlin.test.*

class IntegratedProductAcceptanceTests {
    private fun live() = artifactIntegrityImplementationSnapshot()
    @Suppress("UNCHECKED_CAST") private fun section(value: Any?) = value as Map<String, Any?>
    @Suppress("UNCHECKED_CAST") private fun records(value: Any?) = value as List<Map<String, Any?>>
    private fun reject(s: WorkflowSemanticsRecoveryLifecycleSnapshot) =
        assertTrue(WorkflowSemanticsRecoveryLifecycle.errors(s).isNotEmpty())
    private fun slice(s: WorkflowSemanticsRecoveryLifecycleSnapshot, transform: (Map<String, Any?>) -> Map<String, Any?>) =
        s.copy(artifactWorkPackage = s.artifactWorkPackage + ("implementationSlices" to
            records(s.artifactWorkPackage["implementationSlices"]).map { if (it["id"] == "AR-05E") transform(it) else it }))
    private fun boundary(s: WorkflowSemanticsRecoveryLifecycleSnapshot, name: String, value: Map<String, Any?>) =
        s.copy(artifactWorkPackage = s.artifactWorkPackage + ("lifecycle" to
            (section(s.artifactWorkPackage["lifecycle"]) + (name to value))))

    @Test fun allAcceptedSlicesPreserveIndependentValidationAndHistoricalCandidates() {
        val current = live()
        assertEquals(emptyList(), WorkflowSemanticsRecoveryLifecycle.errors(current))
        assertTrue(records(current.artifactWorkPackage["implementationSlices"]).all { it["status"] == "accepted" })
        val lifecycle = section(current.artifactWorkPackage["lifecycle"])
        assertEquals("passed", section(lifecycle["implementationBoundary"])["status"])
        for (name in listOf("validationBoundary", "completionBoundary")) {
            assertEquals(mapOf("status" to "pending"), section(lifecycle[name]))
            reject(boundary(current, name, section(lifecycle["implementationBoundary"])))
            reject(boundary(current, name, mapOf("status" to "pending", "workflowRunId" to 37272555478L)))
        }
        for (historical in listOf(integratedProductCandidateSnapshot(), atomicPublicationCandidateSnapshot(),
            boundedIoCandidateSnapshot(), contractDistributionCandidateSnapshot(), cliArgumentCandidateSnapshot())) {
            assertEquals(emptyList(), WorkflowSemanticsRecoveryLifecycle.errors(historical))
        }
        val candidate = integratedProductCandidateSnapshot()
        reject(slice(candidate) { it + ("status" to "accepted") })
        reject(boundary(candidate, "implementationBoundary", section(lifecycle["implementationBoundary"])))
        reject(slice(current) { it + ("status" to "implemented") })
    }

    @Test fun acceptanceAndImplementationBoundaryRejectEveryMissingChangedOrExtraField() {
        val current = live()
        val receipt = section(records(current.artifactWorkPackage["implementationSlices"])[4]["acceptance"])
        val implementation = section(section(current.artifactWorkPackage["lifecycle"])["implementationBoundary"])
        for (field in receipt.keys) {
            reject(slice(current) { it + ("acceptance" to (receipt - field)) })
            for (value in listOf(null, "invented", true, -1, 1.0))
                reject(slice(current) { it + ("acceptance" to (receipt + (field to value))) })
        }
        for (field in implementation.keys) {
            reject(boundary(current, "implementationBoundary", implementation - field))
            for (value in listOf(null, "invented", true, -1, 1.0))
                reject(boundary(current, "implementationBoundary", implementation + (field to value)))
        }
        reject(slice(current) { it + ("acceptance" to (receipt + ("futureSuccess" to true))) })
        reject(boundary(current, "implementationBoundary", implementation + ("futureSuccess" to true)))
    }

    @Test fun evidenceCannotBeSubstitutedAlteredOrSelfAuthenticated() {
        val current = live()
        val raw = requireNotNull(current.integratedProductAcceptanceEvidence)
        val altered = raw.replace("\"tests\": 1822", "\"tests\": 1821")
        assertNotEquals(raw, altered)
        for (value in listOf(null, "", "malformed: [", raw + "\n", altered,
            current.atomicPublicationAcceptanceEvidence, current.integratedLanguageEvidence)) {
            reject(current.copy(integratedProductAcceptanceEvidence = value))
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(altered.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        val forged = slice(current.copy(integratedProductAcceptanceEvidence = altered)) {
            it + ("acceptance" to (section(it["acceptance"]) + ("sha256" to digest)))
        }
        val implementation = section(section(forged.artifactWorkPackage["lifecycle"])["implementationBoundary"])
        reject(boundary(forged, "implementationBoundary", implementation + ("evidenceSha256" to digest)))
    }

    @Test fun loaderAuthenticatesActualEvidenceBeforeParsing() {
        val root = createTempDirectory("product-implementation-acceptance-").toFile()
        try {
            File(".flow-agent").copyRecursively(File(root, ".flow-agent"))
            val original = WorkflowSemanticsRecoveryLifecycle.load(root)
            assertEquals(emptyList(), WorkflowSemanticsRecoveryLifecycle.errors(original))
            val file = File(root, IntegratedProductIntegrityLifecycle.IMPLEMENTATION_EVIDENCE)
            file.appendText("\n")
            assertNotEquals(original.integratedProductAcceptanceEvidence,
                WorkflowSemanticsRecoveryLifecycle.load(root).integratedProductAcceptanceEvidence)
            reject(WorkflowSemanticsRecoveryLifecycle.load(root))
            file.writeText("malformed: [")
            reject(WorkflowSemanticsRecoveryLifecycle.load(root))
            file.delete()
            reject(WorkflowSemanticsRecoveryLifecycle.load(root))
        } finally { root.deleteRecursively() }
    }

    @Test fun actualMainCannotImpersonateHeadOrSyntheticMergeAndPriorEvidenceCannotBeBorrowed() {
        val current = live()
        val lifecycle = section(current.artifactWorkPackage["lifecycle"])
        val implementation = section(lifecycle["implementationBoundary"])
        for ((destination, source) in listOf("exactHead" to "mainCommit", "syntheticMergeCandidate" to "exactHead",
            "workflowRunId" to "postMergeWorkflowRunId", "exactHeadJobId" to "mainExactHeadJobId",
            "mergeCandidateJobId" to "mainExactHeadJobId", "isolationJobId" to "mainIsolationJobId")) {
            assertNotEquals(implementation[destination], implementation[source])
            reject(boundary(current, "implementationBoundary", implementation + (destination to implementation[source])))
        }
        reject(boundary(current, "implementationBoundary", section(lifecycle["activationBoundary"])))
        reject(boundary(current, "implementationBoundary", section(current.artifactWorkPackage["verifiedBaseline"])))
        for (index in 0..3) {
            val earlier = section(records(current.artifactWorkPackage["implementationSlices"])[index]["acceptance"])
            reject(slice(current) { it + ("acceptance" to earlier) })
        }
    }

    @Test fun acceptedImplementationCannotCloseAnyFindingOrActivateTheSuccessor() {
        val current = live()
        assertEquals("not-complete", section(current.artifactWorkPackage["completionDecision"])["status"])
        reject(current.copy(artifactWorkPackage = current.artifactWorkPackage + ("status" to "complete")))
        for (id in listOf("F-03", "F-05", "F-19", "F-22")) {
            reject(current.copy(recovery = current.recovery + ("findingRegister" to records(current.recovery["findingRegister"])
                .map { if (it["id"] == id) it + ("status" to "closed") else it })))
        }
        reject(current.copy(recovery = current.recovery + ("milestones" to records(current.recovery["milestones"])
            .map { if (it["id"] == "AR-06") it + ("status" to "active") else it })))
        val decision = section(current.artifactWorkPackage["completionDecision"])
        reject(current.copy(artifactWorkPackage = current.artifactWorkPackage +
            ("completionDecision" to (decision + ("nextItemActivationState" to "active")))))
    }
}
