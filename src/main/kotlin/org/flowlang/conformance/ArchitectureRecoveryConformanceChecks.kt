package org.flowlang.conformance

import java.io.File
import org.flowlang.adapters.maturity.AdapterTargetMaturityEvidenceLoader
import org.flowlang.adapters.maturity.AdapterTargetMaturityPublisher
import org.flowlang.adapters.maturity.AdapterTargetMaturityReport
import org.flowlang.adapters.maturity.TargetMaturityStage
import org.flowlang.adapters.portfolio.AdapterPortfolioLoader
import org.flowlang.adapters.portfolio.AdapterPortfolioRole
import org.flowlang.capabilities.SupportLevel
import org.flowlang.capabilities.TargetCapability
import org.flowlang.cli.Json
import org.flowlang.generators.manifest.TargetProjectionRegistry
import org.flowlang.generators.manifest.TargetStructuralProjectionKind
import org.flowlang.serialization.FlowYaml
import org.flowlang.targets.builtin.BuiltInTargetProjections

class AdapterTargetMaturityConformanceChecks(
    private val rootDir: File,
    private val targets: Map<String, TargetCapability>,
    private val projections: TargetProjectionRegistry
) {
    fun checks(): List<ConformanceCheck> {
        val reportResult = runCatching {
            AdapterTargetMaturityPublisher(rootDir, targets, projections).analyze()
        }
        val report = reportResult.getOrNull()
        val publicationErrors = buildList {
            reportResult.exceptionOrNull()?.let { add(it.message ?: it.javaClass.simpleName) }
            report?.findings?.forEach { finding ->
                add(
                    "${finding.code}:${finding.target}:" +
                        "${finding.scopeId.orEmpty()}:${finding.message}"
                )
            }
            if (report != null && report.status != "PASS" && report.findings.isEmpty()) {
                add("Target maturity report failed without a finding.")
            }
        }

        return listOf(
            ConformanceCheck(
                name = PUBLICATION_CHECK,
                passed = report?.status == "PASS",
                message = publicationErrors.takeIf(List<String>::isNotEmpty)?.joinToString(" | ")
            ),
            resultCheck(TARGET_WIDE_SCOPE_CHECK, runCatching { targetWideScopeErrors(report) }),
            resultCheck(STRUCTURAL_EVIDENCE_CHECK, runCatching(::structuralEvidenceErrors)),
            resultCheck(BOUNDED_EXECUTABLE_CHECK, runCatching { boundedExecutableErrors(report) })
        )
    }

    private fun targetWideScopeErrors(report: AdapterTargetMaturityReport?): List<String> = buildList {
        if (report == null) {
            add("Target maturity report is unavailable.")
            return@buildList
        }
        val expectedHighest = mapOf(
            "local" to TargetMaturityStage.ANALYZABLE,
            "jenkins" to TargetMaturityStage.RENDERABLE,
            "github-actions" to TargetMaturityStage.RENDERABLE,
            "tekton" to TargetMaturityStage.RENDERABLE,
            "argo-workflows" to TargetMaturityStage.ANALYZABLE,
            "azure-devops" to TargetMaturityStage.ANALYZABLE
        )
        val byTarget = report.assessments.associateBy { it.target }
        if (byTarget.keys != expectedHighest.keys) {
            add(
                "Target maturity publication target set differs: " +
                    "expected=${expectedHighest.keys.sorted()} observed=${byTarget.keys.sorted()}."
            )
        }
        expectedHighest.forEach { (target, highest) ->
            val assessment = byTarget[target]
            if (assessment == null) {
                add("Target maturity publication is missing '$target'.")
                return@forEach
            }
            if (assessment.highestTargetWideStage != highest) {
                add(
                    "$target target-wide maturity must stop at $highest, " +
                        "found ${assessment.highestTargetWideStage}."
                )
            }
            if (
                TargetMaturityStage.EXECUTABLE in assessment.targetWideStages ||
                TargetMaturityStage.BEHAVIORALLY_CERTIFIED in assessment.targetWideStages
            ) {
                add("$target must not publish target-wide executable or certified maturity.")
            }
            val expectedPrefix = TargetMaturityStage.entries.take(assessment.targetWideStages.size)
            if (assessment.targetWideStages != expectedPrefix) {
                add("$target target-wide maturity stages are not cumulative and ordered.")
            }
        }
    }

    private fun structuralEvidenceErrors(): List<String> = buildList {
        val portfolio = AdapterPortfolioLoader.load(rootDir).records.associateBy { it.target }
        targets.toSortedMap().forEach { (targetName, target) ->
            val record = portfolio[targetName]
            if (record?.role != AdapterPortfolioRole.TARGET_ADAPTER) return@forEach
            val catalog = projections.providerFor(targetName)?.nativeProjectionCatalog
            TargetStructuralProjectionKind.entries.forEach { kind ->
                val support = structuralSupport(target, kind)
                val owned = catalog?.hasStructuralProjection(kind) == true
                if (support == SupportLevel.SUPPORTED && !owned) {
                    add("$targetName declares ${kind.capability} SUPPORTED without provider evidence.")
                }
                if (owned && support != SupportLevel.SUPPORTED) {
                    add("$targetName provider owns ${kind.capability}, but registry declares $support.")
                }
            }
        }
    }

    private fun boundedExecutableErrors(report: AdapterTargetMaturityReport?): List<String> = buildList {
        if (report == null) {
            add("Target maturity report is unavailable.")
            return@buildList
        }
        val expectedScopes = mapOf(
            "jenkins" to "checkout-build-image",
            "github-actions" to "checkout-build-image"
        )
        val observedScopes = report.assessments
            .flatMap { assessment -> assessment.scopes.map { scope -> assessment.target to scope } }
        val observedTargets = observedScopes.map { it.first }.toSet()
        if (observedTargets != expectedScopes.keys) {
            add(
                "Scoped executable target set differs: " +
                    "expected=${expectedScopes.keys.sorted()} observed=${observedTargets.sorted()}."
            )
        }
        observedScopes.forEach { (target, scope) ->
            val expectedScope = expectedScopes[target]
            if (expectedScope == null) {
                add("Unexpected executable maturity scope '${scope.scopeId}' for '$target'.")
                return@forEach
            }
            if (scope.scopeId != expectedScope || scope.scenarioId != expectedScope) {
                add("$target maturity scope must be the exact '$expectedScope' scenario.")
            }
            if (!scope.valid) add("$target/${scope.scopeId} maturity scope is not valid.")
            if (scope.stages != TargetMaturityStage.entries.toList()) {
                add("$target/${scope.scopeId} must publish all cumulative maturity stages.")
            }
            if (scope.highestStage != TargetMaturityStage.BEHAVIORALLY_CERTIFIED) {
                add("$target/${scope.scopeId} is not behaviorally certified.")
            }
            if (scope.limitations.isEmpty()) {
                add("$target/${scope.scopeId} must preserve explicit limitations.")
            }
        }

        val document = AdapterTargetMaturityEvidenceLoader.load(rootDir)
        document.scopes.forEach { scope ->
            val snapshotFile = File(rootDir, scope.snapshotReference.substringBefore('#'))
            val snapshotResult = runCatching {
                Json.mapper.readValue(snapshotFile, ReferenceSnapshotSet::class.java)
            }
            val snapshot = snapshotResult.getOrNull()
            if (snapshot == null) {
                add(
                    "${scope.target}/${scope.scopeId} snapshot cannot be parsed: " +
                        snapshotResult.exceptionOrNull()?.message
                )
                return@forEach
            }
            ReferenceSnapshotHonesty.validate(snapshot).forEach { issue ->
                add("${scope.target}/${scope.scopeId} invalid snapshot: $issue")
            }
            if (snapshot.scenarioId != scope.scenarioId) {
                add("${scope.target}/${scope.scopeId} snapshot scenario identity drifted.")
            }
            val targetState = snapshot.targets.singleOrNull { it.target == scope.target }
            if (targetState == null || !targetState.executable) {
                add("${scope.target}/${scope.scopeId} lacks exactly one executable target state.")
            }
        }
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

    private fun resultCheck(name: String, result: Result<List<String>>): ConformanceCheck {
        val errors = result.getOrElse { listOf(it.message ?: it.javaClass.simpleName) }
        return ConformanceCheck(
            name = name,
            passed = errors.isEmpty(),
            message = errors.takeIf(List<String>::isNotEmpty)?.joinToString(" | ")
        )
    }

    companion object {
        const val PUBLICATION_CHECK =
            "architecture-recovery.ar-00.target-maturity-publication"
        const val TARGET_WIDE_SCOPE_CHECK =
            "architecture-recovery.ar-00.target-wide-scope-separation"
        const val STRUCTURAL_EVIDENCE_CHECK =
            "architecture-recovery.ar-00.structural-evidence-reconciliation"
        const val BOUNDED_EXECUTABLE_CHECK =
            "architecture-recovery.ar-00.bounded-executable-evidence"
    }
}

data class ArchitectureRecoveryConformanceInventory(
    val version: String,
    val checks: List<String>
) {
    init {
        require(version == VERSION) {
            "Architecture Recovery conformance inventory version '$version' is unsupported; expected '$VERSION'."
        }
        require(checks.isNotEmpty()) { "Architecture Recovery conformance inventory must declare checks." }
        require(checks.none(String::isBlank)) {
            "Architecture Recovery conformance inventory contains a blank check id."
        }
        require(checks.size == checks.toSet().size) {
            "Architecture Recovery conformance inventory contains duplicate check ids."
        }
    }

    companion object {
        const val PATH = "architecture-recovery/conformance/check-inventory.yaml"
        const val VERSION = "1.0"
        private val KEYS = setOf("version", "checks")

        fun load(rootDir: File): ArchitectureRecoveryConformanceInventory {
            val file = File(rootDir, PATH)
            require(file.isFile) {
                "Architecture Recovery conformance inventory is missing: ${file.path}"
            }
            val yaml = FlowYaml.readMap(file)
            val unknown = yaml.keys - KEYS
            val missing = KEYS - yaml.keys
            require(unknown.isEmpty()) { "$PATH has unknown fields: ${unknown.sorted()}." }
            require(missing.isEmpty()) { "$PATH is missing fields: ${missing.sorted()}." }
            val checks = (yaml["checks"] as? Iterable<*>)?.mapIndexed { index, value ->
                (value as? String)?.takeIf(String::isNotBlank)
                    ?: error("$PATH.checks[$index] must be non-blank text.")
            } ?: error("$PATH.checks must be a list.")
            return ArchitectureRecoveryConformanceInventory(
                version = yaml["version"] as? String
                    ?: error("$PATH.version must be text."),
                checks = checks
            )
        }
    }
}

class ArchitectureRecoveryConformanceRunner(
    private val rootDir: File,
    private val targets: Map<String, TargetCapability>,
    private val projections: TargetProjectionRegistry = BuiltInTargetProjections.registry
) {
    fun checks(): List<ConformanceCheck> {
        val produced = AdapterTargetMaturityConformanceChecks(rootDir, targets, projections).checks() +
            Ar01CompilerAxisConformanceChecks(rootDir).checks() +
            Ar02FlowSensitiveConformanceChecks(rootDir).checks() +
            Ar02ExplicitMergeConformanceChecks(rootDir).checks() +
            Ar02WorkflowOwnershipConformanceChecks(rootDir).checks() +
            Ar02WorkflowFailureConformanceChecks(rootDir).checks() +
            Ar02IntegratedSemanticClosureChecks(rootDir).checks()
        val inventoryResult = runCatching { ArchitectureRecoveryConformanceInventory.load(rootDir) }
        val inventory = inventoryResult.getOrNull()
        val observed = produced.map { it.name }
        val exact = inventory != null && inventory.checks == observed
        val message = when {
            inventoryResult.isFailure -> inventoryResult.exceptionOrNull()?.message
            !exact -> "declared=${inventory?.checks?.joinToString()} observed=${observed.joinToString()}"
            else -> null
        }
        return listOf(
            ConformanceCheck(
                name = INVENTORY_CHECK,
                passed = exact,
                message = message
            )
        ) + produced
    }

    companion object {
        const val INVENTORY_CHECK = "architecture-recovery.conformance.inventory-exact"
    }
}
