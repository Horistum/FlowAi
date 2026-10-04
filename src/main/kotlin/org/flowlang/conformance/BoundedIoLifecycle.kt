package org.flowlang.conformance

import java.security.MessageDigest
import org.flowlang.conformance.CompilerModuleAcceptance.matchingFields
import org.flowlang.conformance.CompilerModuleAcceptance.section

/** Independent distribution acceptance permits bounded I/O work, without completing AR-05. */
internal object BoundedIoLifecycle {
    const val EVIDENCE = ".flow-agent/evidence/contract-distribution-acceptance.json"
    const val SHA256 = "b68ef5d56d983cd43a4571c1be1d53e0608eab1778496057464008125a7a9521"
    private val candidate = mapOf("source" to "current-revision-ci",
        "requiredChecks" to listOf("compile-test-conformance", "merge-candidate-compile-test-conformance"))
    private val acceptance = mapOf("source" to EVIDENCE, "sha256" to SHA256,
        "mainCommit" to "48ae72336759c44afc5cbfe1eece4957f4c1b1c2", "workflowRunId" to 37163508408L)

    fun errors(snapshot: WorkflowSemanticsRecoveryLifecycleSnapshot): List<String> = buildList {
        val work = snapshot.artifactWorkPackage
        addAll(matchingFields("Bounded I/O slice", work, mapOf("selectedSlice" to "AR-05C", "nextSlice" to "AR-05D")))
        val raw = snapshot.contractDistributionAcceptanceEvidence
        val digest = raw?.let { MessageDigest.getInstance("SHA-256").digest(it.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) } }
        if (digest != SHA256) add("Bounded I/O requires independently inspected AR-05B PR and actual-main evidence.")
        val slices = (work["implementationSlices"] as? List<*>).orEmpty().map { section(it).entries.associate { e -> e.key.toString() to e.value } }
        if (slices.size != 5 || slices.getOrNull(1)?.get("status") != "accepted" ||
            section(slices.getOrNull(1)?.get("acceptance")) != acceptance ||
            slices.getOrNull(2)?.get("status") != "implemented" ||
            section(slices.getOrNull(2)?.get("acceptance")) != candidate) {
            add("Bounded I/O candidate requires exact distribution acceptance and current-revision validation.")
        }
        val predecessor = work + mapOf("selectedSlice" to "AR-05B", "nextSlice" to "AR-05C",
            "implementationSlices" to slices.mapIndexed { index, slice -> when (index) {
                1 -> slice + mapOf("status" to "implemented", "acceptance" to candidate)
                2 -> (slice - "acceptance") + ("status" to "planned")
                else -> slice
            } })
        addAll(ContractDistributionLifecycle.errors(snapshot.copy(artifactWorkPackage = predecessor)))
    }
}
