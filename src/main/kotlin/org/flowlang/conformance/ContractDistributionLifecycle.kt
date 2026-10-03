package org.flowlang.conformance

import java.security.MessageDigest
import org.flowlang.conformance.CompilerModuleAcceptance.matchingFields
import org.flowlang.conformance.CompilerModuleAcceptance.section

/** A separately accepted CLI slice authorizes resource work, never milestone completion. */
internal object ContractDistributionLifecycle {
    const val CLI_EVIDENCE = ".flow-agent/evidence/cli-argument-acceptance.json"
    const val CLI_SHA256 = "e65ab6cb5f86f9df801c256f393a58695214314b47786f78df295bb7db62de43"
    private val candidate = mapOf("source" to "current-revision-ci",
        "requiredChecks" to listOf("compile-test-conformance", "merge-candidate-compile-test-conformance"))
    private val acceptance = mapOf("source" to CLI_EVIDENCE, "sha256" to CLI_SHA256,
        "mainCommit" to "f7def792f9579cfcb9ed27f08f4b2b23b4e97ead", "workflowRunId" to 36998199817L)

    fun errors(snapshot: WorkflowSemanticsRecoveryLifecycleSnapshot): List<String> = buildList {
        val work = snapshot.artifactWorkPackage
        addAll(matchingFields("Contract resource slice", work, mapOf("selectedSlice" to "AR-05B", "nextSlice" to "AR-05C")))
        val raw = snapshot.cliArgumentAcceptanceEvidence
        val digest = raw?.let { MessageDigest.getInstance("SHA-256").digest(it.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) } }
        if (digest != CLI_SHA256) add("Resource work requires independently inspected AR-05A PR and actual-main evidence.")
        val slices = (work["implementationSlices"] as? List<*>).orEmpty().map { section(it).entries.associate { e -> e.key.toString() to e.value } }
        if (slices.size != 5 || slices.getOrNull(0)?.get("status") != "accepted" ||
            section(slices.getOrNull(0)?.get("acceptance")) != acceptance ||
            slices.getOrNull(1)?.get("status") != "implemented" ||
            section(slices.getOrNull(1)?.get("acceptance")) != candidate) {
            add("Resource candidate requires exact CLI acceptance and current-revision resource validation.")
        }
        // Replay only the already-checked transition fields; all finding owners,
        // later slices, historical receipts and roadmap pointers remain enforced.
        val predecessor = work + mapOf("selectedSlice" to "AR-05A", "nextSlice" to "AR-05B",
            "implementationSlices" to slices.mapIndexed { index, slice -> when (index) {
                0 -> slice + mapOf("status" to "implemented", "acceptance" to candidate)
                1 -> (slice - "acceptance") + ("status" to "planned")
                else -> slice
            } })
        addAll(ArtifactIntegrityLifecycle.errors(snapshot.copy(artifactWorkPackage = predecessor)))
    }
}
