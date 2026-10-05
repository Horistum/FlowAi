package org.flowlang.conformance

import org.flowlang.conformance.CompilerModuleAcceptance.matchingFields
import org.flowlang.conformance.CompilerModuleAcceptance.section
import org.flowlang.serialization.FlowYaml
import java.security.MessageDigest

/** Accepts independent PR validation and actual-main completion without activating the successor. */
internal object ArtifactIntegrityCompletion {
    const val EVIDENCE = ".flow-agent/evidence/artifact-integrity-completion-acceptance.json"
    private const val SHA256 = "0dd21c793d1fca995a3b3c3d4685a0b1863c36dad710e2fa2ded920380a8c74d"

    fun errors(snapshot: WorkflowSemanticsRecoveryLifecycleSnapshot): List<String> = buildList {
        val document = snapshot.artifactCompletionEvidence
        val digest = document?.let {
            MessageDigest.getInstance("SHA-256").digest(it.toByteArray(Charsets.UTF_8))
                .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
        }
        if (digest != SHA256) {
            add("Artifact completion requires the exact independently inspected validation and actual-main evidence bytes.")
            return@buildList
        }
        val evidence = FlowYaml.readMap(requireNotNull(document), EVIDENCE)
        val work = snapshot.artifactWorkPackage
        addAll(exactFields("Artifact authorization", section(work["authorization"]),
            stringMap(section(evidence["predecessor"])["authorization"]) + ("status" to "completed")))
        addAll(matchingFields("Artifact closure work", work, mapOf("status" to "complete", "selectedSlice" to "AR-05E", "nextSlice" to "")))
        if (records(work["implementationSlices"]).let { it.size != 5 || it.any { slice -> slice["status"] != "accepted" } }) {
            add("Artifact completion requires all five accepted implementation slices.")
        }
        val milestones = records(snapshot.recovery["milestones"])
        addAll(matchingFields("Artifact closure milestone", milestones.singleOrNull { it["id"] == "AR-05" }.orEmpty(),
            mapOf("status" to "completed", "name" to "Artifact, Distribution, CLI and I/O Integrity",
                "workPackage" to ArtifactIntegrityLifecycle.WORK_PACKAGE, "dependsOn" to listOf("AR-03", "AR-04"),
                "closesFindings" to evidence["closesFindings"])))
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
        addAll(exactFields("Artifact validationBoundary", section(lifecycle["validationBoundary"]), common + mapOf(
            "workflowRunId" to evidence["workflowRunId"], "workflowRunNumber" to evidence["workflowRunNumber"],
            "exactHead" to evidence["head"], "syntheticMergeCandidate" to evidence["syntheticMerge"],
            "base" to evidence["base"], "exactHeadJobId" to jobs["exactHead"],
            "mergeCandidateJobId" to jobs["mergeCandidate"], "isolationJobId" to jobs["physicalIsolation"])))
        // A push validates actual main. It must never invent a second synthetic-merge job.
        addAll(exactFields("Artifact completionBoundary", section(lifecycle["completionBoundary"]), common + mapOf(
            "event" to "push", "mergedPullRequest" to evidence["pullRequest"],
            "mainCommit" to evidence["mergedMain"], "validatedHead" to evidence["head"],
            "workflowRunId" to main["workflowRunId"], "workflowRunNumber" to main["workflowRunNumber"],
            "exactHeadJobId" to main["exactHeadJobId"], "isolationJobId" to main["isolationJobId"])))
        addAll(exactFields("Artifact acceptanceEvidence", section(work["acceptanceEvidence"]),
            mapOf("path" to EVIDENCE, "sha256" to SHA256)))
        addAll(exactFields("Artifact completionDecision", section(work["completionDecision"]), mapOf(
            "status" to "complete", "completedSlice" to "AR-05E", "closesFindings" to evidence["closesFindings"],
            "remainingFindings" to emptyList<String>(), "nextItem" to "AR-06", "nextItemActivationState" to "not-activated")))

        val path = ArtifactIntegrityLifecycle.WORK_PACKAGE
        val nextName = "Behavioral Adapter Certification and Honest Target Portfolio"
        val decision = mapOf("nextItem" to "AR-06", "nextItemName" to nextName,
            "activationState" to "not-activated", "workPackage" to path)
        addAll(matchingFields("Artifact recovery decision", section(snapshot.recovery["currentDecision"]), decision + mapOf(
            "previousCompletedItem" to "AR-05", "previousCompletedItemName" to work["name"])))
        addAll(matchingFields("Artifact post-toolchain decision", section(snapshot.postToolchain["currentDecision"]), decision + mapOf(
            "completedItem" to "AR-05", "completedItemName" to work["name"])))
        addAll(matchingFields("Artifact recovery state", section(snapshot.postToolchain["recoveryRoadmap"]), mapOf(
            "completedItem" to "AR-05", "activeItem" to "", "activeWorkPackage" to "",
            "nextItem" to "AR-06", "activationState" to "not-activated")))
        val sequence = (snapshot.postToolchain["sequence"] as? List<*>).orEmpty().map(::section)
        addAll(matchingFields("Artifact recovery sequence", sequence.singleOrNull { it["id"] == "ARCHITECTURE-RECOVERY" }.orEmpty(),
            mapOf("status" to "active", "completedItem" to "AR-05", "activeItem" to "", "nextItem" to "AR-06",
                "activationState" to "not-activated", "workPackage" to path)))
        addAll(matchingFields("Artifact release state", section(snapshot.release["roadmapState"]), mapOf(
            "completedRecoveryItem" to "AR-05", "completedRecoveryItemName" to work["name"],
            "completedRecoveryWorkPackage" to path, "activeRecoveryWorkPackage" to "",
            "nextRecoveryItem" to "AR-06", "nextRecoveryItemName" to nextName, "nextRecoveryActivationState" to "not-activated")))
        addAll(exactFields("Artifact release acceptance", section(snapshot.release["recoveryAcceptance"]), mapOf(
            "completedItem" to "AR-05", "evidence" to EVIDENCE, "acceptedMain" to evidence["mergedMain"],
            "workflowRunId" to main["workflowRunId"], "successorCandidate" to "AR-06",
            "candidateValidation" to "current-revision-ci-required")))

        val before = records(evidence["findingStateBeforeClosure"])
        val owned = evidence["closesFindings"] as List<*>
        val expectedFindings = before.map { previous -> if (previous["id"] in owned)
            previous + mapOf("status" to "closed", "closureEvidence" to EVIDENCE) else previous }
        if (records(snapshot.recovery["findingRegister"]) != expectedFindings) {
            add("Artifact closure must preserve all 22 ordered findings and close exactly its four accepted findings.")
        }
        // Replay only transitions whose live values were checked above. All historical
        // implementation receipts, global/Core pointers and later milestones stay live.
        addAll(ArtifactIntegrityLifecycle.errors(implementationSnapshot(snapshot, evidence)))
    }

    private fun implementationSnapshot(s: WorkflowSemanticsRecoveryLifecycleSnapshot,
        evidence: Map<String, Any?>): WorkflowSemanticsRecoveryLifecycleSnapshot {
        val prior = section(evidence["predecessor"])
        fun restore(owner: Map<String, Any?>, sectionName: String, key: String) =
            owner + (sectionName to (stringMap(owner[sectionName]) + stringMap(prior[key])))
        val work = s.artifactWorkPackage
        return s.copy(
            artifactWorkPackage = (work - "acceptanceEvidence") + mapOf("status" to "active",
                "authorization" to prior["authorization"], "completionDecision" to prior["completionDecision"],
                "lifecycle" to (stringMap(work["lifecycle"]) + mapOf(
                    "validationBoundary" to mapOf("status" to "pending"), "completionBoundary" to mapOf("status" to "pending")))),
            recovery = restore(s.recovery, "currentDecision", "recoveryDecision") + mapOf(
                "findingRegister" to evidence["findingStateBeforeClosure"],
                "milestones" to records(s.recovery["milestones"]).map { if (it["id"] == "AR-05") it + ("status" to "active") else it }),
            postToolchain = restore(restore(s.postToolchain, "currentDecision", "postDecision"), "recoveryRoadmap", "recoveryRoadmap") +
                ("sequence" to records(s.postToolchain["sequence"]).map { if (it["id"] == "ARCHITECTURE-RECOVERY")
                    it + stringMap(prior["sequence"]) else it }),
            release = restore(s.release, "roadmapState", "releaseState") + ("recoveryAcceptance" to prior["recoveryAcceptance"]))
    }

    private fun stringMap(value: Any?): Map<String, Any?> = section(value).entries.associate { it.key.toString() to it.value }
    private fun records(value: Any?): List<Map<String, Any?>> = (value as? List<*>).orEmpty().map(::stringMap)

    private fun exactFields(owner: String, actual: Map<*, *>, expected: Map<String, Any?>): List<String> =
        matchingFields(owner, actual, expected) + if (actual.keys == expected.keys) emptyList() else
            listOf("$owner has missing or unsupported receipt fields.")
}
