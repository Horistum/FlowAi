package org.flowlang.conformance

import java.io.File
import org.flowlang.adapters.portfolio.AdapterExecutableReferencePromotionLoader
import org.flowlang.cli.Json
import org.flowlang.modules.ModuleRegistry
import org.flowlang.planner.ExecutionPlanCanonicalizer
import org.flowlang.serialization.FlowYaml

data class SemanticEquivalenceReport(
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

class SemanticEquivalenceAuthority(
    private val rootDir: File = File("."),
    private val registry: ModuleRegistry = ModuleRegistry.fromDirectory(File(rootDir, "modules"))
) {
    fun analyze(): SemanticEquivalenceReport {
        val document = SemanticEquivalenceLoader.load(rootDir)
        val schemaErrors = schemaErrors(document)
        val coverageErrors = coverageErrors(document)
        val polarityErrors = polarityErrors(document)
        val independenceErrors = independenceErrors(document)
        val concreteReferenceErrors = concreteReferenceErrors(document)
        val boundaryErrors = boundaryErrors()
        val all = schemaErrors + coverageErrors + polarityErrors +
            independenceErrors + concreteReferenceErrors + boundaryErrors
        return SemanticEquivalenceReport(
            status = if (all.isEmpty()) "PASS" else "FAIL",
            schemaErrors = schemaErrors,
            coverageErrors = coverageErrors,
            polarityErrors = polarityErrors,
            independenceErrors = independenceErrors,
            concreteReferenceErrors = concreteReferenceErrors,
            boundaryErrors = boundaryErrors
        )
    }

    private fun schemaErrors(document: SemanticEquivalenceDocument): List<String> = buildList {
        document.cases.forEach { case ->
            val requirements = SemanticObservationAuthority.requirementsFor(
                SemanticEquivalencePlanFactory.plan(case.fixture)
            )
            val actualKinds = requirements.map(SemanticObservationRequirement::kind).distinct()
            if (actualKinds != case.requiredKinds) {
                add(
                    "Case '${case.id}' declares ${case.requiredKinds.map { it.documentValue }} " +
                        "but production observation derivation produced ${actualKinds.map { it.documentValue }}."
                )
            }
            if (requirements.isEmpty()) {
                add("Case '${case.id}' does not derive any explicit observable requirement.")
            }
        }
    }

    private fun coverageErrors(document: SemanticEquivalenceDocument): List<String> = buildList {
        val coveredKinds = document.cases.flatMap(SemanticEquivalenceCase::requiredKinds).toSet()
        val requiredKinds = SemanticObservationKind.entries.toSet()
        if (coveredKinds != requiredKinds) {
            add(
                "C0.3 observation coverage must be exact: " +
                    "missing=${(requiredKinds - coveredKinds).map { it.documentValue }.sorted()} " +
                    "extra=${(coveredKinds - requiredKinds).map { it.documentValue }.sorted()}."
            )
        }
        SemanticObservationKind.entries.forEach { kind ->
            if (document.cases.none { kind in it.requiredKinds }) {
                add("C0.3 has no positive fixture for observation kind '${kind.documentValue}'.")
            }
        }
    }

    private fun polarityErrors(document: SemanticEquivalenceDocument): List<String> = buildList {
        document.cases.forEach { case ->
            val requirements = SemanticObservationAuthority.requirementsFor(
                SemanticEquivalencePlanFactory.plan(case.fixture)
            )
            val baselineEvidence = SemanticObservationAuthority.fullyPreserved(
                requirements,
                "c0.3:${case.id}:baseline"
            )
            val baseline = SemanticObservationAuthority.assess(requirements, baselineEvidence)
            if (baseline.decision.status != SemanticEquivalenceDecisionStatus.EQUIVALENT ||
                baseline.observations.any { it.status != SemanticObservationEvidenceStatus.PRESERVED }
            ) {
                add("Case '${case.id}' does not establish an EQUIVALENT fully preserved baseline.")
            }
            requirements.forEach { requirement ->
                document.mutations.forEach { mutation ->
                    val mutatedEvidence = mutate(baselineEvidence, requirement, mutation)
                    val assessment = SemanticObservationAuthority.assess(requirements, mutatedEvidence)
                    val affected = assessment.observations.singleOrNull { it.requirement?.id == requirement.id }
                    val expectedStatus = when (mutation) {
                        SemanticEquivalenceMutation.MISSING -> SemanticObservationEvidenceStatus.MISSING
                        SemanticEquivalenceMutation.WEAKENED -> SemanticObservationEvidenceStatus.WEAKENED
                        SemanticEquivalenceMutation.UNKNOWN -> SemanticObservationEvidenceStatus.UNKNOWN
                        SemanticEquivalenceMutation.CONTRADICTORY -> SemanticObservationEvidenceStatus.CONTRADICTORY
                    }
                    if (assessment.decision.status != SemanticEquivalenceDecisionStatus.NOT_EQUIVALENT ||
                        affected?.status != expectedStatus
                    ) {
                        add(
                            "Case '${case.id}' mutation '${mutation.documentValue}' on '${requirement.id}' " +
                                "expected NOT_EQUIVALENT/$expectedStatus but observed " +
                                "${assessment.decision.status}/${affected?.status}."
                        )
                    }
                }
            }
            val unexpected = baselineEvidence + SemanticObservationEvidence(
                requirementId = "semantic.unexpected.observation",
                fingerprint = SemanticObservationIdentity.fingerprint("unexpected"),
                status = SemanticObservationEvidenceStatus.PRESERVED,
                evidenceReference = "c0.3:${case.id}:unexpected"
            )
            val unexpectedAssessment = SemanticObservationAuthority.assess(requirements, unexpected)
            if (unexpectedAssessment.decision.status != SemanticEquivalenceDecisionStatus.NOT_EQUIVALENT ||
                unexpectedAssessment.observations.none {
                    it.requirement == null && it.status == SemanticObservationEvidenceStatus.CONTRADICTORY
                }
            ) {
                add("Case '${case.id}' accepts evidence for an observation that was never required.")
            }
        }
    }

    private fun independenceErrors(document: SemanticEquivalenceDocument): List<String> = buildList {
        document.cases.forEach { case ->
            val reference = SemanticObservationAuthority.requirementsFor(
                SemanticEquivalencePlanFactory.plan(case.fixture)
            )
            val alternate = SemanticObservationAuthority.requirementsFor(
                SemanticEquivalencePlanFactory.plan(case.fixture, alternateImplementationLabels = true)
            )
            if (reference != alternate) {
                add("Case '${case.id}' observations changed when only module, action and target labels changed.")
            }
            val referenceAssessment = SemanticObservationAuthority.assess(
                reference,
                SemanticObservationAuthority.fullyPreserved(reference, "c0.3:${case.id}:reference")
            )
            val alternateAssessment = SemanticObservationAuthority.assess(
                reference,
                SemanticObservationAuthority.fullyPreserved(reference, "c0.3:${case.id}:alternate")
            )
            if (referenceAssessment.decision != alternateAssessment.decision ||
                referenceAssessment.observations.map { it.status } != alternateAssessment.observations.map { it.status }
            ) {
                add("Case '${case.id}' equivalence decision depends on evidence-provider identity.")
            }
        }
    }

    private fun concreteReferenceErrors(document: SemanticEquivalenceDocument): List<String> = buildList {
        val promotions = AdapterExecutableReferencePromotionLoader.load(rootDir).promotions
        val referenceGenerator = ReferenceSnapshotBundleGenerator(rootDir, registry)
        document.concretePairs.forEach { pair ->
            val intentFile = File(rootDir, pair.intent)
            if (!intentFile.isFile) {
                add("Concrete pair '${pair.id}' intent is missing: ${intentFile.path}.")
                return@forEach
            }
            val planResult = runCatching { referenceGenerator.planFor(intentFile) }
            val plan = planResult.getOrNull()
            if (plan == null) {
                add("Concrete pair '${pair.id}' cannot produce a plan: ${planResult.exceptionOrNull()?.message}.")
                return@forEach
            }
            val requirements = SemanticObservationAuthority.requirementsFor(plan)
            val concreteKinds = requirements.map(SemanticObservationRequirement::kind).toSet()
            if (concreteKinds != SemanticObservationKind.entries.toSet()) {
                add(
                    "Concrete pair '${pair.id}' must exercise every C0.3 observation kind: " +
                        "observed=${concreteKinds.map { it.documentValue }.sorted()}."
                )
            }
            val forbiddenImplementationTokens = setOf(pair.leftTarget, pair.rightTarget)
            requirements.forEach { requirement ->
                val semanticText = listOf(
                    requirement.id,
                    requirement.subject,
                    requirement.value,
                    requirement.producerIdentity.orEmpty(),
                    requirement.consumerIdentity.orEmpty()
                ).joinToString(" ").lowercase()
                forbiddenImplementationTokens.filter { semanticText.contains(it.lowercase()) }.forEach { token ->
                    add("Concrete pair '${pair.id}' observation '${requirement.id}' depends on target token '$token'.")
                }
            }
            if (promotions.none {
                    it.target == GITHUB_ACTIONS &&
                        it.snapshotReference == snapshotForTarget(pair, GITHUB_ACTIONS)
                }
            ) {
                add("Concrete pair '${pair.id}' GitHub Actions evidence is not backed by the bounded A1.0 promotion authority.")
            }

            val left = validateSnapshot(pair.id, pair.leftTarget, pair.leftSnapshot)
            val right = validateSnapshot(pair.id, pair.rightTarget, pair.rightSnapshot)
            addAll(left.errors)
            addAll(right.errors)
            val productionTree = runCatching {
                Json.mapper.readTree(Json.mapper.writeValueAsBytes(ExecutionPlanCanonicalizer.canonicalize(plan)))
            }
            if (productionTree.isFailure) {
                add("Concrete pair '${pair.id}' production execution plan cannot be serialized: ${productionTree.exceptionOrNull()?.message}.")
            }
            if (left.planFile != null && right.planFile != null) {
                val leftTree = runCatching { Json.mapper.readTree(left.planFile) }
                val rightTree = runCatching { Json.mapper.readTree(right.planFile) }
                if (leftTree.isFailure) {
                    add("Concrete pair '${pair.id}' left execution plan cannot be parsed: ${leftTree.exceptionOrNull()?.message}.")
                }
                if (rightTree.isFailure) {
                    add("Concrete pair '${pair.id}' right execution plan cannot be parsed: ${rightTree.exceptionOrNull()?.message}.")
                }
                val expectedTree = productionTree.getOrNull()
                val leftPlanTree = leftTree.getOrNull()
                val rightPlanTree = rightTree.getOrNull()
                if (leftPlanTree != null && rightPlanTree != null && leftPlanTree != rightPlanTree) {
                    add("Concrete pair '${pair.id}' target snapshots do not preserve the same target-neutral execution plan.")
                }
                if (expectedTree != null && leftPlanTree != null && expectedTree != leftPlanTree) {
                    add("Concrete pair '${pair.id}' Jenkins snapshot plan is stale relative to the production planning path.")
                }
                if (expectedTree != null && rightPlanTree != null && expectedTree != rightPlanTree) {
                    add("Concrete pair '${pair.id}' GitHub Actions snapshot plan is stale relative to the production planning path.")
                }
            }

            val leftAssessment = SemanticObservationAuthority.assess(
                requirements,
                SemanticObservationAuthority.fullyPreserved(requirements, pair.leftSnapshot)
            )
            val rightAssessment = SemanticObservationAuthority.assess(
                requirements,
                SemanticObservationAuthority.fullyPreserved(requirements, pair.rightSnapshot)
            )
            if (leftAssessment.decision.status != SemanticEquivalenceDecisionStatus.EQUIVALENT ||
                rightAssessment.decision.status != SemanticEquivalenceDecisionStatus.EQUIVALENT ||
                leftAssessment.decision != rightAssessment.decision ||
                leftAssessment.observations.map { it.status } != rightAssessment.observations.map { it.status }
            ) {
                add("Concrete pair '${pair.id}' does not preserve equivalent semantic observations across both executable references.")
            }
        }
    }

    private fun boundaryErrors(): List<String> = buildList {
        val expectedKinds = listOf(
            SemanticObservationKind.EFFECT,
            SemanticObservationKind.RESULT_IDENTITY,
            SemanticObservationKind.RESULT_VALUE,
            SemanticObservationKind.CONTINUITY
        )
        if (SemanticObservationKind.entries != expectedKinds) {
            add("C0.3 observation kind order is a closed contract; expected $expectedKinds.")
        }
        val frozenInventories = mapOf(
            ConformanceSuiteInventory.PATH to ConformanceSuiteInventory.load(rootDir).preClosureChecks,
            AdapterConformanceInventory.PATH to AdapterConformanceInventory.load(rootDir).checks,
            AdapterA1ConformanceInventory.PATH to AdapterA1ConformanceInventory.load(rootDir).checks,
            RealWorldCorpusConformanceChecks.INVENTORY_PATH to loadInventory(RealWorldCorpusConformanceChecks.INVENTORY_PATH),
            AbstractTopologyMatrixConformanceInventory.PATH to AbstractTopologyMatrixConformanceInventory.load(rootDir).checks
        )
        frozenInventories.forEach { (path, checks) ->
            val leaked = checks.filter { it.startsWith(CHECK_PREFIX) }
            if (leaked.isNotEmpty()) add("Frozen inventory '$path' contains C0.3 checks: $leaked.")
        }
    }

    private fun mutate(
        baseline: List<SemanticObservationEvidence>,
        requirement: SemanticObservationRequirement,
        mutation: SemanticEquivalenceMutation
    ): List<SemanticObservationEvidence> = when (mutation) {
        SemanticEquivalenceMutation.MISSING -> baseline.filterNot { it.requirementId == requirement.id }
        SemanticEquivalenceMutation.WEAKENED -> baseline.map { evidence ->
            if (evidence.requirementId == requirement.id) evidence.copy(status = SemanticObservationEvidenceStatus.WEAKENED) else evidence
        }
        SemanticEquivalenceMutation.UNKNOWN -> baseline.map { evidence ->
            if (evidence.requirementId == requirement.id) evidence.copy(status = SemanticObservationEvidenceStatus.UNKNOWN) else evidence
        }
        SemanticEquivalenceMutation.CONTRADICTORY -> baseline + baseline.single { it.requirementId == requirement.id }.copy(
            status = SemanticObservationEvidenceStatus.CONTRADICTORY,
            evidenceReference = "c0.3:contradictory:${requirement.id}"
        )
    }

    private fun validateSnapshot(pairId: String, target: String, path: String): SnapshotValidation {
        val errors = mutableListOf<String>()
        val indexFile = File(rootDir, path)
        if (!indexFile.isFile) {
            errors += "Concrete pair '$pairId' snapshot is missing: ${indexFile.path}."
            return SnapshotValidation(errors, null)
        }
        val snapshotResult = runCatching { Json.mapper.readValue(indexFile, ReferenceSnapshotSet::class.java) }
        val snapshot = snapshotResult.getOrNull()
        if (snapshot == null) {
            errors += "Concrete pair '$pairId' snapshot '$path' cannot be parsed: ${snapshotResult.exceptionOrNull()?.message}."
            return SnapshotValidation(errors, null)
        }
        ReferenceSnapshotHonesty.validate(snapshot).forEach {
            errors += "Concrete pair '$pairId' invalid snapshot '$path': $it"
        }
        val state = snapshot.targets.singleOrNull()
        if (state == null || state.target != target || !state.executable) {
            errors += "Concrete pair '$pairId' snapshot '$path' must contain exactly one executable '$target' target state."
        }
        if (snapshot.artifacts.none {
                it.layer == ReferenceSnapshotLayer.SEMANTIC_PLAN && it.file == EXECUTION_PLAN_FILE && !it.executable
            }
        ) {
            errors += "Concrete pair '$pairId' snapshot '$path' must declare a non-executable semantic plan artifact."
        }
        val targetArtifacts = snapshot.artifacts.filter {
            it.layer == ReferenceSnapshotLayer.TARGET_PROJECTION && it.target == target && it.executable
        }
        if (targetArtifacts.size != 1) {
            errors += "Concrete pair '$pairId' snapshot '$path' must declare exactly one executable '$target' artifact."
        } else {
            val artifactFile = File(indexFile.parentFile, targetArtifacts.single().file)
            if (!artifactFile.isFile || artifactFile.length() == 0L) {
                errors += "Concrete pair '$pairId' executable artifact is missing or empty: ${artifactFile.path}."
            }
        }
        val planFile = File(indexFile.parentFile, EXECUTION_PLAN_FILE)
        if (!planFile.isFile) {
            errors += "Concrete pair '$pairId' semantic plan is missing: ${planFile.path}."
        }
        return SnapshotValidation(errors, planFile.takeIf(File::isFile))
    }

    private fun snapshotForTarget(pair: SemanticEquivalenceConcretePair, target: String): String = when (target) {
        pair.leftTarget -> pair.leftSnapshot
        pair.rightTarget -> pair.rightSnapshot
        else -> error("Concrete pair '${pair.id}' does not contain target '$target'.")
    }

    private fun loadInventory(path: String): List<String> {
        val file = File(rootDir, path)
        require(file.isFile) { "Conformance inventory is missing: ${file.path}" }
        val yaml = FlowYaml.readMap(file)
        return (yaml["checks"] as? Iterable<*>)?.map { it.toString() }
            ?: error("$path must declare checks.")
    }

    private data class SnapshotValidation(
        val errors: List<String>,
        val planFile: File?
    )

    companion object {
        const val CHECK_PREFIX = "conformance.c0.3."
        private const val EXECUTION_PLAN_FILE = "execution-plan.json"
        private const val GITHUB_ACTIONS = "github-actions"
    }
}
