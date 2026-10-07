package org.flowlang.conformance

import java.security.MessageDigest
import org.flowlang.conformance.CompilerModuleAcceptance.matchingFields
import org.flowlang.conformance.CompilerModuleAcceptance.section
import org.flowlang.serialization.FlowYaml

/** Activates implementation only; historical completion and all finding ownership stay live. */
internal object AdapterCertificationLifecycle {
    const val WORK_PACKAGE = ".flow-agent/work-packages/behavioral-adapter-certification.yaml"
    const val EVIDENCE = ".flow-agent/evidence/adapter-certification-activation-baseline.json"
    private const val SHA256 = "a6460739813c3f7b136888918e181e1bc3070ae08a5d6b5e909c7db14500f0f8"

    fun errors(s: WorkflowSemanticsRecoveryLifecycleSnapshot): List<String> = buildList {
        val raw = s.certificationActivationEvidence
        val sha = raw?.let { MessageDigest.getInstance("SHA-256").digest(it.toByteArray(Charsets.UTF_8))
            .joinToString("") { b -> "%02x".format(b.toInt() and 0xff) } }
        if (sha != SHA256) {
            add("Adapter activation requires the independently inspected PR and actual-main evidence bytes.")
            return@buildList
        }
        val evidence = FlowYaml.readMap(requireNotNull(raw), EVIDENCE)
        val transition = stringMap(evidence["transition"])
        val work = s.certificationWorkPackage
        addAll(matchingFields("Certification work", work, mapOf(
            "version" to "AR-06", "name" to "Behavioral Adapter Certification and Honest Target Portfolio",
            "status" to "active", "selectedSlice" to "AR-06B", "acceptedSlices" to listOf("AR-06A"))))
        addAll(exact("Certification activation evidence", section(work["activationEvidence"]),
            mapOf("path" to EVIDENCE, "sha256" to SHA256)))
        addAll(matchingFields("Certification authorization", section(work["authorization"]), mapOf(
            "acceptedMain" to evidence["mergedMain"], "workflowRunId" to section(evidence["postMerge"])["workflowRunId"])))
        addAll(exact("Certification candidate validation", section(work["validation"]), mapOf(
            "status" to "current-revision-ci-required",
            "requiredChecks" to listOf("compile-test-conformance", "merge-candidate-compile-test-conformance"),
            "negativeEvidence" to "Substituted source or artifacts, compatibility-only requests, invented and omitted occurrence coverage, cross-scenario borrowing and mutated lifecycle receipts.")))
        addAll(matchingFields("Certification preparation", section(s.release["recoveryPreparation"]), mapOf(
            "candidate" to "AR-06A", "supportPromotion" to false,
            "workPackage" to ".flow-agent/work-packages/adapter-certification-contract.yaml")))
        val owners = owners(s)
        transition.forEach { (name, values) ->
            addAll(matchingFields("Certification $name", owners.getValue(name), stringMap(section(values)["after"])))
        }
        if (records(s.recovery["milestones"]).count { it["id"] == "AR-06" } != 1 ||
            records(s.postToolchain["sequence"]).count { it["id"] == "ARCHITECTURE-RECOVERY" } != 1)
            add("Certification activation requires unique milestone and sequence identities.")
        // Only checked transition fields are replayed. Every other live historical
        // receipt, finding, milestone and global/Core pointer is validated unchanged.
        addAll(ArtifactIntegrityLifecycle.errors(predecessorSnapshot(s)))
    }

    internal fun predecessorSnapshot(s: WorkflowSemanticsRecoveryLifecycleSnapshot): WorkflowSemanticsRecoveryLifecycleSnapshot {
        val evidence = FlowYaml.readMap(requireNotNull(s.certificationActivationEvidence), EVIDENCE)
        val transition = section(evidence["transition"])
        fun restore(owner: Map<String, Any?>, name: String) = owner + stringMap(section(transition[name])["before"])
        return s.copy(
            recovery = s.recovery + mapOf("currentDecision" to restore(stringMap(s.recovery["currentDecision"]), "recoveryDecision"),
                "milestones" to records(s.recovery["milestones"]).map { if (it["id"] == "AR-06") restore(it, "milestone") else it }),
            postToolchain = s.postToolchain + mapOf(
                "currentDecision" to restore(stringMap(s.postToolchain["currentDecision"]), "postDecision"),
                "recoveryRoadmap" to restore(stringMap(s.postToolchain["recoveryRoadmap"]), "recoveryRoadmap"),
                "sequence" to records(s.postToolchain["sequence"]).map { if (it["id"] == "ARCHITECTURE-RECOVERY") restore(it, "sequence") else it }),
            release = s.release + mapOf("roadmapState" to restore(stringMap(s.release["roadmapState"]), "releaseState"),
                "recoveryPreparation" to restore(stringMap(s.release["recoveryPreparation"]), "preparation")))
    }

    private fun owners(s: WorkflowSemanticsRecoveryLifecycleSnapshot): Map<String, Map<String, Any?>> = mapOf(
        "recoveryDecision" to stringMap(s.recovery["currentDecision"]),
        "postDecision" to stringMap(s.postToolchain["currentDecision"]),
        "recoveryRoadmap" to stringMap(s.postToolchain["recoveryRoadmap"]),
        "sequence" to records(s.postToolchain["sequence"]).singleOrNull { it["id"] == "ARCHITECTURE-RECOVERY" }.orEmpty(),
        "releaseState" to stringMap(s.release["roadmapState"]),
        "milestone" to records(s.recovery["milestones"]).singleOrNull { it["id"] == "AR-06" }.orEmpty(),
        "preparation" to stringMap(s.release["recoveryPreparation"]))
    private fun exact(name: String, actual: Map<*, *>, expected: Map<String, Any?>) =
        matchingFields(name, actual, expected) + if (actual.keys == expected.keys) emptyList() else listOf("$name has unsupported fields.")
    private fun stringMap(value: Any?): Map<String, Any?> = section(value).entries.associate { it.key.toString() to it.value }
    private fun records(value: Any?): List<Map<String, Any?>> = (value as? List<*>).orEmpty().map(::stringMap)
}
