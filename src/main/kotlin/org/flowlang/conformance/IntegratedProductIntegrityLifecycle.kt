package org.flowlang.conformance

import java.security.MessageDigest
import org.flowlang.conformance.CompilerModuleAcceptance.matchingFields
import org.flowlang.conformance.CompilerModuleAcceptance.section

/** Independent publication acceptance permits integrated product validation; closure needs a later immutable receipt. */
internal object IntegratedProductIntegrityLifecycle {
    const val EVIDENCE = ".flow-agent/evidence/atomic-publication-acceptance.json"
    const val SHA256 = "77c7fd3a3ae317ac15d1a62677234bcfd845d5844696eeda3d8b3770b0cd882c"
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
        if (slices.size != 5 || slices.getOrNull(3)?.get("status") != "accepted" ||
            section(slices.getOrNull(3)?.get("acceptance")) != acceptance ||
            slices.getOrNull(4)?.get("status") != "implemented" ||
            section(slices.getOrNull(4)?.get("acceptance")) != candidate) {
            add("Integrated product validation requires exact publication acceptance and current-revision validation.")
        }
        val predecessor = work + mapOf("selectedSlice" to "AR-05D", "nextSlice" to "AR-05E",
            "implementationSlices" to slices.mapIndexed { index, slice -> when (index) {
                3 -> slice + mapOf("status" to "implemented", "acceptance" to candidate)
                4 -> (slice - "acceptance") + ("status" to "planned")
                else -> slice
            } })
        addAll(AtomicPublicationLifecycle.errors(snapshot.copy(artifactWorkPackage = predecessor)))
    }
}
