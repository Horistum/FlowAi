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
    const val LOCAL_RECOVERY_WORK_PACKAGE = ".flow-agent/work-packages/jenkins-local-recovery-certification.yaml"
    const val LOCAL_RECOVERY_BASELINE = ".flow-agent/evidence/jenkins-local-recovery-baseline.json"
    const val APPROVAL_WORK_PACKAGE = ".flow-agent/work-packages/jenkins-approval-certification.yaml"
    const val APPROVAL_BASELINE = ".flow-agent/evidence/jenkins-approval-baseline.json"
    const val MATRIX_WORK_PACKAGE = ".flow-agent/work-packages/adapter-behavior-matrix.yaml"
    const val MATRIX_BASELINE = ".flow-agent/evidence/adapter-behavior-matrix-baseline.json"
    const val PORTFOLIO_WORK_PACKAGE = ".flow-agent/work-packages/adapter-certification-portfolio.yaml"
    const val PORTFOLIO_BASELINE = ".flow-agent/evidence/adapter-certification-portfolio-baseline.json"
    const val GITHUB_CHECKOUT_WORK_PACKAGE = ".flow-agent/work-packages/github-actions-checkout-certification.yaml"
    const val GITHUB_CHECKOUT_BASELINE = ".flow-agent/evidence/github-actions-checkout-baseline.json"
    const val MULTI_PORTFOLIO_WORK_PACKAGE = ".flow-agent/work-packages/multi-adapter-certification-portfolio.yaml"
    const val MULTI_PORTFOLIO_BASELINE = ".flow-agent/evidence/multi-adapter-portfolio-baseline.json"
    const val SHARED_CHECKOUT_WORK_PACKAGE = ".flow-agent/work-packages/shared-canonical-checkout-certification.yaml"
    const val SHARED_CHECKOUT_BASELINE = ".flow-agent/evidence/shared-canonical-checkout-baseline.json"
    private const val SHARED_CHECKOUT_BASELINE_SHA256 = "631408a6045737a548f124e81049a139162ada5f809bf8d01c2d6a70c29d361e"
    private const val MULTI_PORTFOLIO_BASELINE_SHA256 = "cf913d74094d247578a05122a5c23c93cebfaa65a3148f40833d3c7f7d6020a3"
    private const val GITHUB_CHECKOUT_BASELINE_SHA256 = "6d85faca53c1196c13048a154370ba30481d775c6a79db6b2d1c1a674501154d"
    private const val PORTFOLIO_BASELINE_SHA256 = "88f7114db5cb1e52a81ddb02c645cfe77fcb74cbed13c151d41a6019b51a3f94"
    private const val MATRIX_BASELINE_SHA256 = "eecb0fa4dbcaf567d48d989103d3abf56db68ae9fd7502f1812c4c64946bd984"
    private const val APPROVAL_BASELINE_SHA256 = "b2dc9f7a63755c9960bc71f4a8b4a2f136ca7b918c06ad4969ff1d14c31348ba"
    private const val LOCAL_RECOVERY_BASELINE_SHA256 = "691b289deb07f766f0a247a015f5117e42f3731fb4b51e2de36aca3e500b2b8c"
    private const val ERROR_BOUNDARY_BASELINE_SHA256 = "cc117ba5c48c2a2abfc4b069eb709ecf0564b8f83e3790d344d4aaa109be8375"
    private const val CONDITION_BASELINE_SHA256 = "05261471566c273815b3e6d9d518f7bb5378ca0ad593060bff8c7825f825131c"
    private const val RUNTIME_BASELINE_SHA256 = "fdbfbe150d4df3fa90213527adf9aca58243c31215d21ca67d041bc475b66183"
    private const val SHA256 = "31ccc9a76188bae91787ba5241f32c02a3c0ee6799aba256a4c178738d75c5e2"
    private val validation = mapOf(
        "status" to "current-revision-ci-required",
        "requiredChecks" to listOf("compile-test-conformance", "merge-candidate-compile-test-conformance"),
        "negativeEvidence" to "Every signed field, untrusted and substituted keys, stale challenge, omitted/duplicate/unexpected runs, oversized statements, failed runtime, forged evidence bytes and mutated lifecycle receipts.")

    fun errors(s: WorkflowSemanticsRecoveryLifecycleSnapshot): List<String> = buildList {
        if (s.certificationWorkPackage["selectedSlice"] == "AR-06O" || s.release.containsKey("recoverySharedCheckout")) {
            val raw = s.certificationSharedCheckoutBaseline
            val sha = raw?.let { MessageDigest.getInstance("SHA-256").digest(it.toByteArray(Charsets.UTF_8))
                .joinToString("") { b -> "%02x".format(b.toInt() and 255) } }
            if (sha != SHARED_CHECKOUT_BASELINE_SHA256) {
                add("Shared canonical checkout certification requires the independently inspected merged-main baseline bytes.")
                return@buildList
            }
            val evidence = FlowYaml.readMap(requireNotNull(raw), SHARED_CHECKOUT_BASELINE)
            val transition = section(evidence["transition"])
            addAll(matchingFields("Shared canonical checkout certification selection", s.certificationWorkPackage, stringMap(section(transition["work"])["after"])))
            addAll(exact("Shared canonical checkout certification release selection", section(s.release["recoveryCertification"]),
                stringMap(section(transition["releaseCertification"])["after"])))
            addAll(matchingFields("Shared canonical checkout certification work", s.certificationSharedCheckoutWorkPackage, mapOf("version" to "AR-06O", "status" to "candidate",
                "stream" to "architecture-recovery", "roadmapReference" to ".flow-agent/roadmap-architecture-recovery.yaml#AR-06")))
            addAll(exact("Shared canonical checkout certification authorization", section(s.certificationSharedCheckoutWorkPackage["authorization"]), stringMap(evidence["candidateAuthorization"])))
            addAll(exact("Shared canonical checkout certification validation", section(s.certificationSharedCheckoutWorkPackage["validation"]),
                stringMap(section(section(transition["work"])["after"])["validation"])))
            addAll(exact("Shared canonical checkout certification candidate", section(s.release["recoverySharedCheckout"]), mapOf(
                "candidate" to "AR-06O", "status" to "candidate", "workPackage" to SHARED_CHECKOUT_WORK_PACKAGE,
                "report" to ".flow-agent/reports/shared-canonical-checkout-certification.md", "baselineEvidence" to SHARED_CHECKOUT_BASELINE,
                "validation" to "current-revision-ci-required", "supportPromotion" to false)))
            addAll(errors(sharedCheckoutPredecessor(s)))
            return@buildList
        }
        if (s.certificationWorkPackage["selectedSlice"] == "AR-06N" || s.release.containsKey("recoveryMultiAdapterPortfolio")) {
            val raw = s.certificationMultiPortfolioBaseline
            val sha = raw?.let { MessageDigest.getInstance("SHA-256").digest(it.toByteArray(Charsets.UTF_8))
                .joinToString("") { b -> "%02x".format(b.toInt() and 255) } }
            if (sha != MULTI_PORTFOLIO_BASELINE_SHA256) {
                add("Multi-adapter portfolio certification requires the independently inspected merged-main baseline bytes.")
                return@buildList
            }
            val evidence = FlowYaml.readMap(requireNotNull(raw), MULTI_PORTFOLIO_BASELINE)
            val transition = section(evidence["transition"])
            addAll(matchingFields("Multi-adapter portfolio certification selection", s.certificationWorkPackage, stringMap(section(transition["work"])["after"])))
            addAll(exact("Multi-adapter portfolio certification release selection", section(s.release["recoveryCertification"]),
                stringMap(section(transition["releaseCertification"])["after"])))
            addAll(matchingFields("Multi-adapter portfolio certification work", s.certificationMultiPortfolioWorkPackage, mapOf("version" to "AR-06N", "status" to "candidate",
                "stream" to "architecture-recovery", "roadmapReference" to ".flow-agent/roadmap-architecture-recovery.yaml#AR-06")))
            addAll(exact("Multi-adapter portfolio certification authorization", section(s.certificationMultiPortfolioWorkPackage["authorization"]), stringMap(evidence["candidateAuthorization"])))
            addAll(exact("Multi-adapter portfolio certification validation", section(s.certificationMultiPortfolioWorkPackage["validation"]),
                stringMap(section(section(transition["work"])["after"])["validation"])))
            addAll(exact("Multi-adapter portfolio certification candidate", section(s.release["recoveryMultiAdapterPortfolio"]), mapOf(
                "candidate" to "AR-06N", "status" to "candidate", "workPackage" to MULTI_PORTFOLIO_WORK_PACKAGE,
                "report" to ".flow-agent/reports/multi-adapter-certification-portfolio.md", "baselineEvidence" to MULTI_PORTFOLIO_BASELINE,
                "validation" to "current-revision-ci-required", "supportPromotion" to false)))
            addAll(errors(multiPortfolioPredecessor(s)))
            return@buildList
        }
        if (s.certificationWorkPackage["selectedSlice"] == "AR-06M" || s.release.containsKey("recoveryGitHubCheckout")) {
            val raw = s.certificationGitHubCheckoutBaseline
            val sha = raw?.let { MessageDigest.getInstance("SHA-256").digest(it.toByteArray(Charsets.UTF_8))
                .joinToString("") { b -> "%02x".format(b.toInt() and 255) } }
            if (sha != GITHUB_CHECKOUT_BASELINE_SHA256) {
                add("GitHub checkout certification requires the independently inspected merged-main baseline bytes.")
                return@buildList
            }
            val evidence = FlowYaml.readMap(requireNotNull(raw), GITHUB_CHECKOUT_BASELINE)
            val transition = section(evidence["transition"])
            addAll(matchingFields("GitHub checkout certification selection", s.certificationWorkPackage, stringMap(section(transition["work"])["after"])))
            addAll(exact("GitHub checkout certification release selection", section(s.release["recoveryCertification"]),
                stringMap(section(transition["releaseCertification"])["after"])))
            addAll(matchingFields("GitHub checkout certification work", s.certificationGitHubCheckoutWorkPackage, mapOf("version" to "AR-06M", "status" to "candidate",
                "stream" to "architecture-recovery", "roadmapReference" to ".flow-agent/roadmap-architecture-recovery.yaml#AR-06")))
            addAll(exact("GitHub checkout certification authorization", section(s.certificationGitHubCheckoutWorkPackage["authorization"]), stringMap(evidence["candidateAuthorization"])))
            addAll(exact("GitHub checkout certification validation", section(s.certificationGitHubCheckoutWorkPackage["validation"]),
                stringMap(section(section(transition["work"])["after"])["validation"])))
            addAll(exact("GitHub checkout certification candidate", section(s.release["recoveryGitHubCheckout"]), mapOf(
                "candidate" to "AR-06M", "status" to "candidate", "workPackage" to GITHUB_CHECKOUT_WORK_PACKAGE,
                "report" to ".flow-agent/reports/github-actions-checkout-certification.md", "baselineEvidence" to GITHUB_CHECKOUT_BASELINE,
                "validation" to "current-revision-ci-required", "supportPromotion" to false)))
            addAll(errors(githubCheckoutPredecessor(s)))
            return@buildList
        }
        if (s.certificationWorkPackage["selectedSlice"] == "AR-06L" || s.release.containsKey("recoveryCertificationPortfolio")) {
            val raw = s.certificationPortfolioBaseline
            val sha = raw?.let { MessageDigest.getInstance("SHA-256").digest(it.toByteArray(Charsets.UTF_8))
                .joinToString("") { b -> "%02x".format(b.toInt() and 255) } }
            if (sha != PORTFOLIO_BASELINE_SHA256) {
                add("Certification portfolio requires the independently inspected merged-main baseline bytes.")
                return@buildList
            }
            val evidence = FlowYaml.readMap(requireNotNull(raw), PORTFOLIO_BASELINE)
            val transition = section(evidence["transition"])
            addAll(matchingFields("Certification portfolio selection", s.certificationWorkPackage, stringMap(section(transition["work"])["after"])))
            addAll(exact("Certification portfolio release selection", section(s.release["recoveryCertification"]),
                stringMap(section(transition["releaseCertification"])["after"])))
            addAll(matchingFields("Certification portfolio work", s.certificationPortfolioWorkPackage, mapOf("version" to "AR-06L", "status" to "candidate",
                "stream" to "architecture-recovery", "roadmapReference" to ".flow-agent/roadmap-architecture-recovery.yaml#AR-06")))
            addAll(exact("Certification portfolio authorization", section(s.certificationPortfolioWorkPackage["authorization"]), stringMap(evidence["candidateAuthorization"])))
            addAll(exact("Certification portfolio validation", section(s.certificationPortfolioWorkPackage["validation"]),
                stringMap(section(section(transition["work"])["after"])["validation"])))
            addAll(exact("Certification portfolio candidate", section(s.release["recoveryCertificationPortfolio"]), mapOf(
                "candidate" to "AR-06L", "status" to "candidate", "workPackage" to PORTFOLIO_WORK_PACKAGE,
                "report" to ".flow-agent/reports/adapter-certification-portfolio.md", "baselineEvidence" to PORTFOLIO_BASELINE,
                "validation" to "current-revision-ci-required", "supportPromotion" to false)))
            addAll(errors(portfolioPredecessor(s)))
            return@buildList
        }
        if (s.certificationWorkPackage["selectedSlice"] == "AR-06K" || s.release.containsKey("recoveryBehaviorMatrix")) {
            val raw = s.certificationMatrixBaseline
            val sha = raw?.let { MessageDigest.getInstance("SHA-256").digest(it.toByteArray(Charsets.UTF_8))
                .joinToString("") { b -> "%02x".format(b.toInt() and 255) } }
            if (sha != MATRIX_BASELINE_SHA256) {
                add("Behavior matrix certification requires the independently inspected merged-main baseline bytes.")
                return@buildList
            }
            val evidence = FlowYaml.readMap(requireNotNull(raw), MATRIX_BASELINE)
            val transition = section(evidence["transition"])
            addAll(matchingFields("Behavior matrix selection", s.certificationWorkPackage, stringMap(section(transition["work"])["after"])))
            addAll(exact("Behavior matrix release selection", section(s.release["recoveryCertification"]),
                stringMap(section(transition["releaseCertification"])["after"])))
            addAll(matchingFields("Behavior matrix work", s.certificationMatrixWorkPackage, mapOf("version" to "AR-06K", "status" to "candidate",
                "stream" to "architecture-recovery", "roadmapReference" to ".flow-agent/roadmap-architecture-recovery.yaml#AR-06")))
            addAll(exact("Behavior matrix authorization", section(s.certificationMatrixWorkPackage["authorization"]), stringMap(evidence["candidateAuthorization"])))
            addAll(exact("Behavior matrix validation", section(s.certificationMatrixWorkPackage["validation"]),
                stringMap(section(section(transition["work"])["after"])["validation"])))
            addAll(exact("Behavior matrix candidate", section(s.release["recoveryBehaviorMatrix"]), mapOf(
                "candidate" to "AR-06K", "status" to "candidate", "workPackage" to MATRIX_WORK_PACKAGE,
                "report" to ".flow-agent/reports/adapter-behavior-matrix.md", "baselineEvidence" to MATRIX_BASELINE,
                "validation" to "current-revision-ci-required", "supportPromotion" to false)))
            addAll(errors(matrixPredecessor(s)))
            return@buildList
        }
        if (s.certificationWorkPackage["selectedSlice"] == "AR-06J" || s.release.containsKey("recoveryApprovalPreparation")) {
            val raw = s.certificationApprovalBaseline
            val sha = raw?.let { MessageDigest.getInstance("SHA-256").digest(it.toByteArray(Charsets.UTF_8))
                .joinToString("") { b -> "%02x".format(b.toInt() and 255) } }
            if (sha != APPROVAL_BASELINE_SHA256) {
                add("Manual approval certification requires the independently inspected merged-main baseline bytes.")
                return@buildList
            }
            val evidence = FlowYaml.readMap(requireNotNull(raw), APPROVAL_BASELINE)
            val transition = section(evidence["transition"])
            addAll(matchingFields("Manual approval selection", s.certificationWorkPackage, stringMap(section(transition["work"])["after"])))
            addAll(exact("Manual approval release selection", section(s.release["recoveryCertification"]),
                stringMap(section(transition["releaseCertification"])["after"])))
            addAll(matchingFields("Manual approval work", s.certificationApprovalWorkPackage, mapOf("version" to "AR-06J", "status" to "candidate",
                "stream" to "architecture-recovery", "roadmapReference" to ".flow-agent/roadmap-architecture-recovery.yaml#AR-06")))
            addAll(exact("Manual approval authorization", section(s.certificationApprovalWorkPackage["authorization"]), stringMap(evidence["candidateAuthorization"])))
            addAll(exact("Manual approval validation", section(s.certificationApprovalWorkPackage["validation"]),
                stringMap(section(section(transition["work"])["after"])["validation"])))
            addAll(exact("Manual approval candidate", section(s.release["recoveryApprovalPreparation"]), mapOf(
                "candidate" to "AR-06J", "status" to "candidate", "workPackage" to APPROVAL_WORK_PACKAGE,
                "report" to ".flow-agent/reports/jenkins-approval-certification.md", "baselineEvidence" to APPROVAL_BASELINE,
                "validation" to "current-revision-ci-required", "supportPromotion" to false)))
            addAll(errors(approvalPredecessor(s)))
            return@buildList
        }
        if (s.certificationWorkPackage["selectedSlice"] == "AR-06I" || s.release.containsKey("recoveryLocalRecoveryPreparation")) {
            val raw = s.certificationLocalRecoveryBaseline
            val sha = raw?.let { MessageDigest.getInstance("SHA-256").digest(it.toByteArray(Charsets.UTF_8))
                .joinToString("") { b -> "%02x".format(b.toInt() and 255) } }
            if (sha != LOCAL_RECOVERY_BASELINE_SHA256) {
                add("Local recovery certification requires the independently inspected merged-main baseline bytes.")
                return@buildList
            }
            val evidence = FlowYaml.readMap(requireNotNull(raw), LOCAL_RECOVERY_BASELINE)
            val transition = section(evidence["transition"])
            addAll(matchingFields("Local recovery selection", s.certificationWorkPackage, stringMap(section(transition["work"])["after"])))
            addAll(exact("Local recovery release selection", section(s.release["recoveryCertification"]),
                stringMap(section(transition["releaseCertification"])["after"])))
            addAll(matchingFields("Local recovery work", s.certificationLocalRecoveryWorkPackage, mapOf("version" to "AR-06I", "status" to "candidate",
                "stream" to "architecture-recovery", "roadmapReference" to ".flow-agent/roadmap-architecture-recovery.yaml#AR-06")))
            addAll(exact("Local recovery authorization", section(s.certificationLocalRecoveryWorkPackage["authorization"]), stringMap(evidence["candidateAuthorization"])))
            addAll(exact("Local recovery validation", section(s.certificationLocalRecoveryWorkPackage["validation"]),
                stringMap(section(section(transition["work"])["after"])["validation"])))
            addAll(exact("Local recovery candidate", section(s.release["recoveryLocalRecoveryPreparation"]), mapOf(
                "candidate" to "AR-06I", "status" to "candidate", "workPackage" to LOCAL_RECOVERY_WORK_PACKAGE,
                "report" to ".flow-agent/reports/jenkins-local-recovery-certification.md", "baselineEvidence" to LOCAL_RECOVERY_BASELINE,
                "validation" to "current-revision-ci-required", "supportPromotion" to false)))
            addAll(errors(localRecoveryPredecessor(s)))
            return@buildList
        }
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
        if (s.release.containsKey("recoveryLocalRecoveryPreparation") || s.certificationWorkPackage["selectedSlice"] == "AR-06I")
            return errorBoundaryPredecessor(localRecoveryPredecessor(s))
        if (!s.release.containsKey("recoveryErrorBoundaryPreparation") && s.certificationWorkPackage["selectedSlice"] != "AR-06H") return s
        val transition = section(FlowYaml.readMap(requireNotNull(s.certificationErrorBoundaryBaseline), ERROR_BOUNDARY_BASELINE)["transition"])
        return s.copy(
            certificationWorkPackage = s.certificationWorkPackage + stringMap(section(transition["work"])["before"]),
            release = (s.release - "recoveryErrorBoundaryPreparation") + ("recoveryCertification" to section(section(transition["releaseCertification"])["before"])),
            certificationErrorBoundaryWorkPackage = emptyMap(), certificationErrorBoundaryBaseline = null)
    }

    internal fun localRecoveryPredecessor(s: WorkflowSemanticsRecoveryLifecycleSnapshot): WorkflowSemanticsRecoveryLifecycleSnapshot {
        if (s.release.containsKey("recoveryApprovalPreparation") || s.certificationWorkPackage["selectedSlice"] == "AR-06J")
            return localRecoveryPredecessor(approvalPredecessor(s))
        if (!s.release.containsKey("recoveryLocalRecoveryPreparation") && s.certificationWorkPackage["selectedSlice"] != "AR-06I") return s
        val transition = section(FlowYaml.readMap(requireNotNull(s.certificationLocalRecoveryBaseline), LOCAL_RECOVERY_BASELINE)["transition"])
        return s.copy(
            certificationWorkPackage = s.certificationWorkPackage + stringMap(section(transition["work"])["before"]),
            release = (s.release - "recoveryLocalRecoveryPreparation") + ("recoveryCertification" to section(section(transition["releaseCertification"])["before"])),
            certificationLocalRecoveryWorkPackage = emptyMap(), certificationLocalRecoveryBaseline = null)
    }

    internal fun approvalPredecessor(s: WorkflowSemanticsRecoveryLifecycleSnapshot): WorkflowSemanticsRecoveryLifecycleSnapshot {
        if (s.release.containsKey("recoveryBehaviorMatrix") || s.certificationWorkPackage["selectedSlice"] == "AR-06K")
            return approvalPredecessor(matrixPredecessor(s))
        if (!s.release.containsKey("recoveryApprovalPreparation") && s.certificationWorkPackage["selectedSlice"] != "AR-06J") return s
        val transition = section(FlowYaml.readMap(requireNotNull(s.certificationApprovalBaseline), APPROVAL_BASELINE)["transition"])
        return s.copy(
            certificationWorkPackage = s.certificationWorkPackage + stringMap(section(transition["work"])["before"]),
            release = (s.release - "recoveryApprovalPreparation") + ("recoveryCertification" to section(section(transition["releaseCertification"])["before"])),
            certificationApprovalWorkPackage = emptyMap(), certificationApprovalBaseline = null)
    }

    internal fun matrixPredecessor(s: WorkflowSemanticsRecoveryLifecycleSnapshot): WorkflowSemanticsRecoveryLifecycleSnapshot {
        if (s.release.containsKey("recoveryCertificationPortfolio") || s.certificationWorkPackage["selectedSlice"] == "AR-06L")
            return matrixPredecessor(portfolioPredecessor(s))
        if (!s.release.containsKey("recoveryBehaviorMatrix") && s.certificationWorkPackage["selectedSlice"] != "AR-06K") return s
        val transition = section(FlowYaml.readMap(requireNotNull(s.certificationMatrixBaseline), MATRIX_BASELINE)["transition"])
        return s.copy(
            certificationWorkPackage = s.certificationWorkPackage + stringMap(section(transition["work"])["before"]),
            release = (s.release - "recoveryBehaviorMatrix") + ("recoveryCertification" to section(section(transition["releaseCertification"])["before"])),
            certificationMatrixWorkPackage = emptyMap(), certificationMatrixBaseline = null)
    }

    internal fun portfolioPredecessor(s: WorkflowSemanticsRecoveryLifecycleSnapshot): WorkflowSemanticsRecoveryLifecycleSnapshot {
        if (s.release.containsKey("recoveryGitHubCheckout") || s.certificationWorkPackage["selectedSlice"] == "AR-06M")
            return portfolioPredecessor(githubCheckoutPredecessor(s))
        if (!s.release.containsKey("recoveryCertificationPortfolio") && s.certificationWorkPackage["selectedSlice"] != "AR-06L") return s
        val transition = section(FlowYaml.readMap(requireNotNull(s.certificationPortfolioBaseline), PORTFOLIO_BASELINE)["transition"])
        return s.copy(
            certificationWorkPackage = s.certificationWorkPackage + stringMap(section(transition["work"])["before"]),
            release = (s.release - "recoveryCertificationPortfolio") + ("recoveryCertification" to section(section(transition["releaseCertification"])["before"])),
            certificationPortfolioWorkPackage = emptyMap(), certificationPortfolioBaseline = null)
    }

    internal fun githubCheckoutPredecessor(s: WorkflowSemanticsRecoveryLifecycleSnapshot): WorkflowSemanticsRecoveryLifecycleSnapshot {
        if (s.release.containsKey("recoveryMultiAdapterPortfolio") || s.certificationWorkPackage["selectedSlice"] == "AR-06N")
            return githubCheckoutPredecessor(multiPortfolioPredecessor(s))
        if (!s.release.containsKey("recoveryGitHubCheckout") && s.certificationWorkPackage["selectedSlice"] != "AR-06M") return s
        val transition = section(FlowYaml.readMap(requireNotNull(s.certificationGitHubCheckoutBaseline), GITHUB_CHECKOUT_BASELINE)["transition"])
        return s.copy(
            certificationWorkPackage = s.certificationWorkPackage + stringMap(section(transition["work"])["before"]),
            release = (s.release - "recoveryGitHubCheckout") + ("recoveryCertification" to section(section(transition["releaseCertification"])["before"])),
            certificationGitHubCheckoutWorkPackage = emptyMap(), certificationGitHubCheckoutBaseline = null)
    }

    internal fun multiPortfolioPredecessor(s: WorkflowSemanticsRecoveryLifecycleSnapshot): WorkflowSemanticsRecoveryLifecycleSnapshot {
        if (s.release.containsKey("recoverySharedCheckout") || s.certificationWorkPackage["selectedSlice"] == "AR-06O")
            return multiPortfolioPredecessor(sharedCheckoutPredecessor(s))
        if (!s.release.containsKey("recoveryMultiAdapterPortfolio") && s.certificationWorkPackage["selectedSlice"] != "AR-06N") return s
        val transition = section(FlowYaml.readMap(requireNotNull(s.certificationMultiPortfolioBaseline), MULTI_PORTFOLIO_BASELINE)["transition"])
        return s.copy(
            certificationWorkPackage = s.certificationWorkPackage + stringMap(section(transition["work"])["before"]),
            release = (s.release - "recoveryMultiAdapterPortfolio") + ("recoveryCertification" to section(section(transition["releaseCertification"])["before"])),
            certificationMultiPortfolioWorkPackage = emptyMap(), certificationMultiPortfolioBaseline = null)
    }

    internal fun sharedCheckoutPredecessor(s: WorkflowSemanticsRecoveryLifecycleSnapshot): WorkflowSemanticsRecoveryLifecycleSnapshot {
        if (!s.release.containsKey("recoverySharedCheckout") && s.certificationWorkPackage["selectedSlice"] != "AR-06O") return s
        val transition = section(FlowYaml.readMap(requireNotNull(s.certificationSharedCheckoutBaseline), SHARED_CHECKOUT_BASELINE)["transition"])
        return s.copy(
            certificationWorkPackage = s.certificationWorkPackage + stringMap(section(transition["work"])["before"]),
            release = (s.release - "recoverySharedCheckout") + ("recoveryCertification" to section(section(transition["releaseCertification"])["before"])),
            certificationSharedCheckoutWorkPackage = emptyMap(), certificationSharedCheckoutBaseline = null)
    }

    private fun stringMap(value: Any?): Map<String, Any?> = section(value).entries.associate { it.key.toString() to it.value }

    private fun exact(name: String, actual: Map<*, *>, expected: Map<String, Any?>) =
        matchingFields(name, actual, expected) + if (actual.keys == expected.keys) emptyList() else listOf("$name has unsupported fields.")
}
