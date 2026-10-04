package org.flowlang.conformance

import java.security.MessageDigest
import org.flowlang.conformance.CompilerModuleAcceptance.matchingFields
import org.flowlang.conformance.CompilerModuleAcceptance.section
import org.flowlang.serialization.FlowYaml

/** Owns AR-05 succession; historical AR-04 acceptance is replayed without rewriting its evidence. */
internal object ArtifactIntegrityLifecycle {
    const val WORK_PACKAGE = ".flow-agent/work-packages/artifact-distribution-cli-io-integrity.yaml"
    const val EVIDENCE = ".flow-agent/evidence/artifact-integrity-activation-baseline.json"
    private const val SHA256 = "28ee22ce6c628ca8166bd8a3dc70bf05f3b1579487287b61ea01a529f6afd5af"
    private const val NAME = "Artifact, Distribution, CLI and I/O Integrity"
    private val findings = listOf("F-03", "F-05", "F-19", "F-22")

    fun errors(snapshot: WorkflowSemanticsRecoveryLifecycleSnapshot): List<String> = buildList {
        val work = snapshot.artifactWorkPackage
        if (work["selectedSlice"] == "AR-05C") {
            addAll(BoundedIoLifecycle.errors(snapshot))
            return@buildList
        }
        if (work["selectedSlice"] == "AR-05B") {
            addAll(ContractDistributionLifecycle.errors(snapshot))
            return@buildList
        }
        addAll(matchingFields("AR-05 work package", work, mapOf("version" to "AR-05", "name" to NAME,
            "status" to "active", "selectedSlice" to "AR-05A", "nextSlice" to "AR-05B")))
        addAll(matchingFields("AR-05 authorization", section(work["authorization"]), mapOf(
            "status" to "active", "predecessor" to "AR-04",
            "strategicSource" to ".flow-agent/roadmap-architecture-recovery.yaml#AR-05")))
        val raw = snapshot.artifactActivationEvidence
        val digest = raw?.let { MessageDigest.getInstance("SHA-256").digest(it.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) } }
        if (digest != SHA256) add("AR-05 requires the exact independently inspected AR-04 merged-main baseline.")
        else {
            val evidence = FlowYaml.readMap(requireNotNull(raw), EVIDENCE)
            addAll(exact("AR-05 verified baseline", section(work["verifiedBaseline"]),
                stringMap(evidence["main"]) + mapOf("evidence" to EVIDENCE, "evidenceSha256" to SHA256)))
        }
        val slices = records(work["implementationSlices"])
        val owners = listOf(listOf("F-19"), listOf("F-05"), listOf("F-22"), listOf("F-03", "F-22"), findings)
        if (slices.map { it["id"] } != listOf("AR-05A", "AR-05B", "AR-05C", "AR-05D", "AR-05E") ||
            slices.map { it["ownsFindings"] } != owners || slices.firstOrNull()?.get("status") != "implemented" ||
            slices.drop(1).any { it["status"] != "planned" || it.containsKey("acceptance") }) {
            add("AR-05A owns only CLI argument implementation; later slices remain planned with stable finding owners.")
        }
        addAll(exact("AR-05A candidate", section(slices.firstOrNull()?.get("acceptance")), mapOf(
            "source" to "current-revision-ci", "requiredChecks" to listOf("compile-test-conformance", "merge-candidate-compile-test-conformance"))))
        addAll(exact("AR-05 completion", section(work["completionDecision"]), mapOf(
            "status" to "not-complete", "closesFindings" to emptyList<String>(), "remainingFindings" to findings,
            "nextItem" to "AR-06", "nextItemActivationState" to "not-activated")))
        val lifecycle = section(work["lifecycle"])
        val expectedLifecycle = mapOf(
            "activationBoundary" to mapOf("status" to "authorized", "source" to "maintainer-request", "validation" to "current-revision-ci-required"),
            "implementationBoundary" to mapOf("status" to "pending"), "validationBoundary" to mapOf("status" to "pending"),
            "completionBoundary" to mapOf("status" to "pending"))
        if (lifecycle != expectedLifecycle) add("AR-05 candidate must not invent future activation, implementation or closure results.")

        val decision = mapOf("nextItem" to "AR-05", "nextItemName" to NAME, "activationState" to "active", "workPackage" to WORK_PACKAGE)
        addAll(matchingFields("AR-05 recovery decision", section(snapshot.recovery["currentDecision"]),
            decision + ("previousCompletedItem" to "AR-04")))
        addAll(matchingFields("AR-05 post-toolchain decision", section(snapshot.postToolchain["currentDecision"]),
            decision + ("completedItem" to "AR-04")))
        addAll(matchingFields("AR-05 recovery state", section(snapshot.postToolchain["recoveryRoadmap"]), mapOf(
            "completedItem" to "AR-04", "activeItem" to "AR-05", "activeWorkPackage" to WORK_PACKAGE,
            "nextItem" to "AR-05", "activationState" to "active")))
        addAll(matchingFields("AR-05 sequence", records(snapshot.postToolchain["sequence"]).singleOrNull {
            it["id"] == "ARCHITECTURE-RECOVERY" }.orEmpty(), mapOf("status" to "active", "completedItem" to "AR-04",
            "activeItem" to "AR-05", "nextItem" to "AR-05", "activationState" to "active", "workPackage" to WORK_PACKAGE)))
        addAll(matchingFields("AR-05 release state", section(snapshot.release["roadmapState"]), mapOf(
            "activeRecoveryWorkPackage" to WORK_PACKAGE, "nextRecoveryActivationState" to "active")))
        addAll(matchingFields("AR-05 milestone", records(snapshot.recovery["milestones"]).singleOrNull {
            it["id"] == "AR-05" }.orEmpty(), mapOf("name" to NAME, "status" to "active", "workPackage" to WORK_PACKAGE,
            "dependsOn" to listOf("AR-03", "AR-04"), "closesFindings" to findings)))
        // Change only succession fields checked above. Every historical proof, finding and Core pointer is still validated.
        addAll(WorkflowSemanticsRecoveryLifecycle.errors(predecessor(snapshot)))
    }

    private fun predecessor(s: WorkflowSemanticsRecoveryLifecycleSnapshot): WorkflowSemanticsRecoveryLifecycleSnapshot {
        val decision = mapOf("activationState" to "not-activated", "workPackage" to LanguageContractIntegrityLifecycle.WORK_PACKAGE)
        return s.copy(
            recovery = s.recovery + mapOf(
                "currentDecision" to (stringMap(s.recovery["currentDecision"]) + decision),
                "milestones" to records(s.recovery["milestones"]).map { if (it["id"] == "AR-05") it + ("status" to "planned") else it }),
            postToolchain = s.postToolchain + mapOf(
                "currentDecision" to (stringMap(s.postToolchain["currentDecision"]) + decision),
                "recoveryRoadmap" to (stringMap(s.postToolchain["recoveryRoadmap"]) + mapOf(
                    "activeItem" to "", "activeWorkPackage" to "", "activationState" to "not-activated")),
                "sequence" to records(s.postToolchain["sequence"]).map { if (it["id"] == "ARCHITECTURE-RECOVERY")
                    it + decision + ("activeItem" to "") else it }),
            release = s.release + ("roadmapState" to (stringMap(s.release["roadmapState"]) + mapOf(
                "activeRecoveryWorkPackage" to "", "nextRecoveryActivationState" to "not-activated"))))
    }

    private fun exact(owner: String, actual: Map<*, *>, expected: Map<String, Any?>): List<String> =
        matchingFields(owner, actual, expected) + if (actual.keys == expected.keys) emptyList() else listOf("$owner has unsupported or missing fields.")
    private fun stringMap(value: Any?): Map<String, Any?> = section(value).entries.associate { it.key.toString() to it.value }
    private fun records(value: Any?): List<Map<String, Any?>> = (value as? List<*>).orEmpty().map(::stringMap)
}
