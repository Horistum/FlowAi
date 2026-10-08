package org.flowlang.conformance

import java.security.MessageDigest
import org.flowlang.conformance.CompilerModuleAcceptance.matchingFields
import org.flowlang.conformance.CompilerModuleAcceptance.section
import org.flowlang.serialization.FlowYaml

/** Accepts the independently verified binding slice, without certifying runtime behavior. */
internal object AdapterObservationAuthenticationLifecycle {
    const val WORK_PACKAGE = ".flow-agent/work-packages/adapter-observation-authentication.yaml"
    const val EVIDENCE = ".flow-agent/evidence/adapter-certification-binding-acceptance.json"
    private const val SHA256 = "31ccc9a76188bae91787ba5241f32c02a3c0ee6799aba256a4c178738d75c5e2"
    private val validation = mapOf(
        "status" to "current-revision-ci-required",
        "requiredChecks" to listOf("compile-test-conformance", "merge-candidate-compile-test-conformance"),
        "negativeEvidence" to "Every signed field, untrusted and substituted keys, stale challenge, omitted/duplicate/unexpected runs, oversized statements, failed runtime, forged evidence bytes and mutated lifecycle receipts.")

    fun errors(s: WorkflowSemanticsRecoveryLifecycleSnapshot): List<String> = buildList {
        val raw = s.certificationBindingEvidence
        val sha = raw?.let { MessageDigest.getInstance("SHA-256").digest(it.toByteArray(Charsets.UTF_8))
            .joinToString("") { b -> "%02x".format(b.toInt() and 255) } }
        if (sha != SHA256) {
            add("Observation authentication requires the inspected final PR and actual-main binding evidence bytes.")
            return@buildList
        }
        val evidence = FlowYaml.readMap(requireNotNull(raw), EVIDENCE)
        val authorization = mapOf("acceptedMain" to evidence["mergedMain"],
            "workflowRunId" to section(evidence["postMerge"])["workflowRunId"])
        val parent = s.certificationWorkPackage
        addAll(matchingFields("Certification slice", parent, mapOf("selectedSlice" to "AR-06C",
            "acceptedSlices" to listOf("AR-06A", "AR-06B"), "selectedWorkPackage" to WORK_PACKAGE)))
        addAll(exact("Binding acceptance evidence", section(parent["bindingAcceptanceEvidence"]), mapOf("path" to EVIDENCE, "sha256" to SHA256)))
        addAll(matchingFields("Certification authorization", section(parent["authorization"]), authorization))
        addAll(exact("Certification validation", section(parent["validation"]), validation))
        val work = s.observationAuthenticationWorkPackage
        addAll(matchingFields("Observation authentication work", work, mapOf("version" to "AR-06C", "status" to "active",
            "stream" to "architecture-recovery", "roadmapReference" to ".flow-agent/roadmap-architecture-recovery.yaml#AR-06",
            "parentWorkPackage" to AdapterCertificationLifecycle.WORK_PACKAGE)))
        addAll(matchingFields("Observation authentication authorization", section(work["authorization"]), authorization))
        addAll(exact("Observation authentication validation", section(work["validation"]), validation))
        addAll(exact("Certification release state", section(s.release["recoveryCertification"]), mapOf(
            "acceptedSlice" to "AR-06B", "acceptedMain" to evidence["mergedMain"], "workflowRunId" to authorization["workflowRunId"],
            "evidence" to EVIDENCE, "selectedSlice" to "AR-06C", "workPackage" to WORK_PACKAGE,
            "validation" to "current-revision-ci-required", "supportPromotion" to false)))
        // Only authenticated, checked slice fields are restored. All old gates remain live.
        addAll(AdapterCertificationLifecycle.errors(predecessorSnapshot(s)))
    }

    internal fun predecessorSnapshot(s: WorkflowSemanticsRecoveryLifecycleSnapshot): WorkflowSemanticsRecoveryLifecycleSnapshot {
        val evidence = FlowYaml.readMap(requireNotNull(s.certificationBindingEvidence), EVIDENCE)
        val before = section(evidence["predecessorWorkFields"]).entries.associate { it.key.toString() to it.value }
        return s.copy(certificationWorkPackage = (s.certificationWorkPackage - "bindingAcceptanceEvidence" - "selectedWorkPackage") + before,
            release = s.release - "recoveryCertification", observationAuthenticationWorkPackage = emptyMap(), certificationBindingEvidence = null)
    }

    private fun exact(name: String, actual: Map<*, *>, expected: Map<String, Any?>) =
        matchingFields(name, actual, expected) + if (actual.keys == expected.keys) emptyList() else listOf("$name has unsupported fields.")
}
