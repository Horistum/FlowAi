package org.flowlang.adapters.maturity

import java.io.File
import org.flowlang.adapters.portfolio.AdapterExecutableReferencePromotion
import org.flowlang.adapters.portfolio.AdapterExecutableReferencePromotionLoader
import org.flowlang.adapters.portfolio.AdapterPortfolioLoader
import org.flowlang.adapters.portfolio.AdapterPortfolioRecord
import org.flowlang.adapters.portfolio.AdapterPortfolioRole
import org.flowlang.adapters.portfolio.AdapterSupportClass
import org.flowlang.capabilities.SupportLevel
import org.flowlang.capabilities.TargetCapability
import org.flowlang.capabilities.TargetProjectionMode
import org.flowlang.adapters.contract.AdapterCatalog
import org.flowlang.generators.manifest.TargetProjectionProvider
import org.flowlang.generators.manifest.providerFor
import org.flowlang.generators.manifest.requireProvider
import org.flowlang.generators.manifest.TargetStructuralProjectionKind
import org.flowlang.serialization.FlowYaml

enum class TargetMaturityStage {
    DECLARED,
    ANALYZABLE,
    RENDERABLE,
    EXECUTABLE,
    BEHAVIORALLY_CERTIFIED
}

enum class TargetMaturityEvidenceSource {
    HISTORICAL_PORTFOLIO,
    EXECUTABLE_REFERENCE_PROMOTION;

    companion object {
        fun parse(raw: String): TargetMaturityEvidenceSource = when (
            raw.trim().lowercase().replace('-', '_')
        ) {
            "historical_portfolio" -> HISTORICAL_PORTFOLIO
            "executable_reference_promotion" -> EXECUTABLE_REFERENCE_PROMOTION
            else -> error("Unknown target maturity evidence source '$raw'.")
        }
    }
}

data class AdapterTargetMaturityEvidenceScope(
    val target: String,
    val scopeId: String,
    val scenarioId: String,
    val source: TargetMaturityEvidenceSource,
    val sourceReference: String,
    val snapshotReference: String,
    val implementationEvidenceReferences: List<String>,
    val behavioralEvidenceReferences: List<String>,
    val limitations: List<String>
)

data class AdapterTargetMaturityEvidenceDocument(
    val kind: String,
    val version: String,
    val scopes: List<AdapterTargetMaturityEvidenceScope>
)

object AdapterTargetMaturityEvidenceLoader {
    const val PATH = "adapters/portfolio/target-maturity-evidence.yaml"
    const val KIND = "FlowAdapterTargetMaturityEvidence"
    const val VERSION = "1.0"

    fun load(rootDir: File = File(".")): AdapterTargetMaturityEvidenceDocument {
        val file = File(rootDir, PATH)
        require(file.isFile) { "Target maturity evidence document is missing: ${file.path}" }
        val document = FlowYaml.readMap(file)
        requireExactKeys(document, ROOT_KEYS, PATH)
        val scopes = document.mapList("scopes").mapIndexed { index, scope ->
            val path = "$PATH.scopes[$index]"
            requireExactKeys(scope, SCOPE_KEYS, path)
            AdapterTargetMaturityEvidenceScope(
                target = scope.requiredString("target", path),
                scopeId = scope.requiredString("scopeId", path),
                scenarioId = scope.requiredString("scenarioId", path),
                source = TargetMaturityEvidenceSource.parse(scope.requiredString("source", path)),
                sourceReference = scope.requiredString("sourceReference", path),
                snapshotReference = scope.requiredString("snapshotReference", path),
                implementationEvidenceReferences = scope.stringList(
                    "implementationEvidenceReferences",
                    path
                ),
                behavioralEvidenceReferences = scope.stringList(
                    "behavioralEvidenceReferences",
                    path
                ),
                limitations = scope.stringList("limitations", path)
            )
        }
        require(scopes.isNotEmpty()) { "$PATH.scopes must not be empty." }
        val identities = scopes.map { "${it.target}::${it.scopeId}" }
        require(identities.size == identities.toSet().size) {
            "$PATH contains duplicate target/scope identities."
        }
        return AdapterTargetMaturityEvidenceDocument(
            kind = document.requiredString("kind", PATH),
            version = document.requiredString("version", PATH),
            scopes = scopes
        )
    }

    private fun requireExactKeys(value: Map<String, Any?>, expected: Set<String>, path: String) {
        val unknown = value.keys - expected
        val missing = expected - value.keys
        require(unknown.isEmpty()) { "$path has unknown fields: ${unknown.sorted()}." }
        require(missing.isEmpty()) { "$path is missing fields: ${missing.sorted()}." }
    }

    private fun Map<String, Any?>.requiredString(key: String, path: String): String =
        (get(key) as? String)?.takeIf(String::isNotBlank)
            ?: error("$path.$key must be non-blank text.")

    private fun Map<String, Any?>.stringList(key: String, path: String): List<String> =
        (get(key) as? Iterable<*>)?.mapIndexed { index, value ->
            (value as? String)?.takeIf(String::isNotBlank)
                ?: error("$path.$key[$index] must be non-blank text.")
        }?.also { values ->
            require(values.isNotEmpty()) { "$path.$key must not be empty." }
            require(values.size == values.toSet().size) { "$path.$key contains duplicates." }
        } ?: error("$path.$key must be a list.")

    private fun Map<String, Any?>.mapList(key: String): List<Map<String, Any?>> =
        (get(key) as? Iterable<*>)?.mapIndexed { index, value ->
            (value as? Map<*, *>)?.entries?.associate { it.key.toString() to it.value }
                ?: error("$PATH.$key[$index] must be a mapping.")
        } ?: error("$PATH.$key must be a list.")

    private val ROOT_KEYS = setOf("kind", "version", "scopes")
    private val SCOPE_KEYS = setOf(
        "target",
        "scopeId",
        "scenarioId",
        "source",
        "sourceReference",
        "snapshotReference",
        "implementationEvidenceReferences",
        "behavioralEvidenceReferences",
        "limitations"
    )
}

data class TargetMaturityFinding(
    val code: String,
    val target: String,
    val scopeId: String?,
    val message: String
)

data class TargetMaturityScopeAssessment(
    val target: String,
    val scopeId: String,
    val scenarioId: String,
    val source: TargetMaturityEvidenceSource,
    val stages: List<TargetMaturityStage>,
    val highestStage: TargetMaturityStage?,
    val valid: Boolean,
    val evidenceReferences: List<String>,
    val limitations: List<String>
)

data class TargetMaturityAssessment(
    val target: String,
    val role: String,
    val targetWideStages: List<TargetMaturityStage>,
    val highestTargetWideStage: TargetMaturityStage?,
    val scopes: List<TargetMaturityScopeAssessment>
)

data class AdapterTargetMaturityReport(
    val reportVersion: String = "1.0",
    val status: String,
    val assessments: List<TargetMaturityAssessment>,
    val findings: List<TargetMaturityFinding>
)

/**
 * Current distribution maturity derived from immutable historical evidence plus
 * explicitly scoped post-history evidence.
 *
 * Target-wide renderability and scenario-scoped executability are deliberately
 * separate. One executable scenario never promotes a whole target adapter.
 */
class AdapterTargetMaturityPublisher(
    private val rootDir: File,
    private val targets: Map<String, TargetCapability>,
    private val projections: AdapterCatalog<TargetProjectionProvider>
) {
    fun analyze(): AdapterTargetMaturityReport {
        val document = runCatching { AdapterTargetMaturityEvidenceLoader.load(rootDir) }
            .getOrElse { error ->
                return AdapterTargetMaturityReport(
                    status = "FAIL",
                    assessments = emptyList(),
                    findings = listOf(
                        finding(
                            "TARGET_MATURITY_DOCUMENT_INVALID",
                            "maturity",
                            null,
                            error.message ?: error.javaClass.simpleName
                        )
                    )
                )
            }
        return evaluate(document)
    }

    fun evaluate(document: AdapterTargetMaturityEvidenceDocument): AdapterTargetMaturityReport {
        val findings = mutableListOf<TargetMaturityFinding>()
        if (document.kind != AdapterTargetMaturityEvidenceLoader.KIND) {
            findings += finding(
                "TARGET_MATURITY_KIND_INVALID",
                "maturity",
                null,
                "Expected kind '${AdapterTargetMaturityEvidenceLoader.KIND}', found '${document.kind}'."
            )
        }
        if (document.version != AdapterTargetMaturityEvidenceLoader.VERSION) {
            findings += finding(
                "TARGET_MATURITY_VERSION_INVALID",
                "maturity",
                null,
                "Expected version '${AdapterTargetMaturityEvidenceLoader.VERSION}', found '${document.version}'."
            )
        }
        val duplicateScopes = document.scopes
            .groupingBy { "${it.target}::${it.scopeId}" }
            .eachCount()
            .filterValues { it > 1 }
            .keys
            .sorted()
        duplicateScopes.forEach { identity ->
            findings += finding(
                "TARGET_MATURITY_SCOPE_DUPLICATE",
                identity.substringBefore("::"),
                identity.substringAfter("::"),
                "Target maturity scope '$identity' is declared more than once."
            )
        }

        val portfolioResult = runCatching { AdapterPortfolioLoader.load(rootDir) }
        val portfolio = portfolioResult.getOrNull()
        portfolioResult.exceptionOrNull()?.let { error ->
            findings += finding(
                "TARGET_MATURITY_PORTFOLIO_INVALID",
                "portfolio",
                null,
                error.message ?: error.javaClass.simpleName
            )
        }
        val promotionResult = runCatching { AdapterExecutableReferencePromotionLoader.load(rootDir) }
        val promotions = promotionResult.getOrNull()
        promotionResult.exceptionOrNull()?.let { error ->
            findings += finding(
                "TARGET_MATURITY_PROMOTIONS_INVALID",
                "promotions",
                null,
                error.message ?: error.javaClass.simpleName
            )
        }

        val portfolioByTarget = portfolio?.records.orEmpty().associateBy { it.target }
        val promotionsByTarget = promotions?.promotions.orEmpty().associateBy { it.target }
        val allTargets = (
            targets.keys +
                portfolioByTarget.keys +
                document.scopes.map(AdapterTargetMaturityEvidenceScope::target)
            ).toSortedSet()

        (targets.keys - portfolioByTarget.keys).sorted().forEach { target ->
            findings += finding(
                "TARGET_MATURITY_PORTFOLIO_RECORD_MISSING",
                target,
                null,
                "Target registry entry has no immutable portfolio record."
            )
        }
        (portfolioByTarget.keys - targets.keys).sorted().forEach { target ->
            findings += finding(
                "TARGET_MATURITY_REGISTRY_TARGET_MISSING",
                target,
                null,
                "Immutable portfolio record has no current target registry entry."
            )
        }

        allTargets.forEach { targetName ->
            reconcileTargetClaims(
                targetName = targetName,
                target = targets[targetName],
                portfolio = portfolioByTarget[targetName],
                findings = findings
            )
        }

        val scopesByTarget = document.scopes.groupBy { it.target }
        val assessments = allTargets.map { targetName ->
            val target = targets[targetName]
            val portfolioRecord = portfolioByTarget[targetName]
            val provider = projections.providerFor(targetName)
            val targetWideStages = targetWideStages(target, provider != null)
            val scoped = scopesByTarget[targetName].orEmpty()
                .sortedBy { it.scopeId }
                .map { scope ->
                    evaluateScope(
                        scope = scope,
                        target = target,
                        portfolio = portfolioRecord,
                        promotion = promotionsByTarget[targetName],
                        targetWideStages = targetWideStages,
                        findings = findings
                    )
                }
            TargetMaturityAssessment(
                target = targetName,
                role = portfolioRecord?.role?.name ?: "UNRECORDED",
                targetWideStages = targetWideStages,
                highestTargetWideStage = targetWideStages.lastOrNull(),
                scopes = scoped
            )
        }

        return AdapterTargetMaturityReport(
            status = if (findings.isEmpty()) "PASS" else "FAIL",
            assessments = assessments,
            findings = findings.sortedWith(
                compareBy(
                    TargetMaturityFinding::target,
                    { it.scopeId.orEmpty() },
                    TargetMaturityFinding::code,
                    TargetMaturityFinding::message
                )
            )
        )
    }

    private fun reconcileTargetClaims(
        targetName: String,
        target: TargetCapability?,
        portfolio: AdapterPortfolioRecord?,
        findings: MutableList<TargetMaturityFinding>
    ) {
        if (target == null || portfolio == null) return
        val provider = projections.providerFor(targetName)
        val catalog = provider?.nativeProjectionCatalog

        if (
            portfolio.role == AdapterPortfolioRole.TARGET_ADAPTER &&
            portfolio.supportClass == AdapterSupportClass.PROFILE_ONLY
        ) {
            if (provider != null) {
                findings += finding(
                    "TARGET_MATURITY_PROFILE_ONLY_PROVIDER_PRESENT",
                    targetName,
                    null,
                    "Profile-only target has a composed projection provider."
                )
            }
            if (target.projectionRules.any { it.mode == TargetProjectionMode.NATIVE }) {
                findings += finding(
                    "TARGET_MATURITY_PROFILE_ONLY_NATIVE_RULE_PRESENT",
                    targetName,
                    null,
                    "Profile-only target declares a native action projection rule."
                )
            }
        }

        if (portfolio.role != AdapterPortfolioRole.TARGET_ADAPTER) return
        TargetStructuralProjectionKind.entries.forEach { kind ->
            val support = structuralSupport(target, kind)
            val owned = catalog?.hasStructuralProjection(kind) == true
            if (support == SupportLevel.SUPPORTED && !owned) {
                findings += finding(
                    "TARGET_MATURITY_SUPPORTED_STRUCTURE_UNOWNED",
                    targetName,
                    null,
                    "Registry declares ${kind.capability} SUPPORTED without matching provider-owned structural evidence."
                )
            }
            if (owned && support != SupportLevel.SUPPORTED) {
                findings += finding(
                    "TARGET_MATURITY_PROVIDER_STRUCTURE_UNDECLARED",
                    targetName,
                    null,
                    "Provider owns ${kind.capability}, but registry status is $support instead of SUPPORTED."
                )
            }
        }
    }

    private fun evaluateScope(
        scope: AdapterTargetMaturityEvidenceScope,
        target: TargetCapability?,
        portfolio: AdapterPortfolioRecord?,
        promotion: AdapterExecutableReferencePromotion?,
        targetWideStages: List<TargetMaturityStage>,
        findings: MutableList<TargetMaturityFinding>
    ): TargetMaturityScopeAssessment {
        val start = findings.size
        val providerAvailable = projections.providerFor(scope.target) != null
        if (target == null) {
            findings += scopeFinding(
                "TARGET_MATURITY_SCOPE_TARGET_UNKNOWN",
                scope,
                "Scoped evidence target is absent from the target registry."
            )
        }
        if (portfolio == null || portfolio.role != AdapterPortfolioRole.TARGET_ADAPTER) {
            findings += scopeFinding(
                "TARGET_MATURITY_SCOPE_ROLE_INVALID",
                scope,
                "Scoped executable maturity requires a target-adapter portfolio record."
            )
        }
        if (!providerAvailable) {
            findings += scopeFinding(
                "TARGET_MATURITY_SCOPE_PROVIDER_MISSING",
                scope,
                "Scoped executable maturity requires a composed projection provider."
            )
        }

        val sourceValid = when (scope.source) {
            TargetMaturityEvidenceSource.HISTORICAL_PORTFOLIO ->
                validateHistoricalSource(scope, portfolio, findings)
            TargetMaturityEvidenceSource.EXECUTABLE_REFERENCE_PROMOTION ->
                validatePromotionSource(scope, promotion, findings)
        }
        val snapshotValid = validateSnapshot(scope, findings)
        val implementationValid = validateEvidenceReferences(
            scope = scope,
            references = scope.implementationEvidenceReferences,
            codePrefix = "TARGET_MATURITY_IMPLEMENTATION_EVIDENCE",
            requiredPathPrefixes = listOf("src/main/"),
            findings = findings
        )
        val behaviorValid = validateEvidenceReferences(
            scope = scope,
            references = scope.behavioralEvidenceReferences,
            codePrefix = "TARGET_MATURITY_BEHAVIOR_EVIDENCE",
            requiredPathPrefixes = listOf("src/test/", "tests/"),
            findings = findings
        )
        if (scope.limitations.isEmpty()) {
            findings += scopeFinding(
                "TARGET_MATURITY_LIMITATION_MISSING",
                scope,
                "Scoped executable maturity must declare at least one limitation."
            )
        }

        val stages = targetWideStages.toMutableList()
        if (
            TargetMaturityStage.RENDERABLE in stages &&
            sourceValid &&
            snapshotValid
        ) {
            stages += TargetMaturityStage.EXECUTABLE
        }
        if (
            stages.lastOrNull() == TargetMaturityStage.EXECUTABLE &&
            implementationValid &&
            behaviorValid &&
            scope.limitations.isNotEmpty()
        ) {
            stages += TargetMaturityStage.BEHAVIORALLY_CERTIFIED
        }
        require(stages == TargetMaturityStage.entries.take(stages.size)) {
            "Target maturity stages must remain cumulative and ordered for ${scope.target}/${scope.scopeId}."
        }

        return TargetMaturityScopeAssessment(
            target = scope.target,
            scopeId = scope.scopeId,
            scenarioId = scope.scenarioId,
            source = scope.source,
            stages = stages,
            highestStage = stages.lastOrNull(),
            valid = findings.size == start,
            evidenceReferences = listOf(scope.sourceReference, scope.snapshotReference) +
                scope.implementationEvidenceReferences + scope.behavioralEvidenceReferences,
            limitations = scope.limitations
        )
    }

    private fun validateHistoricalSource(
        scope: AdapterTargetMaturityEvidenceScope,
        portfolio: AdapterPortfolioRecord?,
        findings: MutableList<TargetMaturityFinding>
    ): Boolean {
        var valid = true
        val expectedReference = "${AdapterPortfolioLoader.PATH}#records.${scope.target}"
        if (scope.sourceReference != expectedReference) {
            findings += scopeFinding(
                "TARGET_MATURITY_HISTORICAL_SOURCE_MISMATCH",
                scope,
                "Historical source must be '$expectedReference'."
            )
            valid = false
        }
        if (
            portfolio == null ||
            portfolio.supportClass != AdapterSupportClass.EXECUTABLE_REFERENCE ||
            scope.snapshotReference !in portfolio.executableEvidence
        ) {
            findings += scopeFinding(
                "TARGET_MATURITY_HISTORICAL_EXECUTABLE_EVIDENCE_MISSING",
                scope,
                "Historical portfolio does not bind this target to the declared executable snapshot."
            )
            valid = false
        }
        return validateRepositoryReference(
            scope,
            scope.sourceReference,
            "TARGET_MATURITY_HISTORICAL_SOURCE",
            findings
        ) && valid
    }

    private fun validatePromotionSource(
        scope: AdapterTargetMaturityEvidenceScope,
        promotion: AdapterExecutableReferencePromotion?,
        findings: MutableList<TargetMaturityFinding>
    ): Boolean {
        var valid = true
        val expectedReference =
            "${AdapterExecutableReferencePromotionLoader.PATH}#promotions.${scope.target}"
        if (scope.sourceReference != expectedReference) {
            findings += scopeFinding(
                "TARGET_MATURITY_PROMOTION_SOURCE_MISMATCH",
                scope,
                "Promotion source must be '$expectedReference'."
            )
            valid = false
        }
        if (
            promotion == null ||
            promotion.supportClass != AdapterSupportClass.EXECUTABLE_REFERENCE ||
            promotion.scenarioId != scope.scenarioId ||
            promotion.snapshotReference != scope.snapshotReference
        ) {
            findings += scopeFinding(
                "TARGET_MATURITY_PROMOTION_EVIDENCE_MISMATCH",
                scope,
                "Executable-reference promotion does not match the declared scenario and snapshot."
            )
            valid = false
        }
        return validateRepositoryReference(
            scope,
            scope.sourceReference,
            "TARGET_MATURITY_PROMOTION_SOURCE",
            findings
        ) && valid
    }

    private fun validateSnapshot(
        scope: AdapterTargetMaturityEvidenceScope,
        findings: MutableList<TargetMaturityFinding>
    ): Boolean {
        if (!validateRepositoryReference(
                scope,
                scope.snapshotReference,
                "TARGET_MATURITY_SNAPSHOT",
                findings
            )) {
            return false
        }
        val file = File(rootDir, scope.snapshotReference.substringBefore('#'))
        val snapshot = runCatching { FlowYaml.readMap(file) }.getOrElse { error ->
            findings += scopeFinding(
                "TARGET_MATURITY_SNAPSHOT_INVALID",
                scope,
                error.message ?: error.javaClass.simpleName
            )
            return false
        }
        var valid = true
        if (snapshot["scenarioId"]?.toString() != scope.scenarioId) {
            findings += scopeFinding(
                "TARGET_MATURITY_SNAPSHOT_SCENARIO_MISMATCH",
                scope,
                "Snapshot scenario does not match '${scope.scenarioId}'."
            )
            valid = false
        }
        if (snapshot["overallState"]?.toString() != "EXECUTABLE" || snapshot["executable"] != true) {
            findings += scopeFinding(
                "TARGET_MATURITY_SNAPSHOT_NOT_EXECUTABLE",
                scope,
                "Snapshot set is not explicitly EXECUTABLE."
            )
            valid = false
        }
        val targetStates = (snapshot["targets"] as? Iterable<*>)?.mapNotNull { value ->
            (value as? Map<*, *>)?.entries?.associate { it.key.toString() to it.value }
        }.orEmpty().filter { it["target"]?.toString() == scope.target }
        if (targetStates.size != 1) {
            findings += scopeFinding(
                "TARGET_MATURITY_SNAPSHOT_TARGET_SCOPE_INVALID",
                scope,
                "Snapshot must contain exactly one matching target state."
            )
            valid = false
        } else {
            val targetState = targetStates.single()
            val executable = targetState["executable"] == true
            val renderMode = targetState["renderMode"]?.toString() == "EXECUTABLE"
            val manifestPresent = targetState["manifestPresent"] == true
            val artifactPresent = targetState["renderedArtifactPresent"] == true
            if (!executable || !renderMode || !manifestPresent || !artifactPresent) {
                findings += scopeFinding(
                    "TARGET_MATURITY_SNAPSHOT_TARGET_NOT_EXECUTABLE",
                    scope,
                    "Target state must be executable with manifest and rendered artifact evidence."
                )
                valid = false
            }
        }
        return valid
    }

    private fun validateEvidenceReferences(
        scope: AdapterTargetMaturityEvidenceScope,
        references: List<String>,
        codePrefix: String,
        requiredPathPrefixes: List<String>,
        findings: MutableList<TargetMaturityFinding>
    ): Boolean {
        var valid = true
        references.forEach { reference ->
            if (!validateRepositoryReference(scope, reference, codePrefix, findings)) {
                valid = false
            }
        }
        val paths = references.map { it.substringBefore('#') }
        if (paths.none { path -> requiredPathPrefixes.any(path::startsWith) }) {
            findings += scopeFinding(
                "${codePrefix}_CLASS_MISSING",
                scope,
                "Evidence must include a repository path under ${requiredPathPrefixes.joinToString(" or ")}."
            )
            valid = false
        }
        return valid
    }

    private fun validateRepositoryReference(
        scope: AdapterTargetMaturityEvidenceScope,
        reference: String,
        codePrefix: String,
        findings: MutableList<TargetMaturityFinding>
    ): Boolean {
        if (reference.startsWith("http://") || reference.startsWith("https://")) {
            findings += scopeFinding(
                "${codePrefix}_EXTERNAL",
                scope,
                "External URL cannot certify repository maturity: $reference"
            )
            return false
        }
        val relative = reference.substringBefore('#')
        if (relative.isBlank()) {
            findings += scopeFinding(
                "${codePrefix}_BLANK",
                scope,
                "Evidence reference path must not be blank."
            )
            return false
        }
        val root = rootDir.canonicalFile
        val file = File(rootDir, relative).canonicalFile
        if (file.path != root.path && !file.path.startsWith(root.path + File.separator)) {
            findings += scopeFinding(
                "${codePrefix}_ESCAPES_REPOSITORY",
                scope,
                "Evidence reference escapes the repository: $reference"
            )
            return false
        }
        if (!file.isFile) {
            findings += scopeFinding(
                "${codePrefix}_MISSING",
                scope,
                "Evidence reference does not resolve: $reference"
            )
            return false
        }
        return true
    }

    private fun targetWideStages(
        target: TargetCapability?,
        providerAvailable: Boolean
    ): List<TargetMaturityStage> = buildList {
        if (target == null) return@buildList
        add(TargetMaturityStage.DECLARED)
        if (target.topologyProfile == null) return@buildList
        add(TargetMaturityStage.ANALYZABLE)
        if (providerAvailable) add(TargetMaturityStage.RENDERABLE)
    }

    private fun structuralSupport(
        target: TargetCapability,
        kind: TargetStructuralProjectionKind
    ): SupportLevel = when (kind) {
        TargetStructuralProjectionKind.CONDITION -> target.conditions
        TargetStructuralProjectionKind.PARALLEL -> target.parallel
        TargetStructuralProjectionKind.LOOP -> target.dynamicLoops
        TargetStructuralProjectionKind.MATCH -> target.match
        TargetStructuralProjectionKind.RETRY -> target.retry
        TargetStructuralProjectionKind.ERROR_BOUNDARY -> target.errorHandlers
    }

    private fun scopeFinding(
        code: String,
        scope: AdapterTargetMaturityEvidenceScope,
        message: String
    ): TargetMaturityFinding = finding(code, scope.target, scope.scopeId, message)

    private fun finding(
        code: String,
        target: String,
        scopeId: String?,
        message: String
    ): TargetMaturityFinding = TargetMaturityFinding(code, target, scopeId, message)
}
