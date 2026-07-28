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
    private val projections: TargetProjectionRegistry = BuiltInTargetProjections.registry,
    private val documentOverride: AdapterControlMaterializationDocument? = null
) {
    private val document: AdapterControlMaterializationDocument by lazy {
        documentOverride ?: AdapterControlMaterializationLoader.load(rootDir)
    }

    /**
     * Runtime composition may intentionally expose only a subset of distribution
     * targets. Certify every active target exactly, while the public [analyze]
     * path continues to verify the complete distribution inventory.
     */
    private val certifiedDocument: AdapterControlMaterializationDocument by lazy {
        val loaded = document
        val active = loaded.copy(targets = loaded.targets.filter { it.target in targets })
        val report = analyze(active)
        check(report.status == "PASS") {
            "Adapter control materialization evidence is invalid: " +
                report.findings.joinToString(" | ") { "${it.code}:${it.target}:${it.family}:${it.message}" }
        }
        active
    }

    fun analyze(
        input: AdapterControlMaterializationDocument = document
    ): AdapterControlMaterializationReport {
        val findings = mutableListOf<AdapterControlMaterializationFinding>()
        if (input.version != AdapterControlMaterializationLoader.SUPPORTED_VERSION) {
            finding(
                findings,
                "CONTROL_MANIFEST_VERSION_UNSUPPORTED",
                "manifest",
                "all",
                "Version '${input.version}' is unsupported; expected '${AdapterControlMaterializationLoader.SUPPORTED_VERSION}'."
            )
        }

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
        val record = certifiedDocument.targets.single { it.target == target }
        val claims = record.claims.associateBy(AdapterControlClaim::family)
        val requirements = requirementsFor(plan)
        val evidence = requirements.map { requirement ->
            val claim = claims[requirement.family]
            when {
                requirement.completeness != AdapterControlRequirementCompleteness.COMPLETE -> AdapterControlEvidence(
                    requirementId = requirement.id,
                    status = AdapterControlEvidenceStatus.UNKNOWN,
                    detail = "Preserved ${requirement.family.name.lowercase()} metadata lacks the exact scope or value needed for provider certification."
                )
                claim == null -> AdapterControlEvidence(
                    requirementId = requirement.id,
                    status = AdapterControlEvidenceStatus.UNKNOWN,
                    detail = "No control-family claim exists for ${requirement.family}."
                )
                requirement.semantic in claim.semantics.supported && requirement.scope !in claim.scopes -> AdapterControlEvidence(
                    requirementId = requirement.id,
                    status = AdapterControlEvidenceStatus.UNSUPPORTED,
                    detail = "${claim.mechanism} Supported scopes are ${claim.scopes.sortedBy { it.name }.joinToString()}, " +
                        "not required scope ${requirement.scope}."
                )
                requirement.semantic in claim.semantics.supported -> AdapterControlEvidence(
                    requirementId = requirement.id,
                    status = AdapterControlEvidenceStatus.SATISFIED,
                    detail = "${claim.mechanism} Scope: ${requirement.scope}. Evidence: ${claim.evidenceReferences.joinToString()}"
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
        require(manifest.target == assessment.target) {
            "Diagnostic manifest target '${manifest.target}' does not match control assessment target '${assessment.target}'."
        }
        val metadata = manifest.metadata + assessmentMetadata(assessment)
        if (assessment.decision == AdapterControlDecision.MATCHED) {
            return manifest.copy(metadata = metadata)
        }

        val evidenceById = assessment.evidence.associateBy(AdapterControlEvidence::requirementId)
        require(evidenceById.size == assessment.evidence.size) {
            "Adapter control assessment for '${assessment.target}' contains duplicate evidence ids."
        }
        val issues = assessment.requirements.mapNotNull { requirement ->
            val evidence = evidenceById[requirement.id]
                ?: error("Adapter control requirement '${requirement.id}' has no assessment evidence.")
            if (evidence.status == AdapterControlEvidenceStatus.SATISFIED) return@mapNotNull null
            CompatibilityIssue(
                level = CompatibilityLevel.ERROR,
                target = assessment.target,
                nodeId = requirement.subject,
                feature = "control.${requirement.semantic}.adapter",
                message = "Adapter control requirement '${requirement.id}' at scope ${requirement.scope} is " +
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
            metadata = metadata
        )
    }

    fun requirementsFor(plan: ExecutionPlan): List<AdapterControlRequirement> {
        val requirements = mutableListOf<AdapterControlRequirement>()
        val canonicalFlowHandler = plan.nodes.lastOrNull()
            ?.takeIf { node ->
                node is TryPlanNode && node.body.isEmpty() && node.errorHandler.isNotEmpty() && plan.nodes.size > 1
            }
        flatten(plan.nodes).forEach { node ->
            when (node) {
                is ApprovalNode -> requirements += approvalRequirement(node)
                is RetryGroupNode -> addRetryRequirements(node, requirements)
                is TryPlanNode -> addCompensationRequirements(
                    node = node,
                    canonicalFlowHandler = node === canonicalFlowHandler,
                    requirements = requirements
                )
                else -> Unit
            }
        }
        addScheduleRequirements(plan, requirements)
        addPreservedSourceRequirements(plan, requirements)

        val byId = requirements.groupBy(AdapterControlRequirement::id)
        val conflicting = byId.filterValues { group -> group.distinct().size > 1 }
        require(conflicting.isEmpty()) {
            "Adapter control requirement identity collision: " + conflicting.keys.sorted().joinToString()
        }
        return byId.values.map { it.first() }.sortedBy(AdapterControlRequirement::id)
    }

    private fun approvalRequirement(node: ApprovalNode): AdapterControlRequirement = when (node.mode) {
        "manual" -> requirement(
            family = AdapterControlFamily.APPROVAL,
            semantic = "approval.manual.inline",
            subject = node.id,
            scope = AdapterControlScope.STEP,
            detail = "ApprovalNode(mode=${node.mode})"
        )
        "environment" -> requirement(
            family = AdapterControlFamily.APPROVAL,
            semantic = "approval.environment.resource",
            subject = node.id,
            scope = AdapterControlScope.ENVIRONMENT,
            detail = "ApprovalNode(mode=${node.mode})"
        )
        "external" -> requirement(
            family = AdapterControlFamily.APPROVAL,
            semantic = "approval.external",
            subject = node.id,
            scope = AdapterControlScope.STEP,
            detail = "ApprovalNode(mode=${node.mode})"
        )
        else -> requirement(
            family = AdapterControlFamily.APPROVAL,
            semantic = "approval.mode.${canonicalId(node.mode)}",
            subject = node.id,
            scope = AdapterControlScope.UNSPECIFIED,
            detail = "ApprovalNode declares unsupported mode '${node.mode}'"
        )
    }

    private fun addRetryRequirements(
        node: RetryGroupNode,
        requirements: MutableList<AdapterControlRequirement>
    ) {
        requirements += requirement(
            family = AdapterControlFamily.RETRY,
            semantic = "retry.attempt-limit",
            subject = node.id,
            scope = AdapterControlScope.TASK,
            detail = "RetryGroupNode(max=${node.max})"
        )
        if (!node.delay.isZeroDuration()) {
            requirements += requirement(
                family = AdapterControlFamily.RETRY,
                semantic = "retry.delay.fixed",
                subject = node.id,
                scope = AdapterControlScope.TASK,
                detail = "RetryGroupNode(delay=${node.delay})"
            )
        }
        if (!node.backoff.equals("fixed", ignoreCase = true)) {
            requirements += requirement(
                family = AdapterControlFamily.RETRY,
                semantic = "retry.backoff.variable",
                subject = node.id,
                scope = AdapterControlScope.TASK,
                detail = "RetryGroupNode(backoff=${node.backoff})"
            )
        }
    }

    private fun addCompensationRequirements(
        node: TryPlanNode,
        canonicalFlowHandler: Boolean,
        requirements: MutableList<AdapterControlRequirement>
    ) {
        if (node.errorHandler.isEmpty()) return
        val detached = node.body.isEmpty() && !canonicalFlowHandler
        requirements += requirement(
            family = AdapterControlFamily.COMPENSATION,
            semantic = if (detached) "compensation.detached-error-handler" else "compensation.error-handler",
            subject = node.id,
            scope = AdapterControlScope.WORKFLOW,
            detail = if (detached) {
                "TryPlanNode has an error handler but no protected body or canonical flow-level boundary"
            } else {
                "TryPlanNode(errorHandler=${node.errorHandler.size})"
            }
        )
        val containsRollback = flatten(node.errorHandler)
            .filterIsInstance<TaskNode>()
            .any { it.semanticCapability == "ROLLBACK" }
        if (containsRollback) {
            requirements += requirement(
                family = AdapterControlFamily.COMPENSATION,
                semantic = "compensation.rollback",
                subject = node.id,
                scope = AdapterControlScope.WORKFLOW,
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
                else -> "scheduling.${canonicalId(schedule.kind)}"
            }
            requirements += requirement(
                family = AdapterControlFamily.SCHEDULING,
                semantic = semantic,
                subject = trigger.id,
                scope = AdapterControlScope.TRIGGER,
                detail = "PlanSchedule(kind=${schedule.kind}, expression=${schedule.expression})"
            )
            if (!schedule.timezone.isNullOrBlank()) {
                requirements += requirement(
                    family = AdapterControlFamily.SCHEDULING,
                    semantic = "scheduling.timezone",
                    subject = trigger.id,
                    scope = AdapterControlScope.TRIGGER,
                    detail = "PlanSchedule(timezone=${schedule.timezone})"
                )
            }
            if (trigger.params.keys.any { it in SCHEDULER_CONCURRENCY_KEYS }) {
                requirements += requirement(
                    family = AdapterControlFamily.SCHEDULING,
                    semantic = "scheduling.concurrency",
                    subject = trigger.id,
                    scope = AdapterControlScope.TRIGGER,
                    detail = "Trigger declares scheduler concurrency policy"
                )
            }
            if (trigger.params.keys.any { it in SCHEDULER_CATCH_UP_KEYS }) {
                requirements += requirement(
                    family = AdapterControlFamily.SCHEDULING,
                    semantic = "scheduling.catch-up",
                    subject = trigger.id,
                    scope = AdapterControlScope.TRIGGER,
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
                    semantic = "retry.unspecified",
                    subject = "policy:${policy.name}",
                    scope = AdapterControlScope.UNSPECIFIED,
                    detail = "Source RETRY policy preserves type and name but not an exact attempt, delay, backoff or scope contract",
                    completeness = AdapterControlRequirementCompleteness.PRESERVED_UNSPECIFIED
                )
                "TIMEOUT" -> requirements += requirement(
                    family = AdapterControlFamily.TIMEOUT,
                    semantic = "timeout.unspecified",
                    subject = "policy:${policy.name}",
                    scope = AdapterControlScope.UNSPECIFIED,
                    detail = "Source TIMEOUT policy preserves type and name but not an exact duration or scope contract",
                    completeness = AdapterControlRequirementCompleteness.PRESERVED_UNSPECIFIED
                )
            }
        }
        if (plan.sourceIntent?.failure?.rollback == true) {
            requirements += requirement(
                family = AdapterControlFamily.COMPENSATION,
                semantic = "compensation.rollback",
                subject = "failure.rollback",
                scope = AdapterControlScope.WORKFLOW,
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
        if (claim.semantics.supported.isNotEmpty() && claim.ownership == AdapterControlOwnership.NONE) {
            finding(
                findings,
                "CONTROL_SUPPORTED_WITHOUT_OWNER",
                target,
                claim.family.name,
                "Supported control semantics require an explicit enforcement owner."
            )
        }
        if (AdapterControlScope.UNSPECIFIED in claim.scopes) {
            finding(
                findings,
                "CONTROL_CLAIM_SCOPE_UNSPECIFIED",
                target,
                claim.family.name,
                "Evidence claims must declare concrete supported or unsupported scopes."
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
        if (claim.semantics.supported.isNotEmpty() && fileParts.none(::isImplementationEvidence)) {
            finding(
                findings,
                "CONTROL_SUPPORTED_IMPLEMENTATION_EVIDENCE_MISSING",
                target,
                claim.family.name,
                "Supported semantics require at least one independent src/main implementation evidence reference."
            )
        }
        if (claim.semantics.supported.isNotEmpty() && fileParts.none(::isBehaviorEvidence)) {
            finding(
                findings,
                "CONTROL_SUPPORTED_BEHAVIOR_EVIDENCE_MISSING",
                target,
                claim.family.name,
                "Supported semantics require at least one independent src/test behavioral evidence reference."
            )
        }
    }

    private fun isImplementationEvidence(path: String): Boolean = path.startsWith("src/main/")

    private fun isBehaviorEvidence(path: String): Boolean = path.startsWith("src/test/")

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
        scope: AdapterControlScope,
        detail: String,
        completeness: AdapterControlRequirementCompleteness = AdapterControlRequirementCompleteness.COMPLETE
    ): AdapterControlRequirement = AdapterControlRequirement(
        id = "adapter-control.$semantic.${canonicalId(subject)}",
        family = family,
        semantic = semantic,
        subject = subject,
        scope = scope,
        detail = detail,
        completeness = completeness
    )

    private fun assessmentMetadata(assessment: AdapterControlAssessment): Map<String, String> = mapOf(
        "adapterControlEvidenceVersion" to AdapterControlMaterializationLoader.SUPPORTED_VERSION,
        "adapterControlDecision" to assessment.decision.name,
        "adapterControlRequirementCount" to assessment.requirements.size.toString(),
        "adapterControlBlockerCount" to assessment.blockingRequirementIds.size.toString()
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
