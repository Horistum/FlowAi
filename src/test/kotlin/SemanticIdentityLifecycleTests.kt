package org.flowlang.conformance

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.*

class SemanticIdentityLifecycleTests {
    private fun live() = WorkflowSemanticsRecoveryLifecycle.load(File("."))
    @Suppress("UNCHECKED_CAST") private fun section(value: Any?) = value as Map<String, Any?>
    @Suppress("UNCHECKED_CAST") private fun slices(value: Any?) = value as List<Map<String, Any?>>
    private fun changeSlice(current: WorkflowSemanticsRecoveryLifecycleSnapshot, id: String,
        transform: (Map<String, Any?>) -> Map<String, Any?>) = current.copy(integrityWorkPackage = current.integrityWorkPackage +
            ("implementationSlices" to slices(current.integrityWorkPackage["implementationSlices"]).map { if (it["id"] == id) transform(it) else it }))
    private fun rejected(current: WorkflowSemanticsRecoveryLifecycleSnapshot) =
        assertTrue(WorkflowSemanticsRecoveryLifecycle.errors(current).isNotEmpty())

    @Test fun liveSemanticIdentityFollowsItsOwnAcceptedSystemPredecessor() {
        val current = live()
        assertEquals("AR-04D", current.integrityWorkPackage["selectedSlice"])
        assertEquals("AR-04E", current.integrityWorkPackage["nextSlice"])
        assertTrue(WorkflowSemanticsRecoveryLifecycle.errors(current).isEmpty(), WorkflowSemanticsRecoveryLifecycle.errors(current).joinToString(" | "))
        rejected(changeSlice(current, "AR-04C") { it + ("status" to "implemented") })
    }
    @Test fun everyPredecessorReceiptFieldMustMatchInspectedEvidence() {
        val current = live()
        val receipt = section(slices(current.integrityWorkPackage["implementationSlices"])[2]["acceptance"])
        receipt.keys.forEach { field ->
            rejected(changeSlice(current, "AR-04C") { it + ("acceptance" to (receipt - field)) })
            rejected(changeSlice(current, "AR-04C") { it + ("acceptance" to (receipt + (field to "invented"))) })
        }
        rejected(changeSlice(current, "AR-04C") { it + ("acceptance" to (receipt + ("futureSuccess" to true))) })
    }
    @Test fun predecessorPostMergeAndTestOutcomesCannotBeBorrowedOrHidden() {
        val current = live()
        val evidence = current.systemIdentityEvidence
        for (field in listOf("head", "syntheticMerge", "mergedMain", "workflowRunId", "sourceTree")) {
            rejected(current.copy(systemIdentityEvidence = evidence + (field to "unrelated")))
        }
        val post = section(evidence["postMerge"])
        post.keys.forEach { field -> rejected(current.copy(systemIdentityEvidence = evidence + ("postMerge" to (post - field)))) }
        val junit = section(evidence["junit"])
        junit.keys.forEach { boundary ->
            val tests = section(junit[boundary])
            for (field in listOf("tests", "uniqueIdentities", "failures", "errors", "skipped", "identitiesSha256")) {
                rejected(current.copy(systemIdentityEvidence = evidence + ("junit" to (junit + (boundary to (tests - field))))))
                rejected(current.copy(systemIdentityEvidence = evidence + ("junit" to (junit + (boundary to (tests + (field to "0")))))))
            }
        }
    }
    @Test fun currentImplementationCannotInventItsOwnCiOrActivateTheNextSlice() {
        val current = live()
        rejected(changeSlice(current, "AR-04D") { it + ("acceptance" to mapOf("status" to "passed")) })
        rejected(changeSlice(current, "AR-04D") { it + ("status" to "complete") })
        listOf("AR-04E", "AR-04F").forEach { id -> rejected(changeSlice(current, id) { it + ("status" to "implemented") }) }
        val lifecycle = section(current.integrityWorkPackage["lifecycle"])
        listOf("implementationBoundary", "validationBoundary", "completionBoundary").forEach { boundary ->
            rejected(current.copy(integrityWorkPackage = current.integrityWorkPackage + ("lifecycle" to
                (lifecycle + (boundary to mapOf("status" to "passed"))))))
        }
    }
    @Test fun evidenceHashBindsTheSingleReadBytesRatherThanOnlyDecodedFields() {
        val root = createTempDirectory("semantic-identity-receipt").toFile()
        try {
            val paths = listOf(WorkflowSemanticsRecoveryLifecycle.WORK_PACKAGE, CompilerModuleExtractionLifecycle.WORK_PACKAGE,
                CompilerModuleAcceptance.EVIDENCE, CompilerModuleAcceptance.INVENTORY, LanguageContractIntegrityLifecycle.WORK_PACKAGE,
                LanguageContractIntegrityLifecycle.ACTIVATION_EVIDENCE, LanguageContractIntegrityLifecycle.SCHEMA_EVIDENCE,
                LanguageContractIntegrityLifecycle.SYSTEM_IDENTITY_EVIDENCE, ".flow-agent/roadmap-architecture-recovery.yaml",
                ".flow-agent/roadmap-post-toolchain.yaml", ".flow-agent/release-state.yaml", ".flow-agent/roadmap.yaml")
            paths.forEach { path -> File(path).copyTo(File(root, path).also { it.parentFile.mkdirs() }) }
            val baseline = WorkflowSemanticsRecoveryLifecycle.load(root)
            assertTrue(WorkflowSemanticsRecoveryLifecycle.errors(baseline).isEmpty())
            val file = File(root, LanguageContractIntegrityLifecycle.SYSTEM_IDENTITY_EVIDENCE)
            file.appendText("\n")
            val changed = WorkflowSemanticsRecoveryLifecycle.load(root)
            assertEquals(baseline.systemIdentityEvidence, changed.systemIdentityEvidence)
            assertNotEquals(baseline.systemIdentitySha256, changed.systemIdentitySha256)
            rejected(changed)
            file.delete()
            rejected(WorkflowSemanticsRecoveryLifecycle.load(root))
        } finally { root.deleteRecursively() }
    }
}
