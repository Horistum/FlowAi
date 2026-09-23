package org.flowlang.conformance

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.*

class StrictContractLifecycleTests {
    private fun live() = WorkflowSemanticsRecoveryLifecycle.load(File(".")).let { current ->
        current.copy(integrityWorkPackage = org.flowlang.serialization.FlowYaml.readMap(
            File("src/test/resources/lifecycle/language-integrity-strict-loaders.yaml")))
    }
    @Suppress("UNCHECKED_CAST") private fun section(value: Any?) = value as Map<String, Any?>
    @Suppress("UNCHECKED_CAST") private fun slices(value: Any?) = value as List<Map<String, Any?>>
    private fun changeSlice(current: WorkflowSemanticsRecoveryLifecycleSnapshot, id: String,
        transform: (Map<String, Any?>) -> Map<String, Any?>) = current.copy(integrityWorkPackage = current.integrityWorkPackage +
            ("implementationSlices" to slices(current.integrityWorkPackage["implementationSlices"]).map {
                if (it["id"] == id) transform(it) else it }))
    private fun rejected(value: WorkflowSemanticsRecoveryLifecycleSnapshot) =
        assertTrue(WorkflowSemanticsRecoveryLifecycle.errors(value).isNotEmpty())

    @Test fun strictLoadersFollowAcceptedSemanticIdentityWithoutClosingTheMilestone() {
        val current = live()
        assertEquals("AR-04E", current.integrityWorkPackage["selectedSlice"])
        assertEquals("AR-04F", current.integrityWorkPackage["nextSlice"])
        assertTrue(WorkflowSemanticsRecoveryLifecycle.errors(current).isEmpty(),
            WorkflowSemanticsRecoveryLifecycle.errors(current).joinToString(" | "))
        rejected(changeSlice(current, "AR-04D") { it + ("status" to "implemented") })
        rejected(changeSlice(current, "AR-04E") { it + ("status" to "complete") })
        rejected(changeSlice(current, "AR-04E") { it + ("acceptance" to mapOf("status" to "passed")) })
        rejected(changeSlice(current, "AR-04F") { it + ("status" to "implemented") })
    }

    @Test fun acceptedPredecessorReceiptRejectsMissingChangedAndInventedFields() {
        val current = live()
        val receipt = section(slices(current.integrityWorkPackage["implementationSlices"])[3]["acceptance"])
        receipt.keys.forEach { field ->
            rejected(changeSlice(current, "AR-04D") { it + ("acceptance" to (receipt - field)) })
            rejected(changeSlice(current, "AR-04D") { it + ("acceptance" to (receipt + (field to "invented"))) })
        }
        rejected(changeSlice(current, "AR-04D") { it + ("acceptance" to (receipt + ("futureSuccess" to true))) })
    }

    @Test fun everyHistoricalValidationBoundaryMustRemainCompleteAndUnchanged() {
        val current = live()
        val evidence = current.semanticIdentityEvidence
        for (field in listOf("head", "syntheticMerge", "mergedMain", "sourceTree", "workflowRunId", "postMerge", "physicalIsolation")) {
            rejected(current.copy(semanticIdentityEvidence = evidence - field))
        }
        for (group in listOf("junit", "conformance")) {
            val boundaries = section(evidence[group])
            boundaries.keys.forEach { boundary ->
                val values = section(boundaries[boundary])
                values.keys.forEach { field ->
                    rejected(current.copy(semanticIdentityEvidence = evidence +
                        (group to (boundaries + (boundary to (values - field))))))
                }
            }
        }
        rejected(current.copy(semanticIdentitySha256 = "0".repeat(64)))
    }

    @Test fun sourceLoaderBindsEvidenceBytesAndRejectsMissingReceipts() {
        val root = createTempDirectory("strict-loader-receipt").toFile()
        try {
            File(".flow-agent").copyRecursively(File(root, ".flow-agent"))
            val baseline = WorkflowSemanticsRecoveryLifecycle.load(root)
            assertTrue(WorkflowSemanticsRecoveryLifecycle.errors(baseline).isEmpty())
            val receipt = File(root, LanguageContractIntegrityLifecycle.SEMANTIC_IDENTITY_EVIDENCE)
            receipt.appendText("\n")
            val altered = WorkflowSemanticsRecoveryLifecycle.load(root)
            assertEquals(baseline.semanticIdentityEvidence, altered.semanticIdentityEvidence)
            assertNotEquals(baseline.semanticIdentitySha256, altered.semanticIdentitySha256)
            rejected(altered)
            receipt.delete()
            rejected(WorkflowSemanticsRecoveryLifecycle.load(root))
        } finally { root.deleteRecursively() }
    }
}
