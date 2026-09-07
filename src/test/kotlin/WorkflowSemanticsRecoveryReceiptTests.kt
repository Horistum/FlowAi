package org.flowlang.tests

import java.io.File
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertTrue
import org.flowlang.conformance.WorkflowSemanticsRecoveryLifecycle
import org.flowlang.conformance.WorkflowSemanticsRecoveryLifecycleSnapshot

class WorkflowSemanticsRecoveryReceiptTests {
    @Test
    fun localValidationAcceptsOneCompleteReceipt() {
        val receipt = validReceipt()
        assertTrue(WorkflowSemanticsRecoveryLifecycle.boundaryErrors("validationBoundary", receipt).isEmpty())
        assertTrue(WorkflowSemanticsRecoveryLifecycle.validationAliasErrors(receipt, receipt).isEmpty())
    }

    @Test
    fun matchingHeadAndRunCannotBorrowOtherJobOrMergeEvidence() {
        val receipt = validReceipt()
        val mutations = mapOf(
            "exactHeadJobId" to 999991L,
            "mergeCandidateJobId" to 999992L,
            "workflowRunNumber" to 999993,
            "syntheticMergeCandidate" to fixtureHash("unrelated-merge")
        )
        mutations.forEach { (field, value) ->
            val local = receipt + (field to value)
            val shapeErrors = WorkflowSemanticsRecoveryLifecycle.boundaryErrors("localValidation", local)
            assertTrue(shapeErrors.isEmpty(), "$field must be a valid but unrelated receipt: ${shapeErrors.joinToString()}")
            val errors = WorkflowSemanticsRecoveryLifecycle.validationAliasErrors(local, receipt)
            assertTrue(errors.any { "localValidation.$field" in it }, "$field: ${errors.joinToString()}")
        }
    }

    @Test
    fun everyReceiptFieldIsRequiredOnBothSides() {
        val receipt = validReceipt()
        receipt.keys.forEach { field ->
            val errors = WorkflowSemanticsRecoveryLifecycle.validationAliasErrors(receipt - field, receipt - field)
            assertTrue(errors.any { "localValidation.$field" in it }, "Missing $field was accepted.")
        }
    }

    @Test
    fun localAliasCannotChangeStatusConclusionHeadOrRun() {
        val receipt = validReceipt()
        mapOf(
            "status" to "pending",
            "conclusion" to "failure",
            "exactHead" to fixtureHash("unrelated-head"),
            "workflowRunId" to 999994L
        ).forEach { (field, value) ->
            val errors = WorkflowSemanticsRecoveryLifecycle.validationAliasErrors(receipt + (field to value), receipt)
            assertTrue(errors.any { "localValidation.$field" in it }, errors.joinToString())
        }
    }

    @Test
    fun yamlIntegerWidthsDoNotChangeReceiptIdentityButStringsDo() {
        val receipt = validReceipt()
        val longNumber = receipt + ("workflowRunNumber" to 17L)
        assertTrue(WorkflowSemanticsRecoveryLifecycle.validationAliasErrors(longNumber, receipt).isEmpty())
        val stringNumber = receipt + ("workflowRunNumber" to "17")
        assertTrue(WorkflowSemanticsRecoveryLifecycle.validationAliasErrors(stringNumber, receipt).isNotEmpty())
    }

    @Test
    fun coherentCompletedLifecyclePassesAsAWhole() {
        val errors = WorkflowSemanticsRecoveryLifecycle.errors(completedSnapshot())
        assertTrue(errors.isEmpty(), errors.joinToString(" | "))
    }

    @Test
    fun eachIncompleteBoundaryRejectsAnOtherwiseCompletedLifecycle() {
        val complete = completedSnapshot()
        WorkflowSemanticsRecoveryLifecycle.boundaryNames.forEach { name ->
            listOf("pending", "candidate").forEach { state ->
                val errors = WorkflowSemanticsRecoveryLifecycle.errors(changeBoundary(complete, name) { it + ("status" to state) })
                assertTrue(errors.any { "$name is not a passed" in it }, "$name/$state: ${errors.joinToString()}")
            }
        }
    }

    @Test
    fun duplicateValidationBoundaryCannotCloseLifecycle() {
        val complete = completedSnapshot()
        val lifecycle = section(complete.workPackage["lifecycle"])
        val implementation = section(lifecycle["implementationBoundary"])
        val changed = complete.copy(workPackage = complete.workPackage + mapOf(
            "lifecycle" to (lifecycle + ("validationBoundary" to implementation)),
            "localValidation" to implementation
        ))
        val errors = WorkflowSemanticsRecoveryLifecycle.errors(changed)
        assertTrue(errors.any { "distinct workflowRunId" in it }, errors.joinToString())
        assertTrue(errors.any { "distinct exactHead" in it }, errors.joinToString())
        assertTrue(errors.any { "distinct syntheticMergeCandidate" in it }, errors.joinToString())
    }

    @Test
    fun completedLifecycleRejectsUnrelatedLocalValidationJobs() {
        val complete = completedSnapshot()
        val local = section(complete.workPackage["localValidation"])
        val changed = complete.copy(workPackage = complete.workPackage +
            ("localValidation" to (local + ("mergeCandidateJobId" to 998877L))))
        val errors = WorkflowSemanticsRecoveryLifecycle.errors(changed)
        assertTrue(errors.any { "localValidation.mergeCandidateJobId" in it }, errors.joinToString())
    }

    @Test
    fun completedLifecycleCannotActivateItsSuccessor() {
        val complete = completedSnapshot()
        val milestones = (complete.recovery["milestones"] as List<*>).map { value ->
            val milestone = section(value)
            if (milestone["id"] == "AR-03") milestone + ("status" to "active") else milestone
        }
        val changed = complete.copy(recovery = complete.recovery + ("milestones" to milestones))
        val errors = WorkflowSemanticsRecoveryLifecycle.errors(changed)
        assertTrue(errors.any { "AR-03 must remain planned" in it }, errors.joinToString())
    }

    private fun changeBoundary(
        snapshot: WorkflowSemanticsRecoveryLifecycleSnapshot,
        name: String,
        change: (Map<String, Any?>) -> Map<String, Any?>
    ): WorkflowSemanticsRecoveryLifecycleSnapshot {
        val lifecycle = section(snapshot.workPackage["lifecycle"])
        return snapshot.copy(workPackage = snapshot.workPackage +
            ("lifecycle" to (lifecycle + (name to change(section(lifecycle[name]))))))
    }

    /** Builds a coherent completed fixture without changing repository lifecycle files. */
    private fun completedSnapshot(): WorkflowSemanticsRecoveryLifecycleSnapshot {
        val snapshot = WorkflowSemanticsRecoveryLifecycle.load(File("."))
        val boundaries = WorkflowSemanticsRecoveryLifecycle.boundaryNames.mapIndexed { index, name ->
            name to (validReceipt() + mapOf(
                "workflowRunId" to (100000L + index),
                "workflowRunNumber" to (10 + index),
                "exactHeadJobId" to (200000L + 2 * index),
                "mergeCandidateJobId" to (200001L + 2 * index),
                "exactHead" to fixtureHash("$name-head"),
                "syntheticMergeCandidate" to fixtureHash("$name-merge")
            ))
        }.toMap()
        val slices = (snapshot.workPackage["implementationSlices"] as List<*>).map {
            section(it) + ("status" to "complete")
        }
        val milestones = (snapshot.recovery["milestones"] as List<*>).map {
            val value = section(it)
            when (value["id"]) {
                "AR-02" -> value + ("status" to "completed")
                "AR-03" -> value + ("status" to "planned")
                else -> value
            }
        }
        val successor = mapOf(
            "nextItem" to "AR-03",
            "activationState" to "not-activated",
            "workPackage" to WorkflowSemanticsRecoveryLifecycle.WORK_PACKAGE
        )
        return snapshot.copy(
            workPackage = snapshot.workPackage + mapOf(
                "status" to "complete",
                "authorization" to (section(snapshot.workPackage["authorization"]) + ("status" to "completed")),
                "implementationSlices" to slices,
                "lifecycle" to boundaries,
                "localValidation" to boundaries.getValue("validationBoundary"),
                "completionDecision" to mapOf(
                    "status" to "complete", "completedSlice" to "AR-02E", "nextItem" to "AR-03",
                    "nextItemActivationState" to "not-activated", "closesFindings" to listOf("F-02", "F-08", "F-15")
                )
            ),
            recovery = snapshot.recovery + mapOf(
                "milestones" to milestones,
                "currentDecision" to (section(snapshot.recovery["currentDecision"]) + successor +
                    ("previousCompletedItem" to "AR-02"))
            ),
            postToolchain = snapshot.postToolchain + mapOf(
                "currentDecision" to (section(snapshot.postToolchain["currentDecision"]) + successor +
                    ("completedItem" to "AR-02")),
                "recoveryRoadmap" to (section(snapshot.postToolchain["recoveryRoadmap"]) + mapOf(
                    "completedItem" to "AR-02", "activationState" to "not-activated"
                ))
            ),
            release = snapshot.release + ("roadmapState" to (section(snapshot.release["roadmapState"]) + mapOf(
                "completedRecoveryItem" to "AR-02", "nextRecoveryItem" to "AR-03",
                "nextRecoveryActivationState" to "not-activated"
            )))
        )
    }

    private fun section(value: Any?): Map<String, Any?> =
        (value as Map<*, *>).entries.associate { (key, content) -> key.toString() to content }

    /** Synthetic unit-test evidence, never used as a repository completion receipt. */
    private fun validReceipt(): Map<String, Any> = mapOf(
        "status" to "passed", "conclusion" to "success", "workflowRunId" to 123456L,
        "workflowRunNumber" to 17, "exactHeadJobId" to 234567L, "mergeCandidateJobId" to 234568L,
        "exactHead" to fixtureHash("head"), "syntheticMergeCandidate" to fixtureHash("merge")
    )

    private fun fixtureHash(identity: String): String = MessageDigest.getInstance("SHA-1")
        .digest(identity.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
