package org.flowlang.conformance

import org.flowlang.frontend.FrontendCompilerComposition

import java.io.File
import org.flowlang.adapters.portfolio.AdapterExecutableReferencePromotionLoader
import org.flowlang.adapters.yaml.IntentYamlLoader
import org.flowlang.capabilities.TargetCapability
import org.flowlang.cli.Json
import org.flowlang.intent.IntentToAstPlanner
import org.flowlang.modules.ModuleRegistry
import org.flowlang.planner.FlowPlanner
import org.flowlang.serialization.FlowYaml
import org.flowlang.topology.ExecutionTopologyDecisionStatus
import org.flowlang.topology.ExecutionTopologyDimension
import org.flowlang.topology.ExecutionTopologyEvidenceStatus
import org.flowlang.topology.ExecutionTopologyKind
import org.flowlang.topology.ExecutionTopologyMatchingAuthority
import org.flowlang.topology.ExecutionTopologyProfile
import org.flowlang.topology.ExecutionTopologySupportDeclaration
import org.flowlang.topology.ExecutionTopologySupportStatus

data class AbstractTopologyMatrixReport(
    val status: String,
    val schemaErrors: List<String>,
    val coverageErrors: List<String>,
    val polarityErrors: List<String>,
    val independenceErrors: List<String>,
    val concreteReferenceErrors: List<String>,
    val boundaryErrors: List<String>
) {
    val errors: List<String> = schemaErrors + coverageErrors + polarityErrors +
        independenceErrors + concreteReferenceErrors + boundaryErrors
}

class AbstractTopologyMatrixAuthority(
    private val rootDir: File = File("."),
    private val registry: ModuleRegistry = ModuleRegistry.fromDirectory(File(rootDir, "modules")),
    private val targets: Map<String, TargetCapability>
) {
    fun analyze(): AbstractTopologyMatrixReport {
        val document = AbstractTopologyMatrixLoader.load(rootDir)
        val schemaErrors = schemaErrors(document)
        val coverageErrors = coverageErrors(document)
        val polarityErrors = polarityErrors(document)
        val independenceErrors = independenceErrors(document)
        val concreteReferenceErrors = concreteReferenceErrors(document)
        val boundaryErrors = boundaryErrors()
        val all = schemaErrors + coverageErrors + polarityErrors + independenceErrors + concreteReferenceErrors + boundaryErrors
        return AbstractTopologyMatrixReport(
            status = if (all.isEmpty()) "PASS" else "FAIL",
            schemaErrors = schemaErrors,
            coverageErrors = coverageErrors,
            polarityErrors = polarityErrors,
            independenceErrors = independenceErrors,
            concreteReferenceErrors = concreteReferenceErrors,
            boundaryErrors = boundaryErrors
        )
    }

    private fun schemaErrors(document: AbstractTopologyMatrixDocument): List<String> = buildList {
        document.cases.forEach { case ->
            val actualKinds = AbstractTopologyMatrixPlanFactory.plan(case.fixture)
                .topologyRequirements.map { it.kind }.distinct()
            if (actualKinds != case.requiredKinds) {
                add("Case '${case.id}' declares ${case.requiredKinds.map { it.registryKey }} but production planning derived ${actualKinds.map { it.registryKey }}.")
            }
        }
    }

    private fun coverageErrors(document: AbstractTopologyMatrixDocument): List<String> = buildList {
        val coveredKinds = document.cases.flatMap(AbstractTopologyMatrixCase::requiredKinds).toSet()
        val requiredKinds = ExecutionTopologyKind.entries.toSet()
        if (coveredKinds != requiredKinds) {
            add("C0.2 topology kind coverage must be exact: missing=${(requiredKinds - coveredKinds).map { it.registryKey }.sorted()} extra=${(coveredKinds - requiredKinds).map { it.registryKey }.sorted()}.")
        }
        val coveredDimensions = coveredKinds.map(ExecutionTopologyKind::dimension).toSet()
        if (coveredDimensions != ExecutionTopologyDimension.entries.toSet()) {
            add("C0.2 must cover all topology dimensions, got ${coveredDimensions.sortedBy { it.ordinal }}.")
        }
    }

    private fun polarityErrors(document: AbstractTopologyMatrixDocument): List<String> = buildList {
        document.cases.forEach { case ->
            val requirements = AbstractTopologyMatrixPlanFactory.plan(case.fixture).topologyRequirements
            val baseline = ExecutionTopologyMatchingAuthority.assess(
                requirements,
                ExecutionTopologyProfile.fullySupported("synthetic-baseline", "c0.2:baseline")
            )
            if (baseline.decision.status != ExecutionTopologyDecisionStatus.MATCHED ||
                baseline.evidence.any { it.status != ExecutionTopologyEvidenceStatus.SATISFIED }
            ) {
                add("Case '${case.id}' does not establish a fully supported MATCHED baseline.")
            }
            case.requiredKinds.forEach { kind ->
                document.mutations.forEach { mutation ->
                    val assessment = ExecutionTopologyMatchingAuthority.assess(
                        requirements,
                        mutatedProfile(kind, mutation)
                    )
                    val expectedDecision = when (mutation) {
                        AbstractTopologyMatrixMutation.PARTIAL -> ExecutionTopologyDecisionStatus.DEGRADED
                        else -> ExecutionTopologyDecisionStatus.BLOCKED
                    }
                    val expectedStatus = when (mutation) {
                        AbstractTopologyMatrixMutation.MISSING,
                        AbstractTopologyMatrixMutation.UNKNOWN -> ExecutionTopologyEvidenceStatus.UNKNOWN
                        AbstractTopologyMatrixMutation.PARTIAL -> ExecutionTopologyEvidenceStatus.DEGRADED
                        AbstractTopologyMatrixMutation.UNSUPPORTED -> ExecutionTopologyEvidenceStatus.UNSATISFIED
                        AbstractTopologyMatrixMutation.CONTRADICTORY -> ExecutionTopologyEvidenceStatus.CONTRADICTORY
                    }
                    val affected = assessment.evidence.filter { it.kind == kind }
                    if (assessment.decision.status != expectedDecision ||
                        affected.isEmpty() || affected.any { it.status != expectedStatus }
                    ) {
                        add("Case '${case.id}' mutation '${mutation.documentValue}' on '${kind.registryKey}' expected $expectedDecision/$expectedStatus but observed ${assessment.decision.status}/${affected.map { it.status }}.")
                    }
                }
            }
            val unrelated = ExecutionTopologyKind.entries.firstOrNull { it !in case.requiredKinds }
            if (unrelated != null) {
                val unrelatedAssessment = ExecutionTopologyMatchingAuthority.assess(
                    requirements,
                    mutatedProfile(unrelated, AbstractTopologyMatrixMutation.UNSUPPORTED)
                )
                if (unrelatedAssessment.decision.status != ExecutionTopologyDecisionStatus.MATCHED) {
                    add("Case '${case.id}' was changed by an unsupported topology kind it does not require: '${unrelated.registryKey}'.")
                }
            }
        }
    }

    private fun independenceErrors(document: AbstractTopologyMatrixDocument): List<String> = buildList {
        document.cases.forEach { case ->
            val first = AbstractTopologyMatrixPlanFactory.plan(case.fixture)
            val second = AbstractTopologyMatrixPlanFactory.plan(case.fixture, alternateActionLabels = true)
            if (first.topologyRequirements != second.topologyRequirements) {
                add("Case '${case.id}' topology changed when only module, action and target labels changed.")
            }
            val alpha = ExecutionTopologyMatchingAuthority.assess(
                first.topologyRequirements,
                ExecutionTopologyProfile.fullySupported("synthetic-alpha", "c0.2:independence")
            )
            val beta = ExecutionTopologyMatchingAuthority.assess(
                first.topologyRequirements,
                ExecutionTopologyProfile.fullySupported("synthetic-beta", "c0.2:independence")
            )
            val alphaEvidence = alpha.evidence.map { evidence ->
                listOf(
                    evidence.requirementId,
                    evidence.kind.name,
                    evidence.status.name,
                    evidence.source.name,
                    evidence.evidenceReference.orEmpty()
                )
            }
            val betaEvidence = beta.evidence.map { evidence ->
                listOf(
                    evidence.requirementId,
                    evidence.kind.name,
                    evidence.status.name,
                    evidence.source.name,
                    evidence.evidenceReference.orEmpty()
                )
            }
            if (alpha.decision != beta.decision || alphaEvidence != betaEvidence) {
                add("Case '${case.id}' topology result depends on synthetic target identity.")
            }
        }
    }

    private fun concreteReferenceErrors(document: AbstractTopologyMatrixDocument): List<String> = buildList {
        val promotions = AdapterExecutableReferencePromotionLoader.load(rootDir).promotions
        document.concreteReferences.forEach { reference ->
            val intentFile = File(rootDir, reference.intent)
            if (!intentFile.isFile) {
                add("Concrete reference '${reference.id}' intent is missing: ${intentFile.path}.")
                return@forEach
            }
            val planResult = runCatching {
                FlowPlanner(registry).plan(FrontendCompilerComposition.intentPlanner(registry).plan(IntentYamlLoader.load(intentFile)))
            }
            val plan = planResult.getOrNull()
            if (plan == null) {
                add("Concrete reference '${reference.id}' cannot produce a plan: ${planResult.exceptionOrNull()?.message}.")
                return@forEach
            }
            val target = targets[reference.target]
            if (target == null) {
                add("Concrete reference '${reference.id}' target '${reference.target}' is absent from the registry.")
                return@forEach
            }
            val assessment = ExecutionTopologyMatchingAuthority.assess(plan.topologyRequirements, target.topologyProfile)
            if (assessment.decision.status != reference.expectedTopologyDecision) {
                add("Concrete reference '${reference.id}' expected topology ${reference.expectedTopologyDecision} but observed ${assessment.decision.status}.")
            }
            val snapshotFile = File(rootDir, reference.executableSnapshot)
            val snapshotResult = runCatching { Json.mapper.readValue(snapshotFile, ReferenceSnapshotSet::class.java) }
            val snapshot = snapshotResult.getOrNull()
            if (snapshot == null) {
                add("Concrete reference '${reference.id}' snapshot cannot be parsed: ${snapshotResult.exceptionOrNull()?.message}.")
                return@forEach
            }
            ReferenceSnapshotHonesty.validate(snapshot).forEach { add("Concrete reference '${reference.id}' invalid snapshot: $it") }
            val state = snapshot.targets.singleOrNull()
            if (state == null || state.target != reference.target || !state.executable) {
                add("Concrete reference '${reference.id}' must point to exactly one executable '${reference.target}' snapshot state.")
            }
            if (reference.target == GITHUB_ACTIONS && promotions.none {
                    it.target == reference.target && it.snapshotReference == reference.executableSnapshot
                }
            ) {
                add("GitHub Actions concrete reference is not backed by the bounded A1.0 promotion authority.")
            }
        }
        val github = document.concreteReferences.single { it.target == GITHUB_ACTIONS }
        if (github.expectedTopologyDecision != ExecutionTopologyDecisionStatus.BLOCKED) {
            add("The GitHub Actions falsification input must retain its generic BLOCKED topology result despite bounded executable evidence.")
        }
    }

    private fun boundaryErrors(): List<String> = buildList {
        val closedKeys = listOf(
            "workflowScope",
            "branchIsolation",
            "attemptIsolation",
            "workflowLifetime",
            "suspendResume",
            "ephemeralWorkspace",
            "durableState",
            "valuePropagation",
            "workspacePropagation",
            "statePropagation",
            "failurePropagation"
        )
        if (ExecutionTopologyKind.entries.map(ExecutionTopologyKind::registryKey) != closedKeys) {
            add("C0.2 cannot add or reorder Core topology kinds; expected $closedKeys.")
        }
        val frozenInventories = mapOf(
            ConformanceSuiteInventory.PATH to ConformanceSuiteInventory.load(rootDir).preClosureChecks,
            AdapterConformanceInventory.PATH to AdapterConformanceInventory.load(rootDir).checks,
            ExecutableContinuityConformanceInventory.PATH to ExecutableContinuityConformanceInventory.load(rootDir).checks,
            RealWorldCorpusConformanceChecks.INVENTORY_PATH to loadC01Inventory()
        )
        frozenInventories.forEach { (path, checks) ->
            val leaked = checks.filter { it.startsWith(CHECK_PREFIX) }
            if (leaked.isNotEmpty()) add("Frozen inventory '$path' contains C0.2 checks: $leaked.")
        }
    }

    private fun loadC01Inventory(): List<String> {
        val file = File(rootDir, RealWorldCorpusConformanceChecks.INVENTORY_PATH)
        require(file.isFile) { "C0.1 inventory is missing: ${file.path}" }
        val yaml = FlowYaml.readMap(file)
        return (yaml["checks"] as? Iterable<*>)?.map { it.toString() }
            ?: error("C0.1 inventory must declare checks.")
    }

    private fun mutatedProfile(
        kind: ExecutionTopologyKind,
        mutation: AbstractTopologyMatrixMutation
    ): ExecutionTopologyProfile {
        val declarations = ExecutionTopologyKind.entries.mapNotNull { current ->
            if (current == kind && mutation == AbstractTopologyMatrixMutation.MISSING) return@mapNotNull null
            ExecutionTopologySupportDeclaration(
                kind = current,
                status = if (current == kind) mutation.supportStatus() else ExecutionTopologySupportStatus.SUPPORTED,
                evidenceReference = "c0.2:${mutation.documentValue}:${current.registryKey}"
            )
        }.toMutableList()
        if (mutation == AbstractTopologyMatrixMutation.CONTRADICTORY) {
            declarations += ExecutionTopologySupportDeclaration(
                kind = kind,
                status = ExecutionTopologySupportStatus.UNSUPPORTED,
                evidenceReference = "c0.2:contradictory:${kind.registryKey}:negative"
            )
        }
        return ExecutionTopologyProfile("synthetic-${mutation.documentValue}-${kind.registryKey}", declarations)
    }

    private fun AbstractTopologyMatrixMutation.supportStatus(): ExecutionTopologySupportStatus = when (this) {
        AbstractTopologyMatrixMutation.MISSING -> error("Missing mutation does not have a support status.")
        AbstractTopologyMatrixMutation.PARTIAL -> ExecutionTopologySupportStatus.PARTIAL
        AbstractTopologyMatrixMutation.UNSUPPORTED -> ExecutionTopologySupportStatus.UNSUPPORTED
        AbstractTopologyMatrixMutation.UNKNOWN -> ExecutionTopologySupportStatus.UNKNOWN
        AbstractTopologyMatrixMutation.CONTRADICTORY -> ExecutionTopologySupportStatus.SUPPORTED
    }

    companion object {
        const val CHECK_PREFIX = "conformance.c0.2."
        private const val GITHUB_ACTIONS = "github-actions"
    }
}
