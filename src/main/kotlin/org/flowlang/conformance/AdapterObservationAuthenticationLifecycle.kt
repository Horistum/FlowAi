package org.flowlang.conformance

import java.security.MessageDigest
import org.flowlang.conformance.CompilerModuleAcceptance.matchingFields
import org.flowlang.conformance.CompilerModuleAcceptance.section
import org.flowlang.serialization.FlowYaml

/** Accepts the independently verified binding slice, without certifying runtime behavior. */
internal object AdapterObservationAuthenticationLifecycle {
    const val WORK_PACKAGE = ".flow-agent/work-packages/adapter-observation-authentication.yaml"
    const val EVIDENCE = ".flow-agent/evidence/adapter-certification-binding-acceptance.json"
    const val VIEW_WORK_PACKAGE = ".flow-agent/work-packages/certification-evidence-views.yaml"
    const val RUNTIME_BASELINE = ".flow-agent/evidence/adapter-runtime-view-baseline.json"
    const val CONDITION_WORK_PACKAGE = ".flow-agent/work-packages/jenkins-condition-certification.yaml"
    const val CONDITION_BASELINE = ".flow-agent/evidence/jenkins-condition-baseline.json"
    const val ERROR_BOUNDARY_WORK_PACKAGE = ".flow-agent/work-packages/jenkins-error-boundary-certification.yaml"
    const val ERROR_BOUNDARY_BASELINE = ".flow-agent/evidence/jenkins-error-boundary-baseline.json"
    private const val ERROR_BOUNDARY_BASELINE_SHA256 = "cc117ba5c48c2a2abfc4b069eb709ecf0564b8f83e3790d344d4aaa109be8375"
    private const val CONDITION_BASELINE_SHA256 = "05261471566c273815b3e6d9d518f7bb5378ca0ad593060bff8c7825f825131c"
    private const val RUNTIME_BASELINE_SHA256 = "fdbfbe150d4df3fa90213527adf9aca58243c31215d21ca67d041bc475b66183"
    private const val SHA256 = "31ccc9a76188bae91787ba5241f32c02a3c0ee6799aba256a4c178738d75c5e2"
    private val validation = mapOf(
        "status" to "current-revision-ci-required",
        "requiredChecks" to listOf("compile-test-conformance", "merge-candidate-compile-test-conformance"),
        "negativeEvidence" to "Every signed field, untrusted and substituted keys, stale challenge, omitted/duplicate/unexpected runs, oversized statements, failed runtime, forged evidence bytes and mutated lifecycle receipts.")

    fun errors(s: WorkflowSemanticsRecoveryLifecycleSnapshot): List<String> = buildList {
        if (s.certificationWorkPackage["selectedSlice"] == "AR-06H" || s.release.containsKey("recoveryErrorBoundaryPreparation")) {
            val raw = s.certificationErrorBoundaryBaseline
            val sha = raw?.let { MessageDigest.getInstance("SHA-256").digest(it.toByteArray(Charsets.UTF_8))
                .joinToString("") { b -> "%02x".format(b.toInt() and 255) } }
            if (sha != ERROR_BOUNDARY_BASELINE_SHA256) {
                add("Workflow error-boundary certification requires the independently inspected merged-main baseline bytes.")
                return@buildList
            }
            val evidence = FlowYaml.readMap(requireNotNull(raw), ERROR_BOUNDARY_BASELINE)
            val transition = section(evidence["transition"])
            addAll(matchingFields("Workflow error-boundary selection", s.certificationWorkPackage, stringMap(section(transition["work"])["after"])))
            addAll(exact("Workflow error-boundary release selection", section(s.release["recoveryCertification"]),
                stringMap(section(transition["releaseCertification"])["after"])))
            addAll(matchingFields("Workflow error-boundary work", s.certificationErrorBoundaryWorkPackage, mapOf("version" to "AR-06H", "status" to "candidate",
                "stream" to "architecture-recovery", "roadmapReference" to ".flow-agent/roadmap-architecture-recovery.yaml#AR-06")))
            addAll(exact("Workflow error-boundary authorization", section(s.certificationErrorBoundaryWorkPackage["authorization"]), stringMap(evidence["candidateAuthorization"])))
            addAll(exact("Workflow error-boundary validation", section(s.certificationErrorBoundaryWorkPackage["validation"]),
                stringMap(section(section(transition["work"])["after"])["validation"])))
            addAll(exact("Workflow error-boundary candidate", section(s.release["recoveryErrorBoundaryPreparation"]), mapOf(
                "candidate" to "AR-06H", "status" to "candidate", "workPackage" to ERROR_BOUNDARY_WORK_PACKAGE,
                "report" to ".flow-agent/reports/jenkins-error-boundary-certification.md", "baselineEvidence" to ERROR_BOUNDARY_BASELINE,
                "validation" to "current-revision-ci-required", "supportPromotion" to false)))
            addAll(errors(errorBoundaryPredecessor(s)))
            return@buildList
        }
        if (s.certificationWorkPackage["selectedSlice"] == "AR-06G" || s.release.containsKey("recoveryConditionPreparation")) {
            val raw = s.certificationConditionBaseline
            val sha = raw?.let { MessageDigest.getInstance("SHA-256").digest(it.toByteArray(Charsets.UTF_8))
                .joinToString("") { b -> "%02x".format(b.toInt() and 255) } }
            if (sha != CONDITION_BASELINE_SHA256) {
                add("Conditional runtime certification requires the independently inspected merged-main baseline bytes.")
                return@buildList
            }
            val evidence = FlowYaml.readMap(requireNotNull(raw), CONDITION_BASELINE)
            val transition = section(evidence["transition"])
            addAll(matchingFields("Conditional runtime selection", s.certificationWorkPackage, stringMap(section(transition["work"])["after"])))
            addAll(exact("Conditional runtime release selection", section(s.release["recoveryCertification"]),
                stringMap(section(transition["releaseCertification"])["after"])))
            addAll(matchingFields("Conditional runtime work", s.certificationConditionWorkPackage, mapOf("version" to "AR-06G", "status" to "candidate",
                "stream" to "architecture-recovery", "roadmapReference" to ".flow-agent/roadmap-architecture-recovery.yaml#AR-06")))
            addAll(exact("Conditional runtime authorization", section(s.certificationConditionWorkPackage["authorization"]), stringMap(evidence["candidateAuthorization"])))
            addAll(exact("Conditional runtime validation", section(s.certificationConditionWorkPackage["validation"]),
                stringMap(section(section(transition["work"])["after"])["validation"])))
            addAll(exact("Conditional runtime candidate", section(s.release["recoveryConditionPreparation"]), mapOf(
                "candidate" to "AR-06G", "status" to "candidate", "workPackage" to CONDITION_WORK_PACKAGE,
                "report" to ".flow-agent/reports/jenkins-condition-certification.md", "baselineEvidence" to CONDITION_BASELINE,
                "validation" to "current-revision-ci-required", "supportPromotion" to false)))
            addAll(errors(conditionPredecessor(s)))
            return@buildList
        }
        if (s.certificationWorkPackage["selectedSlice"] == "AR-06F" || s.release.containsKey("recoveryEvidenceViews")) {
            val raw = s.certificationRuntimeBaseline
            val sha = raw?.let { MessageDigest.getInstance("SHA-256").digest(it.toByteArray(Charsets.UTF_8))
                .joinToString("") { b -> "%02x".format(b.toInt() and 255) } }
            if (sha != RUNTIME_BASELINE_SHA256) {
                add("Evidence views require the independently inspected merged-main runtime baseline bytes.")
                return@buildList
            }
            val evidence = FlowYaml.readMap(requireNotNull(raw), RUNTIME_BASELINE)
            val transition = section(evidence["transition"])
            addAll(matchingFields("Evidence view selection", s.certificationWorkPackage, stringMap(section(transition["work"])["after"])))
            addAll(exact("Evidence view release selection", section(s.release["recoveryCertification"]),
                stringMap(section(transition["releaseCertification"])["after"])))
            addAll(matchingFields("Evidence view work", s.certificationViewWorkPackage, mapOf("version" to "AR-06F", "status" to "candidate",
                "stream" to "architecture-recovery", "roadmapReference" to ".flow-agent/roadmap-architecture-recovery.yaml#AR-06")))
            addAll(exact("Evidence view authorization", section(s.certificationViewWorkPackage["authorization"]), stringMap(evidence["candidateAuthorization"])))
            addAll(exact("Evidence view validation", section(s.certificationViewWorkPackage["validation"]),
                stringMap(section(section(transition["work"])["after"])["validation"])))
            addAll(exact("Evidence view candidate", section(s.release["recoveryEvidenceViews"]), mapOf(
                "candidate" to "AR-06F", "status" to "candidate", "workPackage" to VIEW_WORK_PACKAGE,
                "report" to ".flow-agent/reports/certification-evidence-views.md", "baselineEvidence" to RUNTIME_BASELINE,
                "validation" to "current-revision-ci-required", "supportPromotion" to false)))
            // This is an implementation selection, not milestone completion or support promotion.
            // Reuse the existing gate; preserve every earlier acceptance and finding invariant.
            addAll(errors(runtimeViewPredecessor(s)))
            return@buildList
        }
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
        if (s.release.containsKey("recoveryEvidenceViews")) return predecessorSnapshot(runtimeViewPredecessor(s))
        val evidence = FlowYaml.readMap(requireNotNull(s.certificationBindingEvidence), EVIDENCE)
        val before = section(evidence["predecessorWorkFields"]).entries.associate { it.key.toString() to it.value }
        return s.copy(certificationWorkPackage = (s.certificationWorkPackage - "bindingAcceptanceEvidence" - "selectedWorkPackage") + before,
            release = s.release - "recoveryCertification", observationAuthenticationWorkPackage = emptyMap(), certificationBindingEvidence = null)
    }

    internal fun runtimeViewPredecessor(s: WorkflowSemanticsRecoveryLifecycleSnapshot): WorkflowSemanticsRecoveryLifecycleSnapshot {
        if (s.release.containsKey("recoveryConditionPreparation") || s.certificationWorkPackage["selectedSlice"] == "AR-06G")
            return runtimeViewPredecessor(conditionPredecessor(s))
        if (!s.release.containsKey("recoveryEvidenceViews") && s.certificationWorkPackage["selectedSlice"] != "AR-06F") return s
        val transition = section(FlowYaml.readMap(requireNotNull(s.certificationRuntimeBaseline), RUNTIME_BASELINE)["transition"])
        return s.copy(
            certificationWorkPackage = s.certificationWorkPackage + stringMap(section(transition["work"])["before"]),
            release = (s.release - "recoveryEvidenceViews") + ("recoveryCertification" to section(section(transition["releaseCertification"])["before"])),
            certificationViewWorkPackage = emptyMap(), certificationRuntimeBaseline = null)
    }

    internal fun conditionPredecessor(s: WorkflowSemanticsRecoveryLifecycleSnapshot): WorkflowSemanticsRecoveryLifecycleSnapshot {
        if (s.release.containsKey("recoveryErrorBoundaryPreparation") || s.certificationWorkPackage["selectedSlice"] == "AR-06H")
            return conditionPredecessor(errorBoundaryPredecessor(s))
        if (!s.release.containsKey("recoveryConditionPreparation") && s.certificationWorkPackage["selectedSlice"] != "AR-06G") return s
        val transition = section(FlowYaml.readMap(requireNotNull(s.certificationConditionBaseline), CONDITION_BASELINE)["transition"])
        return s.copy(
            certificationWorkPackage = s.certificationWorkPackage + stringMap(section(transition["work"])["before"]),
            release = (s.release - "recoveryConditionPreparation") + ("recoveryCertification" to section(section(transition["releaseCertification"])["before"])),
            certificationConditionWorkPackage = emptyMap(), certificationConditionBaseline = null)
    }

    internal fun errorBoundaryPredecessor(s: WorkflowSemanticsRecoveryLifecycleSnapshot): WorkflowSemanticsRecoveryLifecycleSnapshot {
        if (!s.release.containsKey("recoveryErrorBoundaryPreparation") && s.certificationWorkPackage["selectedSlice"] != "AR-06H") return s
        val transition = section(FlowYaml.readMap(requireNotNull(s.certificationErrorBoundaryBaseline), ERROR_BOUNDARY_BASELINE)["transition"])
        return s.copy(
            certificationWorkPackage = s.certificationWorkPackage + stringMap(section(transition["work"])["before"]),
            release = (s.release - "recoveryErrorBoundaryPreparation") + ("recoveryCertification" to section(section(transition["releaseCertification"])["before"])),
            certificationErrorBoundaryWorkPackage = emptyMap(), certificationErrorBoundaryBaseline = null)
    }

    private fun stringMap(value: Any?): Map<String, Any?> = section(value).entries.associate { it.key.toString() to it.value }

    private fun exact(name: String, actual: Map<*, *>, expected: Map<String, Any?>) =
        matchingFields(name, actual, expected) + if (actual.keys == expected.keys) emptyList() else listOf("$name has unsupported fields.")
}
