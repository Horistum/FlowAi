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
    const val RETRY_WORK_PACKAGE = ".flow-agent/work-packages/jenkins-retry-certification.yaml"
    const val RETRY_BASELINE = ".flow-agent/evidence/jenkins-retry-baseline.json"
    const val LOOP_WORK_PACKAGE = ".flow-agent/work-packages/jenkins-loop-certification.yaml"
    const val LOOP_BASELINE = ".flow-agent/evidence/jenkins-loop-baseline.json"
    const val MATCH_PROJECTION_WORK_PACKAGE = ".flow-agent/work-packages/match-branch-preservation.yaml"
    const val MATCH_PROJECTION_BASELINE = ".flow-agent/evidence/match-branch-preservation-baseline.json"
    const val RETRY_BOUNDARY_WORK_PACKAGE = ".flow-agent/work-packages/retry-boundary-preservation.yaml"
    const val RETRY_BOUNDARY_BASELINE = ".flow-agent/evidence/retry-boundary-preservation-baseline.json"
    private const val RETRY_BOUNDARY_BASELINE_SHA256 = "e8e630a3b126b655eb40e034700bc69c856348691a6bc7bd8d196493725aa0c6"
    const val WORKSPACE_CANCELLATION_WORK_PACKAGE = ".flow-agent/work-packages/jenkins-workspace-cancellation.yaml"
    const val WORKSPACE_CANCELLATION_BASELINE = ".flow-agent/evidence/jenkins-workspace-cancellation-baseline.json"
    private const val WORKSPACE_CANCELLATION_BASELINE_SHA256 = "56ce891c501fa8a8042025600c959bd070b2b645e8e93f13b0865b4fd315868d"
    const val WORKSPACES_WORK_PACKAGE = ".flow-agent/work-packages/jenkins-parallel-workspaces.yaml"
    const val WORKSPACES_BASELINE = ".flow-agent/evidence/jenkins-parallel-workspaces-baseline.json"
    private const val WORKSPACES_BASELINE_SHA256 = "7404f3d8e2b3332e9735c450aaa2afbe8cc5dd0ec1aeaad124dc1703b56e7f98"
    const val CANCELLATION_WORK_PACKAGE = ".flow-agent/work-packages/jenkins-parallel-cancellation.yaml"
    const val CANCELLATION_BASELINE = ".flow-agent/evidence/jenkins-parallel-cancellation-baseline.json"
    private const val CANCELLATION_BASELINE_SHA256 = "251a415664eba56902cbc86fa542348a041dba8d0a02db9641304c0ea62e16d8"
    const val PARALLEL_WORK_PACKAGE = ".flow-agent/work-packages/jenkins-parallel-certification.yaml"
    const val PARALLEL_BASELINE = ".flow-agent/evidence/jenkins-parallel-baseline.json"
    private const val PARALLEL_BASELINE_SHA256 = "14051d8677676d1e8a3cb3a1ea73a6934313abaad173573b6ecdb67a652304b3"
    const val PARALLEL_PROJECTION_WORK_PACKAGE = ".flow-agent/work-packages/parallel-branch-preservation.yaml"
    const val PARALLEL_PROJECTION_BASELINE = ".flow-agent/evidence/parallel-branch-preservation-baseline.json"
    private const val PARALLEL_PROJECTION_BASELINE_SHA256 = "82ec00a471e166c4d29076c7925faa9797aefc582c5e169650b8e4b37a524571"
    const val MATCH_WORK_PACKAGE = ".flow-agent/work-packages/jenkins-match-certification.yaml"
    const val MATCH_BASELINE = ".flow-agent/evidence/jenkins-match-baseline.json"
    private const val MATCH_BASELINE_SHA256 = "b645db90beb45d8f05f131f09c616cfe88a7e995d5973567d22537af9b3ee3f8"
    private const val MATCH_PROJECTION_BASELINE_SHA256 = "23c4b92c66ff670102d3df83886009f37fd7372e5db2f2d9919565eb727b6304"
    private const val LOOP_BASELINE_SHA256 = "c57621fe0cc44fba575474b16727e695eb924945d20102846c9c1a1eb97085c2"
    private const val RETRY_BASELINE_SHA256 = "f1044b17c0196172a6f437c89ef521b9e79f94ccedd8cfaed282c2a0f9d6a2cb"
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
        if (s.certificationWorkPackage["selectedSlice"] == "AR-06Y" || s.release.containsKey("recoveryRetryBoundary")) {
            val raw = s.certificationRetryBoundaryBaseline
            val sha = raw?.let { MessageDigest.getInstance("SHA-256").digest(it.toByteArray(Charsets.UTF_8))
                .joinToString("") { b -> "%02x".format(b.toInt() and 255) } }
            if (sha != RETRY_BOUNDARY_BASELINE_SHA256) {
                add("Retry boundary preservation requires the independently inspected merged-main baseline bytes.")
                return@buildList
            }
            val evidence = FlowYaml.readMap(requireNotNull(raw), RETRY_BOUNDARY_BASELINE)
            val transition = section(evidence["transition"])
            addAll(matchingFields("Retry boundary preservation selection", s.certificationWorkPackage, stringMap(section(transition["work"])["after"])))
            addAll(exact("Retry boundary preservation release selection", section(s.release["recoveryCertification"]),
                stringMap(section(transition["releaseCertification"])["after"])))
            addAll(matchingFields("Retry boundary preservation work", s.certificationRetryBoundaryWorkPackage, mapOf("version" to "AR-06Y", "status" to "candidate",
                "stream" to "architecture-recovery", "roadmapReference" to ".flow-agent/roadmap-architecture-recovery.yaml#AR-06")))
            addAll(exact("Retry boundary preservation authorization", section(s.certificationRetryBoundaryWorkPackage["authorization"]), stringMap(evidence["candidateAuthorization"])))
            addAll(exact("Retry boundary preservation validation", section(s.certificationRetryBoundaryWorkPackage["validation"]),
                stringMap(section(section(transition["work"])["after"])["validation"])))
            addAll(exact("Retry boundary preservation candidate", section(s.release["recoveryRetryBoundary"]), mapOf(
                "candidate" to "AR-06Y", "status" to "candidate", "workPackage" to RETRY_BOUNDARY_WORK_PACKAGE,
                "report" to ".flow-agent/reports/retry-boundary-preservation.md", "baselineEvidence" to RETRY_BOUNDARY_BASELINE,
                "validation" to "current-revision-ci-required", "supportPromotion" to false)))
            addAll(errors(retryBoundaryPredecessor(s)))
            return@buildList
        }
        if (s.certificationWorkPackage["selectedSlice"] == "AR-06X" || s.release.containsKey("recoveryWorkspaceCancellation")) {
            val raw = s.certificationWorkspaceCancellationBaseline
            val sha = raw?.let { MessageDigest.getInstance("SHA-256").digest(it.toByteArray(Charsets.UTF_8))
                .joinToString("") { b -> "%02x".format(b.toInt() and 255) } }
            if (sha != WORKSPACE_CANCELLATION_BASELINE_SHA256) {
                add("Jenkins workspace cancellation certification requires the independently inspected merged-main baseline bytes.")
                return@buildList
            }
            val evidence = FlowYaml.readMap(requireNotNull(raw), WORKSPACE_CANCELLATION_BASELINE)
            val transition = section(evidence["transition"])
            addAll(matchingFields("Jenkins workspace cancellation certification selection", s.certificationWorkPackage, stringMap(section(transition["work"])["after"])))
            addAll(exact("Jenkins workspace cancellation certification release selection", section(s.release["recoveryCertification"]),
                stringMap(section(transition["releaseCertification"])["after"])))
            addAll(matchingFields("Jenkins workspace cancellation certification work", s.certificationWorkspaceCancellationWorkPackage, mapOf("version" to "AR-06X", "status" to "candidate",
                "stream" to "architecture-recovery", "roadmapReference" to ".flow-agent/roadmap-architecture-recovery.yaml#AR-06")))
            addAll(exact("Jenkins workspace cancellation certification authorization", section(s.certificationWorkspaceCancellationWorkPackage["authorization"]), stringMap(evidence["candidateAuthorization"])))
            addAll(exact("Jenkins workspace cancellation certification validation", section(s.certificationWorkspaceCancellationWorkPackage["validation"]),
                stringMap(section(section(transition["work"])["after"])["validation"])))
            addAll(exact("Jenkins workspace cancellation certification candidate", section(s.release["recoveryWorkspaceCancellation"]), mapOf(
                "candidate" to "AR-06X", "status" to "candidate", "workPackage" to WORKSPACE_CANCELLATION_WORK_PACKAGE,
                "report" to ".flow-agent/reports/jenkins-workspace-cancellation.md", "baselineEvidence" to WORKSPACE_CANCELLATION_BASELINE,
                "validation" to "current-revision-ci-required", "supportPromotion" to false)))
            addAll(errors(workspaceCancellationPredecessor(s)))
            return@buildList
        }
        if (s.certificationWorkPackage["selectedSlice"] == "AR-06W" || s.release.containsKey("recoveryParallelWorkspaces")) {
            val raw = s.certificationWorkspacesBaseline
            val sha = raw?.let { MessageDigest.getInstance("SHA-256").digest(it.toByteArray(Charsets.UTF_8))
                .joinToString("") { b -> "%02x".format(b.toInt() and 255) } }
            if (sha != WORKSPACES_BASELINE_SHA256) {
                add("Jenkins parallel workspace certification requires the independently inspected merged-main baseline bytes.")
                return@buildList
            }
            val evidence = FlowYaml.readMap(requireNotNull(raw), WORKSPACES_BASELINE)
            val transition = section(evidence["transition"])
            addAll(matchingFields("Jenkins parallel workspace certification selection", s.certificationWorkPackage, stringMap(section(transition["work"])["after"])))
            addAll(exact("Jenkins parallel workspace certification release selection", section(s.release["recoveryCertification"]),
                stringMap(section(transition["releaseCertification"])["after"])))
            addAll(matchingFields("Jenkins parallel workspace certification work", s.certificationWorkspacesWorkPackage, mapOf("version" to "AR-06W", "status" to "candidate",
                "stream" to "architecture-recovery", "roadmapReference" to ".flow-agent/roadmap-architecture-recovery.yaml#AR-06")))
            addAll(exact("Jenkins parallel workspace certification authorization", section(s.certificationWorkspacesWorkPackage["authorization"]), stringMap(evidence["candidateAuthorization"])))
            addAll(exact("Jenkins parallel workspace certification validation", section(s.certificationWorkspacesWorkPackage["validation"]),
                stringMap(section(section(transition["work"])["after"])["validation"])))
            addAll(exact("Jenkins parallel workspace certification candidate", section(s.release["recoveryParallelWorkspaces"]), mapOf(
                "candidate" to "AR-06W", "status" to "candidate", "workPackage" to WORKSPACES_WORK_PACKAGE,
                "report" to ".flow-agent/reports/jenkins-parallel-workspaces.md", "baselineEvidence" to WORKSPACES_BASELINE,
                "validation" to "current-revision-ci-required", "supportPromotion" to false)))
            addAll(errors(workspacesPredecessor(s)))
            return@buildList
        }
        if (s.certificationWorkPackage["selectedSlice"] == "AR-06V" || s.release.containsKey("recoveryParallelCancellation")) {
            val raw = s.certificationCancellationBaseline
            val sha = raw?.let { MessageDigest.getInstance("SHA-256").digest(it.toByteArray(Charsets.UTF_8))
                .joinToString("") { b -> "%02x".format(b.toInt() and 255) } }
            if (sha != CANCELLATION_BASELINE_SHA256) {
                add("Jenkins parallel cancellation certification requires the independently inspected merged-main baseline bytes.")
                return@buildList
            }
            val evidence = FlowYaml.readMap(requireNotNull(raw), CANCELLATION_BASELINE)
            val transition = section(evidence["transition"])
            addAll(matchingFields("Jenkins parallel cancellation certification selection", s.certificationWorkPackage, stringMap(section(transition["work"])["after"])))
            addAll(exact("Jenkins parallel cancellation certification release selection", section(s.release["recoveryCertification"]),
                stringMap(section(transition["releaseCertification"])["after"])))
            addAll(matchingFields("Jenkins parallel cancellation certification work", s.certificationCancellationWorkPackage, mapOf("version" to "AR-06V", "status" to "candidate",
                "stream" to "architecture-recovery", "roadmapReference" to ".flow-agent/roadmap-architecture-recovery.yaml#AR-06")))
            addAll(exact("Jenkins parallel cancellation certification authorization", section(s.certificationCancellationWorkPackage["authorization"]), stringMap(evidence["candidateAuthorization"])))
            addAll(exact("Jenkins parallel cancellation certification validation", section(s.certificationCancellationWorkPackage["validation"]),
                stringMap(section(section(transition["work"])["after"])["validation"])))
            addAll(exact("Jenkins parallel cancellation certification candidate", section(s.release["recoveryParallelCancellation"]), mapOf(
                "candidate" to "AR-06V", "status" to "candidate", "workPackage" to CANCELLATION_WORK_PACKAGE,
                "report" to ".flow-agent/reports/jenkins-parallel-cancellation.md", "baselineEvidence" to CANCELLATION_BASELINE,
                "validation" to "current-revision-ci-required", "supportPromotion" to false)))
            addAll(errors(cancellationPredecessor(s)))
            return@buildList
        }
        if (s.certificationWorkPackage["selectedSlice"] == "AR-06U" || s.release.containsKey("recoveryParallelCertification")) {
            val raw = s.certificationParallelBaseline
            val sha = raw?.let { MessageDigest.getInstance("SHA-256").digest(it.toByteArray(Charsets.UTF_8))
                .joinToString("") { b -> "%02x".format(b.toInt() and 255) } }
            if (sha != PARALLEL_BASELINE_SHA256) {
                add("Jenkins parallel approval certification requires the independently inspected merged-main baseline bytes.")
                return@buildList
            }
            val evidence = FlowYaml.readMap(requireNotNull(raw), PARALLEL_BASELINE)
            val transition = section(evidence["transition"])
            addAll(matchingFields("Jenkins parallel approval certification selection", s.certificationWorkPackage, stringMap(section(transition["work"])["after"])))
            addAll(exact("Jenkins parallel approval certification release selection", section(s.release["recoveryCertification"]),
                stringMap(section(transition["releaseCertification"])["after"])))
            addAll(matchingFields("Jenkins parallel approval certification work", s.certificationParallelWorkPackage, mapOf("version" to "AR-06U", "status" to "candidate",
                "stream" to "architecture-recovery", "roadmapReference" to ".flow-agent/roadmap-architecture-recovery.yaml#AR-06")))
            addAll(exact("Jenkins parallel approval certification authorization", section(s.certificationParallelWorkPackage["authorization"]), stringMap(evidence["candidateAuthorization"])))
            addAll(exact("Jenkins parallel approval certification validation", section(s.certificationParallelWorkPackage["validation"]),
                stringMap(section(section(transition["work"])["after"])["validation"])))
            addAll(exact("Jenkins parallel approval certification candidate", section(s.release["recoveryParallelCertification"]), mapOf(
                "candidate" to "AR-06U", "status" to "candidate", "workPackage" to PARALLEL_WORK_PACKAGE,
                "report" to ".flow-agent/reports/jenkins-parallel-certification.md", "baselineEvidence" to PARALLEL_BASELINE,
                "validation" to "current-revision-ci-required", "supportPromotion" to false)))
            addAll(errors(parallelPredecessor(s)))
            return@buildList
        }
        if (s.certificationWorkPackage["selectedSlice"] == "AR-06T" || s.release.containsKey("recoveryParallelProjection")) {
            val raw = s.certificationParallelProjectionBaseline
            val sha = raw?.let { MessageDigest.getInstance("SHA-256").digest(it.toByteArray(Charsets.UTF_8))
                .joinToString("") { b -> "%02x".format(b.toInt() and 255) } }
            if (sha != PARALLEL_PROJECTION_BASELINE_SHA256) {
                add("Parallel branch preservation requires the independently inspected merged-main baseline bytes.")
                return@buildList
            }
            val evidence = FlowYaml.readMap(requireNotNull(raw), PARALLEL_PROJECTION_BASELINE)
            val transition = section(evidence["transition"])
            addAll(matchingFields("Parallel branch preservation selection", s.certificationWorkPackage, stringMap(section(transition["work"])["after"])))
            addAll(exact("Parallel branch preservation release selection", section(s.release["recoveryCertification"]),
                stringMap(section(transition["releaseCertification"])["after"])))
            addAll(matchingFields("Parallel branch preservation work", s.certificationParallelProjectionWorkPackage, mapOf("version" to "AR-06T", "status" to "candidate",
                "stream" to "architecture-recovery", "roadmapReference" to ".flow-agent/roadmap-architecture-recovery.yaml#AR-06")))
            addAll(exact("Parallel branch preservation authorization", section(s.certificationParallelProjectionWorkPackage["authorization"]), stringMap(evidence["candidateAuthorization"])))
            addAll(exact("Parallel branch preservation validation", section(s.certificationParallelProjectionWorkPackage["validation"]),
                stringMap(section(section(transition["work"])["after"])["validation"])))
            addAll(exact("Parallel branch preservation candidate", section(s.release["recoveryParallelProjection"]), mapOf(
                "candidate" to "AR-06T", "status" to "candidate", "workPackage" to PARALLEL_PROJECTION_WORK_PACKAGE,
                "report" to ".flow-agent/reports/parallel-branch-preservation.md", "baselineEvidence" to PARALLEL_PROJECTION_BASELINE,
                "validation" to "current-revision-ci-required", "supportPromotion" to false)))
            addAll(errors(parallelProjectionPredecessor(s)))
            return@buildList
        }
        if (s.certificationWorkPackage["selectedSlice"] == "AR-06S" || s.release.containsKey("recoveryMatchCertification")) {
            val raw = s.certificationMatchBaseline
            val sha = raw?.let { MessageDigest.getInstance("SHA-256").digest(it.toByteArray(Charsets.UTF_8))
                .joinToString("") { b -> "%02x".format(b.toInt() and 255) } }
            if (sha != MATCH_BASELINE_SHA256) {
                add("Jenkins literal match certification requires the independently inspected merged-main baseline bytes.")
                return@buildList
            }
            val evidence = FlowYaml.readMap(requireNotNull(raw), MATCH_BASELINE)
            val transition = section(evidence["transition"])
            addAll(matchingFields("Jenkins literal match certification selection", s.certificationWorkPackage, stringMap(section(transition["work"])["after"])))
            addAll(exact("Jenkins literal match certification release selection", section(s.release["recoveryCertification"]),
                stringMap(section(transition["releaseCertification"])["after"])))
            addAll(matchingFields("Jenkins literal match certification work", s.certificationMatchWorkPackage, mapOf("version" to "AR-06S", "status" to "candidate",
                "stream" to "architecture-recovery", "roadmapReference" to ".flow-agent/roadmap-architecture-recovery.yaml#AR-06")))
            addAll(exact("Jenkins literal match certification authorization", section(s.certificationMatchWorkPackage["authorization"]), stringMap(evidence["candidateAuthorization"])))
            addAll(exact("Jenkins literal match certification validation", section(s.certificationMatchWorkPackage["validation"]),
                stringMap(section(section(transition["work"])["after"])["validation"])))
            addAll(exact("Jenkins literal match certification candidate", section(s.release["recoveryMatchCertification"]), mapOf(
                "candidate" to "AR-06S", "status" to "candidate", "workPackage" to MATCH_WORK_PACKAGE,
                "report" to ".flow-agent/reports/jenkins-match-certification.md", "baselineEvidence" to MATCH_BASELINE,
                "validation" to "current-revision-ci-required", "supportPromotion" to false)))
            addAll(errors(matchPredecessor(s)))
            return@buildList
        }
        if (s.certificationWorkPackage["selectedSlice"] == "AR-06R" || s.release.containsKey("recoveryMatchProjection")) {
            val raw = s.certificationMatchProjectionBaseline
            val sha = raw?.let { MessageDigest.getInstance("SHA-256").digest(it.toByteArray(Charsets.UTF_8))
                .joinToString("") { b -> "%02x".format(b.toInt() and 255) } }
            if (sha != MATCH_PROJECTION_BASELINE_SHA256) {
                add("Match branch preservation requires the independently inspected merged-main baseline bytes.")
                return@buildList
            }
            val evidence = FlowYaml.readMap(requireNotNull(raw), MATCH_PROJECTION_BASELINE)
            val transition = section(evidence["transition"])
            addAll(matchingFields("Match branch preservation selection", s.certificationWorkPackage, stringMap(section(transition["work"])["after"])))
            addAll(exact("Match branch preservation release selection", section(s.release["recoveryCertification"]),
                stringMap(section(transition["releaseCertification"])["after"])))
            addAll(matchingFields("Match branch preservation work", s.certificationMatchProjectionWorkPackage, mapOf("version" to "AR-06R", "status" to "candidate",
                "stream" to "architecture-recovery", "roadmapReference" to ".flow-agent/roadmap-architecture-recovery.yaml#AR-06")))
            addAll(exact("Match branch preservation authorization", section(s.certificationMatchProjectionWorkPackage["authorization"]), stringMap(evidence["candidateAuthorization"])))
            addAll(exact("Match branch preservation validation", section(s.certificationMatchProjectionWorkPackage["validation"]),
                stringMap(section(section(transition["work"])["after"])["validation"])))
            addAll(exact("Match branch preservation candidate", section(s.release["recoveryMatchProjection"]), mapOf(
                "candidate" to "AR-06R", "status" to "candidate", "workPackage" to MATCH_PROJECTION_WORK_PACKAGE,
                "report" to ".flow-agent/reports/match-branch-preservation.md", "baselineEvidence" to MATCH_PROJECTION_BASELINE,
                "validation" to "current-revision-ci-required", "supportPromotion" to false)))
            addAll(errors(matchProjectionPredecessor(s)))
            return@buildList
        }
        if (s.certificationWorkPackage["selectedSlice"] == "AR-06Q" || s.release.containsKey("recoveryLoopCertification")) {
            val raw = s.certificationLoopBaseline
            val sha = raw?.let { MessageDigest.getInstance("SHA-256").digest(it.toByteArray(Charsets.UTF_8))
                .joinToString("") { b -> "%02x".format(b.toInt() and 255) } }
            if (sha != LOOP_BASELINE_SHA256) {
                add("Jenkins literal loop certification requires the independently inspected merged-main baseline bytes.")
                return@buildList
            }
            val evidence = FlowYaml.readMap(requireNotNull(raw), LOOP_BASELINE)
            val transition = section(evidence["transition"])
            addAll(matchingFields("Jenkins literal loop certification selection", s.certificationWorkPackage, stringMap(section(transition["work"])["after"])))
            addAll(exact("Jenkins literal loop certification release selection", section(s.release["recoveryCertification"]),
                stringMap(section(transition["releaseCertification"])["after"])))
            addAll(matchingFields("Jenkins literal loop certification work", s.certificationLoopWorkPackage, mapOf("version" to "AR-06Q", "status" to "candidate",
                "stream" to "architecture-recovery", "roadmapReference" to ".flow-agent/roadmap-architecture-recovery.yaml#AR-06")))
            addAll(exact("Jenkins literal loop certification authorization", section(s.certificationLoopWorkPackage["authorization"]), stringMap(evidence["candidateAuthorization"])))
            addAll(exact("Jenkins literal loop certification validation", section(s.certificationLoopWorkPackage["validation"]),
                stringMap(section(section(transition["work"])["after"])["validation"])))
            addAll(exact("Jenkins literal loop certification candidate", section(s.release["recoveryLoopCertification"]), mapOf(
                "candidate" to "AR-06Q", "status" to "candidate", "workPackage" to LOOP_WORK_PACKAGE,
                "report" to ".flow-agent/reports/jenkins-loop-certification.md", "baselineEvidence" to LOOP_BASELINE,
                "validation" to "current-revision-ci-required", "supportPromotion" to false)))
            addAll(errors(loopPredecessor(s)))
            return@buildList
        }
        if (s.certificationWorkPackage["selectedSlice"] == "AR-06P" || s.release.containsKey("recoveryRetryCertification")) {
            val raw = s.certificationRetryBaseline
            val sha = raw?.let { MessageDigest.getInstance("SHA-256").digest(it.toByteArray(Charsets.UTF_8))
                .joinToString("") { b -> "%02x".format(b.toInt() and 255) } }
            if (sha != RETRY_BASELINE_SHA256) {
                add("Jenkins bounded retry certification requires the independently inspected merged-main baseline bytes.")
                return@buildList
            }
            val evidence = FlowYaml.readMap(requireNotNull(raw), RETRY_BASELINE)
            val transition = section(evidence["transition"])
            addAll(matchingFields("Jenkins bounded retry certification selection", s.certificationWorkPackage, stringMap(section(transition["work"])["after"])))
            addAll(exact("Jenkins bounded retry certification release selection", section(s.release["recoveryCertification"]),
                stringMap(section(transition["releaseCertification"])["after"])))
            addAll(matchingFields("Jenkins bounded retry certification work", s.certificationRetryWorkPackage, mapOf("version" to "AR-06P", "status" to "candidate",
                "stream" to "architecture-recovery", "roadmapReference" to ".flow-agent/roadmap-architecture-recovery.yaml#AR-06")))
            addAll(exact("Jenkins bounded retry certification authorization", section(s.certificationRetryWorkPackage["authorization"]), stringMap(evidence["candidateAuthorization"])))
            addAll(exact("Jenkins bounded retry certification validation", section(s.certificationRetryWorkPackage["validation"]),
                stringMap(section(section(transition["work"])["after"])["validation"])))
            addAll(exact("Jenkins bounded retry certification candidate", section(s.release["recoveryRetryCertification"]), mapOf(
                "candidate" to "AR-06P", "status" to "candidate", "workPackage" to RETRY_WORK_PACKAGE,
                "report" to ".flow-agent/reports/jenkins-retry-certification.md", "baselineEvidence" to RETRY_BASELINE,
                "validation" to "current-revision-ci-required", "supportPromotion" to false)))
            addAll(errors(retryPredecessor(s)))
            return@buildList
        }
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
        if (s.release.containsKey("recoveryRetryCertification") || s.certificationWorkPackage["selectedSlice"] == "AR-06P")
            return sharedCheckoutPredecessor(retryPredecessor(s))
        if (!s.release.containsKey("recoverySharedCheckout") && s.certificationWorkPackage["selectedSlice"] != "AR-06O") return s
        val transition = section(FlowYaml.readMap(requireNotNull(s.certificationSharedCheckoutBaseline), SHARED_CHECKOUT_BASELINE)["transition"])
        return s.copy(
            certificationWorkPackage = s.certificationWorkPackage + stringMap(section(transition["work"])["before"]),
            release = (s.release - "recoverySharedCheckout") + ("recoveryCertification" to section(section(transition["releaseCertification"])["before"])),
            certificationSharedCheckoutWorkPackage = emptyMap(), certificationSharedCheckoutBaseline = null)
    }

    internal fun retryBoundaryPredecessor(s: WorkflowSemanticsRecoveryLifecycleSnapshot): WorkflowSemanticsRecoveryLifecycleSnapshot {
        if (!s.release.containsKey("recoveryRetryBoundary") && s.certificationWorkPackage["selectedSlice"] != "AR-06Y") return s
        val transition = section(FlowYaml.readMap(requireNotNull(s.certificationRetryBoundaryBaseline), RETRY_BOUNDARY_BASELINE)["transition"])
        return s.copy(
            certificationWorkPackage = s.certificationWorkPackage + stringMap(section(transition["work"])["before"]),
            release = (s.release - "recoveryRetryBoundary") + ("recoveryCertification" to section(section(transition["releaseCertification"])["before"])),
            certificationRetryBoundaryWorkPackage = emptyMap(), certificationRetryBoundaryBaseline = null)
    }

    internal fun workspaceCancellationPredecessor(s: WorkflowSemanticsRecoveryLifecycleSnapshot): WorkflowSemanticsRecoveryLifecycleSnapshot {
        if (s.release.containsKey("recoveryRetryBoundary") || s.certificationWorkPackage["selectedSlice"] == "AR-06Y")
            return workspaceCancellationPredecessor(retryBoundaryPredecessor(s))
        if (!s.release.containsKey("recoveryWorkspaceCancellation") && s.certificationWorkPackage["selectedSlice"] != "AR-06X") return s
        val transition = section(FlowYaml.readMap(requireNotNull(s.certificationWorkspaceCancellationBaseline), WORKSPACE_CANCELLATION_BASELINE)["transition"])
        return s.copy(
            certificationWorkPackage = s.certificationWorkPackage + stringMap(section(transition["work"])["before"]),
            release = (s.release - "recoveryWorkspaceCancellation") + ("recoveryCertification" to section(section(transition["releaseCertification"])["before"])),
            certificationWorkspaceCancellationWorkPackage = emptyMap(), certificationWorkspaceCancellationBaseline = null)
    }

    internal fun workspacesPredecessor(s: WorkflowSemanticsRecoveryLifecycleSnapshot): WorkflowSemanticsRecoveryLifecycleSnapshot {
        if (s.release.containsKey("recoveryWorkspaceCancellation") || s.certificationWorkPackage["selectedSlice"] == "AR-06X")
            return workspacesPredecessor(workspaceCancellationPredecessor(s))
        if (!s.release.containsKey("recoveryParallelWorkspaces") && s.certificationWorkPackage["selectedSlice"] != "AR-06W") return s
        val transition = section(FlowYaml.readMap(requireNotNull(s.certificationWorkspacesBaseline), WORKSPACES_BASELINE)["transition"])
        return s.copy(
            certificationWorkPackage = s.certificationWorkPackage + stringMap(section(transition["work"])["before"]),
            release = (s.release - "recoveryParallelWorkspaces") + ("recoveryCertification" to section(section(transition["releaseCertification"])["before"])),
            certificationWorkspacesWorkPackage = emptyMap(), certificationWorkspacesBaseline = null)
    }

    internal fun cancellationPredecessor(s: WorkflowSemanticsRecoveryLifecycleSnapshot): WorkflowSemanticsRecoveryLifecycleSnapshot {
        if (s.release.containsKey("recoveryParallelWorkspaces") || s.certificationWorkPackage["selectedSlice"] == "AR-06W")
            return cancellationPredecessor(workspacesPredecessor(s))
        if (!s.release.containsKey("recoveryParallelCancellation") && s.certificationWorkPackage["selectedSlice"] != "AR-06V") return s
        val transition = section(FlowYaml.readMap(requireNotNull(s.certificationCancellationBaseline), CANCELLATION_BASELINE)["transition"])
        return s.copy(
            certificationWorkPackage = s.certificationWorkPackage + stringMap(section(transition["work"])["before"]),
            release = (s.release - "recoveryParallelCancellation") + ("recoveryCertification" to section(section(transition["releaseCertification"])["before"])),
            certificationCancellationWorkPackage = emptyMap(), certificationCancellationBaseline = null)
    }

    internal fun parallelPredecessor(s: WorkflowSemanticsRecoveryLifecycleSnapshot): WorkflowSemanticsRecoveryLifecycleSnapshot {
        if (s.release.containsKey("recoveryParallelCancellation") || s.certificationWorkPackage["selectedSlice"] == "AR-06V")
            return parallelPredecessor(cancellationPredecessor(s))
        if (!s.release.containsKey("recoveryParallelCertification") && s.certificationWorkPackage["selectedSlice"] != "AR-06U") return s
        val transition = section(FlowYaml.readMap(requireNotNull(s.certificationParallelBaseline), PARALLEL_BASELINE)["transition"])
        return s.copy(
            certificationWorkPackage = s.certificationWorkPackage + stringMap(section(transition["work"])["before"]),
            release = (s.release - "recoveryParallelCertification") + ("recoveryCertification" to section(section(transition["releaseCertification"])["before"])),
            certificationParallelWorkPackage = emptyMap(), certificationParallelBaseline = null)
    }

    internal fun parallelProjectionPredecessor(s: WorkflowSemanticsRecoveryLifecycleSnapshot): WorkflowSemanticsRecoveryLifecycleSnapshot {
        if (s.release.containsKey("recoveryParallelCertification") || s.certificationWorkPackage["selectedSlice"] == "AR-06U")
            return parallelProjectionPredecessor(parallelPredecessor(s))
        if (!s.release.containsKey("recoveryParallelProjection") && s.certificationWorkPackage["selectedSlice"] != "AR-06T") return s
        val transition = section(FlowYaml.readMap(requireNotNull(s.certificationParallelProjectionBaseline), PARALLEL_PROJECTION_BASELINE)["transition"])
        return s.copy(
            certificationWorkPackage = s.certificationWorkPackage + stringMap(section(transition["work"])["before"]),
            release = (s.release - "recoveryParallelProjection") + ("recoveryCertification" to section(section(transition["releaseCertification"])["before"])),
            certificationParallelProjectionWorkPackage = emptyMap(), certificationParallelProjectionBaseline = null)
    }

    internal fun matchPredecessor(s: WorkflowSemanticsRecoveryLifecycleSnapshot): WorkflowSemanticsRecoveryLifecycleSnapshot {
        if (s.release.containsKey("recoveryParallelProjection") || s.certificationWorkPackage["selectedSlice"] == "AR-06T")
            return matchPredecessor(parallelProjectionPredecessor(s))
        if (!s.release.containsKey("recoveryMatchCertification") && s.certificationWorkPackage["selectedSlice"] != "AR-06S") return s
        val transition = section(FlowYaml.readMap(requireNotNull(s.certificationMatchBaseline), MATCH_BASELINE)["transition"])
        return s.copy(
            certificationWorkPackage = s.certificationWorkPackage + stringMap(section(transition["work"])["before"]),
            release = (s.release - "recoveryMatchCertification") + ("recoveryCertification" to section(section(transition["releaseCertification"])["before"])),
            certificationMatchWorkPackage = emptyMap(), certificationMatchBaseline = null)
    }

    internal fun matchProjectionPredecessor(s: WorkflowSemanticsRecoveryLifecycleSnapshot): WorkflowSemanticsRecoveryLifecycleSnapshot {
        if (s.release.containsKey("recoveryMatchCertification") || s.certificationWorkPackage["selectedSlice"] == "AR-06S")
            return matchProjectionPredecessor(matchPredecessor(s))
        if (!s.release.containsKey("recoveryMatchProjection") && s.certificationWorkPackage["selectedSlice"] != "AR-06R") return s
        val transition = section(FlowYaml.readMap(requireNotNull(s.certificationMatchProjectionBaseline), MATCH_PROJECTION_BASELINE)["transition"])
        return s.copy(
            certificationWorkPackage = s.certificationWorkPackage + stringMap(section(transition["work"])["before"]),
            release = (s.release - "recoveryMatchProjection") + ("recoveryCertification" to section(section(transition["releaseCertification"])["before"])),
            certificationMatchProjectionWorkPackage = emptyMap(), certificationMatchProjectionBaseline = null)
    }

    internal fun loopPredecessor(s: WorkflowSemanticsRecoveryLifecycleSnapshot): WorkflowSemanticsRecoveryLifecycleSnapshot {
        if (s.release.containsKey("recoveryMatchProjection") || s.certificationWorkPackage["selectedSlice"] == "AR-06R")
            return loopPredecessor(matchProjectionPredecessor(s))
        if (!s.release.containsKey("recoveryLoopCertification") && s.certificationWorkPackage["selectedSlice"] != "AR-06Q") return s
        val transition = section(FlowYaml.readMap(requireNotNull(s.certificationLoopBaseline), LOOP_BASELINE)["transition"])
        return s.copy(
            certificationWorkPackage = s.certificationWorkPackage + stringMap(section(transition["work"])["before"]),
            release = (s.release - "recoveryLoopCertification") + ("recoveryCertification" to section(section(transition["releaseCertification"])["before"])),
            certificationLoopWorkPackage = emptyMap(), certificationLoopBaseline = null)
    }

    internal fun retryPredecessor(s: WorkflowSemanticsRecoveryLifecycleSnapshot): WorkflowSemanticsRecoveryLifecycleSnapshot {
        if (s.release.containsKey("recoveryLoopCertification") || s.certificationWorkPackage["selectedSlice"] == "AR-06Q")
            return retryPredecessor(loopPredecessor(s))
        if (!s.release.containsKey("recoveryRetryCertification") && s.certificationWorkPackage["selectedSlice"] != "AR-06P") return s
        val transition = section(FlowYaml.readMap(requireNotNull(s.certificationRetryBaseline), RETRY_BASELINE)["transition"])
        return s.copy(
            certificationWorkPackage = s.certificationWorkPackage + stringMap(section(transition["work"])["before"]),
            release = (s.release - "recoveryRetryCertification") + ("recoveryCertification" to section(section(transition["releaseCertification"])["before"])),
            certificationRetryWorkPackage = emptyMap(), certificationRetryBaseline = null)
    }

    private fun stringMap(value: Any?): Map<String, Any?> = section(value).entries.associate { it.key.toString() to it.value }

    private fun exact(name: String, actual: Map<*, *>, expected: Map<String, Any?>) =
        matchingFields(name, actual, expected) + if (actual.keys == expected.keys) emptyList() else listOf("$name has unsupported fields.")
}
