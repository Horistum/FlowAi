package org.flowlang.conformance

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SystemContractIdentityLifecycleTests {
    private fun live(): WorkflowSemanticsRecoveryLifecycleSnapshot {
        val current = WorkflowSemanticsRecoveryLifecycle.load(File("."))
        val historical = records(current.integrityWorkPackage["implementationSlices"]).map { slice -> when (slice["id"]) {
            "AR-04C" -> slice + mapOf("status" to "implemented", "acceptance" to mapOf(
                "source" to "current-revision-ci", "requiredChecks" to listOf("compile-test-conformance", "merge-candidate-compile-test-conformance")))
            "AR-04D" -> (slice - "acceptance") + ("status" to "planned")
            else -> slice
        } }
        return current.copy(integrityWorkPackage = current.integrityWorkPackage + mapOf(
            "selectedSlice" to "AR-04C", "nextSlice" to "AR-04D", "implementationSlices" to historical))
    }
    @Suppress("UNCHECKED_CAST")
    private fun records(value: Any?) = value as List<Map<String, Any?>>
    @Suppress("UNCHECKED_CAST")
    private fun section(value: Any?) = value as Map<String, Any?>
    private fun changeSlice(id: String, transform: (Map<String, Any?>) -> Map<String, Any?>): WorkflowSemanticsRecoveryLifecycleSnapshot {
        val current = live()
        return current.copy(integrityWorkPackage = current.integrityWorkPackage + ("implementationSlices" to
            records(current.integrityWorkPackage["implementationSlices"]).map { if (it["id"] == id) transform(it) else it }))
    }

    @Test fun systemIdentityRequiresItsAcceptedSchemaPredecessor() {
        val current = live()
        assertEquals("AR-04C", current.integrityWorkPackage["selectedSlice"])
        assertEquals("AR-04D", current.integrityWorkPackage["nextSlice"])
        assertTrue(WorkflowSemanticsRecoveryLifecycle.errors(current).isEmpty(),
            WorkflowSemanticsRecoveryLifecycle.errors(current).joinToString(" | "))
        assertTrue(WorkflowSemanticsRecoveryLifecycle.errors(changeSlice("AR-04B") {
            it + ("status" to "implemented")
        }).isNotEmpty())
    }

    @Test fun eachSchemaReceiptFieldMustAgreeWithIndependentEvidence() {
        val receipt = section(records(live().integrityWorkPackage["implementationSlices"])[1]["acceptance"])
        receipt.keys.forEach { key ->
            val missing = changeSlice("AR-04B") { it + ("acceptance" to (receipt - key)) }
            assertTrue(WorkflowSemanticsRecoveryLifecycle.errors(missing).isNotEmpty(), "Missing $key was accepted")
            val changed = changeSlice("AR-04B") { it + ("acceptance" to (receipt + (key to "invented"))) }
            assertTrue(WorkflowSemanticsRecoveryLifecycle.errors(changed).isNotEmpty(), "Changed $key was accepted")
        }
    }

    @Test fun sourceReceiptIsBoundToTheActualSingleReadBytes() {
        val root = createTempDirectory("system-contract-receipt").toFile()
        try {
            val paths = listOf(
                WorkflowSemanticsRecoveryLifecycle.WORK_PACKAGE,
                CompilerModuleExtractionLifecycle.WORK_PACKAGE,
                CompilerModuleAcceptance.EVIDENCE, CompilerModuleAcceptance.INVENTORY,
                LanguageContractIntegrityLifecycle.WORK_PACKAGE,
                LanguageContractIntegrityLifecycle.ACTIVATION_EVIDENCE,
                LanguageContractIntegrityLifecycle.SCHEMA_EVIDENCE, LanguageContractIntegrityLifecycle.SYSTEM_IDENTITY_EVIDENCE,
                ".flow-agent/roadmap-architecture-recovery.yaml", ".flow-agent/roadmap-post-toolchain.yaml",
                ".flow-agent/release-state.yaml", ".flow-agent/roadmap.yaml"
            )
            paths.forEach { name -> File(name).copyTo(File(root, name).also { it.parentFile.mkdirs() }) }
            val baseline = WorkflowSemanticsRecoveryLifecycle.load(root)
            assertTrue(WorkflowSemanticsRecoveryLifecycle.errors(baseline).isEmpty())
            File(root, LanguageContractIntegrityLifecycle.SCHEMA_EVIDENCE).appendText("\n")
            val changed = WorkflowSemanticsRecoveryLifecycle.load(root)
            assertEquals(baseline.schemaIntegrityEvidence, changed.schemaIntegrityEvidence)
            assertTrue(WorkflowSemanticsRecoveryLifecycle.errors(changed).any { "byte-pinned" in it })
            File(root, LanguageContractIntegrityLifecycle.SCHEMA_EVIDENCE).delete()
            assertTrue(WorkflowSemanticsRecoveryLifecycle.errors(WorkflowSemanticsRecoveryLifecycle.load(root)).isNotEmpty())
        } finally { root.deleteRecursively() }
    }

    @Test fun aFutureSuccessOrLaterSliceCannotBePublishedByThisImplementation() {
        assertTrue(WorkflowSemanticsRecoveryLifecycle.errors(changeSlice("AR-04C") {
            it + ("acceptance" to mapOf("source" to "current-revision-ci", "status" to "passed"))
        }).isNotEmpty())
        listOf("AR-04D", "AR-04E", "AR-04F").forEach { id ->
            assertTrue(WorkflowSemanticsRecoveryLifecycle.errors(changeSlice(id) { it + ("status" to "implemented") }).isNotEmpty())
        }
        val current = live()
        val lifecycle = section(current.integrityWorkPackage["lifecycle"])
        listOf("implementationBoundary", "validationBoundary", "completionBoundary").forEach { id ->
            assertTrue(WorkflowSemanticsRecoveryLifecycle.errors(current.copy(integrityWorkPackage =
                current.integrityWorkPackage + ("lifecycle" to (lifecycle + (id to mapOf("status" to "passed")))))).isNotEmpty())
        }
    }
}
