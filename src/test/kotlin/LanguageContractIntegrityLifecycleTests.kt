package org.flowlang.conformance

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertTrue

class LanguageContractIntegrityLifecycleTests {
    private val snapshot get() = WorkflowSemanticsRecoveryLifecycle.load(File("."))

    @Test fun currentTransitionAcceptsModulesAndSelectsOnlyDuplicateDeclarationWork() {
        val current = snapshot
        assertTrue(WorkflowSemanticsRecoveryLifecycle.errors(current).isEmpty(), errors(current))
        assertEquals("complete", current.successorWorkPackage["status"])
        assertEquals("AR-04A", current.integrityWorkPackage["selectedSlice"])
        assertEquals("selected", records(current.integrityWorkPackage["implementationSlices"]).first()["status"])
    }

    @Test fun historicalUnacceptedExtractionStillHasAPositiveAndNegativeBoundary() {
        val historical = moduleExtractionCandidateSnapshot()
        assertTrue(WorkflowSemanticsRecoveryLifecycle.errors(historical).isEmpty(), errors(historical))
        rejected(historical.copy(successorWorkPackage = historical.successorWorkPackage +
            ("completionDecision" to mapOf("status" to "complete"))), "cannot close")
    }

    @Test fun missingSuccessorWorkPackageCannotBorrowCompletedModules() {
        rejected(snapshot.copy(integrityWorkPackage = emptyMap()), "own active work package")
    }

    @Test fun missingOrUnacceptedModulePredecessorCannotActivateLanguageWork() {
        rejected(snapshot.copy(successorWorkPackage = emptyMap()), "completed AR-03 predecessor")
        rejected(snapshot.copy(successorWorkPackage = snapshot.successorWorkPackage + ("status" to "active")), "completed AR-03 predecessor")
    }

    @Test fun predecessorAndStrategicAuthorityAreExplicit() {
        for ((field, value) in mapOf("predecessor" to "AR-02", "strategicSource" to "another-roadmap.yaml#AR-04")) {
            rejected(changeLanguage("authorization") { it + (field to value) }, "completed AR-03 predecessor")
        }
    }

    @Test fun everyExtractionSliceMustBeCompleteBeforeMilestoneClosure() {
        for (id in listOf("AR-03A", "AR-03B", "AR-03C", "AR-03D")) {
            val current = snapshot
            val slices = records(current.successorWorkPackage["implementationSlices"]).map {
                if (it["id"] == id) it + ("status" to "implemented") else it
            }
            rejected(current.copy(successorWorkPackage = current.successorWorkPackage + ("implementationSlices" to slices)), "all four completed")
        }
    }

    @Test fun reviewedEvidenceMustExistAndMatchItsExactHash() {
        rejected(snapshot.copy(moduleAcceptanceEvidence = emptyMap()), "exact reviewed evidence")
        rejected(snapshot.copy(moduleAcceptanceSha256 = "f0123456789abcdef".repeat(4)), "exact reviewed evidence")
        rejected(changeModules("acceptanceEvidence") { it - "path" }, "exact reviewed evidence")
    }

    @Test fun evidenceBytesAreBoundAtTheRealFileLoader() = withMetadataFixture { root ->
        val before = WorkflowSemanticsRecoveryLifecycle.load(root)
        assertTrue(WorkflowSemanticsRecoveryLifecycle.errors(before).isEmpty(), errors(before))
        File(root, CompilerModuleAcceptance.EVIDENCE).appendText("\n")
        val changed = WorkflowSemanticsRecoveryLifecycle.load(root)
        rejected(changed, "exact reviewed evidence")
    }

    @Test fun malformedOrDuplicateEvidenceCannotBecomeAnEmptyPassedReceipt() = withMetadataFixture { root ->
        File(root, CompilerModuleAcceptance.EVIDENCE).writeText("status: passed\nstatus: passed\n")
        assertFails { WorkflowSemanticsRecoveryLifecycle.load(root) }
    }

    @Test fun everyIntegratedReceiptFieldMustMatchIndependentEvidence() {
        val receipt = section(section(snapshot.successorWorkPackage["lifecycle"])["integratedBoundary"])
        for (field in receipt.keys) {
            rejected(changeModuleBoundary("integratedBoundary") { it - field }, "integratedBoundary")
        }
    }

    @Test fun validLookingButUnrelatedCommitAndJobIdsDoNotProveIntegration() {
        for ((field, value) in mapOf("exactHeadJobId" to 555555L, "mergeCandidateJobId" to 555556L,
            "exactHead" to "1234567890".repeat(4), "workflowRunNumber" to 555557)) {
            rejected(changeModuleBoundary("integratedBoundary") { it + (field to value) }, "independently recorded")
        }
    }

    @Test fun everyPostMergeReceiptFieldIsRequired() {
        val receipt = section(section(snapshot.successorWorkPackage["lifecycle"])["completionBoundary"])
        for (field in receipt.keys - "rule") {
            val current = changeModuleBoundary("completionBoundary") { it - field }
            assertTrue(WorkflowSemanticsRecoveryLifecycle.errors(current).isNotEmpty(), "Missing $field was accepted")
        }
    }

    @Test fun actualMergeCannotBeSubstitutedByOriginalHeadOrSyntheticMerge() {
        val integrated = section(section(snapshot.successorWorkPackage["lifecycle"])["integratedBoundary"])
        for (field in listOf("exactHead", "syntheticMergeCandidate")) {
            rejected(changeModuleBoundary("completionBoundary") { it + ("mainCommit" to integrated[field]) }, "completionBoundary.mainCommit")
        }
    }

    @Test fun pushReceiptCannotPretendToContainASecondSyntheticMergeJob() {
        rejected(changeModuleBoundary("completionBoundary") { it + ("event" to "pull_request") }, "completionBoundary.event")
        for (field in listOf("syntheticMergeCandidate", "mergeCandidateJobId")) {
            rejected(changeModuleBoundary("completionBoundary") { it + (field to "invented") }, "synthetic-merge evidence")
        }
    }

    @Test fun completionCannotReuseKernelOrActivationRun() {
        val lifecycle = section(snapshot.successorWorkPackage["lifecycle"])
        for (boundary in listOf("activationBoundary", "implementationBoundary", "offlineBoundary")) {
            rejected(changeModuleBoundary("integratedBoundary") { it +
                ("workflowRunId" to section(lifecycle[boundary])["workflowRunId"]) }, "independently recorded")
        }
    }

    @Test fun receiptNumbersCannotBeCoercedFromStringsFractionsBooleansOrZero() {
        for (field in listOf("workflowRunId", "workflowRunNumber", "exactHeadJobId", "kotlinTests")) {
            for (value in listOf<Any?>("1512", 1512.0, true, 0L, -1L, null)) {
                rejected(changeModuleBoundary("completionBoundary") { it + (field to value) }, "completionBoundary.$field")
            }
        }
    }

    @Test fun zeroOutcomeCountsAreRequiredRatherThanAssumedWhenMissing() {
        for (field in listOf("failures", "errors", "skipped")) {
            for (value in listOf<Any?>(null, 1, "0", false, 0.0)) {
                rejected(changeModuleBoundary("completionBoundary") { it + (field to value) }, "completionBoundary.$field")
            }
        }
    }

    @Test fun missingFailedOrPartialIsolationProofRejectsClosure() {
        for (label in listOf("prHeadAndOriginalMerge", "actualMergedMain")) {
            for (proof in listOf("kernel", "compiler", "adapter", "product")) {
                val current = snapshot
                val all = section(current.moduleAcceptanceEvidence["isolation"])
                val isolated = section(all[label])
                val proofs = section(isolated["proofs"])
                val evidence = current.moduleAcceptanceEvidence + ("isolation" to
                    (all + (label to (isolated + ("proofs" to (proofs - proof))))))
                rejected(current.copy(moduleAcceptanceEvidence = evidence), "all four actual physical isolation proofs")
            }
        }
    }

    @Test fun acceptanceCannotChangeTheTestIdentitySetOrHideFailedTests() {
        for ((field, value) in mapOf("identitiesSha256" to "abcdef0123456789".repeat(4), "failures" to 1,
            "skipped" to 1, "errors" to 1, "uniqueIdentities" to 1500, "tests" to 1400)) {
            val current = snapshot
            val revisions = section(current.moduleAcceptanceEvidence["revisions"])
            val main = section(revisions["actualMergedMain"])
            val changed = main + ("junit" to (section(main["junit"]) + (field to value)))
            rejected(current.copy(moduleAcceptanceEvidence = current.moduleAcceptanceEvidence +
                ("revisions" to (revisions + ("actualMergedMain" to changed)))), "preserved identities and conformance")
        }
    }

    @Test fun everyActivationBaselineFieldIsBoundToAcceptedActualMain() {
        for (field in section(snapshot.integrityWorkPackage["verifiedBaseline"]).keys) {
            rejected(changeLanguage("verifiedBaseline") { it - field }, "Language integrity baseline.$field")
        }
        rejected(changeLanguage("authorization") { it + ("mainAtActivation" to "1234567890".repeat(4)) }, "accepted merged main")
    }

    @Test fun lifecycleCannotClaimFutureActivationOrImplementationSuccess() {
        for (name in listOf("activationBoundary", "implementationBoundary", "validationBoundary", "completionBoundary")) {
            rejected(changeLanguage("lifecycle") { it + (name to mapOf("status" to "passed")) }, "future receipt")
            rejected(changeLanguage("lifecycle") { it + (name to mapOf("status" to "pending", "workflowRunId" to 12345)) }, "future receipt")
            rejected(changeLanguage("lifecycle") { it - name }, "future receipt")
        }
    }

    @Test fun evenRealPredecessorSuccessCannotBeBorrowedAsNewActivation() {
        val old = section(section(snapshot.successorWorkPackage["lifecycle"])["integratedBoundary"])
        rejected(changeLanguage("lifecycle") { it + ("activationBoundary" to old) }, "borrow predecessor success")
    }

    @Test fun languageImplementationCannotStartInsideActivationTransition() {
        for (status in listOf("active", "implemented", "complete")) {
            val current = snapshot
            val slices = records(current.integrityWorkPackage["implementationSlices"]).mapIndexed { index, item ->
                if (index == 0) item + ("status" to status) else item
            }
            rejected(current.copy(integrityWorkPackage = current.integrityWorkPackage + ("implementationSlices" to slices)), "separate green activation")
        }
    }

    @Test fun duplicateMissingOrReorderedSlicesCannotAuthorizeBroaderWork() {
        val current = snapshot
        val slices = records(current.integrityWorkPackage["implementationSlices"])
        for (changed in listOf(slices.drop(1), slices.reversed(), slices + slices.first())) {
            rejected(current.copy(integrityWorkPackage = current.integrityWorkPackage + ("implementationSlices" to changed)), "duplicate-declaration work only")
        }
    }

    @Test fun activationCannotCloseItsOwnFindings() {
        rejected(changeLanguage("completionDecision") { it + ("status" to "complete") }, "cannot close any finding")
        rejected(changeLanguage("completionDecision") { it + ("closesFindings" to listOf("F-07")) }, "cannot close any finding")
    }

    @Test fun compatibilityRetirementRemainsOutsideThisTransition() {
        rejected(changeModules("completionDecision") { it + ("closesFindings" to listOf("F-10", "F-20")) }, "F-20 remains AR-07-owned")
        val current = snapshot
        val inventory = section(current.moduleBoundaryInventory["integratedDecision"])
        rejected(current.copy(moduleBoundaryInventory = current.moduleBoundaryInventory +
            ("integratedDecision" to (inventory + ("containedFindings" to emptyList<String>())))), "compatibility inventory")
    }

    @Test fun findingRegisterCannotDiscardReassignOrDuplicateFindings() {
        val current = snapshot
        val register = records(current.recovery["findingRegister"])
        for (id in listOf("F-10", "F-20", "F-07", "F-12", "F-13", "F-14", "F-21")) {
            for (changed in listOf(register.filterNot { it["id"] == id }, register + register.single { it["id"] == id })) {
                assertTrue(WorkflowSemanticsRecoveryLifecycle.errors(current.copy(recovery = current.recovery +
                    ("findingRegister" to changed))).isNotEmpty(), "Missing or duplicate $id was accepted")
            }
        }
    }

    @Test fun recoveryPointersCannotRemainOnTheOldActiveWorkPackage() {
        val current = snapshot
        rejected(current.copy(postToolchain = current.postToolchain + ("recoveryRoadmap" to
            (section(current.postToolchain["recoveryRoadmap"]) + ("activeItem" to "AR-03")))), "recovery pointers")
        rejected(current.copy(release = current.release + ("roadmapState" to (section(current.release["roadmapState"]) +
            ("activeRecoveryWorkPackage" to CompilerModuleExtractionLifecycle.WORK_PACKAGE)))), "Release metadata")
    }

    @Test fun successorWorkPackageCannotBeHiddenByChangingOnlyCurrentDecision() {
        val current = snapshot
        rejected(current.copy(recovery = current.recovery + ("currentDecision" to
            (section(current.recovery["currentDecision"]) + ("workPackage" to CompilerModuleExtractionLifecycle.WORK_PACKAGE)))), "AR-04 must remain planned")
    }

    @Test fun laterMilestonesCannotBeActivatedByLanguageSelection() {
        val current = snapshot
        for (id in listOf("AR-05", "AR-06", "AR-07")) {
            val milestones = records(current.recovery["milestones"]).map { if (it["id"] == id) it + ("status" to "active") else it }
            rejected(current.copy(recovery = current.recovery + ("milestones" to milestones)), "activate later recovery milestones")
        }
    }

    @Test fun pausedExternalFalsificationCannotBeResumedAsASideEffect() {
        val current = snapshot
        val sequence = records(current.postToolchain["sequence"]).map { if (it["id"] == "EF-09") it + ("status" to "active") else it }
        rejected(current.copy(postToolchain = current.postToolchain + ("sequence" to sequence)), "resume EF-09")
    }

    @Test fun moduleCompletionKeepsHistoricalSuccessorDecisionImmutable() {
        val current = snapshot
        assertEquals("not-activated", section(current.successorWorkPackage["completionDecision"])["nextItemActivationState"])
        assertEquals("not-activated", section(current.workPackage["completionDecision"])["nextItemActivationState"])
        rejected(changeModules("completionDecision") { it + ("nextItemActivationState" to "active") }, "separately owned")
    }

    private fun changeModules(key: String, change: (Map<String, Any?>) -> Map<String, Any?>): WorkflowSemanticsRecoveryLifecycleSnapshot {
        val current = snapshot
        return current.copy(successorWorkPackage = current.successorWorkPackage + (key to change(section(current.successorWorkPackage[key]))))
    }
    private fun changeLanguage(key: String, change: (Map<String, Any?>) -> Map<String, Any?>): WorkflowSemanticsRecoveryLifecycleSnapshot {
        val current = snapshot
        return current.copy(integrityWorkPackage = current.integrityWorkPackage + (key to change(section(current.integrityWorkPackage[key]))))
    }
    private fun changeModuleBoundary(name: String, change: (Map<String, Any?>) -> Map<String, Any?>): WorkflowSemanticsRecoveryLifecycleSnapshot =
        changeModules("lifecycle") { it + (name to change(section(it[name]))) }
    private fun rejected(current: WorkflowSemanticsRecoveryLifecycleSnapshot, fragment: String) {
        val errors = WorkflowSemanticsRecoveryLifecycle.errors(current)
        assertTrue(errors.any { fragment in it }, "Expected '$fragment': ${errors.joinToString(" | ")}")
    }
    private fun errors(current: WorkflowSemanticsRecoveryLifecycleSnapshot): String = WorkflowSemanticsRecoveryLifecycle.errors(current).joinToString(" | ")
    private fun section(value: Any?): Map<String, Any?> = (value as Map<*, *>).entries.associate { it.key.toString() to it.value }
    private fun records(value: Any?): List<Map<String, Any?>> = (value as List<*>).map(::section)
    private fun withMetadataFixture(verify: (File) -> Unit) {
        val root = createTempDirectory("language-lifecycle-").toFile()
        try {
            listOf(WorkflowSemanticsRecoveryLifecycle.WORK_PACKAGE, CompilerModuleExtractionLifecycle.WORK_PACKAGE,
                LanguageContractIntegrityLifecycle.WORK_PACKAGE, CompilerModuleAcceptance.EVIDENCE, CompilerModuleAcceptance.INVENTORY,
                ".flow-agent/roadmap-architecture-recovery.yaml", ".flow-agent/roadmap-post-toolchain.yaml",
                ".flow-agent/release-state.yaml", ".flow-agent/roadmap.yaml").forEach { name ->
                File(name).copyTo(File(root, name).also { it.parentFile.mkdirs() })
            }
            verify(root)
        } finally {
            check(root.deleteRecursively()) { "Cannot remove lifecycle fixture" }
        }
    }
}
