package org.flowlang.conformance

import java.security.MessageDigest
import org.flowlang.conformance.CompilerModuleAcceptance.matchingFields
import org.flowlang.conformance.CompilerModuleAcceptance.section

/** Independent bounded-I/O acceptance permits atomic publication work, without completing AR-05. */
internal object AtomicPublicationLifecycle {
    const val EVIDENCE = ".flow-agent/evidence/bounded-io-acceptance.json"
    const val SHA256 = "9b6cf4598d92f62fbb01bcd8a42d6aa36b31e448f5e4b847a0b73d19edcdd481"
    private val candidate = mapOf("source" to "current-revision-ci",
        "requiredChecks" to listOf("compile-test-conformance", "merge-candidate-compile-test-conformance"))
    private val acceptance = mapOf("source" to EVIDENCE, "sha256" to SHA256,
        "mainCommit" to "21f769f62756bb9df3a75896f7aa4ba10591ccf5", "workflowRunId" to 37202358850L)

    fun errors(snapshot: WorkflowSemanticsRecoveryLifecycleSnapshot): List<String> = buildList {
        val work = snapshot.artifactWorkPackage
        addAll(matchingFields("Atomic publication slice", work, mapOf("selectedSlice" to "AR-05D", "nextSlice" to "AR-05E")))
        val raw = snapshot.boundedIoAcceptanceEvidence
        val digest = raw?.let { MessageDigest.getInstance("SHA-256").digest(it.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) } }
        if (digest != SHA256) add("Atomic publication requires independently inspected AR-05C PR and actual-main evidence.")
        val slices = (work["implementationSlices"] as? List<*>).orEmpty().map { section(it).entries.associate { e -> e.key.toString() to e.value } }
        if (slices.size != 5 || slices.getOrNull(2)?.get("status") != "accepted" ||
            section(slices.getOrNull(2)?.get("acceptance")) != acceptance ||
            slices.getOrNull(3)?.get("status") != "implemented" ||
            section(slices.getOrNull(3)?.get("acceptance")) != candidate) {
            add("Atomic publication candidate requires exact bounded-I/O acceptance and current-revision validation.")
        }
        val predecessor = work + mapOf("selectedSlice" to "AR-05C", "nextSlice" to "AR-05D",
            "implementationSlices" to slices.mapIndexed { index, slice -> when (index) {
                2 -> slice + mapOf("status" to "implemented", "acceptance" to candidate)
                3 -> (slice - "acceptance") + ("status" to "planned")
                else -> slice
            } })
        addAll(BoundedIoLifecycle.errors(snapshot.copy(artifactWorkPackage = predecessor)))
    }
}
