package org.flowlang.conformance

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LanguageContractIntegrityAr04bLifecycleTests {
    private fun live(): WorkflowSemanticsRecoveryLifecycleSnapshot =
        WorkflowSemanticsRecoveryLifecycle.load(File("."))

    @Test
    fun liveWorkPackageSelectsSchemaIntegrityAfterAcceptedAr04a() {
        val current = live()
        val errors = WorkflowSemanticsRecoveryLifecycle.errors(current)
        assertTrue(errors.isEmpty(), errors.joinToString(" | "))

        assertEquals("AR-04B", current.integrityWorkPackage["selectedSlice"])
        assertEquals("AR-04C", current.integrityWorkPackage["nextSlice"])
        val slices = records(current.integrityWorkPackage["implementationSlices"])
        assertEquals("complete", slices.single { it["id"] == "AR-04A" }["status"])
        assertEquals("implemented", slices.single { it["id"] == "AR-04B" }["status"])
        assertTrue(slices.filter { it["id"] in setOf("AR-04C", "AR-04D", "AR-04E", "AR-04F") }
            .all { it["status"] == "planned" })
    }

    @Test
    fun ar04bCannotBorrowOrRewriteAr04aAcceptance() {
        val current = live()
        val slices = records(current.integrityWorkPackage["implementationSlices"])
        val changed = slices.map { slice ->
            if (slice["id"] == "AR-04A") {
                val acceptance = section(slice["acceptance"])
                slice + ("acceptance" to (acceptance + ("head" to "1234567890".repeat(4))))
            } else {
                slice
            }
        }
        val errors = WorkflowSemanticsRecoveryLifecycle.errors(
            current.copy(integrityWorkPackage = current.integrityWorkPackage + ("implementationSlices" to changed))
        )
        assertTrue(errors.any { "AR-04A acceptance" in it }, errors.joinToString(" | "))
    }

    @Test
    fun ar04bCannotPublishFutureMilestoneWideSuccess() {
        val current = live()
        val lifecycle = section(current.integrityWorkPackage["lifecycle"])
        val mutated = lifecycle + ("implementationBoundary" to mapOf("status" to "passed"))
        val errors = WorkflowSemanticsRecoveryLifecycle.errors(
            current.copy(integrityWorkPackage = current.integrityWorkPackage + ("lifecycle" to mutated))
        )
        assertTrue(errors.any { "future milestone-wide" in it }, errors.joinToString(" | "))
    }

    @Suppress("UNCHECKED_CAST")
    private fun section(value: Any?): Map<String, Any?> =
        (value as Map<*, *>).entries.associate { it.key.toString() to it.value }

    private fun records(value: Any?): List<Map<String, Any?>> =
        (value as List<*>).map(::section)
}