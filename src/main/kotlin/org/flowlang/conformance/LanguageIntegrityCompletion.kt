package org.flowlang.conformance

import org.flowlang.conformance.CompilerModuleAcceptance.matchingFields
import org.flowlang.conformance.CompilerModuleAcceptance.section
import org.flowlang.serialization.FlowYaml
import java.security.MessageDigest

/** Accepts independent PR validation and actual-main completion without activating the successor. */
internal object LanguageIntegrityCompletion {
    const val EVIDENCE = ".flow-agent/evidence/language-integrity-completion-acceptance.json"
    private const val SHA256 = "ce43db74f89150896854f98e52dcf12a62aca6aab6f741b54f3c8e81ede6419e"

    fun errors(snapshot: WorkflowSemanticsRecoveryLifecycleSnapshot): List<String> = buildList {
        val document = snapshot.languageCompletionEvidence
        val digest = document?.let {
            MessageDigest.getInstance("SHA-256").digest(it.toByteArray(Charsets.UTF_8))
                .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
        }
        if (digest != SHA256) {
            add("Language completion requires the exact independently inspected validation and actual-main evidence bytes.")
            return@buildList
        }
        val evidence = FlowYaml.readMap(requireNotNull(document), EVIDENCE)
        val work = snapshot.integrityWorkPackage
        val lifecycle = section(work["lifecycle"])
        val jobs = section(evidence["jobs"])
        val main = section(evidence["postMerge"])
        val tests = section(section(evidence["junit"])["exactHead"])
        val checks = section(section(evidence["conformance"])["exactHead"])
        val common = mapOf(
            "status" to "passed", "conclusion" to "success", "sourceTree" to evidence["sourceTree"],
            "kotlinTests" to tests["tests"], "toolingTests" to evidence["toolingTests"],
            "conformanceChecks" to checks["passed"], "failures" to 0, "errors" to 0, "skipped" to 0,
            "evidence" to EVIDENCE, "evidenceSha256" to SHA256)
        addAll(exactFields("Language validationBoundary", section(lifecycle["validationBoundary"]), common + mapOf(
            "workflowRunId" to evidence["workflowRunId"], "workflowRunNumber" to evidence["workflowRunNumber"],
            "exactHead" to evidence["head"], "syntheticMergeCandidate" to evidence["syntheticMerge"],
            "base" to evidence["base"], "exactHeadJobId" to jobs["exactHead"],
            "mergeCandidateJobId" to jobs["mergeCandidate"], "isolationJobId" to jobs["physicalIsolation"])))
        // A push validates actual main. It must never invent a second synthetic-merge job.
        addAll(exactFields("Language completionBoundary", section(lifecycle["completionBoundary"]), common + mapOf(
            "event" to "push", "mergedPullRequest" to evidence["pullRequest"],
            "mainCommit" to evidence["mergedMain"], "validatedHead" to evidence["head"],
            "workflowRunId" to main["workflowRunId"], "workflowRunNumber" to main["workflowRunNumber"],
            "exactHeadJobId" to main["exactHeadJobId"], "isolationJobId" to main["isolationJobId"])))
        addAll(exactFields("Language acceptanceEvidence", section(work["acceptanceEvidence"]),
            mapOf("path" to EVIDENCE, "sha256" to SHA256)))
        addAll(exactFields("Language completionDecision", section(work["completionDecision"]), mapOf(
            "status" to "complete", "completedSlice" to "AR-04F", "closesFindings" to evidence["closesFindings"],
            "remainingFindings" to emptyList<String>(), "nextItem" to "AR-05", "nextItemActivationState" to "not-activated")))

        val path = LanguageContractIntegrityLifecycle.WORK_PACKAGE
        val nextName = "Artifact, Distribution, CLI and I/O Integrity"
        val decision = mapOf("nextItem" to "AR-05", "nextItemName" to nextName,
            "activationState" to "not-activated", "workPackage" to path)
        addAll(matchingFields("Language recovery decision", section(snapshot.recovery["currentDecision"]), decision + mapOf(
            "previousCompletedItem" to "AR-04", "previousCompletedItemName" to work["name"])))
        addAll(matchingFields("Language post-toolchain decision", section(snapshot.postToolchain["currentDecision"]), decision + mapOf(
            "completedItem" to "AR-04", "completedItemName" to work["name"])))
        addAll(matchingFields("Language recovery state", section(snapshot.postToolchain["recoveryRoadmap"]), mapOf(
            "completedItem" to "AR-04", "activeItem" to "", "activeWorkPackage" to "",
            "nextItem" to "AR-05", "activationState" to "not-activated")))
        val sequence = (snapshot.postToolchain["sequence"] as? List<*>).orEmpty().map(::section)
        addAll(matchingFields("Language recovery sequence", sequence.singleOrNull { it["id"] == "ARCHITECTURE-RECOVERY" }.orEmpty(),
            mapOf("status" to "active", "completedItem" to "AR-04", "activeItem" to "", "nextItem" to "AR-05",
                "activationState" to "not-activated", "workPackage" to path)))
        addAll(matchingFields("Language release state", section(snapshot.release["roadmapState"]), mapOf(
            "completedRecoveryItem" to "AR-04", "completedRecoveryItemName" to work["name"],
            "completedRecoveryWorkPackage" to path, "activeRecoveryWorkPackage" to "",
            "nextRecoveryItem" to "AR-05", "nextRecoveryItemName" to nextName, "nextRecoveryActivationState" to "not-activated")))
        addAll(exactFields("Language release acceptance", section(snapshot.release["recoveryAcceptance"]), mapOf(
            "completedItem" to "AR-04", "evidence" to EVIDENCE, "acceptedMain" to evidence["mergedMain"],
            "workflowRunId" to main["workflowRunId"], "successorCandidate" to "AR-05",
            "candidateValidation" to "current-revision-ci-required")))

        val before = (evidence["findingStateBeforeClosure"] as List<*>).map(::section)
        val findings = (snapshot.recovery["findingRegister"] as? List<*>).orEmpty().map(::section)
        if (findings.map { it["id"] } != before.map { it["id"] }) {
            add("Language closure must preserve the complete ordered finding inventory.")
        }
        val owned = evidence["closesFindings"] as List<*>
        before.forEach { previous ->
            val expected = if (previous["id"] in owned) previous + mapOf("status" to "closed", "closureEvidence" to EVIDENCE) else previous
            val actual = findings.singleOrNull { it["id"] == previous["id"] }.orEmpty()
            if (expected.any { (key, value) -> actual[key] != value }) {
                add("Language closure must preserve finding ${previous["id"]} ownership and close only its five accepted findings.")
            }
        }
    }

    private fun exactFields(owner: String, actual: Map<*, *>, expected: Map<String, Any?>): List<String> =
        matchingFields(owner, actual, expected) + if (actual.keys == expected.keys) emptyList() else
            listOf("$owner has missing or unsupported receipt fields.")
}
