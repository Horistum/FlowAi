package org.flowlang.conformance

import com.fasterxml.jackson.databind.JsonNode
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

internal fun applySemanticEquivalenceMutation(
    baseline: List<SemanticObservationEvidence>,
    requirement: SemanticObservationRequirement,
    mutation: SemanticEquivalenceMutation
): List<SemanticObservationEvidence> = when (mutation) {
    SemanticEquivalenceMutation.MISSING -> baseline.filterNot { it.requirementId == requirement.id }
    SemanticEquivalenceMutation.WEAKENED -> baseline.map { evidence ->
        if (evidence.requirementId == requirement.id) {
            evidence.copy(status = SemanticObservationEvidenceStatus.WEAKENED)
        } else {
            evidence
        }
    }
    SemanticEquivalenceMutation.UNKNOWN -> baseline.map { evidence ->
        if (evidence.requirementId == requirement.id) {
            evidence.copy(status = SemanticObservationEvidenceStatus.UNKNOWN)
        } else {
            evidence
        }
    }
    SemanticEquivalenceMutation.CONTRADICTORY -> baseline.map { evidence ->
        if (evidence.requirementId == requirement.id) {
            evidence.copy(
                status = SemanticObservationEvidenceStatus.CONTRADICTORY,
                evidenceReference = "c0.3:contradictory:${requirement.id}"
            )
        } else {
            evidence
        }
    }
}

internal fun semanticIndependencePolarityErrors(
    caseId: String,
    reference: List<SemanticObservationRequirement>,
    alternateImplementation: List<SemanticObservationRequirement>,
    alternateSemanticMeaning: List<SemanticObservationRequirement>
): List<String> = buildList {
    if (reference != alternateImplementation) {
        add("Case '$caseId' observations changed when only module, action and target labels changed.")
    }
    if (reference == alternateSemanticMeaning) {
        add("Case '$caseId' observations did not change when semantic meaning changed.")
    }
}

internal fun semanticSnapshotPlanErrors(
    pairId: String,
    leftTarget: String,
    rightTarget: String,
    expectedTree: JsonNode?,
    leftPlanFile: File?,
    rightPlanFile: File?
): List<String> = buildList {
    val leftTree = leftPlanFile?.let { planFile ->
        runCatching { Json.mapper.readTree(planFile) }
    }
    val rightTree = rightPlanFile?.let { planFile ->
        runCatching { Json.mapper.readTree(planFile) }
    }

    if (leftTree?.isFailure == true) {
        add("Concrete pair '$pairId' left execution plan cannot be parsed: ${leftTree.exceptionOrNull()?.message}.")
    }
    if (rightTree?.isFailure == true) {
        add("Concrete pair '$pairId' right execution plan cannot be parsed: ${rightTree.exceptionOrNull()?.message}.")
    }

    val leftPlanTree = leftTree?.getOrNull()
    val rightPlanTree = rightTree?.getOrNull()
    if (leftPlanTree != null && rightPlanTree != null && leftPlanTree != rightPlanTree) {
        add("Concrete pair '$pairId' target snapshots do not preserve the same target-neutral execution plan.")
    }
    if (expectedTree != null && leftPlanTree != null && expectedTree != leftPlanTree) {
        add(
            "Concrete pair '$pairId' $leftTarget snapshot plan is stale relative to " +
                "the production planning path."
        )
    }
    if (expectedTree != null && rightPlanTree != null && expectedTree != rightPlanTree) {
        add(
            "Concrete pair '$pairId' $rightTarget snapshot plan is stale relative to " +
                "the production planning path."
        )
    }
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
                    val mutatedEvidence = applySemanticEquivalenceMutation(baselineEvidence, requirement, mutation)
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

                val duplicateEvidence = baselineEvidence + baselineEvidence.single {
                    it.requirementId == requirement.id
                }.copy(evidenceReference = "c0.3:${case.id}:duplicate:${requirement.id}")
                val duplicateRecords = duplicateEvidence.filter { it.requirementId == requirement.id }
                val duplicateAssessment = SemanticObservationAuthority.assess(requirements, duplicateEvidence)
                val duplicateAffected = duplicateAssessment.observations.singleOrNull {
                    it.requirement?.id == requirement.id
                }
                if (duplicateAssessment.decision.status != SemanticEquivalenceDecisionStatus.NOT_EQUIVALENT ||
                    duplicateAffected?.status != SemanticObservationEvidenceStatus.CONTRADICTORY ||
                    duplicateAffected.evidenceReferences.sorted() !=
                    duplicateRecords.map(SemanticObservationEvidence::evidenceReference).sorted()
                ) {
                    add("Case '${case.id}' duplicate evidence on '${requirement.id}' does not fail through the duplicate-evidence path.")
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
            val semanticAlternate = SemanticObservationAuthority.requirementsFor(
                SemanticEquivalencePlanFactory.plan(
                    case.fixture,
                    alternateImplementationLabels = true,
                    alternateSemanticMeaning = true
                )
            )
            addAll(
                semanticIndependencePolarityErrors(
                    case.id,
                    reference,
                    alternate,
                    semanticAlternate
                )
            )
        }
    }

    private fun concreteReferenceErrors(document: SemanticEquivalenceDocument): List<String> = buildList {
        val promotions = AdapterExecutableReferencePromotionLoader.load(rootDir).promotions
        val referenceGenerator = ReferenceSnapshotBundleGenerator(rootDir, registry)
        val implementationEvidenceAuthority = SemanticImplementationObservationAuthority(rootDir)
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
            pair.targetBackings
                .filter { it.kind == SemanticEquivalenceTargetBackingKind.BOUNDED_PROMOTION }
                .forEach { backing ->
                    if (promotions.none {
                            it.target == backing.target &&
                                it.snapshotReference == snapshotForTarget(pair, backing.target)
                        }
                    ) {
                        add(
                            "Concrete pair '${pair.id}' target '${backing.target}' evidence is not backed by " +
                                "the declared bounded promotion authority."
                        )
                    }
                }

            val left = validateSnapshot(pair.id, pair.scenarioId, pair.leftTarget, pair.leftSnapshot)
            val right = validateSnapshot(pair.id, pair.scenarioId, pair.rightTarget, pair.rightSnapshot)
            addAll(left.errors)
            addAll(right.errors)
            val productionTree = runCatching {
                Json.mapper.readTree(Json.mapper.writeValueAsBytes(ExecutionPlanCanonicalizer.canonicalize(plan)))
            }
            if (productionTree.isFailure) {
                add("Concrete pair '${pair.id}' production execution plan cannot be serialized: ${productionTree.exceptionOrNull()?.message}.")
            }
            addAll(
                semanticSnapshotPlanErrors(
                    pairId = pair.id,
                    leftTarget = pair.leftTarget,
                    rightTarget = pair.rightTarget,
                    expectedTree = productionTree.getOrNull(),
                    leftPlanFile = left.planFile,
                    rightPlanFile = right.planFile
                )
            )

            val leftProfileResult = runCatching {
                implementationEvidenceAuthority.profile(
                    plan = plan,
                    target = pair.leftTarget,
                    scenarioId = pair.scenarioId,
                    requirements = requirements
                )
            }
            val rightProfileResult = runCatching {
                implementationEvidenceAuthority.profile(
                    plan = plan,
                    target = pair.rightTarget,
                    scenarioId = pair.scenarioId,
                    requirements = requirements
                )
            }
            val leftProfile = leftProfileResult.getOrNull()
            val rightProfile = rightProfileResult.getOrNull()
            if (leftProfile == null) {
                add("Concrete pair '${pair.id}' cannot derive ${pair.leftTarget} observation evidence: ${leftProfileResult.exceptionOrNull()?.message}.")
            }
            if (rightProfile == null) {
                add("Concrete pair '${pair.id}' cannot derive ${pair.rightTarget} observation evidence: ${rightProfileResult.exceptionOrNull()?.message}.")
            }
            if (leftProfile != null) {
                addAll(renderedArtifactErrors(pair.id, pair.leftTarget, left, leftProfile))
            }
            if (rightProfile != null) {
                addAll(renderedArtifactErrors(pair.id, pair.rightTarget, right, rightProfile))
            }
            if (leftProfile != null && rightProfile != null) {
                val leftAssessment = SemanticObservationAuthority.assess(requirements, leftProfile.evidence)
                val rightAssessment = SemanticObservationAuthority.assess(requirements, rightProfile.evidence)
                if (leftAssessment.decision.status != SemanticEquivalenceDecisionStatus.EQUIVALENT ||
                    rightAssessment.decision.status != SemanticEquivalenceDecisionStatus.EQUIVALENT ||
                    leftAssessment.decision != rightAssessment.decision ||
                    leftAssessment.observations.map { it.status } != rightAssessment.observations.map { it.status }
                ) {
                    add(
                        "Concrete pair '${pair.id}' does not preserve equivalent target-backed observations: " +
                            "${pair.leftTarget}=${assessmentSummary(leftAssessment)} " +
                            "${pair.rightTarget}=${assessmentSummary(rightAssessment)}."
                    )
                }
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
            ExecutableContinuityConformanceInventory.PATH to ExecutableContinuityConformanceInventory.load(rootDir).checks,
            RealWorldCorpusConformanceChecks.INVENTORY_PATH to loadInventory(RealWorldCorpusConformanceChecks.INVENTORY_PATH),
            AbstractTopologyMatrixConformanceInventory.PATH to AbstractTopologyMatrixConformanceInventory.load(rootDir).checks
        )
        frozenInventories.forEach { (path, checks) ->
            val leaked = checks.filter { it.startsWith(CHECK_PREFIX) }
            if (leaked.isNotEmpty()) add("Frozen inventory '$path' contains C0.3 checks: $leaked.")
        }
    }

    private fun validateSnapshot(
        pairId: String,
        scenarioId: String,
        target: String,
        path: String
    ): SnapshotValidation {
        val errors = mutableListOf<String>()
        val indexFile = File(rootDir, path)
        if (!indexFile.isFile) {
            errors += "Concrete pair '$pairId' snapshot is missing: ${indexFile.path}."
            return SnapshotValidation(errors, null, null)
        }
        val snapshotResult = runCatching { Json.mapper.readValue(indexFile, ReferenceSnapshotSet::class.java) }
        val snapshot = snapshotResult.getOrNull()
        if (snapshot == null) {
            errors += "Concrete pair '$pairId' snapshot '$path' cannot be parsed: ${snapshotResult.exceptionOrNull()?.message}."
            return SnapshotValidation(errors, null, null)
        }
        ReferenceSnapshotHonesty.validate(snapshot).forEach {
            errors += "Concrete pair '$pairId' invalid snapshot '$path': $it"
        }
        if (snapshot.scenarioId != scenarioId) {
            errors += "Concrete pair '$pairId' snapshot '$path' declares scenario '${snapshot.scenarioId}', expected '$scenarioId'."
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
        var artifactFile: File? = null
        if (targetArtifacts.size != 1) {
            errors += "Concrete pair '$pairId' snapshot '$path' must declare exactly one executable '$target' artifact."
        } else {
            artifactFile = File(indexFile.parentFile, targetArtifacts.single().file)
            if (!artifactFile.isFile || artifactFile.length() == 0L) {
                errors += "Concrete pair '$pairId' executable artifact is missing or empty: ${artifactFile.path}."
                artifactFile = null
            }
        }
        val planFile = File(indexFile.parentFile, EXECUTION_PLAN_FILE)
        if (!planFile.isFile) {
            errors += "Concrete pair '$pairId' semantic plan is missing: ${planFile.path}."
        }
        return SnapshotValidation(errors, planFile.takeIf(File::isFile), artifactFile)
    }

    private fun renderedArtifactErrors(
        pairId: String,
        target: String,
        snapshot: SnapshotValidation,
        profile: SemanticImplementationObservationProfile
    ): List<String> = buildList {
        val committed = snapshot.artifactFile ?: return@buildList
        val rendering = profile.rendering
        if (profile.target != target || profile.manifest.target != target || rendering.artifact.target != target) {
            add("Concrete pair '$pairId' target-backed rendering identity does not match '$target'.")
        }
        val committedContent = runCatching { committed.readText() }
        if (committedContent.isFailure) {
            add("Concrete pair '$pairId' cannot read committed '$target' artifact: ${committedContent.exceptionOrNull()?.message}.")
        } else if (committedContent.getOrNull() != rendering.artifact.content) {
            add(
                "Concrete pair '$pairId' committed '$target' artifact is stale relative to the " +
                    "current production manifest and rendering receipt."
            )
        }
    }

    private fun assessmentSummary(assessment: SemanticEquivalenceAssessment): String =
        assessment.observations.joinToString(prefix = "[", postfix = "]") { observation ->
            "${observation.requirement?.id ?: "unexpected"}:${observation.status}"
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
        val planFile: File?,
        val artifactFile: File?
    )

    companion object {
        const val CHECK_PREFIX = "conformance.c0.3."
        private const val EXECUTION_PLAN_FILE = "execution-plan.json"
    }
}
