package org.flowlang.conformance

import java.security.MessageDigest
import org.flowlang.conformance.CompilerModuleAcceptance.matchingFields
import org.flowlang.conformance.CompilerModuleAcceptance.section
import org.flowlang.serialization.FlowYaml

/** Independent publication acceptance permits integrated product validation; closure needs a later immutable receipt. */
internal object IntegratedProductIntegrityLifecycle {
    const val EVIDENCE = ".flow-agent/evidence/atomic-publication-acceptance.json"
    const val SHA256 = "77c7fd3a3ae317ac15d1a62677234bcfd845d5844696eeda3d8b3770b0cd882c"
    const val IMPLEMENTATION_EVIDENCE = ".flow-agent/evidence/integrated-product-implementation-acceptance.json"
    private const val IMPLEMENTATION_SHA256 = "d521109b78b524f818b388200680df26b58098c1af3c7781e9c8349090c41f6f"
    private val candidate = mapOf("source" to "current-revision-ci",
        "requiredChecks" to listOf("compile-test-conformance", "merge-candidate-compile-test-conformance"))
    private val acceptance = mapOf("source" to EVIDENCE, "sha256" to SHA256,
        "mainCommit" to "c389408a067ba18b7e66aea0b23f3b5e51ab73b8", "workflowRunId" to 37270649521L)

    fun errors(snapshot: WorkflowSemanticsRecoveryLifecycleSnapshot): List<String> = buildList {
        val work = snapshot.artifactWorkPackage
        addAll(matchingFields("Integrated product slice", work, mapOf("selectedSlice" to "AR-05E", "nextSlice" to "")))
        val raw = snapshot.atomicPublicationAcceptanceEvidence
        val digest = raw?.let { MessageDigest.getInstance("SHA-256").digest(it.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) } }
        if (digest != SHA256) add("Integrated product integrity requires independently inspected AR-05D PR and actual-main evidence.")
        val slices = (work["implementationSlices"] as? List<*>).orEmpty().map { section(it).entries.associate { e -> e.key.toString() to e.value } }
        val accepted = slices.getOrNull(4)?.get("status") == "accepted"
        val lifecycle = section(work["lifecycle"]).entries.associate { it.key.toString() to it.value }
        if (slices.size != 5 || slices.getOrNull(3)?.get("status") != "accepted" ||
            section(slices.getOrNull(3)?.get("acceptance")) != acceptance ||
            (!accepted && (slices.getOrNull(4)?.get("status") != "implemented" ||
                section(slices.getOrNull(4)?.get("acceptance")) != candidate))) {
            add("Integrated product validation requires exact publication acceptance and current-revision validation.")
        }
        if (accepted) addAll(implementationErrors(snapshot, section(slices[4]["acceptance"]),
            section(lifecycle["implementationBoundary"])))
        val predecessor = work + mapOf("selectedSlice" to "AR-05D", "nextSlice" to "AR-05E",
            // Replay only the accepted implementation boundary. Validation and completion
            // remain live claims checked by the predecessor and cannot borrow this receipt.
            "lifecycle" to if (accepted) lifecycle + ("implementationBoundary" to mapOf("status" to "pending")) else lifecycle,
            "implementationSlices" to slices.mapIndexed { index, slice -> when (index) {
                3 -> slice + mapOf("status" to "implemented", "acceptance" to candidate)
                4 -> (slice - "acceptance") + ("status" to "planned")
                else -> slice
            } })
        addAll(AtomicPublicationLifecycle.errors(snapshot.copy(artifactWorkPackage = predecessor)))
    }

    private fun implementationErrors(snapshot: WorkflowSemanticsRecoveryLifecycleSnapshot,
        receipt: Map<*, *>, boundary: Map<*, *>): List<String> = buildList {
        val raw = snapshot.integratedProductAcceptanceEvidence
        val digest = raw?.let { MessageDigest.getInstance("SHA-256").digest(it.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) } }
        // Authenticate the same document that is parsed; caller-authored digests,
        // equivalent JSON or a predecessor receipt cannot substitute inspected bytes.
        if (digest != IMPLEMENTATION_SHA256) {
            add("Integrated product acceptance requires the exact independently inspected PR and actual-main evidence bytes.")
            return@buildList
        }
        val evidence = FlowYaml.readMap(requireNotNull(raw), IMPLEMENTATION_EVIDENCE)
        val main = section(evidence["main"])
        val pr = section(evidence["acceptedPullRequest"])
        addAll(exact("Integrated product acceptance", receipt, mapOf(
            "source" to IMPLEMENTATION_EVIDENCE, "sha256" to IMPLEMENTATION_SHA256,
            "mainCommit" to main["mainCommit"], "workflowRunId" to main["workflowRunId"])))
        addAll(exact("Product implementation boundary", boundary, mapOf(
            "status" to "passed", "conclusion" to "success", "scope" to "integrated-implementation",
            "pullRequest" to pr["number"], "base" to pr["base"], "exactHead" to pr["exactHead"],
            "syntheticMergeCandidate" to pr["syntheticMerge"], "sourceTree" to pr["sourceTree"],
            "workflowRunId" to pr["workflowRunId"], "workflowRunNumber" to pr["workflowRunNumber"],
            "exactHeadJobId" to pr["exactHeadJobId"], "mergeCandidateJobId" to pr["mergeCandidateJobId"],
            "isolationJobId" to pr["isolationJobId"], "mainCommit" to main["mainCommit"],
            "postMergeWorkflowRunId" to main["workflowRunId"], "postMergeWorkflowRunNumber" to main["workflowRunNumber"],
            "mainExactHeadJobId" to main["exactHeadJobId"], "mainIsolationJobId" to main["isolationJobId"],
            "kotlinTests" to main["kotlinTests"], "toolingTests" to main["toolingTests"],
            "conformanceChecks" to main["conformanceChecks"], "failures" to 0, "errors" to 0, "skipped" to 0,
            "evidence" to IMPLEMENTATION_EVIDENCE, "evidenceSha256" to IMPLEMENTATION_SHA256)))
    }

    private fun exact(owner: String, actual: Map<*, *>, expected: Map<String, Any?>): List<String> =
        matchingFields(owner, actual, expected) + if (actual.keys == expected.keys) emptyList() else
            listOf("$owner has missing or unsupported receipt fields.")
}
