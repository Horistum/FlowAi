package org.flowlang.adapters.control

import java.io.File
import org.flowlang.adapters.portfolio.AdapterPortfolioLoader
import org.flowlang.adapters.portfolio.AdapterPortfolioRole
import org.flowlang.adapters.portfolio.AdapterSupportClass
import org.flowlang.capabilities.CompatibilityIssue
import org.flowlang.capabilities.CompatibilityLevel
import org.flowlang.capabilities.SupportLevel
import org.flowlang.capabilities.TargetCapability
import org.flowlang.generators.manifest.TargetManifest
import org.flowlang.generators.manifest.TargetMappingNote
import org.flowlang.generators.manifest.TargetProjectionRegistry
import org.flowlang.planner.ApprovalNode
import org.flowlang.planner.ConditionNode
import org.flowlang.planner.ExecutionPlan
import org.flowlang.planner.LoopNode
import org.flowlang.planner.MatchPlanNode
import org.flowlang.planner.ParallelGroupNode
import org.flowlang.planner.PlanNode
import org.flowlang.planner.RetryGroupNode
import org.flowlang.planner.TaskNode
import org.flowlang.planner.TryPlanNode
import org.flowlang.targets.builtin.BuiltInTargetProjections

/**
 * Distribution-owned authority for concrete control materialization evidence.
 *
 * Core meaning remains target-neutral. This authority derives exact adapter
 * requirements from the already-lowered plan and matches them only against
 * composed-provider evidence. Platform documentation and target registry
 * summaries remain contextual or negative corroboration; neither can prove a
 * supported control semantic.
 */
class AdapterControlMaterializationAuthority(
    private val rootDir: File = File("."),
    private val targets: Map<String, TargetCapability>,
    private val projections: TargetProjectionRegistry = BuiltInTargetProjections.registry
) {
    private val document: AdapterControlMaterializationDocument by lazy {
        AdapterControlMaterializationLoader.load(rootDir)
    }

    fun analyze(
        input: AdapterControlMaterializationDocument = document
    ): AdapterControlMaterializationReport {
        val findings = mutableListOf<AdapterControlMaterializationFinding>()
        val recordsByTarget = input.targets.groupBy(AdapterControlTargetRecord::target)
        val expectedTargets = targets.keys

        recordsByTarget.filterValues { it.size > 1 }.forEach { (target, records) ->
            finding(findings, "CONTROL_TARGET_DUPLICATE", target, "all", "Target occurs ${records.size} times.")
        }
        (expectedTargets - recordsByTarget.keys).sorted().forEach { target ->
            finding(findings, "CONTROL_TARGET_MISSING", target, "all", "Target has no control materialization record.")
        }
        (recordsByTarget.keys - expectedTargets).sorted().forEach { target ->
            finding(findings, "CONTROL_TARGET_UNKNOWN", target, "all", "Manifest target is absent from the target registry.")
        }

        val portfolio = AdapterPortfolioLoader.load(rootDir).records.associateBy { it.target }
        input.targets.forEach { record ->
            val claimsByFamily = record.claims.groupBy(AdapterControlClaim::family)
            claimsByFamily.filterValues { it.size > 1 }.forEach { (family, claims) ->
                finding(findings, "CONTROL_FAMILY_DUPLICATE", record.target, family.name, "Family occurs ${claims.size} times.")
            }
            (AdapterControlFamily.entries.toSet() - claimsByFamily.keys).forEach { family ->
                finding(findings, "CONTROL_FAMILY_MISSING", record.target, family.name, "Target does not declare the complete control family contract.")
            }

            val portfolioRecord = portfolio[record.target]
            record.claims.forEach { claim ->
                evaluateClaim(
                    target = record.target,
                    claim = claim,
                    role = portfolioRecord?.role,
                    supportClass = portfolioRecord?.supportClass,
                    findings = findings
                )
            }
        }

        return AdapterControlMaterializationReport(
            status = if (findings.isEmpty()) "PASS" else "FAIL",
            findings = findings,
            targetCount = input.targets.size,
            claimCount = input.targets.sumOf { it.claims.size }
        )
    }

    fun assess(plan: ExecutionPlan, target: String): AdapterControlAssessment {
        require(target in targets) { "Unknown target '$target'." }
        val record = document.targets.singleOrNull { it.target == target }
            ?: error("Target '$target' has no adapter control materialization record.")
        val claims = record.claims.associateBy(AdapterControlClaim::family)
        val requirements = requirementsFor(plan)
        val evidence = requirements.map { requirement ->
            val claim = claims[requirement.family]
            when {
                claim == null -> AdapterControlEvidence(
                    requirementId = requirement.id,
                    status = AdapterControlEvidenceStatus.UNKNOWN,
                    detail = "No control-family claim exists for ${requirement.family}."
                )
                requirement.semantic in claim.semantics.supported -> AdapterControlEvidence(
                    requirementId = requirement.id,
                    status = AdapterControlEvidenceStatus.SATISFIED,
                    detail = "${claim.mechanism} Evidence: ${claim.evidenceReferences.joinToString()}"
                )
                requirement.semantic in claim.semantics.unsupported -> AdapterControlEvidence(
                    requirementId = requirement.id,
                    status = AdapterControlEvidenceStatus.UNSUPPORTED,
                    detail = claim.semantics.unsupported.getValue(requirement.semantic)
                )
                requirement.semantic in claim.semantics.unknown -> AdapterControlEvidence(
                    requirementId = requirement.id,
                    status = AdapterControlEvidenceStatus.UNKNOWN,
                    detail = claim.semantics.unknown.getValue(requirement.semantic)
                )
                else -> AdapterControlEvidence(
                    requirementId = requirement.id,
                    status = AdapterControlEvidenceStatus.UNKNOWN,
                    detail = "Control semantic '${requirement.semantic}' is absent from the declared partition."
                )
            }
        }
        val blockers = evidence
            .filter { it.status != AdapterControlEvidenceStatus.SATISFIED }
            .map(AdapterControlEvidence::requirementId)
        return AdapterControlAssessment(
            target = target,
            requirements = requirements,
            evidence = evidence,
            decision = if (blockers.isEmpty()) AdapterControlDecision.MATCHED else AdapterControlDecision.BLOCKED,
            blockingRequirementIds = blockers
        )
    }

    fun requireMatched(plan: ExecutionPlan, target: String): AdapterControlAssessment =
        assess(plan, target).also { assessment ->
            if (assessment.decision != AdapterControlDecision.MATCHED) {
                throw UnresolvedAdapterControlMaterializationException(assessment)
            }
        }

    fun reconcileDiagnostic(
        manifest: TargetManifest,
        assessment: AdapterControlAssessment
    ): TargetManifest {
        if (assessment.decision == AdapterControlDecision.MATCHED) return manifest
        val evidenceById = assessment.evidence.associateBy(AdapterControlEvidence::requirementId)
        val issues = assessment.requirements.mapNotNull { requirement ->
            val evidence = evidenceById[requirement.id] ?: return@mapNotNull null
            if (evidence.status == AdapterControlEvidenceStatus.SATISFIED) return@mapNotNull null
            CompatibilityIssue(
                level = CompatibilityLevel.ERROR,
                target = assessment.target,
                nodeId = requirement.subject,
                feature = "control.${requirement.family.name.lowercase()}.${requirement.semantic}.adapter",
                message = "Adapter control requirement '${requirement.id}' is " +
                    "${evidence.status.name.lowercase()}: ${evidence.detail}"
            )
        }
        val notes = issues.map { issue ->
            TargetMappingNote(
                level = "error",
                target = assessment.target,
                nodeId = issue.nodeId,
                feature = issue.feature,
                message = issue.message
            )
        }
        return manifest.copy(
            compatibility = manifest.compatibility.copy(
                status = SupportLevel.UNSUPPORTED,
                issues = (manifest.compatibility.issues + issues).distinct(),
                executable = false,
                readinessEvidenceAvailable = true
            ),
            mappingNotes = (manifest.mappingNotes + notes).distinct(),
            metadata = manifest.metadata + mapOf(
                "adapterControlDecision" to assessment.decision.name,
                "adapterControlRequirementCount" to assessment.requirements.size.toString(),
                "adapterControlBlockerCount" to assessment.blockingRequirementIds.size.toString()
            )
        )
    }

    fun requirementsFor(plan: ExecutionPlan): List<AdapterControlRequirement> {
        val requirements = mutableListOf<AdapterControlRequirement>()
        flatten(plan.nodes).forEach { node ->
            when (node) {
                is ApprovalNode -> requirements += requirement(
                    family = AdapterControlFamily.APPROVAL,
                    semantic = "approval.manual.inline",
                    subject = node.id,
                    detail = "ApprovalNode(mode=${node.mode})"
                )
                is RetryGroupNode -> addRetryRequirements(node, requirements)
                is TryPlanNode -> addCompensationRequirements(node, requirements)
                else -> Unit
            }
        }
        addScheduleRequirements(plan, requirements)
        addPreservedSourceRequirements(plan, requirements)
        return requirements
            .distinctBy(AdapterControlRequirement::id)
            .sortedBy(AdapterControlRequirement::id)
    }

    private fun addRetryRequirements(
        node: RetryGroupNode,
        requirements: MutableList<AdapterControlRequirement>
    ) {
        requirements += requirement(
            family = AdapterControlFamily.RETRY,
            semantic = "retry.attempt-limit",
            subject = node.id,
            detail = "RetryGroupNode(max=${node.max})"
        )
        if (!node.delay.isZeroDuration()) {
            requirements += requirement(
                family = AdapterControlFamily.RETRY,
                semantic = "retry.delay.fixed",
                subject = node.id,
                detail = "RetryGroupNode(delay=${node.delay})"
            )
        }
        if (!node.backoff.equals("fixed", ignoreCase = true)) {
            requirements += requirement(
                family = AdapterControlFamily.RETRY,
                semantic = "retry.backoff.variable",
                subject = node.id,
                detail = "RetryGroupNode(backoff=${node.backoff})"
            )
        }
    }

    private fun addCompensationRequirements(
        node: TryPlanNode,
        requirements: MutableList<AdapterControlRequirement>
    ) {
        if (node.errorHandler.isEmpty()) return
        requirements += requirement(
            family = AdapterControlFamily.COMPENSATION,
            semantic = "compensation.error-handler",
            subject = node.id,
            detail = "TryPlanNode(errorHandler=${node.errorHandler.size})"
        )
        val containsRollback = flatten(node.errorHandler)
            .filterIsInstance<TaskNode>()
            .any { it.semanticCapability == "ROLLBACK" }
        if (containsRollback) {
            requirements += requirement(
                family = AdapterControlFamily.COMPENSATION,
                semantic = "compensation.rollback",
                subject = node.id,
                detail = "TryPlanNode contains canonical rollback work"
            )
        }
    }

    private fun addScheduleRequirements(
        plan: ExecutionPlan,
        requirements: MutableList<AdapterControlRequirement>
    ) {
        plan.triggers.forEach { trigger ->
            val schedule = trigger.schedule ?: return@forEach
            val semantic = when (schedule.kind.uppercase()) {
                "CRON" -> "scheduling.cron"
                "INTERVAL" -> "scheduling.interval"
                "CALENDAR" -> "scheduling.calendar"
                else -> "scheduling.${schedule.kind.lowercase()}"
            }
            requirements += requirement(
                family = AdapterControlFamily.SCHEDULING,
                semantic = semantic,
                subject = trigger.id,
                detail = "PlanSchedule(kind=${schedule.kind}, expression=${schedule.expression})"
            )
            if (!schedule.timezone.isNullOrBlank()) {
                requirements += requirement(
                    family = AdapterControlFamily.SCHEDULING,
                    semantic = "scheduling.timezone",
                    subject = trigger.id,
                    detail = "PlanSchedule(timezone=${schedule.timezone})"
                )
            }
            if (trigger.params.keys.any { it in SCHEDULER_CONCURRENCY_KEYS }) {
                requirements += requirement(
                    family = AdapterControlFamily.SCHEDULING,
                    semantic = "scheduling.concurrency",
                    subject = trigger.id,
                    detail = "Trigger declares scheduler concurrency policy"
                )
            }
            if (trigger.params.keys.any { it in SCHEDULER_CATCH_UP_KEYS }) {
                requirements += requirement(
                    family = AdapterControlFamily.SCHEDULING,
                    semantic = "scheduling.catch-up",
                    subject = trigger.id,
                    detail = "Trigger declares missed-run behavior"
                )
            }
        }
    }

    private fun addPreservedSourceRequirements(
        plan: ExecutionPlan,
        requirements: MutableList<AdapterControlRequirement>
    ) {
        plan.sourceIntent?.policies.orEmpty().forEach { policy ->
            when (policy.type.uppercase()) {
                "RETRY" -> requirements += requirement(
                    family = AdapterControlFamily.RETRY,
                    semantic = "retry.attempt-limit",
                    subject = "policy:${policy.name}",
                    detail = "Source RETRY policy is preserved but requires concrete execution evidence"
                )
                "TIMEOUT" -> requirements += requirement(
                    family = AdapterControlFamily.TIMEOUT,
                    semantic = "timeout.workflow",
                    subject = "policy:${policy.name}",
                    detail = "Source TIMEOUT policy is preserved but requires concrete execution evidence"
                )
            }
        }
        if (plan.sourceIntent?.failure?.rollback == true) {
            requirements += requirement(
                family = AdapterControlFamily.COMPENSATION,
                semantic = "compensation.rollback",
                subject = "failure.rollback",
                detail = "Source failure.rollback=true"
            )
        }
    }

    private fun evaluateClaim(
        target: String,
        claim: AdapterControlClaim,
        role: AdapterPortfolioRole?,
        supportClass: AdapterSupportClass?,
        findings: MutableList<AdapterControlMaterializationFinding>
    ) {
        val expected = AdapterControlSemanticContract.byFamily.getValue(claim.family)
        if (claim.semantics.all != expected) {
            finding(
                findings,
                "CONTROL_SEMANTIC_PARTITION_MISMATCH",
                target,
                claim.family.name,
                "declared=${claim.semantics.all.sorted()} expected=${expected.sorted()}"
            )
        }
        val overlaps = (claim.semantics.supported intersect claim.semantics.unsupported.keys) +
            (claim.semantics.supported intersect claim.semantics.unknown.keys) +
            (claim.semantics.unsupported.keys intersect claim.semantics.unknown.keys)
        if (overlaps.isNotEmpty()) {
            finding(
                findings,
                "CONTROL_SEMANTIC_PARTITION_OVERLAP",
                target,
                claim.family.name,
                overlaps.sorted().joinToString()
            )
        }

        if (!claim.status.matches(claim.semantics, expected)) {
            finding(
                findings,
                "CONTROL_STATUS_PARTITION_MISMATCH",
                target,
                claim.family.name,
                "Status ${claim.status} contradicts the semantic partition."
            )
        }

        val provider = projections.providerFor(target)
        if (claim.semantics.supported.isNotEmpty() && provider == null) {
            finding(
                findings,
                "CONTROL_SUPPORTED_WITHOUT_PROVIDER",
                target,
                claim.family.name,
                "Supported control semantics require a composed provider."
            )
        }
        if (role == AdapterPortfolioRole.SEMANTIC_REFERENCE && claim.status != AdapterControlClaimStatus.UNKNOWN) {
            finding(
                findings,
                "CONTROL_SEMANTIC_REFERENCE_PROMOTED",
                target,
                claim.family.name,
                "Semantic references must remain UNKNOWN."
            )
        }
        if (supportClass == AdapterSupportClass.PROFILE_ONLY && claim.status != AdapterControlClaimStatus.UNKNOWN) {
            finding(
                findings,
                "CONTROL_PROFILE_ONLY_PROMOTED",
                target,
                claim.family.name,
                "Profile-only targets must remain UNKNOWN until a provider is composed."
            )
        }
        if ("approval.manual.inline" in claim.semantics.supported) {
            val approvalDefinition = provider?.nativeProjectionCatalog?.approvalDefinitions
                ?.singleOrNull { it.capability == "approval.manual" }
            if (approvalDefinition == null) {
                finding(
                    findings,
                    "CONTROL_APPROVAL_PROVIDER_EVIDENCE_MISSING",
                    target,
                    claim.family.name,
                    "Manual inline approval has no provider-owned payload definition."
                )
            }
        }
        validateEvidenceReferences(target, claim, findings)
        claim.platformReferences.forEach { reference ->
            if (!reference.startsWith("https://")) {
                finding(
                    findings,
                    "CONTROL_PLATFORM_REFERENCE_INVALID",
                    target,
                    claim.family.name,
                    "Platform reference must be HTTPS: $reference"
                )
            }
        }
    }

    private fun validateEvidenceReferences(
        target: String,
        claim: AdapterControlClaim,
        findings: MutableList<AdapterControlMaterializationFinding>
    ) {
        val fileParts = claim.evidenceReferences.map { it.substringBefore('#') }
        fileParts.forEachIndexed { index, filePart ->
            val reference = claim.evidenceReferences[index]
            when {
                filePart == AdapterControlMaterializationLoader.PATH -> finding(
                    findings,
                    "CONTROL_EVIDENCE_SELF_REFERENTIAL",
                    target,
                    claim.family.name,
                    "Evidence cannot cite its own authority '$filePart'."
                )
                !File(rootDir, filePart).isFile -> finding(
                    findings,
                    "CONTROL_EVIDENCE_UNRESOLVED",
                    target,
                    claim.family.name,
                    "Evidence file does not exist: $reference"
                )
            }
        }
        if (fileParts.all { it == TARGET_REGISTRY_PATH }) {
            finding(
                findings,
                "CONTROL_EVIDENCE_REGISTRY_ONLY",
                target,
                claim.family.name,
                "Target registry data may corroborate unsupported projection state but cannot be the only repository evidence."
            )
        }
        if (
            claim.semantics.supported.isNotEmpty() &&
            fileParts.none(::isIndependentImplementationEvidence)
        ) {
            finding(
                findings,
                "CONTROL_SUPPORTED_IMPLEMENTATION_EVIDENCE_MISSING",
                target,
                claim.family.name,
                "Supported semantics require at least one independent src/main or src/test implementation evidence reference."
            )
        }
    }

    private fun isIndependentImplementationEvidence(path: String): Boolean =
        path.startsWith("src/main/") || path.startsWith("src/test/")

    private fun AdapterControlClaimStatus.matches(
        semantics: AdapterControlSemanticPartition,
        expected: Set<String>
    ): Boolean = when (this) {
        AdapterControlClaimStatus.SUPPORTED -> semantics.supported == expected
        AdapterControlClaimStatus.PARTIAL -> semantics.supported.isNotEmpty() && semantics.supported != expected
        AdapterControlClaimStatus.UNSUPPORTED -> semantics.supported.isEmpty() && semantics.unknown.isEmpty()
        AdapterControlClaimStatus.UNKNOWN -> semantics.supported.isEmpty() && semantics.unknown.isNotEmpty()
    }

    private fun requirement(
        family: AdapterControlFamily,
        semantic: String,
        subject: String,
        detail: String
    ): AdapterControlRequirement = AdapterControlRequirement(
        id = "adapter-control.${family.name.lowercase()}.${semantic.substringAfterLast('.')}.${canonicalId(subject)}",
        family = family,
        semantic = semantic,
        subject = subject,
        detail = detail
    )

    private fun flatten(nodes: List<PlanNode>): List<PlanNode> = nodes.flatMap { node ->
        listOf(node) + when (node) {
            is ConditionNode -> flatten(node.then) + flatten(node.otherwise)
            is LoopNode -> flatten(node.body)
            is ParallelGroupNode -> node.branches.flatMap { flatten(it.steps) }
            is MatchPlanNode -> node.cases.flatMap { flatten(it.steps) } +
                flatten(node.errorCase) + flatten(node.defaultSteps)
            is RetryGroupNode -> flatten(node.body)
            is TryPlanNode -> flatten(node.body) + flatten(node.errorHandler)
            else -> emptyList()
        }
    }

    private fun String.isZeroDuration(): Boolean =
        trim().lowercase() in setOf("0", "0s", "0m", "0h", "0ms")

    private fun canonicalId(value: String): String = value.trim().lowercase()
        .replace(Regex("[^a-z0-9]+"), "-")
        .trim('-')
        .ifBlank { "control" }

    private fun finding(
        findings: MutableList<AdapterControlMaterializationFinding>,
        code: String,
        target: String,
        family: String,
        message: String
    ) {
        findings += AdapterControlMaterializationFinding(code, target, family, message)
    }

    companion object {
        private const val TARGET_REGISTRY_PATH = "targets/builtin-targets.yaml"
        private val SCHEDULER_CONCURRENCY_KEYS = setOf("concurrency", "concurrencyPolicy")
        private val SCHEDULER_CATCH_UP_KEYS = setOf("catchUp", "startingDeadlineSeconds")
    }
}
