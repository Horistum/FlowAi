package org.flowlang.conformance

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.*

class IntegratedLanguageIntegrityLifecycleTests {
    private fun live() = WorkflowSemanticsRecoveryLifecycle.load(File("."))
    @Suppress("UNCHECKED_CAST") private fun section(value: Any?) = value as Map<String, Any?>
    @Suppress("UNCHECKED_CAST") private fun slices(value: Any?) = value as List<Map<String, Any?>>
    private fun rejected(value: WorkflowSemanticsRecoveryLifecycleSnapshot) =
        assertTrue(WorkflowSemanticsRecoveryLifecycle.errors(value).isNotEmpty())
    private fun changeSlice(current: WorkflowSemanticsRecoveryLifecycleSnapshot, id: String,
        transform: (Map<String, Any?>) -> Map<String, Any?>) = current.copy(integrityWorkPackage = current.integrityWorkPackage +
        ("implementationSlices" to slices(current.integrityWorkPackage["implementationSlices"]).map {
            if (it["id"] == id) transform(it) else it }))

    @Test fun integratedAcceptanceRequiresAllFiveAcceptedPredecessorsAndCurrentValidation() {
        val current = live()
        assertEquals("AR-04F", current.integrityWorkPackage["selectedSlice"])
        assertEquals("", current.integrityWorkPackage["nextSlice"])
        assertTrue(WorkflowSemanticsRecoveryLifecycle.errors(current).isEmpty(),
            WorkflowSemanticsRecoveryLifecycle.errors(current).joinToString(" | "))
        for (id in listOf("AR-04A", "AR-04B", "AR-04C", "AR-04D", "AR-04E")) {
            rejected(changeSlice(current, id) { it + ("status" to "implemented") })
        }
        rejected(changeSlice(current, "AR-04F") { it + ("status" to "complete") })
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
}
