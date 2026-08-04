package org.flowlang.conformance

import java.io.File
import org.flowlang.adapters.portfolio.AdapterExecutableReferencePromotionLoader
import org.flowlang.adapters.yaml.IntentYamlLoader
import org.flowlang.capabilities.TargetCapability
import org.flowlang.cli.Json
import org.flowlang.intent.IntentToAstPlanner
import org.flowlang.modules.ModuleRegistry
import org.flowlang.planner.ApprovalNode
import org.flowlang.planner.ExecutionPlan
import org.flowlang.planner.ParallelGroupNode
import org.flowlang.planner.PlanBranch
import org.flowlang.planner.PlanDependencyEvidence
import org.flowlang.planner.PlanDependencyKind
import org.flowlang.planner.PlanDependencyRelation
import org.flowlang.planner.RetryGroupNode
import org.flowlang.planner.TaskNode
import org.flowlang.planner.TryPlanNode
import org.flowlang.planner.FlowPlanner
import org.flowlang.serialization.FlowYaml
import org.flowlang.topology.ExecutionTopologyDecisionStatus
import org.flowlang.topology.ExecutionTopologyDimension
import org.flowlang.topology.ExecutionTopologyEvidenceSource
import org.flowlang.topology.ExecutionTopologyEvidenceStatus
import org.flowlang.topology.ExecutionTopologyKind
import org.flowlang.topology.ExecutionTopologyMatchingAuthority
import org.flowlang.topology.ExecutionTopologyProfile
import org.flowlang.topology.ExecutionTopologySupportDeclaration
import org.flowlang.topology.ExecutionTopologySupportStatus

enum class AbstractTopologyMatrixFixture(val documentValue: String) {
    SEQUENTIAL("sequential"),
    PARALLEL("parallel"),
    RETRY("retry"),
    APPROVAL("approval"),
    FAILURE_HANDLER("failure-handler"),
    VALUE_CONTINUITY("value-continuity"),
    WORKSPACE_CONTINUITY("workspace-continuity"),
    STATE_CONTINUITY("state-continuity");

    companion object {
        fun parse(value: String): AbstractTopologyMatrixFixture =
            entries.singleOrNull { it.documentValue == value }
                ?: error("Unknown abstract topology fixture '$value'.")
    }
}

enum class AbstractTopologyMatrixMutation(val documentValue: String) {
    MISSING("missing"),
    PARTIAL("partial"),
    UNSUPPORTED("unsupported"),
    UNKNOWN("unknown"),
    CONTRADICTORY("contradictory");

    companion object {
        fun parse(value: String): AbstractTopologyMatrixMutation =
            entries.singleOrNull { it.documentValue == value }
                ?: error("Unknown abstract topology mutation '$value'.")
    }
}

data class AbstractTopologyMatrixCase(
    val id: String,
    val fixture: AbstractTopologyMatrixFixture,
    val requiredKinds: List<ExecutionTopologyKind>
) {
    init {
        require(id.isNotBlank()) { "Abstract topology matrix case id must not be blank." }
        require(requiredKinds.isNotEmpty()) { "Abstract topology matrix case '$id' must declare requiredKinds." }
        require(requiredKinds.size == requiredKinds.toSet().size) {
            "Abstract topology matrix case '$id' contains duplicate requiredKinds."
        }
    }
}

data class AbstractTopologyConcreteReference(
    val id: String,
    val target: String,
    val intent: String,
    val expectedTopologyDecision: ExecutionTopologyDecisionStatus,
    val executableSnapshot: String
) {
    init {
        require(id.isNotBlank()) { "Concrete topology reference id must not be blank." }
        require(target.isNotBlank()) { "Concrete topology reference target must not be blank." }
        require(intent.isNotBlank()) { "Concrete topology reference intent must not be blank." }
        require(executableSnapshot.isNotBlank()) { "Concrete topology reference snapshot must not be blank." }
    }
}

data class AbstractTopologyMatrixDocument(
    val version: String,
    val mutations: List<AbstractTopologyMatrixMutation>,
    val cases: List<AbstractTopologyMatrixCase>,
    val concreteReferences: List<AbstractTopologyConcreteReference>
) {
    init {
        require(version == VERSION) { "Abstract topology matrix version '$version' is unsupported; expected '$VERSION'." }
        require(mutations == AbstractTopologyMatrixMutation.entries) {
            "Abstract topology matrix mutations must be exactly ${AbstractTopologyMatrixMutation.entries.map { it.documentValue }}."
        }
        require(cases.size == AbstractTopologyMatrixFixture.entries.size) {
            "Abstract topology matrix must declare exactly one case per fixture."
        }
        require(cases.map(AbstractTopologyMatrixCase::fixture).toSet() == AbstractTopologyMatrixFixture.entries.toSet()) {
            "Abstract topology matrix fixture set is incomplete or duplicated."
        }
        require(cases.map(AbstractTopologyMatrixCase::id).size == cases.map(AbstractTopologyMatrixCase::id).toSet().size) {
            "Abstract topology matrix case ids must be unique."
        }
        require(concreteReferences.map(AbstractTopologyConcreteReference::id).size == concreteReferences.map(AbstractTopologyConcreteReference::id).toSet().size) {
            "Concrete topology reference ids must be unique."
        }
        require(concreteReferences.map(AbstractTopologyConcreteReference::target).toSet() == REQUIRED_REFERENCE_TARGETS) {
            "Concrete topology references must be exactly ${REQUIRED_REFERENCE_TARGETS.sorted()}."
        }
    }

    companion object {
        const val VERSION = "1.0"
        val REQUIRED_REFERENCE_TARGETS = setOf("jenkins", "github-actions")
    }
}

object AbstractTopologyMatrixLoader {
    const val PATH = "conformance/topology/abstract-topology-matrix.yaml"
    private val ROOT_KEYS = setOf("version", "mutations", "cases", "concreteReferences")
    private val CASE_KEYS = setOf("id", "fixture", "requiredKinds")
    private val REFERENCE_KEYS = setOf("id", "target", "intent", "expectedTopologyDecision", "executableSnapshot")

    fun load(rootDir: File = File(".")): AbstractTopologyMatrixDocument {
        val file = File(rootDir, PATH)
        require(file.isFile) { "Abstract topology matrix is missing: ${file.path}" }
        val yaml = FlowYaml.readMap(file)
        requireExactKeys(yaml, ROOT_KEYS, PATH)
        val mutations = yaml.stringList("mutations", PATH).map(AbstractTopologyMatrixMutation::parse)
        val cases = yaml.mapList("cases", PATH).mapIndexed { index, raw ->
            val path = "$PATH.cases[$index]"
            requireExactKeys(raw, CASE_KEYS, path)
            AbstractTopologyMatrixCase(
                id = raw.requiredString("id", path),
                fixture = AbstractTopologyMatrixFixture.parse(raw.requiredString("fixture", path)),
                requiredKinds = raw.stringList("requiredKinds", path).map { key ->
                    ExecutionTopologyKind.fromRegistryKey(key)
                        ?: error("$path.requiredKinds contains unknown topology kind '$key'.")
                }
            )
        }
        val references = yaml.mapList("concreteReferences", PATH).mapIndexed { index, raw ->
            val path = "$PATH.concreteReferences[$index]"
            requireExactKeys(raw, REFERENCE_KEYS, path)
            val decision = raw.requiredString("expectedTopologyDecision", path)
            AbstractTopologyConcreteReference(
                id = raw.requiredString("id", path),
                target = raw.requiredString("target", path),
                intent = raw.requiredString("intent", path),
                expectedTopologyDecision = runCatching { ExecutionTopologyDecisionStatus.valueOf(decision) }
                    .getOrElse { error("$path.expectedTopologyDecision '$decision' is unsupported.") },
                executableSnapshot = raw.requiredString("executableSnapshot", path)
            )
        }
        return AbstractTopologyMatrixDocument(
            version = yaml.requiredString("version", PATH),
            mutations = mutations,
            cases = cases,
            concreteReferences = references
        )
    }

    private fun requireExactKeys(raw: Map<String, Any?>, expected: Set<String>, path: String) {
        val unknown = raw.keys - expected
        val missing = expected - raw.keys
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
        } ?: error("$path.$key must be a list.")

    private fun Map<String, Any?>.mapList(key: String, path: String): List<Map<String, Any?>> =
        (get(key) as? Iterable<*>)?.mapIndexed { index, value ->
            (value as? Map<*, *>)?.entries?.associate { it.key.toString() to it.value }
                ?: error("$path.$key[$index] must be a mapping.")
        } ?: error("$path.$key must be a list.")
}

internal object AbstractTopologyMatrixPlanFactory {
    fun plan(fixture: AbstractTopologyMatrixFixture, alternateActionLabels: Boolean = false): ExecutionPlan {
        val primary = task("producer", alternateActionLabels)
        val consumer = task("consumer", alternateActionLabels).copy(
            dependsOn = listOf(primary.id),
            dependencies = listOf(primary.id)
        )
        return when (fixture) {
            AbstractTopologyMatrixFixture.SEQUENTIAL -> ExecutionPlan(
                flowName = fixture.documentValue,
                nodes = listOf(primary)
            )
            AbstractTopologyMatrixFixture.PARALLEL -> ExecutionPlan(
                flowName = fixture.documentValue,
                nodes = listOf(ParallelGroupNode(
                    id = "parallel",
                    branches = listOf(
                        PlanBranch("left", listOf(task("left", alternateActionLabels))),
                        PlanBranch("right", listOf(task("right", alternateActionLabels)))
                    )
                ))
            )
            AbstractTopologyMatrixFixture.RETRY -> ExecutionPlan(
                flowName = fixture.documentValue,
                nodes = listOf(RetryGroupNode(
                    id = "retry",
                    max = 3,
                    delay = "1s",
                    backoff = "fixed",
                    body = listOf(primary)
                ))
            )
            AbstractTopologyMatrixFixture.APPROVAL -> ExecutionPlan(
                flowName = fixture.documentValue,
                nodes = listOf(ApprovalNode(id = "approval"))
            )
            AbstractTopologyMatrixFixture.FAILURE_HANDLER -> ExecutionPlan(
                flowName = fixture.documentValue,
                nodes = listOf(TryPlanNode(
                    id = "protected",
                    body = listOf(primary),
                    errorHandler = listOf(task("handler", alternateActionLabels))
                ))
            )
            AbstractTopologyMatrixFixture.VALUE_CONTINUITY -> continuityPlan(
                fixture,
                primary,
                consumer,
                PlanDependencyKind.VALUE,
                "result"
            )
            AbstractTopologyMatrixFixture.WORKSPACE_CONTINUITY -> continuityPlan(
                fixture,
                primary,
                consumer,
                PlanDependencyKind.WORKSPACE,
                "workspace"
            )
            AbstractTopologyMatrixFixture.STATE_CONTINUITY -> continuityPlan(
                fixture,
                primary,
                consumer,
                PlanDependencyKind.STATE,
                "state"
            )
        }
    }

    private fun continuityPlan(
        fixture: AbstractTopologyMatrixFixture,
        producer: TaskNode,
        consumer: TaskNode,
        kind: PlanDependencyKind,
        channel: String
    ): ExecutionPlan = ExecutionPlan(
        flowName = fixture.documentValue,
        nodes = listOf(producer, consumer),
        dependencyRelations = listOf(
            PlanDependencyRelation(
                sourceNodeId = producer.id,
                targetNodeId = consumer.id,
                kind = PlanDependencyKind.ORDERING,
                evidence = PlanDependencyEvidence.DECLARED_ORDERING
            ),
            PlanDependencyRelation(
                sourceNodeId = producer.id,
                targetNodeId = consumer.id,
                kind = kind,
                channel = channel,
                evidence = PlanDependencyEvidence.MODULE_CONTRACT,
                evidenceReference = "c0.2:${fixture.documentValue}:$channel"
            )
        )
    )

    private fun task(id: String, alternate: Boolean): TaskNode = TaskNode(
        id = id,
        module = if (alternate) "alternative-module" else "standard",
        action = if (alternate) "alternative-action" else "execute",
        target = if (alternate) "alternative-target" else id
    )
}

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
            val alphaEvidence = alpha.evidence.map { listOf(it.requirementId, it.kind.name, it.status.name, it.source.name, it.evidenceReference.orEmpty()) }
            val betaEvidence = beta.evidence.map { listOf(it.requirementId, it.kind.name, it.status.name, it.source.name, it.evidenceReference.orEmpty()) }
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
                FlowPlanner(registry).plan(IntentToAstPlanner(registry).plan(IntentYamlLoader.load(intentFile)))
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
            AdapterA1ConformanceInventory.PATH to AdapterA1ConformanceInventory.load(rootDir).checks,
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
