package org.flowlang.conformance

import java.io.File
import org.flowlang.adapters.control.AdapterControlClaimStatus
import org.flowlang.adapters.control.AdapterControlDecision
import org.flowlang.adapters.control.AdapterControlEvidenceStatus
import org.flowlang.adapters.control.AdapterControlFamily
import org.flowlang.adapters.control.AdapterControlMaterializationAuthority
import org.flowlang.adapters.control.AdapterControlMaterializationLoader
import org.flowlang.adapters.control.AdapterControlRequirementCompleteness
import org.flowlang.adapters.control.AdapterControlRoadmapLifecycleAuthority
import org.flowlang.adapters.portfolio.AdapterPortfolioLoader
import org.flowlang.adapters.portfolio.AdapterPortfolioRole
import org.flowlang.adapters.portfolio.AdapterSupportClass
import org.flowlang.capabilities.TargetCapability
import org.flowlang.lowering.IntentFailureMetadata
import org.flowlang.lowering.IntentPolicyMetadata
import org.flowlang.lowering.IntentSourceMetadata
import org.flowlang.planner.ApprovalNode
import org.flowlang.planner.ExecutionPlan
import org.flowlang.planner.PlanSchedule
import org.flowlang.planner.PlanTrigger
import org.flowlang.planner.RetryGroupNode
import org.flowlang.targets.builtin.BuiltInTargetProjections

class AdapterControlConformanceChecks(
    private val rootDir: File,
    private val targets: Map<String, TargetCapability>
) {
    fun checks(): List<ConformanceCheck> {
        val lifecycleResult = runCatching { AdapterControlRoadmapLifecycleAuthority(rootDir).analyze() }
        val lifecycle = lifecycleResult.getOrNull()
        val evidenceResult = runCatching { authority().analyze() }
        val evidence = evidenceResult.getOrNull()
        val runtimeResult = runCatching(::runtimeAuthorityErrors)
        val demotionResult = runCatching(::unsupportedDemotionErrors)
        val separationResult = runCatching(::platformSeparationErrors)

        val lifecycleErrors = buildList {
            lifecycleResult.exceptionOrNull()?.let { add(it.message ?: it.javaClass.simpleName) }
            lifecycle?.failedChecks?.forEach { id ->
                val failed = lifecycle.checks.first { it.id == id }
                add("$id:${failed.evidence.joinToString()}:${failed.message}")
            }
        }
        val evidenceErrors = buildList {
            evidenceResult.exceptionOrNull()?.let { add(it.message ?: it.javaClass.simpleName) }
            evidence?.findings?.forEach { add("${it.code}:${it.target}:${it.family}:${it.message}") }
        }

        return listOf(
            ConformanceCheck(
                name = LIFECYCLE_CHECK,
                passed = lifecycle?.status == "PASS",
                message = lifecycleErrors.takeIf { it.isNotEmpty() }?.joinToString(" | ")
            ),
            ConformanceCheck(
                name = EVIDENCE_CHECK,
                passed = evidence?.status == "PASS",
                message = evidenceErrors.takeIf { it.isNotEmpty() }?.joinToString(" | ")
            ),
            ConformanceCheck(
                name = RUNTIME_AUTHORITY_CHECK,
                passed = resultErrors(runtimeResult).isEmpty(),
                message = resultErrors(runtimeResult).takeIf { it.isNotEmpty() }?.joinToString(" | ")
            ),
            ConformanceCheck(
                name = DEMOTION_CHECK,
                passed = resultErrors(demotionResult).isEmpty(),
                message = resultErrors(demotionResult).takeIf { it.isNotEmpty() }?.joinToString(" | ")
            ),
            ConformanceCheck(
                name = PLATFORM_SEPARATION_CHECK,
                passed = resultErrors(separationResult).isEmpty(),
                message = resultErrors(separationResult).takeIf { it.isNotEmpty() }?.joinToString(" | ")
            )
        )
    }

    private fun runtimeAuthorityErrors(): List<String> = buildList {
        val authority = authority()
        val approval = authority.assess(
            ExecutionPlan(flowName = "approval", nodes = listOf(ApprovalNode(id = "approve"))),
            "jenkins"
        )
        if (approval.decision != AdapterControlDecision.MATCHED) {
            add("Jenkins provider-backed manual approval must match: ${approval.blockingRequirementIds.joinToString()}")
        }

        val cron = ExecutionPlan(
            flowName = "cron",
            triggers = listOf(PlanTrigger(
                id = "nightly",
                type = "SCHEDULE",
                schedule = PlanSchedule("CRON", "0 2 * * *")
            ))
        )
        listOf("jenkins", "github-actions").forEach { target ->
            val assessment = authority.assess(cron, target)
            if (assessment.decision != AdapterControlDecision.MATCHED) {
                add("$target CRON must match its concrete renderer evidence: ${assessment.blockingRequirementIds.joinToString()}")
            }
        }

        val timeoutPolicy = ExecutionPlan(
            flowName = "timeout",
            sourceIntent = IntentSourceMetadata(
                policies = listOf(IntentPolicyMetadata("deadline", "TIMEOUT")),
                failure = IntentFailureMetadata()
            )
        )
        val timeout = authority.assess(timeoutPolicy, "jenkins")
        if (
            timeout.decision != AdapterControlDecision.BLOCKED ||
            timeout.requirements.none {
                it.semantic == "timeout.unspecified" &&
                    it.completeness == AdapterControlRequirementCompleteness.PRESERVED_UNSPECIFIED
            } ||
            timeout.evidence.none { it.status == AdapterControlEvidenceStatus.UNKNOWN }
        ) {
            add("Preserved TIMEOUT policy must remain an explicit UNKNOWN adapter requirement until duration and scope survive lowering.")
        }
    }

    private fun unsupportedDemotionErrors(): List<String> = buildList {
        val authority = authority()
        val retry = ExecutionPlan(
            flowName = "retry",
            nodes = listOf(RetryGroupNode(id = "retry", max = 3, delay = "10s", backoff = "fixed"))
        )
        listOf("jenkins", "github-actions", "tekton").forEach { target ->
            val assessment = authority.assess(retry, target)
            if (assessment.decision != AdapterControlDecision.BLOCKED) {
                add("$target retry must remain blocked while its provider discards retry semantics.")
            }
            if (assessment.evidence.none { it.status == AdapterControlEvidenceStatus.UNSUPPORTED }) {
                add("$target retry blocker must be explicit UNSUPPORTED evidence.")
            }
        }

        val timezone = ExecutionPlan(
            flowName = "timezone",
            triggers = listOf(PlanTrigger(
                id = "nightly",
                type = "SCHEDULE",
                schedule = PlanSchedule("CRON", "0 2 * * *", "Europe/Prague")
            ))
        )
        listOf("jenkins", "github-actions").forEach { target ->
            val assessment = authority.assess(timezone, target)
            if (assessment.decision != AdapterControlDecision.BLOCKED ||
                assessment.requirements.none { it.semantic == "scheduling.timezone" }) {
                add("$target must not discard an authored schedule timezone.")
            }
        }
    }

    private fun platformSeparationErrors(): List<String> = buildList {
        val document = AdapterControlMaterializationLoader.load(rootDir)
        val portfolio = AdapterPortfolioLoader.load(rootDir).records.associateBy { it.target }
        document.targets.forEach { record ->
            val portfolioRecord = portfolio[record.target]
            if (
                portfolioRecord?.role == AdapterPortfolioRole.SEMANTIC_REFERENCE ||
                portfolioRecord?.supportClass == AdapterSupportClass.PROFILE_ONLY
            ) {
                record.claims.filter { it.status != AdapterControlClaimStatus.UNKNOWN }.forEach { claim ->
                    add("${record.target}.${claim.family}: non-provider target was promoted to ${claim.status}.")
                }
            }
            record.claims.forEach { claim ->
                if (claim.semantics.supported.isNotEmpty() && claim.evidenceReferences.isEmpty()) {
                    add("${record.target}.${claim.family}: supported semantics have no repository implementation evidence.")
                }
                if (claim.evidenceReferences.any { it.startsWith("http://") || it.startsWith("https://") }) {
                    add("${record.target}.${claim.family}: external platform documentation cannot be implementation evidence.")
                }
                if (claim.platformReferences.any { !it.startsWith("https://") }) {
                    add("${record.target}.${claim.family}: platform context must use an HTTPS primary reference.")
                }
            }
        }

        val profileOnlyApproval = authority().assess(
            ExecutionPlan(flowName = "approval", nodes = listOf(ApprovalNode(id = "approve"))),
            "argo-workflows"
        )
        if (profileOnlyApproval.decision != AdapterControlDecision.BLOCKED ||
            profileOnlyApproval.evidence.none { it.status == AdapterControlEvidenceStatus.UNKNOWN }) {
            add("Argo Workflows suspend folklore must not become approval evidence without a composed provider.")
        }
    }

    private fun authority() = AdapterControlMaterializationAuthority(
        rootDir = rootDir,
        targets = targets,
        projections = BuiltInTargetProjections.registry
    )

    private fun resultErrors(result: Result<List<String>>): List<String> =
        result.getOrElse { listOf(it.message ?: it.javaClass.simpleName) }

    companion object {
        const val LIFECYCLE_CHECK = "adapters.a0.4.lifecycle-integrity"
        const val EVIDENCE_CHECK = "adapters.a0.4.control-evidence-integrity"
        const val RUNTIME_AUTHORITY_CHECK = "adapters.a0.4.runtime-control-authority"
        const val DEMOTION_CHECK = "adapters.a0.4.unsupported-control-demotion"
        const val PLATFORM_SEPARATION_CHECK = "adapters.a0.4.platform-capability-separation"
    }
}
