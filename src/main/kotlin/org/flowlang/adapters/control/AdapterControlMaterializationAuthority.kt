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
import org.flowlang.serialization.FlowYaml
import org.flowlang.targets.builtin.BuiltInTargetProjections

enum class AdapterControlFamily {
    APPROVAL,
    RETRY,
    TIMEOUT,
    COMPENSATION,
    SCHEDULING
}

enum class AdapterControlClaimStatus {
    SUPPORTED,
    PARTIAL,
    UNSUPPORTED,
    UNKNOWN
}

enum class AdapterControlOwnership {
    WORKFLOW_DEFINITION,
    TARGET_RESOURCE,
    EXTERNAL_CONTROLLER,
    NONE
}

enum class AdapterControlScope {
    STEP,
    TASK,
    JOB,
    WORKFLOW,
    TRIGGER,
    ENVIRONMENT,
    RESOURCE
}

data class AdapterControlSemanticPartition(
    val supported: Set<String>,
    val unsupported: Map<String, String>,
    val unknown: Map<String, String>
) {
    val all: Set<String> get() = supported + unsupported.keys + unknown.keys
}

data class AdapterControlClaim(
    val family: AdapterControlFamily,
    val status: AdapterControlClaimStatus,
    val mechanism: String,
    val ownership: AdapterControlOwnership,
    val scopes: Set<AdapterControlScope>,
    val semantics: AdapterControlSemanticPartition,
    val evidenceReferences: List<String>,
    val platformReferences: List<String>,
    val prerequisites: List<String>,
    val limitations: List<String>
)

data class AdapterControlTargetRecord(
    val target: String,
    val claims: List<AdapterControlClaim>
)

data class AdapterControlMaterializationDocument(
    val version: String,
    val targets: List<AdapterControlTargetRecord>
)

data class AdapterControlMaterializationFinding(
    val code: String,
    val target: String,
    val family: String,
    val message: String
)

data class AdapterControlMaterializationReport(
    val reportVersion: String = "1.0",
    val status: String,
    val findings: List<AdapterControlMaterializationFinding>,
    val targetCount: Int,
    val claimCount: Int
)

object AdapterControlSemanticContract {
    val byFamily: Map<AdapterControlFamily, Set<String>> = mapOf(
        AdapterControlFamily.APPROVAL to setOf(
            "approval.manual.inline",
            "approval.environment.resource",
            "approval.external"
        ),
        AdapterControlFamily.RETRY to setOf(
            "retry.attempt-limit",
            "retry.delay.fixed",
            "retry.backoff.variable",
            "retry.failure-filter",
            "retry.cancellation"
        ),
        AdapterControlFamily.TIMEOUT to setOf(
            "timeout.step",
            "timeout.task",
            "timeout.workflow",
            "timeout.per-attempt",
            "timeout.cumulative"
        ),
        AdapterControlFamily.COMPENSATION to setOf(
            "compensation.error-handler",
            "compensation.finally",
            "compensation.rollback",
            "compensation.always-run"
        ),
        AdapterControlFamily.SCHEDULING to setOf(
            "scheduling.cron",
            "scheduling.interval",
            "scheduling.calendar",
            "scheduling.timezone",
            "scheduling.concurrency",
            "scheduling.catch-up"
        )
    )
}

object AdapterControlMaterializationLoader {
    const val PATH = "adapters/controls/builtin-control-materialization.yaml"

    fun load(rootDir: File = File(".")): AdapterControlMaterializationDocument {
        val file = File(rootDir, PATH)
        require(file.isFile) { "Adapter control materialization manifest is missing: ${file.path}" }
        val root = FlowYaml.readMap(file)
        requireExactKeys(root, ROOT_KEYS, PATH)
        val targets = objectList(root["targets"], "$PATH.targets").mapIndexed { index, raw ->
            parseTarget(raw, "$PATH.targets[$index]")
        }
        require(targets.isNotEmpty()) { "$PATH.targets must not be empty." }
        return AdapterControlMaterializationDocument(
            version = text(root, "version", PATH),
            targets = targets
        )
    }

    private fun parseTarget(raw: Map<String, Any?>, path: String): AdapterControlTargetRecord {
        requireExactKeys(raw, TARGET_KEYS, path)
        return AdapterControlTargetRecord(
            target = text(raw, "target", path),
            claims = objectList(raw["claims"], "$path.claims").mapIndexed { index, claim ->
                parseClaim(claim, "$path.claims[$index]")
            }.also { require(it.isNotEmpty()) { "$path.claims must not be empty." } }
        )
    }

    private fun parseClaim(raw: Map<String, Any?>, path: String): AdapterControlClaim {
        requireExactKeys(raw, CLAIM_KEYS, path)
        val semantics = map(raw["semantics"], "$path.semantics")
        requireExactKeys(semantics, SEMANTIC_KEYS, "$path.semantics")
        return AdapterControlClaim(
            family = enumValue(text(raw, "family", path), "$path.family"),
            status = enumValue(text(raw, "status", path), "$path.status"),
            mechanism = text(raw, "mechanism", path),
            ownership = enumValue(text(raw, "ownership", path), "$path.ownership"),
            scopes = enumList(raw["scopes"], "$path.scopes"),
            semantics = AdapterControlSemanticPartition(
                supported = stringList(semantics["supported"], "$path.semantics.supported").toSet(),
                unsupported = reasonMap(semantics["unsupported"], "$path.semantics.unsupported"),
                unknown = reasonMap(semantics["unknown"], "$path.semantics.unknown")
            ),
            evidenceReferences = stringList(raw["evidenceReferences"], "$path.evidenceReferences", required = true),
            platformReferences = stringList(raw["platformReferences"], "$path.platformReferences"),
            prerequisites = stringList(raw["prerequisites"], "$path.prerequisites"),
            limitations = stringList(raw["limitations"], "$path.limitations", required = true)
        )
    }

    private inline fun <reified T : Enum<T>> enumValue(value: String, path: String): T =
        runCatching { enumValueOf<T>(value) }
            .getOrElse { error("$path has unknown value '$value'; expected ${enumValues<T>().joinToString()}.") }

    private inline fun <reified T : Enum<T>> enumList(value: Any?, path: String): Set<T> =
        stringList(value, path, required = true).map { enumValue<T>(it, path) }.toSet()

    private fun reasonMap(value: Any?, path: String): Map<String, String> =
        map(value, path).mapValues { (key, raw) ->
            require(key.isNotBlank()) { "$path contains a blank semantic key." }
            (raw as? String)?.takeIf(String::isNotBlank)
                ?: error("$path.$key must be non-blank text.")
        }

    private fun requireExactKeys(value: Map<String, Any?>, keys: Set<String>, path: String) {
        val unknown = value.keys - keys
        val missing = keys - value.keys
        require(unknown.isEmpty()) { "$path has unknown fields: ${unknown.sorted().joinToString()}." }
        require(missing.isEmpty()) { "$path is missing fields: ${missing.sorted().joinToString()}." }
    }

    private fun text(value: Map<String, Any?>, key: String, path: String): String =
        (value[key] as? String)?.takeIf(String::isNotBlank)
            ?: error("$path.$key must be non-blank text.")

    @Suppress("UNCHECKED_CAST")
    private fun map(value: Any?, path: String): Map<String, Any?> =
        value as? Map<String, Any?> ?: error("$path must be a map.")

    @Suppress("UNCHECKED_CAST")
    private fun objectList(value: Any?, path: String): List<Map<String, Any?>> = when (value) {
        is List<*> -> value.mapIndexed { index, item ->
            item as? Map<String, Any?> ?: error("$path[$index] must be a map.")
        }
        else -> error("$path must be a list.")
    }

    private fun stringList(value: Any?, path: String, required: Boolean = false): List<String> = when (value) {
        is List<*> -> value.mapIndexed { index, item ->
            (item as? String)?.takeIf(String::isNotBlank)
                ?: error("$path[$index] must be non-blank text.")
        }.also { list ->
            require(!required || list.isNotEmpty()) { "$path must not be empty." }
            require(list.size == list.toSet().size) { "$path must not contain duplicates." }
        }
        else -> error("$path must be a list.")
    }

    private val ROOT_KEYS = setOf("version", "targets")
    private val TARGET_KEYS = setOf("target", "claims")
    private val CLAIM_KEYS = setOf(
        "family", "status", "mechanism", "ownership", "scopes", "semantics",
        "evidenceReferences", "platformReferences", "prerequisites", "limitations"
    )
    private val SEMANTIC_KEYS = setOf("supported", "unsupported", "unknown")
}

class AdapterControlMaterializationAuthority(
    private val rootDir: File = File("."),
    private val targets: Map<String, TargetCapability>,
    private val projections: TargetProjectionRegistry = BuiltInTargetProjections.registry
) {
    private val document: AdapterControlMaterializationDocument by lazy {
        AdapterControlMaterializationLoader.load(rootDir)
    }

    fun analyze(input: AdapterControlMaterializationDocument = document): AdapterControlMaterializationReport {
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
                evaluateClaim(record.target, claim, portfolioRecord?.role, portfolioRecord?.supportClass, findings)
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
                    requirement.id,
                    AdapterControlEvidenceStatus.UNKNOWN,
                    "No control-family claim exists for ${requirement.family}."
                )
                requirement.semantic in claim.semantics.supported -> AdapterControlEvidence(
                    requirement.id,
                    AdapterControlEvidenceStatus.SATISFIED,
                    "${claim.mechanism} Evidence: ${claim.evidenceReferences.joinToString()}"
                )
                requirement.semantic in claim.semantics.unsupported -> AdapterControlEvidence(
                    requirement.id,
                    AdapterControlEvidenceStatus.UNSUPPORTED,
                    claim.semantics.unsupported.getValue(requirement.semantic)
                )
                requirement.semantic in claim.semantics.unknown -> AdapterControlEvidence(
                    requirement.id,
                    AdapterControlEvidenceStatus.UNKNOWN,
                    claim.semantics.unknown.getValue(requirement.semantic)
                )
                else -> AdapterControlEvidence(
                    requirement.id,
                    AdapterControlEvidenceStatus.UNKNOWN,
                    "Control semantic '${requirement.semantic}' is absent from the declared partition."
                )
            }
        }
        val blockers = evidence.filter { it.status != AdapterControlEvidenceStatus.SATISFIED }
            .map(AdapterControlEvidence::requirementId)
        return AdapterControlAssessment(
            target = target,
            requirements = requirements,
            evidence = evidence,
            decision = if (blockers.isEmpty()) AdapterControlDecision.MATCHED else AdapterControlDecision.BLOCKED,
            blockingRequirementIds = blockers
        )
    }

    fun requireMatched(plan: ExecutionPlan, target: String): AdapterControlAssessment {
        val assessment = assess(plan, target)
        if (assessment.decision != AdapterControlDecision.MATCHED) {
            throw UnresolvedAdapterControlMaterializationException(assessment)
        }
        return assessment
    }

    fun reconcileDiagnostic(manifest: TargetManifest, assessment: AdapterControlAssessment): TargetManifest {
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
                message = "Adapter control requirement '${requirement.id}' is ${evidence.status.name.lowercase()}: ${evidence.detail}"
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
                    AdapterControlFamily.APPROVAL,
                    "approval.manual.inline",
                    node.id,
                    "ApprovalNode(mode=${node.mode})"
                )
                is RetryGroupNode -> {
                    requirements += requirement(
                        AdapterControlFamily.RETRY,
                        "retry.attempt-limit",
                        node.id,
                        "RetryGroupNode(max=${node.max})"
                    )
                    if (!node.delay.isZeroDuration()) {
                        requirements += requirement(
                            AdapterControlFamily.RETRY,
                            "retry.delay.fixed",
                            node.id,
                            "RetryGroupNode(delay=${node.delay})"
                        )
                    }
                    if (!node.backoff.equals("fixed", ignoreCase = true)) {
                        requirements += requirement(
                            AdapterControlFamily.RETRY,
                            "retry.backoff.variable",
                            node.id,
                            "RetryGroupNode(backoff=${node.backoff})"
                        )
                    }
                }
                is TryPlanNode -> if (node.errorHandler.isNotEmpty()) {
                    requirements += requirement(
                        AdapterControlFamily.COMPENSATION,
                        "compensation.error-handler",
                        node.id,
                        "TryPlanNode(errorHandler=${node.errorHandler.size})"
                    )
                    if (flatten(node.errorHandler).filterIsInstance<TaskNode>().any { it.semanticCapability == "ROLLBACK" }) {
                        requirements += requirement(
                            AdapterControlFamily.COMPENSATION,
                            "compensation.rollback",
                            node.id,
                            "TryPlanNode contains canonical rollback work"
                        )
                    }
                }
                else -> Unit
            }
        }

        plan.triggers.forEach { trigger ->
            val schedule = trigger.schedule ?: return@forEach
            val semantic = when (schedule.kind.uppercase()) {
                "CRON" -> "scheduling.cron"
                "INTERVAL" -> "scheduling.interval"
                "CALENDAR" -> "scheduling.calendar"
                else -> "scheduling.${schedule.kind.lowercase()}"
            }
            requirements += requirement(
                AdapterControlFamily.SCHEDULING,
                semantic,
                trigger.id,
                "PlanSchedule(kind=${schedule.kind}, expression=${schedule.expression})"
            )
            if (!schedule.timezone.isNullOrBlank()) {
                requirements += requirement(
                    AdapterControlFamily.SCHEDULING,
                    "scheduling.timezone",
                    trigger.id,
                    "PlanSchedule(timezone=${schedule.timezone})"
                )
            }
            if (trigger.params.keys.any { it in setOf("concurrency", "concurrencyPolicy") }) {
                requirements += requirement(
                    AdapterControlFamily.SCHEDULING,
                    "scheduling.concurrency",
                    trigger.id,
                    "Trigger declares scheduler concurrency policy"
                )
            }
            if (trigger.params.keys.any { it in setOf("catchUp", "startingDeadlineSeconds") }) {
                requirements += requirement(
                    AdapterControlFamily.SCHEDULING,
                    "scheduling.catch-up",
                    trigger.id,
                    "Trigger declares missed-run behavior"
                )
            }
        }

        plan.sourceIntent?.policies.orEmpty().forEach { policy ->
            when (policy.type.uppercase()) {
                "RETRY" -> requirements += requirement(
                    AdapterControlFamily.RETRY,
                    "retry.attempt-limit",
                    "policy:${policy.name}",
                    "Source RETRY policy is preserved but requires concrete execution evidence"
                )
                "TIMEOUT" -> requirements += requirement(
                    AdapterControlFamily.TIMEOUT,
                    "timeout.workflow",
                    "policy:${policy.name}",
                    "Source TIMEOUT policy is preserved but requires concrete execution evidence"
                )
            }
        }
        if (plan.sourceIntent?.failure?.rollback == true) {
            requirements += requirement(
                AdapterControlFamily.COMPENSATION,
                "compensation.rollback",
                "failure.rollback",
                "Source failure.rollback=true"
            )
        }

        return requirements.distinctBy(AdapterControlRequirement::id).sortedBy(AdapterControlRequirement::id)
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
        val duplicatePartitions = (claim.semantics.supported intersect claim.semantics.unsupported.keys) +
            (claim.semantics.supported intersect claim.semantics.unknown.keys) +
            (claim.semantics.unsupported.keys intersect claim.semantics.unknown.keys)
        if (duplicatePartitions.isNotEmpty()) {
            finding(findings, "CONTROL_SEMANTIC_PARTITION_OVERLAP", target, claim.family.name, duplicatePartitions.sorted().joinToString())
        }

        val statusValid = when (claim.status) {
            AdapterControlClaimStatus.SUPPORTED -> claim.semantics.supported == expected
            AdapterControlClaimStatus.PARTIAL -> claim.semantics.supported.isNotEmpty() && claim.semantics.supported != expected
            AdapterControlClaimStatus.UNSUPPORTED -> claim.semantics.supported.isEmpty() && claim.semantics.unknown.isEmpty()
            AdapterControlClaimStatus.UNKNOWN -> claim.semantics.supported.isEmpty() && claim.semantics.unknown.isNotEmpty()
        }
        if (!statusValid) {
            finding(findings, "CONTROL_STATUS_PARTITION_MISMATCH", target, claim.family.name, "Status ${claim.status} contradicts the semantic partition.")
        }

        val provider = projections.providerFor(target)
        if (claim.semantics.supported.isNotEmpty() && provider == null) {
            finding(findings, "CONTROL_SUPPORTED_WITHOUT_PROVIDER", target, claim.family.name, "Supported control semantics require a composed provider.")
        }
        if (role == AdapterPortfolioRole.SEMANTIC_REFERENCE && claim.status != AdapterControlClaimStatus.UNKNOWN) {
            finding(findings, "CONTROL_SEMANTIC_REFERENCE_PROMOTED", target, claim.family.name, "Semantic references must remain UNKNOWN.")
        }
        if (supportClass == AdapterSupportClass.PROFILE_ONLY && claim.status != AdapterControlClaimStatus.UNKNOWN) {
            finding(findings, "CONTROL_PROFILE_ONLY_PROMOTED", target, claim.family.name, "Profile-only targets must remain UNKNOWN until a provider is composed.")
        }
        if ("approval.manual.inline" in claim.semantics.supported) {
            val approvalDefinition = provider?.nativeProjectionCatalog?.approvalDefinitions
                ?.singleOrNull { it.capability == "approval.manual" }
            if (approvalDefinition == null) {
                finding(findings, "CONTROL_APPROVAL_PROVIDER_EVIDENCE_MISSING", target, claim.family.name, "Manual inline approval has no provider-owned payload definition.")
            }
        }

        claim.evidenceReferences.forEach { reference ->
            val filePart = reference.substringBefore('#')
            when {
                filePart == AdapterControlMaterializationLoader.PATH || filePart == "targets/builtin-targets.yaml" ->
                    finding(findings, "CONTROL_EVIDENCE_SELF_REFERENTIAL", target, claim.family.name, "Evidence cannot cite '$filePart'.")
                !File(rootDir, filePart).isFile ->
                    finding(findings, "CONTROL_EVIDENCE_UNRESOLVED", target, claim.family.name, "Evidence file does not exist: $reference")
            }
        }
        claim.platformReferences.forEach { reference ->
            if (!reference.startsWith("https://")) {
                finding(findings, "CONTROL_PLATFORM_REFERENCE_INVALID", target, claim.family.name, "Platform reference must be HTTPS: $reference")
            }
        }
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
            is MatchPlanNode -> node.cases.flatMap { flatten(it.steps) } + flatten(node.errorCase) + flatten(node.defaultSteps)
            is RetryGroupNode -> flatten(node.body)
            is TryPlanNode -> flatten(node.body) + flatten(node.errorHandler)
            else -> emptyList()
        }
    }

    private fun String.isZeroDuration(): Boolean = trim().lowercase() in setOf("0", "0s", "0m", "0h", "0ms")

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
}

data class AdapterControlRequirement(
    val id: String,
    val family: AdapterControlFamily,
    val semantic: String,
    val subject: String,
    val detail: String
)

enum class AdapterControlEvidenceStatus {
    SATISFIED,
    UNSUPPORTED,
    UNKNOWN
}

data class AdapterControlEvidence(
    val requirementId: String,
    val status: AdapterControlEvidenceStatus,
    val detail: String
)

enum class AdapterControlDecision {
    MATCHED,
    BLOCKED
}

data class AdapterControlAssessment(
    val target: String,
    val requirements: List<AdapterControlRequirement>,
    val evidence: List<AdapterControlEvidence>,
    val decision: AdapterControlDecision,
    val blockingRequirementIds: List<String>
)

class UnresolvedAdapterControlMaterializationException(
    val assessment: AdapterControlAssessment
) : IllegalStateException(
    "Target '${assessment.target}' cannot materialize required controls: " +
        assessment.blockingRequirementIds.joinToString()
)
